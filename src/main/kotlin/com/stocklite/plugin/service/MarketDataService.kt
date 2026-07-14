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
        val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=5d",
            label = "市场交易日历")
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
                "http://finance.sina.com.cn", label = "A股行情") ?: ""
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
            val raw    = HttpUtil.get("https://qt.gtimg.cn/q=$tSym", referer = "https://gu.qq.com",
                label = "港股行情") ?: continue
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
                val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=1d",
                    label = "美股/其他行情") ?: continue
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

                // 盘前/盘后延伸交易数据：仅在 marketState 明确为 PRE/POST/POSTPOST 时才展示，
                // 避免交易时段内残留的 preMarketPrice 字段干扰正常行情。
                val marketState = meta.optString("marketState", "")
                val extPair: Pair<Double?, ExtendedSession?> = when (marketState) {
                    "PRE" -> {
                        val p = meta.optDouble("preMarketPrice", Double.NaN).takeIf { it.isFinite() && it > 0 }
                        p to if (p != null) ExtendedSession.PRE_MARKET else null
                    }
                    "POST", "POSTPOST" -> {
                        val p = meta.optDouble("postMarketPrice", Double.NaN).takeIf { it.isFinite() && it > 0 }
                        p to if (p != null) ExtendedSession.POST_MARKET else null
                    }
                    else -> null to null
                }
                val extPrice = extPair.first
                val extSession = extPair.second
                val extPct = if (extPrice != null && prevClose > 0)
                    (extPrice - prevClose) / prevClose * 100 else null

                result[sym] = StockQuote(sym, name, price, prevClose, change, pct,
                    extendedPrice = extPrice, extendedChangePercent = extPct, extendedSession = extSession)
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
        val raw = HttpUtil.get("https://fundgz.1234567.com.cn/js/$code.js", label = "基金估算净值") ?: return null
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
        val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=$q", "http://finance.sina.com.cn",
            label = "基金行情-新浪") ?: return emptyMap()
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
        val raw = HttpUtil.get("https://fundgz.1234567.com.cn/js/$code.js", label = "基金名称查询") ?: return null
        val m = Regex("""jsonpgz\((.*)\);?""").find(raw)?.groupValues?.get(1) ?: return null
        return try { JSONObject(m).optString("name").ifEmpty { null } } catch (_: Exception) { null }
    }

    /** 移植 parseF10LatestNav：解析东方财富历史净值 HTML 表格 */
    private data class F10Nav(val date: String, val nav: Double, val changePercent: Double, val prevNav: Double)

    private fun fetchF10Nav(code: String): F10Nav? {
        val raw = HttpUtil.get(
            "https://fundf10.eastmoney.com/F10DataApi.aspx?type=lsjz&sdate=&edate=&code=$code",
            "https://fundf10.eastmoney.com/", label = "基金历史净值(F10)") ?: return null
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
            "https://finance.sina.com.cn", label = "期货行情") ?: return emptyMap()

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

    // Yahoo 兜底行情缓存：这批指数（VIX/富时100/DAX/CAC40/台湾加权/SENSEX 等无新浪源的 + 强制走 Yahoo 的日经/韩国综指）
    // 本身已确认约15分钟延迟，之前每次面板刷新（默认5秒一次）都逐个单独请求 Yahoo，等于5秒打8次接口、纯耗流量。
    // 60秒内直接复用缓存，不发请求。
    private val yahooQuoteCache = mutableMapOf<String, Triple<Double, Double, Long>>()  // symbol -> (price, pct, time)
    private val YAHOO_QUOTE_FRESH_TTL = 60_000L

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
        HttpUtil.getGbk("http://hq.sinajs.cn/list=$sinaSymbols", "https://finance.sina.com.cn",
            label = "全球指数-新浪")
            ?.let { text ->
                parseSinaData(text).forEach { (k, v) ->
                    if (!quoteMap.containsKey(k)) {
                        quoteMap[k] = v
                        delayMap[k] = k.startsWith("gb_")
                    }
                }
            }

        // 3. Yahoo 兜底（强制 Yahoo 的指数 + 新浪无数据的）——免费接口，约15分钟延迟，本身没必要跟着5秒刷新
        for (item in GLOBAL_INDEXES) {
            val sinaKey = SINA_SYMBOL_MAP[item.symbol]
            if (!GLOBAL_FORCE_YAHOO.contains(item.symbol) && sinaKey != null && quoteMap.containsKey(sinaKey)) continue

            val cached = yahooQuoteCache[item.symbol]
            if (cached != null && now - cached.third < YAHOO_QUOTE_FRESH_TTL) {
                val body = cached.first to cached.second
                if (sinaKey != null) { quoteMap[sinaKey] = body; delayMap[sinaKey] = true }
                quoteMap[item.symbol] = body
                delayMap[item.symbol] = true
                continue
            }

            val (statusCode, body) = fetchYahooQuoteWithStatus(item.symbol, now)
            when {
                statusCode == 429 -> { yahooRateLimited = true }
                body != null -> {
                    yahooQuoteCache[item.symbol] = Triple(body.first, body.second, now)
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
            referer = "https://gu.qq.com", label = "全球指数-腾讯港股") ?: return null
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
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=5d",
            label = "全球指数-Yahoo(${symbol})")
        if (code == 429) return 429 to null
        if (raw == null) return code to null
        val data = parseYahooBody(raw, symbol, now)
        return code to data
    }

    /** 移植 fetchYahooIndexQuote + fetchUnifiedPreviousClose（保留旧方法供内部复用） */
    private fun fetchYahooQuote(symbol: String, now: Long): Pair<Double, Double>? {
        val enc = URLEncoder.encode(resolveYahooSymbol(symbol), "UTF-8")
        val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=5d",
            label = "全球指数-Yahoo(${symbol})") ?: return null
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
            val raw = HttpUtil.get("https://push2.eastmoney.com/api/qt/stock/get?secid=$secid&ut=$EM_UT&fields=f113,f114,f115",
                label = "涨跌家数") ?: return null
            return try {
                val d = JSONObject(raw).getJSONObject("data")
                Triple(d.getInt("f113"), d.getInt("f114"), d.getInt("f115"))
            } catch (_: Exception) { null }
        }
        val sh = fetchOne("1.000001") ?: return null
        val sz = fetchOne("0.399106") ?: return null
        // 北证50（0.899050）代表北交所，原先遗漏会导致涨跌家数比第三方App少约200~300家；取不到不影响沪深数据
        val bj = fetchOne("0.899050") ?: Triple(0, 0, 0)
        return Triple(sh.first + sz.first + bj.first, sh.second + sz.second + bj.second, sh.third + sz.third + bj.third)
    }

    /** 涨停/跌停家数。date 参数必须显式传当日日期（yyyyMMdd），传空接口会返回 rc:102 无数据 */
    private fun fetchLimitCounts(): Pair<Int, Int>? {
        val shZone  = ZoneId.of("Asia/Shanghai")
        val dateFmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        fun fetchTc(url: String): Int? {
            val raw = HttpUtil.get(url, label = "涨停/跌停") ?: return null
            return try { JSONObject(raw).getJSONObject("data").getInt("tc") } catch (_: Exception) { null }
        }
        // push2ex 支持历史日期，收盘后当天仍可查；若今日无数据则回退到最近可用交易日（最多往前找5天）
        for (daysBack in 0..4) {
            val date = ZonedDateTime.now(shZone).toLocalDate().minusDays(daysBack.toLong())
                .format(dateFmt)
            val zt = fetchTc("https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fbt:asc&date=$date")
            val dt = fetchTc("https://push2ex.eastmoney.com/getTopicDTPool?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=1&sort=fund:asc&date=$date")
            if (zt != null && dt != null) return zt to dt
        }
        return null
    }

    /**
     * 两市成交额：新浪上证（sh000001）+ 深证成指（sz399001）字段 9（成交额，元）之和。
     * 新浪接口盘中实时更新、收盘后直接保持当日收盘值，与已有行情请求复用同一主机，稳定性更高。
     * 东方财富 push2.eastmoney.com 在部分网络环境下存在直连限制，故不再依赖该域名。
     */
    private fun fetchTotalTurnover(): Double? {
        val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=sh000001,sz399001",
            "https://finance.sina.com.cn", label = "两市成交额") ?: return null
        var sum = 0.0
        var count = 0
        for (line in raw.lines()) {
            val fields = Regex(""""([^"]*)"""").find(line)?.groupValues?.get(1)?.split(",") ?: continue
            val turnover = fields.getOrElse(9) { "" }.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            sum += turnover
            count++
        }
        return if (count == 0) null else sum
    }

    /** 大/中/小盘代理：沪深300 / 中证500 / 中证1000 涨跌幅% */
    private fun fetchCapTierPct(): Triple<Double, Double, Double>? {
        val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=sh000300,sh000905,sh000852",
            "https://finance.sina.com.cn", label = "大/中/小盘涨跌") ?: return null
        val quotes = parseSinaData(raw)
        val large = quotes["sh000300"]?.second
        val mid   = quotes["sh000905"]?.second
        val small = quotes["sh000852"]?.second
        if (large == null || mid == null || small == null) return null
        return Triple(large, mid, small)
    }

    private data class SectorInfo(val name: String, val pct: Double, val constituents: Int)

    /**
     * 领涨/领跌行业板块 TOP6（过滤掉成分股过少的冷门板块，避免单只股票带动的噪声）。
     * 缓存由调用方 sectorCache（StaleCache）统一负责，这里只管一次真实抓取。
     */
    private fun fetchSectorLeaders(): Pair<List<SectorInfo>, List<SectorInfo>>? {
        fun fetchTop(descending: Boolean): List<SectorInfo>? {
            val po = if (descending) 1 else 0
            val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=30&po=$po&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:2&fields=f14,f3,f104,f105"
            // 该接口偶发被限流/断连（HTTP 层面握手失败），重试一次再放弃
            var raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/", label = "领涨/领跌板块")
            if (raw == null) raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/", label = "领涨/领跌板块")
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
        return if (top != null && bottom != null && top.isNotEmpty() && bottom.isNotEmpty()) top to bottom else null
    }

    /** 主力资金净流入（沪深两市合计），单位元，负数为净流出 */
    private fun fetchMainCapitalFlow(): Double? {
        val raw = HttpUtil.get(
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&secids=1.000001,0.399001&fields=f62",
            label = "主力资金净流入"
        ) ?: return null
        return try {
            val arr = JSONObject(raw).getJSONObject("data").getJSONArray("diff")
            var sum = 0.0
            for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("f62", 0.0)
            sum
        } catch (_: Exception) { null }
    }

    /** 四大股指期货品种 */
    private val INDEX_FUTURES_VARIETIES = linkedMapOf(
        "IH" to "上证50", "IF" to "沪深300", "IC" to "中证500", "IM" to "中证1000"
    )

    /** 最近一个已披露龙虎榜数据的交易日（"yyyy-MM-dd"）。当日收盘结算后即为当日，盘中则为上一交易日 */
    private fun fetchLatestFuturesTradeDate(): String? {
        val filter = URLEncoder.encode("(MEMBER_NAME_ABBR=\"本日合计\")(TYPE=\"2\")(TRADE_CODE=\"IF\")", "UTF-8")
        val raw = HttpUtil.get(
            "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_FUTU_DAILYPOSITION" +
                "&columns=TRADE_DATE&filter=$filter&sortColumns=TRADE_DATE&sortTypes=-1&pageNumber=1&pageSize=1&source=WEB&client=WEB",
            label = "股指期货交易日"
        ) ?: return null
        return try {
            JSONObject(raw).getJSONObject("result").getJSONArray("data").getJSONObject(0)
                .optString("TRADE_DATE", "").takeIf { it.length >= 10 }?.substring(0, 10)
        } catch (_: Exception) { null }
    }

    private data class FuturesAgg(
        val long: Double, val short: Double, val dLong: Double, val dShort: Double,
        /** 品种代码 -> [多, 空, Δ多, Δ空] */
        val byVariety: Map<String, DoubleArray>
    )

    /**
     * 按会员名聚合指定交易日的持仓：覆盖四大期指（IH/IF/IC/IM）的全部合约月份（如 IF2607+IF2609+IF2612），
     * 与东财/豆包等平台"品种合计"口径一致，而非仅主力合约。持仓/增减字段为 null 的行按 0 计（交易所仅披露进入
     * 对应榜单前20的数据，未上榜部分本就不可得，各平台同此口径）。
     */
    private fun fetchFuturesAgg(memberName: String, type: String, date: String): FuturesAgg? {
        val filter = URLEncoder.encode("(MEMBER_NAME_ABBR=\"$memberName\")(TYPE=\"$type\")(TRADE_DATE='$date')", "UTF-8")
        val raw = HttpUtil.get(
            "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_FUTU_DAILYPOSITION" +
                "&columns=TRADE_CODE,LONG_POSITION,SHORT_POSITION,LP_CHANGE,SP_CHANGE" +
                "&filter=$filter&pageNumber=1&pageSize=200&source=WEB&client=WEB",
            label = "股指期货龙虎榜"
        ) ?: return null
        return try {
            val arr = JSONObject(raw).getJSONObject("result").getJSONArray("data")
            val byVariety = mutableMapOf<String, DoubleArray>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val variety = o.optString("TRADE_CODE", "")
                if (variety !in INDEX_FUTURES_VARIETIES) continue
                val acc = byVariety.getOrPut(variety) { DoubleArray(4) }
                acc[0] += o.optDouble("LONG_POSITION", 0.0).takeIf { it.isFinite() } ?: 0.0
                acc[1] += o.optDouble("SHORT_POSITION", 0.0).takeIf { it.isFinite() } ?: 0.0
                acc[2] += o.optDouble("LP_CHANGE", 0.0).takeIf { it.isFinite() } ?: 0.0
                acc[3] += o.optDouble("SP_CHANGE", 0.0).takeIf { it.isFinite() } ?: 0.0
            }
            if (byVariety.isEmpty()) return null
            FuturesAgg(
                long   = byVariety.values.sumOf { it[0] },
                short  = byVariety.values.sumOf { it[1] },
                dLong  = byVariety.values.sumOf { it[2] },
                dShort = byVariety.values.sumOf { it[3] },
                byVariety = byVariety
            )
        } catch (_: Exception) { null }
    }

    private var futuresPositionCache: IndexFuturesPosition? = null
    private var futuresPositionCacheTime = 0L
    private val FUTURES_POSITION_CACHE_TTL = 10 * 60_000L  // 龙虎榜每日结算后才更新一次，10分钟缓存足够

    /**
     * 股指期货收盘后龙虎榜：中信期货(代客) 及前20名会员合计("本日合计"官方汇总行)的多空持仓，
     * 覆盖四大期指全部合约月份。日期取最近一个已披露的交易日，由 tradeDate 如实反映，不做"待收盘"猜测。
     */
    private fun fetchIndexFuturesPosition(): IndexFuturesPosition? {
        val now = System.currentTimeMillis()
        futuresPositionCache?.let {
            if (now - futuresPositionCacheTime < FUTURES_POSITION_CACHE_TTL) return it
        }

        val date = fetchLatestFuturesTradeDate() ?: return futuresPositionCache
        val citic = fetchFuturesAgg("中信期货(代客)", "0", date) ?: return futuresPositionCache
        val total = fetchFuturesAgg("本日合计", "2", date) ?: return futuresPositionCache

        val result = IndexFuturesPosition(
            tradeDate = date.substring(5),
            citicLong = citic.long, citicShort = citic.short,
            citicLongChange = citic.dLong, citicShortChange = citic.dShort,
            mainForceLong = total.long, mainForceShort = total.short,
            mainForceLongChange = total.dLong, mainForceShortChange = total.dShort,
            citicByVariety = INDEX_FUTURES_VARIETIES.mapNotNull { (code, name) ->
                citic.byVariety[code]?.let { v ->
                    VarietyNetChange(code, name, netAddShort = v[3] - v[2])
                }
            },
            mainForceByVariety = INDEX_FUTURES_VARIETIES.mapNotNull { (code, name) ->
                total.byVariety[code]?.let { v ->
                    VarietyNetChange(code, name, netAddShort = v[3] - v[2])
                }
            }
        )
        futuresPositionCache = result
        futuresPositionCacheTime = now
        return result
    }

    /**
     * 子项缓存：
     * - 缓存在 freshTtlMs 内视为"新鲜"，直接返回，**不发起网络请求**——这是修复大盘概览
     *   持续大量耗流量的关键：之前的写法是 cache.update(fetch())，Kotlin 会先无条件求值 fetch()
     *   再决定是否使用缓存，等于缓存形同虚设，每次面板刷新（20秒一次）都会把全部接口打一遍。
     *   现在改成 getOrFetch { fetch() }，缓存新鲜时函数体根本不会执行，才是真正省流量的写法。
     * - 缓存过期后才真正发请求；请求失败时，若仍在 staleTtlMs 兜底期内则继续沿用旧值，
     *   避免接口偶发失败/限流时界面频繁跳"--"；超过兜底期才返回 null。
     */
    private class StaleCache<T>(private val freshTtlMs: Long, private val staleTtlMs: Long) {
        private var value: T? = null
        private var time = 0L
        /** 最后一次成功获取到新鲜数据的时间戳（毫秒），0 表示从未成功 */
        val lastSuccessTime: Long get() = time
        fun getOrFetch(fetch: () -> T?): T? {
            val now = System.currentTimeMillis()
            if (value != null && now - time < freshTtlMs) return value
            val fresh = fetch()
            if (fresh != null) { value = fresh; time = now; return fresh }
            return if (value != null && now - time < staleTtlMs) value else null
        }
    }

    // 大盘概览这类宏观统计没必要跟着20秒的面板刷新节奏逐项重新请求，60秒新鲜度足够；
    // 板块龙虎榜（clist/get）接口本身较脆弱、易被限流，新鲜度放宽到120秒进一步降低请求频率。
    private val BREADTH_FRESH_TTL = 60_000L
    // 收盘后 EastMoney 这些实时接口会直接取不到数据，需要让"沿用上次数据"的窗口盖过整个非交易时段
    // （含周末/节假日），直到下一交易日重新取到新鲜数据为止；这里放宽到 3 天。
    private val BREADTH_STALE_TTL = 3 * 24 * 60 * 60_000L
    private val advDecCache   = StaleCache<Triple<Int, Int, Int>>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val limitsCache   = StaleCache<Pair<Int, Int>>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val turnoverCache = StaleCache<Double>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val capTierCache  = StaleCache<Triple<Double, Double, Double>>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val flowCache     = StaleCache<Double>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val sectorCache   = StaleCache<Pair<List<SectorInfo>, List<SectorInfo>>>(2 * 60_000L, 5 * 60_000L)
    private val forecastFuturesCache = StaleCache<Map<String, FutureQuote>>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)

    data class BreadthDataTimes(
        val advDec: Long,      // 涨跌家数
        val limits: Long,      // 涨跌停
        val turnover: Long,    // 成交额
        val capTier: Long,     // 大/中/小盘
        val flow: Long,        // 主力资金
        val sector: Long,      // 板块涨跌
        val futures: Long      // 股指期货
    )

    /** 返回各子项数据的最后成功获取时间（0 = 从未成功） */
    fun getBreadthDataTimes() = BreadthDataTimes(
        advDec   = advDecCache.lastSuccessTime,
        limits   = limitsCache.lastSuccessTime,
        turnover = turnoverCache.lastSuccessTime,
        capTier  = capTierCache.lastSuccessTime,
        flow     = flowCache.lastSuccessTime,
        sector   = sectorCache.lastSuccessTime,
        futures  = futuresPositionCacheTime
    )

    /**
     * 获取A股大盘概览。各子项独立请求、独立容错、独立缓存——任意一项失败只影响该项，
     * 缓存新鲜时不发请求，过期后请求失败则沿用最近一次成功值，超时仍失败才显示"--"。
     */
    fun getMarketBreadth(): MarketBreadthData {
        val breadth = advDecCache.getOrFetch { fetchAdvanceDecline() }
        val limits  = limitsCache.getOrFetch { fetchLimitCounts() }
        val turnover = turnoverCache.getOrFetch { fetchTotalTurnover() }
        val capTier = capTierCache.getOrFetch { fetchCapTierPct() }
        val sectors = sectorCache.getOrFetch { fetchSectorLeaders() }
        val flow    = flowCache.getOrFetch { fetchMainCapitalFlow() }
        val futuresPosition = fetchIndexFuturesPosition()   // 自带10分钟缓存

        // 内存缓存（StaleCache）在IDE/插件重启后会清空；若重启恰好发生在收盘后，实时接口本身也取不到数据，
        // 会导致完全没有"上一次成功值"可用而直接显示"--"。这里用持久化到磁盘的快照兜底最后一层。
        val persisted = loadPersistedBreadthSnapshot()
        val result = MarketBreadthData(
            upCount = breadth?.first ?: persisted?.upCount,
            downCount = breadth?.second ?: persisted?.downCount,
            flatCount = breadth?.third ?: persisted?.flatCount,
            limitUpCount = limits?.first ?: persisted?.limitUpCount,
            limitDownCount = limits?.second ?: persisted?.limitDownCount,
            totalTurnover = turnover ?: persisted?.totalTurnover,
            largeCapPct = capTier?.first ?: persisted?.largeCapPct,
            midCapPct = capTier?.second ?: persisted?.midCapPct,
            smallCapPct = capTier?.third ?: persisted?.smallCapPct,
            topSectors = sectors?.first?.map { SectorPct(it.name, it.pct) } ?: persisted?.topSectors ?: emptyList(),
            bottomSectors = sectors?.second?.map { SectorPct(it.name, it.pct) } ?: persisted?.bottomSectors ?: emptyList(),
            mainNetInflow = flow ?: persisted?.mainNetInflow,
            futuresPosition = futuresPosition
        )
        persistBreadthSnapshot(result)
        return result
    }

    private fun persistBreadthSnapshot(data: MarketBreadthData) {
        try {
            val json = JSONObject().apply {
                put("up", data.upCount ?: JSONObject.NULL)
                put("down", data.downCount ?: JSONObject.NULL)
                put("flat", data.flatCount ?: JSONObject.NULL)
                put("limitUp", data.limitUpCount ?: JSONObject.NULL)
                put("limitDown", data.limitDownCount ?: JSONObject.NULL)
                put("turnover", data.totalTurnover ?: JSONObject.NULL)
                put("large", data.largeCapPct ?: JSONObject.NULL)
                put("mid", data.midCapPct ?: JSONObject.NULL)
                put("small", data.smallCapPct ?: JSONObject.NULL)
                put("flow", data.mainNetInflow ?: JSONObject.NULL)
                put("topSectors", JSONArray().apply {
                    data.topSectors.forEach { put(JSONObject().apply { put("name", it.name); put("pct", it.pct) }) }
                })
                put("bottomSectors", JSONArray().apply {
                    data.bottomSectors.forEach { put(JSONObject().apply { put("name", it.name); put("pct", it.pct) }) }
                })
            }
            val state = StockliteState.getInstance()
            state.breadthSnapshotJson = json.toString()
            state.breadthSnapshotTime = System.currentTimeMillis()
        } catch (_: Exception) { /* 持久化失败不影响本次展示 */ }
    }

    private fun loadPersistedBreadthSnapshot(): MarketBreadthData? {
        return try {
            val state = StockliteState.getInstance()
            if (state.breadthSnapshotJson.isBlank()) return null
            if (System.currentTimeMillis() - state.breadthSnapshotTime > BREADTH_STALE_TTL) return null
            val o = JSONObject(state.breadthSnapshotJson)
            fun optInt(k: String) = if (o.isNull(k)) null else o.optInt(k)
            fun optDbl(k: String) = if (o.isNull(k)) null else o.optDouble(k)
            fun sectors(k: String): List<SectorPct> {
                val arr = o.optJSONArray(k) ?: return emptyList()
                return (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    SectorPct(s.getString("name"), s.getDouble("pct"))
                }
            }
            MarketBreadthData(
                upCount = optInt("up"), downCount = optInt("down"), flatCount = optInt("flat"),
                limitUpCount = optInt("limitUp"), limitDownCount = optInt("limitDown"),
                totalTurnover = optDbl("turnover"),
                largeCapPct = optDbl("large"), midCapPct = optDbl("mid"), smallCapPct = optDbl("small"),
                topSectors = sectors("topSectors"), bottomSectors = sectors("bottomSectors"),
                mainNetInflow = optDbl("flow")
            )
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    // 盘后多空信号汇总（启发式，非严格预测）
    // ══════════════════════════════════════════════════════════════

    /**
     * A股当日尚未收盘（交易日 15:00 前，含午休和开盘前）时返回 true。
     * 盘后预测面向"下一交易日"，需等当日数据（资金流/宽度/期指龙虎榜）定型后才有意义，
     * 收盘前应显示"待收盘"而非基于盘中未定型数据的信号。
     */
    fun isCnPendingClose(): Boolean {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        if (now.dayOfWeek.value >= 6) return false
        if (!isTodayTradingDay("CN", "Asia/Shanghai", System.currentTimeMillis())) return false
        return now.hour * 60 + now.minute < 15 * 60
    }

    /**
     * 综合盘后可得的多空信号，输出 -100..100 的倾向得分。
     * 因子与权重（每个因子先归一到 [-1,1] 再乘权重）：
     * - A50期货涨跌%      权重35（新加坡A50几乎全天交易，是A股盘后最直接的情绪代理；±1.5%记满分）
     * - 纳指期货涨跌%     权重20（隔夜全球风险偏好；±1.5%记满分）
     * - 期指主力净操作     权重15（龙虎榜前20会员当日净加空为空头信号；±5000手记满分）
     * - 主力资金净流入     权重15（当日沪深两市合计；±200亿记满分）
     * - 市场宽度          权重15（(涨-跌)/(涨+跌)）
     * 数据缺失的因子跳过、其权重不计入分母；全部缺失时返回 null。
     */
    fun getMarketForecast(breadth: MarketBreadthData): MarketForecast? {
        fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)
        val factors = mutableListOf<ForecastFactor>()
        var weightedSum = 0.0
        var totalWeight = 0.0

        fun addFactor(name: String, weight: Double, signal: Double?, detail: String) {
            if (signal == null || !signal.isFinite()) {
                factors.add(ForecastFactor(name, "数据缺失", Double.NaN))
                return
            }
            val contribution = clamp(signal) * weight
            weightedSum += contribution
            totalWeight += weight
            factors.add(ForecastFactor(name, detail, contribution))
        }

        // 期货行情：A50 + 纳指（一次请求，60秒内复用缓存，不用跟着盘后预测20秒的刷新节奏每次重新请求）
        val futures = forecastFuturesCache.getOrFetch { getFutureQuotes(listOf("hf_CHA50CFD", "hf_NQ")).takeIf { it.isNotEmpty() } } ?: emptyMap()
        val a50 = futures["hf_CHA50CFD"]?.takeIf { it.prevClose > 0 }
        val nq  = futures["hf_NQ"]?.takeIf { it.prevClose > 0 }

        addFactor("A50期货", 35.0, a50?.let { it.changePercent / 1.5 },
            a50?.let { "富时中国A50期货 ${"%+.2f".format(it.changePercent)}%" } ?: "")
        addFactor("纳指期货", 20.0, nq?.let { it.changePercent / 1.5 },
            nq?.let { "纳斯达克指数期货 ${"%+.2f".format(it.changePercent)}%" } ?: "")

        // 期指主力操作按品种性质折算：IF/IH（大盘蓝筹品种）的加空方向性更强、全权重；
        // IC/IM 空单以量化中性策略对冲盘为主、不代表方向观点，按 30% 折算，避免把常规对冲误判为利空。
        // 只有当龙虎榜数据确实是"今天"收盘后出炉的才计入预测——数据尚未更新（仍是上一交易日）时，
        // 用它代表"今天的操作"会产生误导，此时该因子按缺失处理，不参与打分（但"股指期货多空"那一行
        // 仍会正常展示最新可得数据，只是标注的日期会让用户看出不是当日，二者互不影响）。
        val fp = breadth.futuresPosition
        val todayMmDd = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).let { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }
        val fpIsToday = fp != null && fp.tradeDate == todayMmDd
        val directionalOp = if (!fpIsToday) null else fp?.let { p ->
            if (p.mainForceByVariety.isNotEmpty())
                p.mainForceByVariety.sumOf { v ->
                    v.netAddShort * (if (v.code == "IF" || v.code == "IH") 1.0 else 0.3)
                }
            else (p.mainForceShortChange - p.mainForceLongChange).takeIf { it.isFinite() }
        }
        addFactor("期指主力操作", 15.0, directionalOp?.let { -it / 3000.0 },
            if (!fpIsToday) (fp?.let { "数据未更新，最新仍是${it.tradeDate}（非当日），本因子暂不计入" } ?: "")
            else fp?.let { p ->
                directionalOp?.let {
                    val blueChip = p.mainForceByVariety.filter { v -> v.code == "IF" || v.code == "IH" }
                        .sumOf { v -> v.netAddShort }
                    "主力方向性${if (it >= 0) "加空" else "加多"}${"%.0f".format(kotlin.math.abs(it))}手" +
                        "(IF/IH合计${"%+.0f".format(blueChip)}手·全权重, IC/IM按30%折算, ${p.tradeDate})"
                }
            } ?: "")

        addFactor("主力资金", 15.0, breadth.mainNetInflow?.let { it / 2e10 },
            breadth.mainNetInflow?.let { "净${if (it >= 0) "流入" else "流出"}${"%.0f".format(kotlin.math.abs(it) / 1e8)}亿" } ?: "")

        val breadthSignal = if (breadth.upCount != null && breadth.downCount != null && breadth.upCount + breadth.downCount > 0)
            (breadth.upCount - breadth.downCount).toDouble() / (breadth.upCount + breadth.downCount) else null
        addFactor("市场宽度", 15.0, breadthSignal,
            if (breadth.upCount != null) "涨${breadth.upCount}家/跌${breadth.downCount}家" else "")

        if (totalWeight <= 0) return null
        val score = weightedSum / totalWeight * 100
        val time = java.time.LocalTime.now().let { String.format("%02d:%02d", it.hour, it.minute) }
        return MarketForecast(score = score, factors = factors, generatedAt = time)
    }

    private var aiForecastCache: String? = null
    private var aiForecastCacheTime = 0L
    private val AI_FORECAST_CACHE_TTL = 30 * 60_000L  // AI 分析 30 分钟内复用，避免后台高频烧 tokens

    /**
     * 盘后预测的 AI 二次分析（可选，仅在配置了 DeepSeek API Key 时调用）。
     * 把启发式模型的原始数据（含期指分品种明细）交给 AI，重点让它判断期指加空的性质
     * （中性对冲 vs 方向性看空）。结果缓存30分钟；失败返回 null，界面静默降级为纯启发式展示。
     * 注意：调用方应在后台线程执行（单轮请求通常需 5~20 秒）。
     */
    fun getAiForecastAnalysis(breadth: MarketBreadthData, forecast: MarketForecast, apiKey: String): String? {
        val now = System.currentTimeMillis()
        aiForecastCache?.let { if (now - aiForecastCacheTime < AI_FORECAST_CACHE_TTL) return it }

        val fp = breadth.futuresPosition
        val todayMmDd = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).let { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }
        val dataDesc = buildString {
            appendLine("以下是A股收盘后的盘面数据：")
            forecast.factors.forEach { f -> if (!f.score.isNaN()) appendLine("- ${f.name}：${f.detail}") }
            // 期指龙虎榜若不是当日出炉的数据（仍是上一交易日），不喂给 AI，避免其误当成当日操作来分析
            fp?.takeIf { it.tradeDate == todayMmDd }?.let { p ->
                if (p.mainForceByVariety.isNotEmpty()) {
                    appendLine("- 期指主力(前20会员)分品种净加空明细(${p.tradeDate})：" +
                        p.mainForceByVariety.joinToString("、") { v ->
                            "${v.name}${if (v.netAddShort >= 0) "加空" else "加多"}${"%.0f".format(kotlin.math.abs(v.netAddShort))}手"
                        })
                    appendLine("- 中信期货(代客)分品种：" +
                        p.citicByVariety.joinToString("、") { v ->
                            "${v.name}${if (v.netAddShort >= 0) "加空" else "加多"}${"%.0f".format(kotlin.math.abs(v.netAddShort))}手"
                        })
                }
            }
            breadth.topSectors.takeIf { it.isNotEmpty() }?.let { s ->
                appendLine("- 领涨板块：${s.take(3).joinToString("、") { "${it.name}${"%+.2f".format(it.pct)}%" }}")
            }
            breadth.bottomSectors.takeIf { it.isNotEmpty() }?.let { s ->
                appendLine("- 领跌板块：${s.take(3).joinToString("、") { "${it.name}${"%+.2f".format(it.pct)}%" }}")
            }
            appendLine("启发式模型综合得分：${"%+.1f".format(forecast.score)}（-100~+100，正为偏多）")
        }
        val systemPrompt = "你是A股市场分析师。特别注意：股指期货空单需区分性质——IC/IM上的空单大多是量化中性策略的" +
            "常规对冲盘（不代表看空，甚至伴随现货加仓），IF/IH上的集中加空方向性更强；请结合分品种明细判断本次" +
            "期指持仓变化更接近对冲行为还是方向性押注，并综合其它数据给出对下一交易日的判断。"
        val userPrompt = dataDesc + "\n请给出：1)偏多/偏空/震荡的判断 2)不超过3句话的核心理由（必须提及期指空单性质的判断）。全文不超过120字。"

        val reply = AiAnalysisService.quickAnalyze(systemPrompt, userPrompt, apiKey, maxTokens = 300) ?: return null
        aiForecastCache = reply
        aiForecastCacheTime = now
        return reply
    }

    // ══════════════════════════════════════════════════════════════
    // 股票搜索（移植自 registerStockSearchHandler，扩展支持港股/美股）
    // ══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    // 美股板块 ETF 行情
    // ══════════════════════════════════════════════════════════════

    /** 板块 ETF 定义：symbol → 中文名 */
    val US_SECTOR_ETFS = listOf(
        // ── 科技/AI ──
        "XLK"  to "科技",
        "AIQ"  to "AI算力",       // Global X Artificial Intelligence & Technology
        "BOTZ" to "AI/机器人",    // Global X Robotics & Artificial Intelligence
        "ROBO" to "机器人",       // ROBO Global Robotics and Automation Index
        "SOXX" to "半导体",       // iShares Semiconductor
        "SKYY" to "云计算",       // First Trust Cloud Computing
        "DTCR" to "数据中心",     // Global X Data Center & Digital Infrastructure
        "HACK" to "网络安全",     // ETFMG Prime Cyber Security
        // ── 工业/航天/国防 ──
        "XLI"  to "工业",
        "ITA"  to "军工",         // iShares U.S. Aerospace & Defense
        "UFO"  to "商业航天/卫星",// Procure Space ETF
        "DRIV" to "自动驾驶",     // Global X Autonomous & Electric Vehicles
        // ── 能源/新能源 ──
        "XLE"  to "能源",
        "XOP"  to "油气",         // SPDR Oil & Gas Exploration & Production
        "USO"  to "石油",         // United States Oil Fund
        "UNG"  to "天然气",       // United States Natural Gas Fund
        "ICLN" to "新能源",       // iShares Global Clean Energy
        "TAN"  to "光伏",         // Invesco Solar
        "NLR"  to "核电",         // VanEck Uranium+Nuclear Energy
        "GRID" to "电网",         // First Trust NASDAQ Smart Grid Infrastructure
        // ── 金属/材料 ──
        "XLB"  to "材料",
        "GLD"  to "黄金",
        "COPX" to "铜/有色",      // Global X Copper Miners
        "LIT"  to "锂电池",       // Global X Lithium & Battery Tech
        // ── 医疗/生物 ──
        "XLV"  to "医疗",
        "XBI"  to "生物科技",
        // ── 金融/消费 ──
        "XLF"  to "金融",
        "KBE"  to "银行",
        "XLY"  to "消费(可选)",
        "XLP"  to "消费(必需)",
        // ── 其他 ──
        "XLU"  to "公用事业",
        "XLRE" to "房地产",
        "XLC"  to "通信"
    )

    enum class UsSession { PRE, REGULAR, POST, CLOSED }

    /** 根据美东时间判断当前美股交易时段（忽略节假日，仅按时间区间） */
    fun currentUsSession(): UsSession {
        val ny  = ZonedDateTime.now(ZoneId.of("America/New_York"))
        val dow = ny.dayOfWeek.value          // 1=Mon..7=Sun
        if (dow == 6 || dow == 7) return UsSession.CLOSED
        val t = ny.hour * 60 + ny.minute
        return when {
            t in  4 * 60 until  9 * 60 + 30 -> UsSession.PRE
            t in  9 * 60 + 30 until 16 * 60  -> UsSession.REGULAR
            t in 16 * 60 until 20 * 60        -> UsSession.POST
            else                              -> UsSession.CLOSED
        }
    }

    fun getUsSectorQuotes(): Map<String, SectorQuote> {
        val result = mutableMapOf<String, SectorQuote>()
        val session = currentUsSession()
        for ((symbol, nameCn) in US_SECTOR_ETFS) {
            try {
                val enc = URLEncoder.encode(symbol, "UTF-8")
                // includePrePost=true 使盘前/盘后分钟数据包含在 indicators 里
                val raw = HttpUtil.get(
                    "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=1d&includePrePost=true",
                    label = "美股板块ETF"
                ) ?: continue
                val result0 = JSONObject(raw).optJSONObject("chart")
                    ?.optJSONArray("result")?.optJSONObject(0) ?: continue
                val meta = result0.optJSONObject("meta") ?: continue

                // 昨日正式收盘价（regularMarketPrice 在盘前/盘后也不变，等于昨收）
                val prevClose = meta.optDouble("chartPreviousClose", Double.NaN).let {
                    if (it.isFinite() && it > 0) it
                    else meta.optDouble("regularMarketPreviousClose", Double.NaN).let { c ->
                        if (c.isFinite() && c > 0) c else meta.optDouble("previousClose", Double.NaN)
                    }
                }.takeIf { it.isFinite() && it > 0 } ?: continue

                // 上次正式收盘涨跌幅（regularMarketPrice 在盘前/盘后等于昨收，涨跌幅为 0；
                // 取 meta 中的 regularMarketChangePercent 更准）
                val regularPrice = meta.optDouble("regularMarketPrice", Double.NaN)
                    .takeIf { it.isFinite() && it > 0 } ?: prevClose
                val regularPct   = (regularPrice - prevClose) / prevClose * 100

                // 盘前/盘后最新价：从 indicators 分钟线取最后一根有效 close
                val extPct: Double? = if (session == UsSession.PRE || session == UsSession.POST) {
                    val timestamps = result0.optJSONArray("timestamp")
                    val closes = result0.optJSONObject("indicators")
                        ?.optJSONArray("quote")?.optJSONObject(0)
                        ?.optJSONArray("close")
                    if (timestamps != null && closes != null) {
                        val nyZone  = ZoneId.of("America/New_York")
                        val preStart  =  4 * 60   // 04:00 ET in minutes
                        val preEnd    =  9 * 60 + 30
                        val postStart = 16 * 60
                        val postEnd   = 20 * 60
                        var lastExtPrice: Double? = null
                        for (i in 0 until minOf(timestamps.length(), closes.length())) {
                            val ts = timestamps.optLong(i, -1).takeIf { it > 0 } ?: continue
                            val cl = closes.optDouble(i, Double.NaN).takeIf { it.isFinite() && it > 0 } ?: continue
                            val nyMin = java.time.Instant.ofEpochSecond(ts)
                                .atZone(nyZone).let { it.hour * 60 + it.minute }
                            val inRange = if (session == UsSession.PRE)
                                nyMin in preStart until preEnd
                            else
                                nyMin in postStart until postEnd
                            if (inRange) lastExtPrice = cl
                        }
                        if (lastExtPrice != null) (lastExtPrice - prevClose) / prevClose * 100
                        else null
                    } else null
                } else null

                val prePct  = if (session == UsSession.PRE)  extPct else null
                val postPct = if (session == UsSession.POST) extPct else null

                result[symbol] = SectorQuote(symbol, nameCn, regularPct, prePct, postPct)
            } catch (_: Exception) {}
        }
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // 财报日期（预约披露时间）
    // ══════════════════════════════════════════════════════════════

    /**
     * 获取 A 股股票的最近财报公告日期。
     * 返回：最近一次历史公告日（格式 "yyyy-MM-dd"），以及下次预约公告日（可能为 null）。
     * 数据来自东方财富 RPT_LICO_FN_CPD，包含历史和预约披露时间。
     * @param pureCode 纯6位代码，如 "600519"（不含 sh/sz 前缀）
     */
    fun getEarningsDate(pureCode: String): Pair<String?, String?> {
        return try {
            val enc = java.net.URLEncoder.encode("(SECURITY_CODE=\"$pureCode\")", "UTF-8")
            val url = "https://datacenter-web.eastmoney.com/api/data/v1/get" +
                "?reportName=RPT_LICO_FN_CPD&columns=SECURITY_CODE,NOTICE_DATE" +
                "&filter=$enc&sortColumns=NOTICE_DATE&sortTypes=-1&pageNumber=1&pageSize=10&source=WEB"
            val raw = HttpUtil.get(url) ?: return null to null
            val items = JSONObject(raw).optJSONObject("result")?.optJSONArray("data")
                ?: return null to null
            val today = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
            var lastDate: String? = null
            var nextDate: String? = null
            for (i in 0 until items.length()) {
                val date = items.getJSONObject(i).optString("NOTICE_DATE", "").take(10)
                if (date.isEmpty()) continue
                if (date >= today && (nextDate == null || date < nextDate)) nextDate = date
                if (date < today  && (lastDate == null || date > lastDate)) lastDate = date
            }
            lastDate to nextDate
        } catch (_: Exception) { null to null }
    }

    /**
     * 批量获取多只 A 股的财报日期，返回 code -> Pair<last, next>。
     * 每只股票单独请求（接口不支持 AND 条件批量过滤）。
     */
    fun getEarningsDates(pureCodes: List<String>): Map<String, Pair<String?, String?>> {
        return pureCodes.associateWith { code -> getEarningsDate(code) }
    }

    fun searchStocks(keyword: String): List<StockSearchResult> {
        if (keyword.isBlank()) return emptyList()
        val results = mutableListOf<StockSearchResult>()

        // ── 1. 新浪搜索：A 股 + 港股（GBK 编码，速度最快）──
        try {
            val enc = URLEncoder.encode(keyword, "UTF-8")
            val raw = HttpUtil.getGbk("https://suggest3.sinajs.cn/suggest/key=$enc") ?: ""
            val m = Regex(""""([^"]+)"""").find(raw)
            m?.groupValues?.get(1)?.split(";")?.filter { it.isNotBlank() }?.forEach { item ->
                val parts  = item.split(",")
                val symbol = parts.getOrElse(3) { "" }.ifEmpty { parts.getOrElse(2) { "" } }.trim()
                val name   = parts.getOrElse(0) { "" }.trim()
                if (symbol.isEmpty() || name.isEmpty()) return@forEach
                when {
                    symbol.startsWith("sh") || symbol.startsWith("sz") ->
                        results.add(StockSearchResult(symbol, name))
                    symbol.matches(Regex("\\d{6}")) -> {
                        val full = if (symbol.startsWith("6") || symbol.startsWith("5")) "sh$symbol" else "sz$symbol"
                        results.add(StockSearchResult(full, name))
                    }
                    symbol.matches(Regex("\\d{5}")) ->
                        results.add(StockSearchResult("hk$symbol", name))
                    symbol.matches(Regex("[Hh][Kk]\\d{4,5}")) -> {
                        val code = symbol.takeLast(5).padStart(5, '0')
                        results.add(StockSearchResult("hk$code", name))
                    }
                }
            }
        } catch (_: Exception) {}

        // ── 2. 东方财富搜索：支持中文/英文/代码搜美股，全库覆盖 ──
        val looksLikeChinese = keyword.any { it.code in 0x4E00..0x9FFF }
        val looksLikeUsTicker = keyword.matches(Regex("[A-Za-z][A-Za-z0-9.\\-]{0,8}"))
        if (looksLikeChinese || looksLikeUsTicker || results.isEmpty()) {
            try {
                val enc = URLEncoder.encode(keyword, "UTF-8")
                val raw = HttpUtil.get(
                    "https://searchapi.eastmoney.com/api/suggest/get?input=$enc&type=14&token=D43BF722C8E33BDC906FB84D85E326E8"
                ) ?: ""
                val data = JSONObject(raw)
                    .optJSONObject("QuotationCodeTable")
                    ?.optJSONArray("Data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    if (o.optString("Classify") != "UsStock") continue
                    val sym  = o.optString("Code", "").takeIf { it.isNotEmpty() } ?: continue
                    val name = o.optString("Name", sym)
                    if (results.none { it.symbol == sym }) {
                        results.add(StockSearchResult(sym, name))
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
        Triple("hf_CHA50CFD", "富时中国A50期货", listOf("A50","FTSE","富时")),
        Triple("hf_MCA",  "MSCI中国A50指数", listOf("MSCI","A50")),
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
                        // 88/87=国内期货（nf_ 前缀）；86=外盘期货（hf_ 前缀，如富时中国A50期货 cha50cfd、
                        // 纽约黄金 gc），此前 86 被过滤导致所有外盘品种只能靠本地兜底表、A50 完全搜不到
                        if (type != "88" && type != "87" && type != "86") return@forEach
                        val code = (parts[2].ifEmpty { parts[3] }).trim()
                        if (code.isEmpty()) return@forEach
                        val symbol = if (type == "86" && !code.startsWith("hf_", true))
                            normFutureSymbol("hf_$code") else normFutureSymbol(code)
                        if (symbol.isEmpty()) return@forEach
                        val cnName = parts.getOrElse(4) { "" }.trim()
                        val enName = parts.getOrElse(0) { "" }.trim()
                        val name   = cnName.ifEmpty { enName }.ifEmpty { symbol }
                        remote.add(FutureSearchResult(symbol, name))
                    }
                }
            }
        } catch (_: Exception) { /* 网络失败，fallback 到本地 */ }

        // 编码直查：输入形如 IF2609 / AP0 / cha50cfd / nf_AU0 / hf_GC 的合约代码时，直接拿行情接口
        // 验证（联想接口对带 nf_/hf_ 前缀或冷门品种代码常无联想结果），有真实行情数据才作为结果返回。
        // 无前缀时国内/外盘两种前缀都试；无效代码新浪返回空串会被 parseFutureFields 安全跳过。
        val direct = mutableListOf<FutureSearchResult>()
        val kwRaw = keyword.trim()
        if (Regex("^(nf_|hf_)?[A-Za-z][A-Za-z0-9]{0,15}$", RegexOption.IGNORE_CASE).matches(kwRaw)) {
            val candidates = if (kwRaw.contains("_")) listOf(normFutureSymbol(kwRaw))
                             else listOf(normFutureSymbol("nf_$kwRaw"), normFutureSymbol("hf_$kwRaw"))
            try {
                getFutureQuotes(candidates).forEach { (sym, q) ->
                    if (q.price > 0) direct.add(FutureSearchResult(sym, q.name))
                }
            } catch (_: Exception) { /* 直查失败不影响其余通道 */ }
        }

        // 本地表兜底（同原版 FUTURE_CONTRACTS fallback）
        val fallback = LOCAL_FUTURES.filter { (sym, name, aliases) ->
            sym.lowercase().contains(trimmed) ||
            name.lowercase().contains(trimmed) ||
            aliases.any { it.lowercase().contains(trimmed) }
        }.map { (sym, name, _) -> FutureSearchResult(sym, name) }

        // 去重合并：编码精确直查结果优先展示
        val dedup = linkedMapOf<String, FutureSearchResult>()
        (direct + remote + fallback).forEach { if (!dedup.containsKey(it.symbol)) dedup[it.symbol] = it }
        return dedup.values.take(80)
    }
}
