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

    init {
        tabs.addTab(L10n.tabStock,  stockPanel)
        tabs.addTab(L10n.tabFund,   fundPanel)
        tabs.addTab(L10n.tabFuture, futurePanel)
        if (state.enableCryptoPanel) tabs.addTab(L10n.tabCrypto, cryptoPanel)
        tabs.addTab(L10n.tabGlobal, globalPanel)
        if (state.enableSectorPanel) tabs.addTab(L10n.tabSector, sectorPanel)

        if (state.enableIpoPanel)      tabs.addTab(L10n.tabIpo, ipoPanel)
        if (state.enableUsMarketPanel) tabs.addTab(L10n.tabUsMarket, usMarketPanel)
        if (state.enableApiLogPanel)   tabs.addTab(L10n.tabApiLog,   apiLogPanel)

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

    private fun applyTabVisibility(component: JComponent, enabled: Boolean, label: String) {
        val exists = tabs.indexOfComponent(component) >= 0
        when {
            enabled && !exists -> tabs.addTab(label, component)
            !enabled && exists -> {
                if (tabs.selectedComponent == component) tabs.selectedIndex = 0
                tabs.remove(component)
            }
        }
    }

    /** 加密 Tab 有固定位置（期货之后、全球之前），开关时插回原位而不是追加到末尾 */
    private fun applyCryptoTabVisibility(enabled: Boolean) {
        val exists = tabs.indexOfComponent(cryptoPanel) >= 0
        when {
            enabled && !exists -> {
                val idx = tabs.indexOfComponent(globalPanel).takeIf { it >= 0 } ?: tabs.tabCount
                tabs.insertTab(L10n.tabCrypto, null, cryptoPanel, null, idx)
            }
            !enabled && exists -> {
                if (tabs.selectedComponent == cryptoPanel) tabs.selectedIndex = 0
                tabs.remove(cryptoPanel)
            }
        }
    }

    override fun onFeatureToggleChanged() {
        applyCryptoTabVisibility(state.enableCryptoPanel)
        applyTabVisibility(sectorPanel,   state.enableSectorPanel,   L10n.tabSector)
        applyTabVisibility(ipoPanel,      state.enableIpoPanel,      L10n.tabIpo)
        applyTabVisibility(usMarketPanel, state.enableUsMarketPanel, L10n.tabUsMarket)
        applyTabVisibility(apiLogPanel,   state.enableApiLogPanel,   L10n.tabApiLog)
    }

    override fun onLanguageChanged() {
        // 统一按组件反查下标设置标题：标签页有可开关项，顺序会变，硬编码下标在加密页开/关时会错位
        val titles = listOf(
            stockPanel    to L10n.tabStock,
            fundPanel     to L10n.tabFund,
            futurePanel   to L10n.tabFuture,
            cryptoPanel   to L10n.tabCrypto,
            globalPanel   to L10n.tabGlobal,
            sectorPanel   to L10n.tabSector,
            ipoPanel      to L10n.tabIpo,
            usMarketPanel to L10n.tabUsMarket,
            apiLogPanel   to L10n.tabApiLog,
        )
        titles.forEach { (comp, title) ->
            val idx = tabs.indexOfComponent(comp)
            if (idx >= 0) tabs.setTitleAt(idx, title)
        }
    }
}
