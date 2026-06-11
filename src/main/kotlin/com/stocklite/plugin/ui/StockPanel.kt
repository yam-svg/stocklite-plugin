package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.dialogs.AddStockDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.ui.dialogs.SetAlertDialog
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import com.intellij.ide.BrowserUtil
import javax.swing.*
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class StockPanel : JPanel(BorderLayout()),
    StockliteState.ColumnSettingsListener,
    StockliteState.LanguageListener,
    StockliteState.RefreshIntervalListener {

    private val chartPanel = InlineChartPanel()
    private val aiPanel    = AiAnalysisPanel(AiAnalysisService.promptForStock)

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val alwaysOn: Boolean = false,
        val getValue: (StockData, StockQuote?) -> Any
    )

    private val allCols get() = listOf(
        ColDef("name",         L10n.colName,      QuoteColumnType.PLAIN, true)  { s, _  -> s.name },
        ColDef("symbol",       L10n.colSymbol,    QuoteColumnType.PLAIN)        { s, _  -> s.symbol },
        ColDef("quantity",     L10n.colQty,       QuoteColumnType.QTY)          { s, _  -> s.quantity },
        ColDef("cost",         L10n.colCost,      QuoteColumnType.PRICE)        { s, _  -> s.costPrice },
        ColDef("price",        L10n.colPrice,     QuoteColumnType.PRICE, true)  { _, q  -> q?.price ?: 0.0 },
        ColDef("changePercent",L10n.colChangePct, QuoteColumnType.PCT,   true)  { _, q  -> q?.changePercent ?: 0.0 },
        ColDef("marketValue",  L10n.colValue,     QuoteColumnType.VALUE)        { s, q  -> (q?.price ?: 0.0) * s.quantity },
        ColDef("pnl",          L10n.colPnl,       QuoteColumnType.PNL)          { s, q  ->
            val p = q?.price ?: 0.0; if (p > 0) (p - s.costPrice) * s.quantity else 0.0
        },
        ColDef("pnlPercent",   L10n.colPnlPct,   QuoteColumnType.PCT)          { s, q  ->
            val p = q?.price ?: 0.0
            if (p > 0 && s.costPrice > 0) (p - s.costPrice) / s.costPrice * 100.0 else 0.0
        },
    )

    private var visibleCols: List<ColDef> = emptyList()
    private val state get() = StockliteState.getInstance()
    private var currentGroupId = SystemGroups.ALL_STOCK_ID
    private var rows: List<Pair<StockData, StockQuote?>> = emptyList()
    private val quotes = mutableMapOf<String, StockQuote>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()            = rows.size
        override fun getColumnCount()         = visibleCols.size
        override fun getColumnName(col: Int)  = visibleCols[col].title
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> =
            if (col < visibleCols.size && visibleCols[col].type != QuoteColumnType.PLAIN)
                Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val (s, q) = rows[row]
            return visibleCols[col].getValue(s, q)
        }
    }

    private val table        = JBTable(tableModel)
    private val groupCombo   = JComboBox<String>()
    private var updatingCombo = false
    private val summaryLabel = JLabel("${L10n.lblTotalValue} --   ${L10n.lblTotalPnl} --")
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())

    private lateinit var groupLbl:   JLabel
    private lateinit var manageBtn:  JButton
    private lateinit var addBtn:     JButton
    private lateinit var editBtn:    JButton
    private lateinit var delBtn:     JButton
    private lateinit var refreshBtn: JButton
    private lateinit var filterField: SearchTextField

    private var refreshTimer: Timer? = null
    // 面板是否可见（用于生命周期优化）
    private var panelActive = true

    init {
        state.addColumnListener(this)
        state.addLanguageListener(this)
        state.addRefreshIntervalListener(this)
        rebuildVisibleCols()
        setupTable()
        table.rowSorter = TableRowSorter(tableModel)
        buildUI()
        refreshGroups()
        scheduleRefresh()

        // 生命周期：面板隐藏时暂停刷新
        addHierarchyListener { e ->
            val showing = isShowing
            if (showing != panelActive) {
                panelActive = showing
                if (showing) fetchQuotesAsync()
            }
        }
    }

    override fun onColumnSettingsChanged() { rebuildVisibleCols(); loadRows() }

    override fun onLanguageChanged() {
        rebuildVisibleCols()
        refreshGroups()
        groupLbl.text   = L10n.lblGroup
        manageBtn.text  = L10n.btnManageGroups
        addBtn.text     = L10n.btnAddStock
        editBtn.text    = L10n.btnEdit
        delBtn.text     = L10n.btnDelete
        refreshBtn.text = L10n.btnRefresh
        updateSummary()
        revalidate(); repaint()
    }

    override fun onRefreshIntervalChanged() {
        refreshTimer?.stop()
        scheduleRefresh()
    }

    private fun rebuildVisibleCols() {
        val enabled = state.stockVisibleColumns.toSet()
        visibleCols = allCols.filter { it.alwaysOn || it.key in enabled }
        tableModel.fireTableStructureChanged()
        table.rowSorter = TableRowSorter(tableModel)
        applyRenderers()
        restoreColumnWidths()
        installColumnWidthListener()
    }

    private fun applyRenderers() {
        visibleCols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(col.type)
            }
        }
        if (table.columnModel.columnCount > 0)
            table.columnModel.getColumn(0).preferredWidth = 100
    }

    private fun restoreColumnWidths() {
        visibleCols.forEachIndexed { i, col ->
            val saved = state.getColumnWidth("stock.${col.key}")
            if (saved != null && i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).preferredWidth = saved
            }
        }
    }

    private fun installColumnWidthListener() {
        // Remove existing listeners first (to avoid duplicates after rebuild)
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent) {
                visibleCols.forEachIndexed { i, col ->
                    if (i < table.columnModel.columnCount) {
                        val w = table.columnModel.getColumn(i).width
                        if (w > 0) state.setColumnWidth("stock.${col.key}", w)
                    }
                }
            }
            override fun columnAdded(e: TableColumnModelEvent) {}
            override fun columnRemoved(e: TableColumnModelEvent) {}
            override fun columnMoved(e: TableColumnModelEvent) {}
            override fun columnSelectionChanged(e: javax.swing.event.ListSelectionEvent) {}
        })
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        centerTableHeader(table)

        // 左键：点击涨跌幅列展开图表
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (SwingUtilities.isRightMouseButton(e)) return
                if (table.getColumnName(viewCol) != L10n.colChangePct) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= rows.size) return
                val (s, q) = rows[modelRow]
                chartPanel.showChart(
                    displayName   = s.name,
                    displaySymbol = s.symbol,
                    changePercent = q?.changePercent ?: 0.0,
                    prevClose     = q?.prevClose ?: 0.0,
                    fetchData     = { ChartDataService.getStockIntraday(s.symbol) }
                )
            }

            // 右键菜单
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
        })

        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && table.getColumnName(col) == L10n.colChangePct)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })
    }

    private fun showContextMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= rows.size) return
        val (s, q) = rows[modelRow]

        val popup = JPopupMenu()

        popup.add(JMenuItem(L10n.btnEdit).also { it.addActionListener {
            AddStockDialog(groupId = s.groupId, groups = state.stockGroups, existingStock = s) {
                _, _, groupId, cost, qty ->
                if (qty < 0) { JOptionPane.showMessageDialog(this, L10n.validationQtyNotNegative()); return@AddStockDialog }
                state.updateStock(s.id, cost, qty, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }})

        popup.add(JMenuItem(L10n.btnDelete).also { it.addActionListener {
            if (JOptionPane.showConfirmDialog(this, L10n.dlgConfirmDelete(s.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteStock(s.id); loadRows()
            }
        }})

        popup.addSeparator()

        popup.add(JMenuItem(L10n.btnSetAlert).also { it.addActionListener {
            SetAlertDialog(s.symbol, s.name, q?.price ?: 0.0) { targetPrice, alertType ->
                state.createAlert(s.symbol, s.name, targetPrice, alertType)
            }.show()
        }})

        // 是否有有效提醒
        val alerts = state.getAlertsForSymbol(s.symbol)
        if (alerts.isNotEmpty()) {
            popup.add(JMenuItem("${L10n.btnDeleteAlert} (${alerts.size})").also { mi ->
                mi.addActionListener {
                    alerts.forEach { state.deleteAlert(it.id) }
                }
            })
        }

        popup.addSeparator()

        popup.add(JMenuItem(L10n.btnCopySymbol).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s.symbol), null)
        }})
        popup.add(JMenuItem(L10n.btnCopyName).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s.name), null)
        }})
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { it.addActionListener {
            BrowserUtil.browse(buildStockUrl(s.symbol))
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnAiDeepAnalysis).also { it.addActionListener {
            com.stocklite.plugin.ui.dialogs.AiDeepAnalysisDialog(
                displayTitle = "${s.name} (${s.symbol})",
                itemContext  = buildStockItemContext(s, q)
            ).show()
        }})

        popup.show(table, e.x, e.y)
    }

    private fun buildStockItemContext(s: StockData, q: StockQuote?): String {
        val sb = StringBuilder()
        sb.appendLine("名称：${s.name}")
        sb.appendLine("代码：${s.symbol}")
        if (q != null) {
            val sign = if (q.changePercent >= 0) "+" else ""
            sb.appendLine("现价：${"%.3f".format(q.price)}")
            sb.appendLine("涨跌幅：$sign${"%.2f".format(q.changePercent)}%")
            sb.appendLine("昨收：${"%.3f".format(q.prevClose)}")
            if (s.costPrice > 0) {
                sb.appendLine("持仓成本：${"%.3f".format(s.costPrice)}")
                val pnlPct = (q.price - s.costPrice) / s.costPrice * 100
                val pSign  = if (pnlPct >= 0) "+" else ""
                sb.appendLine("持仓盈亏：$pSign${"%.2f".format(pnlPct)}%")
            }
        }
        return sb.toString().trim()
    }

    private fun buildStockUrl(symbol: String): String {
        return when {
            symbol.startsWith("sh") || symbol.startsWith("sz") -> {
                // 东方财富：SH600519 / SZ000858
                "https://quote.eastmoney.com/${symbol.uppercase()}.html"
            }
            symbol.startsWith("hk") -> {
                // 港股：东方财富 HK00700
                val code = symbol.removePrefix("hk").padStart(5, '0')
                "https://quote.eastmoney.com/HK$code.html"
            }
            else ->
                "https://finance.yahoo.com/quote/$symbol"
        }
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        groupLbl     = JLabel(L10n.lblGroup)
        manageBtn    = JButton(L10n.btnManageGroups)
        addBtn       = JButton(L10n.btnAddStock)
        editBtn      = JButton(L10n.btnEdit)
        delBtn       = JButton(L10n.btnDelete)
        val upBtn    = JButton("↑")
        val downBtn  = JButton("↓")
        refreshBtn   = JButton(L10n.btnRefresh)
        filterField  = SearchTextField().also { it.preferredSize = Dimension(120, 26) }

        toolbar.add(groupLbl);  toolbar.add(groupCombo)
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(editBtn);   toolbar.add(delBtn)
        toolbar.add(upBtn);     toolbar.add(downBtn)
        toolbar.add(refreshBtn)
        toolbar.add(JLabel(L10n.lblFilter)); toolbar.add(filterField)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(summaryLabel)
        bottomBar.add(statusLabel)

        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(chartPanel, BorderLayout.SOUTH)

        val mainWrapper = JPanel(BorderLayout())
        mainWrapper.add(centerWrapper, BorderLayout.CENTER)
        mainWrapper.add(aiPanel,       BorderLayout.SOUTH)

        add(toolbar,     BorderLayout.NORTH)
        add(mainWrapper, BorderLayout.CENTER)
        add(bottomBar,   BorderLayout.SOUTH)

        // 快速筛选
        filterField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
        })

        groupCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val idx = groupCombo.selectedIndex.takeIf { it >= 0 } ?: return@addActionListener
            currentGroupId = groupIdList()[idx]
            loadRows(); fetchQuotesAsync()
        }

        manageBtn.addActionListener {
            ManageGroupsDialog(
                groups   = state.stockGroups,
                onCreate = { name -> state.createStockGroup(name) },
                onRename = { id, name -> state.updateStockGroup(id, name) },
                onDelete = { id -> state.deleteStockGroup(id) },
                onDone   = { refreshGroups() }
            ).show()
        }

        addBtn.addActionListener {
            AddStockDialog(
                groupId = currentGroupId.takeIf { !isSystemGroup(it) } ?: state.stockGroups.firstOrNull()?.id ?: "",
                groups  = state.stockGroups,
                existingStock = null
            ) { symbol, name, groupId, cost, qty ->
                if (cost < 0) { JOptionPane.showMessageDialog(this, L10n.validationCostPositive()); return@AddStockDialog }
                if (qty < 0) { JOptionPane.showMessageDialog(this, L10n.validationQtyNotNegative()); return@AddStockDialog }
                state.createStock(symbol, name, groupId, cost, qty)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        editBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val modelRow = table.convertRowIndexToModel(row)
            val (s, _) = rows[modelRow]
            AddStockDialog(groupId = s.groupId, groups = state.stockGroups, existingStock = s) {
                _, _, groupId, cost, qty ->
                if (qty < 0) { JOptionPane.showMessageDialog(this, L10n.validationQtyNotNegative()); return@AddStockDialog }
                state.updateStock(s.id, cost, qty, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        delBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val modelRow = table.convertRowIndexToModel(row)
            val (s, _) = rows[modelRow]
            if (JOptionPane.showConfirmDialog(this, L10n.dlgConfirmDelete(s.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteStock(s.id); loadRows()
            }
        }

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }
    }

    private fun updateFilter() {
        val text = filterField.text.trim()
        val sorter = table.rowSorter as? TableRowSorter<*> ?: return
        if (text.isEmpty()) {
            sorter.rowFilter = null
        } else {
            sorter.rowFilter = RowFilter.regexFilter("(?i)${Regex.escape(text)}")
        }
    }

    private fun moveRow(delta: Int) {
        val viewRow  = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(viewRow)
        val items    = state.getStocksForGroup(currentGroupId)
        val targetIdx = modelRow + delta
        if (targetIdx < 0 || targetIdx >= items.size) return

        state.stocks.sortedBy { it.sortOrder }.forEachIndexed { i, s -> s.sortOrder = i }

        val fresh = state.getStocksForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.stocks.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.stocks.find { it.id == b.id }?.sortOrder = tmp

        (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_STOCK_ID, SystemGroups.HOLDING_STOCK_ID) +
        state.stockGroups.map { it.id }

    private fun groupNameList() = listOf(L10n.groupAllStocks, L10n.groupHoldingStocks) +
        state.stockGroups.map { it.name }

    private fun isSystemGroup(id: String) =
        id == SystemGroups.ALL_STOCK_ID || id == SystemGroups.HOLDING_STOCK_ID

    fun refreshGroups() {
        val ids = groupIdList(); val names = groupNameList()
        val prevId = currentGroupId
        updatingCombo = true
        try {
            groupCombo.removeAllItems()
            names.forEach { groupCombo.addItem(it) }
            val idx = ids.indexOf(prevId).takeIf { it >= 0 } ?: 0
            groupCombo.selectedIndex = idx
            currentGroupId = ids[idx]
        } finally {
            updatingCombo = false
        }
        loadRows()
    }

    private fun loadRows() {
        rows = state.getStocksForGroup(currentGroupId).map { it to quotes[it.symbol] }
        tableModel.fireTableDataChanged()
        applyRenderers()
        updateSummary()
    }

    private fun updateSummary() {
        val totalValue = rows.sumOf { (s, q) -> (q?.price ?: 0.0) * s.quantity }
        val totalPnl   = rows.sumOf { (s, q) ->
            val p = q?.price ?: 0.0; if (p > 0) (p - s.costPrice) * s.quantity else 0.0
        }
        val sign = if (totalPnl >= 0) "+" else ""
        summaryLabel.text = "${L10n.lblTotalValue} ${Fmt.value(totalValue)}   ${L10n.lblTotalPnl} $sign${Fmt.value(totalPnl)}"
        statusLabel.text  = MarketTimeUtil.getMarketStatusText()
    }

    fun fetchQuotesAsync() {
        if (!panelActive) return
        val symbols = rows.map { it.first.symbol }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getStockQuotes(symbols)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (s, _) -> s to quotes[s.symbol] }
                tableModel.fireTableDataChanged()
                applyRenderers()
                updateSummary()
                aiPanel.updateContext(buildAiContext())
            }
        }
    }

    private fun buildAiContext(): String {
        val valid = rows.filter { (_, q) -> q != null }.ifEmpty { return "" }
        val sb = StringBuilder("A股/港股/美股行情（共 ${valid.size} 只）:\n")
        for ((s, q) in valid) {
            val sign = if (q!!.changePercent >= 0) "+" else ""
            sb.appendLine("- ${s.name}(${s.symbol}): ${"%.3f".format(q.price)}  $sign${"%.2f".format(q.changePercent)}%")
        }
        return sb.toString().trim()
    }

    private fun scheduleRefresh() {
        val intervalMs = state.refreshIntervalStock * 1000L
        refreshTimer = Timer(intervalMs.toInt()) { fetchQuotesAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }
}
