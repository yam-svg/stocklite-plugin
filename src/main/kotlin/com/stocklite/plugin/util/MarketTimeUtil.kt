package com.stocklite.plugin.util

import java.time.DayOfWeek
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
}
