package com.stocklite.plugin.service

import com.stocklite.plugin.state.*
import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 行情 + 搜索服务。
 * 直接移植自 Electron 版 src/main/ipc/quotes.ts + search.ts，保持完全相同的业务逻辑。
 */
object MarketDataService {

    // ══════════════════════════════════════════════════════════════
    // 常量（移植自 src/main/utils/constants.ts）
    // ══════════════════════════════════════════════════════════════

    data class GlobalIndexConfig(
        val symbol: String, val code: String,
        val nameEn: String, val nameCn: String,
        val market: String, val timezone: String
    )

    val GLOBAL_INDEXES = listOf(
        GlobalIndexConfig("^GSPC",    "SPX",    "S&P 500",         "标普500",    "US", "America/New_York"),
        GlobalIndexConfig("^DJI",     "DJI",    "Dow Jones",       "道琼斯",     "US", "America/New_York"),
        GlobalIndexConfig("^IXIC",    "IXIC",   "NASDAQ",          "纳斯达克",   "US", "America/New_York"),
        GlobalIndexConfig("^VIX",     "VIX",    "CBOE VIX",        "VIX恐慌指数", "US", "America/New_York"),
        GlobalIndexConfig("^FTSE",    "FTSE",   "FTSE 100",        "英国富时100","UK", "Europe/London"),
        GlobalIndexConfig("^GDAXI",   "DAX",    "DAX",             "德国DAX",    "DE", "Europe/Berlin"),
        GlobalIndexConfig("^FCHI",    "CAC40",  "CAC 40",          "法国CAC40",  "FR", "Europe/Paris"),
        GlobalIndexConfig("^N225",    "N225",   "Nikkei 225",      "日经225",    "JP", "Asia/Tokyo"),
        GlobalIndexConfig("^KS11",    "KOSPI",  "KOSPI",           "韩国综合指数","KR", "Asia/Seoul"),
        GlobalIndexConfig("^TWII",    "TWII",   "TAIEX",           "台湾加权指数","TW", "Asia/Taipei"),
        GlobalIndexConfig("^BSESN",   "SENSEX", "SENSEX",          "印度SENSEX", "IN", "Asia/Kolkata"),
        GlobalIndexConfig("^HSI",     "HSI",    "Hang Seng",       "恒生指数",   "HK", "Asia/Hong_Kong"),
        GlobalIndexConfig("^HSTECH",  "HSTECH", "Hang Seng TECH",  "恒生科技指数","HK","Asia/Hong_Kong"),
        GlobalIndexConfig("000001.SS","SSE",    "SSE Composite",   "上证指数",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("399001.SZ","SZSE",   "SZSE Component",  "深证成指",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("399006.SZ","CYB",    "ChiNext",         "创业板指",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("000300.SS","CSI300", "CSI 300",         "沪深300",    "CN", "Asia/Shanghai"),
        GlobalIndexConfig("000688.SS","STAR50", "STAR 50",         "科创50",     "CN", "Asia/Shanghai"),
    )

    private val SINA_SYMBOL_MAP = mapOf(
        "^DJI"      to "gb_dji",
        "^IXIC"     to "gb_ixic",
        "^GSPC"     to "gb_\$inx",
        "^N225"     to "gb_nky",
        "^HSI"      to "rt_hkHSI",
        "^HSTECH"   to "rt_hkHSTECH",
        "000001.SS" to "sh000001",
        "399001.SZ" to "sz399001",
        "399006.SZ" to "sz399006",
        "000300.SS" to "sh000300",
        "000688.SS" to "sh000688",
    )

    private val YAHOO_SYMBOL_MAP = mapOf("^HSTECH" to "HSTECH.HK")
    private fun resolveYahooSymbol(symbol: String) = YAHOO_SYMBOL_MAP[symbol] ?: symbol

    /** 强制使用 Yahoo（新浪数据不可靠的指数） */
    private val GLOBAL_FORCE_YAHOO = setOf("^N225", "^KS11")

    private val TENCENT_HK_MAP = mapOf("^HSI" to "r_hkHSI", "^HSTECH" to "r_hkHSTECH")

    // ══════════════════════════════════════════════════════════════
    // 市场开市判断（移植自 src/main/utils/index.ts）
    // ══════════════════════════════════════════════════════════════

    private fun getSessionMinutes(market: String): Triple<Int, Int, Pair<Int, Int>?> {
        // return (start, end, lunch? or null)
        return when (market) {
            "CN" -> Triple(9*60+30, 15*60, 11*60+30 to 13*60)
            "HK" -> Triple(9*60+30, 16*60, 12*60 to 13*60)
            "JP" -> Triple(9*60,    15*60, 11*60+30 to 12*60+30)
            "KR" -> Triple(9*60,    15*60+30, null)
            "US" -> Triple(9*60+30, 16*60, null)
            "UK" -> Triple(8*60,    16*60+30, null)
            "DE" -> Triple(9*60,    17*60+30, null)
            "FR" -> Triple(9*60,    17*60+30, null)
            "TW" -> Triple(9*60,    13*60+30, null)
            "IN" -> Triple(9*60+15, 15*60+30, null)
            else -> Triple(9*60,    16*60, null)
        }
    }

    /**
     * 判断市场当前是否开盘：先看星期，再看当天是否为交易日（含节假日，见 isTodayTradingDay），
     * 最后再核对是否落在交易时段内。
     */
    fun isMarketOpenByTimezone(market: String, timezone: String): Boolean {
        val now = ZonedDateTime.now(ZoneId.of(timezone))
        val dow = now.dayOfWeek.value  // 1=Mon..7=Sun
        if (dow == 6 || dow == 7) return false
        if (!isTodayTradingDay(market, timezone, System.currentTimeMillis())) return false
        val current = now.hour * 60 + now.minute
        val (start, end, lunch) = getSessionMinutes(market)
        if (current < start || current > end) return false
        if (lunch != null && current >= lunch.first && current <= lunch.second) return false
        return true
    }

    /** 各市场用于判断"今天是否为交易日"的代表性 Yahoo 标的（同一市场共用一次请求结果） */
    private val MARKET_CALENDAR_SYMBOL = mapOf(
        "US" to "^GSPC", "UK" to "^FTSE", "DE" to "^GDAXI", "FR" to "^FCHI",
        "JP" to "^N225", "KR" to "^KS11", "TW" to "^TWII", "IN" to "^BSESN",
        "HK" to "^HSI", "CN" to "000001.SS",
    )
    private val marketTradingDayCache = mutableMapOf<String, Pair<Boolean, Long>>()
    private val MARKET_CALENDAR_CACHE_TTL = 30 * 60_000L  // 30 分钟；是否交易日只在日期边界变化

    /**
     * 判断某市场"今天"是否为交易日（含节假日）。
     * 用 v8/finance/chart（已在其它地方大量使用、无需鉴权）的 currentTradingPeriod 字段——
     * 该字段始终指向最近一个真实交易时段，若其日期不是"今天"，说明今天休市（周末或节假日）。
     * 请求失败 / 无代表标的时，退回"只看星期"。同一市场内多个标的共用同一份结果，且缓存 30 分钟，
     * 避免每次刷新都发请求。
     */
    private fun isTodayTradingDay(market: String, timezone: String, now: Long): Boolean {
        val weekdayFallback = ZonedDateTime.now(ZoneId.of(timezone)).dayOfWeek.value.let { it != 6 && it != 7 }

        val cached = marketTradingDayCache[market]
        if (cached != null && now - cached.second < MARKET_CALENDAR_CACHE_TTL) return cached.first

        val symbol = MARKET_CALENDAR_SYMBOL[market] ?: return weekdayFallback
        val enc = URLEncoder.encode(resolveYahooSymbol(symbol), "UTF-8")
        val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=5d")
            ?: return cached?.first ?: weekdayFallback

        val isTradingDay = try {
            val result = JSONObject(raw).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")
            val gmtOffset = meta.optLong("gmtoffset", 0)
            val regularStart = meta.optJSONObject("currentTradingPeriod")
                ?.optJSONObject("regular")?.optLong("start", -1) ?: -1L
            if (regularStart <= 0) weekdayFallback
            else {
                val periodDate = java.time.Instant.ofEpochSecond(regularStart + gmtOffset)
                    .atZone(ZoneId.of("UTC")).toLocalDate()
                val today = ZonedDateTime.now(ZoneId.of(timezone)).toLocalDate()
                periodDate == today
            }
        } catch (_: Exception) { weekdayFallback }

        marketTradingDayCache[market] = isTradingDay to now
        return isTradingDay
    }

    // ══════════════════════════════════════════════════════════════
    // parseSinaData（移植自 src/main/utils/index.ts）
    // ══════════════════════════════════════════════════════════════

    private fun parseSinaData(text: String): Map<String, Pair<Double, Double>> {
        val result = mutableMapOf<String, Pair<Double, Double>>() // symbol -> (price, changePercent)
        val regex = Regex("""var hq_str_(.*?)="(.*?)"""")
        for (m in regex.findAll(text)) {
            val sinaSymbol = m.groupValues[1]
            val arr = m.groupValues[2].split(",")
            var price = Double.NaN
            var changePct = 0.0
            when {
                sinaSymbol.startsWith("gb_") -> {
                    price     = arr.getOrElse(1) { "" }.toDoubleOrNull() ?: Double.NaN
                    changePct = arr.getOrElse(2) { "" }.toDoubleOrNull() ?: 0.0
                }
                sinaSymbol.startsWith("rt_hk") -> {
                    price = arr.getOrElse(2) { "" }.toDoubleOrNull() ?: Double.NaN
                    val prev = arr.getOrElse(3) { "" }.toDoubleOrNull() ?: 0.0
                    if (price.isFinite() && prev > 0) changePct = (price - prev) / prev * 100
                }
                sinaSymbol.startsWith("sh") || sinaSymbol.startsWith("sz") -> {
                    val prev = arr.getOrElse(2) { "" }.toDoubleOrNull() ?: 0.0
                    price = arr.getOrElse(3) { "" }.toDoubleOrNull() ?: Double.NaN
                    if (price.isFinite() && prev > 0) changePct = (price - prev) / prev * 100
                }
            }
            if (price.isFinite() && price > 0) result[sinaSymbol] = price to changePct
        }
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // 股票行情（移植自 registerStockQuoteHandlers，扩展支持港股/美股）
    // ══════════════════════════════════════════════════════════════

    fun getStockQuotes(symbols: List<String>): Map<String, StockQuote> {
        if (symbols.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, StockQuote>()

        // Separate by market
        val aShares  = symbols.filter { it.startsWith("sh") || it.startsWith("sz") }
        val hkShares = symbols.filter { it.startsWith("hk") }
        val usShares = symbols.filter { !it.startsWith("sh") && !it.startsWith("sz") && !it.startsWith("hk") }

        // A-shares via Sina
        if (aShares.isNotEmpty()) {
            val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=${aShares.joinToString(",")}",
                "http://finance.sina.com.cn") ?: ""
            val re = Regex("""var hq_str_([^=]+)="([^"]+)"""")
            for (m in re.findAll(raw)) {
                val symbol = m.groupValues[1].trim()
                val fields = m.groupValues[2].split(",")
                if (fields.size < 4) continue
                val name      = fields[0].trim().takeIf { it.isNotEmpty() } ?: continue
                val prevClose = fields[2].toDoubleOrNull() ?: continue
                val price     = fields[3].toDoubleOrNull() ?: continue
                val change    = if (price != 0.0) price - prevClose else 0.0
                val changePct = if (prevClose != 0.0) change / prevClose * 100 else 0.0
                result[symbol] = StockQuote(symbol, name, price.takeIf { it != 0.0 } ?: prevClose, prevClose, change, changePct)
            }
        }

        // HK shares via Tencent
        for (sym in hkShares) {
            val code   = sym.removePrefix("hk")
            val tSym   = "r_hk$code"
            val raw    = HttpUtil.get("https://qt.gtimg.cn/q=$tSym", referer = "https://gu.qq.com") ?: continue
            val m      = Regex("""v_[^=]+="([^"]*)"""").find(raw) ?: continue
            val f      = m.groupValues[1].split("~")
            val price  = f.getOrElse(3) { "" }.toDoubleOrNull() ?: continue
            val prev   = f.getOrElse(4) { "" }.toDoubleOrNull() ?: continue
            if (price <= 0 || prev <= 0) continue
            val name   = f.getOrElse(1) { sym }.ifEmpty { sym }
            val change = price - prev
            val pct    = if (prev > 0) change / prev * 100 else 0.0
            result[sym] = StockQuote(sym, name, price, prev, change, pct)
        }

        // US/other shares via Yahoo Finance
        for (sym in usShares) {
            try {
                val enc = URLEncoder.encode(sym, "UTF-8")
                val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=1d") ?: continue
                val meta = JSONObject(raw).optJSONObject("chart")
                    ?.optJSONArray("result")?.optJSONObject(0)
                    ?.optJSONObject("meta") ?: continue
                val price     = meta.optDouble("regularMarketPrice", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: continue
                val prevClose = meta.optDouble("chartPreviousClose", Double.NaN).let {
                    if (it.isFinite() && it > 0) it else meta.optDouble("regularMarketPreviousClose", Double.NaN)
                }.takeIf { it.isFinite() && it > 0 } ?: price
                val name     = meta.optString("shortName", sym).ifEmpty { sym }
                val change   = price - prevClose
                val pct      = if (prevClose > 0) change / prevClose * 100 else 0.0
                result[sym] = StockQuote(sym, name, price, prevClose, change, pct)
            } catch (_: Exception) {}
        }

        return result
    }

    // ══════════════════════════════════════════════════════════════
    // 基金行情（移植自 registerFundQuoteHandlers — 完整多级备用逻辑）
    // ══════════════════════════════════════════════════════════════

    private val KNOWN_QDII_CODES = setOf("017437")
    private val QDII_NAME_KW = listOf("qdii","全球","海外","纳斯达克","标普","道琼斯","日经","恒生","msci","nasdaq","s&p","dow","hang seng")
    // 面板自身的刷新定时器和 FundNavWatcherService 的后台轮询会各自在独立线程上并发调用
    // getFundQuotes，此处以及下方水位线缓存必须使用线程安全集合，否则并发读写可能
    // 抛出 ConcurrentModificationException 导致某轮抓取静默失败。
    private val detectedQdiiCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 官方净值水位线：记录每个基金代码"已确认"的最新官方净值（nav/changePercent/date）。
     * fundgz、Sina、F10 均走 CDN，边缘节点数据不同步时偶发返回滞后快照，
     * 若不加约束会导致净值在新旧数据之间来回跳变。这里跨轮次记忆已知最优结果，
     * 任何一次抓取若日期落后于水位线，则用水位线纠正，只允许官方净值随时间前进、不倒退。
     */
    private val officialQuoteHighWaterMark = java.util.concurrent.ConcurrentHashMap<String, FundQuote>()

    private fun advanceHighWaterMark(code: String, computed: FundQuote): FundQuote {
        // 用 compute 保证"读水位线 → 比较 → 写回"整体原子，避免面板定时器与
        // Watcher 后台轮询同时为同一 code 调用本方法时出现的检查后再写竞态。
        var result = computed
        officialQuoteHighWaterMark.compute(code) { _, prevBest: FundQuote? ->
            if (prevBest != null && !(computed.date.isNotEmpty() && computed.date >= prevBest.date)) {
                // 本次官方日期落后于已知最优（命中滞后 CDN 节点等）——
                // 官方字段用水位线纠正，估值类字段仍保留本轮抓取结果（这些字段本就逐轮刷新）。
                result = computed.copy(nav = prevBest.nav, changePercent = prevBest.changePercent, date = prevBest.date)
                prevBest
            } else {
                result = computed
                computed
            }
        }
        return result
    }

    private fun normFundCode(raw: String) = raw.trim().let { Regex("\\d{6}").find(it)?.value ?: it }
    private fun isQDIIByCode(code: String) = normFundCode(code) in KNOWN_QDII_CODES
    private fun isQDIIByName(name: String?) = name?.lowercase()?.let { n -> QDII_NAME_KW.any { n.contains(it) } } == true
    private fun isQDII(code: String) = isQDIIByCode(code) || code in detectedQdiiCodes

    fun getFundQuotes(codes: List<String>): Map<String, FundQuote> {
        if (codes.isEmpty()) return emptyMap()
        val unique = codes.map { normFundCode(it) }.distinct().filter { it.isNotEmpty() }
        val results = mutableMapOf<String, FundQuote>()

        // ── 第一步：全部走 fundgz（获取估算 + 名称 + 官方净值日期） ──
        for (code in unique) {
            fetchFundgz(code)?.let { q ->
                results[code] = q
                if (isQDIIByName(q.name)) detectedQdiiCodes.add(code)
            }
        }

        // ── 第二步：Sina 备用（fundgz 失败的） ──
        val needSina = unique.filter { !results.containsKey(it) }
        if (needSina.isNotEmpty()) fetchFundsSina(needSina).forEach { (c, q) ->
            results[c] = q
            if (isQDIIByName(q.name)) detectedQdiiCodes.add(c)
        }

        // ── 第三步：F10 增强
        //   QDII：F10 是官方涨跌权威来源，必须获取
        //   普通基金：fundgz 不返回官方涨跌幅（changePercent 固定为 0），均需 F10 补全
        //   合并原则：日期更新的数据胜出；F10 未更新时不覆盖 fundgz 的净值日期 ──
        val needF10 = unique.filter { code ->
            val q = results[code]
            q == null || isQDII(code) || !q.changePercent.isFinite() || q.changePercent == 0.0
        }
        for (code in needF10) {
            val f10 = fetchF10Nav(code) ?: continue
            val existing = results[code]
            if (existing == null) {
                results[code] = buildOfficialQuote(code, "基金$code", f10)
            } else if (f10.date.isNotEmpty() && existing.date.isNotEmpty() && f10.date < existing.date) {
                // F10 命中滞后 CDN 节点，返回的报告期比已掌握的还旧：
                // 只是跳过本次增强，不能让它的旧净值和 existing 的新日期拼到一起。
                continue
            } else {
                val prevNav   = f10.prevNav.takeIf { it.isFinite() && it > 0 } ?: 0.0
                val changePct = when {
                    f10.changePercent.isFinite() -> f10.changePercent
                    prevNav > 0                  -> (f10.nav - prevNav) / prevNav * 100
                    else                         -> existing.changePercent
                }
                results[code] = existing.copy(
                    // nav 和 changePercent 取 F10（官方精度），与 Electron 版一致
                    nav                     = if (f10.nav > 0) f10.nav.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toDouble() else existing.nav,
                    changePercent           = changePct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
                    // 取两者中较新的日期：F10 已更新时用 F10 日期，F10 CDN 旧节点返回旧日期时保持 fundgz 日期不退
                    date                    = maxOf(existing.date, f10.date.ifEmpty { existing.date }),
                    estimatedNav            = if (isQDII(code)) null else existing.estimatedNav,
                    estimatedChangePercent  = if (isQDII(code)) null else existing.estimatedChangePercent,
                    hasEstimate             = if (isQDII(code)) false else existing.hasEstimate
                )
            }
        }

        // ── QDII 兜底清除估算（F10 失败时 fundgz 的估算字段可能残留） ──
        for (code in unique.filter { isQDII(it) }) {
            results[code]?.let { q ->
                if (q.hasEstimate || q.estimatedNav != null)
                    results[code] = q.copy(estimatedNav = null, estimatedChangePercent = null, hasEstimate = false)
            }
        }

        // ── 官方净值水位线：跨轮次纠正，杜绝面板定时器与后台 Watcher 并发轮询时
        //    谁先返回谁生效导致的净值新旧来回跳变（不影响本轮抓到的估值字段）──
        for (code in unique) {
            results[code]?.let { results[code] = advanceHighWaterMark(code, it) }
        }

        return results
    }

    private fun fetchFundgz(code: String): FundQuote? {
        val raw = HttpUtil.get("https://fundgz.1234567.com.cn/js/$code.js") ?: return null
        val m = Regex("""jsonpgz\((.*)\);?""").find(raw)?.groupValues?.get(1) ?: return null
        return try {
            val j = JSONObject(m)
            val fundCode = j.optString("fundcode", code)
            val name   = j.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val gsz    = j.optString("gsz").toDoubleOrNull()?.takeIf { it > 0 }
            val dwjz   = j.optString("dwjz").toDoubleOrNull()?.takeIf { it > 0 }
            val gszzl  = j.optString("gszzl").toDoubleOrNull()
            val jzrq   = j.optString("jzrq", "")
            val gztime = j.optString("gztime", "")
            val nav    = dwjz ?: gsz ?: return null
            val estimatedChange = if (dwjz != null && gszzl != null) gszzl / 100.0 * dwjz else 0.0
            val isToday = gztime.isNotEmpty() && isTodayValue(gztime)
            FundQuote(
                code = fundCode, name = name, nav = nav,
                changePercent = 0.0,  // 由 F10 补全
                date = jzrq,
                estimatedNav = gsz, estimatedChangePercent = gszzl,
                hasEstimate = gsz != null && isToday
            )
        } catch (_: Exception) { null }
    }

    private fun fetchFundsSina(codes: List<String>): Map<String, FundQuote> {
        if (codes.isEmpty()) return emptyMap()
        val q = codes.joinToString(",") { "fund_$it" }
        val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=$q", "http://finance.sina.com.cn") ?: return emptyMap()
        val result = mutableMapOf<String, FundQuote>()
        val re = Regex("""var hq_str_fund_([^=]+)="([^"]*)"""")
        for (m in re.findAll(raw)) {
            val code = m.groupValues[1].trim()
            val data = m.groupValues[2].split(",")
            if (data.size < 3) continue
            val name = data[0].trim().takeIf { it.isNotEmpty() } ?: continue
            val nav  = data[1].toDoubleOrNull() ?: 0.0
            val prev = data[2].toDoubleOrNull() ?: 0.0
            val effectiveNav = (nav.takeIf { it > 0 } ?: prev).takeIf { it > 0 } ?: continue
            val change    = if (nav != 0.0) nav - prev else 0.0
            val changePct = if (prev != 0.0) change / prev * 100 else 0.0
            result[code] = FundQuote(
                code = code, name = name, nav = effectiveNav,
                changePercent = changePct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
                date = data.getOrElse(4) { today() }
            )
        }
        return result
    }

    private fun fetchFundName(code: String): String? {
        val raw = HttpUtil.get("https://fundgz.1234567.com.cn/js/$code.js") ?: return null
        val m = Regex("""jsonpgz\((.*)\);?""").find(raw)?.groupValues?.get(1) ?: return null
        return try { JSONObject(m).optString("name").ifEmpty { null } } catch (_: Exception) { null }
    }

    /** 移植 parseF10LatestNav：解析东方财富历史净值 HTML 表格 */
    private data class F10Nav(val date: String, val nav: Double, val changePercent: Double, val prevNav: Double)

    private fun fetchF10Nav(code: String): F10Nav? {
        val raw = HttpUtil.get(
            "https://fundf10.eastmoney.com/F10DataApi.aspx?type=lsjz&sdate=&edate=&code=$code",
            "https://fundf10.eastmoney.com/") ?: return null
        return parseF10LatestNav(raw)
    }

    private fun parseF10LatestNav(html: String): F10Nav? {
        val stripHtml = { s: String -> s.replace(Regex("<[^>]*>"), "").replace("&nbsp;", "").trim() }
        val thead = Regex("<thead>(.*?)</thead>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: ""
        val headers = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL)
            .findAll(thead).map { stripHtml(it.groupValues[1]) }.toList()

        val tbody = Regex("<tbody>(.*?)</tbody>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val firstRow = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).find(tbody)?.groupValues?.get(1) ?: return null
        val cells = Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
            .findAll(firstRow).map { stripHtml(it.groupValues[1]) }.toList()
        if (cells.size < 2) return null

        fun findIdx(kws: List<String>, fallback: Int): Int {
            val i = headers.indexOfFirst { h -> kws.any { h.contains(it) } }
            return if (i >= 0) i else fallback
        }
        val dateIdx   = findIdx(listOf("净值日期","日期"), 0)
        val navIdx    = findIdx(listOf("单位净值"), 1)
        val growthIdx = findIdx(listOf("日增长率","涨跌幅"), 3)

        val date = cells.getOrElse(dateIdx) { "" }
        val nav  = cells.getOrElse(navIdx) { "" }.replace(Regex("[%+,\\s]"), "").toDoubleOrNull() ?: return null
        if (nav <= 0) return null
        val changePct = cells.getOrElse(growthIdx) { "" }.replace(Regex("[%+,\\s]"), "").toDoubleOrNull() ?: Double.NaN
        val prevNav = if (changePct.isFinite()) nav / (1 + changePct / 100) else Double.NaN
        return F10Nav(date, nav, changePct, prevNav)
    }

    private fun buildOfficialQuote(code: String, name: String, p: F10Nav): FundQuote {
        val prevNav    = p.prevNav.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val change     = if (prevNav > 0) p.nav - prevNav else 0.0
        val changePct  = if (p.changePercent.isFinite()) p.changePercent
                         else if (prevNav > 0) change / prevNav * 100 else 0.0
        return FundQuote(code = code, name = name,
            nav = p.nav.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toDouble(),
            changePercent = changePct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
            date = p.date.ifEmpty { today() }, hasEstimate = false)
    }

    private fun enrichWithF10(q: FundQuote, p: F10Nav): FundQuote {
        val prevNav   = p.prevNav.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val changePct = if (p.changePercent.isFinite()) p.changePercent
                        else if (prevNav > 0) (p.nav - prevNav) / prevNav * 100 else 0.0
        return q.copy(
            nav = p.nav.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toDouble(),
            changePercent = changePct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
            date = p.date.ifEmpty { q.date }
        )
    }

    private fun isTodayValue(raw: String): Boolean {
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(raw.replace("/", "-")) ?: return false
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        return now.year == m.groupValues[1].toInt() &&
               now.monthValue == m.groupValues[2].toInt() &&
               now.dayOfMonth == m.groupValues[3].toInt()
    }

    private fun today() = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        .toLocalDate().toString()

    // ══════════════════════════════════════════════════════════════
    // 期货行情（移植自 registerFutureQuoteHandlers）
    // ══════════════════════════════════════════════════════════════

    private fun normFutureSymbol(raw: String): String {
        val v = raw.trim()
        return if (v.startsWith("nf_", true) || v.startsWith("hf_", true)) {
            val prefix = v.substringBefore("_").lowercase()
            val body   = v.substringAfter("_").uppercase()
            "${prefix}_$body"
        } else "nf_${v.uppercase()}"
    }

    fun getFutureQuotes(symbols: List<String>): Map<String, FutureQuote> {
        if (symbols.isEmpty()) return emptyMap()
        val full = symbols.map { normFutureSymbol(it) }.distinct()
        val raw  = HttpUtil.getGbk("https://hq.sinajs.cn/list=${full.joinToString(",")}",
            "https://finance.sina.com.cn") ?: return emptyMap()

        val result = mutableMapOf<String, FutureQuote>()
        val re = Regex("""var hq_str_([^=]+)="([^"]*)"""")
        for (m in re.findAll(raw)) {
            val symbol = normFutureSymbol(m.groupValues[1])
            val f = m.groupValues[2].split(",")
            if (f.size < 4) continue
            parseFutureFields(symbol, f)?.let { result[symbol] = it }
        }
        return result
    }

    private fun parseFutureFields(symbol: String, f: List<String>): FutureQuote? {
        fun first(vararg idxs: Int) = idxs.toList().mapNotNull { f.getOrNull(it)?.toDoubleOrNull()?.takeIf { v -> v > 0 } }.firstOrNull() ?: 0.0
        return try {
            if (symbol.startsWith("nf_")) {
                val firstNum = f[0].toDoubleOrNull()
                val isIndex  = firstNum != null && firstNum > 0
                val name     = if (isIndex) f.getOrElse(47) { "" }.ifEmpty { f.getOrElse(46) { symbol } }
                               else f[0].trim().ifEmpty { symbol }
                val price    = if (isIndex) first(0,3,2,14,13)
                               else first(8,6,3,2,1)
                val preClose = if (isIndex) first(14,15,2,13,3)
                               else first(10,2,7,27,9,3)
                val change   = if (preClose > 0) price - preClose else 0.0
                val pct      = if (preClose > 0) change / preClose * 100 else 0.0
                FutureQuote(symbol, name.trim(), price, preClose, change, pct)
            } else { // hf_
                val name     = f.getOrElse(13) { symbol }
                val price    = f.getOrElse(0) { "0" }.toDoubleOrNull() ?: 0.0
                val preClose = first(7, 2, 8)
                val change   = if (preClose > 0) price - preClose else 0.0
                val pct      = if (preClose > 0) change / preClose * 100 else 0.0
                FutureQuote(symbol, name.trim(), price, preClose, change, pct)
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    // 全球指数（移植自 registerGlobalIndexQuoteHandlers）
    // ══════════════════════════════════════════════════════════════

    private val globalPrevCloseCache  = mutableMapOf<String, Pair<Double, Long>>()
    private val GLOBAL_CACHE_TTL       = 60_000L

    /** 全球指数行情结果，附带限流标志供面板自适应退避 */
    data class GlobalQuoteResult(
        val quotes: List<GlobalIndexQuote>,
        val rateLimited: Boolean = false
    )

    fun getGlobalIndexQuotes(): GlobalQuoteResult {
        val quoteMap = mutableMapOf<String, Pair<Double, Double>>()
        val delayMap = mutableMapOf<String, Boolean>()  // 本次实际取值来源是否为延迟源
        val now = System.currentTimeMillis()
        var yahooRateLimited = false

        // 1. 腾讯港股实时（HK 指数）——实时
        for (sym in listOf("^HSI", "^HSTECH")) {
            fetchTencentHkQuote(sym)?.let { (price, pct, _) ->
                SINA_SYMBOL_MAP[sym]?.let { key -> quoteMap[key] = price to pct; delayMap[key] = false }
            }
        }

        // 2. 新浪批量（A 股 / 美股粗行情）
        // 沪深（sh/sz）、港股新浪兜底（rt_hk）为实时行情；海外指数（gb_）新浪标明"至少延时15分钟"
        val sinaSymbols = SINA_SYMBOL_MAP.values.joinToString(",")
        HttpUtil.getGbk("http://hq.sinajs.cn/list=$sinaSymbols", "https://finance.sina.com.cn")
            ?.let { text ->
                parseSinaData(text).forEach { (k, v) ->
                    if (!quoteMap.containsKey(k)) {
                        quoteMap[k] = v
                        delayMap[k] = k.startsWith("gb_")
                    }
                }
            }

        // 3. Yahoo 兜底（强制 Yahoo 的指数 + 新浪无数据的）——免费接口，约15分钟延迟
        for (item in GLOBAL_INDEXES) {
            val sinaKey = SINA_SYMBOL_MAP[item.symbol]
            if (!GLOBAL_FORCE_YAHOO.contains(item.symbol) && sinaKey != null && quoteMap.containsKey(sinaKey)) continue

            val (statusCode, body) = fetchYahooQuoteWithStatus(item.symbol, now)
            when {
                statusCode == 429 -> { yahooRateLimited = true }
                body != null -> {
                    if (sinaKey != null) { quoteMap[sinaKey] = body; delayMap[sinaKey] = true }
                    quoteMap[item.symbol] = body
                    delayMap[item.symbol] = true
                }
            }
        }

        val quotes = GLOBAL_INDEXES.map { item ->
            val sinaKey = SINA_SYMBOL_MAP[item.symbol]
            val (price, pct) = (sinaKey?.let { quoteMap[it] } ?: quoteMap[item.symbol]) ?: (0.0 to 0.0)
            val isDelayed = (sinaKey?.let { delayMap[it] } ?: delayMap[item.symbol]) ?: true
            GlobalIndexQuote(
                symbol = item.symbol, name = "${item.nameCn} (${item.nameEn})",
                value = price, changePercent = pct,
                isOpen = isMarketOpenByTimezone(item.market, item.timezone),
                market = item.market,
                isDelayed = isDelayed
            )
        }
        return GlobalQuoteResult(quotes, yahooRateLimited)
    }

    private data class HkQuote(val price: Double, val changePct: Double, val prevClose: Double)

    private fun fetchTencentHkQuote(symbol: String): HkQuote? {
        val tSym = TENCENT_HK_MAP[symbol] ?: return null
        val raw  = HttpUtil.get("https://qt.gtimg.cn/q=$tSym",
            referer = "https://gu.qq.com") ?: return null
        val m    = Regex("""v_[^=]+="([^"]*)"""").find(raw) ?: return null
        val f    = m.groupValues[1].split("~")
        val price = f.getOrElse(3) { "" }.toDoubleOrNull() ?: return null
        val prev  = f.getOrElse(4) { "" }.toDoubleOrNull() ?: return null
        if (price <= 0 || prev <= 0) return null
        val tPct = f.getOrElse(32) { "" }.toDoubleOrNull()
        val pct  = if (tPct != null && tPct.isFinite()) tPct else (price - prev) / prev * 100
        return HkQuote(price, pct, prev)
    }

    /**
     * 带 HTTP 状态码的 Yahoo 行情获取。
     * 返回 Triple(statusCode, priceData?, body是否有效)
     * - statusCode=429 → 限流
     * - priceData != null → 成功解析
     */
    private fun fetchYahooQuoteWithStatus(symbol: String, now: Long): Pair<Int, Pair<Double, Double>?> {
        val enc = URLEncoder.encode(resolveYahooSymbol(symbol), "UTF-8")
        val (code, raw) = HttpUtil.getWithStatus(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=5d")
        if (code == 429) return 429 to null
        if (raw == null) return code to null
        val data = parseYahooBody(raw, symbol, now)
        return code to data
    }

    /** 移植 fetchYahooIndexQuote + fetchUnifiedPreviousClose（保留旧方法供内部复用） */
    private fun fetchYahooQuote(symbol: String, now: Long): Pair<Double, Double>? {
        val enc = URLEncoder.encode(resolveYahooSymbol(symbol), "UTF-8")
        val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=5d") ?: return null
        return parseYahooBody(raw, symbol, now)
    }

    private fun parseYahooBody(raw: String, symbol: String, now: Long): Pair<Double, Double>? {
        return try {
            val json   = JSONObject(raw)
            val result = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta   = result.getJSONObject("meta")
            val closes = result.getJSONObject("indicators").getJSONArray("quote")
                .getJSONObject(0).getJSONArray("close")

            val marketPrice = meta.optDouble("regularMarketPrice", Double.NaN)
            val latestClose = (0 until closes.length()).mapNotNull { closes.optDouble(it).takeIf { v -> v.isFinite() && v > 0 } }.lastOrNull()
            val price = if (marketPrice.isFinite() && marketPrice > 0) marketPrice
                        else latestClose ?: return null

            // 前收：分钟线数据点校验通过才采信，否则依次退回 Yahoo 官方字段
            // （previousClose 是 Yahoo 页面展示涨跌幅所用的口径，比 chartPreviousClose 更准确）
            var prevClose = extractPrevCloseFromIntraday(result, symbol)
            if (!prevClose.isFinite() || prevClose <= 0) prevClose = meta.optDouble("previousClose", Double.NaN)
            if (!prevClose.isFinite() || prevClose <= 0) prevClose = meta.optDouble("chartPreviousClose", Double.NaN)
            if (!prevClose.isFinite() || prevClose <= 0) prevClose = meta.optDouble("regularMarketPreviousClose", Double.NaN)
            if (!prevClose.isFinite() || prevClose <= 0) {
                // 缓存
                val cached = globalPrevCloseCache[symbol]
                if (cached != null && now - cached.second < GLOBAL_CACHE_TTL) prevClose = cached.first
                else prevClose = latestClose ?: return null
            }
            globalPrevCloseCache[symbol] = prevClose to now
            val pct = if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0
            price to pct
        } catch (_: Exception) { null }
    }

    /**
     * 移植 extractPreviousCloseFromIntraday，并加一道校验：
     * 只有当"前一交易日最后一个分钟线数据点"确实接近该市场官方收盘时间时才采信，
     * 否则回退到 chartPreviousClose 等官方字段更可靠。
     * 起因：韩国综合指数（^KS11）等标的 Yahoo 分钟线经常在收盘前提前截断
     *（如实际 15:30 收盘，数据却止于 14:59），若直接取"最后一个点"当前收，
     * 会把前收算低/算高，涨跌幅随之出现明显偏差。
     */
    private fun extractPrevCloseFromIntraday(result: JSONObject, symbol: String): Double {
        val gmtOffset = result.getJSONObject("meta").optLong("gmtoffset", 0)
        val timestamps = result.optJSONArray("timestamp") ?: return Double.NaN
        val closes = result.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")

        val dayMap = linkedMapOf<String, Double>()
        val dayLastMinuteOfDay = mutableMapOf<String, Int>()

        for (i in 0 until minOf(timestamps.length(), closes.length())) {
            val ts    = timestamps.optLong(i, -1).takeIf { it > 0 } ?: continue
            val close = closes.optDouble(i).takeIf { it.isFinite() && it > 0 } ?: continue
            val exchMs = (ts + gmtOffset) * 1000
            val zdt = java.time.Instant.ofEpochMilli(exchMs).atZone(ZoneId.of("UTC"))
            val day = zdt.toLocalDate().toString()
            dayMap[day] = close
            dayLastMinuteOfDay[day] = zdt.hour * 60 + zdt.minute
        }

        val days = dayMap.keys.toList()
        if (days.size < 2) return Double.NaN
        val prevDay   = days[days.size - 2]
        val prevClose = dayMap[prevDay] ?: return Double.NaN

        val market = GLOBAL_INDEXES.find { it.symbol == symbol }?.market ?: return prevClose
        val sessionEnd  = getSessionMinutes(market).second
        val lastMinute  = dayLastMinuteOfDay[prevDay] ?: return Double.NaN
        if (sessionEnd - lastMinute > 15) return Double.NaN
        return prevClose
    }

    // ══════════════════════════════════════════════════════════════
    // A股大盘概览（东方财富 push2 系列免费接口，均实测确认无需登录/鉴权）
    // ══════════════════════════════════════════════════════════════

    private const val EM_UT = "bd1d9ddb04089700cf9c27f6f7426281"

    /** 涨跌家数（沪深两市合计）：分别查上证指数(1.000001)、深证综指(0.399106)的成分家数字段后求和 */
    private fun fetchAdvanceDecline(): Triple<Int, Int, Int>? {
        fun fetchOne(secid: String): Triple<Int, Int, Int>? {
            val raw = HttpUtil.get("https://push2.eastmoney.com/api/qt/stock/get?secid=$secid&ut=$EM_UT&fields=f113,f114,f115")
                ?: return null
            return try {
                val d = JSONObject(raw).getJSONObject("data")
                Triple(d.getInt("f113"), d.getInt("f114"), d.getInt("f115"))
            } catch (_: Exception) { null }
        }
        val sh = fetchOne("1.000001") ?: return null
        val sz = fetchOne("0.399106") ?: return null
        return Triple(sh.first + sz.first, sh.second + sz.second, sh.third + sz.third)
    }

    /** 涨停/跌停家数 */
    private fun fetchLimitCounts(): Pair<Int, Int>? {
        fun fetchTc(url: String): Int? {
            val raw = HttpUtil.get(url) ?: return null
            return try { JSONObject(raw).getJSONObject("data").getInt("tc") } catch (_: Exception) { null }
        }
        val zt = fetchTc("https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fbt:asc&date=") ?: return null
        val dt = fetchTc("https://push2ex.eastmoney.com/getTopicDTPool?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fund:asc&date=") ?: return null
        return zt to dt
    }

    /** 两市成交额代理：中证流通指数（覆盖沪深京全市场）成交额，单位元 */
    private fun fetchTotalTurnover(): Double? {
        val raw = HttpUtil.get("https://push2.eastmoney.com/api/qt/stock/get?secid=1.000902&ut=$EM_UT&fields=f48") ?: return null
        return try {
            val v = JSONObject(raw).getJSONObject("data").optDouble("f48", Double.NaN)
            v.takeIf { it.isFinite() && it > 0 }
        } catch (_: Exception) { null }
    }

    /** 大/中/小盘代理：沪深300 / 中证500 / 中证1000 涨跌幅% */
    private fun fetchCapTierPct(): Triple<Double, Double, Double>? {
        val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=sh000300,sh000905,sh000852", "https://finance.sina.com.cn")
            ?: return null
        val quotes = parseSinaData(raw)
        val large = quotes["sh000300"]?.second
        val mid   = quotes["sh000905"]?.second
        val small = quotes["sh000852"]?.second
        if (large == null || mid == null || small == null) return null
        return Triple(large, mid, small)
    }

    private data class SectorInfo(val name: String, val pct: Double, val constituents: Int)

    private var sectorLeadersCache: Pair<List<SectorInfo>, List<SectorInfo>>? = null
    private var sectorLeadersCacheTime = 0L
    private val SECTOR_CACHE_TTL = 5 * 60_000L  // 板块接口偶发限流/断连时，5分钟内用最近一次成功结果兜底，避免频繁"--"

    /** 领涨/领跌行业板块 TOP6（过滤掉成分股过少的冷门板块，避免单只股票带动的噪声） */
    private fun fetchSectorLeaders(): Pair<List<SectorInfo>, List<SectorInfo>>? {
        fun fetchTop(descending: Boolean): List<SectorInfo>? {
            val po = if (descending) 1 else 0
            val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=30&po=$po&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:2&fields=f14,f3,f104,f105"
            // 该接口偶发被限流/断连（HTTP 层面握手失败），重试一次再放弃
            var raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/")
            if (raw == null) raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/")
            raw ?: return null
            return try {
                val arr = JSONObject(raw).getJSONObject("data").getJSONArray("diff")
                (0 until arr.length()).asSequence().mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val name = o.optString("f14", "")
                    val pct  = o.optDouble("f3", Double.NaN)
                    val cons = o.optInt("f104", 0) + o.optInt("f105", 0)
                    if (name.isEmpty() || !pct.isFinite()) null else SectorInfo(name, pct, cons)
                }.filter { it.constituents >= 5 }.take(6).toList()
            } catch (_: Exception) { null }
        }

        val top    = fetchTop(descending = true)
        val bottom = if (top != null) fetchTop(descending = false) else null
        val now = System.currentTimeMillis()
        if (top != null && bottom != null && top.isNotEmpty() && bottom.isNotEmpty()) {
            val result = top to bottom
            sectorLeadersCache = result
            sectorLeadersCacheTime = now
            return result
        }
        // 本次失败：若最近一次成功结果还在有效期内，沿用它，而不是直接展示"--"
        val cached = sectorLeadersCache
        return if (cached != null && now - sectorLeadersCacheTime < SECTOR_CACHE_TTL) cached else null
    }

    /** 主力资金净流入（沪深两市合计），单位元，负数为净流出 */
    private fun fetchMainCapitalFlow(): Double? {
        val raw = HttpUtil.get(
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&secids=1.000001,0.399001&fields=f62"
        ) ?: return null
        return try {
            val arr = JSONObject(raw).getJSONObject("data").getJSONArray("diff")
            var sum = 0.0
            for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("f62", 0.0)
            sum
        } catch (_: Exception) { null }
    }

    /**
     * 获取A股大盘概览。各子项独立请求、独立容错——任意一项失败只影响该项（显示为 null/"--"），
     * 不影响其它已成功获取的数据，也不影响全球指数行情本身。
     */
    fun getMarketBreadth(): MarketBreadthData {
        val breadth = fetchAdvanceDecline()
        val limits  = fetchLimitCounts()
        val turnover = fetchTotalTurnover()
        val capTier = fetchCapTierPct()
        val sectors = fetchSectorLeaders()
        val flow    = fetchMainCapitalFlow()
        return MarketBreadthData(
            upCount = breadth?.first, downCount = breadth?.second, flatCount = breadth?.third,
            limitUpCount = limits?.first, limitDownCount = limits?.second,
            totalTurnover = turnover,
            largeCapPct = capTier?.first, midCapPct = capTier?.second, smallCapPct = capTier?.third,
            topSectors = sectors?.first?.map { SectorPct(it.name, it.pct) } ?: emptyList(),
            bottomSectors = sectors?.second?.map { SectorPct(it.name, it.pct) } ?: emptyList(),
            mainNetInflow = flow
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 股票搜索（移植自 registerStockSearchHandler，扩展支持港股/美股）
    // ══════════════════════════════════════════════════════════════

    fun searchStocks(keyword: String): List<StockSearchResult> {
        if (keyword.isBlank()) return emptyList()
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val raw = HttpUtil.getGbk("https://suggest3.sinajs.cn/suggest/key=$enc") ?: return emptyList()
        // 移植原版：match = text.match(/"([^"]+)"/)
        val m = Regex(""""([^"]+)"""").find(raw) ?: return emptyList()

        val results = mutableListOf<StockSearchResult>()

        m.groupValues[1].split(";")
            .filter { it.isNotBlank() }
            .forEach { item ->
                val parts  = item.split(",")
                val symbol = parts.getOrElse(3) { "" }.ifEmpty { parts.getOrElse(2) { "" } }.trim()
                val name   = parts.getOrElse(0) { "" }.trim()
                if (symbol.isEmpty() || name.isEmpty()) return@forEach

                when {
                    // A-share with explicit prefix
                    symbol.startsWith("sh") || symbol.startsWith("sz") ->
                        results.add(StockSearchResult(symbol, name))
                    // A-share 6-digit code
                    symbol.matches(Regex("\\d{6}")) -> {
                        val full = if (symbol.startsWith("6") || symbol.startsWith("5")) "sh$symbol" else "sz$symbol"
                        results.add(StockSearchResult(full, name))
                    }
                    // HK share: 5-digit numeric code
                    symbol.matches(Regex("\\d{5}")) ->
                        results.add(StockSearchResult("hk$symbol", name))
                    // HK share with exchange prefix
                    symbol.matches(Regex("[Hh][Kk]\\d{4,5}")) -> {
                        val code = symbol.takeLast(5).padStart(5, '0')
                        results.add(StockSearchResult("hk$code", name))
                    }
                }
            }

        // US stock fallback: if keyword looks like a US ticker (uppercase letters only)
        if (results.isEmpty() && keyword.matches(Regex("[A-Z]{1,5}"))) {
            try {
                val yEnc = URLEncoder.encode(keyword, "UTF-8")
                val yRaw = HttpUtil.get("https://query2.finance.yahoo.com/v1/finance/search?q=$yEnc&quotesCount=5&newsCount=0") ?: ""
                val quotes = JSONObject(yRaw).optJSONArray("quotes") ?: JSONArray()
                for (i in 0 until quotes.length()) {
                    val o = quotes.optJSONObject(i) ?: continue
                    val sym = o.optString("symbol", "").takeIf { it.isNotEmpty() } ?: continue
                    val shortName = o.optString("shortname", o.optString("longname", sym))
                    val qType = o.optString("quoteType", "")
                    if (qType == "EQUITY" || qType == "ETF") {
                        results.add(StockSearchResult(sym, shortName))
                    }
                }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.symbol }.take(15)
    }

    // ══════════════════════════════════════════════════════════════
    // 基金搜索（移植自 registerFundSearchHandler）
    // ══════════════════════════════════════════════════════════════

    fun searchFunds(keyword: String): List<FundSearchResult> {
        if (keyword.isBlank()) return emptyList()
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val raw = HttpUtil.get(
            "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=$enc") ?: return emptyList()
        return try {
            val data = JSONObject(raw).optJSONArray("Datas") ?: JSONArray()
            (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optString("CODE").ifEmpty { return@mapNotNull null }
                val name = o.optString("NAME").ifEmpty { return@mapNotNull null }
                FundSearchResult(code, name)
            }.take(15)
        } catch (_: Exception) { emptyList() }
    }

    // ══════════════════════════════════════════════════════════════
    // 期货搜索（移植自 registerFutureSearchHandler + FUTURE_CONTRACTS 本地表）
    // ══════════════════════════════════════════════════════════════

    private val LOCAL_FUTURES = listOf(
        Triple("nf_IF0",  "沪深300股指连续", listOf("IF","CSI300")),
        Triple("nf_IH0",  "上证50股指连续",  listOf("IH","SSE50")),
        Triple("nf_IC0",  "中证500股指连续", listOf("IC","CSI500")),
        Triple("nf_IM0",  "中证1000股指连续",listOf("IM","CSI1000")),
        Triple("nf_RB0",  "螺纹钢连续",      listOf("RB","SHFE")),
        Triple("nf_HC0",  "热轧卷板连续",    listOf("HC")),
        Triple("nf_I0",   "铁矿石连续",      listOf("I","DCE")),
        Triple("nf_JM0",  "焦煤连续",        listOf("JM")),
        Triple("nf_J0",   "焦炭连续",        listOf("J")),
        Triple("nf_M0",   "豆粕连续",        listOf("M")),
        Triple("nf_Y0",   "豆油连续",        listOf("Y")),
        Triple("nf_P0",   "棕榈油连续",      listOf("P")),
        Triple("nf_AU0",  "沪金连续",        listOf("AU","黄金")),
        Triple("nf_AG0",  "沪银连续",        listOf("AG","白银")),
        Triple("nf_CU0",  "沪铜连续",        listOf("CU")),
        Triple("nf_AL0",  "沪铝连续",        listOf("AL")),
        Triple("nf_ZN0",  "沪锌连续",        listOf("ZN")),
        Triple("nf_SC0",  "原油连续",        listOf("SC","INE","原油")),
        Triple("nf_SR0",  "白糖连续",        listOf("SR")),
        Triple("nf_CF0",  "棉花连续",        listOf("CF")),
        Triple("hf_ES",   "标普500期货",     listOf("S&P","ES")),
        Triple("hf_NQ",   "纳斯达克100期货", listOf("NASDAQ","NQ")),
        Triple("hf_YM",   "道琼斯期货",      listOf("Dow","YM")),
        Triple("hf_CL",   "纽约原油期货",    listOf("WTI","CL")),
        Triple("hf_OIL",  "布伦特原油期货",  listOf("Brent")),
        Triple("hf_GC",   "纽约黄金期货",    listOf("Gold","GC","黄金")),
        Triple("hf_SI",   "纽约白银期货",    listOf("Silver","SI")),
        Triple("hf_HG",   "纽约铜期货",      listOf("Copper","HG")),
        Triple("hf_NK225","日经225期货",     listOf("Nikkei")),
        Triple("hf_HSI",  "恒生指数期货",    listOf("HSI","Hang Seng")),
    )

    fun searchFutures(keyword: String): List<FutureSearchResult> {
        if (keyword.isBlank()) return emptyList()
        val trimmed = keyword.trim().lowercase()
        val remote  = mutableListOf<FutureSearchResult>()

        // 网络搜索（移植自原版，使用 UTF-8 编码）
        try {
            val enc = URLEncoder.encode(trimmed, "UTF-8")
            val raw = HttpUtil.getGbk("https://suggest3.sinajs.cn/suggest/key=$enc")
            if (raw != null) {
                val m = Regex(""""([^"]*)"""").find(raw)
                val payload = m?.groupValues?.get(1) ?: ""
                if (payload.isNotEmpty()) {
                    payload.split(";").filter { it.isNotBlank() }.forEach { row ->
                        val parts = row.split(",")
                        if (parts.size < 5) return@forEach
                        val type = parts[1].trim()
                        if (type != "88" && type != "87") return@forEach
                        val code   = (parts[2].ifEmpty { parts[3] }).trim()
                        val symbol = normFutureSymbol(code)
                        if (symbol.isEmpty()) return@forEach
                        val cnName = parts.getOrElse(4) { "" }.trim()
                        val enName = parts.getOrElse(0) { "" }.trim()
                        val name   = cnName.ifEmpty { enName }.ifEmpty { symbol }
                        remote.add(FutureSearchResult(symbol, name))
                    }
                }
            }
        } catch (_: Exception) { /* 网络失败，fallback 到本地 */ }

        // 本地表兜底（同原版 FUTURE_CONTRACTS fallback）
        val fallback = LOCAL_FUTURES.filter { (sym, name, aliases) ->
            sym.lowercase().contains(trimmed) ||
            name.lowercase().contains(trimmed) ||
            aliases.any { it.lowercase().contains(trimmed) }
        }.map { (sym, name, _) -> FutureSearchResult(sym, name) }

        // 去重合并
        val dedup = linkedMapOf<String, FutureSearchResult>()
        (remote + fallback).forEach { if (!dedup.containsKey(it.symbol)) dedup[it.symbol] = it }
        return dedup.values.take(80)
    }
}
