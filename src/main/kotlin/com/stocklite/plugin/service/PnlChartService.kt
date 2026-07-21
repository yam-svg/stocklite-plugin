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
     * 计算单只股票持仓期间每日浮动盈亏。
     * 原理：遍历历史 K 线，在每个交易日按交易记录重放持仓，
     * 用「当日收盘价 × 当日持仓量 − 历史累计买入成本」得出浮动盈亏。
     *
     * @param stock 股票数据（用于 symbol）
     * @param records 交易记录（按时间升序）
     * @param kline 历史日 K 线（ChartPoint 列表，time 为 Unix 秒）
     */
    fun calcStockPnl(
        stock: StockData,
        records: List<TradeRecordData>,
        kline: List<ChartDataService.ChartPoint>
    ): List<DailyPnl> {
        if (records.isEmpty() || kline.isEmpty()) return emptyList()

        // 交易记录按日期升序排列
        val sortedRecords = records.sortedBy { it.tradeAt }
        val firstTradeDate = Instant.ofEpochMilli(sortedRecords.first().tradeAt)
            .atZone(SH).toLocalDate()

        // K 线 → date : closePrice
        val priceByDate = kline.associate { pt ->
            Instant.ofEpochSecond(pt.time).atZone(SH).toLocalDate() to pt.value
        }

        // 按日期遍历 K 线，重放持仓状态
        var qty  = 0.0
        var cost = 0.0   // 累计买入总成本
        var recIdx = 0
        val result = mutableListOf<DailyPnl>()

        for (pt in kline.sortedBy { it.time }) {
            val date = Instant.ofEpochSecond(pt.time).atZone(SH).toLocalDate()
            if (date < firstTradeDate) continue

            // 应用当天及之前所有未处理的交易记录
            while (recIdx < sortedRecords.size) {
                val rec = sortedRecords[recIdx]
                val recDate = Instant.ofEpochMilli(rec.tradeAt).atZone(SH).toLocalDate()
                if (recDate > date) break
                when (rec.tradeType) {
                    "BUY" -> {
                        cost += rec.price * rec.quantity
                        qty  += rec.quantity
                    }
                    "SELL" -> {
                        // 卖出按加权均价扣减成本
                        if (qty > 0) cost -= (cost / qty) * rec.quantity
                        qty = (qty - rec.quantity).coerceAtLeast(0.0)
                        if (qty <= 0) cost = 0.0
                    }
                    "ADJUST" -> {
                        cost = rec.price * qty
                    }
                }
                recIdx++
            }

            if (qty <= 0) continue
            val closePrice = pt.value
            val pnl = closePrice * qty - cost
            result.add(DailyPnl(date, pnl))
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
