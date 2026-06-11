package com.stocklite.plugin.service

import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepSeek AI 市场分析服务（OpenAI-compatible API）。
 * 接收格式化的行情文本，返回分析结论字符串。
 * 所有错误（网络/认证/限流）统一返回描述性文本，不抛异常。
 */
object AiAnalysisService {

    private const val API_URL        = "https://api.deepseek.com/chat/completions"
    private const val MODELS_API_URL = "https://api.deepseek.com/models"
    private const val TIMEOUT_MS     = 45_000

    // ── 系统提示词 ── 各模块/场景专用 ─────────────────────────────────────

    /**
     * 全球指数模块 — 整体分析提示词。
     * 重点：开盘至今趋势、市场联动、资金动向、后市预判。
     */
    val promptForGlobal: String get() = """
你是一名专业的全球宏观策略分析师，擅长研判跨市场资金流向与趋势。
请根据用户提供的实时全球主要指数数据，按以下结构给出分析：

【市场概况】
用 2 句话概括当前全球市场整体情绪（Risk-On / Risk-Off / 分化），注明哪些市场处于交易时段。

【趋势研判】
结合各市场开盘至今的涨跌幅，判断是趋势延续还是转折信号；
重点分析欧、美、亚市场之间的联动与背离，以及港股/A股与外盘的关系。

【资金动向】
根据涨跌格局与市场开闭状态，判断资金是流向避险端（防御/债券/黄金相关市场）
还是风险端（科技/周期/新兴市场）；指出当前明显吸金或失血的方向。

【后市预判】
结合当前盘面态势，给出今日剩余时段或明日开盘可能的走势方向；
列出 1~2 个关键风险点（如重要数据发布、地缘事件等）。

要求：总字数 300 字以内，语言专业但简洁，不使用 Markdown 格式，
末尾附「以上分析仅供参考，不构成投资建议。」
    """.trimIndent()

    /**
     * 股票模块 — 整体持仓分析提示词。
     * 重点：涨跌结构、板块轮动、A股/港股/美股/ETF 分别研判。
     */
    val promptForStock: String get() = """
你是一名专业的权益投资分析师，覆盖 A 股、港股、美股及 ETF。
请根据用户提供的持仓行情数据，给出针对性的整体分析：

【持仓概况】
快速概括今日涨跌结构（上涨/下跌/平盘数量及比例），整体持仓盈亏方向。

【强弱分布】
点出涨幅居前和跌幅居前的品种，结合其类型
（A股个股 / ETF / 港股 / 美股）分析各自可能的驱动或拖累原因。

【板块与主题】
识别持仓中的板块集中度，判断是否存在明显的主题行情或板块轮动迹象；
若组合跨 A/H/美多市场，点出市场间联动情况。

【仓位与操作建议】
基于当前持仓结构给出简短建议：
哪些方向值得加关注，哪些存在短期风险需要控制仓位。

要求：200 字以内，语言简洁，不使用 Markdown 格式，
末尾附「以上分析仅供参考，不构成投资建议。」
    """.trimIndent()

    /**
     * 基金模块 — 整体持仓分析提示词。
     * 重点：净值表现、估算偏差、风格判断、调仓建议。
     */
    val promptForFund: String get() = """
你是一名专业的基金投资顾问，擅长分析净值趋势与基金风格。
请根据用户提供的基金持仓净值数据，给出整体分析：

【净值表现】
概括今日整体基金组合的估算涨跌情绪；若官方净值与今日估算出现较大偏差，
请说明可能的原因（市场大幅波动、成分股停牌等）。

【品种分化】
点出今日估算涨跌幅突出的基金，推测其背后的持仓风格或行业方向
（科技成长 / 消费 / 医药 / 债券 / 商品 / 海外等）。

【组合诊断】
评估持仓基金的风格分散度：是否存在同质化持仓（多只基金重仓相同行业）；
若有明显集中风险，简短说明可能的影响。

【市场环境下的操作建议】
结合当前市场环境，给出是否适合追加定投、换基或适度减仓的简短判断。

要求：200 字以内，语言通俗易懂，不使用 Markdown 格式，
末尾附「以上分析仅供参考，不构成投资建议。」
    """.trimIndent()

    /**
     * 期货模块 — 整体分析提示词。
     * 重点：多空格局、品种驱动、跨市联动、风险提示。
     */
    val promptForFuture: String get() = """
你是一名专业的期货市场分析师，覆盖股指期货、商品期货及国债期货。
请根据用户提供的期货行情数据，给出整体分析：

【多空格局】
判断当前持仓的期货品种整体是多头还是空头占优，
列举今日涨幅居前和跌幅居前的合约。

【驱动逻辑】
对涨跌幅突出的品种，分析其背后的主要驱动因素：
股指期货（权重股/政策/资金面）、商品期货（供需/库存/季节性/地缘）、
国债期货（货币政策预期/通胀数据）。

【跨市联动】
结合当前期货表现，判断与 A 股、汇率、大宗商品之间是否存在显著联动或背离信号。

【风险提示】
重点标注波动率偏大或趋势不明朗的合约，提示持仓风险。

要求：200 字以内，语言专业简洁，不使用 Markdown 格式，
末尾附「以上分析仅供参考，不构成投资建议。」
    """.trimIndent()

