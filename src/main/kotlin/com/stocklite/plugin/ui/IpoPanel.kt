package com.stocklite.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.service.MarketDataService.IpoInfo
import com.stocklite.plugin.state.StockQuote
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.TriStateRowSorter
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.AlertManager
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.AbstractTableModel

/**
 * 新股标签页：
 * 上方 = 新股申购日历（东财 RPTA_APP_IPOAPPLY，今日可申购气泡提醒）
 * 下方 = 新股/次新股表现（近90天上市，实时行情，列与股票标签页同款渲染器，点涨跌幅展开走势图）
 */
class IpoPanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener {

    private val state get() = StockliteState.getInstance()
    private val shZone get() = ZoneId.of("Asia/Shanghai")

    /** 筛选下拉索引：0 全部 / 1 今日申购 / 2 本周申购 / 3 待上市 */
    private var filterIdx = 0
    private var ipoList: List<IpoInfo> = emptyList()
    private var calRows: List<IpoInfo> = emptyList()
    private var recentRows: List<IpoInfo> = emptyList()
    private val recentQuotes = mutableMapOf<String, StockQuote>()
    private var panelActive = true
    private var fetching = false
    private var refreshTimer: Timer? = null

    private val chartPanel = InlineChartPanel()

    private val statusLabel = JLabel("--").apply {
        font = font.deriveFont(11f); foreground = Color(0x888aaa)
    }
    private val filterCombo = JComboBox<String>()
    private var updatingCombo = false
    private lateinit var titleLabel: JLabel
    private lateinit var refreshBtn: JButton
    private lateinit var recentHeader: JLabel

    // ══════════════════ 上方：申购日历表 ══════════════════

    private val calCols get() = listOf(
        Triple("applyDate",  L10n.colApplyDate)   { it: IpoInfo -> it.applyDate.ifEmpty { "--" } },
        Triple("applyCode",  L10n.colApplyCode)   { it: IpoInfo -> it.applyCode },
        Triple("name",       L10n.colName)        { it: IpoInfo -> markPrefix(it) + it.name },
        Triple("symbol",     L10n.colSymbol)      { it: IpoInfo -> it.sinaSymbol },
        Triple("issuePrice", L10n.colIssuePrice)  { it: IpoInfo -> it.issuePrice?.let { p -> Fmt.price(p) } ?: "--" },
        Triple("issuePe",    L10n.colIssuePe)     { it: IpoInfo -> it.issuePe?.let { p -> "%.2f".format(p) } ?: "--" },
        Triple("raiseFunds", L10n.colRaiseFunds)  { it: IpoInfo -> it.raiseFunds?.let { f -> "%.1f".format(f) } ?: "--" },
        Triple("maxApply",   L10n.colMaxApply)    { it: IpoInfo -> it.maxApplyQty?.let { q -> "%,d".format(q) } ?: "--" },
        Triple("listingDate",L10n.colListingDate) { it: IpoInfo -> it.listingDate ?: L10n.ipoStatusWaitListing },
        Triple("status",     L10n.colStatus)      { it: IpoInfo -> statusOf(it) },
    )

    private val calModel = object : AbstractTableModel() {
        override fun getRowCount()           = calRows.size
        override fun getColumnCount()        = calCols.size
        override fun getColumnName(col: Int) = calCols[col].second
        override fun getValueAt(row: Int, col: Int) = calCols[col].third(calRows[row])
    }
    private val calTable = JBTable(calModel)

