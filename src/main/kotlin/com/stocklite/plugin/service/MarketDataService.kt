package com.stocklite.plugin.service

import com.stocklite.plugin.state.*
import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
        GlobalIndexConfig("^NDX",     "NDX",    "NASDAQ 100",      "纳指100",    "US", "America/New_York"),
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
        GlobalIndexConfig("^BVSP",    "BVSP",   "Bovespa",         "巴西IBOVESPA","BR", "America/Sao_Paulo"),
    )

    private val SINA_SYMBOL_MAP = mapOf(
        "^DJI"      to "gb_dji",
        "^IXIC"     to "gb_ixic",
        "^NDX"      to "gb_\$ndx",
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
            "BR" -> Triple(10*60,   17*60, null)
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
        "HK" to "^HSI", "CN" to "000001.SS", "BR" to "^BVSP",
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
    fun isTodayTradingDay(market: String, timezone: String, now: Long): Boolean {
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
                sinaSymbol.startsWith("sh") || sinaSymbol.startsWith("sz") || sinaSymbol.startsWith("bj") -> {
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
    // A股早盘集合竞价
    // 新浪免费接口在竞价期间现价为 0；腾讯 qt.gtimg.cn 在 9:15–9:25 约 3 秒/笔返回
    // 虚拟撮合价，9:25 定格后即开盘价、9:25–9:30 持续展示竞价结果，故窗口内 A 股切换腾讯源。
    // ══════════════════════════════════════════════════════════════

    private val CN_ZONE = ZoneId.of("Asia/Shanghai")

    /**
     * A 股涨跌停价（元，昨收 ± 幅度四舍五入到 0.01）：主板 10%（含 ST 与 *ST，
     * 新规已与主板普通股统一，不再适用 5%）、创业板/科创板 20%、北交所 30%。
     */
    private fun cnLimitPrices(symbol: String, prevClose: Double): Pair<Double, Double>? {
        if (prevClose <= 0) return null
        val ratio = when {
            symbol.startsWith("bj") -> 0.30
            symbol.startsWith("sz30") || symbol.startsWith("sh688") -> 0.20
            else -> 0.10
        }
        fun round2(v: Double) = Math.floor(v * 100 + 0.5) / 100
        return round2(prevClose * (1 + ratio)) to round2(prevClose * (1 - ratio))
    }

    /**
     * 封单手数（正=涨停买一封单，负=跌停卖一封单，未封板 null）。
     * bidVol1Lots/askVol1Lots 统一按"手"传入（新浪盘口量为股，需先除以 100）。
     */
    private fun cnSealLots(symbol: String, price: Double, prevClose: Double,
                           bidVol1Lots: Double?, askVol1Lots: Double?): Long? {
        if (price <= 0) return null
        val (limitUp, limitDown) = cnLimitPrices(symbol, prevClose) ?: return null
        val eps = 1e-6
        return when {
            price >= limitUp - eps  -> bidVol1Lots?.let { v -> if (v <= 0) null else (v + 0.5).toLong() }
            price <= limitDown + eps -> askVol1Lots?.let { v -> if (v <= 0) null else -(v + 0.5).toLong() }
            else -> null
        }
    }

    /** 当前是否处于 A 股早盘集合竞价展示窗口（上海时区交易日 9:15–9:30） */
    fun isInAStockAuction(now: Long = System.currentTimeMillis()): Boolean {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), CN_ZONE)
        if (zdt.dayOfWeek.value == 6 || zdt.dayOfWeek.value == 7) return false
        val minutes = zdt.hour * 60 + zdt.minute
        if (minutes < 9 * 60 + 15 || minutes >= 9 * 60 + 30) return false
        return isTodayTradingDay("CN", "Asia/Shanghai", now)
    }

    /**
     * 竞价行情（腾讯批量，50 只/请求）。
     * 只收录行情时间戳落在当天 9:15 之后的标的：北交所等品种竞价期间时间戳仍冻结在
     * 9:15 前、无竞价数据，不收录，由调用方回退新浪的默认表现。
     */
    private fun getAStockAuctionQuotes(symbols: List<String>, now: Long = System.currentTimeMillis()): Map<String, StockQuote> {
        if (symbols.isEmpty()) return emptyMap()
        // 注意不能用 BASIC_ISO_DATE：格式化 ZonedDateTime 会带上 "+0800" 偏移后缀，导致与 14 位行情时间戳前缀比对永远失败
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), CN_ZONE)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val result = mutableMapOf<String, StockQuote>()
        for (chunk in symbols.chunked(50)) {
            val raw = HttpUtil.getGbk("https://qt.gtimg.cn/q=${chunk.joinToString(",")}",
                referer = "https://gu.qq.com", label = "A股集合竞价行情") ?: continue
            // 腾讯 A 股字段（0 基）：1=名称 3=现价 4=昨收 30=时间戳yyyyMMddHHmmss 31=涨跌 32=涨跌幅%
            for (m in Regex("""v_(\w+)="([^"]*)"""").findAll(raw)) {
                val symbol    = m.groupValues[1]
                val f         = m.groupValues[2].split("~")
                val price     = f.getOrElse(3) { "" }.toDoubleOrNull() ?: continue
                val prevClose = f.getOrElse(4) { "" }.toDoubleOrNull() ?: continue
                if (price <= 0 || prevClose <= 0) continue
                val ts = f.getOrElse(30) { "" }
                if (ts.length != 14 || !ts.startsWith(today)) continue
                val hhmmss = ts.substring(8).toIntOrNull() ?: continue
                if (hhmmss < 91500) continue
                val change    = f.getOrElse(31) { "" }.toDoubleOrNull() ?: (price - prevClose)
                val changePct = f.getOrElse(32) { "" }.toDoubleOrNull()
                    ?: if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0
                val name = f.getOrElse(1) { symbol }.ifEmpty { symbol }
                // 封单量：腾讯字段 47/48=涨停/跌停价，买一量=f[10]、卖一量=f[20]（手）
                val limitUp   = f.getOrElse(47) { "" }.toDoubleOrNull()
                val limitDown = f.getOrElse(48) { "" }.toDoubleOrNull()
                val seal = when {
                    limitUp != null && price >= limitUp -> f.getOrElse(10) { "" }.toDoubleOrNull()?.let { if (it <= 0) null else (it + 0.5).toLong() }
                    limitDown != null && price <= limitDown -> f.getOrElse(20) { "" }.toDoubleOrNull()?.let { if (it <= 0) null else -(it + 0.5).toLong() }
                    else -> null
                }
                // 9:25 竞价定格后价格即当日开盘价，按正常行情展示，不再带竞价标记
                result[symbol] = StockQuote(symbol, name, price, prevClose, change, changePct, isAuction = hhmmss < 92500, sealLots = seal)
            }
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
        // 北交所 bj 前缀与沪深同走新浪 A 股协议，字段布局一致
        val aShares  = symbols.filter { it.startsWith("sh") || it.startsWith("sz") || it.startsWith("bj") }
        val hkShares = symbols.filter { it.startsWith("hk") }
        val usShares = symbols.filter { !it.startsWith("sh") && !it.startsWith("sz") && !it.startsWith("bj") && !it.startsWith("hk") }

        // A股集合竞价窗口（9:15–9:30）内优先腾讯竞价源，取不到的回退新浪
        val aSharesViaSina = if (aShares.isNotEmpty() && isInAStockAuction()) {
            val auction = getAStockAuctionQuotes(aShares)
            result.putAll(auction)
            aShares - auction.keys
        } else aShares

        // A-shares via Sina
        if (aSharesViaSina.isNotEmpty()) {
            val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=${aSharesViaSina.joinToString(",")}",
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
                // 封单量取盘口一档量：买一量=fields[10]、卖一量=fields[20]，新浪单位为股，折手除以 100
                val bidLots = if (fields.size > 21) fields[10].toDoubleOrNull()?.let { it / 100 } else null
                val askLots = if (fields.size > 21) fields[20].toDoubleOrNull()?.let { it / 100 } else null
                val seal = cnSealLots(symbol, price, prevClose, bidLots, askLots)
                result[symbol] = StockQuote(symbol, name, price.takeIf { it != 0.0 } ?: prevClose, prevClose, change, changePct, sealLots = seal)
            }
        }

        // HK shares via Tencent
        for (sym in hkShares) {
            val code   = sym.removePrefix("hk")
            val tSym   = "r_hk$code"
            val raw    = HttpUtil.getGbk("https://qt.gtimg.cn/q=$tSym", referer = "https://gu.qq.com",
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

                result[sym] = StockQuote(sym, name, price, prevClose, change, pct)
            } catch (_: Exception) {}
        }

        // 美股盘前/盘后延伸数据：Yahoo v8 chart 的 meta 从未提供 pre/postMarketPrice 字段
        // （旧读取逻辑永远不生效），改在盘前/盘后窗口用新浪 gb_ 批量补充
        val usSession = currentUsSession()
        if (usShares.isNotEmpty() &&
            (usSession == UsSession.PRE || usSession == UsSession.POST)) {
            for ((sym, pair) in getUsExtendedQuotes(usShares, usSession)) {
                val q = result[sym] ?: continue
                result[sym] = q.copy(
                    extendedPrice = pair.first,
                    extendedChangePercent = pair.second,
                    extendedSession = if (usSession == UsSession.PRE)
                        ExtendedSession.PRE_MARKET else ExtendedSession.POST_MARKET
                )
            }
        }

        return result
    }

    /**
     * 新浪 gb_ 批量取美股盘前/盘后价（symbols 为不带市场前缀的裸代码）。
     * 返回 代码 → (延伸价, 相对最近正式收盘的涨跌幅%)；延伸时间戳非当天（ET）视为陈旧数据丢弃。
     */
    private fun getUsExtendedQuotes(symbols: List<String>, session: UsSession): Map<String, Pair<Double, Double>> {
        val result = mutableMapOf<String, Pair<Double, Double>>()
        if (session != UsSession.PRE && session != UsSession.POST) return result
        val codeToSym = symbols.associateBy { "gb_" + it.lowercase() }
        for (chunk in codeToSym.keys.chunked(30)) {
            val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=${chunk.joinToString(",")}",
                "http://finance.sina.com.cn", label = "美股延伸时段行情") ?: continue
            for (m in Regex("""var hq_str_gb_([^=]+)="([^"]*)"""").findAll(raw)) {
                val sym = codeToSym["gb_" + m.groupValues[1].lowercase()] ?: continue
                val f   = m.groupValues[2].split(",")
                if (f.size < 27) continue
                val close = f[1].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                val ext   = f[21].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                if (parseSinaUsStamp(f.getOrElse(24) { "" }) != etToday()) continue
                result[sym] = ext to (ext - close) / close * 100
            }
        }
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // 基金行情（移植自 registerFundQuoteHandlers — 完整多级备用逻辑）
    // ══════════════════════════════════════════════════════════════

    // 面板自身的刷新定时器和 FundNavWatcherService 的后台轮询会各自在独立线程上并发调用
    // getFundQuotes，下方水位线缓存必须使用线程安全集合，否则并发读写可能
    // 抛出 ConcurrentModificationException 导致某轮抓取静默失败。

    /**
     * 官方净值水位线：记录每个基金代码"已确认"的最新官方净值（nav/changePercent/date）。
     * 腾讯、F10 均走 CDN，边缘节点数据不同步时偶发返回滞后快照，
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
                // 本次官方日期落后于已知最优（命中滞后 CDN 节点等）——用水位线纠正
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

    // 天天基金实时估值接口 fundgz.1234567.com.cn 已整体下线（官方公告"下线指数基金净值估算
    // 功能"，接口对所有代码返回 404 页，东财移动 API 的 GSZ 字段亦为 null），基金面板
    // 因此不再有"今日估算"列。新浪 fund_ 基金接口同样已下线（对所有代码返回空串），
    // 行情改为 腾讯 jj 批量接口 + 东财 F10 官方净值 两级结构。
    fun getFundQuotes(codes: List<String>): Map<String, FundQuote> {
        if (codes.isEmpty()) return emptyMap()
        val unique = codes.map { normFundCode(it) }.distinct().filter { it.isNotEmpty() }
        val results = mutableMapOf<String, FundQuote>()

        // ── 第一步：腾讯批量抓取（名称 + 单位净值 + 涨跌 + 净值日期，一次请求覆盖全部代码） ──
        fetchFundsTencent(unique).forEach { (c, q) -> results[c] = q }

        // ── 第二步：F10 增强（东财历史净值 API 是官方涨跌权威来源，逐代码补全） ──
        //   合并原则：日期更新的数据胜出；F10 失败或日期滞后时不覆盖已有净值日期 ──
        for (code in unique) {
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
                    // 取两者中较新的日期：F10 已更新时用 F10 日期，F10 CDN 旧节点返回旧日期时保持腾讯日期不退
                    date                    = maxOf(existing.date, f10.date.ifEmpty { existing.date })
                )
            }
        }

        // ── 官方净值水位线：跨轮次纠正，杜绝面板定时器与后台 Watcher 并发轮询时
        //    谁先返回谁生效导致的净值新旧来回跳变 ──
        for (code in unique) {
            results[code]?.let { results[code] = advanceHighWaterMark(code, it) }
        }

        return results
    }

    // 腾讯 jj 基金接口（GBK 编码，批量），字段以 ~ 分隔：
    // 0=代码 1=名称 5=单位净值 6=累计净值 7=净值日涨跌% 8=净值日期
    private fun fetchFundsTencent(codes: List<String>): Map<String, FundQuote> {
        if (codes.isEmpty()) return emptyMap()
        val raw = HttpUtil.getGbk("https://qt.gtimg.cn/q=${codes.joinToString(",") { "jj$it" }}",
            label = "基金行情-腾讯") ?: return emptyMap()
        val result = mutableMapOf<String, FundQuote>()
        val re = Regex("""v_jj(\d{6})="([^"]*)"""")
        for (m in re.findAll(raw)) {
            val code = m.groupValues[1]
            val f = m.groupValues[2].split("~")
            if (f.size < 9) continue
            val name = f[1].trim().takeIf { it.isNotEmpty() } ?: continue
            val nav  = f[5].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            val pct  = f[7].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            result[code] = FundQuote(
                code = code, name = name, nav = nav,
                changePercent = pct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
                date = f[8].trim().ifEmpty { today() }
            )
        }
        return result
    }

    /**
     * 基金历史净值：东方财富官方前端已将该接口迁移至 api.fund.eastmoney.com/f10/lsjz（JSON），
     * 旧版 fundf10.eastmoney.com/F10DataApi.aspx?type=lsjz（HTML 表格）已被后端废弃，
     * 现对任意基金代码均返回空响应（HTTP 200，body 仅 "var apidata="），不再更新数据。
     */
    private data class F10Nav(val date: String, val nav: Double, val changePercent: Double, val prevNav: Double)

    private fun fetchF10Nav(code: String): F10Nav? {
        val raw = HttpUtil.get(
            "https://api.fund.eastmoney.com/f10/lsjz?fundCode=$code&pageIndex=1&pageSize=1",
            "https://fundf10.eastmoney.com/jjjz_$code.html", label = "基金历史净值(F10)") ?: return null
        return parseF10LatestNav(raw)
    }

    private fun parseF10LatestNav(json: String): F10Nav? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("ErrCode", 0) != 0) return null
            val list = root.optJSONObject("Data")?.optJSONArray("LSJZList") ?: return null
            if (list.length() == 0) return null
            val first = list.getJSONObject(0)
            val date  = first.optString("FSRQ", "")
            val nav   = first.optString("DWJZ", "").toDoubleOrNull() ?: return null
            if (nav <= 0) return null
            val changePct = first.optString("JZZZL", "").toDoubleOrNull() ?: Double.NaN
            val prevNav = if (changePct.isFinite()) nav / (1 + changePct / 100) else Double.NaN
            F10Nav(date, nav, changePct, prevNav)
        } catch (_: Exception) { null }
    }

    private fun buildOfficialQuote(code: String, name: String, p: F10Nav): FundQuote {
        val prevNav    = p.prevNav.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val change     = if (prevNav > 0) p.nav - prevNav else 0.0
        val changePct  = if (p.changePercent.isFinite()) p.changePercent
                         else if (prevNav > 0) change / prevNav * 100 else 0.0
        return FundQuote(code = code, name = name,
            nav = p.nav.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP).toDouble(),
            changePercent = changePct.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP).toDouble(),
            date = p.date.ifEmpty { today() })
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
        val raw  = HttpUtil.getGbk("https://qt.gtimg.cn/q=$tSym",
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

    /**
     * 上一交易日"同期"两市成交额：取上证/深证成指在东财的分分钟走势（ndays=2 返回最近两个交易日），
     * 上一交易日取返回数据中的较早日期，天然跳过周末与节假日；
     * 盘中截至当前分钟累计，休市/盘前则取整日合计（此时实时成交额本身就是上一交易日收盘值，对比依然成立）。
     * 数据源说明：新浪分钟线的 amount 在深证成指上少计约一半（全天合计仅为真实成交额的一半），
     * 而东财 trends2 的分钟成交额与其日线完全一致，故以其为准。
     * 返回 Pair(同期累计成交额(元), 上一交易日日期)；任一指数数据缺失时返回 null（不做对比）。
     */
    private fun fetchPrevDaySameTimeTurnover(): Pair<Double, String>? {
        val shZone = ZoneId.of("Asia/Shanghai")
        val now    = ZonedDateTime.now(shZone)
        val today  = now.toLocalDate().toString()
        var total = 0.0
        var prevDayStr: String? = null
        for (secid in listOf("1.000001", "0.399001")) {   // 上证指数 / 深证成指
            val raw = HttpUtil.get(
                "https://push2his.eastmoney.com/api/qt/stock/trends2/get?secid=$secid&fields1=f1&fields2=f51,f57&ndays=2&iscr=0",
                referer = "https://quote.eastmoney.com/", label = "成交额同期对比-东财分钟走势") ?: return null
            val trends = try { JSONObject(raw).optJSONObject("data")?.optJSONArray("trends") } catch (_: Exception) { null }
            if (trends == null || trends.length() == 0) return null
            // 行格式 "2026-09-21 09:30,成交额(元)"
            val dates = (0 until trends.length())
                .mapNotNull { i -> trends.optString(i, "").takeIf { it.length >= 10 }?.substring(0, 10) }
                .distinct()
            val lastDataDay = dates.maxOrNull() ?: return null
            val prevDay     = dates.filter { it < lastDataDay }.maxOrNull() ?: return null
            // 走势含当日数据 = 今日是交易日，截至当前分钟；否则休市/盘前，取整日
            val cutoff = if (lastDataDay == today) "%02d:%02d".format(now.hour, now.minute) else "23:59"
            var sum = 0.0
            for (i in 0 until trends.length()) {
                val row = trends.optString(i, "")
                if (row.length < 20 || !row.startsWith(prevDay)) continue
                if (row.substring(11, 16) > cutoff) continue
                sum += row.substringAfter(",").toDoubleOrNull() ?: continue
            }
            if (sum <= 0) return null
            total += sum
            prevDayStr = prevDay
        }
        return if (total > 0) total to (prevDayStr ?: return null) else null
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

    // ── A股板块行情（行业/概念/地域全量列表，东财 clist 同一接口族） ──

    /** A股板块行情条目。fsT 对应东财板块分类：1=地域 2=行业 3=概念 */
    data class AStockSector(
        val code: String,           // 板块代码，如 BK0475
        val name: String,
        val price: Double,          // 板块指数点位，缺失为 NaN
        val changePct: Double,      // 涨跌幅%
        val turnoverRate: Double,   // 换手率%
        val mainInflow: Double,     // 主力净流入（元），负数为净流出
        val upCount: Int,           // 板块内上涨家数
        val downCount: Int,         // 板块内下跌家数
        val leaderName: String,     // 领涨股名称
        val leaderCode: String,     // 领涨股代码（f140），用于点击弹走势图
        val leaderMarket: Int,      // 领涨股市场（f141）：1=沪 0=深，北交所按代码前缀判断
        val leaderPct: Double       // 领涨股涨幅%
    )

    // 60秒新鲜度与大盘概览同节奏；该接口偶发限流断连，沿用 StaleCache 的失败兜底窗口
    // 覆盖休市时段（收盘后东财仍返回收盘数据，真正取不到时靠旧值撑住界面）
    private val sectorListTtl = 60_000L to 3 * 24 * 60 * 60_000L
    private val sectorListCaches = mapOf(
        1 to StaleCache<List<AStockSector>>(sectorListTtl.first, sectorListTtl.second),
        2 to StaleCache<List<AStockSector>>(sectorListTtl.first, sectorListTtl.second),
        3 to StaleCache<List<AStockSector>>(sectorListTtl.first, sectorListTtl.second)
    )

    /** 板块全量行情（t: 1地域/2行业/3概念）。缓存新鲜时不发请求；失败时兜底期内沿用旧值 */
    fun getSectorQuotes(fsT: Int): List<AStockSector>? =
        sectorListCaches[fsT]?.getOrFetch { fetchSectorList(fsT) }

    /**
     * 抓取一个分类的板块列表。实测该接口对直连请求随机断连（HTTP 层握手失败，约七成请求
     * 被拒，数字镜像域名同样如此），且大分页（pz>=500）易触发连坐封禁；因此：
     * 单页固定 100 条按 pn 翻页（概念板块 500+ 条）、上限 12 页防死循环；
     * 每页失败间隔 1.2 秒重试一次（背靠背重试无意义），页间留 400ms 缓和。
     * 中途断连时返回已取到的部分——接口按涨跌幅降序返回，已取部分即板块榜头部，
     * 状态栏"共 N 个"如实反映条数，面板 60 秒轮询下一轮自动补全。
     */
    private fun fetchSectorList(fsT: Int): List<AStockSector>? {
        val fields = "f2,f3,f8,f12,f14,f62,f104,f105,f128,f136,f140,f141"
        val result = ArrayList<AStockSector>()
        var total = Int.MAX_VALUE
        var page = 1
        while (result.size < total && page <= 12) {
            if (page > 1) runCatching { Thread.sleep(400) }
            val url = "https://push2.eastmoney.com/api/qt/clist/get" +
                "?pn=$page&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:90+t:$fsT&fields=$fields"
            var raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/", label = "板块行情")
            if (raw == null) {
                if (runCatching { Thread.sleep(1200) }.isFailure) break   // 线程中断（插件卸载）时收尾返回已取部分
                raw = HttpUtil.get(url, referer = "https://data.eastmoney.com/", label = "板块行情")
            }
            val data = try { raw?.let { JSONObject(it).optJSONObject("data") } } catch (_: Exception) { null }
            val diff = data?.optJSONArray("diff")
            if (diff == null || diff.length() == 0)
                return if (page == 1 || result.isEmpty()) null else result
            if (page == 1) total = data.optInt("total", 0)
            for (i in 0 until diff.length()) {
                val o = diff.optJSONObject(i) ?: continue
                val code = o.optString("f12", "")
                val name = o.optString("f14", "")
                if (code.isEmpty() || name.isEmpty()) continue
                result.add(AStockSector(
                    code = code, name = name,
                    price = o.optDouble("f2", Double.NaN),
                    changePct = o.optDouble("f3", Double.NaN),
                    turnoverRate = o.optDouble("f8", Double.NaN),
                    mainInflow = o.optDouble("f62", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    upCount = o.optInt("f104", 0),
                    downCount = o.optInt("f105", 0),
                    leaderName = o.optString("f128", ""),
                    leaderCode = o.optString("f140", ""),
                    leaderMarket = o.optInt("f141", 0),
                    leaderPct = o.optDouble("f136", Double.NaN)
                ))
            }
            page++
        }
        return result.takeIf { it.isNotEmpty() }
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
    // 同期成交额是随钟点增长的累计值，取不到就宁可不显示对比，沿用窗口只给 10 分钟
    private val prevTurnoverCache = StaleCache<Pair<Double, String>>(BREADTH_FRESH_TTL, 10 * 60_000L)
    private val capTierCache  = StaleCache<Triple<Double, Double, Double>>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val flowCache     = StaleCache<Double>(BREADTH_FRESH_TTL, BREADTH_STALE_TTL)
    private val sectorCache   = StaleCache<Pair<List<SectorInfo>, List<SectorInfo>>>(2 * 60_000L, 5 * 60_000L)
    // 涨停生态池（连板/炸板）：盘前拿到即 T-1 定型数据；新鲜 2 分钟仅防面板多次刷新重复请求
    private val limitEcoCache = StaleCache<LimitEcosystem?>(2 * 60_000L, BREADTH_STALE_TTL)

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
        val prevTurn = prevTurnoverCache.getOrFetch { fetchPrevDaySameTimeTurnover() }
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
            prevTurnoverSameTime = prevTurn?.first,   // 不随快照持久化：累计口径与钟点相关，旧值只会误导
            prevTurnoverDate = prevTurn?.second,
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
    // 预测机制数据源（涨停生态 / 指数bar日期 / USDCNH / 指数快照）
    // ══════════════════════════════════════════════════════════════

    /**
     * 涨停生态明细（涨停/跌停/炸板家数与连板梯队）。
     * 池接口实测历史 date 参数不可靠（恒返回最近一日），语义直接定义为
     * "最近一个有涨停数据的交易日"：盘前当日池未生成自动落到 T-1，周末/节假日 tc=0 继续前移。
     */
    fun getLimitEcosystem(): LimitEcosystem? = limitEcoCache.getOrFetch { fetchLimitEcosystem() }

    private fun fetchLimitEcosystem(): LimitEcosystem? {
        val shZone  = ZoneId.of("Asia/Shanghai")
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        // 返回 (tc 总数, pool 明细数组)；接口偶发断连返回 null 由上层按 0 处理
        fun fetchData(url: String, label: String): Pair<Int, JSONArray?> {
            val raw = HttpUtil.get(url, label = label) ?: return 0 to null
            return try {
                val data = JSONObject(raw).optJSONObject("data") ?: return 0 to null
                data.optInt("tc") to data.optJSONArray("pool")
            } catch (_: Exception) { 0 to null }
        }
        for (daysBack in 0..4) {
            val date = ZonedDateTime.now(shZone).toLocalDate().minusDays(daysBack.toLong()).format(dateFmt)
            val (ztTc, ztPool) = fetchData(
                "https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989" +
                "&dpt=wz.ztzt&Pageindex=0&pagesize=320&sort=lbc:desc&date=$date", "涨停池")
            // 今日池未生成（盘前/非交易日）则回退更早日期；连板数与涨跌停家数天然同源同池，取不到生态则整体放弃
            if (ztTc <= 0) continue
            val (zbTc, _) = fetchData(
                "https://push2ex.eastmoney.com/getTopicZBPool?ut=7eea3edcaed734bea9cbfc24409ed989" +
                "&dpt=wz.ztzt&Pageindex=0&pagesize=320&sort=fbt:asc&date=$date", "炸板池")
            val (dtTc, _) = fetchData(
                "https://push2ex.eastmoney.com/getTopicDTPool?ut=7eea3edcaed734bea9cbfc24409ed989" +
                "&dpt=wz.ztzt&Pageindex=0&pagesize=320&sort=fund:asc&date=$date", "跌停池")
            var maxLbc = 0
            ztPool?.let { pool ->
                for (i in 0 until pool.length())
                    maxLbc = maxOf(maxLbc, pool.optJSONObject(i)?.optInt("lbc") ?: 0)
            }
            // 日期标签直接用回溯命中的池自身日期：bar 日期盘中恒为今天，池尚未生成时（实测早盘 10 点前
            // 当日池 tc=0）会把 T-1 池的数据标成今天，造成归属日错位；池日期才是数据的真实归属
            val label = "${date.take(4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
            return LimitEcosystem(date = label, ztCount = ztTc, dtCount = dtTc, zbCount = zbTc, maxLianban = maxLbc)
        }
        return null
    }

    /** 新浪上证指数的数据日期（f[30]，形如 "2026-10-16"），即行情快照实际归属的交易日 */
    fun getAshareLastBarDate(): String? {
        val raw = HttpUtil.getGbk("https://hq.sinajs.cn/list=sh000001",
            "https://finance.sina.com.cn", label = "指数数据日期") ?: return null
        val f = Regex(""""([^"]*)"""").find(raw)?.groupValues?.get(1)?.split(",") ?: return null
        return f.getOrNull(30)?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
    }

    /**
     * 离岸人民币（USDCNH）当日涨跌%：正=人民币贬值（利空）、负=升值（利好）。
     * 新浪 fx_ 接口 f[10] 直接给涨跌%，缺失时用 f[1] 现价与 f[3] 昨收自算。
     */
    fun getUsdcnhPct(): Double? {
        val raw = HttpUtil.getGbk("https://hq.sinajs.cn/list=fx_susdcnh",
            "https://finance.sina.com.cn", label = "USDCNH") ?: return null
        val f = Regex(""""([^"]*)"""").find(raw)?.groupValues?.get(1)?.split(",") ?: return null
        f.getOrNull(10)?.toDoubleOrNull()?.let { return it }
        val price = f.getOrNull(1)?.toDoubleOrNull() ?: return null
        val prev  = f.getOrNull(3)?.toDoubleOrNull() ?: return null
        return if (prev > 0) (price - prev) / prev * 100 else null
    }

    /**
     * 腾讯指数快照 Triple(现价, 昨收, 今开)。盘前研判结算用：
     * 开盘跳空 =(今开−昨收)/昨收，全天 =(现价−昨收)/昨收。
     * 盘前今开为 0（尚未开盘），调用方据此保持未结算——符合预期，不会拿旧数据凑数。
     */
    fun getTencentIndexSnapshot(symbol: String): Triple<Double, Double, Double>? {
        val raw = HttpUtil.getGbk("https://qt.gtimg.cn/q=$symbol", label = "指数快照") ?: return null
        val f = Regex(""""([^"]*)"""").find(raw)?.groupValues?.get(1)?.split("~") ?: return null
        val price = f.getOrNull(3)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val prev  = f.getOrNull(4)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val open  = f.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        return Triple(price, prev, open)
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

    /**
     * 美股板块 ETF 行情：主源新浪 gb_ 批量（单次请求，国内直连），
     * 缺漏的标的再逐个走 Yahoo v8 chart 兜底。
     */
    fun getUsSectorQuotes(): Map<String, SectorQuote> {
        val session = currentUsSession()
        val result  = getUsSectorQuotesViaSina(session).toMutableMap()
        for ((symbol, nameCn) in US_SECTOR_ETFS) {
            if (symbol in result) continue
            try { getUsSectorQuoteViaYahoo(symbol, nameCn, session)?.let { result[symbol] = it } } catch (_: Exception) {}
        }
        return result
    }

    private val NY_ZONE = ZoneId.of("America/New_York")

    /** 今天的纽约时区日期（yyyy-MM-dd） */
    private fun etToday(): String = ZonedDateTime.now(NY_ZONE).toLocalDate().toString()

    /**
     * 新浪美股时段戳（形如 "Oct 16 05:13AM EDT"，EDT/EST 随夏令时切换）→ 纽约时区日期。
     * 解析失败返回 null；跨年场景（1 月初解析到 12 月戳）自动回退年份。
     */
    private fun parseSinaUsStamp(stamp: String): String? {
        val m = Regex("""([A-Z][a-z]{2}) (\d{1,2}) (\d{1,2}):(\d{2})(AM|PM)\s*(?:EDT|EST)""").find(stamp) ?: return null
        val month = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
            .indexOf(m.groupValues[1]).let { if (it < 0) return null else it + 1 }
        val day  = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        val min  = m.groupValues[4].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val hour = m.groupValues[3].toIntOrNull()?.let { h ->
            when {
                m.groupValues[5] == "AM" && h == 12 -> 0
                m.groupValues[5] == "PM" && h != 12 -> h + 12
                else -> h
            }
        } ?: return null
        if (hour !in 0..23) return null
        val nowNy = ZonedDateTime.now(NY_ZONE)
        var date  = java.time.LocalDate.of(nowNy.year, month, day)
        if (date.isAfter(nowNy.toLocalDate().plusDays(2))) date = date.minusYears(1)
        return date.toString()
    }

    /**
     * 新浪 gb_ 批量解析。字段（0 基）：1=最近正式价（盘中实时/盘外为最近收盘），
     * 21=盘前/盘后最新价，24=延伸时段戳，25=最近正式盘戳，26=最近正式价的昨收。
     * 延伸涨跌幅基线用 f[1]（盘前=昨收、盘后=今收），时间戳非当天的陈旧延伸数据直接丢弃。
     */
    private fun getUsSectorQuotesViaSina(session: UsSession): Map<String, SectorQuote> {
        val result = mutableMapOf<String, SectorQuote>()
        val codes = US_SECTOR_ETFS.map { "gb_" + it.first.lowercase() }
        for (chunk in codes.chunked(30)) {
            val raw = HttpUtil.getGbk("http://hq.sinajs.cn/list=${chunk.joinToString(",")}",
                "http://finance.sina.com.cn", label = "美股板块ETF") ?: continue
            for (m in Regex("""var hq_str_gb_([^=]+)="([^"]*)"""").findAll(raw)) {
                val symbol = m.groupValues[1].uppercase()
                val f      = m.groupValues[2].split(",")
                if (f.size < 27) continue
                val price = f[1].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                val prev  = f[26].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
                val nameCn = US_SECTOR_ETFS.firstOrNull { it.first == symbol }?.second ?: symbol
                val lastRegularDate = parseSinaUsStamp(f[25])
                val extDate  = if (f.size > 24) parseSinaUsStamp(f[24]) else null
                val extPrice = f.getOrElse(21) { "" }.toDoubleOrNull()?.takeIf { it > 0 }
                val extPct = if ((session == UsSession.PRE || session == UsSession.POST) &&
                    extPrice != null && extDate == etToday())
                    (extPrice - price) / price * 100 else null
                result[symbol] = SectorQuote(
                    symbol, nameCn, (price - prev) / prev * 100,
                    if (session == UsSession.PRE)  extPct else null,
                    if (session == UsSession.POST) extPct else null,
                    lastRegularDate, extDate
                )
            }
        }
        return result
    }

    /**
     * Yahoo v8 chart 兜底。延伸涨跌幅用 meta.fulldayPrice（含延伸时段最新价）
     * 对比 regularMarketPrice（盘前=昨收、盘后=今收）；休市时无延伸成交，
     * fulldayPrice 与 regularMarketPrice 相同，视为无延伸数据。
     */
    private fun getUsSectorQuoteViaYahoo(symbol: String, nameCn: String, session: UsSession): SectorQuote? {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val raw = HttpUtil.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1m&range=1d&includePrePost=true",
            label = "美股板块ETF(Yahoo兜底)"
        ) ?: return null
        val result0 = JSONObject(raw).optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = result0.optJSONObject("meta") ?: return null

        val prevClose = meta.optDouble("chartPreviousClose", Double.NaN).let {
            if (it.isFinite() && it > 0) it
            else meta.optDouble("regularMarketPreviousClose", Double.NaN).let { c ->
                if (c.isFinite() && c > 0) c else meta.optDouble("previousClose", Double.NaN)
            }
        }.takeIf { it.isFinite() && it > 0 } ?: return null

        val regularPrice = meta.optDouble("regularMarketPrice", Double.NaN)
            .takeIf { it.isFinite() && it > 0 } ?: prevClose
        val regularPct   = (regularPrice - prevClose) / prevClose * 100

        val extPct = if (session == UsSession.PRE || session == UsSession.POST) {
            val fd = meta.optDouble("fulldayPrice", Double.NaN)
            if (fd.isFinite() && fd > 0 && fd != regularPrice) (fd - regularPrice) / regularPrice * 100 else null
        } else null

        val lastRegularDate = meta.optLong("regularMarketTime", 0).takeIf { it > 0 }
            ?.let { Instant.ofEpochSecond(it).atZone(NY_ZONE).toLocalDate().toString() }

        return SectorQuote(symbol, nameCn, regularPct,
            if (session == UsSession.PRE)  extPct else null,
            if (session == UsSession.POST) extPct else null,
            lastRegularDate, null)
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

    // ══════════════════════════════════════════════════════════════
    // 新股日历（东方财富 RPTA_APP_IPOAPPLY，与期货持仓/财报同一 datacenter 通道）
    // 一次返回两类行：申购日历（未上市、申购日在近7天及未来）与新股/次新股表现（上市90天内）
    // ══════════════════════════════════════════════════════════════

    /** 新股信息行（新股面板上方申购表与下方表现表共用） */
    data class IpoInfo(
        val pureCode: String,          // 纯6位代码
        val sinaSymbol: String,        // sh/sz/bj 前缀，可直接走 getStockQuotes / 走势图
        val name: String,
        val applyCode: String,         // 申购代码（多数与代码相同，科创板等可能不同）
        val applyDate: String,         // 申购日 yyyy-MM-dd，空串=未公告
        val listingDate: String?,      // 上市日，null=尚未公告
        val issuePrice: Double?,
        val issuePe: Double?,          // 发行市盈率（已公布用实际，未公布用预测）
        val industryPe: Double?,
        val maxApplyQty: Int?,         // 顶格申购上限（股）
        val raiseFunds: Double?,       // 募资额（亿元）
        val firstDayChangePct: Double?,// 上市首日收盘涨幅 %
        val listed: Boolean,
        val isBeijing: Boolean
    )

    private val ipoListCache = StaleCache<List<IpoInfo>>(10 * 60_000L, 2 * 60 * 60_000L)

    private fun optJsonDouble(o: JSONObject, key: String): Double? {
        if (o.isNull(key)) return null
        val v = o.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    /** SECUCODE 后缀（688287.SH）→ 新浪 symbol；无后缀时按代码首位推断 */
    private fun ipoSinaSymbol(secuCode: String, pure: String): String {
        when (secuCode.substringAfterLast('.', "").uppercase()) {
            "SH" -> return "sh$pure"
            "SZ" -> return "sz$pure"
            "BJ" -> return "bj$pure"
        }
        return when {
            pure.startsWith("6") || pure.startsWith("5") -> "sh$pure"
            pure.startsWith("43") || pure.startsWith("83") ||
                pure.startsWith("87") || pure.startsWith("88") ||
                pure.startsWith("92") -> "bj$pure"
            else -> "sz$pure"
        }
    }

    /** 新股日历；返回 null 表示接口不可用（stale 缓存也过期/无数据），调用方保留旧数据并提示 */
    fun getIpoList(): List<IpoInfo>? = ipoListCache.getOrFetch { fetchIpoList() }

    private fun fetchIpoList(): List<IpoInfo>? {
        return try {
            val enc = URLEncoder.encode("(IS_VALID=\"1\")", "UTF-8")
            val url = "https://datacenter-web.eastmoney.com/api/data/v1/get" +
                "?reportName=RPTA_APP_IPOAPPLY&columns=" +
                "SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,APPLY_CODE,APPLY_DATE,LISTING_DATE," +
                "ISSUE_PRICE,PREDICT_ISSUE_PRICE,AFTER_ISSUE_PE,PREDICT_ISSUE_PE,PREDICT_PE," +
                "INDUSTRY_PE,ONLINE_APPLY_UPPER,DEC_SUMFINA,PREDICT_RAISE_FUNDS,LD_CLOSE_CHANGE,IS_BEIJING" +
                "&filter=$enc&sortColumns=APPLY_DATE&sortTypes=-1&pageNumber=1&pageSize=100&source=WEB&client=WEB"
            val raw = HttpUtil.get(url) ?: return null
            val items = JSONObject(raw).optJSONObject("result")?.optJSONArray("data") ?: return null
            val today = java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))
            val recentFrom = today.minusDays(90).toString()   // 次新股窗口
            val applyFrom  = today.minusDays(7).toString()    // 申购日历回看窗口
            val list = ArrayList<IpoInfo>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val pure = o.optString("SECURITY_CODE", "")
                if (pure.length != 6) continue
                val applyDate = o.optString("APPLY_DATE", "").take(10)
                val listing = o.optString("LISTING_DATE", "").take(10).ifEmpty { null }
                val keep = (listing != null && listing >= recentFrom) ||
                           (listing == null && applyDate.isNotEmpty() && applyDate >= applyFrom)
                if (!keep) continue
                val predictPrice = optJsonDouble(o, "PREDICT_ISSUE_PRICE")?.takeIf { it > 0 }
                list.add(IpoInfo(
                    pureCode = pure,
                    sinaSymbol = ipoSinaSymbol(o.optString("SECUCODE", ""), pure),
                    name = o.optString("SECURITY_NAME_ABBR", pure),
                    applyCode = o.optString("APPLY_CODE", pure).ifEmpty { pure },
                    applyDate = applyDate,
                    listingDate = listing,
                    issuePrice = optJsonDouble(o, "ISSUE_PRICE") ?: predictPrice,
                    issuePe = optJsonDouble(o, "AFTER_ISSUE_PE")
                        ?: optJsonDouble(o, "PREDICT_ISSUE_PE")?.takeIf { it > 0 }
                        ?: optJsonDouble(o, "PREDICT_PE")?.takeIf { it > 0 },
                    industryPe = optJsonDouble(o, "INDUSTRY_PE"),
                    maxApplyQty = optJsonDouble(o, "ONLINE_APPLY_UPPER")?.toInt()?.takeIf { it > 0 },
                    raiseFunds = optJsonDouble(o, "DEC_SUMFINA")?.takeIf { it > 0 }
                        ?: optJsonDouble(o, "PREDICT_RAISE_FUNDS")?.takeIf { it > 0 },
                    firstDayChangePct = optJsonDouble(o, "LD_CLOSE_CHANGE"),
                    listed = listing != null,
                    isBeijing = o.optInt("IS_BEIJING", 0) == 1
                ))
            }
            list.sortedBy { it.applyDate }
        } catch (_: Exception) { null }
    }

    /** 判断搜索结果中的名称是否"看起来有问题"（代码/乱码/空），需要用行情接口纠正 */
    private fun isNameBad(name: String, symbol: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == symbol) return true
        // 纯代码格式：sh/sz/bj/hk + 数字
        if (n.matches(Regex("^(sh|sz|bj|hk)\\d{5,6}$"))) return true
        // 纯数字代码
        if (n.matches(Regex("^\\d{5,6}$"))) return true
        // 包含乱码字符（U+FFFD 替换符、控制字符、或全是问号/特殊符号）
        if (n.any { it == '\uFFFD' || it.code < 0x20 }) return true
        // 去除中英文字母后剩余字符极少（大部分是乱码符号）
        val cjk = n.filter { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF }
        val latin = n.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        val meaningful = cjk.length + latin.length
        if (meaningful < n.length / 2 && n.length > 2) return true
        return false
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
                    symbol.startsWith("sh") || symbol.startsWith("sz") || symbol.startsWith("bj") ->
                        results.add(StockSearchResult(symbol, name))
                    symbol.matches(Regex("\\d{6}")) -> {
                        // 北交所代码段：43/83/87/88（原新三板平移）与 92（新代码）
                        val full = when {
                            symbol.startsWith("6") || symbol.startsWith("5") -> "sh$symbol"
                            symbol.startsWith("43") || symbol.startsWith("83") ||
                                symbol.startsWith("87") || symbol.startsWith("88") ||
                                symbol.startsWith("92") -> "bj$symbol"
                            else -> "sz$symbol"
                        }
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

        // ── 3. 行情名称补全：搜索API有时返回代码而非真实名称，用行情接口纠正 ──
        val enriched = results.distinctBy { it.symbol }.take(15).toMutableList()
        val needsNameFix = enriched.filter { r -> isNameBad(r.name, r.symbol) }
        if (needsNameFix.isNotEmpty()) {
            val quotes = getStockQuotes(needsNameFix.map { it.symbol })
            for (i in enriched.indices) {
                val r = enriched[i]
                val q = quotes[r.symbol]
                if (q != null && q.name.isNotBlank()) {
                    if (isNameBad(r.name, r.symbol)) {
                        enriched[i] = r.copy(name = q.name)
                    }
                }
            }
        }
        return enriched
    }

    // ══════════════════════════════════════════════════════════════
    // 批量导入校验（配合 StockTextParser / BatchImportStockDialog）
    // ══════════════════════════════════════════════════════════════

    /**
     * 批量校验导入候选：
     * - 带代码的条目：走 getStockQuotes 批量确认存在性并取规范名称/现价；
     * - 仅名称的条目：小并发（3 线程）逐个 searchStocks，取完全同名结果；多个不同代码命中 → AMBIGUOUS；
     * - 已在自选（existingSymbols）→ EXISTS；文本内解析后仍重复的 symbol → 只保留第一条，其余标 EXISTS；
     * - 最后统一为已确认条目回填现价（默认成本用）。
     * progress 回调在完成计数变化时触发（已完成数, 总数），由调用方转成 UI 进度。
     */
    fun resolveImportCandidates(
        tokens: List<com.stocklite.plugin.util.ParsedStockToken>,
        existingSymbols: Set<String>,
        progress: ((Int, Int) -> Unit)? = null
    ): List<ImportCandidate> {
        if (tokens.isEmpty()) return emptyList()
        val results = arrayOfNulls<ImportCandidate>(tokens.size)
        val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
        fun bump() { progress?.invoke(doneCounter.incrementAndGet(), tokens.size) }

        // ── 1. 带代码条目：批量行情确认 ──
        val codeTokens = tokens.withIndex().filter { it.value.code != null }
        if (codeTokens.isNotEmpty()) {
            val quotes = getStockQuotes(codeTokens.map { it.value.code!! }.distinct())
            for ((idx, tok) in codeTokens) {
                val sym = tok.code!!
                val q = quotes[sym]
                results[idx] = when {
                    q == null -> ImportCandidate(tok.raw, "", tok.name ?: sym, ImportStatus.NOT_FOUND)
                    existingSymbols.contains(sym) ->
                        ImportCandidate(tok.raw, sym, q.name, ImportStatus.EXISTS, price = q.price)
                    else -> ImportCandidate(
                        tok.raw, sym, q.name, ImportStatus.OK, price = q.price,
                        nameCorrected = !tok.name.isNullOrBlank() && tok.name.trim() != q.name
                    )
                }
                bump()
            }
        }

        // ── 2. 仅名称条目：3 并发搜索完全同名结果 ──
        val nameTokens = tokens.withIndex().filter { it.value.code == null && !it.value.name.isNullOrBlank() }
        if (nameTokens.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                val futures = nameTokens.map { (_, tok) ->
                    pool.submit<ImportCandidate> {
                        val cand = resolveImportByName(tok, existingSymbols)
                        bump()
                        cand
                    }
                }
                futures.forEachIndexed { i, f ->
                    results[nameTokens[i].index] = try { f.get() } catch (_: Exception) {
                        ImportCandidate(nameTokens[i].value.raw, "", nameTokens[i].value.name ?: "", ImportStatus.NOT_FOUND)
                    }
                }
            } finally { pool.shutdown() }
        }

        // ── 3. 解析后 symbol 仍重复的条目：保留第一条，其余标 EXISTS ──
        val seenSymbols = mutableSetOf<String>()
        for (i in results.indices) {
            val c = results[i] ?: continue
            if (c.status == ImportStatus.OK || c.status == ImportStatus.AMBIGUOUS) {
                if (!seenSymbols.add(c.symbol)) {
                    results[i] = c.copy(status = ImportStatus.EXISTS)
                }
            }
        }

        // ── 4. 已确认但缺现价的条目（名称搜索路径）统一补价 ──
        val needPrice = results.filterNotNull().filter {
            (it.status == ImportStatus.OK || it.status == ImportStatus.AMBIGUOUS) && it.price <= 0
        }
        if (needPrice.isNotEmpty()) {
            val quotes = getStockQuotes(needPrice.map { it.symbol }.distinct())
            for (i in results.indices) {
                val c = results[i] ?: continue
                val p = quotes[c.symbol]?.price
                if (p != null && p > 0) results[i] = c.copy(price = p)
            }
        }

        return results.mapNotNull { it }
    }

    /** 仅名称条目 → 搜索结果中完全同名的第一条；多个不同代码同名 → AMBIGUOUS */
    private fun resolveImportByName(
        tok: com.stocklite.plugin.util.ParsedStockToken,
        existingSymbols: Set<String>
    ): ImportCandidate {
        val name = tok.name!!.trim()
        val results = try { searchStocks(name) } catch (_: Exception) { emptyList() }
        val exact = results.filter { it.name.trim() == name }
        val pick = exact.firstOrNull()
            ?: return ImportCandidate(tok.raw, "", name, ImportStatus.NOT_FOUND)
        val distinctSymbols = exact.map { it.symbol }.distinct()
        val status = when {
            existingSymbols.contains(pick.symbol) -> ImportStatus.EXISTS
            distinctSymbols.size > 1 -> ImportStatus.AMBIGUOUS
            else -> ImportStatus.OK
        }
        return ImportCandidate(tok.raw, pick.symbol, pick.name, status, alternates = distinctSymbols.drop(1))
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

    // ══════════════════════════════════════════════════════════════
    // 加密货币行情（免费公开 REST 接口，无需 key）
    // 主源 Bitget → 备源 Gate.io → 兜底 Binance 公开数据镜像（data-api.binance.vision）。
    // 三源实测在港线网络可直连，但可达性因用户网络环境而异：全部失败时返回 OFFLINE，
    // 由界面明确提示"当前网络无法连接加密行情接口"，不得静默显示空数据。
    // ══════════════════════════════════════════════════════════════

    enum class CryptoNetStatus { CONNECTED, DEGRADED, OFFLINE }

    /**
     * 一轮批量拉取的结果。
     * @param status CONNECTED=全部走主源；DEGRADED=至少一个币降级到备源拉取；OFFLINE=三源全部不可达
     * @param workingSource 最后一次成功响应的源名（OFFLINE 时为 null）
     */
    data class CryptoQuoteBatch(
        val quotes: Map<String, CryptoQuote>,
        val status: CryptoNetStatus,
        val workingSource: String?
    )

    /** 用户输入 → Gate 风格交易对："btc/usdt"/"BTCUSDT"/"btc" → "BTC_USDT" */
    fun normCryptoSymbol(raw: String): String {
        val s = raw.trim().uppercase().replace("/", "_").replace("-", "_")
        if (s.isEmpty()) return ""
        if (s.contains("_")) return s
        val quotes = listOf("USDT", "USDC", "FDUSD", "TUSD", "USD", "BTC", "ETH")
        for (q in quotes) if (s.length > q.length && s.endsWith(q)) return "${s.dropLast(q.length)}_$q"
        return "${s}_USDT"
    }

    private fun cryptoNum(o: JSONObject, key: String): Double =
        o.optString(key).toDoubleOrNull() ?: Double.NaN

    private fun fetchCryptoBitget(pair: String): CryptoQuote? {
        val sym = pair.replace("_", "")
        val raw = HttpUtil.get("https://api.bitget.com/api/v2/spot/market/tickers?symbol=$sym",
            label = "加密/Bitget") ?: return null
        return try {
            val arr = JSONObject(raw).optJSONArray("data") ?: return null
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            val price = cryptoNum(o, "lastPr"); val open = cryptoNum(o, "open")
            if (!price.isFinite() || price <= 0) return null
            val pct = if (open.isFinite() && open > 0) (price / open - 1) * 100 else 0.0
            CryptoQuote(pair, price, pct, cryptoNum(o, "high24h"), cryptoNum(o, "low24h"),
                cryptoNum(o, "quoteVolume"))
        } catch (_: Exception) { null }
    }

    private fun fetchCryptoGate(pair: String): CryptoQuote? {
        val raw = HttpUtil.get("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=$pair",
            label = "加密/Gate.io") ?: return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() == 0) return null
            val o = arr.getJSONObject(0)
            val price = cryptoNum(o, "last")
            if (!price.isFinite() || price <= 0) return null
            CryptoQuote(pair, price, cryptoNum(o, "change_percentage"), cryptoNum(o, "high_24h"),
                cryptoNum(o, "low_24h"), cryptoNum(o, "quote_volume"))
        } catch (_: Exception) { null }
    }

    private fun fetchCryptoBinance(pair: String): CryptoQuote? {
        val sym = pair.replace("_", "")
        val raw = HttpUtil.get("https://data-api.binance.vision/api/v3/ticker/24hr?symbol=$sym",
            label = "加密/Binance镜像") ?: return null
        return try {
            val o = JSONObject(raw)
            val price = cryptoNum(o, "lastPrice")
            if (!price.isFinite() || price <= 0) return null
            CryptoQuote(pair, price, cryptoNum(o, "priceChangePercent"), cryptoNum(o, "highPrice"),
                cryptoNum(o, "lowPrice"), cryptoNum(o, "quoteVolume"))
        } catch (_: Exception) { null }
    }

    /** 逐币拉取：主源失败自动降级，单币三源全失败仅该行缺数据；全缺 → OFFLINE */
    fun getCryptoQuotes(symbols: List<String>): CryptoQuoteBatch {
        val quotes = LinkedHashMap<String, CryptoQuote>()
        var usedFallback = false
        var lastWorkingSource: String? = null
        for (raw in symbols) {
            val pair = normCryptoSymbol(raw); if (pair.isEmpty()) continue
            val b = fetchCryptoBitget(pair)
            if (b != null) { quotes[pair] = b; lastWorkingSource = "Bitget"; continue }
            val g = fetchCryptoGate(pair)
            if (g != null) { quotes[pair] = g; usedFallback = true; lastWorkingSource = "Gate.io"; continue }
            val v = fetchCryptoBinance(pair)
            if (v != null) { quotes[pair] = v; usedFallback = true; lastWorkingSource = "Binance镜像" }
        }
        val status = when {
            quotes.isEmpty() -> CryptoNetStatus.OFFLINE
            usedFallback     -> CryptoNetStatus.DEGRADED
            else             -> CryptoNetStatus.CONNECTED
        }
        return CryptoQuoteBatch(quotes, status, lastWorkingSource)
    }

    /** 空自选时的连通性探测（也用作首次进入面板的即时反馈） */
    fun probeCryptoNetwork(): CryptoQuoteBatch = getCryptoQuotes(listOf("BTC_USDT"))

    // ── 币种搜索：Gate.io 全量交易对表（2200+ 对）本地缓存 24h，避免每次搜索打网络 ──

    private data class CryptoPairInfo(val symbol: String, val base: String, val quote: String, val quoteVolume: Double)

    @Volatile private var cryptoPairCache: List<CryptoPairInfo>? = null
    @Volatile private var cryptoPairCacheAt = 0L
    private val CRYPTO_PAIR_TTL = 24 * 60 * 60_000L

    private fun loadCryptoPairs(): List<CryptoPairInfo>? {
        cryptoPairCache?.let {
            if (System.currentTimeMillis() - cryptoPairCacheAt < CRYPTO_PAIR_TTL) return it
        }
        val raw = HttpUtil.get("https://api.gateio.ws/api/v4/spot/currency_pairs", label = "加密/交易对表")
            ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<CryptoPairInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("disable") == "true") continue
                val id = o.optString("id")
                if (!id.contains("_")) continue
                list.add(CryptoPairInfo(id,
                    o.optString("base").uppercase(), o.optString("quote").uppercase(),
                    o.optString("quote_volume").toDoubleOrNull() ?: 0.0))
            }
            if (list.isEmpty()) null
            else { cryptoPairCache = list; cryptoPairCacheAt = System.currentTimeMillis(); list }
        } catch (_: Exception) { null }
    }

    /** 搜索网络全部不可达时的常见币兜底表（仅 USDT 交易对）：代码 → 英文名 */
    private val CRYPTO_FALLBACK = listOf(
        "BTC" to "Bitcoin", "ETH" to "Ethereum", "SOL" to "Solana", "BNB" to "BNB", "XRP" to "XRP",
        "DOGE" to "Dogecoin", "ADA" to "Cardano", "TON" to "Toncoin", "TRX" to "TRON", "AVAX" to "Avalanche",
        "DOT" to "Polkadot", "LINK" to "Chainlink", "MATIC" to "Polygon", "SHIB" to "Shiba Inu",
        "LTC" to "Litecoin", "BCH" to "Bitcoin Cash", "UNI" to "Uniswap", "NEAR" to "NEAR",
        "APT" to "Aptos", "ETC" to "Ethereum Classic", "XLM" to "Stellar", "SUI" to "Sui",
        "PEPE" to "Pepe", "ATOM" to "Cosmos", "FIL" to "Filecoin", "ARB" to "Arbitrum",
        "OP" to "Optimism", "INJ" to "Injective", "AAVE" to "Aave", "DOGS" to "Dogs"
    )

    /**
     * 按币代码/交易对搜索。命中顺序：交易对精确 → 基础币 → 前缀；
     * 同币多交易对时 USDT 计价优先、再按 24h 成交额降序。
     * 交易对表拉不到时退到本地兜底表（返回空列表也可能意味着网络不可达，由调用方提示）。
     */
    fun searchCryptos(keyword: String): List<CryptoSearchResult> {
        val kw = keyword.trim().uppercase().replace("/", "_").replace("-", "_")
        if (kw.isEmpty()) return emptyList()
        val baseKw = kw.substringBefore("_")
        loadCryptoPairs()?.let { pairs ->
            val hits = pairs.filter { it.symbol == kw || it.base == baseKw || it.symbol.startsWith("${baseKw}_") }
            return hits.sortedWith(
                compareByDescending<CryptoPairInfo> { it.symbol == kw }
                    .thenByDescending { it.quote == "USDT" }
                    .thenByDescending { it.quoteVolume }
            ).distinctBy { it.symbol }.take(20)
                .map { CryptoSearchResult(it.symbol, "${it.base}/${it.quote}") }
        }
        return CRYPTO_FALLBACK
            .filter { it.first == baseKw || it.first.startsWith(baseKw) }
            .map { CryptoSearchResult("${it.first}_USDT", "${it.first}/USDT · ${it.second}") }
            .take(20)
    }
}
