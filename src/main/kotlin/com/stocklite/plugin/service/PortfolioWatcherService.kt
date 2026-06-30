package com.stocklite.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.PortfolioStatusWidget
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 持仓状态栏后台轮询服务。
 * 作为 ApplicationService 全程独立运行，不依赖 StockPanel 是否打开，
 * 定期拉取持仓股票行情并推送到 IDE 状态栏 PortfolioStatusWidget。
 *
 * 轮询间隔与 Settings → 刷新间隔 → 股票 保持一致。
 * 面板打开时 StockPanel 本身也会更新状态栏，两者写同一个静态状态，互不干扰。
 */
class PortfolioWatcherService : Disposable {

    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        // IDE 启动后延迟 20 秒再开始第一次轮询（错开 StockAlertWatcher 的 15 秒）
        Timer(20_000) { doPoll() }.also { it.isRepeats = false; it.start() }
    }

    private fun doPoll() {
        val state = StockliteState.getInstance()

        if (!state.enablePortfolioStatusBar) {
            scheduleNext(); return
        }

        val holdings = state.stocks.filter { it.quantity > 0 }
        if (holdings.isEmpty()) {
            PortfolioStatusWidget.update(0.0, 0.0, 0.0, emptyList())
            scheduleNext(); return
        }

        val symbols = holdings.map { it.symbol }.distinct()

        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = try {
                MarketDataService.getStockQuotes(symbols)
            } catch (_: Exception) {
                emptyMap()
            }

            SwingUtilities.invokeLater {
                val rows = holdings.map { s ->
                    val q     = fetched[s.symbol]
                    val price = q?.price ?: 0.0
                    PortfolioStatusWidget.HoldingRow(
                        name      = s.name,     symbol   = s.symbol,
                        qty       = s.quantity, price    = price,   cost = s.costPrice,
                        changePct = q?.changePercent ?: 0.0,
                        pnl       = if (price > 0) (price - s.costPrice) * s.quantity else 0.0,
                        todayPnl  = (q?.change ?: 0.0) * s.quantity
                    )
                }
                PortfolioStatusWidget.update(
                    totalValue = rows.sumOf { it.price * it.qty },
                    totalPnl   = rows.sumOf { it.pnl },
                    todayPnl   = rows.sumOf { it.todayPnl },
                    holdings   = rows
                )
                scheduleNext()
            }
        }
    }

    private fun scheduleNext() {
        val intervalMs = StockliteState.getInstance().refreshIntervalStock * 1000
        pollTimer.initialDelay = intervalMs
        pollTimer.restart()
    }

    override fun dispose() {
        pollTimer.stop()
    }

    companion object {
        fun getInstance(): PortfolioWatcherService =
            ApplicationManager.getApplication().getService(PortfolioWatcherService::class.java)
    }
}
