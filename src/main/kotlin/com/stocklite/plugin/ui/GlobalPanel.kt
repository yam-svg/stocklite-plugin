package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.GlobalIndexQuote
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import com.intellij.ide.BrowserUtil
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class GlobalPanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.RefreshIntervalListener {

    private val chartPanel = InlineChartPanel()
    private val aiPanel    = AiAnalysisPanel(AiAnalysisService.promptForGlobal)

    private val MIN_INTERVAL_MS  = 5_000
    private val MAX_INTERVAL_MS  = 60_000
    private val RECOVERY_STEP_MS = 5_000
    private var currentIntervalMs = MIN_INTERVAL_MS
    private var refreshTimer: Timer? = null
    private var panelActive = true

    private var quotes: List<GlobalIndexQuote> = emptyList()

    private val colTypes = arrayOf(
        QuoteColumnType.PLAIN, QuoteColumnType.PRICE, QuoteColumnType.PCT,
        QuoteColumnType.PLAIN, QuoteColumnType.PLAIN
    )

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = quotes.size
        override fun getColumnCount()        = 5
        override fun getColumnName(col: Int) = when (col) {
            0 -> L10n.colIndex; 1 -> L10n.colPrice; 2 -> L10n.colChangePct
            3 -> L10n.colMarket; 4 -> L10n.colStatus; else -> ""
        }
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> =
            if (col == 1 || col == 2) Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val q = quotes[row]
            return when (col) {
                0 -> q.name; 1 -> q.value; 2 -> q.changePercent
                3 -> q.market; 4 -> if (q.isOpen) L10n.cellOpen else L10n.cellClosed
                else -> ""
            }
        }
    }

    private val table        = JBTable(tableModel)
    private val statusLabel  = JLabel("${L10n.lblLastUpdate} --")
    private val delayNoticeLabel = JLabel(L10n.globalDelayNotice).apply {
        font = font.deriveFont(11f)
        foreground = Color(0x888aaa)
        border = BorderFactory.createEmptyBorder(2, 8, 4, 8)
    }

    private val ALL_MARKETS   = "ALL"
    private val OTHER_MARKETS = "OTHER"
    private val MAIN_MARKETS  = listOf("CN", "HK", "US")
    private var selectedMarket = ALL_MARKETS
    private val marketTabBtns = linkedMapOf<String, JButton>()
    private val otherMarketsSet: Set<String> by lazy {
        MarketDataService.GLOBAL_INDEXES.map { it.market }.distinct().filterNot { it in MAIN_MARKETS }.toSet()
    }

    private lateinit var titleLbl:    JLabel
    private lateinit var refreshBtn:  JButton
    private lateinit var filterField: SearchTextField

    init {
        StockliteState.getInstance().addLanguageListener(this)
        StockliteState.getInstance().addRefreshIntervalListener(this)
        setupTable()
        buildUI()
        fetchAsync()
        table.rowSorter = TableRowSorter(tableModel)
        startTimer()
        addHierarchyListener { _ ->
            val showing = isShowing
            if (showing != panelActive) {
                panelActive = showing
                if (showing) fetchAsync()
            }
        }
    }

    override fun onLanguageChanged() {
        tableModel.fireTableStructureChanged()
        tableModel.fireTableDataChanged()
        table.rowSorter = TableRowSorter(tableModel)
        applyRenderers()
        titleLbl.text   = L10n.lblGlobalTitle
        refreshBtn.text = L10n.btnRefresh
        delayNoticeLabel.text = L10n.globalDelayNotice
        marketTabBtns.forEach { (m, btn) -> btn.text = marketTabLabel(m) }
        updateFilter()
        val cur = statusLabel.text
        val updatedPrefix = cur.substringBefore("   ").takeIf { it.isNotBlank() }
        statusLabel.text = if (updatedPrefix != null) "$updatedPrefix   ${MarketTimeUtil.getMarketStatusText()}"
                           else MarketTimeUtil.getMarketStatusText()
        revalidate(); repaint()
    }

    override fun onRefreshIntervalChanged() {
        val newBase = StockliteState.getInstance().refreshIntervalGlobal * 1000
        currentIntervalMs = newBase
        restartTimer()
    }

    private fun applyRenderers() {
        colTypes.forEachIndexed { i, type ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(type)
            }
        }
        if (table.columnModel.columnCount >= 3) {
            table.columnModel.getColumn(0).preferredWidth = 120
            table.columnModel.getColumn(1).preferredWidth = 100
            table.columnModel.getColumn(2).preferredWidth = 80
        }
    }

    private fun setupTable() {
        applyRenderers()
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        centerTableHeader(table)

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) return
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.getColumnName(viewCol) != L10n.colChangePct) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= quotes.size) return
                val q = quotes[modelRow]
                val prev = if (q.changePercent != 0.0 && q.value > 0)
                    q.value / (1.0 + q.changePercent / 100.0) else 0.0
                chartPanel.showChart(
                    displayName   = q.name,
                    displaySymbol = q.symbol,
                    changePercent = q.changePercent,
                    prevClose     = prev,
                    fetchData     = { ChartDataService.getGlobalIntraday(q.symbol) }
                )
            }
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
        })
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && table.getColumnName(col) == L10n.colChangePct)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })

        com.stocklite.plugin.ui.common.TableRowDragHandler.install(table) { from, to ->
            if (from != to && from in quotes.indices && to in quotes.indices) {
                val mutable = quotes.toMutableList()
                mutable.add(to, mutable.removeAt(from))
                quotes = mutable
                val state = StockliteState.getInstance()
                state.globalIndexOrder.clear()
                state.globalIndexOrder.addAll(quotes.map { it.symbol })
                tableModel.fireTableDataChanged()
                table.setRowSelectionInterval(to, to)
                table.scrollRectToVisible(table.getCellRect(to, 0, true))
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= quotes.size) return
        val q = quotes[modelRow]

        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.btnCopyName).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(q.name), null)
        }})
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { it.addActionListener {
            BrowserUtil.browse(buildIndexUrl(q.symbol))
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnAiDeepAnalysis).also { it.addActionListener {
            val sign   = if (q.changePercent >= 0) "+" else ""
            val status = if (q.isOpen) "交易中" else "休市"
            val ctx    = buildString {
                appendLine("指数名称：${q.name}"); appendLine("代码：${q.symbol}")
                appendLine("当前点位：${"%.2f".format(q.value)}")
                appendLine("涨跌幅：$sign${"%.2f".format(q.changePercent)}%")
                appendLine("市场状态：$status"); appendLine("所属市场：${q.market}")
                if (q.isDelayed) appendLine("数据延迟：约15分钟（非实时）")
            }.trim()
            com.stocklite.plugin.ui.dialogs.AiDeepAnalysisDialog(
                displayTitle = q.name,
                itemContext  = ctx
            ).show()
        }})
        popup.show(table, e.x, e.y)
    }

    private fun buildIndexUrl(symbol: String): String = when {
        symbol == "000001.SS" -> "https://quote.eastmoney.com/zs000001.html"
        symbol == "399001.SZ" -> "https://quote.eastmoney.com/zs399001.html"
        symbol == "399006.SZ" -> "https://quote.eastmoney.com/zs399006.html"
        symbol == "000300.SS" -> "https://quote.eastmoney.com/zs000300.html"
        symbol == "000688.SS" -> "https://quote.eastmoney.com/zs000688.html"
        symbol == "^HSI"      -> "https://finance.yahoo.com/quote/%5EHSI/"
        symbol == "^HSTECH"   -> "https://finance.yahoo.com/quote/%5EHSTECH/"
        symbol.startsWith("^") -> "https://finance.yahoo.com/quote/${symbol.replace("^", "%5E")}/"
        else -> "https://finance.yahoo.com/quote/$symbol/"
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        titleLbl   = JLabel(L10n.lblGlobalTitle)
        refreshBtn = JButton(L10n.btnRefresh)
        filterField = SearchTextField().also { it.preferredSize = Dimension(120, 26) }
        toolbar.add(titleLbl)
        toolbar.add(refreshBtn)
        toolbar.add(statusLabel)
        toolbar.add(JLabel(L10n.lblFilter)); toolbar.add(filterField)

        val topSection = JPanel(BorderLayout())
        topSection.add(toolbar, BorderLayout.NORTH)
        topSection.add(buildMarketTabs(), BorderLayout.SOUTH)

        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(delayNoticeLabel, BorderLayout.NORTH)
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(chartPanel, BorderLayout.SOUTH)

        val mainWrapper = JPanel(BorderLayout())
        mainWrapper.add(centerWrapper, BorderLayout.CENTER)
        mainWrapper.add(aiPanel,       BorderLayout.SOUTH)

        add(topSection,  BorderLayout.NORTH)
        add(mainWrapper, BorderLayout.CENTER)

        filterField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
        })

        refreshBtn.addActionListener { fetchAsync() }
    }

    /** 按市场快捷筛选的 tab 栏，"全部" + 各市场代码（如 CN/HK/US），默认选中"全部" */
    private fun buildMarketTabs(): JPanel {
        val presentMarkets = MarketDataService.GLOBAL_INDEXES.map { it.market }.distinct()
        val tabs = listOf(ALL_MARKETS) + MAIN_MARKETS.filter { it in presentMarkets } +
            (if (otherMarketsSet.isNotEmpty()) listOf(OTHER_MARKETS) else emptyList())
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 6, 2, 6)
        }
        tabs.forEach { m ->
            val btn = JButton(marketTabLabel(m)).apply {
                isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
                font = font.deriveFont(11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { selectMarket(m) }
            }
            marketTabBtns[m] = btn
            bar.add(btn)
        }
        highlightMarketTab(ALL_MARKETS)
        return bar
    }

    private fun marketTabLabel(m: String) = when (m) {
        ALL_MARKETS   -> L10n.marketTabAll
        OTHER_MARKETS -> L10n.marketTabOther
        else          -> m
    }

    private fun selectMarket(market: String) {
        selectedMarket = market
        highlightMarketTab(market)
        updateFilter()
    }

    private fun highlightMarketTab(selected: String) {
        marketTabBtns.forEach { (m, btn) ->
            if (m == selected) {
                btn.foreground = Color(0xcdd6f4)
                btn.font = btn.font.deriveFont(Font.BOLD, 11f)
            } else {
                btn.foreground = Color(0x888aaa)
                btn.font = btn.font.deriveFont(Font.PLAIN, 11f)
            }
        }
    }

    private fun updateFilter() {
        val sorter = table.rowSorter as? TableRowSorter<*> ?: return
        val text = filterField.text.trim()
        val filters = mutableListOf<RowFilter<Any, Any>>()
        if (text.isNotEmpty()) filters.add(RowFilter.regexFilter("(?i)${Regex.escape(text)}"))
        val marketMatchSet = when (selectedMarket) {
            ALL_MARKETS   -> null
            OTHER_MARKETS -> otherMarketsSet
            else          -> setOf(selectedMarket)
        }
        if (marketMatchSet != null) {
            filters.add(object : RowFilter<Any, Any>() {
                override fun include(entry: RowFilter.Entry<out Any, out Any>) = entry.getStringValue(3) in marketMatchSet
            })
        }
        sorter.rowFilter = when {
            filters.isEmpty() -> null
            filters.size == 1 -> filters[0]
            else -> RowFilter.andFilter(filters)
        }
    }

    fun fetchAsync() {
        if (!panelActive) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = MarketDataService.getGlobalIndexQuotes()
            SwingUtilities.invokeLater {
                if (result.rateLimited) {
                    val newInterval = (currentIntervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
                    if (newInterval != currentIntervalMs) { currentIntervalMs = newInterval; restartTimer() }
                    statusLabel.text = L10n.statusRateLimited(currentIntervalMs / 1000)
                } else {
                    if (currentIntervalMs > MIN_INTERVAL_MS) {
                        currentIntervalMs = (currentIntervalMs - RECOVERY_STEP_MS).coerceAtLeast(MIN_INTERVAL_MS)
                        restartTimer()
                    }
                    val order = StockliteState.getInstance().globalIndexOrder
                    quotes = if (order.isEmpty()) result.quotes else {
                        val map = result.quotes.associateBy { it.symbol }
                        val known = order.mapNotNull { map[it] }
                        val newOnes = result.quotes.filter { it.symbol !in order }
                        known + newOnes
                    }
                    tableModel.fireTableDataChanged()
                    val now = java.time.LocalTime.now()
                        .let { String.format("%02d:%02d:%02d", it.hour, it.minute, it.second) }
                    statusLabel.text = "${L10n.lblLastUpdate} $now   ${MarketTimeUtil.getMarketStatusText()}" +
                        if (currentIntervalMs > MIN_INTERVAL_MS)
                            "   (${L10n.statusInterval(currentIntervalMs / 1000)})" else ""
                    aiPanel.updateContext(buildAiContext())
                }
            }
        }
    }

    private fun buildAiContext(): String {
        if (quotes.isEmpty()) return ""
        val sb = StringBuilder("全球指数行情（共 ${quotes.size} 个，标注[延迟]的约有15分钟延迟）:\n")
        for (q in quotes) {
            val sign   = if (q.changePercent >= 0) "+" else ""
            val status = if (q.isOpen) "[交易中]" else "[休市]"
            val delay  = if (q.isDelayed) "[延迟]" else ""
            sb.appendLine("- ${q.name}: ${"%.2f".format(q.value)}  $sign${"%.2f".format(q.changePercent)}%  $status$delay")
        }
        return sb.toString().trim()
    }

    private fun startTimer() {
        val base = StockliteState.getInstance().refreshIntervalGlobal * 1000
        currentIntervalMs = base.coerceAtLeast(MIN_INTERVAL_MS)
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also { it.isRepeats = true; it.start() }
    }

    private fun restartTimer() {
        refreshTimer?.stop()
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also { it.isRepeats = true; it.start() }
    }
}
