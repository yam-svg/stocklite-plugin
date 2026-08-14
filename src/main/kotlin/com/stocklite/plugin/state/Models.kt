package com.stocklite.plugin.state

// ── 持久化数据模型（XML 序列化要求：无参构造 + var 属性）──

class StockGroupData {
    var id: String = ""
    var name: String = ""
    var createdAt: Long = 0L
}

class StockData {
    var id: String = ""
    var symbol: String = ""
    var name: String = ""
    /** 自定义显示名称（别名），为空时显示 name；仅影响展示，搜索/行情/AI仍用真实名称 */
    var alias: String = ""
    var groupId: String = ""
    var costPrice: Double = 0.0
    var quantity: Double = 0.0
    var sortOrder: Int = 0
    var createdAt: Long = 0L
    /** 最后一次修改成本价或持仓数量的时间戳（毫秒），用于今日盈亏的计算口径判断 */
    var updatedAt: Long = 0L
    /**
     * 今日首次修改前的持仓数量快照（股数）。
     * 仅在 updatedAt 为今天时有效，用于区分「老仓」和「今日变动部分」：
     * - 老仓 (snapshotQty)：以昨收为基准计算今日盈亏
     * - 新增/减仓 (quantity - snapshotQty)：以当前均价（costPrice）为基准
     * 未修改过（snapshotQty == -1）表示无快照，退化为统一逻辑。
     */
    var snapshotQty: Double = -1.0
    /** 今日首次修改前的成本价快照 */
    var snapshotCostPrice: Double = 0.0
    /**
     * 历史已实现盈亏：Σ (卖出价 - 卖出时成本价) × 卖出量。
     * 随每笔卖出记录更新；删除记录后由 recalcStockFromRecords 重算。
     */
    var realizedPnl: Double = 0.0
    /**
     * 历史累计买入成本：Σ (买入价 × 买入量)，用于计算整体收益率。
     * ADJUST 操作不影响此字段。
     */
    var totalBuyCost: Double = 0.0
}

class FundGroupData {
    var id: String = ""
    var name: String = ""
    var createdAt: Long = 0L
}

class FundData {
    var id: String = ""
    var code: String = ""
    var name: String = ""
    /** 自定义显示名称（别名），为空时显示 name */
    var alias: String = ""
    var groupId: String = ""
    var costNav: Double = 0.0
    var shares: Double = 0.0
    var sortOrder: Int = 0
    var createdAt: Long = 0L
}

class FutureGroupData {
    var id: String = ""
    var name: String = ""
    var createdAt: Long = 0L
}

class FutureData {
    var id: String = ""
    var symbol: String = ""
    var name: String = ""
    /** 自定义显示名称（别名），为空时显示 name */
    var alias: String = ""
    var groupId: String = ""
    var sortOrder: Int = 0
    var createdAt: Long = 0L
}

// ── 价格提醒（持久化）──
class PriceAlertData {
    var id: String = ""
    var symbol: String = ""   // e.g. "sh600519"
    var name: String = ""
    var targetPrice: Double = 0.0
    var alertType: String = "ABOVE"  // "ABOVE" | "BELOW"
    var enabled: Boolean = true
    var triggered: Boolean = false
    var createdAt: Long = 0L
}

// ── 列宽记忆（持久化）──
class ColumnWidthEntry {
    var key: String = ""    // e.g. "stock.name", "fund.nav"
    var width: Int = 80
}

// ── 行情数据（运行时，不持久化）──

/** 盘前/盘后会话标识 */
enum class ExtendedSession { PRE_MARKET, POST_MARKET }

data class StockQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val change: Double,
    val changePercent: Double,
    val updateTime: Long = System.currentTimeMillis(),
    /** 盘前或盘后价格（仅美股，非交易时段时才有值） */
    val extendedPrice: Double? = null,
    /** 盘前/盘后相对昨收的涨跌幅% */
    val extendedChangePercent: Double? = null,
    /** 当前处于盘前还是盘后 */
    val extendedSession: ExtendedSession? = null
)

data class FundQuote(
    val code: String,
    val name: String,
    val nav: Double,
    val changePercent: Double,
    val date: String,
    val estimatedNav: Double? = null,
    val estimatedChangePercent: Double? = null,
    val hasEstimate: Boolean = false
)

data class FutureQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val change: Double,
    val changePercent: Double
)

data class GlobalIndexQuote(
    val symbol: String,
    val name: String,
    val value: Double,
    val changePercent: Double,
    val isOpen: Boolean,
    val market: String,
    /** 本次取到的数据是否来自延迟源（如 Yahoo 免费接口，约15分钟延迟），而非新浪/腾讯实时源 */
    val isDelayed: Boolean = false
)

/**
 * A股（沪深两市）大盘概览。各字段独立可空——单项接口失败时该项显示"--"，不影响其它已取到的数据。
 * 数据源均为东方财富 push2 系列免费接口（实测确认，无需登录/鉴权）。
 */
data class MarketBreadthData(
    val upCount: Int? = null,
    val downCount: Int? = null,
    val flatCount: Int? = null,
    val limitUpCount: Int? = null,
    val limitDownCount: Int? = null,
    /** 两市成交额代理指标（中证流通指数成交额，覆盖沪深京全市场），单位：元 */
    val totalTurnover: Double? = null,
    /** 大盘股代理：沪深300涨跌幅% */
    val largeCapPct: Double? = null,
    /** 中盘股代理：中证500涨跌幅% */
    val midCapPct: Double? = null,
    /** 小盘股代理：中证1000涨跌幅% */
    val smallCapPct: Double? = null,
    /** 领涨行业板块 TOP6（已过滤成分股<5只的冷门板块），为空表示接口失败 */
    val topSectors: List<SectorPct> = emptyList(),
    /** 领跌行业板块 TOP6（同上过滤规则） */
    val bottomSectors: List<SectorPct> = emptyList(),
    /** 主力资金净流入（沪深两市合计），单位：元，负数为净流出 */
    val mainNetInflow: Double? = null,
    /** 沪深300股指期货（主力合约）收盘后龙虎榜：中信期货(代客) 与前20名会员合计的多空持仓 */
    val futuresPosition: IndexFuturesPosition? = null
)

