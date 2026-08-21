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
     * 计算单只股票的每日盈亏（已实现 + 未实现）。
     * 按时间顺序回放交易记录，同步推进持仓状态，避免用当前成本价/数量反推历史。
     * 每个 K 线日期的盈亏 = 截至当日累计已实现盈亏 + (当日收盘价 - 当时成本价) × 当时持仓量。
     */
    fun calcStockPnl(
        @Suppress("UNUSED_PARAMETER") stock: StockData,
        records: List<TradeRecordData>,
        kline: List<ChartDataService.ChartPoint>
    ): List<DailyPnl> {
        if (records.isEmpty() || kline.isEmpty()) return emptyList()

        val sortedRecords = records.sortedBy { it.tradeAt }
        val firstTradeDate = Instant.ofEpochMilli(sortedRecords.first().tradeAt).atZone(SH).toLocalDate()

        var tradeIdx = 0
        var qty      = 0.0
        var cost     = 0.0
        var realized = 0.0
        val result   = mutableListOf<DailyPnl>()

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
            result.add(DailyPnl(date, realized + unrealized))
        }

        return result
    }

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
