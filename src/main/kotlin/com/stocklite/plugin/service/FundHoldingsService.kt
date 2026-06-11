package com.stocklite.plugin.service

import com.stocklite.plugin.state.FundHoldingItem
import com.stocklite.plugin.state.FundHoldingsResult
import com.stocklite.plugin.util.HttpUtil

/**
 * 基金持仓查询服务。
 *
 * 数据来源：东方财富 F10 接口（fundf10.eastmoney.com）。
 * 持仓数据根据季报/年报公布，通常延迟 1~3 个月。
 *
 * 支持类型：普通股票型、混合型、指数型、QDII、ETF。
 * 债券/货币类基金如无股票持仓，返回空列表并附说明。
 */
object FundHoldingsService {

    private const val REFERER = "https://fundf10.eastmoney.com/"

    /** 最多展示的持仓数量（默认取前 20 名）*/
    private const val TOP_LINE = 20

    // ── 缓存（持仓数据更新极慢，缓存 30 分钟）───────────────────────────
    private val cache = mutableMapOf<String, Pair<FundHoldingsResult, Long>>()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /**
     * 获取基金最新持仓明细。
     * 先走内存缓存，缓存未命中或过期则重新拉取。
     *
     * @param code 基金代码（6 位数字）
     * @return FundHoldingsResult，失败时 error 字段非空
     */
    fun fetchHoldings(code: String): FundHoldingsResult {
        val now = System.currentTimeMillis()
        cache[code]?.let { (cached, ts) ->
            if (now - ts < CACHE_TTL_MS && cached.error == null) return cached
        }

        val result = doFetch(code)
        if (result.error == null) cache[code] = result to now
        return result
    }

    // ── 私有 ─────────────────────────────────────────────────────────────

    private fun doFetch(code: String): FundHoldingsResult {
        // rt 参数避免服务端缓存
        val rt = (now() % 100000) / 100000.0
        val url = "https://fundf10.eastmoney.com/FundArchivesDatas.aspx" +
                "?type=jjcc&code=$code&topline=$TOP_LINE&year=&month=&rt=$rt"

        val raw = HttpUtil.get(url, REFERER)
            ?: return FundHoldingsResult("", "", emptyList(), 0, "网络请求失败，请检查网络连接。")

        return parseResponse(raw)
    }

    /**
     * 解析东方财富返回的 JavaScript 格式：
     * ```
     * var apidata={ content:"<table>...</table>", arryear:[2026,2025,...], curyear:2026 };
     * ```
     * 注意：实际响应中 **没有** count 字段，也 **没有** summary 字段；
     * content 字段紧跟着的是 arryear 数组，不是 summary。
     */
    private fun parseResponse(raw: String): FundHoldingsResult {
        // ── 提取 content HTML（逐字符解析，正确处理 \" 转义）──
        val contentHtml = extractJsonStringField(raw, "content")
            ?: return FundHoldingsResult("", "", emptyList(), 0,
                "暂无持仓数据。该基金可能为债券型/货币型，或尚未公布季报。")

        if (contentHtml.isBlank() || !contentHtml.contains("<tr")) {
            return FundHoldingsResult("", "", emptyList(), 0,
                "暂无股票持仓数据。该基金可能为纯债券型或货币市场基金。")
        }

        // ── 提取报告期（从 content HTML 内的 caption 或日期文本）──
        val reportDate = Regex("""(\d{4}-\d{2}-\d{2})""").find(contentHtml)
            ?.groupValues?.get(1) ?: ""

        // ── 解析 HTML 表格 ──
        val (date, items) = parseHtmlTable(contentHtml, reportDate)
        return FundHoldingsResult(
            reportDate  = date,
            reportLabel = "",          // 新接口无 summary 字段，留空
            items       = items,
            totalCount  = items.size   // count 字段不存在，以实际解析数量为准
        )
    }

