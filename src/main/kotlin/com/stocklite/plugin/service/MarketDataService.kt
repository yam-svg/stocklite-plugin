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
        GlobalIndexConfig("^N225",    "N225",   "Nikkei 225",      "日经225",    "JP", "Asia/Tokyo"),
        GlobalIndexConfig("^KS11",    "KOSPI",  "KOSPI",           "韩国综合指数","KR", "Asia/Seoul"),
        GlobalIndexConfig("^HSI",     "HSI",    "Hang Seng",       "恒生指数",   "HK", "Asia/Hong_Kong"),
        GlobalIndexConfig("^HSTECH",  "HSTECH", "Hang Seng TECH",  "恒生科技指数","HK","Asia/Hong_Kong"),
        GlobalIndexConfig("000001.SS","SSE",    "SSE Composite",   "上证指数",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("399001.SZ","SZSE",   "SZSE Component",  "深证成指",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("399006.SZ","CYB",    "ChiNext",         "创业板指",   "CN", "Asia/Shanghai"),
        GlobalIndexConfig("000300.SS","CSI300", "CSI 300",         "沪深300",    "CN", "Asia/Shanghai"),
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
            else -> Triple(9*60,    16*60, null)
        }
    }

    fun isMarketOpenByTimezone(market: String, timezone: String): Boolean {
        val now = ZonedDateTime.now(ZoneId.of(timezone))
        val dow = now.dayOfWeek.value  // 1=Mon..7=Sun
        if (dow == 6 || dow == 7) return false
        val current = now.hour * 60 + now.minute
        val (start, end, lunch) = getSessionMinutes(market)
        if (current < start || current > end) return false
        if (lunch != null && current >= lunch.first && current <= lunch.second) return false
        return true
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
    private val detectedQdiiCodes = mutableSetOf<String>()

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
    private val marketStateCache       = mutableMapOf<String, Pair<Boolean, Long>>()
    private val GLOBAL_CACHE_TTL       = 60_000L
    private val MARKET_STATE_CACHE_TTL = 60_000L  // 开/休状态缓存 1 分钟

    /** 全球指数行情结果，附带限流标志供面板自适应退避 */
    data class GlobalQuoteResult(
        val quotes: List<GlobalIndexQuote>,
        val rateLimited: Boolean = false
    )

    fun getGlobalIndexQuotes(): GlobalQuoteResult {
        val quoteMap = mutableMapOf<String, Pair<Double, Double>>()
        val now = System.currentTimeMillis()
        var yahooRateLimited = false

        // 0. 优先用 Yahoo 批量接口获取所有指数的市场状态（含节假日判断）
        val marketStates = fetchYahooMarketStates(now)

        // 1. 腾讯港股实时（HK 指数）
        for (sym in listOf("^HSI", "^HSTECH")) {
            fetchTencentHkQuote(sym)?.let { (price, pct, _) ->
                SINA_SYMBOL_MAP[sym]?.let { quoteMap[it] = price to pct }
            }
        }

        // 2. 新浪批量（A 股 / 美股粗行情）
        val sinaSymbols = SINA_SYMBOL_MAP.values.joinToString(",")
        HttpUtil.getGbk("http://hq.sinajs.cn/list=$sinaSymbols", "https://finance.sina.com.cn")
            ?.let { text ->
                parseSinaData(text).forEach { (k, v) ->
                    if (!quoteMap.containsKey(k)) quoteMap[k] = v
                }
            }

        // 3. Yahoo 兜底（强制 Yahoo 的指数 + 新浪无数据的）
        for (item in GLOBAL_INDEXES) {
            val sinaKey = SINA_SYMBOL_MAP[item.symbol]
            if (!GLOBAL_FORCE_YAHOO.contains(item.symbol) && sinaKey != null && quoteMap.containsKey(sinaKey)) continue

            val (statusCode, body) = fetchYahooQuoteWithStatus(item.symbol, now)
            when {
                statusCode == 429 -> { yahooRateLimited = true }
                body != null -> {
                    if (sinaKey != null) quoteMap[sinaKey] = body
                    quoteMap[item.symbol] = body
                }
            }
        }

        val quotes = GLOBAL_INDEXES.map { item ->
            val sinaKey = SINA_SYMBOL_MAP[item.symbol]
            val (price, pct) = (sinaKey?.let { quoteMap[it] } ?: quoteMap[item.symbol]) ?: (0.0 to 0.0)
            // 优先使用 Yahoo marketState（含节假日），其次回退时区计算
            val isOpen = marketStates[item.symbol]
                ?: isMarketOpenByTimezone(item.market, item.timezone)
            GlobalIndexQuote(
                symbol = item.symbol, name = "${item.nameCn} (${item.nameEn})",
                value = price, changePercent = pct,
                isOpen = isOpen,
                market = item.market
            )
        }
        return GlobalQuoteResult(quotes, yahooRateLimited)
    }

    /**
     * 通过 Yahoo Finance v7/quote 批量接口获取所有指数的市场状态（单次请求）。
     * - marketState = "REGULAR" → 交易中（isOpen = true）
     * - marketState = "PRE"/"POST"/"CLOSED" → 休市/节假日（isOpen = false）
     * 结果缓存 1 分钟，失败时返回缓存值，缓存也无时回退到 isMarketOpenByTimezone。
     */
    private fun fetchYahooMarketStates(now: Long): Map<String, Boolean> {
        // 全部命中缓存则直接返回
        val cached = GLOBAL_INDEXES.mapNotNull { item ->
            val c = marketStateCache[item.symbol]
            if (c != null && now - c.second < MARKET_STATE_CACHE_TTL) item.symbol to c.first else null
        }.toMap()
        if (cached.size == GLOBAL_INDEXES.size) return cached

        // Yahoo symbol 到我们 symbol 的反向映射
        val yahooToOurs = GLOBAL_INDEXES.associate { resolveYahooSymbol(it.symbol) to it.symbol }
        val symbolList = yahooToOurs.keys.joinToString(",")
        val url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$symbolList"
        val raw = HttpUtil.get(url) ?: return cached  // 网络失败 → 返回现有缓存

        return try {
            val results = JSONObject(raw)
                .getJSONObject("quoteResponse")
                .optJSONArray("result") ?: return cached
            val result = cached.toMutableMap()
            for (i in 0 until results.length()) {
                val obj      = results.getJSONObject(i)
                val yahooSym = obj.optString("symbol")
                val ourSym   = yahooToOurs[yahooSym] ?: continue
                val state    = obj.optString("marketState", "CLOSED")
                val isOpen   = state == "REGULAR"
                result[ourSym] = isOpen
                marketStateCache[ourSym] = isOpen to now
            }
            result
        } catch (_: Exception) { cached }
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

            // 前收
            var prevClose = extractPrevCloseFromIntraday(result)
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

    /** 移植 extractPreviousCloseFromIntraday */
    private fun extractPrevCloseFromIntraday(result: JSONObject): Double {
        val gmtOffset = result.getJSONObject("meta").optLong("gmtoffset", 0)
        val timestamps = result.optJSONArray("timestamp") ?: return Double.NaN
        val closes = result.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).getJSONArray("close")

        data class DayClose(val day: String, val close: Double)
        val dayMap = linkedMapOf<String, Double>()

        for (i in 0 until minOf(timestamps.length(), closes.length())) {
            val ts    = timestamps.optLong(i, -1).takeIf { it > 0 } ?: continue
            val close = closes.optDouble(i).takeIf { it.isFinite() && it > 0 } ?: continue
            val exchMs = (ts + gmtOffset) * 1000
            val day = java.time.Instant.ofEpochMilli(exchMs)
                .atZone(ZoneId.of("UTC")).toLocalDate().toString()
            dayMap[day] = close
        }

        val days = dayMap.keys.toList()
        if (days.size < 2) return Double.NaN
        return dayMap[days[days.size - 2]] ?: Double.NaN
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
