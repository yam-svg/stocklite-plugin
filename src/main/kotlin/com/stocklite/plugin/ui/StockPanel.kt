package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import javax.swing.table.TableRowSorter
import com.stocklite.plugin.ui.dialogs.AddStockDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.table.AbstractTableModel

class StockPanel : JPanel(BorderLayout()),
    StockliteState.ColumnSettingsListener,
    StockliteState.LanguageListener {

    private val chartPanel = InlineChartPanel()

    // ── 列定义（计算属性，每次访问返回当前语言的列名）──
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

    // ── 数据状态 ──
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

    private val table      = JBTable(tableModel)
    private val groupCombo = JComboBox<String>()
    private var updatingCombo = false
    private val summaryLabel = JLabel("${L10n.lblTotalValue} --   ${L10n.lblTotalPnl} --")
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())

    // ── 需要在语言切换时更新文字的 UI 组件 ──
    private lateinit var groupLbl:   JLabel
    private lateinit var manageBtn:  JButton
    private lateinit var addBtn:     JButton
    private lateinit var editBtn:    JButton
    private lateinit var delBtn:     JButton
    private lateinit var refreshBtn: JButton

    init {
        state.addColumnListener(this)
        state.addLanguageListener(this)
        rebuildVisibleCols()
        setupTable()
        table.rowSorter = TableRowSorter(tableModel)
        buildUI()
        refreshGroups()
        scheduleRefresh()
    }

    override fun onColumnSettingsChanged() {
        rebuildVisibleCols()
        loadRows()
    }

    override fun onLanguageChanged() {
        rebuildVisibleCols()    // 刷新列名
        refreshGroups()         // 刷新系统分组名称（"全部股票"/"我的持有"等）
        groupLbl.text   = L10n.lblGroup
        manageBtn.text  = L10n.btnManageGroups
        addBtn.text     = L10n.btnAddStock
        editBtn.text    = L10n.btnEdit
        delBtn.text     = L10n.btnDelete
        refreshBtn.text = L10n.btnRefresh
        updateSummary()
        revalidate(); repaint()
    }

    private fun rebuildVisibleCols() {
        val enabled = state.stockVisibleColumns.toSet()
        visibleCols = allCols.filter { it.alwaysOn || it.key in enabled }
        tableModel.fireTableStructureChanged()
        table.rowSorter = TableRowSorter(tableModel)
        applyRenderers()
    }

    private fun applyRenderers() {
        visibleCols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(col.type)
            }
        }
        table.columnModel.getColumn(0).preferredWidth = 100
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        centerTableHeader(table)

        // 点击"涨跌幅"列展开内嵌走势图
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
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

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        groupLbl   = JLabel(L10n.lblGroup)
        manageBtn  = JButton(L10n.btnManageGroups)
        addBtn     = JButton(L10n.btnAddStock)
        editBtn    = JButton(L10n.btnEdit)
        delBtn     = JButton(L10n.btnDelete)
        val upBtn  = JButton("↑")
        val downBtn= JButton("↓")
        refreshBtn = JButton(L10n.btnRefresh)

        toolbar.add(groupLbl);  toolbar.add(groupCombo)
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(editBtn);   toolbar.add(delBtn)
        toolbar.add(upBtn);     toolbar.add(downBtn)
        toolbar.add(refreshBtn)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(summaryLabel); bottomBar.add(statusLabel)

        // 中间区域：表格 + 内嵌图表（图表初始隐藏，点击涨跌幅后展开）
        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(chartPanel, BorderLayout.SOUTH)

        add(toolbar,      BorderLayout.NORTH)
        add(centerWrapper,BorderLayout.CENTER)
        add(bottomBar,    BorderLayout.SOUTH)

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
                state.createStock(symbol, name, groupId, cost, qty)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        editBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val (s, _) = rows[row]
            AddStockDialog(groupId = s.groupId, groups = state.stockGroups, existingStock = s) {
                _, _, groupId, cost, qty ->
                state.updateStock(s.id, cost, qty, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        delBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val (s, _) = rows[row]
            if (JOptionPane.showConfirmDialog(this, L10n.dlgConfirmDelete(s.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteStock(s.id); loadRows()
            }
        }

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }
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

        (table.rowSorter as? javax.swing.table.TableRowSorter<*>)?.sortKeys = emptyList()
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
        val symbols = rows.map { it.first.symbol }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getStockQuotes(symbols)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (s, _) -> s to quotes[s.symbol] }
                tableModel.fireTableDataChanged()
                applyRenderers()
                updateSummary()
            }
        }
    }

    private fun scheduleRefresh() {
        Timer(5_000) { fetchQuotesAsync() }.also { it.isRepeats = true; it.start() }
    }
}
