package com.stocklite.plugin.service

import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.TradeRecordData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object PnlChartService {

    private val SH = ZoneId.of("Asia/Shanghai")

    data class DailyPnl(val date: LocalDate, val pnl: Double)

    /**
     * 单只股票单日回放明细：累计盈亏 / 当日盈亏 / 当日收益率。
     * dayPct 的分母取上一交易日收盘市值，首日取（市值 − 当日盈亏）≈ 成本口径；
     * 盘中加减仓会让当日比例略有偏差，明细展示可接受。
     */
    data class DayRow(val date: LocalDate, val cumPnl: Double, val dayPnl: Double, val dayPct: Double)

    /**
     * 按时间顺序回放交易记录计算单只股票的逐日明细（已实现 + 未实现）。
     * 每个 K 线日期的盈亏 = 截至当日累计已实现盈亏 + (当日收盘价 − 当时成本价) × 当时持仓量。
     */
    fun calcStockDayRows(
        stock: StockData,
        records: List<TradeRecordData>,
        kline: List<ChartDataService.ChartPoint>
    ): List<DayRow> {
        // 手动录入的持仓（直接设置数量/成本价、从未记过交易）没有交易记录，
        // 按 createdAt 时点以成本价合成一笔建仓，使走势/日历对这类持仓也有效。
        val effectiveRecords = records.ifEmpty {
            if (stock.quantity > 0 && stock.costPrice > 0)
                listOf(TradeRecordData().apply {
                    tradeType = "BUY"
                    price     = stock.costPrice
                    quantity  = stock.quantity
                    tradeAt   = if (stock.createdAt > 0) stock.createdAt else 0L
                })
            else emptyList()
        }
        if (effectiveRecords.isEmpty() || kline.isEmpty()) return emptyList()

        val sortedRecords = effectiveRecords.sortedBy { it.tradeAt }
        val firstTradeDate = Instant.ofEpochMilli(sortedRecords.first().tradeAt).atZone(SH).toLocalDate()

        var tradeIdx = 0
        var qty      = 0.0
        var cost     = 0.0
        var realized = 0.0
        var prevCum  = 0.0
        var prevMkt  = 0.0
        var hasPrev  = false
        val result   = mutableListOf<DayRow>()

        for (pt in kline.sortedBy { it.time }) {
            val date = Instant.ofEpochSecond(pt.time).atZone(SH).toLocalDate()
            if (date < firstTradeDate) continue

            // 回放截至当日的所有交易
            while (tradeIdx < sortedRecords.size) {
                val r = sortedRecords[tradeIdx]
                if (Instant.ofEpochMilli(r.tradeAt).atZone(SH).toLocalDate() > date) break
                when (r.tradeType) {
                    "BUY" -> {
                        val newQty = qty + r.quantity
                        cost = if (newQty > 0) (cost * qty + r.price * r.quantity) / newQty else r.price
                        qty  = newQty
                    }
                    "SELL" -> {
                        val actualSell = minOf(r.quantity, qty)
                        if (actualSell > 0) realized += (r.price - cost) * actualSell
                        qty = (qty - r.quantity).coerceAtLeast(0.0)
                    }
                    "ADJUST" -> cost = r.price
                }
                tradeIdx++
            }

            val unrealized = if (qty > 0 && cost > 0) (pt.value - cost) * qty else 0.0
            val cum = realized + unrealized
            val mkt = if (qty > 0) pt.value * qty else 0.0
            val dayPnl  = cum - prevCum
            val denom   = if (hasPrev && prevMkt > 0) prevMkt else mkt - dayPnl
            val dayPct  = if (denom > 0) dayPnl / denom * 100.0 else 0.0
            result.add(DayRow(date, cum, dayPnl, dayPct))
            prevCum = cum; prevMkt = mkt; hasPrev = true
        }

        return result
    }

    /** 计算单只股票的每日累计盈亏（已实现 + 未实现），供走势图使用 */
    fun calcStockPnl(
        stock: StockData,
        records: List<TradeRecordData>,
        kline: List<ChartDataService.ChartPoint>
    ): List<DailyPnl> = calcStockDayRows(stock, records, kline).map { DailyPnl(it.date, it.cumPnl) }

    /**
     * 计算所有持仓股票的总盈亏（每日合计），
     * 输入为各股票的 DailyPnl 列表，按日期对齐后求和。
     */
    fun mergePortfolioPnl(allStockPnl: List<List<DailyPnl>>): List<DailyPnl> {
        val map = mutableMapOf<LocalDate, Double>()
        for (stockPnl in allStockPnl) {
            for (dp in stockPnl) {
                map[dp.date] = (map[dp.date] ?: 0.0) + dp.pnl
            }
        }
        return map.entries.sortedBy { it.key }.map { DailyPnl(it.key, it.value) }
    }

    /** 将 DailyPnl 列表转为 ChartPoint 列表供图表显示 */
    fun toChartPoints(pnlList: List<DailyPnl>): List<ChartDataService.ChartPoint> =
        pnlList.map { dp ->
            ChartDataService.ChartPoint(
                time  = dp.date.atStartOfDay(SH).toInstant().epochSecond,
                value = dp.pnl
            )
        }
}
