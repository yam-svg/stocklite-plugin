package com.stocklite.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.service.MarketDataService.AStockSector
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.TriStateRowSorter
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.LocalTime
import java.time.ZoneId
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * A股板块标签页：行业 / 概念 / 地域板块全量涨跌幅一览（东财 clist 接口）。
 * 列：名称、涨跌幅、上涨/下跌家数、主力净流入、换手率、领涨股及其涨幅。
 * 支持分类切换、名称筛选、点表头排序（默认涨跌幅降序）、右键跳转东财板块页。
 */
class SectorPanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener {

    private val state get() = StockliteState.getInstance()
    private val shZone get() = ZoneId.of("Asia/Shanghai")

    /** 分类下拉索引 → 东财板块分类值：0 行业(t:2) / 1 概念(t:3) / 2 地域(t:1) */
    private val categoryTs = intArrayOf(2, 3, 1)
    private var categoryIdx = 0
    private var allSectors: List<AStockSector> = emptyList()
    private var rows: List<AStockSector> = emptyList()
    private var fetching = false
    private var pendingFetch = false
    private var lastLoadFailed = false
    private var panelActive = true
    private var refreshTimer: Timer? = null

    private val statusLabel = JLabel("--").apply {
        font = font.deriveFont(11f); foreground = Color(0x888aaa)
    }
    private val categoryCombo = JComboBox<String>()
    private var updatingCombo = false
    private val searchField = JTextField(12)
    private lateinit var refreshBtn: JButton
    private lateinit var filterLabel: JLabel

    /** 点领涨股列弹出的分时/日K图，与股票、新股标签页同款 */
    private val chartPanel = InlineChartPanel()

    // ══════════════════ 列定义 ══════════════════

    private data class SecCol(val key: String, val title: String, val type: QuoteColumnType)

    private val cols get() = listOf(
        SecCol("name",      L10n.colName,       QuoteColumnType.PLAIN),
        SecCol("changePct", L10n.colChangePct,  QuoteColumnType.PCT),
        SecCol("upCount",   L10n.colUpCount,    QuoteColumnType.PLAIN),
        SecCol("downCount", L10n.colDownCount,  QuoteColumnType.PLAIN),
        SecCol("mainFlow",  L10n.colMainFlow,   QuoteColumnType.PNL),
        SecCol("turnover",  L10n.colTurnover,   QuoteColumnType.PLAIN),
        SecCol("leader",    L10n.colLeader,     QuoteColumnType.PLAIN),
        SecCol("leaderPct", L10n.colLeaderPct,  QuoteColumnType.PCT),
    )

    /** NaN → null（缺失值在排序中沉底、渲染为 --） */
    private fun num(v: Double): Double? = if (v.isFinite()) v else null

    private fun valueAt(row: AStockSector, key: String): Any? = when (key) {
        "name"      -> row.name
        "changePct" -> num(row.changePct)
        "upCount"   -> row.upCount
        "downCount" -> row.downCount
        "mainFlow"  -> row.mainInflow
        "turnover"  -> num(row.turnoverRate)
        "leader"    -> row.leaderName.ifEmpty { "--" }
        "leaderPct" -> num(row.leaderPct)
        else        -> ""
    }

    private val model = object : AbstractTableModel() {
        override fun getRowCount()           = rows.size
        override fun getColumnCount()        = cols.size
        override fun getColumnName(col: Int) = cols[col].title
        override fun getValueAt(row: Int, col: Int) = valueAt(rows[row], cols[col].key)
    }
    private val table = JBTable(model)

