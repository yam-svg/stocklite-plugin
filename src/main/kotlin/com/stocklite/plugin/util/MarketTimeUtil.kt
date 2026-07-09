package com.stocklite.plugin.util

import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.StockQuote
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object MarketTimeUtil {

    private val SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai")

    private val STOCK_MORNING_START   = LocalTime.of(9,  30)
    private val STOCK_MORNING_END     = LocalTime.of(11, 30)
    private val STOCK_AFTERNOON_START = LocalTime.of(13, 0)
    private val STOCK_AFTERNOON_END   = LocalTime.of(15, 0)

    fun isStockMarketOpen(): Boolean {
        val now = ZonedDateTime.now(SHANGHAI_ZONE)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = now.toLocalTime()
        return (t >= STOCK_MORNING_START && t <= STOCK_MORNING_END) ||
               (t >= STOCK_AFTERNOON_START && t <= STOCK_AFTERNOON_END)
    }

    fun getMarketStatusText(): String {
        val now = ZonedDateTime.now(SHANGHAI_ZONE)
        val t   = now.toLocalTime()
        return when {
            now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY ->
                L10n.statusWeekend
            t < STOCK_MORNING_START ->
                L10n.statusPreOpen(STOCK_MORNING_START.toString())
            t in STOCK_MORNING_START..STOCK_MORNING_END ->
                L10n.statusAMOpen
            t < STOCK_AFTERNOON_START ->
                L10n.statusLunch
            t in STOCK_AFTERNOON_START..STOCK_AFTERNOON_END ->
                L10n.statusPMOpen
            else ->
                L10n.statusClosed
        }
    }

    /** 行情刷新间隔（ms）：开市 5s，收市 60s */
    fun refreshIntervalMs(): Long = if (isStockMarketOpen()) 5_000L else 60_000L

    /**
     * 计算单行今日盈亏（全局共用，StockPanel 和 PortfolioWatcherService 统一调用）。
     * - 若持仓在**今日**有过创建或修改（updatedAt 为今天），以成本价为基准：(现价 - 成本价) × 数量
     * - 否则以昨收为基准：(现价 - 昨收) × 数量，反映今天市场涨跌的影响
     * - 老数据 updatedAt == 0 时，兼容原有逻辑（走昨收分支）
     */
    fun calcTodayPnl(s: StockData, q: StockQuote?): Double {
        if (s.quantity <= 0 || q == null) return 0.0
        val today = LocalDate.now(SHANGHAI_ZONE)
        val updatedDate = if (s.updatedAt > 0)
            Instant.ofEpochMilli(s.updatedAt).atZone(SHANGHAI_ZONE).toLocalDate()
        else null
        return if (updatedDate == today)
            (q.price - s.costPrice) * s.quantity   // 今日建仓/调仓：以实际成本为基准
        else
            q.change * s.quantity                   // 昨日及以前的持仓：以昨收为基准
    }
}
