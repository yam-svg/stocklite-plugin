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
    val market: String
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
