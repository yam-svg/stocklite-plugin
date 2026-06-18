package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.QuoteRenderer.Companion.SENTINEL_NO_ESTIMATE
import com.stocklite.plugin.ui.common.QuoteRenderer.Companion.SENTINEL_OFFICIAL_UPDATED
import com.stocklite.plugin.ui.dialogs.AddFundDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
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

class FundPanel : JPanel(BorderLayout()),
    StockliteState.ColumnSettingsListener,
    StockliteState.LanguageListener,
    StockliteState.RefreshIntervalListener,
    StockliteState.FundQuotesRefreshListener {

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val alwaysOn: Boolean = false,
        val getValue: (FundData, FundQuote?) -> Any
    )

    private val allCols get() = listOf(
        ColDef("name",         L10n.colName,       QuoteColumnType.PLAIN,  true) { f, _  -> f.name },
        ColDef("nav",          L10n.colNav,         QuoteColumnType.PRICE4, true) { _, q  -> q?.nav ?: 0.0 },
        ColDef("changePercent",L10n.colOfficialChg, QuoteColumnType.PCT,    true) { _, q  -> q?.changePercent ?: 0.0 },
        ColDef("navDate",      L10n.colNavDate,     QuoteColumnType.PLAIN,  true) { _, q  ->
            val d = q?.date ?: return@ColDef "--"
            if (d.length >= 10) d.substring(5) else d
        },
        ColDef("todayChange",  L10n.colTodayEst,   QuoteColumnType.PCT,    true) { _, q  ->
            when {
                q == null        -> SENTINEL_NO_ESTIMATE
                q.hasEstimate    -> q.estimatedChangePercent ?: 0.0
                q.date == todayStr() -> SENTINEL_OFFICIAL_UPDATED
                else             -> SENTINEL_NO_ESTIMATE
            }
        },
        ColDef("code",         L10n.colSymbol,     QuoteColumnType.PLAIN)        { f, _  -> f.code },
        ColDef("shares",       L10n.colShares,     QuoteColumnType.QTY)          { f, _  -> f.shares },
        ColDef("costNav",      L10n.colCostNav,    QuoteColumnType.PRICE4)       { f, _  -> f.costNav },
        ColDef("marketValue",  L10n.colValue,      QuoteColumnType.VALUE)        { f, q  ->
            if (f.shares > 0) (q?.nav ?: 0.0) * f.shares else 0.0
        },
        ColDef("pnl",          L10n.colPnl,        QuoteColumnType.PNL)          { f, q  ->
            val n = q?.nav ?: 0.0
            if (f.shares > 0 && n > 0) (n - f.costNav) * f.shares else 0.0
        },
        ColDef("pnlPercent",   L10n.colPnlPct,    QuoteColumnType.PCT)          { f, q  ->
            val n = q?.nav ?: 0.0
            if (f.shares > 0 && n > 0 && f.costNav > 0) (n - f.costNav) / f.costNav * 100.0 else 0.0
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

    private val aiPanel      = AiAnalysisPanel(AiAnalysisService.promptForFund)
    private val table        = JBTable(tableModel)
    private val groupCombo   = JComboBox<String>()
    private var updatingCombo = false
    private val summaryLabel = JLabel("${L10n.lblTotalValue} --   ${L10n.lblTotalPnl} --")
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())
    private var panelActive  = true
    private var refreshTimer: Timer? = null

    private lateinit var groupLbl:   JLabel
    private lateinit var manageBtn:  JButton
    private lateinit var addBtn:     JButton
    private lateinit var refreshBtn: JButton
    private lateinit var filterField: SearchTextField

    init {
        state.addColumnListener(this)
        state.addLanguageListener(this)
        state.addRefreshIntervalListener(this)
        state.addFundQuotesListener(this)
        rebuildVisibleCols()
        setupTable()
        table.rowSorter = TableRowSorter(tableModel)
        buildUI()
        refreshGroups()
        scheduleRefresh()
        addHierarchyListener { _ ->
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
        addBtn.text     = L10n.btnAddFund
        refreshBtn.text = L10n.btnRefresh
        updateSummary()
        revalidate(); repaint()
    }

    override fun onRefreshIntervalChanged() {
        refreshTimer?.stop()
        scheduleRefresh()
    }

    override fun onFundQuotesRefreshed(incoming: Map<String, FundQuote>) {
        quotes.putAll(incoming)
        loadRows()
    }

    private fun rebuildVisibleCols() {
        val enabled = state.fundVisibleColumns.toSet()
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
            table.columnModel.getColumn(0).preferredWidth = 140
    }

    private fun restoreColumnWidths() {
        visibleCols.forEachIndexed { i, col ->
            val saved = state.getColumnWidth("fund.${col.key}")
            if (saved != null && i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).preferredWidth = saved
            }
        }
    }

    private fun installColumnWidthListener() {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent) {
                visibleCols.forEachIndexed { i, col ->
                    if (i < table.columnModel.columnCount) {
                        val w = table.columnModel.getColumn(i).width
                        if (w > 0) state.setColumnWidth("fund.${col.key}", w)
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

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) return
                // 单击名称列 → 弹出持仓详情
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.getColumnName(viewCol) != L10n.colName) return
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= rows.size) return
                val (f, _) = rows[modelRow]
                com.stocklite.plugin.ui.dialogs.FundHoldingsDialog(f.name, f.code).show()
            }
        })

        // 鼠标悬停在名称列时显示手型光标
        table.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                table.cursor = if (col >= 0 && table.getColumnName(col) == L10n.colName)
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
        val (f, q) = rows[modelRow]

        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.btnEdit).also { it.addActionListener {
            AddFundDialog(groupId = f.groupId, groups = state.fundGroups, existingFund = f) {
                _, _, groupId, costNav, shares ->
                state.updateFund(f.id, costNav, shares, groupId)
                loadRows(); fetchQuotesAsync()
            }.show()
        }})
        popup.add(JMenuItem(L10n.btnDelete).also { it.addActionListener {
            if (JOptionPane.showConfirmDialog(this@FundPanel, L10n.dlgConfirmDelete(f.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteFund(f.id); loadRows()
            }
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnCopySymbol).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(f.code), null)
        }})
        popup.add(JMenuItem(L10n.btnCopyName).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(f.name), null)
        }})
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { it.addActionListener {
            BrowserUtil.browse("https://fund.eastmoney.com/${f.code}.html")
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnAiDeepAnalysis).also { it.addActionListener {
            com.stocklite.plugin.ui.dialogs.AiDeepAnalysisDialog(
                displayTitle = "${f.name} (${f.code})",
                itemContext  = buildFundItemContext(f, q)
            ).show()
        }})
        popup.show(table, e.x, e.y)
    }

    private fun buildFundItemContext(f: com.stocklite.plugin.state.FundData,
                                     q: com.stocklite.plugin.state.FundQuote?): String {
        val sb = StringBuilder()
        sb.appendLine("名称：${f.name}")
        sb.appendLine("代码：${f.code}")
        if (q != null) {
            sb.appendLine("当前净值：${"%.4f".format(q.nav)}")
            val offSign = if (q.changePercent >= 0) "+" else ""
            sb.appendLine("官方涨跌：$offSign${"%.2f".format(q.changePercent)}%")
            if (q.estimatedChangePercent != null) {
                val estSign = if ((q.estimatedChangePercent ?: 0.0) >= 0) "+" else ""
                sb.appendLine("今日估算：$estSign${"%.2f".format(q.estimatedChangePercent)}%")
            }
            if (!q.date.isNullOrBlank()) sb.appendLine("净值日期：${q.date}")
            if (f.costNav > 0) {
                sb.appendLine("成本净值：${"%.4f".format(f.costNav)}")
                val pnlPct = (q.nav - f.costNav) / f.costNav * 100
                val pSign  = if (pnlPct >= 0) "+" else ""
                sb.appendLine("持仓盈亏：$pSign${"%.2f".format(pnlPct)}%")
            }
        }
        return sb.toString().trim()
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        groupLbl   = JLabel(L10n.lblGroup)
        manageBtn  = JButton(L10n.btnManageGroups)
        addBtn     = JButton(L10n.btnAddFund)
        val upBtn  = JButton("↑")
        val downBtn= JButton("↓")
        refreshBtn = JButton(L10n.btnRefresh)
        filterField = SearchTextField().also { it.preferredSize = Dimension(120, 26) }

        toolbar.add(groupLbl); toolbar.add(groupCombo)
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(upBtn);     toolbar.add(downBtn)
        toolbar.add(refreshBtn)
        toolbar.add(JLabel(L10n.lblFilter)); toolbar.add(filterField)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(summaryLabel); bottomBar.add(statusLabel)

        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(bottomBar, BorderLayout.SOUTH)

        val mainWrapper = JPanel(BorderLayout())
        mainWrapper.add(centerWrapper, BorderLayout.CENTER)
        mainWrapper.add(aiPanel,       BorderLayout.SOUTH)

        add(toolbar,     BorderLayout.NORTH)
        add(mainWrapper, BorderLayout.CENTER)

        filterField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateFilter()
        })

        groupCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val idx = groupCombo.selectedIndex.takeIf { it >= 0 } ?: return@addActionListener
            currentGroupId = groupIdList()[idx]; loadRows(); fetchQuotesAsync()
        }

        manageBtn.addActionListener {
            ManageGroupsDialog(groups = state.fundGroups,
                onCreate = { name -> state.createFundGroup(name) },
                onRename = { id, name -> state.updateFundGroup(id, name) },
                onDelete = { id -> state.deleteFundGroup(id) },
                onDone   = { refreshGroups() }).show()
        }

        addBtn.addActionListener {
            AddFundDialog(
                groupId = currentGroupId.takeIf { !isSystemGroup(it) } ?: state.fundGroups.firstOrNull()?.id ?: "",
                groups  = state.fundGroups, existingFund = null
            ) { code, name, groupId, costNav, shares ->
                state.createFund(code, name, groupId, costNav, shares)
                loadRows(); fetchQuotesAsync()
            }.show()
        }

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }
    }

    private fun updateFilter() {
        val text = filterField.text.trim()
        val sorter = table.rowSorter as? TableRowSorter<*> ?: return
        sorter.rowFilter = if (text.isEmpty()) null else RowFilter.regexFilter("(?i)${Regex.escape(text)}")
    }

    private fun moveRow(delta: Int) {
        val viewRow   = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow  = table.convertRowIndexToModel(viewRow)
        val targetIdx = modelRow + delta
        val items     = state.getFundsForGroup(currentGroupId)
        if (targetIdx < 0 || targetIdx >= items.size) return
        state.funds.sortedBy { it.sortOrder }.forEachIndexed { i, f -> f.sortOrder = i }
        val fresh = state.getFundsForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.funds.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.funds.find { it.id == b.id }?.sortOrder = tmp
        (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_FUND_ID, SystemGroups.HOLDING_FUND_ID) +
        state.fundGroups.map { it.id }

    private fun groupNameList() = listOf(L10n.groupAllFunds, L10n.groupHoldingFunds) +
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
            groupCombo.selectedIndex = idx; currentGroupId = ids[idx]
        } finally { updatingCombo = false }
        loadRows()
    }

    private fun loadRows() {
        rows = state.getFundsForGroup(currentGroupId).map { it to quotes[it.code] }
        tableModel.fireTableDataChanged(); applyRenderers(); updateSummary()
    }

    private fun updateSummary() {
        val totalValue = rows.sumOf { (f, q) -> if (f.shares > 0) (q?.nav ?: 0.0) * f.shares else 0.0 }
        val totalPnl   = rows.sumOf { (f, q) ->
            val n = q?.nav ?: 0.0
            if (f.shares > 0 && n > 0) (n - f.costNav) * f.shares else 0.0
        }
        val todayPnl   = rows.sumOf { (f, q) ->
            if (f.shares <= 0 || q == null) return@sumOf 0.0
            val n = q.nav; if (n <= 0) return@sumOf 0.0
            val pct = (if (q.hasEstimate) q.estimatedChangePercent ?: q.changePercent else q.changePercent) / 100.0
            n / (1 + pct) * pct * f.shares
        }
        fun sign(v: Double) = if (v >= 0) "+" else ""
        summaryLabel.text = buildString {
            append("${L10n.lblTotalValue} ${Fmt.value(totalValue)}")
            append("   ${L10n.lblTotalPnl} ${sign(totalPnl)}${Fmt.value(totalPnl)}")
            append("   ${L10n.lblTodayPnl} ${sign(todayPnl)}${Fmt.value(todayPnl)}")
        }
        statusLabel.text  = MarketTimeUtil.getMarketStatusText()
    }

    fun fetchQuotesAsync() {
        if (!panelActive) return
        val codes = rows.map { it.first.code }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getFundQuotes(codes)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (f, _) -> f to quotes[f.code] }
                tableModel.fireTableDataChanged(); applyRenderers(); updateSummary()
                aiPanel.updateContext(buildAiContext())
            }
        }
    }

    private fun scheduleRefresh() {
        val intervalMs = state.refreshIntervalFund * 1000L
        refreshTimer = Timer(intervalMs.toInt()) { fetchQuotesAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }

    private fun buildAiContext(): String {
        if (rows.isEmpty()) return ""

        val holdings  = rows.filter { (f, _) -> f.shares > 0 }
        val watchlist = rows.filter { (f, _) -> f.shares <= 0 }

        val sb = StringBuilder()

        if (holdings.isNotEmpty()) {
            sb.appendLine("【我的基金持仓】（${holdings.size} 只）")
            for ((f, q) in holdings) {
                val nav    = q?.nav?.let { "%.4f".format(it) } ?: "--"
                val offChg = q?.changePercent?.let { val s = if (it >= 0) "+" else ""; "${s}${"%.2f".format(it)}%(官方)" } ?: ""
                val estChg = q?.estimatedChangePercent?.let { val s = if (it >= 0) "+" else ""; "${s}${"%.2f".format(it)}%(今估)" } ?: ""
                val date   = q?.date?.let { "净值日期:$it" } ?: ""
                val pnlStr = if (q != null && f.costNav > 0) {
                    val pct    = (q.nav - f.costNav) / f.costNav * 100
                    val amount = (q.nav - f.costNav) * f.shares
                    val s      = if (pct >= 0) "+" else ""
                    "成本净值:${"%.4f".format(f.costNav)}  持有份额:${"%.2f".format(f.shares)}  盈亏:$s${"%.2f".format(amount)}($s${"%.2f".format(pct)}%)"
                } else ""
                sb.appendLine("- ${f.name}(${f.code}): 净值$nav  $offChg  $estChg  $date  $pnlStr")
            }
        }

        if (watchlist.isNotEmpty()) {
            if (holdings.isNotEmpty()) sb.appendLine()
            sb.appendLine("【自选（未持仓）】（${watchlist.size} 只）")
            for ((f, q) in watchlist) {
                val nav    = q?.nav?.let { "%.4f".format(it) } ?: "--"
                val offChg = q?.changePercent?.let { val s = if (it >= 0) "+" else ""; "${s}${"%.2f".format(it)}%(官方)" } ?: ""
                val estChg = q?.estimatedChangePercent?.let { val s = if (it >= 0) "+" else ""; "${s}${"%.2f".format(it)}%(今估)" } ?: ""
                val date   = q?.date?.let { "净值日期:$it" } ?: ""
                sb.appendLine("- ${f.name}(${f.code}): 净值$nav  $offChg  $estChg  $date")
            }
        }

        return sb.toString().trim()
    }
}
