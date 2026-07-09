package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

class AddTradeRecordDialog(
    private val stock: StockData,
    private val onSave: (tradeType: String, price: Double, quantity: Double, tradeAt: Long, note: String) -> Unit
) : DialogWrapper(null, true) {

    private val typeCombo  = JComboBox(arrayOf(L10n.tradeTypeBuy, L10n.tradeTypeSell))
    private val priceField = JTextField("", 12)
    private val qtyField   = JTextField("", 12)
    private val dateField  = JTextField(
        LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 12
    )
    private val noteField  = JTextField("", 12)
    private val hintLabel  = JLabel().apply {
        font = font.deriveFont(10.5f)
        foreground = Color(0x888aaa)
    }

    init {
        title = L10n.dlgAddTradeTitle
        // 初始化提示文字（对应默认选中的"买入"）
        updateHint(0)
        typeCombo.addActionListener { updateHint(typeCombo.selectedIndex) }
        init()
    }

    private fun updateHint(typeIndex: Int) {
        val html = if (typeIndex == 0) {
            // 买入
            """<html><div style='width:300px;color:#888aaa'>
            <b style='color:#aaaacc'>买入</b>：增加持仓数量，自动重算加权平均成本价。<br>
            例：持有 100 股成本 50，再买 50 股 @ 60，<br>
            新均价 = (100×50 + 50×60) / 150 = <b style='color:#aaaacc'>53.33</b>
            </div></html>"""
        } else {
            // 卖出
            """<html><div style='width:300px;color:#888aaa'>
            <b style='color:#aaaacc'>卖出</b>：减少持仓数量，成本价保持不变。<br>
            今日盈亏 = (现价 - 成本价) × 持仓数量，<br>
            卖出本身不影响成本，仅减少持有份额。
            </div></html>"""
        }
        hintLabel.text = html
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6); anchor = GridBagConstraints.WEST
        }
        fun row(label: String, comp: JComponent, r: Int) {
            gbc.gridx = 0; gbc.gridy = r; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            form.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(comp, gbc)
        }
        // 标题行：股票名+代码
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL
        form.add(JLabel("<html><b>${stock.alias.ifBlank { stock.name }}</b>  <span style='color:#888aaa'>${stock.symbol}</span></html>"), gbc)
        gbc.gridwidth = 1

        row(L10n.dlgTradeTypeLbl,  typeCombo,  1)
        row(L10n.dlgTradePriceLbl, priceField, 2)
        row(L10n.dlgTradeQtyLbl,   qtyField,   3)
        row(L10n.dlgTradeDateLbl,  dateField,  4)
        row(L10n.dlgTradeNoteLbl,  noteField,  5)

        // 说明区域
        val hintPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0x3a3a5a)),
                BorderFactory.createEmptyBorder(8, 6, 4, 6)
            )
            add(hintLabel, BorderLayout.CENTER)
        }

        val panel = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = Dimension(380, 310)
            add(form,      BorderLayout.CENTER)
            add(hintPanel, BorderLayout.SOUTH)
        }
        return panel
    }

    override fun doOKAction() {
        val price = priceField.text.trim().toDoubleOrNull()
        if (price == null || price <= 0) {
            JOptionPane.showMessageDialog(contentPane, L10n.validationTradePricePositive()); return
        }
        val qty = qtyField.text.trim().toDoubleOrNull()
        if (qty == null || qty <= 0) {
            JOptionPane.showMessageDialog(contentPane, L10n.validationTradeQtyPositive()); return
        }
        val dateStr = dateField.text.trim()
        val tradeAt = try {
            LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(contentPane, L10n.validationTradeDateInvalid()); return
        }
        val typeStr = if (typeCombo.selectedIndex == 0) "BUY" else "SELL"
        onSave(typeStr, price, qty, tradeAt, noteField.text.trim())
        super.doOKAction()
    }
}
