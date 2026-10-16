package com.stocklite.plugin.service

import com.stocklite.plugin.state.ForecastFactor
import com.stocklite.plugin.state.ForecastRecordData
import com.stocklite.plugin.state.PreOpenForecast
import com.stocklite.plugin.state.SentimentSnapshot
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 预测机制核心服务——"两张卡两时点两目标"：
 *
 * 1) 盘后"市场天气"（SentimentSnapshot）：只用当日收盘后定型数据描述情绪温度
 *    （涨跌家数/涨停生态/量能环比），是描述性指标、不参与任何结算与胜率统计。
 * 2) 盘前研判（PreOpenForecast）：交易日 8:40–9:25 生成，分两段——
 *    初版（8:40 起）信息集 = 隔夜外盘（A50、纳指隔夜、USDCNH）+ T-1 全部定型数据；
 *    终版（9:15 起）再并入韩日早盘截面：实证发现 KOSPI/日经"前一交易日涨跌"对当日
 *    跳空无预测力（r≈0），但"当日早盘涨幅"与开盘高度共舞（9:15 时点两国已交易 75 分钟，
 *    A股 9:25 才定价，时点合法；n≈50 均值因子→跳空 r≈0.75，三态命中 0.65）。
 *    核心输出是"隐含跳空%"——隔夜部分经实证检验（n≈950：A50 r≈0.22、纳指隔夜 r≈0.22），
 *    早盘部分如上；同样的数据对全天方向预测力弱（r≤0.16~0.5），全天分只做低置信倾向展示。
 *
 * 结算双账本：开盘跳空三态（高开/平开/低开）命中率 + 全天方向命中率分开统计；
 * 错过的日期标记 settled 但不回填数值、不进统计，杜绝"事后诸葛"式虚高胜率。
 */
object ForecastService {

    private val shZone = ZoneId.of("Asia/Shanghai")

    // ── 跳空回归标定（2023-01 ~ 2026-10，n≈930-956，对次日沪深300开盘跳空的一元最小二乘）──
    // 缺失一侧时直接用另一侧的一元回归系数（两者均为一元标定值，无需再重归一）
    private const val A50_SLOPE = 0.171
    private const val NDX_SLOPE = 0.177
    private const val GAP_INTERCEPT = 0.025
    private const val GAP_CLAMP = 1.5        // 隐含跳空输出限幅 ±1.5%

    // ── 韩日早盘因子（2026-08~10，n≈50：两国指数"早场截面均值"→当日跳空 r≈0.75，三态命中 0.65；
    //    前一交易日日级 KOSPI/日经 对当日跳空 r≈0，故只取当日早盘实时值，不取昨收涨跌。
    //    两国互相关 r≈0.89 高度共线，取均值避免双重计入；回归截距(-0.16)是近60日漂移，不稳定，不采纳。
    //    标定截面≈北京9:10（首尔/东京10:10），终版生成时点 9:15 与之几乎一致）──
    private const val ASIA_SLOPE = 0.184     // 均值因子→跳空回归系数
    private const val ASIA_SCORE_FULL = 2.0  // 早盘均值 ±2% 打分打满 ±25
    private val FINALIZE_TIME = LocalTime.of(9, 15)  // 9:15 后生成终版（并入早盘截面）
    const val GAP_FLAT_BAND = 0.15           // 跳空三态阈值：|gap|<0.15% 算平开
    private const val DIRECTION_BAND = 20.0  // 全天方向阈值 ±20 分，其间为震荡
    private const val DAY_FLAT_BAND = 0.1    // 全天实际涨跌 ±0.1% 内算平盘

    private const val SETTLE_INDEX = "sh000300"   // 结算标的：沪深300
    private const val MAX_RECORDS = 130           // 约半年样本

    /** 情绪五档：≥50 过热 / ≥20 热烈 / >-20 正常 / >-50 冷清 / 其余冰点 */
    fun sentimentTier(score: Double): String = when {
        score >= 50 -> "overheated"
        score >= 20 -> "hot"
        score > -20 -> "normal"
        score > -50 -> "cold"
        else -> "freezing"
    }

