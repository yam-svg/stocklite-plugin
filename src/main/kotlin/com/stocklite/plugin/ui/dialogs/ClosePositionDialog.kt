package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 清仓确认对话框：卖出全部持仓。
 * - 卖出类型和数量只读（固定为 SELL + 全部持仓）
 * - 价格预填当前实时价，用户可修改
 * - 日期默认今天，用户可修改
 */
class ClosePositionDialog(
    private val stock: StockData,
    private val currentPrice: Double,
    private val onConfirm: (price: Double, tradeAt: Long, note: String) -> Unit
) : DialogWrapper(null, true) {

    private val priceField = JTextField(
        if (currentPrice > 0) "%.3f".format(currentPrice) else "", 12
    )
    private val dateField = JTextField(
        LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 12
    )
    private val noteField = JTextField("清仓", 12)

    init {
        title = L10n.dlgClosePosition
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { preferredSize = Dimension(380, 260) }
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6); anchor = GridBagConstraints.WEST
        }

        fun row(label: String, comp: JComponent, r: Int) {
            gbc.gridx = 0; gbc.gridy = r; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            panel.add(comp, gbc); gbc.weightx = 0.0
        }

        // 标题：股票名 + 代码
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JLabel(
            "<html><b>${stock.alias.ifBlank { stock.name }}</b>  " +
            "<span style='color:#888aaa'>${stock.symbol}</span></html>"
        ), gbc)
        gbc.gridwidth = 1

        // 只读行：类型 + 数量
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JLabel(L10n.dlgTradeTypeLbl), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(JLabel("<html><b style='color:#e05555'>${L10n.tradeTypeSell}</b></html>"), gbc)
        gbc.weightx = 0.0

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel(L10n.dlgTradeQtyLbl), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(JLabel(
            "<html><b>${Fmt.qty(stock.quantity)}</b>  " +
            "<span style='color:#888aaa'>（全部持仓）</span></html>"
        ), gbc); gbc.weightx = 0.0

        row(L10n.dlgTradePriceLbl, priceField, 3)
        row(L10n.dlgTradeDateLbl,  dateField,  4)
        row(L10n.dlgTradeNoteLbl,  noteField,  5)

        // 预览盈亏提示（有实时价时显示）
        if (currentPrice > 0 && stock.costPrice > 0) {
            val pnl     = (currentPrice - stock.costPrice) * stock.quantity
            val pnlPct  = (currentPrice - stock.costPrice) / stock.costPrice * 100
            val sign    = if (pnl >= 0) "+" else ""
            val color   = if (pnl >= 0) "#4caf50" else "#e05555"
            gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = Insets(8, 6, 4, 6)
            panel.add(JLabel(
                "<html><span style='color:#888aaa'>预计实现盈亏：</span>" +
                "<b style='color:$color'>$sign${Fmt.value(pnl)}" +
                "（$sign${"%.2f".format(pnlPct)}%）</b></html>"
            ), gbc)
            gbc.gridwidth = 1; gbc.insets = Insets(4, 6, 4, 6)
        }

        return panel
    }

    override fun doOKAction() {
        val price = priceField.text.trim().toDoubleOrNull()
        if (price == null || price <= 0) {
            JOptionPane.showMessageDialog(contentPane, L10n.validationTradePricePositive()); return
        }
        val tradeAt = try {
            LocalDate.parse(dateField.text.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(contentPane, L10n.validationTradeDateInvalid()); return
        }
        onConfirm(price, tradeAt, noteField.text.trim())
        super.doOKAction()
    }
}
