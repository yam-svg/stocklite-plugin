package com.stocklite.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.AlertManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 股票到价提醒后台监听服务。
 * 作为 ApplicationService 在 IDE 启动时自动注册，全程后台轮询，
 * 不依赖 StockPanel 是否打开，价格触达目标时主动推送 IDE 气泡通知。
 *
 * 轮询间隔与 Settings → 刷新间隔 → 股票 保持一致。
 */
class StockAlertWatcherService : Disposable {

    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        // IDE 启动后延迟 15 秒再开始第一次轮询
        Timer(15_000) {
            doPoll()
        }.also { it.isRepeats = false; it.start() }
    }

    // ── 轮询 ────────────────────────────────────────────────────────

    private fun doPoll() {
        val state = StockliteState.getInstance()

        // 没有启用提醒 或 没有任何待触发的提醒 → 跳过本次，继续调度
        if (!state.enablePriceAlerts || state.priceAlerts.none { it.enabled && !it.triggered }) {
            scheduleNext(); return
        }

        // 只拉取有有效提醒的标的，减少无效请求
        val symbols = state.priceAlerts
            .filter { it.enabled && !it.triggered }
            .map { it.symbol }
            .distinct()
            .ifEmpty { scheduleNext(); return }

        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = try {
                MarketDataService.getStockQuotes(symbols)
            } catch (_: Exception) {
                emptyMap()
            }

            SwingUtilities.invokeLater {
                if (fetched.isNotEmpty()) {
                    AlertManager.checkAlerts(fetched.mapValues { it.value.price })
                }
                scheduleNext()
            }
        }
    }

    private fun scheduleNext() {
        val intervalMs = StockliteState.getInstance().refreshIntervalStock * 1000
        pollTimer.initialDelay = intervalMs
        pollTimer.restart()
    }

    // ── Disposable ──────────────────────────────────────────────────

    override fun dispose() {
        pollTimer.stop()
    }

    companion object {
        fun getInstance(): StockAlertWatcherService =
            ApplicationManager.getApplication().getService(StockAlertWatcherService::class.java)
    }
}
