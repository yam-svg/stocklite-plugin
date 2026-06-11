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
 * 去重策略：用 "code:navDate" 组合键记录已通知集合，
 * 同一基金同一净值日期无论 date 字段如何在不同 poll 之间变动，
 * 均只通知一次。避免多接口返回不同日期格式导致的重复通知。
 */
class FundNavWatcherService : Disposable {

    /**
     * 已触发过通知的键集合，格式为 "code:yyyy-MM-dd"。
     * 初始化时预填当日所有基金，保证 IDE 启动不误报。
     */
    private val notifiedKeys = mutableSetOf<String>()

    /** true = 首次轮询已完成，后续轮询可以做对比通知 */
    @Volatile private var initialized = false

    /** 单次定时器，每次 poll 结束后重新调度，避免并发堆积 */
    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        // IDE 启动后延迟 15 秒再做第一次轮询，让 IDE 完成初始化
        Timer(15_000) { doPoll() }.also { it.isRepeats = false; it.start() }
    }

    // ── 轮询 ────────────────────────────────────────────────────────────

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
                    // 首次：把当前所有 code:date 全部预填到 notifiedKeys，不发任何通知
                    fetched.forEach { (code, q) ->
                        val d = q.date?.take(10) ?: ""
                        if (d.isNotEmpty()) notifiedKeys.add("$code:$d")
                    }
                    initialized = true
                } else {
                    fetched.forEach { (code, q) ->
                        val d = q.date?.take(10) ?: ""
                        if (d.isEmpty()) return@forEach          // 日期缺失，跳过

                        val key = "$code:$d"
                        if (key !in notifiedKeys) {
                            // 这是该基金尚未通知过的新净值日期 → 发通知
                            notifiedKeys.add(key)
                            val name = funds.find { it.code == code }?.name ?: code
                            AlertManager.notifyFundNavUpdate(name, q.changePercent)
                        }
                        // 即使已在 notifiedKeys，也不需要做任何事：本次不通知，下次也不会
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

    // ── Disposable ──────────────────────────────────────────────────────

    override fun dispose() {
        pollTimer.stop()
    }

    companion object {
        fun getInstance(): FundNavWatcherService =
            ApplicationManager.getApplication().getService(FundNavWatcherService::class.java)
    }
}
