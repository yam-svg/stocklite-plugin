package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import javax.swing.table.TableRowSorter
import com.stocklite.plugin.ui.dialogs.AddStockDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class StockPanel : JPanel(BorderLayout()), StockliteState.ColumnSettingsListener {

    // ── 列定义 ──
    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val alwaysOn: Boolean = false,
        val getValue: (StockData, StockQuote?) -> Any
    )

    private val ALL_COLS = listOf(
        ColDef("name",        "名称",   QuoteColumnType.PLAIN,  true)  { s, _  -> s.name },
        ColDef("symbol",      "代码",   QuoteColumnType.PLAIN)         { s, _  -> s.symbol },
        ColDef("quantity",    "持仓",   QuoteColumnType.QTY)           { s, _  -> s.quantity },
        ColDef("cost",        "成本价", QuoteColumnType.PRICE)         { s, _  -> s.costPrice },
        ColDef("price",       "现价",   QuoteColumnType.PRICE,  true)  { _, q  -> q?.price ?: 0.0 },
        ColDef("changePercent","涨跌幅",QuoteColumnType.PCT,    true)  { _, q  -> q?.changePercent ?: 0.0 },
        ColDef("marketValue", "市值",   QuoteColumnType.VALUE)         { s, q  -> (q?.price ?: 0.0) * s.quantity },
        ColDef("pnl",         "盈亏",   QuoteColumnType.PNL)           { s, q  ->
            val p = q?.price ?: 0.0; if (p > 0) (p - s.costPrice) * s.quantity else 0.0
        },
        ColDef("pnlPercent",  "盈亏%",  QuoteColumnType.PCT)           { s, q  ->
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

    private val table        = JBTable(tableModel)
    private val groupCombo   = JComboBox<String>()
    private val summaryLabel = JLabel("总市值: --   总盈亏: --")
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())
    private var updatingCombo = false  // 防止 removeAllItems/addItem 误触发 listener

    init {
        state.addColumnListener(this)
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

    private fun rebuildVisibleCols() {
        val enabled = state.stockVisibleColumns.toSet()
        visibleCols = ALL_COLS.filter { it.alwaysOn || it.key in enabled }
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
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        toolbar.add(JLabel("分组:"))
        toolbar.add(groupCombo)
        val manageBtn  = JButton("管理分组")
        val addBtn     = JButton("添加股票")
        val editBtn    = JButton("编辑")
        val delBtn     = JButton("删除")
        val upBtn      = JButton("↑")
        val downBtn    = JButton("↓")
        val refreshBtn = JButton("刷新")
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(editBtn);   toolbar.add(delBtn)
        toolbar.add(upBtn);     toolbar.add(downBtn)
        toolbar.add(refreshBtn)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(summaryLabel); bottomBar.add(statusLabel)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(bottomBar, BorderLayout.SOUTH)

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
            if (JOptionPane.showConfirmDialog(this, "确定删除「${s.name}」？", "删除确认",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
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

        // 先归一化所有股票的 sortOrder（确保无重复值）
        state.stocks.sortedBy { it.sortOrder }.forEachIndexed { i, s -> s.sortOrder = i }

        // 重新获取归一化后的列表，再交换两项的 sortOrder
        val fresh = state.getStocksForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.stocks.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.stocks.find { it.id == b.id }?.sortOrder = tmp

        // 清除列排序，重载，恢复选中
        (table.rowSorter as? javax.swing.table.TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_STOCK_ID, SystemGroups.HOLDING_STOCK_ID) +
        state.stockGroups.map { it.id }

    private fun groupNameList() = listOf(SystemGroups.ALL_STOCK_NAME, SystemGroups.HOLDING_STOCK_NAME) +
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
        summaryLabel.text = "总市值: ${Fmt.value(totalValue)}   总盈亏: $sign${Fmt.value(totalPnl)}"
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
