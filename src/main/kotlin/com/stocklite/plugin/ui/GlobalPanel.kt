package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.GlobalIndexQuote
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class GlobalPanel : JPanel(BorderLayout()), StockliteState.LanguageListener {

    private val chartPanel = InlineChartPanel()

    // ── 自适应刷新间隔 ──
    private val MIN_INTERVAL_MS  = 5_000
    private val MAX_INTERVAL_MS  = 60_000
    private val RECOVERY_STEP_MS = 5_000
    private var currentIntervalMs = MIN_INTERVAL_MS
    private var refreshTimer: Timer? = null

    private var quotes: List<GlobalIndexQuote> = emptyList()

    // 列类型固定（全球面板无可选列），列名通过 L10n 动态读取
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
                0 -> q.name
                1 -> q.value
                2 -> q.changePercent
                3 -> q.market
                4 -> if (q.isOpen) L10n.cellOpen else L10n.cellClosed
                else -> ""
            }
        }
    }

    private val table       = JBTable(tableModel)
    private val statusLabel = JLabel("${L10n.lblLastUpdate} --")

    private lateinit var titleLbl:   JLabel
    private lateinit var refreshBtn: JButton

    init {
        StockliteState.getInstance().addLanguageListener(this)
        setupTable()
        buildUI()
        fetchAsync()
        table.rowSorter = TableRowSorter(tableModel)
        startTimer()
    }

    override fun onLanguageChanged() {
        // 列名、单元格值（"Open"/"Closed"）均通过 L10n 动态计算，重绘即可
        tableModel.fireTableStructureChanged()
        tableModel.fireTableDataChanged()
        table.rowSorter = TableRowSorter(tableModel)
        applyRenderers()
        titleLbl.text   = L10n.lblGlobalTitle
        refreshBtn.text = L10n.btnRefresh
        // 更新状态栏（不含上次更新时间，只更新市场状态部分）
        val cur = statusLabel.text
        val updatedPrefix = cur.substringBefore("   ").takeIf { it.isNotBlank() }
        statusLabel.text = if (updatedPrefix != null) "$updatedPrefix   ${MarketTimeUtil.getMarketStatusText()}"
                           else MarketTimeUtil.getMarketStatusText()
        revalidate(); repaint()
    }

    private fun applyRenderers() {
        colTypes.forEachIndexed { i, t ->
            if (i < table.columnModel.columnCount)
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(t)
        }
        if (table.columnModel.columnCount >= 2) {
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

        // 点击"涨跌幅"列展开内嵌走势图
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
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
        })
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && table.getColumnName(col) == L10n.colChangePct)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        titleLbl   = JLabel(L10n.lblGlobalTitle)
        refreshBtn = JButton(L10n.btnRefresh)
        toolbar.add(titleLbl)
        toolbar.add(refreshBtn)
        toolbar.add(statusLabel)

        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(chartPanel, BorderLayout.SOUTH)

        add(toolbar, BorderLayout.NORTH)
        add(centerWrapper, BorderLayout.CENTER)

        refreshBtn.addActionListener { fetchAsync() }
    }

    fun fetchAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = MarketDataService.getGlobalIndexQuotes()
            SwingUtilities.invokeLater {
                if (result.rateLimited) {
                    val newInterval = (currentIntervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
                    if (newInterval != currentIntervalMs) {
                        currentIntervalMs = newInterval
                        restartTimer()
                    }
                    statusLabel.text = L10n.statusRateLimited(currentIntervalMs / 1000)
                } else {
                    if (currentIntervalMs > MIN_INTERVAL_MS) {
                        currentIntervalMs = (currentIntervalMs - RECOVERY_STEP_MS).coerceAtLeast(MIN_INTERVAL_MS)
                        restartTimer()
                    }
                    quotes = result.quotes
                    tableModel.fireTableDataChanged()
                    val now = java.time.LocalTime.now()
                        .let { String.format("%02d:%02d:%02d", it.hour, it.minute, it.second) }
                    statusLabel.text = "${L10n.lblLastUpdate} $now   ${MarketTimeUtil.getMarketStatusText()}" +
                        if (currentIntervalMs > MIN_INTERVAL_MS)
                            "   (${L10n.statusInterval(currentIntervalMs / 1000)})" else ""
                }
            }
        }
    }

    private fun startTimer() {
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }

    private fun restartTimer() {
        refreshTimer?.stop()
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }
}
