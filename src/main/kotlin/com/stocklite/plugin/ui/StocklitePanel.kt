package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class StocklitePanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener {

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

    /** 按启用状态整体重建选项卡：组件实例复用不丢状态，只需恢复选中项 */
    private fun rebuildTabs() {
        val selected = tabs.selectedComponent
        tabs.removeAll()
        tabs.addTab(L10n.tabStock, stockPanel)
        optionalTabs.filter { it.enabled() }
            .forEach { tabs.addTab(it.label(), it.component) }
        val idx = tabs.indexOfComponent(selected)
        tabs.selectedIndex = if (idx >= 0) idx else 0
    }

    init {
        rebuildTabs()

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

    override fun onFeatureToggleChanged() = rebuildTabs()

    override fun onLanguageChanged() = rebuildTabs()
}
