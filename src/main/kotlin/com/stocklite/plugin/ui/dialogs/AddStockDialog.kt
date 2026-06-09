package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.StockGroupData
import com.stocklite.plugin.state.StockSearchResult
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class AddStockDialog(
    private val groupId: String,
    private val groups: List<StockGroupData>,
    private val existingStock: StockData?,
    private val onSave: (symbol: String, name: String, groupId: String, costPrice: Double, qty: Double) -> Unit
) : DialogWrapper(null, true) {

    // 搜索区
    private val searchField  = JTextField(20)
    private val searchBtn    = JButton(L10n.btnSearch)
    private val resultModel  = DefaultListModel<StockSearchResult>()
    private val resultList   = JBList(resultModel)

    // 数据填写区
    private val symbolLabel  = JLabel("--")
    private val nameLabel    = JLabel("--")
    private val costField    = JTextField("0.00", 12)
    private val qtyField     = JTextField("0", 12)
    private val groupCombo   = JComboBox<String>()

    private var selectedResult: StockSearchResult? = null

    init {
        title = if (existingStock == null) L10n.dlgAddStock else L10n.dlgEditStock
        init()
        setupGroups()
        if (existingStock != null) prefillExisting()
        isOKActionEnabled = existingStock != null
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(480, 460)

        // 搜索栏（编辑模式下隐藏）
        if (existingStock == null) {
            val searchBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            searchBar.add(JLabel(L10n.dlgSearch))
            searchBar.add(searchField)
            searchBar.add(searchBtn)

            resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, _ ->
                JLabel("${value?.name}  (${value?.symbol})").apply {
                    isOpaque = true
                    if (isSelected) { background = UIManager.getColor("List.selectionBackground") }
                }
            }

            val searchPanel = JPanel(BorderLayout(4, 4))
            searchPanel.add(searchBar, BorderLayout.NORTH)
            searchPanel.add(JBScrollPane(resultList).apply { preferredSize = Dimension(460, 160) }, BorderLayout.CENTER)
            panel.add(searchPanel, BorderLayout.NORTH)

            resultList.addListSelectionListener {
                resultList.selectedValue?.let { r ->
                    selectedResult = r
                    symbolLabel.text = r.symbol
                    nameLabel.text   = r.name
                    isOKActionEnabled = true
                }
            }

            searchBtn.addActionListener { doSearch() }
            searchField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) doSearch()
                }
            })
        }

        // 表单
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST
        }

        fun row(label: String, comp: JComponent, r: Int) {
            gbc.gridx = 0; gbc.gridy = r; gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(comp, gbc)
            gbc.weightx = 0.0
        }

        row(L10n.dlgSymbolLbl, symbolLabel, 0)
        row(L10n.dlgNameLbl,   nameLabel,   1)
        row(L10n.dlgCostLbl,   costField,   2)
        row(L10n.dlgQtyLbl,    qtyField,    3)
        row(L10n.dlgGroupLbl,  groupCombo,  4)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun doSearch() {
        val kw = searchField.text.trim().ifEmpty { return }
        searchBtn.isEnabled = false
        resultModel.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = MarketDataService.searchStocks(kw)
            SwingUtilities.invokeLater {
                results.forEach { resultModel.addElement(it) }
                searchBtn.isEnabled = true
                if (results.isEmpty()) JOptionPane.showMessageDialog(null, L10n.dlgNoStockFound)
            }
        }
    }

    private fun setupGroups() {
        groups.forEach { groupCombo.addItem(it.name) }
        val idx = groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    private fun prefillExisting() {
        val s = existingStock!!
        symbolLabel.text = s.symbol
        nameLabel.text   = s.name
        costField.text   = s.costPrice.toString()
        qtyField.text    = s.quantity.toString()
        val idx = groups.indexOfFirst { it.id == s.groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    override fun doOKAction() {
        val symbol = existingStock?.symbol ?: selectedResult?.symbol ?: return
        val name   = existingStock?.name   ?: selectedResult?.name   ?: return
        val cost   = costField.text.toDoubleOrNull() ?: 0.0
        val qty    = qtyField.text.toDoubleOrNull()  ?: 0.0
        val gid    = groups.getOrNull(groupCombo.selectedIndex)?.id ?: groupId
        onSave(symbol, name, gid, cost, qty)
        super.doOKAction()
    }
}
