package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import javax.swing.JPanel

class StocklitePanel : JPanel(BorderLayout()), StockliteState.LanguageListener {

    val stockPanel    = StockPanel()
    val fundPanel     = FundPanel()
    val futurePanel   = FuturePanel()
    val globalPanel   = GlobalPanel()
    val usMarketPanel = UsMarketPanel()

    private val tabs = JBTabbedPane()

    init {
        tabs.addTab(L10n.tabStock,    stockPanel)
        tabs.addTab(L10n.tabFund,     fundPanel)
        tabs.addTab(L10n.tabFuture,   futurePanel)
        tabs.addTab(L10n.tabGlobal,   globalPanel)
        tabs.addTab(L10n.tabUsMarket, usMarketPanel)

        tabs.addChangeListener {
            when (tabs.selectedIndex) {
                0 -> stockPanel.fetchQuotesAsync()
                1 -> fundPanel.fetchQuotesAsync()
                2 -> futurePanel.fetchQuotesAsync()
                3 -> globalPanel.fetchAsync()
                4 -> usMarketPanel.fetchAsync()
            }
        }

        add(tabs, BorderLayout.CENTER)
        StockliteState.getInstance().addLanguageListener(this)
    }

    override fun onLanguageChanged() {
        // 子面板已各自注册了 LanguageListener，此处只更新标签页标题
        tabs.setTitleAt(0, L10n.tabStock)
        tabs.setTitleAt(1, L10n.tabFund)
        tabs.setTitleAt(2, L10n.tabFuture)
        tabs.setTitleAt(3, L10n.tabGlobal)
        tabs.setTitleAt(4, L10n.tabUsMarket)
    }
}
