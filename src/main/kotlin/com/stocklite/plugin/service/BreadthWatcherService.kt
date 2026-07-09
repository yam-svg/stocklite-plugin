package com.stocklite.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.swing.Timer

/**
 * A股大盘概览后台刷新服务。
 *
 * 核心目标：确保用户再次打开面板时看到的是当日收盘最终数据。
 *
 * 东方财富 push2 实时接口在收盘后约 15 分钟开始陆续停止返回数据。
 * 需要在 15:00 附近密集轮询，把收盘结算数据写入磁盘快照，
 * 这样 IDE 重启后、面板重新打开时都能读到正确的当日收盘数据。
 *
 * 轮询频率策略（上海时间，工作日）：
 *   14:50–15:10  每 30 秒   —— 贴近收盘时刻，最大化捕获结算数据的概率
 *   15:10–16:30  每  2 分钟 —— 收盘后短期仍可能有数据（期货龙虎榜等）
 *   16:30–21:00  每 10 分钟 —— 延伸交易时段，低频保活快照
 *   21:00–次日14:50  不轮询 —— 无意义，让 GlobalPanel 面板开启时自行刷新
 *
 * IDE 冷启动补救：如果启动时已是工作日 14:50–21:00，立即触发一次拉取，
 * 不等 25 秒——确保用户打开面板看到的是最新快照而非旧数据。
 */
class BreadthWatcherService : Disposable {

    private val shZone = ZoneId.of("Asia/Shanghai")
    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        val now = ZonedDateTime.now(shZone)
        val t   = now.toLocalTime()
        val isWeekday = now.dayOfWeek.value in 1..5
        // 在关键窗口（14:50–21:00 工作日）立即拉取，其他时间延迟 25 秒
        val delayMs = if (isWeekday && t >= LocalTime.of(14, 50) && t < LocalTime.of(21, 0))
            3_000 else 25_000
        Timer(delayMs) { doPoll() }.also { it.isRepeats = false; it.start() }
    }

    private fun doPoll() {
        val intervalMs = nextIntervalMs()
        if (intervalMs <= 0) { scheduleAt(60 * 60_000); return }   // 非轮询时段，1小时后再检查

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                MarketDataService.getMarketBreadth()   // 内部写入持久化快照
            } catch (_: Exception) {}
            scheduleAt(nextIntervalMs().coerceAtLeast(30_000))
        }
    }

    /**
     * 根据当前时刻返回下次轮询间隔（ms）；<= 0 表示当前不在轮询窗口。
     */
    private fun nextIntervalMs(): Int {
        val now = ZonedDateTime.now(shZone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return -1
        val t = now.toLocalTime()
        return when {
            t >= LocalTime.of(14, 50) && t < LocalTime.of(15, 10) -> 30_000       // 30 秒
            t >= LocalTime.of(15, 10) && t < LocalTime.of(16, 30) ->  2 * 60_000  // 2 分钟
            t >= LocalTime.of(16, 30) && t < LocalTime.of(21,  0) -> 10 * 60_000  // 10 分钟
            else -> -1
        }
    }

    private fun scheduleAt(delayMs: Int) {
        pollTimer.initialDelay = delayMs
        pollTimer.restart()
    }

    override fun dispose() { pollTimer.stop() }
}