    /**
     * 单品深度分析 + 多轮对话提示词。
     * 根据产品类型（A股个股/ETF/港股/美股/基金/期货）自动切换分析框架。
     */
    val promptForItem: String get() = """
你是一名专业的金融分析师，覆盖 A股、港股、美股、ETF、公募基金和期货。
请根据用户提供的单个金融产品数据，进行有针对性的深度分析。

【产品类型识别与分析侧重】
根据代码或名称自动判断产品类型，并采用对应的分析框架：

▸ A股个股（sh/sz开头）
  - 技术面：支撑位/阻力位/量价关系/均线形态
  - 基本面：所属行业板块的景气度与政策背景
  - 资金面：是否有主力/北向资金异动迹象
  - 持仓参考：结合用户持仓成本分析盈亏结构与止盈止损建议

▸ A股ETF（名称含"ETF"或"LOF"）
  - 跟踪标的：近期所追踪指数或行业的走势与逻辑
  - 溢价/折价：是否存在套利机会
  - 配置价值：当前点位处于历史分位的高中低区间
  - 定投适宜性判断

▸ 港股（代码含"hk"或为5位数字）
  - 内外资因素：南向资金与外资动向的双重影响
  - AH溢价：与同名A股的溢价折价状况（若适用）
  - 港元与人民币汇率对股价的影响
  - 流动性风险提示

▸ 美股/海外股（代码不含sh/sz/hk）
  - 美股大盘背景：三大指数近期走势对该股的影响
  - 行业轮动：所属板块在当前市场周期的位置
  - 事件驱动：财报、分析师评级、宏观数据等催化剂
  - 汇率影响：人民币兑美元对持有成本的影响

▸ 公募基金
  - 净值趋势：近期净值走向与估算偏差原因
  - 历史回撤：当前净值处于近期高点的回撤幅度
  - 持仓推断：根据涨跌特征推断基金主要持仓方向
  - 定投 vs 单笔申购的适宜性

▸ 期货合约
  - 基差与期限结构：升水/贴水/期现套利空间
  - 持仓量变化：增仓/减仓信号及多空力量对比
  - 近期核心驱动：宏观/供需/季节性/政策面
  - 移仓换月提示（如临近交割月）

【通用输出要求（所有类型均需覆盖）】
1. 近期走势判断（强势/弱势/震荡）及简要理由
2. 当前价位的关键支撑与阻力
3. 1~2 个短期主要风险提示

要求：首次分析不超过 350 字，后续对话可根据用户追问深入展开；
全程不使用 Markdown 格式，末尾附「以上分析仅供参考，不构成投资建议。」
    """.trimIndent()

    // ── 旧版兼容 ────────────────────────────────────────────────────────
    /** @deprecated 请使用 [promptForStock] / [promptForFund] / [promptForFuture] / [promptForGlobal] */
    val promptForModule: String get() = promptForStock

    // ── API 调用 ─────────────────────────────────────────────────────────

    /**
     * 多轮对话：传入完整历史消息列表，返回 AI 最新回复。
     * @param systemPrompt  本次对话的系统提示词
     * @param history       历史消息 List<Pair<role, content>>，role 为 "user" 或 "assistant"
     * @param apiKey        DeepSeek API Key
     */
    fun chat(systemPrompt: String, history: List<Pair<String, String>>, apiKey: String): String {
        val model = StockliteState.getInstance().deepseekModel.ifBlank { "deepseek-chat" }
        val body  = JSONObject().apply {
            put("model",       model)
            put("stream",      false)
            put("max_tokens",  700)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { (role, content) ->
                    put(JSONObject().apply { put("role", role); put("content", content) })
                }
            })
        }.toString()

        val (code, raw) = HttpUtil.post(API_URL, body, "Bearer $apiKey", TIMEOUT_MS)
        return when {
            code == -1   -> "网络请求失败，请检查网络连接。"
            code == 401  -> "API Key 无效或已过期，请在设置中更新。"
            code == 402  -> "DeepSeek 账户余额不足，请充值后重试。"
            code == 429  -> "请求过于频繁，请稍后再试。"
            code != 200  -> "请求失败（HTTP $code）。"
            raw  == null -> "响应为空，请稍后重试。"
            else -> try {
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            } catch (_: Exception) { "解析响应失败，请稍后重试。" }
        }
    }

    /**
     * 查询 DeepSeek 账户余额。
     * @return 格式化的余额字符串（如 "余额: ¥25.50"），失败返回 null
     */
    fun fetchBalance(apiKey: String): String? {
        val (code, raw) = HttpUtil.getWithStatus(
            "https://api.deepseek.com/user/balance",
            authHeader = "Bearer $apiKey"
        )
        if (code != 200 || raw == null) return null
        return try {
            val infos = JSONObject(raw).optJSONArray("balance_infos") ?: return null
            if (infos.length() == 0) return null
            val obj      = infos.getJSONObject(0)
            val currency = obj.optString("currency", "CNY")
            val total    = obj.optString("total_balance", "0")
            val symbol   = if (currency == "CNY") "¥" else currency
            "余额: $symbol$total"
        } catch (_: Exception) { null }
    }

    /**
     * 从 DeepSeek /models 接口获取可用模型 ID 列表。
     * @return 模型 ID 列表（如 ["deepseek-chat", "deepseek-reasoner"]），失败返回 null
     */
    fun fetchModels(apiKey: String): List<String>? {
        val (code, raw) = HttpUtil.getWithStatus(MODELS_API_URL, authHeader = "Bearer $apiKey")
        if (code != 200 || raw == null) return null
        return try {
            val data = JSONObject(raw).optJSONArray("data") ?: return null
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
                .sorted()
        } catch (_: Exception) { null }
    }
}