    /** 主力净流入：按 万亿/亿/万 自动换算（与大盘概览成交额口径一致），红涨绿跌着色 */
    private inner class MainFlowRenderer : QuoteRenderer(QuoteColumnType.PNL) {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) text = when (value) {
                is Double -> fmtYuanBig(value)
                null      -> "--"
                else      -> value.toString()
            }
            return comp
        }
    }

    /** 换手率：无涨跌语义不着色，固定两位小数带 % */
    private inner class TurnoverRenderer : QuoteRenderer(QuoteColumnType.PLAIN) {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) text = when (value) {
                is Double -> "%.2f%%".format(value)
                null      -> "--"
                else      -> value.toString()
            }
            return comp
        }
    }

    private fun fmtYuanBig(v: Double): String {
        if (v == 0.0) return "--"
        val abs = kotlin.math.abs(v)
        return when {
            abs >= 1e12 -> "%.2f万亿".format(v / 1e12)
            abs >= 1e8  -> "%.1f亿".format(v / 1e8)
            abs >= 1e4  -> "%.1f万".format(v / 1e4)
            else        -> "%.0f".format(v)
        }
    }

    // ══════════════════ 构建 ══════════════════

    init {
        state.addLanguageListener(this)
        state.addFeatureToggleListener(this)

        refreshBtn = JButton(L10n.btnRefresh).apply { addActionListener { fetchAsync() } }
        filterLabel = JLabel(L10n.sectorFilterHint).apply { font = font.deriveFont(11f) }
        categoryCombo.addActionListener {
            if (!updatingCombo && categoryCombo.selectedIndex >= 0) {
                categoryIdx = categoryCombo.selectedIndex
                maybeFetch()
            }
        }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  { rebuildRows() }
            override fun removeUpdate(e: DocumentEvent)  { rebuildRows() }
            override fun changedUpdate(e: DocumentEvent) { rebuildRows() }
        })

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(categoryCombo); add(refreshBtn)
            add(filterLabel); add(searchField)
            add(statusLabel)
        }

        setupTable()
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        add(chartPanel, BorderLayout.SOUTH)

        rebuildCategoryCombo()
        updateTexts()
        refreshEmptyText()   // 启动即接管空态文案，避免闪现 JBTable 内置的 "Nothing to show"
        if (state.enableSectorPanel) scheduleRefresh()

        addHierarchyListener {
            val showing = isShowing
            if (showing != panelActive) {
                panelActive = showing
                if (showing) fetchAsync()
            }
        }
    }

    private fun newSorter() = TriStateRowSorter(model).also { s ->
        cols.forEachIndexed { i, c ->
            if (c.key != "name" && c.key != "leader") s.setComparator(i, TriStateRowSorter.numericComparator)
        }
        // 默认按涨跌幅降序：涨得最猛的板块排最上面
        s.sortKeys = listOf(RowSorter.SortKey(1, SortOrder.DESCENDING))
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.fillsViewportHeight = true
        centerTableHeader(table)
        table.rowSorter = newSorter()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) return
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                val key = cols[viewCol].key
                if (key != "leader" && key != "leaderPct") return
                val modelRow = try { table.convertRowIndexToModel(viewRow) } catch (_: Exception) { -1 }
                if (modelRow < 0 || modelRow >= rows.size) return
                val sec = rows[modelRow]
                val symbol = leaderSymbol(sec)
                if (symbol.isEmpty() || sec.leaderName.isEmpty()) return
                chartPanel.showChart(
                    displayName   = sec.leaderName,
                    displaySymbol = symbol,
                    changePercent = if (sec.leaderPct.isFinite()) sec.leaderPct else 0.0,
                    prevClose     = 0.0,
                    fetchData     = { ChartDataService.getStockIntraday(symbol) }
                )
            }
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showRowMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showRowMenu(e) }
        })
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && (cols[col].key == "leader" || cols[col].key == "leaderPct"))
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    /** 领涨股 6 位代码 → 新浪格式 symbol；北交所（4/8 开头）优先按代码前缀，其余按 f141 市场位（1=沪） */
    private fun leaderSymbol(sec: AStockSector): String = when {
        sec.leaderCode.length != 6 -> ""
        sec.leaderCode.startsWith("4") || sec.leaderCode.startsWith("8") -> "bj" + sec.leaderCode
        sec.leaderMarket == 1      -> "sh" + sec.leaderCode
        else                       -> "sz" + sec.leaderCode
    }

    // ══════════════════ 数据 ══════════════════

    /** 抓取进行中被触发（如切分类）时先记一笔，完成后自动补抓一次 */
    private fun maybeFetch() {
        if (fetching) pendingFetch = true else fetchAsync()
    }

    fun fetchAsync() {
        if (!panelActive || fetching) return
        fetching = true
        refreshEmptyText()
        val t = categoryTs[categoryIdx]
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = MarketDataService.getSectorQuotes(t)
            fetching = false
            SwingUtilities.invokeLater {
                val now = LocalTime.now(shZone)
                lastLoadFailed = list == null
                if (list == null) {
                    // 接口失败：保留上次数据，仅状态栏标红
                    statusLabel.text = L10n.sectorLoadFailed
                    statusLabel.foreground = QuoteRenderer.negativeColor(state.colorScheme) ?: Color(0xE53935)
                } else {
                    allSectors = list
                    rebuildRows()
                    val up = list.count { it.changePct > 0 }
                    val down = list.count { it.changePct < 0 }
                    val summary = L10n.sectorSummary
                        .replace("{0}", list.size.toString())
                        .replace("{1}", up.toString())
                        .replace("{2}", down.toString())
                    statusLabel.text = "$summary · ${L10n.lblLastUpdate} %02d:%02d".format(now.hour, now.minute)
                    statusLabel.foreground = Color(0x888aaa)
                }
                refreshEmptyText()
                if (pendingFetch) { pendingFetch = false; fetchAsync() }
            }
        }
    }

    private fun rebuildRows() {
        val q = searchField.text.trim()
        rows = if (q.isEmpty()) allSectors else allSectors.filter { it.name.contains(q, ignoreCase = true) }
        model.fireTableDataChanged()
        refreshEmptyText()
    }

    /** 空表覆盖文案：加载中 / 加载失败 / 筛选无匹配 / 无数据，替掉 JBTable 默认的 "Nothing to show" */
    private fun refreshEmptyText() {
        if (rows.isNotEmpty()) return
        table.emptyText.text = when {
            fetching              -> L10n.sectorLoading
            lastLoadFailed        -> L10n.sectorLoadFailed
            allSectors.isNotEmpty() -> L10n.sectorNoMatch
            else                  -> L10n.sectorEmpty
        }
    }

    // ══════════════════ 右键菜单 ══════════════════

    private fun showRowMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= rows.size) return
        val sec = rows[modelRow]
        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { mi ->
            mi.addActionListener { BrowserUtil.browse("https://quote.eastmoney.com/bk/90.${sec.code}.html") } })
        popup.add(JMenuItem(L10n.btnCopyName).also { mi ->
            mi.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sec.name), null)
            } })
        popup.add(JMenuItem(L10n.btnCopySymbol).also { mi ->
            mi.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sec.code), null)
            } })
        popup.show(table, e.x, e.y)
    }

    // ══════════════════ 语言 ══════════════════

    private fun rebuildCategoryCombo() {
        updatingCombo = true
        categoryCombo.removeAllItems()
        listOf(L10n.sectorCatIndustry, L10n.sectorCatConcept, L10n.sectorCatRegion)
            .forEach { categoryCombo.addItem(it) }
        categoryCombo.selectedIndex = categoryIdx
        updatingCombo = false
    }

    private fun updateTexts() {
        refreshBtn.text = L10n.btnRefresh
        filterLabel.text = L10n.sectorFilterHint
        rebuildCategoryCombo()
        model.fireTableStructureChanged()
        table.rowSorter = newSorter()
        cols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = when (col.key) {
                    "mainFlow" -> MainFlowRenderer()
                    "turnover" -> TurnoverRenderer()
                    else       -> QuoteRenderer(col.type)
                }
            }
        }
        model.fireTableDataChanged()
        refreshEmptyText()
        revalidate(); repaint()
    }

    override fun onLanguageChanged() = updateTexts()

    override fun onFeatureToggleChanged() {
        if (state.enableSectorPanel) {
            if (refreshTimer == null) scheduleRefresh()
            fetchAsync()
        } else {
            refreshTimer?.stop()
            refreshTimer = null
        }
    }

    private fun scheduleRefresh() {
        refreshTimer = Timer(60_000) { fetchAsync() }.also { it.isRepeats = true; it.start() }
    }
}