    /**
     * 从 `var apidata={...}` 响应中提取指定字段的字符串值。
     * 正确处理 `\"` 转义、`\/` 转义、`\uXXXX` Unicode 转义，
     * 不依赖后续字段名（对 summary/count 缺失均有容错）。
     *
     * 查找模式：`"field":"`（忽略冒号前后空格），然后逐字符解析到非转义 `"` 为止。
     */
    private fun extractJsonStringField(raw: String, field: String): String? {
        // 用 regex 定位 field 键名后的开头引号，忽略冒号两侧可能的空格
        // 注意：东方财富 API 返回的是 JS 对象字面量，属性名无引号：content:"..."
        val keyRegex = Regex("$field\\s*:\\s*\"")
        val keyMatch = keyRegex.find(raw) ?: return null
        var i = keyMatch.range.last + 1   // 指向 value 字符串第一个字符

        val sb = StringBuilder()
        while (i < raw.length) {
            when {
                raw[i] == '\\' && i + 1 < raw.length -> {
                    when (raw[i + 1]) {
                        '"'  -> { sb.append('"');  i += 2 }
                        '/'  -> { sb.append('/');  i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'n'  -> { sb.append('\n'); i += 2 }
                        'r'  -> { sb.append('\r'); i += 2 }
                        't'  -> { sb.append('\t'); i += 2 }
                        'u'  -> if (i + 5 <= raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 6
                        } else { sb.append('\\'); i++ }
                        else -> { sb.append(raw[i + 1]); i += 2 }
                    }
                }
                raw[i] == '"' -> break   // 非转义引号 → 字符串结束
                else -> { sb.append(raw[i]); i++ }
            }
        }
        return sb.toString()
    }

    /** 解析 HTML 表格，返回 (reportDate, items) */
    private fun parseHtmlTable(html: String, fallbackDate: String): Pair<String, List<FundHoldingItem>> {
        val stripHtml = { s: String ->
            s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
        }

        // ── 解析表头，确定列索引 ──
        val theadMatch = Regex("""<thead>(.*?)</thead>""", RegexOption.DOT_MATCHES_ALL).find(html)
        val headers = if (theadMatch != null) {
            Regex("""<th[^>]*>(.*?)</th>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(theadMatch.groupValues[1])
                .map { stripHtml(it.groupValues[1]) }
                .toList()
        } else emptyList()

        fun colIdx(vararg kws: String): Int =
            headers.indexOfFirst { h -> kws.any { k -> h.contains(k, ignoreCase = true) } }

        val idxRank    = colIdx("序号").let { if (it < 0) 0 else it }
        val idxCode    = colIdx("代码").let { if (it < 0) 1 else it }
        val idxName    = colIdx("名称").let { if (it < 0) 2 else it }
        val idxNavPct  = colIdx("净值比例", "占净值", "比例")
        val idxShares  = colIdx("持仓股数", "万股", "数量")
        val idxValue   = colIdx("持仓市值", "万元", "市值")
        val idxChange  = colIdx("上期", "变化", "增减")

        // ── 解析报告期（caption 或正文中的日期）──
        val captionDate = Regex("""<caption[^>]*>(.*?)</caption>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { stripHtml(it) }
        val reportDate = Regex("""(\d{4}-\d{2}-\d{2})""")
            .find(captionDate ?: html)?.groupValues?.get(1) ?: fallbackDate

        // ── 解析 tbody 行 ──
        val tbodyMatch = Regex("""<tbody>(.*?)</tbody>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return reportDate to emptyList()

        val items = mutableListOf<FundHoldingItem>()
        val rowRegex  = Regex("""<tr[^>]*>(.*?)</tr>""",  RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

        for (row in rowRegex.findAll(tbodyMatch.groupValues[1])) {
            val cells = cellRegex.findAll(row.groupValues[1])
                .map { stripHtml(it.groupValues[1]) }
                .toList()
            if (cells.size < 3) continue

            fun cell(idx: Int) = if (idx in cells.indices) cells[idx] else ""

            val navPctStr = cell(idxNavPct).replace("%", "").trim()
            val navPct    = navPctStr.toDoubleOrNull()
                ?: continue  // 跳过没有有效净值比例的行（如合计行）

            items += FundHoldingItem(
                rank       = cell(idxRank).toIntOrNull() ?: (items.size + 1),
                stockCode  = cell(idxCode),
                stockName  = cell(idxName),
                navPercent = navPct,
                holdShares = cell(idxShares),
                holdValue  = cell(idxValue),
                change     = cell(idxChange)
            )
        }

        return reportDate to items
    }

    private fun now() = System.currentTimeMillis()
}
