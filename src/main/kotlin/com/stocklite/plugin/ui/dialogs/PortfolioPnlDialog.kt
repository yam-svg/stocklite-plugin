package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.PnlChartService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.InlineChartPanel
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class PortfolioPnlDialog : DialogWrapper(true) {

    private val chartPanel = InlineChartPanel()
    private val statusLbl  = JLabel(L10n.chartLoading, SwingConstants.CENTER).apply {
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
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(720, 360)
        }
        panel.add(statusLbl,  BorderLayout.NORTH)
        panel.add(chartPanel, BorderLayout.CENTER)
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
            val allPnl = holdings.mapNotNull { stock ->
                val records = state.getTradeRecordsForStock(stock.id)
                if (records.isEmpty()) return@mapNotNull null
                val kline = ChartDataService.getHistoryKLine(stock.symbol, "daily", 9999)
                if (kline.isEmpty()) return@mapNotNull null
                PnlChartService.calcStockPnl(stock, records, kline)
            }

            val merged = PnlChartService.mergePortfolioPnl(allPnl)
            val points = PnlChartService.toChartPoints(merged)
            val totalPnl = merged.lastOrNull()?.pnl ?: 0.0

            SwingUtilities.invokeLater {
                statusLbl.text = ""
                if (points.isEmpty()) {
                    statusLbl.text = L10n.chartNoData
                    return@invokeLater
                }
                chartPanel.showPnlChart(L10n.dlgPortfolioPnl, points, totalPnl)
            }
        }
    }

    override fun dispose() {
        chartPanel.disposeResources()
        super.dispose()
    }
}
