package com.stocklite.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.AlertManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 基金净值后台监听服务。
 * 作为 ApplicationService 在 IDE 启动时自动注册，全程后台轮询，
 * 不依赖 FundPanel 是否打开，净值更新时主动推送 IDE 气泡通知。
 *
 * 轮询间隔与 Settings → 刷新间隔 → 基金 保持一致。
 * 首次轮询仅初始化日期快照，不触发通知（避免 IDE 启动时误报）。
 */
class FundNavWatcherService : Disposable {

    /** code -> 上次已知的净值日期（yyyy-MM-dd） */
    private val prevNavDates = mutableMapOf<String, String>()

    /** true = 首次轮询已完成，后续轮询可以做对比通知 */
    @Volatile private var initialized = false

    /** 单次定时器，每次 poll 结束后重新调度，避免并发堆积 */
    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        // IDE 启动后延迟 15 秒再做第一次轮询，让 IDE 完成初始化
        Timer(15_000) {
            doPoll()
        }.also { it.isRepeats = false; it.start() }
    }

    // ── 轮询 ────────────────────────────────────────────────────────

    private fun doPoll() {
        val state = StockliteState.getInstance()
        if (!state.enableFundNavAlert) {
            scheduleNext(); return
        }
        val funds = state.funds.toList().ifEmpty { scheduleNext(); return }
        val codes = funds.map { it.code }.distinct()

        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = try {
                MarketDataService.getFundQuotes(codes)
            } catch (_: Exception) {
                emptyMap()
            }

            SwingUtilities.invokeLater {
                if (!initialized) {
                    // 首次：只记录当前日期，不发通知
                    fetched.forEach { (code, q) ->
                        val d = q.date?.take(10) ?: ""
                        if (d.isNotEmpty()) prevNavDates[code] = d
                    }
                    initialized = true
                } else {
                    // 后续：对比日期，有变化则通知
                    fetched.forEach { (code, q) ->
                        val newDate  = q.date?.take(10) ?: ""
                        val prevDate = prevNavDates[code]
                        if (prevDate != null && newDate.isNotEmpty() && newDate != prevDate) {
                            val name = funds.find { it.code == code }?.name ?: code
                            AlertManager.notifyFundNavUpdate(name, q.changePercent)
                        }
                        if (newDate.isNotEmpty()) prevNavDates[code] = newDate
                    }
                }
                scheduleNext()
            }
        }
    }

    private fun scheduleNext() {
        val intervalMs = StockliteState.getInstance().refreshIntervalFund * 1000
        pollTimer.initialDelay = intervalMs
        pollTimer.restart()
    }

    // ── Disposable ──────────────────────────────────────────────────

    override fun dispose() {
        pollTimer.stop()
    }

    companion object {
        fun getInstance(): FundNavWatcherService =
            ApplicationManager.getApplication().getService(FundNavWatcherService::class.java)
    }
}
