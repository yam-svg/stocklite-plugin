package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.QuoteRenderer.Companion.SENTINEL_NO_ESTIMATE
import com.stocklite.plugin.ui.common.QuoteRenderer.Companion.SENTINEL_OFFICIAL_UPDATED
import javax.swing.table.TableRowSorter
import com.stocklite.plugin.ui.dialogs.AddFundDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class FundPanel : JPanel(BorderLayout()), StockliteState.ColumnSettingsListener {

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val alwaysOn: Boolean = false,
        val getValue: (FundData, FundQuote?) -> Any
    )

    private val ALL_COLS = listOf(
        ColDef("name",         "名称",     QuoteColumnType.PLAIN,  true) { f, _  -> f.name },
        ColDef("nav",          "当前净值", QuoteColumnType.PRICE4, true) { _, q  -> q?.nav ?: 0.0 },
        // 官方净值涨跌幅（F10 数据，始终反映最新已发布净值的当日变化）
        ColDef("changePercent","官方涨跌", QuoteColumnType.PCT,    true) { _, q  -> q?.changePercent ?: 0.0 },
        // 净值日期（YYYY-MM-DD），日期从昨天变今天即说明今日净值已更新
        ColDef("navDate",      "净值日期", QuoteColumnType.PLAIN,  true) { _, q  ->
            val d = q?.date ?: return@ColDef "--"
            if (d.length >= 10) d.substring(5) else d  // 显示 MM-dd
        },
        // 今日盘中估算（天天基金 gszzl），始终返回 Double 以支持正确排序：
        //   hasEstimate=true  → 今日实时估算涨跌幅
        //   hasEstimate=false + date==today → 哨兵 SENTINEL_OFFICIAL_UPDATED，显示"官方✓"
        //   else              → 哨兵 SENTINEL_NO_ESTIMATE，显示"-"
        ColDef("todayChange",  "今日估算", QuoteColumnType.PCT,    true) { _, q  ->
            when {
                q == null             -> SENTINEL_NO_ESTIMATE
                q.hasEstimate         -> q.estimatedChangePercent ?: 0.0
                q.date == todayStr()  -> SENTINEL_OFFICIAL_UPDATED
                else                  -> SENTINEL_NO_ESTIMATE
            }
        },
        // 可选列（默认隐藏）
        ColDef("code",         "代码",     QuoteColumnType.PLAIN)        { f, _  -> f.code },
        ColDef("shares",       "持仓份额", QuoteColumnType.QTY)          { f, _  -> f.shares },
        ColDef("costNav",      "成本净值", QuoteColumnType.PRICE4)       { f, _  -> f.costNav },
        ColDef("marketValue",  "市值",     QuoteColumnType.VALUE)        { f, q  -> (q?.nav ?: 0.0) * f.shares },
        ColDef("pnl",          "盈亏",     QuoteColumnType.PNL)          { f, q  ->
            val n = q?.nav ?: 0.0; if (n > 0) (n - f.costNav) * f.shares else 0.0
        },
        ColDef("pnlPercent",   "盈亏%",    QuoteColumnType.PCT)          { f, q  ->
            val n = q?.nav ?: 0.0
            if (n > 0 && f.costNav > 0) (n - f.costNav) / f.costNav * 100.0 else 0.0
        },
    )

    private fun todayStr(): String {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        return String.format("%04d-%02d-%02d", now.year, now.monthValue, now.dayOfMonth)
    }

    private var visibleCols: List<ColDef> = emptyList()

    private val state get() = StockliteState.getInstance()
    private var currentGroupId = SystemGroups.ALL_FUND_ID
    private var rows: List<Pair<FundData, FundQuote?>> = emptyList()
    private val quotes = mutableMapOf<String, FundQuote>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = rows.size
        override fun getColumnCount()        = visibleCols.size
        override fun getColumnName(col: Int) = visibleCols[col].title
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> =
            if (col < visibleCols.size && visibleCols[col].type != QuoteColumnType.PLAIN)
                Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val (f, q) = rows[row]
            return visibleCols[col].getValue(f, q)
        }
    }

    private val table        = JBTable(tableModel)
    private val groupCombo   = JComboBox<String>()
    private var updatingCombo = false
    private val summaryLabel = JLabel("总市值: --   总盈亏: --")
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())

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
        val enabled = state.fundVisibleColumns.toSet()
        visibleCols = ALL_COLS.filter { it.alwaysOn || it.key in enabled }
        tableModel.fireTableStructureChanged()
        table.rowSorter = TableRowSorter(tableModel)  // 列结构变化后重建排序器
        applyRenderers()
    }

    private fun applyRenderers() {
        visibleCols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(col.type)
            }
        }
        if (table.columnModel.columnCount > 0)
            table.columnModel.getColumn(0).preferredWidth = 140
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        centerTableHeader(table)
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        toolbar.add(JLabel("分组:")); toolbar.add(groupCombo)
        val manageBtn  = JButton("管理分组")
        val addBtn     = JButton("添加基金")
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
                groups   = state.fundGroups,
                onCreate = { name -> state.createFundGroup(name) },
                onRename = { id, name -> state.updateFundGroup(id, name) },
                onDelete = { id -> state.deleteFundGroup(id) },
                onDone   = { refreshGroups() }
            ).show()
        }

        addBtn.addActionListener {
            AddFundDialog(
                groupId = currentGroupId.takeIf { !isSystemGroup(it) } ?: state.fundGroups.firstOrNull()?.id ?: "",
                groups  = state.fundGroups,
                existingFund = null
            ) { code, name, groupId, costNav, shares ->
                state.createFund(code, name, groupId, costNav, shares)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        editBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val (f, _) = rows[row]
            AddFundDialog(groupId = f.groupId, groups = state.fundGroups, existingFund = f) {
                _, _, groupId, costNav, shares ->
                state.updateFund(f.id, costNav, shares, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        delBtn.addActionListener {
            val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
            val (f, _) = rows[row]
            if (JOptionPane.showConfirmDialog(this, "确定删除「${f.name}」？", "删除确认",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteFund(f.id); loadRows()
            }
        }

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }
    }

    private fun moveRow(delta: Int) {
        val viewRow   = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow  = table.convertRowIndexToModel(viewRow)
        val items     = state.getFundsForGroup(currentGroupId)
        val targetIdx = modelRow + delta
        if (targetIdx < 0 || targetIdx >= items.size) return

        state.funds.sortedBy { it.sortOrder }.forEachIndexed { i, f -> f.sortOrder = i }

        val fresh = state.getFundsForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.funds.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.funds.find { it.id == b.id }?.sortOrder = tmp

        (table.rowSorter as? javax.swing.table.TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_FUND_ID, SystemGroups.HOLDING_FUND_ID) +
        state.fundGroups.map { it.id }

    private fun groupNameList() = listOf(SystemGroups.ALL_FUND_NAME, SystemGroups.HOLDING_FUND_NAME) +
        state.fundGroups.map { it.name }

    private fun isSystemGroup(id: String) =
        id == SystemGroups.ALL_FUND_ID || id == SystemGroups.HOLDING_FUND_ID

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
        rows = state.getFundsForGroup(currentGroupId).map { it to quotes[it.code] }
        tableModel.fireTableDataChanged()
        applyRenderers()
        updateSummary()
    }

    private fun updateSummary() {
        val totalValue = rows.sumOf { (f, q) -> (q?.nav ?: 0.0) * f.shares }
        val totalPnl   = rows.sumOf { (f, q) ->
            val n = q?.nav ?: 0.0; if (n > 0) (n - f.costNav) * f.shares else 0.0
        }
        val sign = if (totalPnl >= 0) "+" else ""
        summaryLabel.text = "总市值: ${Fmt.value(totalValue)}   总盈亏: $sign${Fmt.value(totalPnl)}"
        statusLabel.text  = MarketTimeUtil.getMarketStatusText()
    }

    fun fetchQuotesAsync() {
        val codes = rows.map { it.first.code }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getFundQuotes(codes)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (f, _) -> f to quotes[f.code] }
                tableModel.fireTableDataChanged()
                applyRenderers()
                updateSummary()
            }
        }
    }

    private fun scheduleRefresh() {
        Timer(30_000) { fetchQuotesAsync() }.also { it.isRepeats = true; it.start() }
    }
}
