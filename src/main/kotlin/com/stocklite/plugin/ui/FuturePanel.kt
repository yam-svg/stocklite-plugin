package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.ui.dialogs.AddFutureDialog
import com.stocklite.plugin.ui.dialogs.ChartDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * 期货行情看板（纯行情展示，不含持仓/方向/盈亏）
 */
class FuturePanel : JPanel(BorderLayout()) {

    private val state get() = StockliteState.getInstance()
    private var currentGroupId = SystemGroups.ALL_FUTURE_ID
    private var rows: List<Pair<FutureData, FutureQuote?>> = emptyList()
    private val quotes = mutableMapOf<String, FutureQuote>()

    private val COLS      = arrayOf("名称", "代码", "现价", "涨跌幅")
    private val COL_TYPES = listOf(QuoteColumnType.PLAIN, QuoteColumnType.PLAIN,
                                   QuoteColumnType.PRICE, QuoteColumnType.PCT)
    private val COL_CLASS = arrayOf(String::class.java, String::class.java,
                                    Double::class.java, Double::class.java)

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = rows.size
        override fun getColumnCount()        = COLS.size
        override fun getColumnName(col: Int) = COLS[col]
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> = COL_CLASS.getOrElse(col) { String::class.java }
        override fun getValueAt(row: Int, col: Int): Any {
            val (f, q) = rows[row]
            return when (col) {
                0 -> f.name
                1 -> f.symbol
                2 -> q?.price ?: 0.0
                3 -> q?.changePercent ?: 0.0
                else -> ""
            }
        }
    }

    private val table       = JBTable(tableModel)
    private val groupCombo  = JComboBox<String>()
    private var updatingCombo = false
    private val statusLabel = JLabel(MarketTimeUtil.getMarketStatusText())

    init {
        setupTable()
        table.rowSorter = TableRowSorter(tableModel)
        buildUI()
        refreshGroups()
        scheduleRefresh()
    }

    private fun setupTable() {
        COL_TYPES.forEachIndexed { i, t ->
            table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(t)
        }
        table.columnModel.getColumn(0).preferredWidth = 120
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        centerTableHeader(table)

        // 点击"涨跌幅"列弹出日内走势图
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.getColumnName(viewCol) != "涨跌幅") return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= rows.size) return
                val (f, q) = rows[modelRow]
                ChartDialog(
                    displayName   = f.name,
                    displaySymbol = f.symbol,
                    changePercent = q?.changePercent ?: 0.0,
                    prevClose     = q?.prevClose ?: 0.0,
                    fetchData     = { ChartDataService.getFutureIntraday(f.symbol) }
                ).show()
            }
        })
        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && table.getColumnName(col) == "涨跌幅")
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        toolbar.add(JLabel("分组:")); toolbar.add(groupCombo)
        val manageBtn  = JButton("管理分组")
        val addBtn     = JButton("添加期货")
        val delBtn     = JButton("删除")
        val upBtn      = JButton("↑")
        val downBtn    = JButton("↓")
        val refreshBtn = JButton("刷新")
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(delBtn)
        toolbar.add(upBtn);  toolbar.add(downBtn)
        toolbar.add(refreshBtn)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(statusLabel)

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
                groups   = state.futureGroups,
                onCreate = { name -> state.createFutureGroup(name) },
                onRename = { id, name -> state.updateFutureGroup(id, name) },
                onDelete = { id -> state.deleteFutureGroup(id) },
                onDone   = { refreshGroups() }
            ).show()
        }

        addBtn.addActionListener {
            AddFutureDialog(
                groupId = currentGroupId.takeIf { it != SystemGroups.ALL_FUTURE_ID }
                    ?: state.futureGroups.firstOrNull()?.id ?: "",
                groups  = state.futureGroups
            ) { symbol, name, groupId ->
                state.createFuture(symbol, name, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        delBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val (f, _) = rows[row]
            if (JOptionPane.showConfirmDialog(this, "确定删除「${f.name}」？", "删除确认",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteFuture(f.id); loadRows()
            }
        }

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }
    }

    private fun moveRow(delta: Int) {
        val viewRow   = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow  = table.convertRowIndexToModel(viewRow)
        val items     = state.getFuturesForGroup(currentGroupId)
        val targetIdx = modelRow + delta
        if (targetIdx < 0 || targetIdx >= items.size) return

        state.futures.sortedBy { it.sortOrder }.forEachIndexed { i, f -> f.sortOrder = i }

        val fresh = state.getFuturesForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.futures.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.futures.find { it.id == b.id }?.sortOrder = tmp

        (table.rowSorter as? javax.swing.table.TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_FUTURE_ID) + state.futureGroups.map { it.id }
    private fun groupNameList() = listOf(SystemGroups.ALL_FUTURE_NAME) + state.futureGroups.map { it.name }

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
        rows = state.getFuturesForGroup(currentGroupId).map { it to quotes[it.symbol] }
        tableModel.fireTableDataChanged()
        statusLabel.text = MarketTimeUtil.getMarketStatusText()
    }

    fun fetchQuotesAsync() {
        val symbols = rows.map { it.first.symbol }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getFutureQuotes(symbols)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (f, _) -> f to quotes[f.symbol] }
                tableModel.fireTableDataChanged()
                statusLabel.text = MarketTimeUtil.getMarketStatusText()
            }
        }
    }

    private fun scheduleRefresh() {
        Timer(5_000) { fetchQuotesAsync() }.also { it.isRepeats = true; it.start() }
    }
}
