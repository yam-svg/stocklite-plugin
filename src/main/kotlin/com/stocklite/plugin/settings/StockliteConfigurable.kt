package com.stocklite.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.state.StockliteState
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class StockliteConfigurable : Configurable {

    // ── 股票可选列 ──
    private val STOCK_OPTIONAL = listOf(
        "symbol"      to "代码",
        "quantity"    to "持仓数量",
        "cost"        to "成本价",
        "marketValue" to "市值",
        "pnl"         to "盈亏",
        "pnlPercent"  to "盈亏%"
    )

    // ── 基金可选列（"当前净值""昨日涨跌""今日估算"始终显示，不在此列） ──
    private val FUND_OPTIONAL = listOf(
        "code"        to "代码",
        "shares"      to "持仓份额",
        "costNav"     to "成本净值",
        "marketValue" to "市值",
        "pnl"         to "盈亏",
        "pnlPercent"  to "盈亏%"
    )

    private val stockBoxes = mutableMapOf<String, JBCheckBox>()
    private val fundBoxes  = mutableMapOf<String, JBCheckBox>()

    // 颜色方案单选组
    private val colorRadios = linkedMapOf(
        "RED_UP"   to JRadioButton("红涨绿跌（中国惯例）"),
        "RED_DOWN" to JRadioButton("绿涨红跌（欧美惯例）"),
        "NONE"     to JRadioButton("无颜色")
    )
    private val colorGroup = ButtonGroup().also { g -> colorRadios.values.forEach { g.add(it) } }

    override fun getDisplayName() = "StockLite"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 8, 2, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
        }

        fun sep(text: String, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(12, 8, 2, 8)
            panel.add(JSeparator(), gbc)
            gbc.gridy = row + 1; gbc.insets = Insets(2, 8, 4, 8)
            panel.add(JLabel("<html><b>$text</b></html>"), gbc)
        }

        fun alwaysRow(label: String, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(2, 8, 2, 8)
            panel.add(JBCheckBox(label).apply { isSelected = true; isEnabled = false }, gbc)
        }

        fun optionalRow(key: String, label: String, boxes: MutableMap<String, JBCheckBox>, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(2, 8, 2, 8)
            val cb = JBCheckBox(label); boxes[key] = cb; panel.add(cb, gbc)
        }

        // ── 颜色方案 ──
        sep("涨跌幅颜色", 0)
        var row = 2
        colorRadios.values.forEach { rb -> gbc.gridy = row++; gbc.insets = Insets(2, 8, 2, 8); panel.add(rb, gbc) }

        // ── 股票列 ──
        sep("股票列设置", row++); row++
        alwaysRow("名称（始终显示）", row++)
        alwaysRow("现价（始终显示）", row++)
        alwaysRow("涨跌幅（始终显示）", row++)
        for ((key, label) in STOCK_OPTIONAL) optionalRow(key, label, stockBoxes, row++)

        // ── 基金列 ──
        sep("基金列设置", row++); row++
        alwaysRow("名称（始终显示）", row++)
        alwaysRow("当前净值（始终显示）", row++)
        alwaysRow("官方涨跌（始终显示）", row++)
        alwaysRow("净值日期（始终显示，日期变今天即说明已更新）", row++)
        alwaysRow("今日估算（始终显示，官方净值更新后显示 官方✓）", row++)
        for ((key, label) in FUND_OPTIONAL) optionalRow(key, label, fundBoxes, row++)

        // 撑开底部
        gbc.gridy = row; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        reset()
        return JBScrollPane(panel).apply { border = null }
    }

    override fun isModified(): Boolean {
        val state = StockliteState.getInstance()
        val sv = state.stockVisibleColumns.toSet()
        val fv = state.fundVisibleColumns.toSet()
        val selectedScheme = colorRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "RED_UP"
        return stockBoxes.any { (k, cb) -> cb.isSelected != sv.contains(k) } ||
               fundBoxes.any  { (k, cb) -> cb.isSelected != fv.contains(k) } ||
               selectedScheme != state.colorScheme
    }

    override fun apply() {
        val state = StockliteState.getInstance()
        state.stockVisibleColumns = ArrayList(stockBoxes.filter { it.value.isSelected }.keys.sorted())
        state.fundVisibleColumns  = ArrayList(fundBoxes.filter  { it.value.isSelected }.keys.sorted())
        state.colorScheme = colorRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "RED_UP"
        state.notifyColumnSettingsChanged()
    }

    override fun reset() {
        val state = StockliteState.getInstance()
        val sv = state.stockVisibleColumns.toSet()
        val fv = state.fundVisibleColumns.toSet()
        stockBoxes.forEach { (k, cb) -> cb.isSelected = k in sv }
        fundBoxes.forEach  { (k, cb) -> cb.isSelected = k in fv }
        (colorRadios[state.colorScheme] ?: colorRadios["RED_UP"])?.isSelected = true
    }
}
