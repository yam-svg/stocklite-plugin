package com.stocklite.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.JBColor
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.service.MarketDataService.CryptoNetStatus
import com.stocklite.plugin.state.*
import com.stocklite.plugin.ui.common.QuoteColumnType
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.ui.common.centerTableHeader
import com.stocklite.plugin.ui.dialogs.AddCryptoDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.util.L10n
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

/**
 * 加密货币看板（纯行情，无持仓）。
 * 行情源依赖网络可直连交易所公开接口，不同用户网络环境差异极大，
 * 因此工具栏常驻一个连通状态徽章：已连接(绿)/降级备源(橙)/全部不可达(红+详细说明)。
 */
class CryptoPanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.RefreshIntervalListener,
    StockliteState.DataChangeListener {

    private val chartPanel = InlineChartPanel()
    private val aiPanel    = AiAnalysisPanel(AiAnalysisService.promptForCrypto)
    private val state get() = StockliteState.getInstance()
    private var currentGroupId = SystemGroups.ALL_CRYPTO_ID
    private var rows: List<Pair<CryptoData, CryptoQuote?>> = emptyList()
    private val quotes = mutableMapOf<String, CryptoQuote>()

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val getValue: (CryptoData, CryptoQuote?) -> Any
    )

    private val allCols get() = listOf(
        ColDef("name",         L10n.colName,      QuoteColumnType.PLAIN) { c, _ -> c.alias.ifBlank { c.name } },
        ColDef("symbol",       L10n.colSymbol,    QuoteColumnType.PLAIN) { c, _ -> c.symbol },
        ColDef("price",        L10n.colPrice,     QuoteColumnType.PRICE) { _, q -> q?.price ?: 0.0 },
        ColDef("changePercent",L10n.colChangePct, QuoteColumnType.PCT)   { _, q -> q?.changePercent ?: 0.0 },
        ColDef("high24h",      L10n.colHigh24h,   QuoteColumnType.PRICE) { _, q -> q?.high24h?.takeIf { it.isFinite() } ?: 0.0 },
        ColDef("low24h",       L10n.colLow24h,    QuoteColumnType.PRICE) { _, q -> q?.low24h?.takeIf { it.isFinite() } ?: 0.0 },
    )

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()           = rows.size
        override fun getColumnCount()        = allCols.size
        override fun getColumnName(col: Int) = allCols[col].title
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> =
            if (allCols[col].type != QuoteColumnType.PLAIN) Double::class.java else String::class.java
        override fun getValueAt(row: Int, col: Int): Any {
            val (c, q) = rows[row]
            return allCols[col].getValue(c, q)
        }
    }

    private val table      = JBTable(tableModel)
    private val groupCombo = JComboBox<String>()
    private var updatingCombo = false
    private val statusLabel  = JLabel(L10n.cryptoUnitNote)
    private val netBadge     = JLabel(L10n.cryptoNetUnknown).apply {
        font = font.deriveFont(Font.BOLD)
        toolTipText = L10n.cryptoNetTooltip
    }
    private var panelActive  = true
    private var refreshTimer: Timer? = null
    private var probing = false

    // 最近一次连通状态（语言切换时重绘文案用）
    private var lastStatus: CryptoNetStatus? = null
    private var lastSource: String? = null
    private var lastFailCount = 0

    private val colorDegraded = JBColor(Color(0xC8, 0x8A, 0x00), Color(0xE0, 0xA0, 0x30))
    private val statusDefaultForeground = statusLabel.foreground

    private lateinit var groupLbl:   JLabel
    private lateinit var manageBtn:  JButton
    private lateinit var addBtn:     JButton
    private lateinit var upBtn:      JButton
    private lateinit var downBtn:    JButton
    private lateinit var refreshBtn: JButton
    private lateinit var filterField: SearchTextField

    init {
        seedDefaults()
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
        addBtn.text     = L10n.btnAddCrypto
        refreshBtn.text = L10n.btnRefresh
        netBadge.toolTipText = L10n.cryptoNetTooltip
        lastStatus?.let { updateNetUi(it, lastSource, lastFailCount) }
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
        allCols.forEachIndexed { i, col ->
            val saved = state.getColumnWidth("crypto.${col.key}")
            if (saved != null && i < table.columnModel.columnCount)
                table.columnModel.getColumn(i).preferredWidth = saved
        }
        if (table.columnModel.columnCount > 0)
            table.columnModel.getColumn(0).preferredWidth =
                state.getColumnWidth("crypto.name") ?: 120
    }

    private fun installColumnWidthListener() {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent) {
                allCols.forEachIndexed { i, col ->
                    if (i < table.columnModel.columnCount) {
                        val w = table.columnModel.getColumn(i).width
                        if (w > 0) state.setColumnWidth("crypto.${col.key}", w)
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
                val (c, q) = rows[modelRow]
                chartPanel.showChart(
                    displayName   = c.alias.ifBlank { c.name },
                    displaySymbol = c.symbol,
                    changePercent = q?.changePercent ?: 0.0,
                    prevClose     = 0.0,   // 加密 24/7 无昨收概念，分时以现价为基准
                    fetchData     = { ChartDataService.getCryptoIntraday(c.symbol) }
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
        val (c, q) = rows[modelRow]

        val popup = JPopupMenu()
        popup.add(JMenuItem(L10n.menuRename).also { it.addActionListener {
            val input = JOptionPane.showInputDialog(this@CryptoPanel, L10n.dlgAliasPrompt, c.alias.ifBlank { c.name })
            if (input != null) {  // null=取消；空串=恢复默认名称
                c.alias = input.trim().takeIf { a -> a != c.name } ?: ""
                loadRows()
            }
        }})
        popup.add(JMenuItem(L10n.btnDelete).also { it.addActionListener {
            if (JOptionPane.showConfirmDialog(this@CryptoPanel, L10n.dlgConfirmDelete(c.name),
                    L10n.dlgConfirmTitle, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                state.deleteCrypto(c.id); loadRows()
            }
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnCopySymbol).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.symbol), null)
        }})
        popup.add(JMenuItem(L10n.btnCopyName).also { it.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.name), null)
        }})
        popup.add(JMenuItem(L10n.btnOpenBrowser).also { it.addActionListener {
            BrowserUtil.browse("https://www.gate.io/trade/${c.symbol}")
        }})
        popup.addSeparator()
        popup.add(JMenuItem(L10n.btnAiDeepAnalysis).also { it.addActionListener {
            val ctx = buildString {
                appendLine("名称：${c.name}"); appendLine("交易对：${c.symbol}")
                if (q != null) {
                    val sign = if (q.changePercent >= 0) "+" else ""
                    appendLine("现价（USDT）：${"%.4f".format(q.price).trimEnd('0').trimEnd('.')}")
                    appendLine("24h涨跌幅：$sign${"%.2f".format(q.changePercent)}%")
                    if (q.high24h.isFinite() && q.high24h > 0)  appendLine("24h最高：${"%.4f".format(q.high24h).trimEnd('0').trimEnd('.')}")
                    if (q.low24h.isFinite()  && q.low24h  > 0)  appendLine("24h最低：${"%.4f".format(q.low24h).trimEnd('0').trimEnd('.')}")
                }
            }.trim()
            com.stocklite.plugin.ui.dialogs.AiDeepAnalysisDialog(
                displayTitle = "${c.name} (${c.symbol})",
                itemContext  = ctx
            ).show()
        }})
        popup.show(table, e.x, e.y)
    }

    /**
     * 首次使用时内置一组常见币种，安装后标签页即有数据可看。
     * cryptoDefaultsSeeded 置位后不再播种，用户删除的内置币不会复活。
     * 在注册数据监听之前调用，避免播种过程中的 notify 打到未完成的 UI。
     */
    private fun seedDefaults() {
        if (state.cryptoDefaultsSeeded) return
        state.cryptoDefaultsSeeded = true
        if (state.cryptos.isNotEmpty() || state.cryptoGroups.isNotEmpty()) return
        val group = state.createCryptoGroup(L10n.cryptoDefaultGroup)
        listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE").forEach { base ->
            state.createCrypto("${base}_USDT", "$base/USDT", group.id)
        }
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        groupLbl   = JLabel(L10n.lblGroup)
        manageBtn  = JButton(L10n.btnManageGroups)
        addBtn     = JButton(L10n.btnAddCrypto)
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

        // 连通状态徽章独立一行：FlowLayout 窄面板下会裁掉尾部组件，放工具栏里会被隐藏
        val netBar = JPanel(BorderLayout()).apply {
            add(netBadge, BorderLayout.WEST)
            border = BorderFactory.createEmptyBorder(0, 6, 2, 6)
        }
        val north = JPanel(BorderLayout())
        north.add(toolbar, BorderLayout.NORTH)
        north.add(netBar,  BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 12, 2))
        bottomBar.add(statusLabel)

        val centerWrapper = JPanel(BorderLayout())
        centerWrapper.add(JBScrollPane(table), BorderLayout.CENTER)
        centerWrapper.add(chartPanel, BorderLayout.SOUTH)

        val mainWrapper = JPanel(BorderLayout())
        mainWrapper.add(centerWrapper, BorderLayout.CENTER)
        mainWrapper.add(aiPanel,       BorderLayout.SOUTH)

        add(north,       BorderLayout.NORTH)
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
            ManageGroupsDialog(groups = state.cryptoGroups,
                onCreate = { name -> state.createCryptoGroup(name) },
                onRename = { id, name -> state.updateCryptoGroup(id, name) },
                onDelete = { id -> state.deleteCryptoGroup(id) },
                onDone   = { refreshGroups() }).show()
        }

        addBtn.addActionListener {
            AddCryptoDialog(
                groupId = currentGroupId.takeIf { it != SystemGroups.ALL_CRYPTO_ID }
                    ?: state.cryptoGroups.firstOrNull()?.id ?: "",
                groups  = state.cryptoGroups
            ) { symbol, name, groupId ->
                state.createCrypto(symbol, name, groupId)
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
        val items     = state.getCryptosForGroup(currentGroupId)
        if (targetIdx < 0 || targetIdx >= items.size) return
        state.cryptos.sortedBy { it.sortOrder }.forEachIndexed { i, c -> c.sortOrder = i }
        val fresh = state.getCryptosForGroup(currentGroupId)
        val a = fresh[modelRow]; val b = fresh[targetIdx]
        val tmp = a.sortOrder
        state.cryptos.find { it.id == a.id }?.sortOrder = b.sortOrder
        state.cryptos.find { it.id == b.id }?.sortOrder = tmp
        (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
        loadRows()
        val newRow = targetIdx.coerceIn(0, tableModel.rowCount - 1)
        table.setRowSelectionInterval(newRow, newRow)
        table.scrollRectToVisible(table.getCellRect(newRow, 0, true))
    }

    private fun moveRowTo(fromModelRow: Int, toModelRow: Int) {
        state.cryptos.sortedBy { it.sortOrder }.forEachIndexed { i, c -> c.sortOrder = i }
        val groupItems = state.getCryptosForGroup(currentGroupId)
        if (fromModelRow !in groupItems.indices || toModelRow !in groupItems.indices) return
        val sortOrders = groupItems.map { g -> state.cryptos.find { it.id == g.id }!!.sortOrder }.sorted()
        val mutable = groupItems.toMutableList()
        mutable.add(toModelRow, mutable.removeAt(fromModelRow))
        mutable.forEachIndexed { i, data -> state.cryptos.find { it.id == data.id }?.sortOrder = sortOrders[i] }
        loadRows()
        table.setRowSelectionInterval(toModelRow, toModelRow)
        table.scrollRectToVisible(table.getCellRect(toModelRow, 0, true))
    }

    private fun groupIdList() = listOf(SystemGroups.ALL_CRYPTO_ID) + state.cryptoGroups.map { it.id }
    private fun groupNameList() = listOf(L10n.groupAllCryptos) + state.cryptoGroups.map { it.name }

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
        rows = state.getCryptosForGroup(currentGroupId).map { it to quotes[it.symbol] }
        tableModel.fireTableDataChanged()
    }

    /**
     * 刷新行情 + 更新连通状态徽章。
     * 自选为空时用 BTC 探测源连通性，保证用户一眼能看到"接口通不通"。
     */
    fun fetchQuotesAsync() {
        if (!panelActive) return
        val symbols = rows.map { it.first.symbol }.distinct()
        if (symbols.isEmpty()) {
            if (probing) return
            probing = true
            ApplicationManager.getApplication().executeOnPooledThread {
                val batch = MarketDataService.probeCryptoNetwork()
                SwingUtilities.invokeLater {
                    probing = false
                    updateNetUi(batch.status, batch.workingSource, 0)
                }
            }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val batch = MarketDataService.getCryptoQuotes(symbols)
            SwingUtilities.invokeLater {
                quotes.putAll(batch.quotes)
                rows = rows.map { (c, _) -> c to quotes[MarketDataService.normCryptoSymbol(c.symbol)] }
                tableModel.fireTableDataChanged()
                statusLabel.text = if (batch.status == CryptoNetStatus.OFFLINE)
                    L10n.cryptoNetOfflineDetail else L10n.cryptoUnitNote
                aiPanel.updateContext(buildAiContext())
                updateNetUi(batch.status, batch.workingSource,
                    (symbols.size - batch.quotes.size).coerceAtLeast(0))
            }
        }
    }

    /** 工具栏连通状态徽章：绿=主源正常，橙=降级备源/部分失败，红=三源全不可达 */
    private fun updateNetUi(status: CryptoNetStatus, source: String?, failCount: Int) {
        lastStatus = status; lastSource = source; lastFailCount = failCount
        val base = when (status) {
            CryptoNetStatus.CONNECTED -> L10n.cryptoNetOk(source ?: "")
            CryptoNetStatus.DEGRADED  -> L10n.cryptoNetDegraded(source ?: "")
            CryptoNetStatus.OFFLINE   -> L10n.cryptoNetOffline
        }
        val suffix = if (status != CryptoNetStatus.OFFLINE && failCount > 0) L10n.cryptoNetPartial(failCount) else ""
        netBadge.text = base + suffix
        netBadge.foreground = when (status) {
            CryptoNetStatus.CONNECTED -> if (failCount > 0) colorDegraded else QuoteRenderer.GREEN
            CryptoNetStatus.DEGRADED  -> colorDegraded
            CryptoNetStatus.OFFLINE   -> QuoteRenderer.RED
        }
        statusLabel.text = if (status == CryptoNetStatus.OFFLINE)
            L10n.cryptoNetOfflineDetail else L10n.cryptoUnitNote
        statusLabel.foreground = if (status == CryptoNetStatus.OFFLINE)
            QuoteRenderer.RED else statusDefaultForeground
    }

    private fun buildAiContext(): String {
        val valid = rows.filter { (_, q) -> q != null }.ifEmpty { return "" }
        val sb = StringBuilder("加密行情（共 ${valid.size} 个交易对，价格单位 USDT）:\n")
        for ((c, q) in valid) {
            val sign = if (q!!.changePercent >= 0) "+" else ""
            sb.appendLine("- ${c.name}(${c.symbol}): ${"%.4f".format(q.price).trimEnd('0').trimEnd('.')}  $sign${"%.2f".format(q.changePercent)}%")
        }
        return sb.toString().trim()
    }

    private fun scheduleRefresh() {
        val intervalMs = state.refreshIntervalCrypto * 1000L
        refreshTimer = Timer(intervalMs.toInt()) { fetchQuotesAsync() }.also {
            it.isRepeats = true; it.start()
        }
    }
}
