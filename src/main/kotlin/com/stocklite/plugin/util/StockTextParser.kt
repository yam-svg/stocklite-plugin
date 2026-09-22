package com.stocklite.plugin.util

/**
 * 批量导入的纯词法解析：把一大段粘贴文本拆成「代码 / 名称 / 代码+名称」候选条目。
 * 不发起任何网络请求；代码/名称是否真实存在由 MarketDataService.resolveImportCandidates 校验。
 *
 * 解析规则（按用户确认的方案）：
 * - 文本先按行切分，行内再按 ，、; ；/ | 等分隔符切成段；空格不作为段分隔符，
 *   以便「贵州茅台 600519」这类"名称+代码"留在同一段内配对。
 * - 段内先用正则提取代码（按 带前缀 → 后缀式(.SH) → 裸6位 → 裸5位 → 全大写美股ticker 的顺序，
 *   已提取的部分从文本中移除，避免重复匹配），再提取连续中文片段作为名称候选。
 * - 段内代码与名称按出现顺序配对；多出的名称变成"仅名称"条目，多出的代码变成"仅代码"条目。
 * - 全大写才视为美股 ticker（如 AAPL、BRK.B），避免把普通英文备注当代码。
 * - 相同代码或相同名称的条目只保留第一次出现（文本内部去重）。
 */

/** 一条解析结果：code 与 name 至少有一个非空；code 为补全前缀后的完整代码 */
data class ParsedStockToken(
    val raw: String,
    val code: String?,
    val name: String?
)

object StockTextParser {

    /** 单次导入条目上限，防止误粘贴超大文本导致海量网络校验 */
    const val MAX_ENTRIES = 300

    // 带前缀：sh600519 / SZ 000001 / HK00700 / bj920002
    private val RE_PREFIXED = Regex("""\b(sh|sz|bj|hk)\s?(\d{4,6})\b""", RegexOption.IGNORE_CASE)
    // 后缀式：600519.SH / hk00700（少见但常见于导出文本：00700.HK）
    private val RE_SUFFIX   = Regex("""(?<![\d.])(\d{5,6})\.(sh|sz|bj|hk)\b""", RegexOption.IGNORE_CASE)
    // 裸代码：6 位 A 股、5 位港股；(?<![\d.]) 防止命中金额/日期里的一串数字
    private val RE_BARE6    = Regex("""(?<![\d.])(\d{6})(?![\d.])""")
    private val RE_BARE5    = Regex("""(?<![\d.])(\d{5})(?![\d.])""")
    // 美股 ticker：全大写字母开头，可带一个 .X 后缀（BRK.B）；含小写的单词视为普通文本
    private val RE_US       = Regex("""(?<![A-Za-z0-9])([A-Z][A-Z0-9]{0,5}(?:\.[A-Z])?)(?![A-Za-z0-9])""")
    // 中文名候选：连续 2 个及以上 CJK 字符
    private val RE_CJK      = Regex("""[\u4e00-\u9fa5]{2,}""")

    /** 行内段分隔符（不含空格） */
    private val SEGMENT_SPLIT = Regex("""[，,、;；/|]+""")

    fun parse(text: String): List<ParsedStockToken> {
        val result = LinkedHashMap<String, ParsedStockToken>()  // key 去重，保持输入顺序
        text.lineSequence().forEach { line ->
            line.split(SEGMENT_SPLIT).forEach { seg ->
                parseSegment(seg).forEach { token ->
                    if (result.size >= MAX_ENTRIES) return@forEach
                    val key = token.code?.lowercase() ?: "name:" + (token.name ?: "")
                    if (!result.containsKey(key)) result[key] = token
                }
            }
        }
        return result.values.toList()
    }

    private fun parseSegment(segment: String): List<ParsedStockToken> {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. 按优先级提取代码，并从工作文本中移除，避免后续正则重复命中
        var work = trimmed
        val codes = mutableListOf<String>()
        val suffixMatches = RE_SUFFIX.findAll(work).map { Triple(it.range, it.groupValues[1], it.groupValues[2].lowercase()) }.toList()
        suffixMatches.asReversed().forEach { (range, digits, prefix) ->
            normalizeCode(prefix, digits)?.let { codes.add(it) }
            work = work.removeRange(range)
        }
        val prefixedMatches = RE_PREFIXED.findAll(work).map { Triple(it.range, it.groupValues[1].lowercase(), it.groupValues[2]) }.toList()
        prefixedMatches.asReversed().forEach { (range, prefix, digits) ->
            normalizeCode(prefix, digits)?.let { codes.add(it) }
            work = work.removeRange(range)
        }
        RE_BARE6.findAll(work).map { it.value }.toList().forEach { digits ->
            normalizeCode(null, digits)?.let { codes.add(it) }
        }
        work = RE_BARE6.replace(work, " ")
        RE_BARE5.findAll(work).map { it.value }.toList().forEach { digits ->
            normalizeCode(null, digits)?.let { codes.add(it) }
        }
        work = RE_BARE5.replace(work, " ")
        RE_US.findAll(work).map { it.value }.toList().forEach { codes.add(it) }

        // 2. 名称候选：原始段内的连续中文片段
        val names = RE_CJK.findAll(trimmed).map { it.value }.toList()

        // 3. 配对：代码与名称按出现顺序一一配对，多余部分各自独立成条
        val tokens = mutableListOf<ParsedStockToken>()
        val pairCount = minOf(codes.size, names.size)
        for (i in 0 until pairCount) {
            tokens.add(ParsedStockToken(trimmed, codes[i], names[i]))
        }
        for (i in pairCount until codes.size) {
            tokens.add(ParsedStockToken(trimmed, codes[i], null))
        }
        if (codes.isEmpty()) {
            // 纯名称段（可能一段多只）：每个中文片段独立成条
            names.forEach { tokens.add(ParsedStockToken(trimmed, null, it)) }
        }
        return tokens
    }

    /**
     * 补全代码前缀：
     * - hk + 4~6 位 → 取后 5 位补足 5 位（与单只添加 searchStocks 的规则一致）
     * - sh/sz/bj 必须 6 位
     * - 裸 6 位：6/5 开头→sh；43/83/87/88/92→北交所 bj；其余→sz
     * - 裸 5 位→港股
     */
    private fun normalizeCode(prefix: String?, digits: String): String? {
        return when (prefix) {
            "hk" -> if (digits.length in 4..6) "hk" + digits.takeLast(5).padStart(5, '0') else null
            "sh", "sz", "bj" -> if (digits.length == 6) prefix + digits else null
            null -> when {
                digits.length == 6 -> when {
                    digits.startsWith("6") || digits.startsWith("5") -> "sh$digits"
                    digits.startsWith("43") || digits.startsWith("83") ||
                        digits.startsWith("87") || digits.startsWith("88") ||
                        digits.startsWith("92") -> "bj$digits"
                    else -> "sz$digits"
                }
                digits.length == 5 -> "hk" + digits
                else -> null
            }
            else -> null
        }
    }
}
