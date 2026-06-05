package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

class StocklitePanel : JPanel(BorderLayout()) {

    val stockPanel  = StockPanel()
    val fundPanel   = FundPanel()
    val futurePanel = FuturePanel()
    val globalPanel = GlobalPanel()

    init {
        val tabs = JBTabbedPane()
        tabs.addTab("股票", stockPanel)
        tabs.addTab("基金", fundPanel)
        tabs.addTab("期货", futurePanel)
        tabs.addTab("全球", globalPanel)

        tabs.addChangeListener {
            when (tabs.selectedIndex) {
                0 -> stockPanel.fetchQuotesAsync()
                1 -> fundPanel.fetchQuotesAsync()
                2 -> futurePanel.fetchQuotesAsync()
                3 -> globalPanel.fetchAsync()
            }
        }

        add(tabs, BorderLayout.CENTER)
    }
}
