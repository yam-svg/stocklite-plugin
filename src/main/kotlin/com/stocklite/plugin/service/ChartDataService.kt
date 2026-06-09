package com.stocklite.plugin.service

import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * 日内走势数据服务（与原 Electron 版业务逻辑完全一致）
 *
 * 股票：新浪 CN_MarketDataService.getKLineData（1分钟优先，5分钟降级）
 *        响应字段 day / close（注意：不是 d / c）
 *
 * 国内期货（nf_）：stock2.finance.sina.com.cn InnerFuturesNewService.getFewMinLine
 *        响应字段 d / c
 *
 * 国际期货（hf_）：stock2.finance.sina.com.cn GlobalFuturesService.getGlobalFuturesMinLine
 *        响应结构 minLine_1d: [[首条 10+ 字段], [普通 6 字段], ...]
 *
 * A 股指数：新浪 CN_MarketDataService.getKLineData（1分钟 scale=1）
 *        响应字段 day / close
 *
 * 其他全球指数：Yahoo Finance v8/finance/chart?interval=5m&range=1d
 */
object ChartDataService {

    /** 返回给图表使用的点：Unix 秒 + 价格（调用方再转换成涨跌幅百分比） */
    data class ChartPoint(val time: Long, val value: Double)

    // A 股指数 Yahoo symbol → 新浪 sh/sz symbol
    private val CN_SINA_MAP = mapOf(
        "000001.SS" to "sh000001",
        "399001.SZ" to "sz399001",
        "399006.SZ" to "sz399006",
        "000300.SS" to "sh000300"
    )

