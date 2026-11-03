package com.stocklite.plugin.util

import com.github.promeg.pinyinhelper.Pinyin
import com.stocklite.plugin.state.StockliteState

/**
 * 隐秘模式（防偷窥）：把界面上展示的中文标的名转成拼音，旁人扫一眼看不出在看什么行情。
 * 参考 Money Never Sleeps 的"隐秘模式"：全拼逐字首字母大写（贵州茅台 → GuiZhouMaoTai），
 * 本插件另加"拼音首字母"档（贵州茅台 → GZmt 式缩写，见 ABBR 说明）。
 * 开启时涨跌配色同步抹平（QuoteRenderer 正负色返回 null）。
 *
 * 转换只发生在展示层：数据模型、搜索、AI 上下文、导入导出均用真名，不受影响。
 */
object StealthName {

    const val OFF = "OFF"; const val FULL = "FULL"; const val ABBR = "ABBR"

    /** 当前是否处于隐秘模式（读取失败时按关闭处理，如单测无 IDE 环境） */
    fun active(): Boolean = try {
        StockliteState.getInstance().stealthMode != OFF
    } catch (_: Throwable) { false }

    /** 展示名转换：隐秘模式关闭或输入为空时原样返回 */
    fun display(raw: String?): String {
        if (raw.isNullOrEmpty()) return raw ?: ""
        if (!active()) return raw
        return convert(raw, StockliteState.getInstance().stealthMode)
    }

    /**
     * 界面文字（标签页/表头/按钮等静态文案）转换：需同时满足隐秘开启 + "界面文字"开关。
     * 英文界面下字符串本就无汉字，透传即等效不转。
     */
    fun uiText(raw: String?): String {
        if (raw.isNullOrEmpty()) return raw ?: ""
        val on = try { StockliteState.getInstance().stealthUiText } catch (_: Throwable) { false }
        if (!active() || !on) return raw
        return convert(raw, StockliteState.getInstance().stealthMode)
    }

    /** 循环切换档位：OFF → FULL → ABBR → OFF（工具栏快捷按钮用） */
    fun cycle(current: String): String = when (current) {
        OFF  -> FULL
        FULL -> ABBR
        else -> OFF
    }

    // ── 转换实现（带缓存：名称集合有限且渲染高频，避免每帧重算）──
    // internal 供无 IDE 环境的独立自测直接验证纯转换逻辑

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun convert(raw: String, mode: String): String {
        val key = "$mode|$raw"
        cache[key]?.let { return it }
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (Pinyin.isChinese(ch)) {
                // 单字取默认读音 + 金融高频词特判（见下）；其余词组级多音不保证，防窥场景可接受
                val py = pinyinOf(raw, i).lowercase()
                when (mode) {
                    ABBR -> sb.append(py.first().uppercaseChar())  // 首字母档：贵州茅台 → GZMT
                    else -> sb.append(py.first().uppercaseChar()).append(py.drop(1))  // 全拼档：GuiZhouMaoTai
                }
            } else {
                sb.append(ch)  // 非中文（数字/字母/括号）原样透传
            }
            i++
        }
        val out = sb.toString()
        if (cache.size > 20_000) cache.clear()  // 极端场景兜底，正常远达不到
        cache[key] = out
        return out
    }

    /** 多音字金融语境补丁：TinyPinyin 单字"行"默认 xíng，但银行/行业均读 háng（银行股名高频出现） */
    private fun pinyinOf(raw: String, i: Int): String {
        val ch = raw[i]
        if (ch == '行') {
            val prev = raw.getOrNull(i - 1)
            val next = raw.getOrNull(i + 1)
            if (prev == '银' || next == '业') return "HANG"
        }
        return Pinyin.toPinyin(ch)
    }

    /** 设置变更后清缓存由模式本身兜底（缓存键含模式），无需显式清理 */
}
