package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * 全球指数显示选择对话框，分两个 Tab：
 * - 常用指数：勾选 = 显示（默认全勾选，取消即隐藏）；
 * - 更多指数：扩展市场指数，默认全不勾选，勾选 = 显式开启显示。
 * "全选/全不选"作用于当前选中的 Tab。
 */
class GlobalIndexPickerDialog(hidden: Set<String>, enabledOptional: Set<String>) : DialogWrapper(null, true) {

    private fun boxesFor(items: List<MarketDataService.GlobalIndexConfig>, checked: (String) -> Boolean) =
        items.associate { it.symbol to JBCheckBox("${it.nameCn} (${it.nameEn})", checked(it.symbol)) }

    private val mainBoxes = boxesFor(MarketDataService.GLOBAL_INDEXES.filterNot { it.optional }) { it !in hidden }
    private val optBoxes  = boxesFor(MarketDataService.GLOBAL_INDEXES.filter     { it.optional }) { it in  enabledOptional }
    private val tabs      = JTabbedPane()

    init {
        title = L10n.globalIndexPickerTitle
        init()
    }

    /** 常用区中被取消（隐藏）的 symbol 集合 */
    fun hiddenSymbols(): Set<String> = mainBoxes.filterValues { !it.isSelected }.keys
    /** 更多区中被显式开启的 symbol 集合 */
    fun enabledOptionalSymbols(): Set<String> = optBoxes.filterValues { it.isSelected }.keys

    private fun gridOf(boxes: Map<String, JBCheckBox>): JPanel =
        JPanel(GridLayout(0, 2, 12, 4)).apply { boxes.values.forEach { add(it) } }

    override fun createCenterPanel(): JComponent {
        tabs.addTab(L10n.globalIndexTabMain, gridOf(mainBoxes))
        tabs.addTab(L10n.globalIndexTabMore, gridOf(optBoxes))
        val root = JPanel(BorderLayout(0, 6))
        val quick = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        fun currentBoxes() = if (tabs.selectedIndex == 0) mainBoxes else optBoxes
        quick.add(JButton(L10n.btnSelectAll).apply {
            addActionListener { currentBoxes().values.forEach { it.isSelected = true } }
        })
        quick.add(JButton(L10n.biClearAll).apply {
            addActionListener { currentBoxes().values.forEach { it.isSelected = false } }
        })
        root.add(quick, BorderLayout.NORTH)
        root.add(tabs,  BorderLayout.CENTER)
        return root
    }
}
