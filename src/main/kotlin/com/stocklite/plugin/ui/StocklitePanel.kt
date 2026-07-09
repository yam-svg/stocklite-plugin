package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import javax.swing.JPanel

class StocklitePanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener {

    val stockPanel    = StockPanel()
    val fundPanel     = FundPanel()
    val futurePanel   = FuturePanel()
    val globalPanel   = GlobalPanel()
    val usMarketPanel = UsMarketPanel()

    private val tabs = JBTabbedPane()
    private val state = StockliteState.getInstance()

    // 固定前4个 Tab 索引；美股板块是否存在由 usMarketTabIndex 动态决定
    private val usMarketTabIndex get() = if (tabs.indexOfComponent(usMarketPanel) >= 0)
        tabs.indexOfComponent(usMarketPanel) else -1

    init {
        tabs.addTab(L10n.tabStock,  stockPanel)
        tabs.addTab(L10n.tabFund,   fundPanel)
        tabs.addTab(L10n.tabFuture, futurePanel)
        tabs.addTab(L10n.tabGlobal, globalPanel)

        // 根据设置决定是否初始加入美股板块 Tab
        if (state.enableUsMarketPanel) {
            tabs.addTab(L10n.tabUsMarket, usMarketPanel)
        }

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

    /** 由外部（Settings apply 后）调用，同步美股板块 Tab 的显示状态 */
    fun applyUsMarketPanelVisibility() {
        val enabled = state.enableUsMarketPanel
        val exists  = usMarketTabIndex >= 0
        when {
            enabled && !exists -> tabs.addTab(L10n.tabUsMarket, usMarketPanel)
            !enabled && exists -> {
                // 切走再移除，避免移除当前选中 Tab 导致异常
                if (tabs.selectedComponent == usMarketPanel) tabs.selectedIndex = 0
                tabs.remove(usMarketPanel)
            }
        }
    }

    override fun onFeatureToggleChanged() {
        applyUsMarketPanelVisibility()
    }

    override fun onLanguageChanged() {
        tabs.setTitleAt(0, L10n.tabStock)
        tabs.setTitleAt(1, L10n.tabFund)
        tabs.setTitleAt(2, L10n.tabFuture)
        tabs.setTitleAt(3, L10n.tabGlobal)
        val idx = usMarketTabIndex
        if (idx >= 0) tabs.setTitleAt(idx, L10n.tabUsMarket)
    }
}
