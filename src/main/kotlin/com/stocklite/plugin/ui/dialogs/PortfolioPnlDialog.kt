package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.PnlChartService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.DayStockRow
import com.stocklite.plugin.ui.InlineChartPanel
import com.stocklite.plugin.ui.PnlCalendarPanel
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.LocalDate
import javax.swing.*

class PortfolioPnlDialog : DialogWrapper(true) {

    private val chartPanel    = InlineChartPanel()
    private val calendarPanel = PnlCalendarPanel()
    private val statusLbl     = JLabel(L10n.chartLoading, SwingConstants.CENTER).apply {
        font = font.deriveFont(12f)
        foreground = java.awt.Color(0x888aaa)
    }

    init {
        title = L10n.dlgPortfolioPnl
        isModal = false
        init()
        loadAsync()
    }

    override fun createCenterPanel(): JComponent {
        val chartTab = JPanel(BorderLayout()).apply {
            add(statusLbl,  BorderLayout.NORTH)
            add(chartPanel, BorderLayout.CENTER)
        }

        val tabs = JTabbedPane()
        tabs.addTab(L10n.tabPnlChart,    chartTab)
        tabs.addTab(L10n.tabPnlCalendar, calendarPanel)

        val panel = JPanel(BorderLayout()).apply {
            // 高度需容纳"明细区占满（持仓多时固定 140px）"的最坏情况，否则日历格子会被压扁
            preferredSize = Dimension(720, 480)
        }
        panel.add(tabs, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> =
        arrayOf(cancelAction.also { it.putValue(Action.NAME, L10n.btnClose) })

    private fun loadAsync() {
        val state   = StockliteState.getInstance()
        val holdings = state.stocks.filter { it.quantity > 0 }
        if (holdings.isEmpty()) {
            statusLbl.text = L10n.pnlNoHoldings
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            data class StockRows(val name: String, val rows: List<PnlChartService.DayRow>)
            val perStock = holdings.mapNotNull { stock ->
                val records = state.getTradeRecordsForStock(stock.id)
                val kline = ChartDataService.getHistoryKLine(stock.symbol, "daily", 9999)
                if (kline.isEmpty()) return@mapNotNull null
                PnlChartService.calcStockDayRows(stock, records, kline)
                    .takeIf { it.isNotEmpty() }
                    ?.let { StockRows(stock.alias.ifBlank { stock.name }, it) }
            }

            val merged = PnlChartService.mergePortfolioPnl(perStock.map { s ->
                s.rows.map { PnlChartService.DailyPnl(it.date, it.cumPnl) }
            })
            val points = PnlChartService.toChartPoints(merged)
            val totalPnl = merged.lastOrNull()?.pnl ?: 0.0
            // 累计盈亏曲线差分 → 当日盈亏（累计值只在有行情/交易的日期变化，差分即为当日变动）
            val dailyPnl = LinkedHashMap<LocalDate, Double>()
            run {
                var prev = 0.0
                for (dp in merged) {
                    dailyPnl[dp.date] = dp.pnl - prev
                    prev = dp.pnl
                }
            }
            // 日期 → 各股当日盈亏明细（供日历点击钻取）
            val detailMap = mutableMapOf<LocalDate, MutableList<DayStockRow>>()
            for (s in perStock) {
                for (r in s.rows) {
                    if (r.dayPnl == 0.0) continue
                    detailMap.getOrPut(r.date) { mutableListOf() }.add(DayStockRow(s.name, r.dayPnl, r.dayPct))
                }
            }

            SwingUtilities.invokeLater {
                statusLbl.text = ""
                if (points.isEmpty()) {
                    statusLbl.text = L10n.chartNoData
                    return@invokeLater
                }
                chartPanel.showPnlChart(L10n.dlgPortfolioPnl, points, totalPnl)
                calendarPanel.setData(dailyPnl, detailMap)
            }
        }
    }

    override fun dispose() {
        chartPanel.disposeResources()
        super.dispose()
    }
}
