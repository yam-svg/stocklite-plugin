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
import com.stocklite.plugin.ui.dialogs.AddTradeRecordDialog
import com.stocklite.plugin.ui.dialogs.ClosePositionDialog
import com.stocklite.plugin.ui.dialogs.FundHoldingsDialog
import com.stocklite.plugin.ui.dialogs.ManageGroupsDialog
import com.stocklite.plugin.ui.dialogs.SetAlertDialog
import com.stocklite.plugin.ui.dialogs.TradeHistoryDialog
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

    /** changePercent 列的值包装：主涨跌幅 + 可选的盘前/盘后信息 */
    private data class ChangePctValue(
        val changePct: Double,
        val extPct: Double?,
        val session: ExtendedSession?
    )

    /** 能正确渲染 ChangePctValue 的 renderer（主行着色 + 盘前/盘后小字行） */
    private inner class ChangePctRenderer : QuoteRenderer(QuoteColumnType.PCT) {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): java.awt.Component {
            val wrapped = value as? ChangePctValue
            val mainPct = wrapped?.changePct ?: (value as? Double) ?: 0.0
            val comp = super.getTableCellRendererComponent(table, mainPct, isSelected, hasFocus, row, column)
                    as javax.swing.JLabel

            if (wrapped?.extPct != null && wrapped.session != null) {
                val sessionLabel = when (wrapped.session) {
                    ExtendedSession.PRE_MARKET  -> L10n.lblPreMarket
                    ExtendedSession.POST_MARKET -> L10n.lblPostMarket
                }
                val sign   = if (wrapped.extPct >= 0) "+" else ""
                val extStr = "$sign${"%.2f".format(wrapped.extPct)}%"
                val mainColor = comp.foreground
                val hex = "#%02x%02x%02x".format(mainColor.red, mainColor.green, mainColor.blue)
                val mainStr = Fmt.pct(mainPct)
                comp.text = "<html><center>" +
                    "<span style='color:$hex'>$mainStr</span><br>" +
                    "<span style='font-size:85%;color:gray'>$sessionLabel $extStr</span>" +
                    "</center></html>"
            }
            return comp
        }
    }

    /** 名称列 renderer：临近财报时在名称后附加彩色圆点 */
    private inner class EarningsNameRenderer : QuoteRenderer(QuoteColumnType.PLAIN) {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                as javax.swing.JLabel
            val modelRow = try { table.convertRowIndexToModel(row) } catch (_: Exception) { row }
            val dot = earningsDotMap[modelRow]
            if (dot != null) {
                comp.text = "<html>${value ?: ""}  <span style='color:$dot'>●</span></html>"
            }
            return comp
        }
    }

    private data class ColDef(
        val key: String, val title: String, val type: QuoteColumnType,
        val alwaysOn: Boolean = false,
        val getValue: (StockData, StockQuote?) -> Any
    )

    private val allCols get() = listOf(
        ColDef("name",         L10n.colName,      QuoteColumnType.PLAIN, true)  { s, _  -> s.alias.ifBlank { s.name } },
        ColDef("symbol",       L10n.colSymbol,    QuoteColumnType.PLAIN)        { s, _  -> s.symbol },
        ColDef("quantity",     L10n.colQty,       QuoteColumnType.QTY)          { s, _  -> s.quantity },
        ColDef("cost",         L10n.colCost,      QuoteColumnType.PRICE)        { s, _  -> s.costPrice },
        ColDef("price",        L10n.colPrice,     QuoteColumnType.PRICE, true)  { _, q  -> q?.price ?: 0.0 },
        ColDef("changePercent",L10n.colChangePct, QuoteColumnType.PCT,   true)  { _, q  ->
            val pct = q?.changePercent ?: 0.0
            if (q?.extendedSession != null)
                ChangePctValue(pct, q.extendedChangePercent, q.extendedSession)
            else pct
        },
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
    /** 财报日期缓存：纯6位代码 -> (上次财报日, 下次财报日)，当日缓存，不跨天 */
    private val earningsCache = mutableMapOf<String, Pair<String?, String?>>()
    private var earningsCacheDate = ""
    /** modelRow -> tooltip HTML */
    private val earningsTooltipMap = mutableMapOf<Int, String>()
    /** modelRow -> 小红点/橙点颜色（临近财报） */
    private val earningsDotMap = mutableMapOf<Int, String>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()            = rows.size
        override fun getColumnCount()         = visibleCols.size
        override fun getColumnName(col: Int)  = visibleCols[col].title
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> {
            if (col >= visibleCols.size) return String::class.java
            val colDef = visibleCols[col]
            // changePercent 列的值可能是 ChangePctValue 或 Double，统一声明为 Any
            if (colDef.key == "changePercent") return Any::class.java
            return if (colDef.type != QuoteColumnType.PLAIN) Double::class.java else String::class.java
        }
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
    private lateinit var upBtn:      JButton
    private lateinit var downBtn:    JButton
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
        val sorter = TableRowSorter(tableModel)
        // changePercent 列值为 ChangePctValue 或 Double，统一用 changePct 字段排序
        visibleCols.indexOfFirst { it.key == "changePercent" }.takeIf { it >= 0 }?.let { colIdx ->
            sorter.setComparator(colIdx, Comparator<Any?> { a, b ->
                fun toDouble(v: Any?) = (v as? ChangePctValue)?.changePct ?: (v as? Double) ?: 0.0
                toDouble(a).compareTo(toDouble(b))
            })
        }
        table.rowSorter = sorter
        applyRenderers()
        restoreColumnWidths()
        installColumnWidthListener()
    }

    private fun applyRenderers() {
        visibleCols.forEachIndexed { i, col ->
            if (i < table.columnModel.columnCount) {
                table.columnModel.getColumn(i).cellRenderer = when (col.key) {
                    "changePercent" -> ChangePctRenderer()
                    "name"          -> EarningsNameRenderer()
                    else            -> QuoteRenderer(col.type)
                }
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

        // 左键：点击涨跌幅列展开图表；点击名称列对 ETF 弹出持仓明细
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val viewCol = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
                if (SwingUtilities.isRightMouseButton(e)) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= rows.size) return
                val (s, q) = rows[modelRow]
                when (table.getColumnName(viewCol)) {
                    L10n.colChangePct -> chartPanel.showChart(
                        displayName   = s.alias.ifBlank { s.name },
                        displaySymbol = s.symbol,
                        changePercent = q?.changePercent ?: 0.0,
                        prevClose     = q?.prevClose ?: 0.0,
                        fetchData     = { ChartDataService.getStockIntraday(s.symbol) }
                    )
                    L10n.colName -> if (isEtfLike(s.symbol, s.name)) {
                        FundHoldingsDialog(
                            fundName = s.alias.ifBlank { s.name },
                            fundCode = toPureCode(s.symbol)
                        ).show()
                    }
                }
            }

            // 右键菜单
            override fun mousePressed(e: MouseEvent)  { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (SwingUtilities.isRightMouseButton(e)) showContextMenu(e) }
        })

        table.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val col     = table.columnAtPoint(e.point)
                val viewRow = table.rowAtPoint(e.point)
                // 鼠标指针：涨跌幅列和 ETF 名称列显示手形
                val colName = if (col >= 0) table.getColumnName(col) else ""
                val isHandCursor = colName == L10n.colChangePct ||
                    (colName == L10n.colName && viewRow >= 0 && run {
                        val mr = try { table.convertRowIndexToModel(viewRow) } catch (_: Exception) { -1 }
                        mr >= 0 && mr < rows.size && rows[mr].first.let { isEtfLike(it.symbol, it.name) }
                    })
                table.cursor = if (isHandCursor) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                               else Cursor.getDefaultCursor()
                // 名称列：显示财报日期 tooltip
                if (col >= 0 && table.getColumnName(col) == L10n.colName && viewRow >= 0) {
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    table.toolTipText = earningsTooltipMap[modelRow]
                } else {
                    table.toolTipText = null
                }
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
        popup.add(JMenuItem(L10n.btnAddTrade).also { it.addActionListener {
            AddTradeRecordDialog(s) { type, price, qty, tradeAt, note ->
                try {
                    state.addTradeRecordAndSync(s.id, s.symbol, s.name, type, price, qty, tradeAt, note)
                    loadRows()
                    fetchQuotesAsync()
                } catch (ex: IllegalArgumentException) {
                    JOptionPane.showMessageDialog(this, ex.message,
                        L10n.dlgConfirmTitle, JOptionPane.ERROR_MESSAGE)
                }
            }.show()
        }})
        popup.add(JMenuItem(L10n.btnTradeHistory).also { it.addActionListener {
            TradeHistoryDialog(s) {
                loadRows()
                fetchQuotesAsync()
            }.show()
        }})
        // 清仓：仅持仓时显示
        if (s.quantity > 0) {
            popup.add(JMenuItem(L10n.btnClosePosition).also { it.addActionListener {
                val currentPrice = q?.price ?: 0.0
                ClosePositionDialog(s, currentPrice) { price, tradeAt, note ->
                    try {
                        state.addTradeRecordAndSync(s.id, s.symbol, s.name, "SELL",
                            price, s.quantity, tradeAt, note)
                        loadRows()
                        fetchQuotesAsync()
                    } catch (ex: IllegalArgumentException) {
                        JOptionPane.showMessageDialog(this, ex.message,
                            L10n.dlgConfirmTitle, JOptionPane.ERROR_MESSAGE)
                    }
                }.show()
            }})
        }
        // ETF/LOF/指数基金：显示持仓明细入口
        if (isEtfLike(s.symbol, s.name)) {
            popup.add(JMenuItem(L10n.btnFundHoldings).also { it.addActionListener {
                FundHoldingsDialog(
                    fundName = s.alias.ifBlank { s.name },
                    fundCode = toPureCode(s.symbol)
                ).show()
            }})
        }
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

    /**
     * 判断股票代码是否为 ETF/LOF/指数基金，可以用基金持仓明细接口查持仓。
     * 规则：A 股代码前缀匹配常见 ETF/LOF 号段，或名称含"ETF"/"LOF"/"指数"。
     */
    private fun isEtfLike(symbol: String, name: String): Boolean {
        val code = symbol.lowercase()
        val etfPrefixes = listOf(
            "sh51", "sh15", "sh56", "sh58", "sh50",   // 沪市 ETF
            "sz15", "sz16", "sz18", "sz16"              // 深市 ETF/LOF
        )
        if (etfPrefixes.any { code.startsWith(it) }) return true
        val upperName = name.uppercase()
        return upperName.contains("ETF") || upperName.contains("LOF") ||
               upperName.contains("指数") || upperName.contains("增强")
    }

    /** 从 sh600519 / sz000858 格式提取纯6位基金代码 */
    private fun toPureCode(symbol: String): String =
        symbol.removePrefix("sh").removePrefix("sz").removePrefix("hk")

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
        upBtn        = JButton("↑")
        downBtn      = JButton("↓")
        refreshBtn   = JButton(L10n.btnRefresh)
        filterField  = SearchTextField().also { it.preferredSize = Dimension(120, 26) }

        upBtn.isVisible   = false
        downBtn.isVisible = false

        toolbar.add(groupLbl);  toolbar.add(groupCombo)
        toolbar.add(refreshBtn)
        toolbar.add(manageBtn); toolbar.add(addBtn)
        toolbar.add(upBtn);     toolbar.add(downBtn)
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

    private fun moveRowTo(fromModelRow: Int, toModelRow: Int) {
        state.stocks.sortedBy { it.sortOrder }.forEachIndexed { i, s -> s.sortOrder = i }
        val groupItems = state.getStocksForGroup(currentGroupId)
        if (fromModelRow !in groupItems.indices || toModelRow !in groupItems.indices) return
        val sortOrders = groupItems.map { g -> state.stocks.find { it.id == g.id }!!.sortOrder }.sorted()
        val mutable = groupItems.toMutableList()
        mutable.add(toModelRow, mutable.removeAt(fromModelRow))
        mutable.forEachIndexed { i, data -> state.stocks.find { it.id == data.id }?.sortOrder = sortOrders[i] }
        loadRows()
        table.setRowSelectionInterval(toModelRow, toModelRow)
        table.scrollRectToVisible(table.getCellRect(toModelRow, 0, true))
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
        adjustRowHeights()
        updateSummary()
    }

    /** 有盘前/盘后数据的行显示两行文字，需要更高的行高 */
    private fun adjustRowHeights() {
        rows.forEachIndexed { modelRow, (_, q) ->
            val viewRow = try { table.convertRowIndexToView(modelRow) } catch (_: Exception) { modelRow }
            if (viewRow >= 0) {
                val needsTwoLines = q?.extendedSession != null
                table.setRowHeight(viewRow, if (needsTwoLines) 34 else 24)
            }
        }
    }

    private fun updateSummary() {
        val totalValue = rows.sumOf { (s, q) -> (q?.price ?: 0.0) * s.quantity }
        val totalPnl   = rows.sumOf { (s, q) ->
            val p = q?.price ?: 0.0; if (p > 0) (p - s.costPrice) * s.quantity else 0.0
        }
        val todayPnl   = rows.sumOf { (s, q) ->
            MarketTimeUtil.calcTodayPnl(s, q, state.getTradeRecordsForStock(s.id))
        }
        fun sign(v: Double) = if (v >= 0) "+" else ""
        summaryLabel.text = buildString {
            append("${L10n.lblTotalValue} ${Fmt.value(totalValue)}")
            append("   ${L10n.lblTotalPnl} ${sign(totalPnl)}${Fmt.value(totalPnl)}")
            append("   ${L10n.lblTodayPnl} ${sign(todayPnl)}${Fmt.value(todayPnl)}")
        }
        statusLabel.text = MarketTimeUtil.getMarketStatusText()

        // 推送全量持仓到 IDE 状态栏 widget（不受当前分组过滤影响）
        if (!state.enablePortfolioStatusBar) {
            PortfolioStatusWidget.update(0.0, 0.0, 0.0, emptyList())
            return
        }
        val allHoldings = state.stocks
            .filter { it.quantity > 0 }
            .map { s ->
                val q = quotes[s.symbol]
                val price = q?.price ?: 0.0
                PortfolioStatusWidget.HoldingRow(
                    name      = s.name,      symbol   = s.symbol,
                    qty       = s.quantity,  price    = price,    cost = s.costPrice,
                    changePct = q?.changePercent ?: 0.0,
                    pnl       = if (price > 0) (price - s.costPrice) * s.quantity else 0.0,
                    todayPnl  = MarketTimeUtil.calcTodayPnl(s, q, state.getTradeRecordsForStock(s.id))
                )
            }
        PortfolioStatusWidget.update(
            totalValue = allHoldings.sumOf { it.price * it.qty },
            totalPnl   = allHoldings.sumOf { it.pnl },
            todayPnl   = allHoldings.sumOf { it.todayPnl },
            holdings   = allHoldings
        )
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
                adjustRowHeights()
                updateSummary()
                aiPanel.updateContext(buildAiContext())
            }
        }
        // 财报日期异步加载（仅 A 股，低频：当日首次加载后缓存全天）
        fetchEarningsDatesAsync()
    }

    /** 异步加载当前行中 A 股的财报日期，设置到名称列 tooltip */
    private fun fetchEarningsDatesAsync() {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        if (earningsCacheDate != today) { earningsCache.clear(); earningsCacheDate = today }

        // 只处理 A 股（sh/sz 前缀），且不在缓存中的
        val pending = rows.map { it.first }
            .filter { s ->
                (s.symbol.startsWith("sh") || s.symbol.startsWith("sz")) &&
                !earningsCache.containsKey(s.symbol.drop(2))
            }
            .map { it.symbol.drop(2) }
            .distinct()

        if (pending.isEmpty()) { applyEarningsTooltips(); return }

        ApplicationManager.getApplication().executeOnPooledThread {
            val results = MarketDataService.getEarningsDates(pending)
            SwingUtilities.invokeLater {
                earningsCache.putAll(results)
                applyEarningsTooltips()
            }
        }
    }

    private fun applyEarningsTooltips() {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val nameColIdx = visibleCols.indexOfFirst { it.key == "name" }.takeIf { it >= 0 } ?: return

        rows.forEachIndexed { modelRow, (s, _) ->
            val pureCode = when {
                s.symbol.startsWith("sh") || s.symbol.startsWith("sz") -> s.symbol.drop(2)
                else -> return@forEachIndexed   // 港股/美股暂不支持
            }
            val (last, next) = earningsCache[pureCode] ?: return@forEachIndexed
            if (last == null && next == null) return@forEachIndexed

            val viewRow = try { table.convertRowIndexToView(modelRow) } catch (_: Exception) { modelRow }
            if (viewRow < 0) return@forEachIndexed

            // 计算距下次财报天数，临近30天高亮
            val daysToNext = next?.let {
                try { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(it)).toInt() }
                catch (_: Exception) { null }
            }

            val tip = buildString {
                append("<html>")
                if (next != null) {
                    val color = when {
                        daysToNext != null && daysToNext <= 7  -> "#f44336"   // 红色：一周内
                        daysToNext != null && daysToNext <= 30 -> "#ff9800"   // 橙色：一个月内
                        else -> "#888aaa"
                    }
                    val daysStr = if (daysToNext != null) "（${daysToNext}天后）" else ""
                    append("<b style='color:$color'>${L10n.lblEarningsNext}：$next$daysStr</b>")
                }
                if (last != null) {
                    if (next != null) append("<br/>")
                    append("<span style='color:#888aaa'>${L10n.lblEarningsLast}：$last</span>")
                }
                append("</html>")
            }

            // 设置名称列该行的 tooltip（通过 JTable 的 cell renderer 不方便，
            // 改用 table.setToolTipText 在 mouseMoved 里动态设置）
            // 这里存入一个 map，由 mouseMoved 事件读取
            earningsTooltipMap[modelRow] = tip

            // 如果临近30天，在名称旁追加小红点标注
            if (daysToNext != null && daysToNext <= 30) {
                val dotColor = if (daysToNext <= 7) "#f44336" else "#ff9800"
                earningsDotMap[modelRow] = dotColor
            } else {
                earningsDotMap.remove(modelRow)
            }
        }
        tableModel.fireTableDataChanged()
    }

    private fun buildAiContext(): String {
        val valid = rows.filter { (_, q) -> q != null }.ifEmpty { return "" }

        val holdings  = valid.filter { (s, _) -> s.quantity > 0 }
        val watchlist = valid.filter { (s, _) -> s.quantity <= 0 }

        val sb = StringBuilder()

        if (holdings.isNotEmpty()) {
            sb.appendLine("【我的持仓】（${holdings.size} 只）")
            for ((s, q) in holdings) {
                val chgSign = if (q!!.changePercent >= 0) "+" else ""
                val price   = q.price
                val pnl     = if (price > 0) (price - s.costPrice) * s.quantity else 0.0
                val pnlPct  = if (price > 0 && s.costPrice > 0)
                                  (price - s.costPrice) / s.costPrice * 100.0 else 0.0
                val pnlSign = if (pnl >= 0) "+" else ""
                sb.appendLine(
                    "- ${s.name}(${s.symbol}): ${"%.3f".format(price)}  " +
                    "$chgSign${"%.2f".format(q.changePercent)}%  " +
                    "成本${"%.3f".format(s.costPrice)}  持仓${"%.0f".format(s.quantity)}股  " +
                    "盈亏$pnlSign${"%.2f".format(pnl)}($pnlSign${"%.2f".format(pnlPct)}%)"
                )
            }
        }

        if (watchlist.isNotEmpty()) {
            if (holdings.isNotEmpty()) sb.appendLine()
            sb.appendLine("【自选（未持仓）】（${watchlist.size} 只）")
            for ((s, q) in watchlist) {
                val sign = if (q!!.changePercent >= 0) "+" else ""
                sb.appendLine("- ${s.name}(${s.symbol}): ${"%.3f".format(q.price)}  $sign${"%.2f".format(q.changePercent)}%")
            }
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
