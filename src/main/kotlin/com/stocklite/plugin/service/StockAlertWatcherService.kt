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

    // 封单监测：本服务只负责按间隔拉取并喂给 AlertManager，实际的滑动窗口/冷却在 AlertManager 内维护。
    // 与到价提醒共用同一轮询：封单只需监控自选里的 A 股（数据源仅对 A 股提供盘口）。
    private fun isCnSymbol(sym: String): Boolean =
        sym.startsWith("sh") || sym.startsWith("sz") || sym.startsWith("bj")

    private fun doPoll() {
        val state = StockliteState.getInstance()

        val priceAlerts = if (state.enablePriceAlerts)
            state.priceAlerts.filter { it.enabled && !it.triggered } else emptyList()
        val cnWatch = if (state.enableSealShrinkAlert)
            state.stocks.asSequence()
                .filter { !state.sealAlertHoldingOnly || it.quantity > 0 }  // 仅持仓模式：只盯数量>0 的票
                .map { it.symbol }.filter { isCnSymbol(it) }.toList() else emptyList()

        val priceAlertOn = priceAlerts.isNotEmpty()
        val sealAlertOn  = state.enableSealShrinkAlert && cnWatch.isNotEmpty()
        if (!priceAlertOn && !sealAlertOn) { scheduleNext(); return }

        val symbols = (priceAlerts.map { it.symbol } + cnWatch).distinct()

        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = try {
                MarketDataService.getStockQuotes(symbols)
            } catch (_: Exception) {
                emptyMap()
            }

            SwingUtilities.invokeLater {
                if (fetched.isNotEmpty()) {
                    if (priceAlertOn) AlertManager.checkAlerts(fetched.mapValues { it.value.price })
                    if (sealAlertOn)  AlertManager.checkSealShrink(fetched)
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