    /** 跳空三态：HIGH / FLAT / LOW；NaN 原样返回 */
    fun gapState(gapPct: Double): String = when {
        gapPct.isNaN() -> "NAN"
        gapPct >= GAP_FLAT_BAND -> "HIGH"
        gapPct <= -GAP_FLAT_BAND -> "LOW"
        else -> "FLAT"
    }

    /** 全天方向：±20 分以内震荡 */
    fun directionOf(score: Double): String = when {
        score >= DIRECTION_BAND -> "BULL"
        score <= -DIRECTION_BAND -> "BEAR"
        else -> "NEUTRAL"
    }

    // ══════════════════════════════════════════════════════════════
    // 一、盘后"市场天气"情绪分
    // ══════════════════════════════════════════════════════════════

    /**
     * 实时计算市场天气分。各因子独立容错，取不到的因子跳过（得分向 0 收缩=不确定）。
     * 盘前调用时所有输入天然是 T-1 定型数据，因此同一函数可同时服务
     * "盘后天气卡"与"盘前研判的 T-1 情绪极值因子"。
     */
    fun computeSentiment(): SentimentSnapshot? {
        val breadth = MarketDataService.getMarketBreadth()
        val eco = MarketDataService.getLimitEcosystem()
        val date = MarketDataService.getAshareLastBarDate() ?: return null
        val factors = mutableListOf<ForecastFactor>()

        // 涨跌家数差：±3000 家打满 ±25 分
        val up = breadth.upCount
        val down = breadth.downCount
        if (up != null && down != null) {
            val diff = up - down
            factors += ForecastFactor("涨跌家数", "上涨 $up / 下跌 $down", clamp(diff / 3000.0) * 25)
        }
        // 涨停家数（赚钱效应广度）：150 家打满 +20
        eco?.let { factors += ForecastFactor("涨停家数", "涨停 ${it.ztCount} 家", (it.ztCount.coerceAtMost(150) / 150.0) * 20) }
        // 跌停家数（亏钱效应）：80 家打满 -20
        eco?.let { factors += ForecastFactor("跌停家数", "跌停 ${it.dtCount} 家", -(it.dtCount.coerceAtMost(80) / 80.0) * 20) }
        // 连板高度（情绪梯队/周期位置）：7 板打满 +15
        eco?.let { if (it.maxLianban > 0) factors += ForecastFactor("连板高度", "最高 ${it.maxLianban} 连板", (it.maxLianban.coerceAtMost(7) / 7.0) * 15) }
        // 封板率：以 0.7 为中性轴，±10 分
        eco?.sealRate?.let { r ->
            if (r.isFinite()) factors += ForecastFactor("封板率", "%.0f%%".format(r * 100), clamp((r - 0.7) / 0.3) * 10)
        }
        // 两市量能环比：放量/缩量 ±15（+50% 打满）
        val turnover = breadth.totalTurnover
        val prevTurn = breadth.prevTurnoverSameTime
        if (turnover != null && prevTurn != null && turnover > 0 && prevTurn > 0) {
            val ratio = turnover / prevTurn - 1
            factors += ForecastFactor("量能环比", "%+.1f%%，成交 %.2f万亿".format(ratio * 100, turnover / 1e12), clamp(ratio / 0.5) * 15)
        }

        if (factors.isEmpty()) return null
        val score = factors.sumOf { it.score }.coerceIn(-100.0, 100.0)
        return SentimentSnapshot(date, score, factors)
    }

    /** 计算并持久化当日（或最近交易日）天气快照；数据源全挂时回退持久化快照 */
    fun getSentiment(): SentimentSnapshot? {
        val live = computeSentiment()
        if (live != null) persistSentiment(live)
        return live ?: loadSentiment()
    }