/**
 * 股指期货会员持仓龙虎榜快照（中金所官方数据，东方财富镜像）。
 * 统计口径：四大期指（IH/IF/IC/IM）全部合约月份合计，与东财"品种合计"口径一致，非仅主力合约。
 * @param tradeDate 数据对应的交易日"MM-dd"，非当日说明当日尚未收盘结算、仍是上一交易日数据
 * @param citicLong/citicShort 中信期货(代客) 持多单/空单量（手）
 * @param citicLongChange/citicShortChange 中信较上一交易日的多单/空单增减（手）
 * @param mainForceLong/mainForceShort 前20名会员（"本日合计"，即龙虎榜官方汇总口径）持多单/空单量（手）
 * @param mainForceLongChange/mainForceShortChange 前20名会员合计较上一交易日的多单/空单增减（手）
 * @param citicByVariety 中信各品种净加空明细
 */
data class IndexFuturesPosition(
    val tradeDate: String,
    val citicLong: Double,
    val citicShort: Double,
    val citicLongChange: Double,
    val citicShortChange: Double,
    val mainForceLong: Double,
    val mainForceShort: Double,
    val mainForceLongChange: Double = Double.NaN,
    val mainForceShortChange: Double = Double.NaN,
    val citicByVariety: List<VarietyNetChange> = emptyList(),
    val mainForceByVariety: List<VarietyNetChange> = emptyList()
)

/**
 * 单个期指品种的净加空手数。
 * @param netAddShort Δ空单-Δ多单：正=净加空（看空倾向增强），负=净加多/减空
 */
data class VarietyNetChange(val code: String, val name: String, val netAddShort: Double)

/**
 * 盘后多空信号汇总（启发式，非严格意义的预测）。
 * @param score 综合得分 -100..100，正=偏多；由各可用因子加权求和后按可用权重归一
 * @param factors 各因子明细（含缺失数据时被跳过的说明），供悬浮提示透明展示
 * @param generatedAt 生成时间 "HH:mm"
 */
data class MarketForecast(
    val score: Double,
    val factors: List<ForecastFactor>,
    val generatedAt: String
)

/**
 * @param score 该因子对综合得分的实际贡献（已乘权重），正=偏多；NaN 表示数据缺失被跳过
 * @param detail 因子取值的人话描述，如 "富时中国A50期货 +1.07%"
 */
data class ForecastFactor(val name: String, val detail: String, val score: Double)

data class SectorPct(val name: String, val pct: Double)

/** 基金单只持股明细（来自东方财富季报数据） */
data class FundHoldingItem(
    val rank:       Int,
    val stockCode:  String,
    val stockName:  String,
    val navPercent: Double,   // 占净值比例 (%)
    val holdShares: String,   // 持仓股数，原始格式字符串（如 "1,234.56"，单位万股）
    val holdValue:  String,   // 持仓市值，原始格式字符串（如 "56,789.00"，单位万元）
    val change:     String    // 较上期变化（"增加"/"减少"/"新进"/"退出"/"不变" 或空）
)

data class FundHoldingsResult(
    val reportDate:  String,                  // 报告期，如 "2024-03-31"
    val reportLabel: String,                  // 如 "2024年第1季度"
    val items:       List<FundHoldingItem>,
    val totalCount:  Int,                     // 实际持仓股数（含未展示部分）
    val error:       String? = null
)

/** 美股板块 ETF 行情（运行时，不持久化） */
data class SectorQuote(
    val symbol: String,
    val nameCn: String,
    val regularPct: Double,
    val prePct: Double? = null,
    val postPct: Double? = null
)

/** 股票交易记录（持久化） */
class TradeRecordData {
    var id: String = ""
    var stockId: String = ""       // FK → StockData.id
    var symbol: String = ""        // 冗余，方便显示
    var stockName: String = ""     // 冗余，方便显示
    var tradeType: String = "BUY"  // "BUY" | "SELL" | "ADJUST"
    var price: Double = 0.0
    var quantity: Double = 0.0
    var note: String = ""
    var tradeAt: Long = 0L         // 交易日期（用户填写，精确到天）
    var createdAt: Long = 0L       // 记录创建时间
}

data class StockSearchResult(val symbol: String, val name: String)
data class FundSearchResult(val code: String, val name: String)
data class FutureSearchResult(val symbol: String, val name: String)

// ── 系统分组常量 ──

object SystemGroups {
    const val ALL_STOCK_ID = "__all_stocks__"
    const val ALL_STOCK_NAME = "全部股票"
    const val HOLDING_STOCK_ID = "__holding_stocks__"
    const val HOLDING_STOCK_NAME = "我的持有"

    const val ALL_FUND_ID = "__all_funds__"
    const val ALL_FUND_NAME = "全部基金"
    const val HOLDING_FUND_ID = "__holding_funds__"
    const val HOLDING_FUND_NAME = "我的持有"

    const val ALL_FUTURE_ID = "__all_futures__"
    const val ALL_FUTURE_NAME = "全部期货"
}
