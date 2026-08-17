package com.stocklite.plugin.service

import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.TimeZone

object ChartDataService {

    /**
     * @param value 收盘价（历史遗留字段名，各处仍按“最新价”语义使用）
     * @param open/high/low 真实开高低价；数据源不提供时为 NaN，前端据此回退为折线/面积图，不伪造K线
     * @param volume 成交量；数据源不提供时为 NaN，前端按 hasVolume 判断是否绘制底部成交量柱
     */
    data class ChartPoint(
        val time: Long, val value: Double,
        val open: Double = Double.NaN, val high: Double = Double.NaN, val low: Double = Double.NaN,
        val volume: Double = Double.NaN
    ) {
        val hasOhlc: Boolean get() = open.isFinite() && high.isFinite() && low.isFinite()
        val hasVolume: Boolean get() = volume.isFinite() && volume > 0
    }

    private val CN_SINA_MAP = mapOf(
        "000001.SS" to "sh000001",
        "399001.SZ" to "sz399001",
        "399006.SZ" to "sz399006",
        "000300.SS" to "sh000300",
        "000688.SS" to "sh000688"
    )

    private val sdfShanghai: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss").also { it.timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    }

    private val sdfDate: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd").also { it.timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun getStockIntraday(symbol: String): List<ChartPoint> {
        // 港股：hk07709 → 腾讯港股分时接口（新浪/Yahoo 均不支持港股 ETF 分时）
        if (symbol.startsWith("hk", ignoreCase = true)) {
            val hkCode = symbol.removePrefix("hk").removePrefix("HK")
            val pts = fetchHkIntraday(hkCode)
            if (pts.isNotEmpty()) return pts
            // 腾讯失败时 fallback 到 Yahoo Finance
            return fetchYahooIntraday("$hkCode.HK")
        }
        val oneMin = fetchSinaKline(symbol, scale = 1, datalen = 600)
        if (oneMin.isNotEmpty()) return oneMin
        return fetchSinaKline(symbol, scale = 5, datalen = 288)
    }

    fun getFutureIntraday(symbol: String): List<ChartPoint> {
        val normalized = normalizeFuture(symbol)
        return if (normalized.startsWith("nf_")) {
            fetchDomesticFutureMinLine(normalized.removePrefix("nf_"))
        } else {
            fetchGlobalFutureMinLine(normalized.removePrefix("hf_"))
        }
    }

    fun getGlobalIntraday(yahooSymbol: String): List<ChartPoint> {
        val sinaSymbol = CN_SINA_MAP[yahooSymbol]
        return if (sinaSymbol != null) {
            val pts = fetchSinaKline(sinaSymbol, scale = 1, datalen = 600)
            if (pts.isNotEmpty()) pts else fetchSinaKline(sinaSymbol, scale = 5, datalen = 288)
        } else {
            fetchYahooIntraday(yahooSymbol)
        }
    }

    /**
     * 历史 K 线（日/周/月），供图表面板切换周期使用。
     * @param symbol  A 股 "sh600519"，全球指数 "^GSPC"，期货 "nf_IF0"/"hf_NQ"，基金 "fund_161725"（暂不支持）
     * @param period  "daily" | "weekly" | "monthly"
     * @param count   数据点数量
     */
    fun getHistoryKLine(symbol: String, period: String, count: Int): List<ChartPoint> {
        // 期货（国内/外盘）→ 新浪期货日K接口；周/月无原生接口，本地重采样
        if (symbol.startsWith("nf_", true) || symbol.startsWith("hf_", true)) {
            val normalized = normalizeFuture(symbol)
            val daily = if (normalized.startsWith("nf_"))
                fetchDomesticFutureDailyHistory(normalized.removePrefix("nf_"))
            else
                fetchGlobalFutureDailyHistory(normalized.removePrefix("hf_"))
            return resampleDaily(daily, period, count)
        }
        // 港股：hk07709 → 腾讯 newfqkline 接口，fallback Yahoo Finance
        if (symbol.startsWith("hk", ignoreCase = true)) {
            val hkCode = symbol.removePrefix("hk").removePrefix("HK")
            val pts = fetchHkHistory(hkCode, period, count)
            if (pts.isNotEmpty()) return pts
            val yahooInterval = when (period) { "daily" -> "1d"; "weekly" -> "1wk"; "monthly" -> "1mo"; else -> "1d" }
            val yahooRange    = when (period) { "daily" -> "3mo"; "weekly" -> "1y"; "monthly" -> "5y"; else -> "3mo" }
            return fetchYahooHistory("$hkCode.HK", yahooInterval, yahooRange, count)
        }
        // 基金（纯数字代码，如 "017437"）→ 天天基金净值历史，resample 到日/周/月
        if (symbol.all { it.isDigit() } && symbol.length == 6) {
            val daily = fetchFundNavHistory(symbol)
            if (daily.isNotEmpty()) return resampleDaily(daily, period, count)
            // fallback：部分场内 ETF 有 K 线
            val prefixed = if (symbol.startsWith("6")) "sh$symbol" else "sz$symbol"
            val pts = fetchTencentAShareHistory(prefixed, period, count)
            if (pts.isNotEmpty()) return pts
            val scale = when (period) { "daily" -> 240; "weekly" -> 1200; "monthly" -> 5000; else -> 240 }
            return fetchSinaKlineHistory(prefixed, scale, count)
        }
        // A 股 / 国内指数 → 腾讯 qfq（前复权，避免分红拆分断层），fallback 新浪
        if (symbol.startsWith("sh") || symbol.startsWith("sz")) {
            val pts = fetchTencentAShareHistory(symbol, period, count)
            if (pts.isNotEmpty()) return pts
            val scale = when (period) { "daily" -> 240; "weekly" -> 1200; "monthly" -> 5000; else -> 240 }
            return fetchSinaKlineHistory(symbol, scale, count)
        }
        // A 股指数（Yahoo symbol → Sina）
        val sinaSymbol = CN_SINA_MAP[symbol]
        if (sinaSymbol != null) {
            val pts = fetchTencentAShareHistory(sinaSymbol, period, count)
            if (pts.isNotEmpty()) return pts
            val scale = when (period) { "daily" -> 240; "weekly" -> 1200; "monthly" -> 5000; else -> 240 }
            return fetchSinaKlineHistory(sinaSymbol, scale, count)
        }
        // 全球指数 → Yahoo Finance
        // 不能用 max：历史超长的指数超过 Yahoo 单次约 1000 条限制会静默降频
        // 日K: 5y ≈ 1260条；周K: 10y ≈ 520条；月K: 10y ≈ 120条，均安全
        val yahooRange = when (period) {
            "daily"   -> "5y"
            "weekly"  -> "10y"
            "monthly" -> "10y"
            else      -> "5y"
        }
        val yahooInterval = when (period) {
            "daily"   -> "1d"
            "weekly"  -> "1wk"
            "monthly" -> "1mo"
            else      -> "1d"
        }
        return fetchYahooHistory(symbol, yahooInterval, yahooRange, count)
    }

    // ── Sina A-share intraday KLine ─────────────────────────────────────

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
                val dayStr = obj.optString("day", "")
                val close  = obj.optString("close", "").toDoubleOrNull() ?: continue
                if (!dayStr.startsWith(today)) continue
                val t = runCatching { sdfShanghai.get().parse(dayStr)!!.time / 1000L }.getOrNull() ?: continue
                val open = obj.optString("open", "").toDoubleOrNull() ?: Double.NaN
                val high = obj.optString("high", "").toDoubleOrNull() ?: Double.NaN
                val low  = obj.optString("low", "").toDoubleOrNull() ?: Double.NaN
                val vol  = obj.optString("volume", "").toDoubleOrNull() ?: Double.NaN
                list.add(ChartPoint(t, close, open, high, low, vol))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    /** 历史 K 线：不过滤今日，取全部；追加实时今日 bar（日内场中也能显示当日K线） */
    private fun fetchSinaKlineHistory(symbol: String, scale: Int, datalen: Int): List<ChartPoint> {
        val url = "https://quotes.sina.cn/cn/api/json_v2.php/" +
            "CN_MarketDataService.getKLineData?symbol=$symbol&scale=$scale&ma=no&datalen=$datalen"
        val raw = HttpUtil.get(url) ?: return emptyList()
        val list = try {
            val arr = JSONArray(raw)
            val result = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj    = arr.getJSONObject(i)
                val dayStr = obj.optString("day", "")
                val close  = obj.optString("close", "").toDoubleOrNull() ?: continue
                // 历史 K 线只有日期部分 "2026-06-09"，补 00:00:00
                val dateStr = if (dayStr.length == 10) "$dayStr 00:00:00" else dayStr
                val t = runCatching { sdfShanghai.get().parse(dateStr)!!.time / 1000L }.getOrNull() ?: continue
                val open = obj.optString("open", "").toDoubleOrNull() ?: Double.NaN
                val high = obj.optString("high", "").toDoubleOrNull() ?: Double.NaN
                val low  = obj.optString("low", "").toDoubleOrNull() ?: Double.NaN
                val vol  = obj.optString("volume", "").toDoubleOrNull() ?: Double.NaN
                result.add(ChartPoint(t, close, open, high, low, vol))
            }
            result
        } catch (_: Exception) { return emptyList() }

        // 仅日K（scale=240）追加今日实时 bar；周/月K无需（收盘前数据无意义）
        if (scale != 240) return list
        val todayBar = fetchSinaTodayBar(symbol) ?: return list
        val todayStr = todayKey()
        val todayTs  = runCatching {
            sdfShanghai.get().parse("$todayStr 00:00:00")!!.time / 1000L
        }.getOrNull() ?: return list
        // 若末尾已有今日数据则替换，否则追加
        if (list.isNotEmpty() && list.last().time == todayTs) {
            return list.dropLast(1) + todayBar.copy(time = todayTs)
        }
        return list + todayBar.copy(time = todayTs)
    }

    /** 通过新浪实时行情接口获取今日 open/high/low/close/volume */
    private fun fetchSinaTodayBar(symbol: String): ChartPoint? {
        val raw = HttpUtil.getGbk(
            "http://hq.sinajs.cn/list=$symbol", "http://finance.sina.com.cn", label = "日K今日bar"
        ) ?: return null
        return try {
            val m = Regex("""hq_str_[^=]+="([^"]+)"""").find(raw) ?: return null
            val f = m.groupValues[1].split(",")
            // 新浪 A 股字段：0=名称,1=今开,2=昨收,3=现价,4=最高,5=最低,...,8=今日累计成交量(股)
            val open  = f.getOrElse(1) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val close = f.getOrElse(3) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val high  = f.getOrElse(4) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val low   = f.getOrElse(5) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val vol   = f.getOrElse(8) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: Double.NaN
            ChartPoint(0L, close, open, high, low, vol)
        } catch (_: Exception) { null }
    }

    // ── Domestic futures intraday (nf_) ────────────────────────────────

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
                val dayStr = obj.optString("d", "")
                val price  = obj.optDouble("c", Double.NaN)
                if (!dayStr.startsWith(today) || !price.isFinite() || price <= 0) continue
                val t = runCatching { sdfShanghai.get().parse(dayStr)!!.time / 1000L }.getOrNull() ?: continue
                val open = obj.optDouble("o", Double.NaN)
                val high = obj.optDouble("h", Double.NaN)
                val low  = obj.optDouble("l", Double.NaN)
                val vol  = obj.optDouble("v", Double.NaN)
                list.add(ChartPoint(t, price, open, high, low, vol))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    /** 国内期货日K线（供周期切换的日/周/月使用；周/月由 resampleDaily 本地聚合） */
    private fun fetchDomesticFutureDailyHistory(contract: String): List<ChartPoint> {
        val url = "https://stock2.finance.sina.com.cn/futures/api/json.php/" +
            "InnerFuturesNewService.getDailyKLine?symbol=$contract&datalen=2000"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj    = arr.getJSONObject(i)
                val dayStr = obj.optString("d", "")
                val close  = obj.optDouble("c", Double.NaN)
                if (dayStr.isEmpty() || !close.isFinite() || close <= 0) continue
                val dateStr = if (dayStr.length == 10) "$dayStr 00:00:00" else dayStr
                val t = runCatching { sdfShanghai.get().parse(dateStr)!!.time / 1000L }.getOrNull() ?: continue
                val open = obj.optDouble("o", Double.NaN)
                val high = obj.optDouble("h", Double.NaN)
                val low  = obj.optDouble("l", Double.NaN)
                val vol  = obj.optDouble("v", Double.NaN)
                list.add(ChartPoint(t, close, open, high, low, vol))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── Global futures intraday (hf_) ──────────────────────────────────

    private fun fetchGlobalFutureMinLine(contract: String): List<ChartPoint> {
        val url = "https://stock2.finance.sina.com.cn/futures/api/json.php/" +
            "GlobalFuturesService.getGlobalFuturesMinLine?symbol=$contract"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val rows = root.optJSONArray("minLine_1d") ?: return emptyList()
            var currentDate = ""
            val allRows = mutableListOf<Pair<String, Double>>()
            for (i in 0 until rows.length()) {
                val row    = rows.optJSONArray(i) ?: continue
                val values = (0 until row.length()).map { row.optString(it, "") }
                val (dateKey, price) = parseGlobalFutureRow(values, currentDate)
                if (dateKey.isNotEmpty()) currentDate = dateKey.split(" ")[0].ifEmpty { currentDate }
                if (dateKey.isNotEmpty() && price.isFinite() && price > 0) allRows.add(dateKey to price)
            }
            if (allRows.isEmpty()) return emptyList()
            val latestDateKey = allRows.last().first.split(" ")[0]
            val list = mutableListOf<ChartPoint>()
            for ((dateTime, price) in allRows) {
                if (!dateTime.startsWith(latestDateKey)) continue
                val t = runCatching { sdfShanghai.get().parse(dateTime)!!.time / 1000L }.getOrNull() ?: continue
                list.add(ChartPoint(t, price))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 外盘期货日K线（供周期切换的日/周/月使用；周/月由 resampleDaily 本地聚合）。
     * 注：新浪该接口不提供成交量/持仓量（恒为 0），仅日期+OHLC 可用。
     */
    private fun fetchGlobalFutureDailyHistory(contract: String): List<ChartPoint> {
        val url = "https://stock2.finance.sina.com.cn/futures/api/json.php/" +
            "GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=$contract&datalen=2000"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                val dateStr = obj.optString("date", "")
                val close   = obj.optString("close", "").toDoubleOrNull() ?: continue
                if (dateStr.isEmpty() || close <= 0) continue
                val t = runCatching { sdfShanghai.get().parse("$dateStr 00:00:00")!!.time / 1000L }.getOrNull() ?: continue
                val open = obj.optString("open", "").toDoubleOrNull() ?: Double.NaN
                val high = obj.optString("high", "").toDoubleOrNull() ?: Double.NaN
                val low  = obj.optString("low", "").toDoubleOrNull() ?: Double.NaN
                list.add(ChartPoint(t, close, open, high, low))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    /** 期货日K → 周/月本地重采样（新浪期货接口无原生周/月K线） */
    private fun resampleDaily(daily: List<ChartPoint>, period: String, count: Int): List<ChartPoint> {
        if (period != "weekly" && period != "monthly") return daily.takeLast(count)
        val zone = ZoneId.of("Asia/Shanghai")
        val buckets = linkedMapOf<String, MutableList<ChartPoint>>()
        for (p in daily) {
            val date = Instant.ofEpochSecond(p.time).atZone(zone).toLocalDate()
            val key = if (period == "weekly") {
                val wf = WeekFields.ISO
                "${date.get(wf.weekBasedYear())}-W${date.get(wf.weekOfWeekBasedYear())}"
            } else {
                "${date.year}-${date.monthValue}"
            }
            buckets.getOrPut(key) { mutableListOf() }.add(p)
        }
        return buckets.values.map { bucket ->
            // 周期内成交量求和（过滤 NaN/负值）
            val sumVol = bucket.mapNotNull { it.volume.takeIf { v -> v.isFinite() && v > 0 } }.sum()
            ChartPoint(
                time  = bucket.last().time,
                value = bucket.last().value,
                open  = bucket.first().open,
                high  = bucket.mapNotNull { it.high.takeIf(Double::isFinite) }.maxOrNull() ?: Double.NaN,
                low   = bucket.mapNotNull { it.low.takeIf(Double::isFinite) }.minOrNull() ?: Double.NaN,
                volume = if (sumVol > 0) sumVol else Double.NaN
            )
        }.takeLast(count)
    }

    private fun parseGlobalFutureRow(values: List<String>, currentDate: String): Pair<String, Double> {
        if (values.size >= 10) {
            val date     = values[0].ifEmpty { currentDate }
            val time     = values[4]
            val price    = values[5].toDoubleOrNull() ?: return "" to Double.NaN
            val dateTime = values[9].ifEmpty { "$date $time" }
            return dateTime to price
        } else if (values.size >= 6) {
            val price    = values[1].toDoubleOrNull() ?: return "" to Double.NaN
            val dateTime = values[5].ifEmpty { "$currentDate ${values[0]}" }
            return dateTime to price
        }
        return "" to Double.NaN
    }

    // ── Yahoo Finance intraday ──────────────────────────────────────────

    private fun fetchYahooIntraday(symbol: String): List<ChartPoint> {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=5m&range=1d"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val result = JSONObject(raw).optJSONObject("chart")
                ?.optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
            val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
            val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
            val closes = quote.optJSONArray("close") ?: return emptyList()
            val opens  = quote.optJSONArray("open")
            val highs  = quote.optJSONArray("high")
            val lows   = quote.optJSONArray("low")
            val vols   = quote.optJSONArray("volume")
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until timestamps.length()) {
                val t = timestamps.optLong(i, -1L)
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i, Double.NaN)
                if (t <= 0 || !c.isFinite()) continue
                val o = if (opens != null && !opens.isNull(i)) opens.optDouble(i, Double.NaN) else Double.NaN
                val h = if (highs != null && !highs.isNull(i)) highs.optDouble(i, Double.NaN) else Double.NaN
                val l = if (lows  != null && !lows.isNull(i))  lows.optDouble(i, Double.NaN)  else Double.NaN
                val v = if (vols  != null && !vols.isNull(i))  vols.optDouble(i, Double.NaN)  else Double.NaN
                list.add(ChartPoint(t, c, o, h, l, v))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── Yahoo Finance history (daily/weekly/monthly) ────────────────────

    private fun fetchYahooHistory(symbol: String, interval: String, range: String, maxCount: Int): List<ChartPoint> {
        val yahooSym = when (symbol) {
            "^HSTECH" -> "HSTECH.HK"
            else -> symbol
        }
        val enc = URLEncoder.encode(yahooSym, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=$interval&range=$range"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val result = JSONObject(raw).optJSONObject("chart")
                ?.optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
            val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
            val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
            val closes = quote.optJSONArray("close") ?: return emptyList()
            val opens  = quote.optJSONArray("open")
            val highs  = quote.optJSONArray("high")
            val lows   = quote.optJSONArray("low")
            val vols   = quote.optJSONArray("volume")
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until timestamps.length()) {
                val t = timestamps.optLong(i, -1L)
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i, Double.NaN)
                if (t <= 0 || !c.isFinite()) continue
                val o = if (opens != null && !opens.isNull(i)) opens.optDouble(i, Double.NaN) else Double.NaN
                val h = if (highs != null && !highs.isNull(i)) highs.optDouble(i, Double.NaN) else Double.NaN
                val l = if (lows  != null && !lows.isNull(i))  lows.optDouble(i, Double.NaN)  else Double.NaN
                val v = if (vols  != null && !vols.isNull(i))  vols.optDouble(i, Double.NaN)  else Double.NaN
                list.add(ChartPoint(t, c, o, h, l, v))
            }
            list.takeLast(maxCount)
        } catch (_: Exception) { emptyList() }
    }

    // ── 港股分时 & K 线（腾讯接口）─────────────────────────────────────────

    /**
     * 港股日内分时，用腾讯 proxy.finance.qq.com 接口。
     * 返回格式：每条 "HHMM cumPrice cumVol cumAmount"（累计量），需差分算每分钟价格。
     * 实际价格取每分钟末的实时价（累计量差分仅用于成交量，价格直接用该分钟最新价）。
     */
    private fun fetchHkIntraday(code: String): List<ChartPoint> {
        val url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/minute/query" +
                  "?code=hk$code&_var=min_data"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            // 格式：min_data={...}
            val json  = raw.substringAfter("=").trim()
            val arr   = JSONObject(json).getJSONObject("data")
                .getJSONObject("hk$code").getJSONObject("data")
                .getJSONArray("data")
            val shZone = ZoneId.of("Asia/Hong_Kong")
            val today  = java.time.LocalDate.now(shZone).toString()
            val list   = mutableListOf<ChartPoint>()
            var prevCumVol = 0.0
            for (i in 0 until arr.length()) {
                val parts = arr.getString(i).split(" ")
                if (parts.size < 2) continue
                val timeStr   = parts[0]           // "HHMM"
                val price     = parts[1].toDoubleOrNull() ?: continue
                val cumVol    = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val vol       = (cumVol - prevCumVol).coerceAtLeast(0.0)
                prevCumVol    = cumVol
                if (price <= 0) continue
                val hh = timeStr.take(2).toIntOrNull() ?: continue
                val mm = timeStr.takeLast(2).toIntOrNull() ?: continue
                val ts = java.time.LocalDateTime.of(
                    java.time.LocalDate.now(shZone), java.time.LocalTime.of(hh, mm)
                ).atZone(shZone).toInstant().epochSecond
                list.add(ChartPoint(ts, price, volume = vol))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    /**
     * A 股历史 K 线（日/周/月），腾讯 qfq 前复权接口，分页拉取全量历史。
     * 字段顺序：[date, open, close, high, low, volume, {}, change%, amount, ...]
     */
    private fun fetchTencentAShareHistory(symbol: String, period: String, @Suppress("UNUSED_PARAMETER") count: Int): List<ChartPoint> {
        val periodParam = when (period) {
            "weekly"  -> "week"
            "monthly" -> "month"
            else      -> "day"
        }
        val shZone  = ZoneId.of("Asia/Shanghai")
        val PAGE    = 300
        val all     = mutableListOf<ChartPoint>()
        var endDate = ""   // 空字符串表示拉到最新

        while (true) {
            val varName = "kline_${periodParam}qfq"
            val url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get" +
                      "?param=$symbol,$periodParam,,$endDate,$PAGE,qfq&_var=$varName"
            val raw  = HttpUtil.get(url) ?: break
            val page = try {
                val json = raw.substringAfter("=").trim()
                val symObj = JSONObject(json).getJSONObject("data").getJSONObject(symbol)
                // 优先前复权（qfqday/qfqweek/qfqmonth），不支持时 fallback 非复权（day/week/month）
                val bars = symObj.optJSONArray("qfq$periodParam")
                    ?.takeIf { it.length() > 0 }
                    ?: symObj.optJSONArray(periodParam)
                    ?: break
                val list = mutableListOf<ChartPoint>()
                for (i in 0 until bars.length()) {
                    val bar  = bars.getJSONArray(i)
                    val date = bar.optString(0, "")
                    if (date.isEmpty()) continue
                    val open  = bar.optString(1, "").toDoubleOrNull() ?: Double.NaN
                    val close = bar.optString(2, "").toDoubleOrNull() ?: continue
                    val high  = bar.optString(3, "").toDoubleOrNull() ?: Double.NaN
                    val low   = bar.optString(4, "").toDoubleOrNull() ?: Double.NaN
                    val vol   = bar.optString(5, "").toDoubleOrNull() ?: Double.NaN
                    val ts    = java.time.LocalDate.parse(date)
                        .atStartOfDay(shZone).toInstant().epochSecond
                    list.add(ChartPoint(ts, close, open, high, low, vol))
                }
                list
            } catch (_: Exception) { break }

            if (page.isEmpty()) break
            // 插到列表头部（向前翻页）
            all.addAll(0, page)
            // 已到上市首日（返回条数不足一页），结束
            if (page.size < PAGE) break
            // 下一页 endDate = 本页最早日期的前一天
            val earliest = java.time.Instant.ofEpochSecond(page.first().time)
                .atZone(shZone).toLocalDate().minusDays(1).toString()
            endDate = earliest
        }
        return all
    }

    /**
     * 港股历史 K 线（日/周/月），用腾讯 newfqkline 接口。
     * 字段顺序：[date, close, open, high, low, volume, {}, change, amount, ...]
     */
    private fun fetchHkHistory(code: String, period: String, count: Int): List<ChartPoint> {
        val periodParam = when (period) {
            "weekly"  -> "week"
            "monthly" -> "month"
            else      -> "day"
        }
        val url = "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get" +
                  "?param=hk$code,$periodParam,,,$count,qfq&_var=kline_${periodParam}qfq"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            val json  = raw.substringAfter("=").trim()
            val bars  = JSONObject(json).getJSONObject("data")
                .getJSONObject("hk$code").getJSONArray(periodParam)
            val shZone = ZoneId.of("Asia/Hong_Kong")
            val list   = mutableListOf<ChartPoint>()
            for (i in 0 until bars.length()) {
                val bar  = bars.getJSONArray(i)
                val date = bar.optString(0, "")
                if (date.isEmpty()) continue
                val close  = bar.optString(1, "").toDoubleOrNull() ?: continue
                val open   = bar.optString(2, "").toDoubleOrNull() ?: Double.NaN
                val high   = bar.optString(3, "").toDoubleOrNull() ?: Double.NaN
                val low    = bar.optString(4, "").toDoubleOrNull() ?: Double.NaN
                val vol    = bar.optString(5, "").toDoubleOrNull() ?: Double.NaN
                val ts     = java.time.LocalDate.parse(date)
                    .atStartOfDay(shZone).toInstant().epochSecond
                list.add(ChartPoint(ts, close, open, high, low, vol))
            }
            list.takeLast(count)
        } catch (_: Exception) { emptyList() }
    }

    // ── Fund NAV history（天天基金 pingzhongdata）─────────────────────────

    private fun fetchFundNavHistory(code: String): List<ChartPoint> {
        val url = "https://fund.eastmoney.com/pingzhongdata/$code.js"
        val raw = HttpUtil.get(url) ?: return emptyList()
        return try {
            // 提取 Data_netWorthTrend = [{"x":ms,"y":nav,...},...]
            val m = Regex("""Data_netWorthTrend\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)
                .find(raw) ?: return emptyList()
            val arr = JSONArray(m.groupValues[1])
            val list = mutableListOf<ChartPoint>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ms  = obj.optLong("x", -1L).takeIf { it > 0 } ?: continue
                val nav = obj.optDouble("y", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: continue
                list.add(ChartPoint(ms / 1000L, nav))   // ms → seconds
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ── Utilities ────────────────────────────────────────────────────────

    private fun normalizeFuture(raw: String): String {
        val v = raw.trim()
        if (v.startsWith("nf_") || v.startsWith("hf_")) {
            val parts = v.split("_", limit = 2)
            return "${parts[0].lowercase()}_${(parts.getOrElse(1) { "" }).uppercase()}"
        }
        return "nf_${v.uppercase()}"
    }

    private fun todayKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(java.util.Date())
    }
}