    private fun persistSentiment(s: SentimentSnapshot) = try {
        val arr = JSONArray()
        s.factors.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.detail).put("s", it.score)) }
        StockliteState.getInstance().apply {
            sentimentJson = JSONObject().put("date", s.date).put("score", s.score).put("factors", arr).toString()
            sentimentDate = s.date
        }
    } catch (_: Exception) { Unit }

    private fun loadSentiment(): SentimentSnapshot? = try {
        val st = StockliteState.getInstance()
        if (st.sentimentJson.isBlank()) null
        else {
            val o = JSONObject(st.sentimentJson)
            SentimentSnapshot(o.optString("date"), o.optDouble("score", Double.NaN), parseFactorsJson(o.optString("factors")))
        }
    } catch (_: Exception) { null }

    // ══════════════════════════════════════════════════════════════
    // 二、盘前研判
    // ══════════════════════════════════════════════════════════════

    /** 当前是否处于盘前研判生成窗口（交易日 8:40–9:25） */
    fun inPreOpenWindow(now: ZonedDateTime = ZonedDateTime.now(shZone)): Boolean {
        if (now.dayOfWeek.value > 5) return false
        val t = now.toLocalTime()
        if (t < LocalTime.of(8, 40) || t > LocalTime.of(9, 25)) return false
        return MarketDataService.isTodayTradingDay("CN", "Asia/Shanghai", System.currentTimeMillis())
    }

    /**
     * 生成当日盘前研判：8:40 后首次调用产出隔夜初版并锁定，9:15 后重新生成并入
     * 韩日早盘因子的终版，终版当日锁定不再更新（避免面板反复刷新导致数字跳变）。
     * 非交易日/数据全缺时返回 null。
     */
    fun generatePreOpenForecast(now: ZonedDateTime = ZonedDateTime.now(shZone)): PreOpenForecast? {
        val state = StockliteState.getInstance()
        val today = now.toLocalDate().toString()
        // 两段式：8:40 起先生成"隔夜初版"；9:15 后韩日已交易 75 分钟（截面与标定口径一致），
        // 重新生成并入韩日早盘因子的"终版"，此后当日锁定不再更新。
        val finalizeNow = now.toLocalTime() >= FINALIZE_TIME
        state.forecastHistory.firstOrNull { it.kind == "PRE" && it.date == today }?.let {
            if (it.finalized || !finalizeNow) return rehydrate(it)
        }

        // ── 隔夜信息 + （终版）韩日早盘截面 ──
        val a50 = MarketDataService.getFutureQuotes(listOf("hf_CHA50CFD"))["hf_CHA50CFD"]
            ?.changePercent?.takeIf { it.isFinite() }
        val ndx = getNdxOvernightPct()
        val cnh = MarketDataService.getUsdcnhPct()
        val asia = if (finalizeNow) getAsiaMorningAvgPct() else null

        if (a50 == null && ndx == null && asia == null) return null   // 主力预测源全缺，宁可不给

        val factors = mutableListOf<ForecastFactor>()
        // A50 隔夜：±1.5% 打满 ±35 分
        a50?.let { factors += ForecastFactor("A50期货", "%.2f%%".format(it), clamp(it / 1.5) * 35) }
        // 纳指隔夜：±1.5% 打满 ±20 分
        ndx?.let { factors += ForecastFactor("纳指隔夜", "%.2f%%".format(it), clamp(it / 1.5) * 20) }

        // 隐含跳空：缺失侧直接不进公式（各侧均为一元回归标定值，无需重归一）
        var gap = GAP_INTERCEPT
        a50?.let { gap += A50_SLOPE * it }
        ndx?.let { gap += NDX_SLOPE * it }
        if (finalizeNow) {
            // 韩日早盘：本因子是终版的核心增量，取不到也明示"数据缺失"（gap 退化为隔夜口径）
            factors += if (asia == null) ForecastFactor("韩日早盘", "数据缺失", Double.NaN)
            else {
                gap += ASIA_SLOPE * asia
                ForecastFactor("韩日早盘", "早场均值 %+.2f%%".format(asia), clamp(asia / ASIA_SCORE_FULL) * 25)
            }
        }
        gap = gap.coerceIn(-GAP_CLAMP, GAP_CLAMP)

        // ── T-1 定型数据 ──
        // 情绪极值反转：过热（高潮次日退潮）-12 / 冰点（否极泰来）+12
        computeSentiment()?.let { t1 ->
            when (sentimentTier(t1.score)) {
                "overheated" -> factors += ForecastFactor("T-1情绪过热", "%.0f 分".format(t1.score), -12.0)
                "freezing"   -> factors += ForecastFactor("T-1情绪冰点", "%.0f 分".format(t1.score), 12.0)
                else -> Unit
            }
        }
        // 暴跌反转：T-1 沪深300 ≤ -1.5% 时短线均值回归 +8
        val t1IdxPct = t1IndexPct()
        t1IdxPct?.let { if (it <= -1.5) factors += ForecastFactor("T-1大跌", "%.2f%%".format(it), 8.0) }
        // 汇率极值：贬值 ≥0.3% -6 / 升值 ≥0.3% +4
        cnh?.let {
            if (it >= 0.3) factors += ForecastFactor("人民币贬值", "%.2f%%".format(it), -6.0)
            else if (it <= -0.3) factors += ForecastFactor("人民币升值", "%.2f%%".format(it), 4.0)
        }

        val score = factors.sumOf { it.score }.coerceIn(-100.0, 100.0)
        val generatedAt = "%02d:%02d".format(now.hour, now.minute)
        val forecast = PreOpenForecast(today, generatedAt, gap, score, factors, finalizeNow)

        // ── 落库（同日唯一：终版覆盖隔夜初版；超量裁旧）──
        state.forecastHistory.removeAll { it.kind == "PRE" && it.date == today }
        state.forecastHistory += ForecastRecordData().apply {
            date = today
            this.generatedAt = forecast.generatedAt
            this.score = forecast.score
            direction = directionOf(forecast.score)
            kind = "PRE"; gapPct = forecast.gapPct
            factorsJson = factorsToJson(factors)
            finalized = finalizeNow
        }
        if (state.forecastHistory.size > MAX_RECORDS)
            state.forecastHistory = ArrayList(state.forecastHistory.takeLast(MAX_RECORDS))
        return forecast
    }

    /** 最近一条盘前研判（面板 chip 用） */
    fun latestRecord(): ForecastRecordData? =
        StockliteState.getInstance().forecastHistory.lastOrNull { it.kind == "PRE" }

    /** 把持久化记录还原为展示对象（面板在非生成窗口读最近一条时用） */
    fun rehydrate(r: ForecastRecordData) =
        PreOpenForecast(r.date, r.generatedAt, r.gapPct, r.score, parseFactorsJson(r.factorsJson), r.finalized)

    /**
     * 结算未到终态的记录。只在"指数 bar 日期 == 研判日期"（即结算当日盘中/盘后）时取实时快照：
     * 开盘跳空用今开、全天用现价；bar 日期已越过的记录无法再还原当日数值（昨收口径每日漂移），
     * 直接标记 settled 但数值保持 NaN，不进任何统计——宁缺毋假。
     */
    fun settleForecasts() {
        val state = StockliteState.getInstance()
        val pending = state.forecastHistory.filterNot { it.settled }
        if (pending.isEmpty()) return
        val barDate = MarketDataService.getAshareLastBarDate() ?: return
        val snap = MarketDataService.getTencentIndexSnapshot(SETTLE_INDEX)
        for (r in pending) {
            if (r.date > barDate) continue   // 研判日期还没到（理论上不会发生，防御）
            if (r.date == barDate && snap != null) {
                val openPct = if (snap.third > 0) (snap.third - snap.second) / snap.second * 100 else Double.NaN
                if (openPct.isNaN()) continue   // 尚未开盘，等开盘后再结
                r.openPct = openPct
                r.actualPct = (snap.first - snap.second) / snap.second * 100
                if (!r.gapPct.isNaN()) r.openHit = gapState(r.gapPct) == gapState(openPct)
                val flat = kotlin.math.abs(r.actualPct) <= DAY_FLAT_BAND
                r.correct = when (r.direction) {
                    "BULL" -> r.actualPct > DAY_FLAT_BAND
                    "BEAR" -> r.actualPct < -DAY_FLAT_BAND
                    else -> flat
                }
                r.settled = true
            } else {
                r.settled = true   // 错过的历史日期：结而不统计
            }
        }
    }

    data class ForecastStats(
        val gapSample: Int, val gapHits: Int, val gapHitRate: Double,
        val dirSample: Int, val dirHits: Int, val dirHitRate: Double
    )

    /** 双账本统计：跳空三态命中率 与 全天方向命中率（仅 BULL/BEAR 进方向样本） */
    fun getStats(): ForecastStats {
        val settled = StockliteState.getInstance().forecastHistory.filter { it.settled }
        val gapRecs = settled.filter { it.kind == "PRE" && !it.gapPct.isNaN() && !it.openPct.isNaN() }
        val dirRecs = settled.filter { it.direction == "BULL" || it.direction == "BEAR" }
        val gapHits = gapRecs.count { it.openHit }
        val dirHits = dirRecs.count { it.correct }
        return ForecastStats(
            gapSample = gapRecs.size, gapHits = gapHits,
            gapHitRate = if (gapRecs.isEmpty()) Double.NaN else gapHits * 100.0 / gapRecs.size,
            dirSample = dirRecs.size, dirHits = dirHits,
            dirHitRate = if (dirRecs.isEmpty()) Double.NaN else dirHits * 100.0 / dirRecs.size
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 三、AI 研判短评（复用 AiAnalysisService 轻量通道，30 分钟缓存）
    // ══════════════════════════════════════════════════════════════

    private const val AI_TTL = 30 * 60_000L
    private var aiText: String? = null
    private var aiTime = 0L
    private var aiDate = ""

    fun getAiBrief(refresh: Boolean = false): String? {
        val key = StockliteState.getInstance().deepseekApiKey
        if (key.isBlank()) return null
        val today = ZonedDateTime.now(shZone).toLocalDate().toString()
        if (!refresh && aiText != null && aiDate == today && System.currentTimeMillis() - aiTime < AI_TTL) return aiText

        val fc = latestRecord()
        val eco = MarketDataService.getLimitEcosystem()
        val sent = getSentiment()
        val cnh = MarketDataService.getUsdcnhPct()
        val lines = mutableListOf<String>()
        lines += "日期 $today"
        fc?.let {
            lines += "隔夜与盘前: A50/纳指隐含跳空 ${if (it.gapPct.isNaN()) "无" else "%.2f%%".format(it.gapPct)}，全天倾向 ${"%.0f".format(it.score)} 分(${it.direction})"
            if (it.factorsJson.isNotBlank())
                lines += "研判因子: " + parseFactorsJson(it.factorsJson).joinToString("；") { f -> "${f.name} ${f.detail}" }
        }
        sent?.let { lines += "T-1市场天气: ${"%.0f".format(it.score)} 分(${sentimentTier(it.score)})，" +
            it.factors.joinToString("；") { f -> "${f.name} ${f.detail}" } }
        eco?.let { lines += "T-1涨停生态: 涨停${it.ztCount}/跌停${it.dtCount}/炸板${it.zbCount}/最高${it.maxLianban}板" }
        cnh?.let { lines += "人民币: ${if (it >= 0) "贬值" else "升值"} ${"%.2f%%".format(kotlin.math.abs(it))}" }

        val text = AiAnalysisService.quickAnalyze(
            "你是严谨的A股买方量化助手。基于给定点时数据给出概率化、可验证的研判，不喊单、不给投资建议、不堆砌套话；中文、120字以内。",
            lines.joinToString("\n") + "\n请点评今日开盘与日内节奏，指出主要风险点。",
            key, maxTokens = 300
        ) ?: return aiText   // 失败时沿用旧缓存
        aiText = text; aiTime = System.currentTimeMillis(); aiDate = today
        return text
    }

    // ══════════════════════════════════════════════════════════════
    // 数据小件
    // ══════════════════════════════════════════════════════════════

    /**
     * 韩日当日早盘涨幅均值（KOSPI、日经，取到哪个算哪个；全缺返回 null）。
     * 统一用 Yahoo 5 分钟线：最新价 vs meta.chartPreviousClose，且要求最后一根 bar
     * 在当地时区属于"今天"——防止对方休市时把昨日走势当成今日早盘误用。
     * 标定用的就是这份数据口径（Yahoo 5m），同源保证系数与实时输入一致。
     */
    private fun getAsiaMorningAvgPct(): Double? {
        val vals = listOfNotNull(
            yahooMorningPct("^KS11", ZoneId.of("Asia/Seoul")),
            yahooMorningPct("^N225", ZoneId.of("Asia/Tokyo"))
        )
        return if (vals.isEmpty()) null else vals.average()
    }

    private fun yahooMorningPct(symbol: String, zone: ZoneId): Double? {
        return try {
        val enc = java.net.URLEncoder.encode(symbol, "UTF-8")
        val raw = HttpUtil.get("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=5m&range=1d",
            label = "早盘行情 $symbol") ?: return null
        val result = JSONObject(raw).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        var prev = meta.optDouble("chartPreviousClose", Double.NaN)
        if (!prev.isFinite() || prev <= 0) prev = meta.optDouble("previousClose", Double.NaN)
        val ts = result.optJSONArray("timestamp") ?: return null
        val closes = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close") ?: return null
        var lastClose = Double.NaN
        var lastTs = 0L
        for (i in closes.length() - 1 downTo 0) {
            if (!closes.isNull(i)) { lastClose = closes.getDouble(i); lastTs = ts.getLong(i); break }
        }
        if (!lastClose.isFinite() || !prev.isFinite() || prev <= 0) return null
        // 新鲜度校验：最后一根 bar 必须是当地时区的今天（休市/停牌时 Yahoo 会返回旧数据）
        val barDay = java.time.Instant.ofEpochSecond(lastTs).atZone(zone).toLocalDate()
        if (barDay != java.time.LocalDate.now(zone)) return null
        ((lastClose - prev) / prev * 100).takeIf { it.isFinite() }
        } catch (_: Exception) { null }
    }

    /** 纳指100最近一个美股交易日涨跌%（新浪 gb_$ndx f[2]）。盘前取到即隔夜收盘涨跌。 */
    private fun getNdxOvernightPct(): Double? {
        val raw = HttpUtil.getGbk("https://hq.sinajs.cn/list=gb_\$ndx",
            "https://finance.sina.com.cn", label = "纳指隔夜") ?: return null
        val f = Regex(""""([^"]*)"""").find(raw)?.groupValues?.get(1)?.split(",") ?: return null
        return f.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** T-1 沪深300 全日涨跌%（盘前腾讯快照的 现价vs昨收 即上一交易日全天表现） */
    private fun t1IndexPct(): Double? {
        val s = MarketDataService.getTencentIndexSnapshot(SETTLE_INDEX) ?: return null
        return (s.first - s.second) / s.second * 100
    }

    private fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)

    internal fun factorsToJson(factors: List<ForecastFactor>): String {
        val arr = JSONArray()
        factors.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.detail).put("s", it.score)) }
        return arr.toString()
    }

    internal fun parseFactorsJson(json: String): List<ForecastFactor> = try {
        if (json.isBlank()) emptyList()
        else {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ForecastFactor(o.optString("n"), o.optString("d"), o.optDouble("s", Double.NaN))
            }
        }
    } catch (_: Exception) { emptyList() }
}
