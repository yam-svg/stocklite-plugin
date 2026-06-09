package com.stocklite.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class StockliteConfigurable : Configurable {

    // 语言选择单选组
    private val langRadios = linkedMapOf(
        "ZH" to JRadioButton(L10n.settingsLangZh),
        "EN" to JRadioButton(L10n.settingsLangEn)
    )
    private val langGroup = ButtonGroup().also { g -> langRadios.values.forEach { g.add(it) } }

    // 股票可选列（key → 显示标签）
    private val stockOptional get() = listOf(
        "symbol"      to L10n.settingsOptStockSymbol,
        "quantity"    to L10n.settingsOptStockQty,
        "cost"        to L10n.settingsOptStockCost,
        "marketValue" to L10n.settingsOptStockValue,
        "pnl"         to L10n.settingsOptStockPnl,
        "pnlPercent"  to L10n.settingsOptStockPnlPct
    )

    // 基金可选列
    private val fundOptional get() = listOf(
        "code"        to L10n.settingsOptFundCode,
        "shares"      to L10n.settingsOptFundShares,
        "costNav"     to L10n.settingsOptFundCostNav,
        "marketValue" to L10n.settingsOptFundValue,
        "pnl"         to L10n.settingsOptFundPnl,
        "pnlPercent"  to L10n.settingsOptFundPnlPct
    )

    private val stockBoxes = mutableMapOf<String, JBCheckBox>()
    private val fundBoxes  = mutableMapOf<String, JBCheckBox>()

    // 颜色方案单选组
    private val colorRadios = linkedMapOf(
        "RED_UP"   to JRadioButton(L10n.settingsRedUp),
        "RED_DOWN" to JRadioButton(L10n.settingsRedDown),
        "NONE"     to JRadioButton(L10n.settingsNoColor)
    )
    private val colorGroup = ButtonGroup().also { g -> colorRadios.values.forEach { g.add(it) } }

    override fun getDisplayName() = "StockLite"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 8, 2, 8)
            anchor = GridBagConstraints.WEST
            fill   = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx   = 0
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

        // ── 界面语言 ──
        sep(L10n.settingsLanguage, 0)
        var row = 2
        langRadios.values.forEach { rb -> gbc.gridy = row++; gbc.insets = Insets(2, 8, 2, 8); panel.add(rb, gbc) }

        // ── 颜色方案 ──
        sep(L10n.settingsColorScheme, row++); row++
        colorRadios.values.forEach { rb -> gbc.gridy = row++; gbc.insets = Insets(2, 8, 2, 8); panel.add(rb, gbc) }

        // ── 股票列 ──
        sep(L10n.settingsStockCols, row++); row++
        alwaysRow(L10n.settingsStockName,   row++)
        alwaysRow(L10n.settingsStockPrice,  row++)
        alwaysRow(L10n.settingsStockChange, row++)
        for ((key, label) in stockOptional) optionalRow(key, label, stockBoxes, row++)

        // ── 基金列 ──
        sep(L10n.settingsFundCols, row++); row++
        alwaysRow(L10n.settingsFundName,       row++)
        alwaysRow(L10n.settingsFundNav,         row++)
        alwaysRow(L10n.settingsFundOfficialChg, row++)
        alwaysRow(L10n.settingsFundNavDate,     row++)
        alwaysRow(L10n.settingsFundTodayEst,    row++)
        for ((key, label) in fundOptional) optionalRow(key, label, fundBoxes, row++)

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
        val selectedLang   = langRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "ZH"
        return stockBoxes.any { (k, cb) -> cb.isSelected != sv.contains(k) } ||
               fundBoxes.any  { (k, cb) -> cb.isSelected != fv.contains(k) } ||
               selectedScheme != state.colorScheme ||
               selectedLang   != state.language
    }

    override fun apply() {
        val state = StockliteState.getInstance()
        state.stockVisibleColumns = ArrayList(stockBoxes.filter { it.value.isSelected }.keys.sorted())
        state.fundVisibleColumns  = ArrayList(fundBoxes.filter  { it.value.isSelected }.keys.sorted())
        state.colorScheme = colorRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "RED_UP"
        state.language    = langRadios.entries.firstOrNull  { it.value.isSelected }?.key ?: "ZH"
        // 通知各面板刷新列设置和语言
        state.notifyColumnSettingsChanged()
        state.notifyLanguageChanged()
    }

    override fun reset() {
        val state = StockliteState.getInstance()
        val sv = state.stockVisibleColumns.toSet()
        val fv = state.fundVisibleColumns.toSet()
        stockBoxes.forEach { (k, cb) -> cb.isSelected = k in sv }
        fundBoxes.forEach  { (k, cb) -> cb.isSelected = k in fv }
        (colorRadios[state.colorScheme] ?: colorRadios["RED_UP"])?.isSelected = true
        (langRadios[state.language]     ?: langRadios["ZH"])?.isSelected = true
    }
}
