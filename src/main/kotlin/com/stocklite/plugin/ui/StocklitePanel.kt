package com.stocklite.plugin.ui

import com.intellij.ui.components.JBTabbedPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
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

        applySavedTabOrder()
        enableTabDragReorder()

        add(tabs, BorderLayout.CENTER)
        state.addLanguageListener(this)
        state.addFeatureToggleListener(this)
    }

    // ══════════════════ 标签页拖拽排序 ══════════════════

    private fun tabKey(c: java.awt.Component): String = when (c) {
        stockPanel    -> "stock"
        fundPanel     -> "fund"
        futurePanel   -> "future"
        cryptoPanel   -> "crypto"
        globalPanel   -> "global"
        sectorPanel   -> "sector"
        ipoPanel      -> "ipo"
        usMarketPanel -> "usMarket"
        apiLogPanel   -> "apiLog"
        else          -> ""
    }

    private fun saveTabOrder() {
        state.toolWindowTabOrder = (0 until tabs.tabCount)
            .mapNotNull { tabKey(tabs.getComponentAt(it)).takeIf { k -> k.isNotEmpty() } }
            .joinToString(",")
    }

    /** 启动时按上次拖动保存的顺序重排；不在记录里的（新开关出的）标签保持原位排最后 */
    private fun applySavedTabOrder() {
        val order = state.toolWindowTabOrder.split(",").filter { it.isNotEmpty() }
        if (order.isEmpty() || tabs.tabCount < 2) return
        data class Entry(val comp: java.awt.Component, val title: String, val rank: Int, val idx: Int)
        val entries = (0 until tabs.tabCount).map { i ->
            val c = tabs.getComponentAt(i)
            val rank = order.indexOf(tabKey(c)).let { if (it >= 0) it else Int.MAX_VALUE }
            Entry(c, tabs.getTitleAt(i), rank, i)
        }
        val sorted = entries.sortedWith(compareBy({ it.rank }, { it.idx }))
        if (sorted.map { it.comp } == entries.map { it.comp }) return
        val selComp = tabs.selectedComponent
        while (tabs.tabCount > 0) tabs.removeTabAt(0)
        sorted.forEach { tabs.addTab(it.title, it.comp) }
        val selIdx = sorted.indexOfFirst { it.comp === selComp }
        if (selIdx >= 0) tabs.selectedIndex = selIdx
    }

    /** 按住标签横向拖到另一个标签位置即换位；顺序实时持久化，重启后保持 */
    private fun enableTabDragReorder() {
        var from = -1
        tabs.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  { from = tabs.indexAtLocation(e.x, e.y) }
            override fun mouseReleased(e: MouseEvent) {
                from = -1
                tabs.cursor = Cursor.getDefaultCursor()
            }
        })
        tabs.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (from < 0 || from >= tabs.tabCount) return
                val to = tabs.indexAtLocation(e.x, e.y)
                if (to >= 0 && to != from) {
                    val comp  = tabs.getComponentAt(from)
                    val title = tabs.getTitleAt(from)
                    tabs.removeTabAt(from)
                    tabs.insertTab(title, null, comp, null, to)
                    tabs.selectedIndex = to
                    from = to
                    saveTabOrder()
                }
            }
            override fun mouseMoved(e: MouseEvent) {
                // 标签条上显示移动光标，提示可拖拽
                tabs.cursor = if (tabs.indexAtLocation(e.x, e.y) >= 0)
                    Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else Cursor.getDefaultCursor()
            }
        })
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
