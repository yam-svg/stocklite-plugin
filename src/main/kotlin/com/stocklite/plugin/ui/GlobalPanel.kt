package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.GlobalIndexQuote
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.centerTableHeader
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

class GlobalPanel : JPanel(BorderLayout()) {

    private val chartPanel = InlineChartPanel()

    // ── 自适应刷新间隔 ──
    private val MIN_INTERVAL_MS  = 5_000
    private val MAX_INTERVAL_MS  = 60_000
    private val RECOVERY_STEP_MS = 5_000   // 每次成功请求恢复 5s
    private var currentIntervalMs = MIN_INTERVAL_MS
    private var refreshTimer: Timer? = null

    private var quotes: List<GlobalIndexQuote> = emptyList()

    private val COLS      = arrayOf("指数", "最新", "涨跌幅", "市场", "状态")
    private val COL_CLASS = arrayOf(String::class.java, Double::class.java, Double::class.java,
                                    String::class.java, String::class.java)

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = quotes.size
        override fun getColumnCount()        = COLS.size
        override fun getColumnName(col: Int) = COLS[col]
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> = COL_CLASS.getOrElse(col) { String::class.java }
        override fun getValueAt(row: Int, col: Int): Any {
            val q = quotes[row]
            return when (col) {
                0 -> q.name
                1 -> q.value
                2 -> q.changePercent
                3 -> q.market
                4 -> if (q.isOpen) "交易中" else "休市"
                else -> ""
            }
        }
    }

    private val table       = JBTable(tableModel)
    private val statusLabel = JLabel("上次更新: --")

    init {
        setupTable()
        buildUI()
        fetchAsync()
        table.rowSorter = TableRowSorter(tableModel)
        startTimer()
    }

    private fun setupTable() {
        val colTypes = listOf(
            QuoteColumnType.PLAIN,
            QuoteColumnType.PRICE,
            QuoteColumnType.PCT,
            QuoteColumnType.PLAIN,
            QuoteColumnType.PLAIN
        )
        colTypes.forEachIndexed { i, t ->
            table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(t)
        }
        table.columnModel.getColumn(0).preferredWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 100
        table.columnModel.getColumn(2).preferredWidth = 80
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        centerTableHeader(table)

        // 点击"涨跌幅"列弹出日内走势图
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.getColumnName(viewCol) != "涨跌幅") return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= quotes.size) return
                val q = quotes[modelRow]
                // 全球指数没有昨收字段；从现价和涨跌幅反推
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
                table.cursor = if (col >= 0 && table.getColumnName(col) == "涨跌幅")
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val refreshBtn = JButton("刷新")
        toolbar.add(JLabel("全球主要指数"))
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
                    // 限流：指数退避，间隔加倍（最多 60s）
                    val newInterval = (currentIntervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
                    if (newInterval != currentIntervalMs) {
                        currentIntervalMs = newInterval
                        restartTimer()
                    }
                    statusLabel.text = "⚠ 请求受限，刷新间隔已延长至 ${currentIntervalMs / 1000}s"
                } else {
                    // 成功：逐步恢复（每次减少 5s，直到回到最小值）
                    if (currentIntervalMs > MIN_INTERVAL_MS) {
                        currentIntervalMs = (currentIntervalMs - RECOVERY_STEP_MS).coerceAtLeast(MIN_INTERVAL_MS)
                        restartTimer()
                    }
                    quotes = result.quotes
                    tableModel.fireTableDataChanged()
                    val now = java.time.LocalTime.now()
                        .let { String.format("%02d:%02d:%02d", it.hour, it.minute, it.second) }
                    statusLabel.text = "上次更新: $now   ${MarketTimeUtil.getMarketStatusText()}" +
                        if (currentIntervalMs > MIN_INTERVAL_MS) "   (${currentIntervalMs / 1000}s/次)" else ""
                }
            }
        }
    }

    private fun startTimer() {
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also {
            it.isRepeats = true
            it.start()
        }
    }

    private fun restartTimer() {
        refreshTimer?.stop()
        refreshTimer = Timer(currentIntervalMs) { fetchAsync() }.also {
            it.isRepeats = true
            it.start()
        }
    }
}
