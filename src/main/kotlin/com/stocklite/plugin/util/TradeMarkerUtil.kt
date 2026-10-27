package com.stocklite.plugin.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 交易记录 → K 线买卖点标记（B/S）的纯匹配逻辑。
 * 不依赖任何 IDE/图表类，输入输出均为简单数据类型，便于独立单测。
 */
object TradeMarkerUtil {

    private val shZone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 输入：一笔交易的精简视图 */
    data class TradeLite(val tradeAt: Long, val type: String, val price: Double, val quantity: Double)

    /** 输出：聚合到某根 K 线上的同类交易标记 */
    data class Marker(
        val time: Long,            // 命中的 K 线 bar time（与 ChartPoint.time 对齐）
        val type: String,          // "BUY" | "SELL"
        val trades: List<TradeLite> // 同一根 K 线上的同类交易（可能多笔）
    )

    /**
     * 把交易按交易日对齐到 K 线 bar：
     * - 优先精确命中交易日对应的 bar；
     * - 非交易日（周末/节假日）顺延到其后最近一根 bar；
     * - 晚于最后一根 bar 的交易丢弃（图上无对应位置）。
     * 结果按 time 升序、同 bar 内先 BUY 后 SELL，满足 lightweight-charts setMarkers 的排序要求。
     */
    fun match(trades: List<TradeLite>, barTimes: List<Long>): List<Marker> {
        if (trades.isEmpty() || barTimes.isEmpty()) return emptyList()
        val bars = barTimes.distinct().sorted()
        val barDays = bars.map { Instant.ofEpochSecond(it).atZone(shZone).toLocalDate() }

        // 交易日 → bar 下标：精确命中 > 顺延到下一交易日 > 丢弃（超出数据右边界）
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(shZone).toLocalDate()
        val grouped = LinkedHashMap<Pair<Long, String>, MutableList<TradeLite>>()
        for (t in trades) {
            if (t.tradeAt <= 0L) continue
            if (t.type != "BUY" && t.type != "SELL") continue
            val day = dayOf(t.tradeAt)
            val idx = barDays.indexOfFirst { it == day }
                .takeIf { it >= 0 }
                ?: barDays.indexOfFirst { it.isAfter(day) }
                .takeIf { it >= 0 }
                ?: continue  // 交易日晚于全部 K 线，无处可标
            grouped.getOrPut(bars[idx] to t.type) { mutableListOf() }.add(t)
        }
        return grouped.entries
            .map { (k, v) -> Marker(k.first, k.second, v.sortedBy { it.tradeAt }) }
            .sortedWith(compareBy({ it.time }, { if (it.type == "BUY") 0 else 1 }))
    }

    /**
     * 日内（分时）图买卖点标记：只标“分时数据所属交易日当天”的交易。
     * 交易记录只精确到日、没有成交时刻，按成交价反查日内曲线定位：
     * - 价格有效时取第一个与成交价相差不超过 max(1 分钱, 0.1%) 的分时点（即价格曲线首次触及处）；
     * - 全天未触及（价填偏或数据缺口）则标在全天价格最接近该值的位置；
     * - 价格未填（<=0）时标在开盘第一个点。
     * 输入 points 为分时点的 (time, price)，time 为秒级时间戳。
     */
    fun matchIntraday(trades: List<TradeLite>, points: List<Pair<Long, Double>>): List<Marker> {
        if (trades.isEmpty() || points.isEmpty()) return emptyList()
        val day = Instant.ofEpochSecond(points.last().first).atZone(shZone).toLocalDate()
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(shZone).toLocalDate()
        val grouped = LinkedHashMap<Pair<Long, String>, MutableList<TradeLite>>()
        for (t in trades) {
            if (t.tradeAt <= 0L) continue
            if (t.type != "BUY" && t.type != "SELL") continue
            if (dayOf(t.tradeAt) != day) continue  // 只标当天交易，历史交易归 K 线周期展示
            val idx = if (t.price > 0) {
                val tol = maxOf(0.01, t.price * 0.001)
                val touch = points.indexOfFirst { (_, p) -> p > 0 && abs(p - t.price) <= tol }
                if (touch >= 0) touch
                else points.indices.minByOrNull { i -> abs(points[i].second - t.price) } ?: 0
            } else 0
            grouped.getOrPut(points[idx].first to t.type) { mutableListOf() }.add(t)
        }
        return grouped.entries
            .map { (k, v) -> Marker(k.first, k.second, v.sortedBy { it.tradeAt }) }
            .sortedWith(compareBy({ it.time }, { if (it.type == "BUY") 0 else 1 }))
    }
}
