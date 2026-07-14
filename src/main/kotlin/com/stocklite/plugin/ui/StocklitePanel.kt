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
    val usMarketPanel = UsMarketPanel()
    val apiLogPanel   = ApiLogPanel()

    private val tabs = JBTabbedPane()
    private val state = StockliteState.getInstance()

    private val usMarketTabIndex get() = tabs.indexOfComponent(usMarketPanel)
    private val apiLogTabIndex   get() = tabs.indexOfComponent(apiLogPanel)

    init {
        tabs.addTab(L10n.tabStock,  stockPanel)
        tabs.addTab(L10n.tabFund,   fundPanel)
        tabs.addTab(L10n.tabFuture, futurePanel)
        tabs.addTab(L10n.tabGlobal, globalPanel)

        if (state.enableUsMarketPanel) tabs.addTab(L10n.tabUsMarket, usMarketPanel)
        if (state.enableApiLogPanel)   tabs.addTab(L10n.tabApiLog,   apiLogPanel)

        tabs.addChangeListener {
            when (tabs.selectedComponent) {
                stockPanel    -> stockPanel.fetchQuotesAsync()
                fundPanel     -> fundPanel.fetchQuotesAsync()
                futurePanel   -> futurePanel.fetchQuotesAsync()
                globalPanel   -> globalPanel.fetchAsync()
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

    override fun onFeatureToggleChanged() {
        applyTabVisibility(usMarketPanel, state.enableUsMarketPanel, L10n.tabUsMarket)
        applyTabVisibility(apiLogPanel,   state.enableApiLogPanel,   L10n.tabApiLog)
    }

    override fun onLanguageChanged() {
        tabs.setTitleAt(0, L10n.tabStock)
        tabs.setTitleAt(1, L10n.tabFund)
        tabs.setTitleAt(2, L10n.tabFuture)
        tabs.setTitleAt(3, L10n.tabGlobal)
        val usIdx  = usMarketTabIndex
        val logIdx = apiLogTabIndex
        if (usIdx  >= 0) tabs.setTitleAt(usIdx,  L10n.tabUsMarket)
        if (logIdx >= 0) tabs.setTitleAt(logIdx, L10n.tabApiLog)
    }
}
