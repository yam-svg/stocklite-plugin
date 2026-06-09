package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.FutureGroupData
import com.stocklite.plugin.state.FutureSearchResult
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * 添加期货对话框（纯行情看板模式，只需搜索 + 选分组）
 */
class AddFutureDialog(
    private val groupId: String,
    private val groups: List<FutureGroupData>,
    private val onSave: (symbol: String, name: String, groupId: String) -> Unit
) : DialogWrapper(null, true) {

    private val searchField  = JTextField(20)
    private val searchBtn    = JButton(L10n.btnSearch)
    private val resultModel  = DefaultListModel<FutureSearchResult>()
    private val resultList   = JBList(resultModel)
    private val symbolLabel  = JLabel("--")
    private val nameLabel    = JLabel("--")
    private val groupCombo   = JComboBox<String>()

    private var selectedResult: FutureSearchResult? = null

    init {
        title = L10n.dlgAddFuture
        init()
        setupGroups()
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(460, 360)

        // 搜索区
        val searchBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        searchBar.add(JLabel(L10n.dlgSearch))
        searchBar.add(searchField)
        searchBar.add(searchBtn)

        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, _ ->
            JLabel("${value?.name}  (${value?.symbol})").apply {
                isOpaque = true
                if (isSelected) background = UIManager.getColor("List.selectionBackground")
            }
        }

        val searchPanel = JPanel(BorderLayout(4, 4))
        searchPanel.add(searchBar, BorderLayout.NORTH)
        searchPanel.add(JBScrollPane(resultList).apply { preferredSize = Dimension(440, 160) }, BorderLayout.CENTER)
        panel.add(searchPanel, BorderLayout.NORTH)

        // 表单
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST }
        fun row(label: String, comp: JComponent, r: Int) {
            gbc.gridx = 0; gbc.gridy = r; gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(comp, gbc); gbc.weightx = 0.0
        }
        row(L10n.dlgSymbolLbl,    symbolLabel, 0)
        row(L10n.dlgNameLbl,      nameLabel,   1)
        row(L10n.dlgFutureGrpLbl, groupCombo,  2)
        panel.add(form, BorderLayout.CENTER)

        // 事件
        resultList.addListSelectionListener {
            resultList.selectedValue?.let { r ->
                selectedResult = r
                symbolLabel.text  = r.symbol
                nameLabel.text    = r.name
                isOKActionEnabled = true
            }
        }
        searchBtn.addActionListener { doSearch() }
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) doSearch() }
        })

        return panel
    }

    private fun doSearch() {
        val kw = searchField.text.trim().ifEmpty { return }
        searchBtn.isEnabled = false; resultModel.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = MarketDataService.searchFutures(kw)
            SwingUtilities.invokeLater {
                results.forEach { resultModel.addElement(it) }
                searchBtn.isEnabled = true
                if (results.isEmpty()) JOptionPane.showMessageDialog(null, L10n.dlgNoFutureFound)
            }
        }
    }

    private fun setupGroups() {
        groups.forEach { groupCombo.addItem(it.name) }
        val idx = groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    override fun doOKAction() {
        val r   = selectedResult ?: return
        val gid = groups.getOrNull(groupCombo.selectedIndex)?.id ?: groupId
        onSave(r.symbol, r.name, gid)
        super.doOKAction()
    }
}
