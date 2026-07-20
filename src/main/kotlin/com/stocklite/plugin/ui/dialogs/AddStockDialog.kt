package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.StockGroupData
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.state.StockQuote
import com.stocklite.plugin.state.StockSearchResult
import com.stocklite.plugin.ui.InlineChartPanel
import com.stocklite.plugin.ui.common.Fmt
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
    private val aliasField   = JTextField("", 12)
    private val costField    = JTextField("0.00", 12)
    private val qtyField     = JTextField("0", 12)
    private val groupCombo   = JComboBox<String>()

    // 编辑模式下成本/数量显示为纯文本，通过交易记录修改
    private val isEditMode   get() = existingStock != null
    private val qtyValueLbl  = JLabel("0")

    // 行情卡 + 图表
    private val quoteCard    = QuoteCard()
    private val chartPanel   = InlineChartPanel()
    private var loadSeq      = 0   // 防止乱序回调覆盖最新结果

    private var selectedResult: StockSearchResult? = null

    init {
        title = if (existingStock == null) L10n.dlgAddStock else L10n.dlgEditStock
        init()
        setupGroups()
        if (existingStock != null) {
            prefillExisting()
            fetchQuoteAndChart(existingStock.symbol)
        }
        isOKActionEnabled = existingStock != null
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(600, 680)

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
            searchPanel.add(JBScrollPane(resultList).apply { preferredSize = Dimension(580, 120) }, BorderLayout.CENTER)
            panel.add(searchPanel, BorderLayout.NORTH)

            resultList.addListSelectionListener {
                resultList.selectedValue?.let { r ->
                    selectedResult = r
                    symbolLabel.text = r.symbol
                    nameLabel.text   = r.name
                    isOKActionEnabled = true
                    fetchQuoteAndChart(r.symbol)
                }
            }

            searchBtn.addActionListener { doSearch() }
            searchField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) doSearch()
                }
            })
        }

        // 行情卡 + 图表的中间区
        val quoteAndChart = JPanel(BorderLayout(0, 0))
        quoteAndChart.add(quoteCard, BorderLayout.NORTH)
        quoteAndChart.add(chartPanel, BorderLayout.CENTER)

        // 表单
        val form = buildForm()

        val center = JPanel(BorderLayout(0, 6))
        center.add(quoteAndChart, BorderLayout.NORTH)
        center.add(form,          BorderLayout.CENTER)

        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    private fun buildForm(): JPanel {
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
        if (existingStock != null) row(L10n.dlgAliasLbl, aliasField, 2)

        if (isEditMode) {
            val s = existingStock!!
            val costValueLbl = JLabel(s.costPrice.toString())
            val tradeLink = JButton("<html><u>${L10n.btnTradeHistory}</u></html>").apply {
                isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                font = font.deriveFont(11f)
                foreground = java.awt.Color(0x6895d6)
                toolTipText = L10n.tradeEditHint
                addActionListener {
                    TradeHistoryDialog(s) {
                        costValueLbl.text = s.costPrice.toString()
                        qtyValueLbl.text  = s.quantity.toString()
                    }.show()
                }
            }
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            form.add(JLabel(L10n.dlgCostLbl), gbc)
            val costRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                add(costValueLbl); add(tradeLink)
            }
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(costRow, gbc); gbc.weightx = 0.0

            gbc.gridx = 0; gbc.gridy = 4; gbc.fill = GridBagConstraints.NONE
            form.add(JLabel(L10n.dlgQtyLbl), gbc)
            val qtyRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                add(qtyValueLbl)
                add(JLabel("<html><span style='color:#888aaa'>${L10n.tradeEditHint}</span></html>").apply {
                    font = font.deriveFont(10.5f)
                })
            }
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            form.add(qtyRow, gbc); gbc.weightx = 0.0

            row(L10n.dlgGroupLbl, groupCombo, 5)
        } else {
            row(L10n.dlgCostLbl,  costField,  3)
            row(L10n.dlgQtyLbl,   qtyField,   4)
            row(L10n.dlgGroupLbl, groupCombo, 5)
        }

        return form
    }

    // ── 行情卡 ────────────────────────────────────────────────────────────

    private inner class QuoteCard : JPanel(BorderLayout()) {
        private val priceLabel  = JLabel("--", SwingConstants.LEFT)
        private val changeLbl   = JLabel("--")
        private val metaLabel   = JLabel("--")   // 昨收 / 今开 / 高 / 低

        init {
            isVisible = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0x3A3A5A)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            priceLabel.font  = priceLabel.font.deriveFont(Font.BOLD, 18f)
            changeLbl.font   = changeLbl.font.deriveFont(Font.BOLD, 13f)
            metaLabel.font   = metaLabel.font.deriveFont(11f)
            metaLabel.foreground = Color(0x888aaa)

            val top = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
            top.add(priceLabel)
            top.add(changeLbl)
            add(top,       BorderLayout.NORTH)
            add(metaLabel, BorderLayout.SOUTH)
        }

        fun showLoading() {
            isVisible = true
            priceLabel.text  = L10n.dlgQuoteLoading
            priceLabel.foreground = Color(0x888aaa)
            changeLbl.text   = ""
            metaLabel.text   = ""
            revalidate(); repaint()
        }

        fun showQuote(q: StockQuote) {
            isVisible = true
            val scheme = try { StockliteState.getInstance().colorScheme } catch (_: Exception) { "RED_UP" }
            val pct = q.changePercent
            val upColor   = if (scheme == "RED_DOWN") Color(0x26a69a) else Color(0xef5350)
            val downColor = if (scheme == "RED_DOWN") Color(0xef5350) else Color(0x26a69a)
            val color = when { scheme == "NONE" -> Color(0x888aaa); pct >= 0 -> upColor; else -> downColor }
            priceLabel.text      = Fmt.price(q.price)
            priceLabel.foreground = color
            changeLbl.text       = Fmt.pct(pct)
            changeLbl.foreground  = color

            metaLabel.text = buildString {
                append("${L10n.dlgQuotePrevClose} ${Fmt.price(q.prevClose)}")
                // 拉取到开高低时展示（需从行情 API 补充，当前 StockQuote 暂无 open/high/low 字段，故省略）
            }
            revalidate(); repaint()
        }

        fun hideCard() {
            isVisible = false
            revalidate(); repaint()
        }
    }

    // ── 行情 + 图表异步加载 ───────────────────────────────────────────────

    private fun fetchQuoteAndChart(symbol: String) {
        val seq = ++loadSeq
        quoteCard.showLoading()
        // 图表先显示（立即以空状态展开，等数据回填）
        chartPanel.showChart(
            displayName   = symbolLabel.text.ifBlank { symbol },
            displaySymbol = symbol,
            changePercent = 0.0,
            prevClose     = 0.0,
            fetchData     = { ChartDataService.getStockIntraday(symbol) }
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val quotes = MarketDataService.getStockQuotes(listOf(symbol))
            val q = quotes[symbol]
            SwingUtilities.invokeLater {
                if (seq != loadSeq) return@invokeLater
                if (q != null) {
                    quoteCard.showQuote(q)
                    // 当 costField 仍为默认值 0.00 时，自动预填当前价，方便用户
                    if (!isEditMode && (costField.text.toDoubleOrNull() ?: 0.0) == 0.0) {
                        costField.text = Fmt.price(q.price)
                    }
                    // 重新触发图表以带入 prevClose（基线涨跌幅计算需要）
                    chartPanel.showChart(
                        displayName   = nameLabel.text.ifBlank { symbol },
                        displaySymbol = symbol,
                        changePercent = q.changePercent,
                        prevClose     = q.prevClose,
                        fetchData     = { ChartDataService.getStockIntraday(symbol) }
                    )
                } else {
                    quoteCard.hideCard()
                }
            }
        }
    }

    // ── 以下原有逻辑保持不变 ─────────────────────────────────────────────

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
        symbolLabel.text  = s.symbol
        nameLabel.text    = s.name
        aliasField.text   = s.alias
        qtyValueLbl.text  = s.quantity.toString()
        costField.text    = s.costPrice.toString()
        qtyField.text     = s.quantity.toString()
        val idx = groups.indexOfFirst { it.id == s.groupId }.takeIf { it >= 0 } ?: 0
        if (groups.isNotEmpty()) groupCombo.selectedIndex = idx
    }

    override fun doOKAction() {
        val symbol = existingStock?.symbol ?: selectedResult?.symbol ?: return
        val name   = existingStock?.name   ?: selectedResult?.name   ?: return
        val gid    = groups.getOrNull(groupCombo.selectedIndex)?.id ?: groupId
        existingStock?.alias = aliasField.text.trim()

        if (isEditMode) {
            val s = existingStock!!
            onSave(symbol, name, gid, s.costPrice, s.quantity)
        } else {
            val cost = costField.text.toDoubleOrNull() ?: 0.0
            val qty  = qtyField.text.toDoubleOrNull()  ?: 0.0
            onSave(symbol, name, gid, cost, qty)
        }
        super.doOKAction()
    }

    override fun dispose() {
        chartPanel.disposeResources()
        super.dispose()
    }
}