    private val sdfShanghai: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").also {
            it.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }

    // ── 公共入口 ────────────────────────────────────────────────────────

    /** 股票日内（新浪 KLine，1分钟优先，5分钟降级）。symbol 如 "sh600519" */
    fun getStockIntraday(symbol: String): List<ChartPoint> {
        // 1 分钟粒度，datalen=600 覆盖完整交易日
        val oneMin = fetchSinaKline(symbol, scale = 1, datalen = 600)
        if (oneMin.isNotEmpty()) return oneMin
        // 降级：5 分钟
        return fetchSinaKline(symbol, scale = 5, datalen = 288)
    }

    /**
     * 期货日内。symbol 如 "nf_IF2506"、"hf_GC"
     * 优先新浪期货分时接口；国际期货走 GlobalFuturesService
     */
    fun getFutureIntraday(symbol: String): List<ChartPoint> {
        val normalized = normalizeFuture(symbol)
        return if (normalized.startsWith("nf_")) {
            val contract = normalized.removePrefix("nf_")
            fetchDomesticFutureMinLine(contract)
        } else {
            val contract = normalized.removePrefix("hf_")
            fetchGlobalFutureMinLine(contract)
        }
    }

    /**
     * 全球指数日内。A 股指数走新浪 1 分钟 KLine，其余走 Yahoo 5m。
     * @param yahooSymbol  如 "^GSPC"、"000001.SS"
     */
    fun getGlobalIntraday(yahooSymbol: String): List<ChartPoint> {
        val sinaSymbol = CN_SINA_MAP[yahooSymbol]
        return if (sinaSymbol != null) {
            val pts = fetchSinaKline(sinaSymbol, scale = 1, datalen = 600)
            if (pts.isNotEmpty()) pts else fetchSinaKline(sinaSymbol, scale = 5, datalen = 288)
        } else {
            fetchYahooIntraday(yahooSymbol)
        }
    }

    // ── 新浪 A 股 / 指数 KLine ──────────────────────────────────────────
    // 响应格式：[{"day":"2026-06-09 09:35:00","open":"...","high":"...","low":"...","close":"...","volume":"..."},...]

    private fun fetchSinaKline(symbol: String, scale: Int, datalen: Int): List<ChartPoint> {
        val url = "https://quotes.sina.cn/cn/api/json_v2.php/" +
            "CN_MarketDataService.getKLineData?symbol=$symbol&scale=$scale&ma=no&datalen=$datalen"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val today = todayKey()
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val dayStr = obj.optString("day", "")          // 正确字段名是 "day"
                val close  = obj.optString("close", "").toDoubleOrNull() ?: continue
                if (!dayStr.startsWith(today)) continue        // 只取今日
                val t = runCatching { sdfShanghai.get().parse(dayStr)!!.time / 1000L }.getOrNull() ?: continue
                list.add(ChartPoint(t, close))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── 国内期货分时（nf_） ──────────────────────────────────────────────
    // URL: stock2.finance.sina.com.cn/futures/api/json.php/InnerFuturesNewService.getFewMinLine?symbol=IF2506&type=1
    // 响应：[{"d":"2026-06-09 09:35:00","c":3800.0,"v":1234},...]
    // 字段是 d 和 c（与 A 股 KLine 不同）

    private fun fetchDomesticFutureMinLine(contract: String): List<ChartPoint> {
        val url = "https://stock2.finance.sina.com.cn/futures/api/json.php/" +
            "InnerFuturesNewService.getFewMinLine?symbol=$contract&type=1"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val today = todayKey()
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj    = arr.getJSONObject(i)
                val dayStr = obj.optString("d", "")            // 国内期货用 "d"
                val price  = obj.optDouble("c", Double.NaN)   // 国内期货用 "c"
                if (!dayStr.startsWith(today) || !price.isFinite() || price <= 0) continue
                val t = runCatching { sdfShanghai.get().parse(dayStr)!!.time / 1000L }.getOrNull() ?: continue
                list.add(ChartPoint(t, price))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── 国际期货分时（hf_） ──────────────────────────────────────────────
    // URL: stock2.finance.sina.com.cn/futures/api/json.php/GlobalFuturesService.getGlobalFuturesMinLine?symbol=GC
    // 响应：{"minLine_1d": [[首条10+字段], [普通6字段], ...]}
    // 两种行格式（与原 TS 版完全一致）：
    //   首条: [date, preClose, exchange, "-", time, price, volume, ..., dateTime]  (≥10 字段)
    //   普通: [time, price, volume, ..., avgPrice, dateTime]                        (6 字段)

    private fun fetchGlobalFutureMinLine(contract: String): List<ChartPoint> {
        val url = "https://stock2.finance.sina.com.cn/futures/api/json.php/" +
            "GlobalFuturesService.getGlobalFuturesMinLine?symbol=$contract"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val rows = root.optJSONArray("minLine_1d") ?: return emptyList()
            var currentDate = ""
            val latestDateKey: String

            // 先找最后一个有效 dateKey
            val allRows = mutableListOf<Pair<String, Double>>() // dateKey, price
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val values = (0 until row.length()).map { row.optString(it, "") }
                val (dateKey, price) = parseGlobalFutureRow(values, currentDate)
                if (dateKey.isNotEmpty()) currentDate = dateKey.split(" ")[0].ifEmpty { currentDate }
                if (dateKey.isNotEmpty() && price.isFinite() && price > 0) {
                    allRows.add(dateKey to price)
                }
            }
            if (allRows.isEmpty()) return emptyList()
            latestDateKey = allRows.last().first.let { it.split(" ")[0] }

            val list = mutableListOf<ChartPoint>()
            for ((dateTime, price) in allRows) {
                if (!dateTime.startsWith(latestDateKey)) continue
                val t = runCatching {
                    sdfShanghai.get().parse(dateTime)!!.time / 1000L
                }.getOrNull() ?: continue
                list.add(ChartPoint(t, price))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun parseGlobalFutureRow(values: List<String>, currentDate: String): Pair<String, Double> {
        if (values.size >= 10) {
            // 首条格式
            val date   = values[0].ifEmpty { currentDate }
            val time   = values[4]
            val price  = values[5].toDoubleOrNull() ?: return "" to Double.NaN
            val dateTime = values[9].ifEmpty { "$date $time" }
            return dateTime to price
        } else if (values.size >= 6) {
            // 普通格式
            val price    = values[1].toDoubleOrNull() ?: return "" to Double.NaN
            val dateTime = values[5].ifEmpty { "$currentDate ${values[0]}" }
            return dateTime to price
        }
        return "" to Double.NaN
    }

    // ── Yahoo Finance ───────────────────────────────────────────────────

    private fun fetchYahooIntraday(symbol: String): List<ChartPoint> {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=5m&range=1d"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val result = JSONObject(raw)
                .optJSONObject("chart")
                ?.optJSONArray("result")
                ?.optJSONObject(0) ?: return emptyList()
            val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
            val closes = result
                .optJSONObject("indicators")
                ?.optJSONArray("quote")
                ?.optJSONObject(0)
                ?.optJSONArray("close") ?: return emptyList()
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until timestamps.length()) {
                val t = timestamps.optLong(i, -1L)
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i, Double.NaN)
                if (t > 0 && c.isFinite()) list.add(ChartPoint(t, c))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private fun normalizeFuture(raw: String): String {
        val v = raw.trim()
        if (v.startsWith("nf_") || v.startsWith("hf_")) {
            val parts = v.split("_", limit = 2)
            return "${parts[0].lowercase()}_${(parts.getOrElse(1) { "" }).uppercase()}"
        }
        return "nf_${v.uppercase()}"
    }

    /** 今天日期字符串 "2026-06-09"，用于过滤 KLine 中今日的点 */
    private fun todayKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(java.util.Date())
    }
}
