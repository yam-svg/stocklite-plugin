package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.StealthChrome
import com.stocklite.plugin.util.StealthName
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class StocklitePanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener,
    StockliteState.StealthListener {

    val stockPanel    = StockPanel()
    val fundPanel     = FundPanel()
    val futurePanel   = FuturePanel()
    val globalPanel   = GlobalPanel()
    val sectorPanel   = SectorPanel()
    val cryptoPanel   = CryptoPanel()
    val ipoPanel      = IpoPanel()
    val usMarketPanel = UsMarketPanel()
    val apiLogPanel   = ApiLogPanel()

    private val tabs = JBTabbedPane()
    private val state = StockliteState.getInstance()

    /** 除股票外的标签页规格：固定展示顺序 + 各自的开关与标题（股票固定第 0 位不可关闭） */
    private data class TabSpec(val component: JComponent, val enabled: () -> Boolean, val label: () -> String)

    private val optionalTabs = listOf(
        TabSpec(fundPanel,     { state.enableFundPanel },     { L10n.tabFund }),
        TabSpec(futurePanel,   { state.enableFuturePanel },   { L10n.tabFuture }),
        TabSpec(cryptoPanel,   { state.enableCryptoPanel },   { L10n.tabCrypto }),
        TabSpec(globalPanel,   { state.enableGlobalPanel },   { L10n.tabGlobal }),
        TabSpec(sectorPanel,   { state.enableSectorPanel },   { L10n.tabSector }),
        TabSpec(ipoPanel,      { state.enableIpoPanel },      { L10n.tabIpo }),
        TabSpec(usMarketPanel, { state.enableUsMarketPanel }, { L10n.tabUsMarket }),
        TabSpec(apiLogPanel,   { state.enableApiLogPanel },   { L10n.tabApiLog }),
    )

    /** 按清单顺序把组件插回规范位置：排在它之后且当前存在的第一个组件之前 */
    private fun insertTabAtCanonicalPosition(spec: TabSpec) {
        val idx = optionalTabs.indexOf(spec)
        var target = tabs.tabCount
        for (other in optionalTabs.drop(idx + 1)) {
            val oi = tabs.indexOfComponent(other.component)
            if (oi >= 0) { target = oi; break }
        }
        tabs.insertTab(spec.label(), null, spec.component, null, target)
    }

    /**
     * 增量增删单个 Tab。不能整表 removeAll 重建：所有组件被瞬时摘除会让内容首选尺寸
     * 塌成接近 0，IDE 工具窗口随之收缩且不会随组件恢复而回弹，表现为每开关一次缩一圈。
     */
    private fun syncTabsFromToggles() {
        for (spec in optionalTabs) {
            val exists = tabs.indexOfComponent(spec.component) >= 0
            when {
                spec.enabled() && !exists -> insertTabAtCanonicalPosition(spec)
                !spec.enabled() && exists -> {
                    if (tabs.selectedComponent == spec.component) tabs.selectedIndex = 0
                    tabs.remove(spec.component)
                }
            }
        }
    }

    init {
        tabs.addTab(L10n.tabStock, stockPanel)
        optionalTabs.filter { it.enabled() }.forEach { tabs.addTab(it.label(), it.component) }

        tabs.addChangeListener {
            when (tabs.selectedComponent) {
                stockPanel    -> stockPanel.fetchQuotesAsync()
                fundPanel     -> fundPanel.fetchQuotesAsync()
                futurePanel   -> futurePanel.fetchQuotesAsync()
                globalPanel   -> globalPanel.fetchAsync()
                sectorPanel   -> sectorPanel.fetchAsync()
                cryptoPanel   -> cryptoPanel.fetchQuotesAsync()
                ipoPanel      -> ipoPanel.fetchAsync()
                usMarketPanel -> usMarketPanel.fetchAsync()
            }
        }

        add(tabs, BorderLayout.CENTER)
        state.addLanguageListener(this)
        state.addFeatureToggleListener(this)
        state.addStealthListener(this)
        // 重开工具窗口时持久化的隐秘设置立即生效（构建期文案都是原文）
        refreshTabTitles(); StealthChrome.refresh(this)
    }

    /** 隐秘模式切换：各页表格/卡片重刷（颜色由渲染器实时读取，重绘即生效） */
    override fun onStealthChanged() {
        stockPanel.refreshGroups();  fundPanel.refreshGroups()
        futurePanel.refreshGroups(); cryptoPanel.refreshGroups()
        globalPanel.stealthRefresh(); sectorPanel.stealthRefresh()
        ipoPanel.stealthRefresh();    usMarketPanel.stealthRefresh()
        refreshTabTitles(); StealthChrome.refresh(this)
    }

    /**
     * 标签页标题刷新：始终从 L10n 取原文再按当前隐秘档位转换，
     * 语言切换与隐秘切换共用（原文源唯一，不存在叠加转换）。
     */
    private fun refreshTabTitles() {
        val titles = (listOf(stockPanel to L10n.tabStock) +
            optionalTabs.map { it.component to it.label() })
        titles.forEach { (comp, title) ->
            val idx = tabs.indexOfComponent(comp)
            if (idx >= 0) tabs.setTitleAt(idx, StealthName.uiText(title))
        }
    }

    /**
     * 供新手指引跳转标签页：按组件反查下标（可开关 tab 顺序会变，不能按下标定位）。
     * @return false 表示该标签页因功能开关未启用而不在选项卡中，调用方应改为引导去设置开启
     */
    fun selectTab(component: JComponent): Boolean {
        val idx = tabs.indexOfComponent(component)
        if (idx < 0) return false
        tabs.selectedIndex = idx
        return true
    }

    override fun onFeatureToggleChanged() = syncTabsFromToggles()

    /**
     * 工具窗口内容销毁时摘除一切跨重建存活的注册（ApiLogger 是 object、State 是服务，
     * 不清理会让旧面板永久滞留并跟随每次日志/语言/开关事件空转重绘）。
     */
    fun dispose() {
        stockPanel.dispose();    fundPanel.dispose();    futurePanel.dispose()
        globalPanel.dispose();   sectorPanel.dispose();  cryptoPanel.dispose()
        ipoPanel.dispose();      usMarketPanel.dispose(); apiLogPanel.dispose()
        state.removeLanguageListener(this)
        state.removeFeatureToggleListener(this)
        state.removeStealthListener(this)
    }

    // 只改标题不动结构：增删/重建会导致工具窗口尺寸抖动
    override fun onLanguageChanged() { refreshTabTitles() }
}
