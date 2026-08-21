package com.stocklite.plugin.util

import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.StockQuote
import com.stocklite.plugin.state.TradeRecordData
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
     * 回放交易记录，计算该股票历史 SELL 操作累计的已实现盈亏。
     * 每次 SELL 时的已实现盈亏 = (卖出价 - 当时加权成本价) × 卖出数量。
     */
    fun calcRealizedPnl(records: List<TradeRecordData>): Double {
        val sorted = records.sortedBy { it.tradeAt }
        var qty = 0.0
        var cost = 0.0
        var realized = 0.0
        for (r in sorted) {
            when (r.tradeType) {
                "BUY" -> {
                    val newQty = qty + r.quantity
                    cost = if (newQty > 0) (cost * qty + r.price * r.quantity) / newQty else r.price
                    qty = newQty
                }
                "SELL" -> {
                    val actualSell = minOf(r.quantity, qty)
                    if (actualSell > 0) realized += (r.price - cost) * actualSell
                    qty = (qty - r.quantity).coerceAtLeast(0.0)
                }
                "ADJUST" -> cost = r.price
            }
        }
        return realized
    }

    /**
     * 计算单行今日盈亏（全局共用，StockPanel 和 PortfolioWatcherService 统一调用）。
     *
     * 有交易记录时（精确模式）：
     * - 今日之前已持有的数量（历史仓位）以昨收为基准：q.change × historyQty
     * - 今日每笔 BUY 记录以各自买入价为基准：(price - buyPrice) × qty
     * - 今日 SELL 记录：已卖出部分从持仓中扣除，不计入今日盈亏
     *
     * 无交易记录时（兼容模式，等同原有 snapshotQty 逻辑）：
     * - 今日未动仓：q.change × quantity
     * - 今日建仓/调仓：按 snapshotQty 分拆
     */
    fun calcTodayPnl(s: StockData, q: StockQuote?,
                     records: List<TradeRecordData> = emptyList()): Double {
        if (s.quantity <= 0 || q == null) return 0.0
        val today = LocalDate.now(SHANGHAI_ZONE)

        // ── 精确模式：有今日交易记录 ──
        val todayRecords = records.filter { r ->
            r.tradeAt > 0 &&
            Instant.ofEpochMilli(r.tradeAt).atZone(SHANGHAI_ZONE).toLocalDate() == today
        }
        if (todayRecords.isNotEmpty()) {
            // 今日净买入量（BUY - SELL）
            val todayNetBuy  = todayRecords.filter { it.tradeType == "BUY"  }.sumOf { it.quantity }
            val todayNetSell = todayRecords.filter { it.tradeType == "SELL" }.sumOf { it.quantity }
            // 今日操作前的历史持仓（昨收基准）
            val historyQty = (s.quantity - todayNetBuy + todayNetSell).coerceAtLeast(0.0)
            // 今日买入逐笔计算
            val todayBuyPnl = todayRecords
                .filter { it.tradeType == "BUY" }
                .sumOf { r -> (q.price - r.price) * r.quantity }
            return q.change * historyQty + todayBuyPnl
        }

        // ── 兼容模式：无交易记录，沿用 snapshotQty 逻辑 ──
        val updatedDate = if (s.updatedAt > 0)
            Instant.ofEpochMilli(s.updatedAt).atZone(SHANGHAI_ZONE).toLocalDate()
        else null
        if (updatedDate != today) return q.change * s.quantity

        val snap = s.snapshotQty
        return when {
            // snap < 0：老数据无快照，无法区分新旧仓，回退到昨收基准（保守、不虚高）
            snap < 0    -> q.change * s.quantity
            // snap == 0：今日全新建仓（今天之前持仓为零），以成本价为基准
            snap == 0.0 -> (q.price - s.costPrice) * s.quantity
            // snap > 0：今日加减仓，老仓用昨收、变动部分用成本价
            else        -> q.change * snap + (q.price - s.costPrice) * (s.quantity - snap)
        }
    }
}
