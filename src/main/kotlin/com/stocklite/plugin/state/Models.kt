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
    var groupId: String = ""
    var costPrice: Double = 0.0
    var quantity: Double = 0.0
    var sortOrder: Int = 0
    var createdAt: Long = 0L
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

data class StockQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val change: Double,
    val changePercent: Double,
    val updateTime: Long = System.currentTimeMillis()
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
