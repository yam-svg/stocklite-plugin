package com.stocklite.plugin.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

@State(
    name = "StockliteState",
    storages = [Storage("stocklite.xml")]
)
class StockliteState : PersistentStateComponent<StockliteState> {

    var stockGroups: MutableList<StockGroupData> = ArrayList()
    var stocks: MutableList<StockData> = ArrayList()
    var fundGroups: MutableList<FundGroupData> = ArrayList()
    var funds: MutableList<FundData> = ArrayList()
    var futureGroups: MutableList<FutureGroupData> = ArrayList()
    var futures: MutableList<FutureData> = ArrayList()

    // ── 列显示设置 ──
    var stockVisibleColumns: MutableList<String> = ArrayList()
    var fundVisibleColumns: MutableList<String> = ArrayList()

    // ── 列宽记忆 ──
    var columnWidths: MutableList<ColumnWidthEntry> = ArrayList()

    // ── 涨跌幅颜色方案 ──
    var colorScheme: String = "RED_UP"

    // ── 界面语言 ──
    var language: String = "ZH"

    // ── 刷新间隔（秒）──
    var refreshIntervalStock: Int = 5
    var refreshIntervalFund: Int = 30
    var refreshIntervalGlobal: Int = 5

    // ── A股大盘概览快照持久化（跨IDE重启保留最后一次成功获取的数据，收盘/重启后不至于变成"--"）──
    var breadthSnapshotJson: String = ""
    var breadthSnapshotTime: Long = 0L

    // ── 功能开关 ──
    var enablePriceAlerts: Boolean = true
    var enableFundNavAlert: Boolean = true
    var enablePortfolioStatusBar: Boolean = true

    // ── AI 分析 ──
    var deepseekApiKey: String   = ""
    var deepseekModel:  String   = "deepseek-chat"

    // ── AI 功能增强 ──
    /** 将当前时间 + 行情数据发送给 AI，让分析更准确 */
    var aiInjectRealTimeData: Boolean = true
    /** 允许 AI 调用 Tavily 联网搜索最新新闻/公告 */
    var aiEnableWebSearch: Boolean = false
    /** Tavily Search API Key（https://tavily.com 免费注册） */
    var aiTavilyApiKey: String = ""
    /** 联网搜索最大轮次（每轮可调用一次 web_search 工具） */
    var aiWebSearchMaxRounds: Int = 8
    /** 自动切换为 deepseek-reasoner 深度推理模型 */
    var aiEnableDeepReasoning: Boolean = false
    /** 单次 AI 回复最大 Token 数 */
    var aiMaxTokens: Int = 1500

    // ── 全球指数自定义排序（存储 symbol 顺序） ──
    var globalIndexOrder: MutableList<String> = ArrayList()

    // ── 全球指数自定义显示名称（symbol -> 别名），为空/缺失时显示默认名称 ──
    var globalIndexAliases: MutableMap<String, String> = HashMap()

    // ── 价格提醒 ──
    var priceAlerts: MutableList<PriceAlertData> = ArrayList()

    override fun getState(): StockliteState = this

    override fun loadState(state: StockliteState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    // ── 列宽 CRUD ──

    fun getColumnWidth(key: String): Int? = columnWidths.find { it.key == key }?.width

    fun setColumnWidth(key: String, width: Int) {
        val existing = columnWidths.find { it.key == key }
        if (existing != null) existing.width = width
        else columnWidths.add(ColumnWidthEntry().also { it.key = key; it.width = width })
    }

    // ── 价格提醒 CRUD ──

    fun createAlert(symbol: String, name: String, targetPrice: Double, alertType: String): PriceAlertData {
        val a = PriceAlertData().apply {
            id = UUID.randomUUID().toString()
            this.symbol = symbol
            this.name = name
            this.targetPrice = targetPrice
            this.alertType = alertType
            this.createdAt = System.currentTimeMillis()
        }
        priceAlerts.add(a)
        return a
    }

    fun deleteAlert(id: String) = priceAlerts.removeIf { it.id == id }

    fun getAlertsForSymbol(symbol: String) = priceAlerts.filter { it.symbol == symbol }

    // ── 基金行情刷新通知（Watcher 拿到新数据后广播，Panel 立即更新列表）──
    interface FundQuotesRefreshListener { fun onFundQuotesRefreshed(quotes: Map<String, FundQuote>) }
    @Transient private val fundQuotesListeners = mutableListOf<FundQuotesRefreshListener>()
    fun addFundQuotesListener(l: FundQuotesRefreshListener) { fundQuotesListeners.add(l) }
    fun removeFundQuotesListener(l: FundQuotesRefreshListener) { fundQuotesListeners.remove(l) }
    fun notifyFundQuotesRefreshed(quotes: Map<String, FundQuote>) =
        fundQuotesListeners.forEach { it.onFundQuotesRefreshed(quotes) }

    // ── 列设置变更通知 ──
    interface ColumnSettingsListener { fun onColumnSettingsChanged() }
    @Transient private val columnListeners = mutableListOf<ColumnSettingsListener>()
    fun addColumnListener(l: ColumnSettingsListener) { columnListeners.add(l) }
    fun removeColumnListener(l: ColumnSettingsListener) { columnListeners.remove(l) }
    fun notifyColumnSettingsChanged() = columnListeners.forEach { it.onColumnSettingsChanged() }

    // ── 语言变更通知 ──
    interface LanguageListener { fun onLanguageChanged() }
    @Transient private val languageListeners = mutableListOf<LanguageListener>()
    fun addLanguageListener(l: LanguageListener) { languageListeners.add(l) }
    fun removeLanguageListener(l: LanguageListener) { languageListeners.remove(l) }
    fun notifyLanguageChanged() = languageListeners.forEach { it.onLanguageChanged() }

    // ── 刷新间隔变更通知 ──
    interface RefreshIntervalListener { fun onRefreshIntervalChanged() }
    @Transient private val refreshIntervalListeners = mutableListOf<RefreshIntervalListener>()
    fun addRefreshIntervalListener(l: RefreshIntervalListener) { refreshIntervalListeners.add(l) }
    fun removeRefreshIntervalListener(l: RefreshIntervalListener) { refreshIntervalListeners.remove(l) }
    fun notifyRefreshIntervalChanged() = refreshIntervalListeners.forEach { it.onRefreshIntervalChanged() }

    // ── 股票分组 CRUD ──

    fun createStockGroup(name: String): StockGroupData {
        val g = StockGroupData().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            createdAt = System.currentTimeMillis()
        }
        stockGroups.add(g)
        return g
    }

