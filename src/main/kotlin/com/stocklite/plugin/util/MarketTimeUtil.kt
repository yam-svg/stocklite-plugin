package com.stocklite.plugin.util

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object MarketTimeUtil {

    private val SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai")

    private val STOCK_MORNING_START = LocalTime.of(9, 30)
    private val STOCK_MORNING_END   = LocalTime.of(11, 30)
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
        val t = now.toLocalTime()
        return when {
            now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY -> "休市（周末）"
            t < STOCK_MORNING_START -> "未开市（${STOCK_MORNING_START} 开盘）"
            t in STOCK_MORNING_START..STOCK_MORNING_END -> "交易中（上午盘）"
            t < STOCK_AFTERNOON_START -> "午休中（${STOCK_AFTERNOON_START} 开盘）"
            t in STOCK_AFTERNOON_START..STOCK_AFTERNOON_END -> "交易中（下午盘）"
            else -> "已收盘"
        }
    }

    /** 行情刷新间隔（ms）：开市 5s，收市 60s */
    fun refreshIntervalMs(): Long = if (isStockMarketOpen()) 5_000L else 60_000L
}
