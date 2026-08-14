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
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.ui.dialogs.AddFutureDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.intellij.ide.BrowserUtil
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.MarketTimeUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import com.stocklite.plugin.ui.common.TriStateRowSorter

class FuturePanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.RefreshIntervalListener,
    StockliteState.DataChangeListener {

    private val chartPanel = InlineChartPanel()
    private val aiPanel    = AiAnalysisPanel(AiAnalysisService.promptForFuture)
    private val state get() = StockliteState.getInstance()
    private var currentGroupId = SystemGroups.ALL_FUTURE_ID
    private var rows: List<Pair<FutureData, FutureQuote?>> = emptyList()
    private val quotes = mutableMapOf<String, FutureQuote>()

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val getValue: (FutureData, FutureQuote?) -> Any
    )

    private val allCols get() = listOf(
        ColDef("name",         L10n.colName,      QuoteColumnType.PLAIN) { f, _  -> f.alias.ifBlank { f.name } },
        ColDef("symbol",       L10n.colSymbol,    QuoteColumnType.PLAIN) { f, _  -> f.symbol },
        ColDef("price",        L10n.colPrice,     QuoteColumnType.PRICE) { _, q  -> q?.price ?: 0.0 },
        ColDef("changePercent",L10n.colChangePct, QuoteColumnType.PCT)   { _, q  -> q?.changePercent ?: 0.0 },
    )

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = rows.size
        override fun getColumnCount()        = allCols.size
        override fun getColumnName(col: Int) = allCols[col].title
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> =
            if (allCols[col].type != QuoteColumnType.PLAIN) Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val (f, q) = rows[row]
            return allCols[col].getValue(f, q)
        }
    }

    private val table      = JBTable(tableModel)
    private val groupCombo = JComboBox<String>()
    private var updatingCombo = false
    private val statusLabel  = JLabel(MarketTimeUtil.getMarketStatusText())
    private var panelActive  = true
    private var refreshTimer: Timer? = null

    private lateinit var groupLbl:   JLabel
    private lateinit var manageBtn:  JButton
    private lateinit var addBtn:     JButton
    private lateinit var upBtn:      JButton
    private lateinit var downBtn:    JButton
    private lateinit var refreshBtn: JButton
    private lateinit var filterField: SearchTextField

    init {
        state.addLanguageListener(this)
        state.addRefreshIntervalListener(this)
        state.addDataChangeListener(this)
        setupTable()
        table.rowSorter = TriStateRowSorter(tableModel)
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

    override fun onDataChanged() { refreshGroups() }

    override fun onLanguageChanged() {
        tableModel.fireTableStructureChanged()
        table.rowSorter = TriStateRowSorter(tableModel)
        applyRenderers()
        refreshGroups()
        groupLbl.text   = L10n.lblGroup
        manageBtn.text  = L10n.btnManageGroups
        addBtn.text     = L10n.btnAddFuture
        refreshBtn.text = L10n.btnRefresh
        statusLabel.text = MarketTimeUtil.getMarketStatusText()
        revalidate(); repaint()
    }

    override fun onRefreshIntervalChanged() {
        refreshTimer?.stop()
        scheduleRefresh()
    }

    private fun applyRenderers() {
        allCols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = QuoteRenderer(col.type)
            }
        }
        // Restore column widths
        allCols.forEachIndexed { i, col ->
            val saved = state.getColumnWidth("future.${col.key}")
            if (saved != null && i < table.columnModel.columnCount)
                table.columnModel.getColumn(i).preferredWidth = saved
        }
        if (table.columnModel.columnCount > 0)
            table.columnModel.getColumn(0).preferredWidth =
                state.getColumnWidth("future.name") ?: 120
    }

    private fun installColumnWidthListener() {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent) {
                allCols.forEachIndexed { i, col ->
                    if (i < table.columnModel.columnCount) {
                        val w = table.columnModel.getColumn(i).width
                        if (w > 0) state.setColumnWidth("future.${col.key}", w)
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
        applyRenderers()
        installColumnWidthListener()
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        centerTableHeader(table)

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) return
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (table.getColumnName(viewCol) != L10n.colChangePct) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= rows.size) return
                val (f, q) = rows[modelRow]
                chartPanel.showChart(
                    displayName   = f.alias.ifBlank { f.name },
                    displaySymbol = f.symbol,
                    changePercent = q?.changePercent ?: 0.0,
                    prevClose     = q?.prevClose ?: 0.0,
                    fetchData     = { ChartDataService.getFutureIntraday(f.symbol) }
                )
            }
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

        com.stocklite.plugin.ui.common.TableRowDragHandler.install(table) { from, to ->
            moveRowTo(from, to)
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(viewRow, viewRow)
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow < 0 || modelRow >= rows.size) return
        val (f, q) = rows[modelRow]

        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.menuRename).also { it.addActionListener {
            val input = JOptionPane.showInputDialog(this@FuturePanel, L10n.dlgAliasPrompt, f.alias.ifBlank { f.name })
            if (input != null) {  // null=取消；空串=恢复默认名称
                f.alias = input.trim().takeIf { a -> a != f.name } ?: ""
                loadRows()
            }
        }})
        popup.add(JMenuItem(L10n.btnDelete).also { it.addActionListener {
            if (JOptionPane.showConfirmDialog(this@FuturePanel, L10n.dlgConfirmDelete(f.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteFuture(f.id); loadRows()
            }
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnCopySymbol).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(f.symbol), null)
        }})
        popup.add(JMenuItem(L10n.btnCopyName).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(f.name), null)
        }})
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { it.addActionListener {
            BrowserUtil.browse(buildFutureUrl(f.symbol))
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnAiDeepAnalysis).also { it.addActionListener {
            val ctx = buildString {
                appendLine("名称：${f.name}"); appendLine("合约：${f.symbol}")
                if (q != null) {
                    val sign = if (q.changePercent >= 0) "+" else ""
                    appendLine("现价：${"%.2f".format(q.price)}")
                    appendLine("涨跌幅：$sign${"%.2f".format(q.changePercent)}%")
                    if (q.prevClose > 0) appendLine("昨结算：${"%.2f".format(q.prevClose)}")
                }
            }.trim()
            com.stocklite.plugin.ui.dialogs.AiDeepAnalysisDialog(
                displayTitle = "${f.name} (${f.symbol})",
                itemContext  = ctx
            ).show()
        }})
        popup.show(table, e.x, e.y)
    }

    private fun buildFutureUrl(symbol: String): String {
        val sym = symbol.trim()
        // 东方财富期货行情页：国内 nf_IF2506 → IF2506，国际 hf_GC → GC，统一大写
        val contract = when {
            sym.startsWith("nf_") -> sym.removePrefix("nf_").uppercase()
            sym.startsWith("hf_") -> sym.removePrefix("hf_").uppercase()
            else -> sym.uppercase()
        }
        return "https://quote.eastmoney.com/qihuo/$contract.html"
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        groupLbl   = JLabel(L10n.lblGroup)
        manageBtn  = JButton(L10n.btnManageGroups)
        addBtn     = JButton(L10n.btnAddFuture)
        upBtn      = JButton("↑")
        downBtn    = JButton("↓")
        refreshBtn = JButton(L10n.btnRefresh)
        filterField = SearchTextField().also { it.preferredSize = Dimension(120, 26) }

        upBtn.isVisible   = false
        downBtn.isVisible = false

        toolbar.add(groupLbl); toolbar.add(groupCombo)
        toolbar.add(refreshBtn)
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(upBtn);    toolbar.add(downBtn)
        toolbar.add(JLabel(L10n.lblFilter)); toolbar.add(filterField)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
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
            ManageGroupsDialog(groups = state.futureGroups,
                onCreate = { name -> state.createFutureGroup(name) },
                onRename = { id, name -> state.updateFutureGroup(id, name) },
                onDelete = { id -> state.deleteFutureGroup(id) },
                onDone   = { refreshGroups() }).show()
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

        upBtn.addActionListener   { moveRow(-1) }
        downBtn.addActionListener { moveRow(+1) }
        refreshBtn.addActionListener { fetchQuotesAsync() }

        table.selectionModel.addListSelectionListener {
            val hasSelection = table.selectedRow >= 0
            upBtn.isVisible   = hasSelection
            downBtn.isVisible = hasSelection
            toolbar.revalidate(); toolbar.repaint()
        }
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
        val items     = state.getFuturesForGroup(currentGroupId)
        if (targetIdx < 0 || targetIdx >= items.size) return
        state.futures.sortedBy { it.sortOrder }.forEachIndexed { i, f -> f.sortOrder = i }
        val fresh = state.getFuturesForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.futures.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.futures.find { it.id == b.id }?.sortOrder = tmp
        (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun moveRowTo(fromModelRow: Int, toModelRow: Int) {
        state.futures.sortedBy { it.sortOrder }.forEachIndexed { i, f -> f.sortOrder = i }
        val groupItems = state.getFuturesForGroup(currentGroupId)
        if (fromModelRow !in groupItems.indices || toModelRow !in groupItems.indices) return
        val sortOrders = groupItems.map { g -> state.futures.find { it.id == g.id }!!.sortOrder }.sorted()
        val mutable = groupItems.toMutableList()
        mutable.add(toModelRow, mutable.removeAt(fromModelRow))
        mutable.forEachIndexed { i, data -> state.futures.find { it.id == data.id }?.sortOrder = sortOrders[i] }
        loadRows()
        table.setRowSelectionInterval(toModelRow, toModelRow)
        table.scrollRectToVisible(table.getCellRect(toModelRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_FUTURE_ID) + state.futureGroups.map { it.id }
    private fun groupNameList() = listOf(L10n.groupAllFutures) + state.futureGroups.map { it.name }

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
        rows = state.getFuturesForGroup(currentGroupId).map { it to quotes[it.symbol] }
        tableModel.fireTableDataChanged()
        statusLabel.text = MarketTimeUtil.getMarketStatusText()
    }

    fun fetchQuotesAsync() {
        if (!panelActive) return
        val symbols = rows.map { it.first.symbol }.distinct().ifEmpty { return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getFutureQuotes(symbols)
            SwingUtilities.invokeLater {
                quotes.putAll(fetched)
                rows = rows.map { (f, _) -> f to quotes[f.symbol] }
                tableModel.fireTableDataChanged()
                statusLabel.text = MarketTimeUtil.getMarketStatusText()
                aiPanel.updateContext(buildAiContext())
            }
        }
    }

    private fun buildAiContext(): String {
        val valid = rows.filter { (_, q) -> q != null }.ifEmpty { return "" }
        val sb = StringBuilder("期货行情（共 ${valid.size} 个合约）:\n")
        for ((f, q) in valid) {
            val sign = if (q!!.changePercent >= 0) "+" else ""
            sb.appendLine("- ${f.name}(${f.symbol}): ${"%.2f".format(q.price)}  $sign${"%.2f".format(q.changePercent)}%")
        }
        return sb.toString().trim()
    }

    private fun scheduleRefresh() {
        val intervalMs = state.refreshIntervalStock * 1000L  // futures use stock interval
        refreshTimer = Timer(intervalMs.toInt()) { fetchQuotesAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }
}
