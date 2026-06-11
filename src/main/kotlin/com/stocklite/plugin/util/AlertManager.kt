package com.stocklite.plugin.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
}