    /** 状态列渲染：今日申购着色加粗，待上市置灰 */
    private inner class StatusRenderer : QuoteRenderer(QuoteColumnType.PLAIN) {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            if (!isSelected) {
                val scheme = state.colorScheme
                when (value?.toString()) {
                    L10n.ipoStatusToday -> {
                        foreground = QuoteRenderer.positiveColor(scheme) ?: table.foreground
                        font = font.deriveFont(Font.BOLD)
                    }
                    L10n.ipoStatusWaitListing -> { foreground = QuoteRenderer.FLAT; font = font.deriveFont(Font.PLAIN) }
                    else -> { foreground = table.foreground; font = font.deriveFont(Font.PLAIN) }
                }
            }
            return comp
        }
    }

    // ══════════════════ 下方：新股/次新股表现表 ══════════════════

    private data class RecCol(
        val key: String, val title: String, val type: QuoteColumnType,
        val getValue: (IpoInfo, StockQuote?) -> Any
    )

    /** 现价较发行价涨幅（%），发行价或现价缺失返回 0（渲染为 --） */
    private fun vsIssuePct(it: IpoInfo, q: StockQuote?): Double {
        val p = q?.price ?: 0.0
        val ip = it.issuePrice ?: 0.0
        return if (p > 0 && ip > 0) (p / ip - 1) * 100 else 0.0
    }

    private val recCols get() = listOf(
        RecCol("name",       L10n.colName,       QuoteColumnType.PLAIN) { it, _ -> markPrefix(it) + it.name },
        RecCol("symbol",     L10n.colSymbol,     QuoteColumnType.PLAIN) { it, _ -> it.sinaSymbol },
        RecCol("price",      L10n.colPrice,      QuoteColumnType.PRICE) { _, q -> q?.price ?: 0.0 },
        RecCol("changePct",  L10n.colChangePct,  QuoteColumnType.PCT)   { _, q -> q?.changePercent ?: 0.0 },
        RecCol("vsIssue",    L10n.colVsIssue,    QuoteColumnType.PCT)   { it, q -> vsIssuePct(it, q) },
        RecCol("issuePrice", L10n.colIssuePrice, QuoteColumnType.PRICE) { it, _ -> it.issuePrice ?: 0.0 },
        RecCol("firstDay",   L10n.colFirstDay,   QuoteColumnType.PCT)   { it, _ -> it.firstDayChangePct ?: 0.0 },
        RecCol("listingDate",L10n.colListingDate,QuoteColumnType.PLAIN) { it, _ -> it.listingDate ?: "--" },
    )

    private val recModel = object : AbstractTableModel() {
        override fun getRowCount()           = recentRows.size
        override fun getColumnCount()        = recCols.size
        override fun getColumnName(col: Int) = recCols[col].title
        override fun getColumnClass(col: Int) =
            if (recCols[col].type != QuoteColumnType.PLAIN) Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val it = recentRows[row]
            return recCols[col].getValue(it, recentQuotes[it.sinaSymbol])
        }
    }
    private val recTable = JBTable(recModel)

    // ══════════════════ 构建 ══════════════════

    init {
        state.addLanguageListener(this)
        state.addFeatureToggleListener(this)

        titleLabel = JLabel(L10n.lblIpoCalendar).apply { font = font.deriveFont(Font.BOLD, 12f) }
        refreshBtn = JButton(L10n.btnRefresh).apply { addActionListener { fetchAsync() } }
        filterCombo.addActionListener {
            if (!updatingCombo && filterCombo.selectedIndex >= 0) {
                filterIdx = filterCombo.selectedIndex
                rebuildRows()
            }
        }
        recentHeader = JLabel(L10n.lblRecentNew).apply { font = font.deriveFont(Font.BOLD, 12f) }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(titleLabel); add(refreshBtn)
            add(JLabel(L10n.lblFilter))
            add(filterCombo)
            add(statusLabel)
        }

        setupCalTable()
        setupRecTable()

        val calScroll = JBScrollPane(calTable).apply { border = javax.swing.BorderFactory.createEmptyBorder() }
        val bottom = JPanel(BorderLayout()).apply {
            val header = JPanel(BorderLayout()).apply {
                border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 2, 8)
                add(recentHeader, BorderLayout.WEST)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(recTable).apply { border = javax.swing.BorderFactory.createEmptyBorder() },
                BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, calScroll, bottom).apply {
            resizeWeight = 0.5
            dividerLocation = 260
            isContinuousLayout = true
            dividerSize = 8
            border = javax.swing.BorderFactory.createEmptyBorder()
            // 主题默认的拖拽把柄（圆点/立体色带）比较显眼，换成一条与主题边框色一致的细线。
            // 覆写 paint 后默认边框与圆点都不会被绘制，拖拽逻辑仍由 BasicSplitPaneDivider 负责
            ui = object : javax.swing.plaf.basic.BasicSplitPaneUI() {
                override fun createDefaultDivider() =
                    object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                        override fun paint(g: java.awt.Graphics) {
                            g.color = com.intellij.ui.JBColor.border()
                            g.drawLine(0, height / 2, width, height / 2)
                        }
                    }
            }
        }
        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(chartPanel, BorderLayout.SOUTH)

        rebuildFilterCombo()
        updateTexts()
        if (state.enableIpoPanel) scheduleRefresh()

        addHierarchyListener {
            val showing = isShowing
            if (showing != panelActive) {
                panelActive = showing
                if (showing) fetchAsync()
            }
        }
    }

    /** 日历表排序器：表中按数值比较的列（列值均为字符串，默认字典序会排错，如 "150" < "55"）。
     *  注意：本函数在 init 阶段即被调用，依赖的常量必须内置于函数，不能是声明在 init 之后的字段 */
    private fun newCalSorter() = TriStateRowSorter(calModel).also { s ->
        val numericCalCols = setOf("issuePrice", "issuePe", "raiseFunds", "maxApply")
        calCols.forEachIndexed { i, c ->
            if (c.first in numericCalCols) s.setComparator(i, TriStateRowSorter.numericComparator)
        }
    }

    /** 次新股表的全部数值列（模型值已是 Double，显式挂比较器以防不同运行时的类型退化） */
    private fun newRecSorter() = TriStateRowSorter(recModel).also { s ->
        recCols.forEachIndexed { i, c ->
            if (c.type != QuoteColumnType.PLAIN) s.setComparator(i, TriStateRowSorter.numericComparator)
        }
    }

    private fun setupCalTable() {
        calTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        calTable.rowHeight = 24
        calTable.fillsViewportHeight = true
        centerTableHeader(calTable)
        calTable.rowSorter = newCalSorter()
        calTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showCalMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showCalMenu(e) }
        })
        calTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                // 发行市盈率列悬浮显示行业市盈率对比
                val viewRow = calTable.rowAtPoint(e.point)
                val viewCol = calTable.columnAtPoint(e.point)
                calTable.toolTipText = null
                if (viewRow < 0 || viewCol < 0 || calCols[viewCol].first != "issuePe") return
                val modelRow = try { calTable.convertRowIndexToModel(viewRow) } catch (_: Exception) { -1 }
                if (modelRow < 0 || modelRow >= calRows.size) return
                val it = calRows[modelRow]
                val ip = it.industryPe ?: return
                val cmp = it.issuePe?.let { p ->
                    if (p > ip) L10n.ipoPeAbove else L10n.ipoPeBelow
                } ?: ""
                calTable.toolTipText = L10n.ipoTooltipIndustryPe + "%.2f".format(ip) + cmp
            }
        })
    }

    private fun setupRecTable() {
        recTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        recTable.rowHeight = 24
        recTable.fillsViewportHeight = true
        centerTableHeader(recTable)
        recTable.rowSorter = newRecSorter()
        recTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) return
                val viewRow = recTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = recTable.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                val modelRow = recTable.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= recentRows.size) return
                if (recCols[viewCol].key != "changePct") return
                val it = recentRows[modelRow]
                val q = recentQuotes[it.sinaSymbol]
                chartPanel.showChart(
                    displayName   = it.name,
                    displaySymbol = it.sinaSymbol,
                    changePercent = q?.changePercent ?: 0.0,
                    prevClose     = q?.prevClose ?: 0.0,
                    fetchData     = { ChartDataService.getStockIntraday(it.sinaSymbol) }
                )
            }
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showRecMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showRecMenu(e) }
        })
        recTable.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = recTable.columnAtPoint(e.point)
                recTable.cursor = if (col >= 0 && recCols[col].key == "changePct")
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    // ══════════════════ 数据 ══════════════════

    fun fetchAsync() {
        if (!panelActive || !state.enableIpoPanel || fetching) return
        fetching = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val list = MarketDataService.getIpoList()
            val syms = list?.filter { it.listed }?.map { it.sinaSymbol } ?: emptyList()
            val quotes = if (syms.isNotEmpty()) MarketDataService.getStockQuotes(syms) else emptyMap()
            fetching = false
            SwingUtilities.invokeLater {
                val now = java.time.LocalTime.now(shZone)
                if (list == null) {
                    // 接口失败：保留上次数据，仅状态栏标红
                    statusLabel.text = L10n.ipoLoadFailed
                    statusLabel.foreground = QuoteRenderer.negativeColor(state.colorScheme) ?: Color(0xE53935)
                } else {
                    ipoList = list
                    recentQuotes.clear(); recentQuotes.putAll(quotes)
                    rebuildRows()
                    statusLabel.text = if (list.isEmpty()) L10n.lblNoIpo
                        else "${L10n.lblLastUpdate} %02d:%02d".format(now.hour, now.minute)
                    statusLabel.foreground = Color(0x888aaa)
                    checkTodayAlerts(list)
                }
            }
        }
    }

    /** 今日可申购气泡提醒：同一代码一天只弹一次（ipoNotifiedDates 持久化去重） */
    private fun checkTodayAlerts(list: List<IpoInfo>) {
        if (!state.enableIpoPanel) return
        val today = LocalDate.now(shZone).toString()
        list.filter { it.applyDate == today }.forEach {
            if (state.ipoNotifiedDates[it.pureCode] != today) {
                AlertManager.notifyIpoApplyToday(it.name, it.applyCode, it.issuePrice)
                state.ipoNotifiedDates[it.pureCode] = today
            }
        }
    }

    private fun rebuildRows() {
        val today = LocalDate.now(shZone)
        val todayStr = today.toString()
        val weekEnd = today.plusDays(6).toString()
        calRows = ipoList.filter { !it.listed }.filter { info ->
            when (filterIdx) {
                1 -> info.applyDate == todayStr
                2 -> info.applyDate >= todayStr && info.applyDate <= weekEnd
                3 -> info.applyDate < todayStr
                else -> true
            }
        }
        // 默认按上市日倒序：最新上市的在最上面，发行越早越靠后
        recentRows = ipoList.filter { it.listed }
            .sortedByDescending { it.listingDate ?: "" }
        calModel.fireTableDataChanged()
        recModel.fireTableDataChanged()
    }

    private fun statusOf(it: IpoInfo): String {
        val today = LocalDate.now(shZone)
        val todayStr = today.toString()
        if (it.applyDate == todayStr) return L10n.ipoStatusToday
        val d = runCatching { LocalDate.parse(it.applyDate) }.getOrNull()
        return when {
            d != null && d.isAfter(today) ->
                L10n.ipoStatusInDays(ChronoUnit.DAYS.between(today, d).toInt())
            else -> L10n.ipoStatusWaitListing
        }
    }

    private fun markPrefix(it: IpoInfo) =
        if (state.ipoAppliedCodes.contains(it.pureCode)) "✓ " else ""

    // ══════════════════ 右键菜单 ══════════════════

    private fun addToStocks(it: IpoInfo) {
        if (state.stocks.any { s -> s.symbol == it.sinaSymbol }) {
            JOptionPane.showMessageDialog(this, "${it.name} ${L10n.biStExists}",
                L10n.btnAddStock, JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val groupId = state.stockGroups.firstOrNull()?.id
            ?: state.createStockGroup(L10n.ipoDefaultGroup).id
        state.createStock(it.sinaSymbol, it.name, groupId, 0.0, 0.0)
    }

    private fun toggleApplied(it: IpoInfo) {
        if (!state.ipoAppliedCodes.remove(it.pureCode)) state.ipoAppliedCodes.add(it.pureCode)
        calModel.fireTableDataChanged()
        recModel.fireTableDataChanged()
    }

    private fun buildIpoUrl(it: IpoInfo): String = when {
        it.sinaSymbol.startsWith("sh") || it.sinaSymbol.startsWith("sz") ->
            "https://quote.eastmoney.com/${it.sinaSymbol.uppercase()}.html"
        it.sinaSymbol.startsWith("bj") ->
            "https://quote.eastmoney.com/bj/${it.pureCode}.html"
        else -> "https://quote.eastmoney.com/${it.sinaSymbol.uppercase()}.html"
    }

    private fun rowMenu(info: IpoInfo): JPopupMenu {
        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.btnAddStock).also { mi ->
            mi.addActionListener { addToStocks(info) } })
        popup.add(JMenuItem(
            if (state.ipoAppliedCodes.contains(info.pureCode)) L10n.btnUnmarkApplied else L10n.btnMarkApplied
        ).also { mi -> mi.addActionListener { toggleApplied(info) } })
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnCopySymbol).also { mi ->
            mi.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(info.sinaSymbol), null)
            } })
        popup.add(JMenuItem(L10n.btnCopyName).also { mi ->
            mi.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(info.name), null)
            } })
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { mi ->
            mi.addActionListener { BrowserUtil.browse(buildIpoUrl(info)) } })
        return popup
    }

    private fun showCalMenu(e: MouseEvent) {
        val viewRow = calTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        calTable.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = calTable.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= calRows.size) return
        rowMenu(calRows[modelRow]).show(calTable, e.x, e.y)
    }

    private fun showRecMenu(e: MouseEvent) {
        val viewRow = recTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        recTable.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = recTable.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= recentRows.size) return
        rowMenu(recentRows[modelRow]).show(recTable, e.x, e.y)
    }

    // ══════════════════ 语言 / 开关 ══════════════════

    private fun rebuildFilterCombo() {
        updatingCombo = true
        filterCombo.removeAllItems()
        listOf(L10n.marketTabAll, L10n.ipoFilterToday, L10n.ipoFilterWeek, L10n.ipoFilterPending)
            .forEach { filterCombo.addItem(it) }
        filterCombo.selectedIndex = filterIdx
        updatingCombo = false
    }

    private fun updateTexts() {
        titleLabel.text   = L10n.lblIpoCalendar
        refreshBtn.text   = L10n.btnRefresh
        recentHeader.text = L10n.lblRecentNew
        rebuildFilterCombo()
        // 列标题来自 L10n：结构重建并重新挂渲染器/排序器
        calModel.fireTableStructureChanged()
        calTable.rowSorter = newCalSorter()
        calCols.forEachIndexed { i, col ->
            if (i < calTable.columnModel.columnCount) {
                calTable.columnModel.getColumn(i).cellRenderer =
                    if (col.first == "status") StatusRenderer()
                    else QuoteRenderer(QuoteColumnType.PLAIN)
            }
        }
        recModel.fireTableStructureChanged()
        recTable.rowSorter = newRecSorter()
        recCols.forEachIndexed { i, col ->
            if (i < recTable.columnModel.columnCount)
                recTable.columnModel.getColumn(i).cellRenderer = QuoteRenderer(col.type)
        }
        rebuildRows()
        revalidate(); repaint()
    }

    override fun onLanguageChanged() = updateTexts()

    override fun onFeatureToggleChanged() {
        if (state.enableIpoPanel) {
            if (refreshTimer == null) scheduleRefresh()
            fetchAsync()
        } else {
            refreshTimer?.stop()
            refreshTimer = null
        }
    }

    private fun scheduleRefresh() {
        refreshTimer = Timer(30_000) { fetchAsync() }.also { it.isRepeats = true; it.start() }
    }
}
