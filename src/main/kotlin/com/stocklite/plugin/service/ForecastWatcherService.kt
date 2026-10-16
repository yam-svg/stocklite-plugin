package com.stocklite.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.swing.Timer

/**
 * 盘前研判/结算后台服务（不依赖面板是否打开）。
 *
 * 轮询策略（上海时间，工作日）：
 *   08:40–09:25  每 1 分钟  生成盘前研判（当天一次即锁定；隔夜源偶尔延迟，重试到成功为止）
 *   09:25–15:10  每 5 分钟  开盘后结算（今开出数即结跳空）+ 天气分盘中保鲜
 *   15:10–16:00  每 2 分钟  收盘结算冲刺：涨停池等数据收盘后才定型，密集补结
 *   16:00–21:00  每 15 分钟 延迟数据回填天气快照
 *   其余时段     每 1 小时  轻量唤醒，等待进入下一窗口
 *
 * 旧版模型没有常驻触发导致"准确率"样本几乎不增长，本服务保证只要 IDE 开着，
 * 每个交易日必有研判落库、必有结算回填。
 */
class ForecastWatcherService : Disposable {

    private val shZone = ZoneId.of("Asia/Shanghai")
    private val pollTimer = Timer(0) { doPoll() }.also { it.isRepeats = false }

    init {
        val now = ZonedDateTime.now(shZone)
        val t = now.toLocalTime()
        // 冷启动：研判窗口内 3 秒后立即干活；收盘窗口 5 秒；其余 25 秒
        val delayMs = when {
            now.dayOfWeek.value in 1..5 && t >= LocalTime.of(8, 40) && t <= LocalTime.of(9, 25) -> 3_000
            now.dayOfWeek.value in 1..5 && t >= LocalTime.of(15, 0) && t < LocalTime.of(16, 0)  -> 5_000
            else -> 25_000
        }
        Timer(delayMs) { doPoll() }.also { it.isRepeats = false; it.start() }
    }

    private fun scheduleNext() {
        // Timer 监听在 EDT 上触发，重排同样在 EDT，无需线程同步
        val delay = nextDelayMs()
        pollTimer.initialDelay = delay
        pollTimer.restart()
    }

    private fun doPoll() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ForecastService.settleForecasts()
                if (ForecastService.inPreOpenWindow()) ForecastService.generatePreOpenForecast()
                // 天气分：盘后/收盘窗口刷新即可，盘中面板自己会带出来
                val t = ZonedDateTime.now(shZone).toLocalTime()
                if (t >= LocalTime.of(15, 0) || t < LocalTime.of(8, 40)) ForecastService.getSentiment()
            } catch (_: Exception) { }
            scheduleNextSafe()
        }
    }

    /** 池线程回调后切回 EDT 重启非重复 Timer */
    private fun scheduleNextSafe() {
        ApplicationManager.getApplication().invokeLater { scheduleNext() }
    }

    private fun nextDelayMs(): Int {
        val now = ZonedDateTime.now(shZone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return 60 * 60_000
        val t = now.toLocalTime()
        return when {
            t >= LocalTime.of(8, 40) && t <= LocalTime.of(9, 25) -> 60_000
            t > LocalTime.of(9, 25) && t < LocalTime.of(15, 10)  -> 5 * 60_000
            t >= LocalTime.of(15, 10) && t < LocalTime.of(16, 0) -> 2 * 60_000
            t >= LocalTime.of(16, 0) && t < LocalTime.of(21, 0)  -> 15 * 60_000
            else -> 60 * 60_000
        }
    }

    override fun dispose() { pollTimer.stop() }
}
