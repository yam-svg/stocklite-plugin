package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.util.L10n
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 全球指数显示选择对话框：勾选 = 显示。
 * 两列网格排布全部 GLOBAL_INDEXES，附全选/全不选快捷键式按钮。
 */
class GlobalIndexPickerDialog(hidden: Set<String>) : DialogWrapper(null, true) {

    private val boxes = MarketDataService.GLOBAL_INDEXES.associate {
        it.symbol to JBCheckBox("${it.nameCn} (${it.nameEn})", it.symbol !in hidden)
    }

    init {
        title = L10n.globalIndexPickerTitle
        init()
    }

    /** 用户取消选择（未勾选）的 symbol 集合 */
    fun hiddenSymbols(): Set<String> = boxes.filterValues { !it.isSelected }.keys

    override fun createCenterPanel(): JComponent {
        val grid = JPanel(GridLayout(0, 2, 12, 4))
        boxes.values.forEach { grid.add(it) }
        val root = JPanel(java.awt.BorderLayout(0, 6))
        val quick = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        quick.add(JButton(L10n.btnSelectAll).apply {
            addActionListener { boxes.values.forEach { it.isSelected = true } }
        })
        quick.add(JButton(L10n.biClearAll).apply {
            addActionListener { boxes.values.forEach { it.isSelected = false } }
        })
        root.add(quick, java.awt.BorderLayout.NORTH)
        root.add(grid, java.awt.BorderLayout.CENTER)
        return root
    }
}
