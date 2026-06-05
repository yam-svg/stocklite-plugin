package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.FundData
import com.stocklite.plugin.state.FundGroupData
import com.stocklite.plugin.state.FundSearchResult
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class AddFundDialog(
    private val groupId: String,
    private val groups: List<FundGroupData>,
    private val existingFund: FundData?,
    private val onSave: (code: String, name: String, groupId: String, costNav: Double, shares: Double) -> Unit
) : DialogWrapper(null, true) {

    private val searchField  = JTextField(20)
    private val searchBtn    = JButton("搜索")
    private val resultModel  = DefaultListModel<FundSearchResult>()
    private val resultList   = JBList(resultModel)

    private val codeLabel    = JLabel("--")
    private val nameLabel    = JLabel("--")
    private val costNavField = JTextField("1.0000", 12)
    private val sharesField  = JTextField("0", 12)
    private val groupCombo   = JComboBox<String>()

    private var selectedResult: FundSearchResult? = null

    init {
        title = if (existingFund == null) "添加基金" else "编辑基金"
        init()
        setupGroups()
        if (existingFund != null) prefillExisting()
        isOKActionEnabled = existingFund != null
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(480, 440)

        if (existingFund == null) {
            val searchBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            searchBar.add(JLabel("搜索:"))
            searchBar.add(searchField)
            searchBar.add(searchBtn)

            resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
            resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, _ ->
                JLabel("${value?.name}  (${value?.code})").apply {
                    isOpaque = true
                    if (isSelected) background = UIManager.getColor("List.selectionBackground")
                }
            }

            val searchPanel = JPanel(BorderLayout(4, 4))
            searchPanel.add(searchBar, BorderLayout.NORTH)
            searchPanel.add(JBScrollPane(resultList).apply { preferredSize = Dimension(460, 150) }, BorderLayout.CENTER)
            panel.add(searchPanel, BorderLayout.NORTH)

            resultList.addListSelectionListener {
                resultList.selectedValue?.let { r ->
                    selectedResult = r
                    codeLabel.text = r.code
                    nameLabel.text = r.name
                    isOKActionEnabled = true
                }
            }
            searchBtn.addActionListener { doSearch() }
            searchField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) doSearch() }
            })
        }

        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(4,4,4,4); anchor = GridBagConstraints.WEST }
        fun row(label: String, comp: JComponent, r: Int) {
            gbc.gridx = 0; gbc.gridy = r; gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(comp, gbc); gbc.weightx = 0.0
        }
        row("基金代码:", codeLabel,    0)
        row("基金名称:", nameLabel,    1)
        row("成本净值:", costNavField,  2)
        row("持仓份额:", sharesField,   3)
        row("所属分组:", groupCombo,    4)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun doSearch() {
        val kw = searchField.text.trim().ifEmpty { return }
        searchBtn.isEnabled = false; resultModel.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = MarketDataService.searchFunds(kw)
            SwingUtilities.invokeLater {
                results.forEach { resultModel.addElement(it) }
                searchBtn.isEnabled = true
                if (results.isEmpty()) JOptionPane.showMessageDialog(null, "未找到相关基金")
            }
        }
    }

    private fun setupGroups() {
        groups.forEach { groupCombo.addItem(it.name) }
        val idx = groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    private fun prefillExisting() {
        val f = existingFund!!
        codeLabel.text    = f.code; nameLabel.text = f.name
        costNavField.text = f.costNav.toString(); sharesField.text = f.shares.toString()
        val idx = groups.indexOfFirst { it.id == f.groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    override fun doOKAction() {
        val code    = existingFund?.code ?: selectedResult?.code ?: return
        val name    = existingFund?.name ?: selectedResult?.name ?: return
        val costNav = costNavField.text.toDoubleOrNull() ?: 1.0
        val shares  = sharesField.text.toDoubleOrNull()  ?: 0.0
        val gid     = groups.getOrNull(groupCombo.selectedIndex)?.id ?: groupId
        onSave(code, name, gid, costNav, shares)
        super.doOKAction()
    }
}
