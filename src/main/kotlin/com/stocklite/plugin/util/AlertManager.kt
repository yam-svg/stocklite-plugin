package com.stocklite.plugin.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.stocklite.plugin.state.StockQuote
import com.stocklite.plugin.state.StockliteState

/**
 * 价格提醒管理器。
 * 在每次行情刷新后调用 [checkAlerts]。
 * 触发后将提醒标记为 triggered=true，避免重复通知。
 * 调用方负责在适当时机重置（例如价格回退后）。
 */
object AlertManager {

    /**
     * 基金净值更新通知。
     * 由 FundPanel 在检测到某支基金的 navDate 变为今日时调用。
     * @param name       基金名称
     * @param changePct  官方涨跌幅（%）
     */
    fun notifyFundNavUpdate(name: String, changePct: Double) {
        if (!StockliteState.getInstance().enableFundNavAlert) return
        val msg = L10n.dlgFundNavAlertMsg(name, changePct)
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("StockLite Alerts")
                .createNotification(L10n.dlgFundNavAlertTitle, msg, NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}
    }

    /**
     * 今日新股申购提醒。由 IpoPanel 在拉取新股日历后调用，
     * 去重由调用方通过 state.ipoNotifiedDates 完成（同一代码一天只弹一次）。
     */
    fun notifyIpoApplyToday(name: String, applyCode: String, issuePrice: Double?) {
        if (!StockliteState.getInstance().enableIpoPanel) return
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("StockLite Alerts")
                .createNotification(L10n.ipoAlertTitle, L10n.ipoAlertMsg(name, applyCode, issuePrice),
                    NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}
    }

    /**
     * 新手指引入口气泡：新安装首次启动时发一次（置位由调用方负责），
     * 指向工具窗口标题栏的 ? 按钮。不自动弹出指引对话框，避免打扰。
     */
    fun notifyOnboardingHint() {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("StockLite Alerts")
                .createNotification(L10n.onbHintTitle, L10n.onbHintMsg, NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}
    }

    /**
     * @param quotes  Map<symbol, price>，本次刷新到的行情
     */
    fun checkAlerts(quotes: Map<String, Double>) {
        val state = StockliteState.getInstance()
        if (!state.enablePriceAlerts) return

        for (alert in state.priceAlerts) {
            if (!alert.enabled || alert.triggered) continue
            val price = quotes[alert.symbol] ?: continue

            val triggered = when (alert.alertType) {
                "ABOVE" -> price >= alert.targetPrice
                "BELOW" -> price <= alert.targetPrice
                else    -> false
            }
            if (!triggered) continue

            alert.triggered = true

            val msg = L10n.dlgAlertTriggered(alert.name, price, alert.targetPrice)
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("StockLite Alerts")
                    .createNotification(L10n.dlgAlertNotifyTitle, msg, NotificationType.INFORMATION)
                    .notify(null)
            } catch (_: Exception) {
                // NotificationGroup 未注册时降级：IDE 通知不可用，静默忽略
            }
        }
    }

    // ── 封单锐减监测（涨停开板 / 跌停翘板前兆）────────────────────────
    // 判定规则：60 秒滑动窗口内、至少 4 个采样点（默认 5s 刷新即 20 秒+），
    // 以窗口内封单峰值为基线，当前值较基线累计减少 ≥50% 且下降步数 ≥2 才触发，
    // 排除单笔撤单的瞬时噪音与反复撤单挂回的震荡。封板→开板视为"锐减到 0"。
    // 冷却：同一股票同一方向每天最多提醒一次（当天反复开板不轰炸）。

    private data class SealSample(val ts: Long, val lots: Long)
    private val sealHistory  = mutableMapOf<String, kotlin.collections.ArrayDeque<SealSample>>()
    private val sealNotified = mutableSetOf<String>()
    private var sealTrackDate = ""

    private const val SEAL_WINDOW_MS      = 60_000L
    private const val SEAL_MIN_SAMPLES    = 4
    private const val SEAL_MIN_BASE_LOTS  = 1000L
    private const val SEAL_DROP_RATIO     = 0.5
    private const val SEAL_MIN_DROPS      = 2
    // 断崖特判：单个刷新周期内单笔撤单 ≥ 基线 50%（基线 ≥ 1万手）即视为抽单，
    // 不要求多步趋势、只需 2 个采样点——大额撤单本身已是最强开板/翘板前兆
    private const val SEAL_CLIFF_BASE_LOTS = 10_000L

    /** 每次行情刷新后调用（quotes 为本次拉到的全部行情，含无封单的标的） */
    fun checkSealShrink(quotes: Map<String, StockQuote>) {
        if (!StockliteState.getInstance().enableSealShrinkAlert) return
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        if (today != sealTrackDate) { sealHistory.clear(); sealNotified.clear(); sealTrackDate = today }

        for ((sym, q) in quotes) {
            val lots = q.sealLots
            if (lots == null) {
                // 当前未封板：若刚有封板历史，按封单归零评估一次（开板/翘板事件），随后清空
                for (d in arrayOf("U", "D")) {
                    sealHistory.remove(sym + d)?.let { dq ->
                        if (dq.isNotEmpty()) evaluateSealShrink(sym + d, q.name, dq.maxOf { it.lots }, 0, today)
                    }
                }
                continue
            }
            val dir = if (lots > 0) "U" else "D"
            val key = sym + dir
            // 涨跌方向切换（开板后反向封板）不沿用旧基线
            sealHistory.keys.removeIf { it.startsWith(sym) && !it.endsWith(dir) }
            val dq = sealHistory.getOrPut(key) { kotlin.collections.ArrayDeque() }
            dq.addLast(SealSample(now, Math.abs(lots)))
            while (dq.size > 1 && now - dq.first().ts > SEAL_WINDOW_MS) dq.removeFirst()
            if (dq.size < 2) continue
            var drops = 0
            var maxStepDrop = 0L
            for (i in 1 until dq.size) {
                if (dq[i].lots < dq[i - 1].lots) drops++
                maxStepDrop = Math.max(maxStepDrop, dq[i - 1].lots - dq[i].lots)
            }
            val base = dq.maxOf { it.lots }
            val cliff = base >= SEAL_CLIFF_BASE_LOTS && maxStepDrop >= base * SEAL_DROP_RATIO
            if (dq.size >= SEAL_MIN_SAMPLES || cliff) {
                // 断崖抽单跳过趋势确认（drops 视为达标）
                evaluateSealShrink(key, q.name, base, dq.last().lots, today,
                                   if (cliff) SEAL_MIN_DROPS else drops)
            }
        }
    }

    private fun evaluateSealShrink(key: String, name: String, base: Long, cur: Long,
                                   today: String, drops: Int = SEAL_MIN_DROPS) {
        if (drops < SEAL_MIN_DROPS) return
        if (base < SEAL_MIN_BASE_LOTS) return
        if (cur > base * (1 - SEAL_DROP_RATIO)) return
        if (!sealNotified.add("$today|$key")) return

        val pct  = ((base - cur) * 100 / base).toInt()
        val from = L10n.lotsText(base)
        val to   = L10n.lotsText(cur)
        val msg  = if (key.endsWith("U")) L10n.sealShrinkMsgUp(name, from, to, pct)
                   else                  L10n.sealShrinkMsgDown(name, from, to, pct)
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("StockLite Alerts")
                .createNotification(L10n.sealShrinkTitle, msg, NotificationType.WARNING)
                .notify(null)
        } catch (_: Exception) {
            // 通知组不可用时静默降级
        }
    }
}
