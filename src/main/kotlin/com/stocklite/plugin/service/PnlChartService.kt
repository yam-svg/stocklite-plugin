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
     * 计算单只股票的每日浮动盈亏。
     * 计算方式与面板表格一致：(历史收盘价 - 当前成本价) × 当前持仓量。
     * 仅展示第一笔买入记录日期之后的数据点。
     */
    fun calcStockPnl(
        stock: StockData,
        records: List<TradeRecordData>,
        kline: List<ChartDataService.ChartPoint>
    ): List<DailyPnl> {
        if (stock.quantity <= 0 || stock.costPrice <= 0 || kline.isEmpty()) return emptyList()

        val firstTradeDate = records.minOfOrNull { it.tradeAt }
            ?.let { Instant.ofEpochMilli(it).atZone(SH).toLocalDate() }
            ?: return emptyList()

        return kline.sortedBy { it.time }.mapNotNull { pt ->
            val date = Instant.ofEpochSecond(pt.time).atZone(SH).toLocalDate()
            if (date < firstTradeDate) return@mapNotNull null
            val pnl = (pt.value - stock.costPrice) * stock.quantity
            DailyPnl(date, pnl)
        }
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
