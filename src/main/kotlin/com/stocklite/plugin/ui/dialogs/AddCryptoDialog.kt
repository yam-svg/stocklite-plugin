package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.CryptoGroupData
import com.stocklite.plugin.state.CryptoSearchResult
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * 添加币种对话框（纯行情看板模式，只需搜索 + 选分组）。
 * 搜索命中后显示 "BTC/USDT"，symbol 存 Gate 风格 "BTC_USDT"。
 */
class AddCryptoDialog(
    private val groupId: String,
    private val groups: List<CryptoGroupData>,
    private val onSave: (symbol: String, name: String, groupId: String) -> Unit
) : DialogWrapper(null, true) {

    private val searchField  = JTextField(20)
    private val searchBtn    = JButton(L10n.btnSearch)
    private val resultModel  = DefaultListModel<CryptoSearchResult>()
    private val resultList   = JBList(resultModel)
    private val symbolLabel  = JLabel("--")
    private val nameLabel    = JLabel("--")
    private val groupCombo   = JComboBox<String>()

    private var selectedResult: CryptoSearchResult? = null

    // 手写占位提示：项目编译环境下 JTextField.placeholderText（JDK11+ API）不可用
    private var hintActive = false
    private val hintColor  = Color(0x99, 0x99, 0x99)

    init {
        title = L10n.dlgAddCrypto
        init()
        setupGroups()
        setupSearchHint()
        isOKActionEnabled = false
    }

    private fun setupSearchHint() {
        hintActive = true
        searchField.text      = L10n.cryptoSearchHint
        searchField.foreground = hintColor
        searchField.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (hintActive) { searchField.text = ""; searchField.foreground = UIManager.getColor("TextField.foreground"); hintActive = false }
            }
            override fun focusLost(e: FocusEvent) {
                if (searchField.text.isEmpty()) { searchField.text = L10n.cryptoSearchHint; searchField.foreground = hintColor; hintActive = true }
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(460, 390)

        // 搜索区
        val searchBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        searchBar.add(JLabel(L10n.dlgSearch))
        searchBar.add(searchField)
        searchBar.add(searchBtn)
        val scopeHint = JLabel("<html><font color='gray' size='2'>${L10n.cryptoSearchScope}</font></html>")

        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, _ ->
            JLabel("${value?.name}  (${value?.symbol})").apply {
                isOpaque = true
                if (isSelected) background = UIManager.getColor("List.selectionBackground")
            }
        }

        val searchPanel = JPanel(BorderLayout(4, 4))
        searchPanel.add(searchBar,  BorderLayout.NORTH)
        searchPanel.add(scopeHint,  BorderLayout.SOUTH)
        searchPanel.add(JBScrollPane(resultList).apply { preferredSize = Dimension(440, 170) }, BorderLayout.CENTER)
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
        row(L10n.dlgSymbolLbl,   symbolLabel, 0)
        row(L10n.dlgNameLbl,     nameLabel,   1)
        row(L10n.dlgFutureGrpLbl, groupCombo, 2)
        panel.add(form, BorderLayout.CENTER)

        // 价格单位提示（三源报价均为报价币计价，绝大多数为 USDT）
        panel.add(JLabel("<html><font color='gray' size='2'>${L10n.cryptoUnitNote}</font></html>"),
            BorderLayout.SOUTH)

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
        val kw = if (hintActive) "" else searchField.text.trim()
        if (kw.isEmpty()) return
        searchBtn.isEnabled = false; resultModel.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = MarketDataService.searchCryptos(kw)
            SwingUtilities.invokeLater {
                results.forEach { resultModel.addElement(it) }
                searchBtn.isEnabled = true
                if (results.isEmpty()) JOptionPane.showMessageDialog(null, L10n.dlgNoCryptoFound)
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
