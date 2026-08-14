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
    var tradeRecords: MutableList<TradeRecordData> = ArrayList()
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
    var enableUsMarketPanel: Boolean = false  // 默认关闭，使用频率低
    var enableApiLogPanel: Boolean = false   // 默认关闭，调试用途
    var enableChartMA: Boolean = false       // K线图均线（MA5/10/20），默认关闭
    var enableChartVolume: Boolean = true    // K线图底部成交量（红绿柱），默认开启

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

    // ── 功能开关变更通知（美股板块 Tab 显隐） ──
    interface FeatureToggleListener { fun onFeatureToggleChanged() }
    @Transient private val featureToggleListeners = mutableListOf<FeatureToggleListener>()
    fun addFeatureToggleListener(l: FeatureToggleListener) { featureToggleListeners.add(l) }
    fun notifyFeatureToggleChanged() = featureToggleListeners.forEach { it.onFeatureToggleChanged() }

    // ── 数据变更通知（股票/基金/期货增删改，用于跨 IDE 窗口同步） ──
    interface DataChangeListener { fun onDataChanged() }
    @Transient private val dataChangeListeners = mutableListOf<DataChangeListener>()
    fun addDataChangeListener(l: DataChangeListener) { dataChangeListeners.add(l) }
    fun removeDataChangeListener(l: DataChangeListener) { dataChangeListeners.remove(l) }
    fun notifyDataChanged() = dataChangeListeners.forEach { it.onDataChanged() }

    // ── 股票分组 CRUD ──

    fun createStockGroup(name: String): StockGroupData {
        val g = StockGroupData().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            createdAt = System.currentTimeMillis()
        }
        stockGroups.add(g)
        notifyDataChanged()
        return g
    }

    fun updateStockGroup(id: String, name: String) {
        stockGroups.find { it.id == id }?.name = name
        notifyDataChanged()
    }

    fun deleteStockGroup(id: String) {
        stockGroups.removeIf { it.id == id }
        val fallback = stockGroups.firstOrNull()?.id ?: return
        stocks.filter { it.groupId == id }.forEach { it.groupId = fallback }
        notifyDataChanged()
    }

    // ── 股票 CRUD ──

    fun createStock(symbol: String, name: String, groupId: String, costPrice: Double, quantity: Double): StockData {
        val now = System.currentTimeMillis()
        val s = StockData().apply {
            id = UUID.randomUUID().toString()
            this.symbol = symbol
            this.name = name
            this.groupId = groupId
            this.costPrice = costPrice
            this.quantity = quantity
            sortOrder = stocks.size
            createdAt = now
            updatedAt = now
            snapshotQty = 0.0
            snapshotCostPrice = 0.0
        }
        stocks.add(s)
        // 自动生成初始买入记录
        if (quantity > 0) {
            addTradeRecord(s.id, symbol, name, "BUY", costPrice, quantity, now, "初始建仓")
        }
        notifyDataChanged()
        return s
    }

    fun updateStock(id: String, costPrice: Double, quantity: Double, groupId: String) {
        val now = System.currentTimeMillis()
        val shZone = java.time.ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.now(shZone)
        stocks.find { it.id == id }?.apply {
            val prevUpdatedDate = if (updatedAt > 0)
                java.time.Instant.ofEpochMilli(updatedAt).atZone(shZone).toLocalDate()
            else null
            if (prevUpdatedDate != today) {
                snapshotQty = this.quantity
                snapshotCostPrice = this.costPrice
            }
            val oldQty = this.quantity
            val oldCost = this.costPrice
            this.costPrice = costPrice
            this.quantity = quantity
            this.groupId = groupId
            this.updatedAt = now

            // 自动生成交易记录
            val deltaQty = quantity - oldQty
            when {
                deltaQty > 1e-8  -> addTradeRecord(id, symbol, name, "BUY",  costPrice,  deltaQty, now, "加仓")
                // SELL 自动记录价格填 0（实际卖出价未知，需用户手动补充）
                deltaQty < -1e-8 -> addTradeRecord(id, symbol, name, "SELL", 0.0, -deltaQty, now, "减仓（编辑持仓自动生成，价格待填）")
                kotlin.math.abs(costPrice - oldCost) > 1e-8 ->
                    addTradeRecord(id, symbol, name, "ADJUST", costPrice, quantity, now, "成本调整")
            }
        }
        notifyDataChanged()
    }

    fun deleteStock(id: String) {
        stocks.removeIf { it.id == id }
        notifyDataChanged()
    }

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
        notifyDataChanged()
        return g
    }

    fun updateFundGroup(id: String, name: String) {
        fundGroups.find { it.id == id }?.name = name
        notifyDataChanged()
    }

    fun deleteFundGroup(id: String) {
        fundGroups.removeIf { it.id == id }
        val fallback = fundGroups.firstOrNull()?.id ?: return
        funds.filter { it.groupId == id }.forEach { it.groupId = fallback }
        notifyDataChanged()
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
        notifyDataChanged()
        return f
    }

    fun updateFund(id: String, costNav: Double, shares: Double, groupId: String) {
        funds.find { it.id == id }?.apply {
            this.costNav = costNav
            this.shares = shares
            this.groupId = groupId
        }
        notifyDataChanged()
    }

    fun deleteFund(id: String) {
        funds.removeIf { it.id == id }
        notifyDataChanged()
    }

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
        notifyDataChanged()
        return g
    }

    fun updateFutureGroup(id: String, name: String) {
        futureGroups.find { it.id == id }?.name = name
        notifyDataChanged()
    }

    fun deleteFutureGroup(id: String) {
        futureGroups.removeIf { it.id == id }
        val fallback = futureGroups.firstOrNull()?.id ?: return
        futures.filter { it.groupId == id }.forEach { it.groupId = fallback }
        notifyDataChanged()
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
        notifyDataChanged()
        return f
    }

    fun updateFuture(id: String, groupId: String) {
        futures.find { it.id == id }?.groupId = groupId
        notifyDataChanged()
    }

    fun deleteFuture(id: String) {
        futures.removeIf { it.id == id }
        notifyDataChanged()
    }

    fun getFuturesForGroup(groupId: String): List<FutureData> = when (groupId) {
        SystemGroups.ALL_FUTURE_ID -> futures.sortedBy { it.sortOrder }
        else -> futures.filter { it.groupId == groupId }.sortedBy { it.sortOrder }
    }

    // ── 交易记录 CRUD ──

    /**
     * 添加交易记录并同步更新持仓数量和成本价。
     * - BUY：加仓，重算加权均价，数量增加
     * - SELL：减仓，成本价不变，数量减少（不低于0）
     * - ADJUST：仅调整成本价，数量不变
     */
    fun addTradeRecordAndSync(
        stockId: String, symbol: String, stockName: String,
        tradeType: String, price: Double, quantity: Double,
        tradeAt: Long, note: String = ""
    ): TradeRecordData {
        val record = addTradeRecord(stockId, symbol, stockName, tradeType, price, quantity, tradeAt, note)
        val now = System.currentTimeMillis()
        val shZone = java.time.ZoneId.of("Asia/Shanghai")
        val today = java.time.LocalDate.now(shZone)
        stocks.find { it.id == stockId }?.apply {
            // 更新快照（今日首次操作时记录操作前状态）
            val prevUpdatedDate = if (updatedAt > 0)
                java.time.Instant.ofEpochMilli(updatedAt).atZone(shZone).toLocalDate() else null
            if (prevUpdatedDate != today) {
                snapshotQty = this.quantity
                snapshotCostPrice = this.costPrice
            }
            when (tradeType) {
                "BUY" -> {
                    val totalCost  = this.costPrice * this.quantity + price * quantity
                    val newQty     = this.quantity + quantity
                    this.quantity  = newQty
                    val rawAvg     = if (newQty > 0) totalCost / newQty else price
                    // 与 Fmt.price() 保持相同精度：自动检测 2/3/4 位小数
                    this.costPrice = roundToMatchPrice(rawAvg)
                }
                "SELL" -> {
                    if (quantity > this.quantity + 1e-8) {
                        // 超卖：抛出异常让调用方展示错误提示
                        throw IllegalArgumentException("卖出数量(${quantity})超过当前持仓(${this.quantity})")
                    }
                    this.quantity = (this.quantity - quantity).coerceAtLeast(0.0)
                }
                "ADJUST" -> {
                    this.costPrice = price
                }
            }
            this.updatedAt = now
        }
        notifyDataChanged()
        return record
    }

    fun addTradeRecord(
        stockId: String, symbol: String, stockName: String,
        tradeType: String, price: Double, quantity: Double,
        tradeAt: Long, note: String = ""
    ): TradeRecordData {
        val now = System.currentTimeMillis()
        val r = TradeRecordData().apply {
            id = UUID.randomUUID().toString()
            this.stockId = stockId
            this.symbol = symbol
            this.stockName = stockName
            this.tradeType = tradeType
            this.price = price
            this.quantity = quantity
            this.note = note
            this.tradeAt = tradeAt
            this.createdAt = now
        }
        tradeRecords.add(r)
        return r
    }

    /**
     * 删除交易记录并从全部剩余记录重算持仓（数量 + 加权均价）。
     * 删除后按 tradeAt 升序重放所有 BUY/SELL/ADJUST 记录，
     * 确保 StockData 与历史记录始终一致。
     */
    fun deleteTradeRecord(id: String) {
        val rec = tradeRecords.find { it.id == id } ?: return
        tradeRecords.removeIf { it.id == id }
        recalcStockFromRecords(rec.stockId)
        notifyDataChanged()
    }

    /** 从该股票的所有交易记录重新推算持仓数量和成本价。 */
    private fun recalcStockFromRecords(stockId: String) {
        val stock = stocks.find { it.id == stockId } ?: return
        val sorted = tradeRecords
            .filter { it.stockId == stockId }
            .sortedBy { it.tradeAt }
        var qty  = 0.0
        var cost = 0.0
        for (r in sorted) {
            when (r.tradeType) {
                "BUY" -> {
                    val newQty = qty + r.quantity
                    cost = if (newQty > 0) (cost * qty + r.price * r.quantity) / newQty else r.price
                    qty  = newQty
                }
                "SELL"   -> qty  = (qty - r.quantity).coerceAtLeast(0.0)
                "ADJUST" -> cost = r.price
            }
        }
        stock.quantity  = qty
        stock.costPrice = if (qty > 0) roundToMatchPrice(cost) else 0.0
        stock.updatedAt = System.currentTimeMillis()
    }

    fun getTradeRecordsForStock(stockId: String): List<TradeRecordData> =
        tradeRecords.filter { it.stockId == stockId }.sortedByDescending { it.tradeAt }

    companion object {
        /**
         * 将均价 round 到与价格显示一致的精度（与 Fmt.price() 相同规则）：
         * - 差值 < 5e-4 时认为 2 位精度足够 → round 到 2 位
         * - 差值 < 5e-5 时认为 3 位精度足够 → round 到 3 位
         * - 否则 round 到 4 位（ETF / 基金 / 低价股）
         */
        fun roundToMatchPrice(v: Double): Double {
            val r2 = Math.round(v * 100) / 100.0
            val r3 = Math.round(v * 1000) / 1000.0
            return when {
                Math.abs(v - r2) < 5e-4 -> r2
                Math.abs(v - r3) < 5e-5 -> r3
                else -> Math.round(v * 10000) / 10000.0
            }
        }

        fun getInstance(): StockliteState =
            ApplicationManager.getApplication().getService(StockliteState::class.java)
    }
}