    fun updateStockGroup(id: String, name: String) {
        stockGroups.find { it.id == id }?.name = name
    }

    fun deleteStockGroup(id: String) {
        stockGroups.removeIf { it.id == id }
        val fallback = stockGroups.firstOrNull()?.id ?: return
        stocks.filter { it.groupId == id }.forEach { it.groupId = fallback }
    }

    // ── 股票 CRUD ──

    fun createStock(symbol: String, name: String, groupId: String, costPrice: Double, quantity: Double): StockData {
        val s = StockData().apply {
            id = UUID.randomUUID().toString()
            this.symbol = symbol
            this.name = name
            this.groupId = groupId
            this.costPrice = costPrice
            this.quantity = quantity
            sortOrder = stocks.size
            createdAt = System.currentTimeMillis()
        }
        stocks.add(s)
        return s
    }

    fun updateStock(id: String, costPrice: Double, quantity: Double, groupId: String) {
        stocks.find { it.id == id }?.apply {
            this.costPrice = costPrice
            this.quantity = quantity
            this.groupId = groupId
        }
    }

    fun deleteStock(id: String) = stocks.removeIf { it.id == id }

    fun getStocksForGroup(groupId: String): List<StockData> = when (groupId) {
        SystemGroups.ALL_STOCK_ID -> stocks.sortedBy { it.sortOrder }
        SystemGroups.HOLDING_STOCK_ID -> stocks.filter { it.quantity > 0 }.sortedBy { it.sortOrder }
        else -> stocks.filter { it.groupId == groupId }.sortedBy { it.sortOrder }
    }

    // ── 基金分组 CRUD ──

    fun createFundGroup(name: String): FundGroupData {
        val g = FundGroupData().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            createdAt = System.currentTimeMillis()
        }
        fundGroups.add(g)
        return g
    }

    fun updateFundGroup(id: String, name: String) {
        fundGroups.find { it.id == id }?.name = name
    }

    fun deleteFundGroup(id: String) {
        fundGroups.removeIf { it.id == id }
        val fallback = fundGroups.firstOrNull()?.id ?: return
        funds.filter { it.groupId == id }.forEach { it.groupId = fallback }
    }

    // ── 基金 CRUD ──

    fun createFund(code: String, name: String, groupId: String, costNav: Double, shares: Double): FundData {
        val f = FundData().apply {
            id = UUID.randomUUID().toString()
            this.code = code
            this.name = name
            this.groupId = groupId
            this.costNav = costNav
            this.shares = shares
            sortOrder = funds.size
            createdAt = System.currentTimeMillis()
        }
        funds.add(f)
        return f
    }

    fun updateFund(id: String, costNav: Double, shares: Double, groupId: String) {
        funds.find { it.id == id }?.apply {
            this.costNav = costNav
            this.shares = shares
            this.groupId = groupId
        }
    }

    fun deleteFund(id: String) = funds.removeIf { it.id == id }

    fun getFundsForGroup(groupId: String): List<FundData> = when (groupId) {
        SystemGroups.ALL_FUND_ID -> funds.sortedBy { it.sortOrder }
        SystemGroups.HOLDING_FUND_ID -> funds.filter { it.shares > 0 }.sortedBy { it.sortOrder }
        else -> funds.filter { it.groupId == groupId }.sortedBy { it.sortOrder }
    }

    // ── 期货分组 CRUD ──

    fun createFutureGroup(name: String): FutureGroupData {
        val g = FutureGroupData().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            createdAt = System.currentTimeMillis()
        }
        futureGroups.add(g)
        return g
    }

    fun updateFutureGroup(id: String, name: String) {
        futureGroups.find { it.id == id }?.name = name
    }

    fun deleteFutureGroup(id: String) {
        futureGroups.removeIf { it.id == id }
        val fallback = futureGroups.firstOrNull()?.id ?: return
        futures.filter { it.groupId == id }.forEach { it.groupId = fallback }
    }

    // ── 期货 CRUD ──

    fun createFuture(symbol: String, name: String, groupId: String): FutureData {
        val f = FutureData().apply {
            id = UUID.randomUUID().toString()
            this.symbol = symbol
            this.name = name
            this.groupId = groupId
            sortOrder = futures.size
            createdAt = System.currentTimeMillis()
        }
        futures.add(f)
        return f
    }

    fun updateFuture(id: String, groupId: String) {
        futures.find { it.id == id }?.groupId = groupId
    }

    fun deleteFuture(id: String) = futures.removeIf { it.id == id }

    fun getFuturesForGroup(groupId: String): List<FutureData> = when (groupId) {
        SystemGroups.ALL_FUTURE_ID -> futures.sortedBy { it.sortOrder }
        else -> futures.filter { it.groupId == groupId }.sortedBy { it.sortOrder }
    }

    companion object {
        fun getInstance(): StockliteState =
            ApplicationManager.getApplication().getService(StockliteState::class.java)
    }
}
