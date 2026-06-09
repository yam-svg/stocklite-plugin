package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class SetAlertDialog(
    private val symbol: String,
    private val name: String,
    private val currentPrice: Double,
    private val onConfirm: (targetPrice: Double, alertType: String) -> Unit
) : DialogWrapper(true) {

    private val targetField = JTextField("%.3f".format(currentPrice), 12)
    private val aboveRadio  = JRadioButton(L10n.dlgAlertAbove, true)
    private val belowRadio  = JRadioButton(L10n.dlgAlertBelow)

    init {
        title = L10n.dlgSetAlertTitle
        init()
    }

    override fun createCenterPanel(): JComponent {
        val group = ButtonGroup().also { it.add(aboveRadio); it.add(belowRadio) }
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8); anchor = GridBagConstraints.WEST
        }

        fun row(label: String, comp: JComponent, row: Int) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(comp, gbc)
            gbc.fill = GridBagConstraints.NONE
        }

        row(L10n.dlgAlertSymbol, JLabel("$name ($symbol)"), 0)
        row(L10n.dlgAlertTarget, targetField, 1)
        row(L10n.dlgAlertType, JPanel().also {
            it.add(aboveRadio); it.add(belowRadio)
        }, 2)

        return panel
    }

    override fun doOKAction() {
        val price = targetField.text.trim().toDoubleOrNull()
        if (price == null || price <= 0) {
            JOptionPane.showMessageDialog(contentPanel, L10n.validationAlertTargetPositive())
            return
        }
        val alertType = if (aboveRadio.isSelected) "ABOVE" else "BELOW"
        onConfirm(price, alertType)
        super.doOKAction()
    }
}
