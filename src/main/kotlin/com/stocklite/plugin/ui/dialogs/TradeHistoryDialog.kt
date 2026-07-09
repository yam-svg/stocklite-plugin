package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.state.StockData
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.state.TradeRecordData
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class TradeHistoryDialog(
    private val stock: StockData,
    private val onRecordsChanged: () -> Unit = {}
) : DialogWrapper(true) {

    private val state = StockliteState.getInstance()
    private val shZone = ZoneId.of("Asia/Shanghai")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(shZone)

    private var records: MutableList<TradeRecordData> = mutableListOf()

    private val COL_DATE  = 0
    private val COL_TYPE  = 1
    private val COL_PRICE = 2
    private val COL_QTY   = 3
    private val COL_NOTE  = 4

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount() = records.size
        override fun getColumnCount() = 5
        override fun getColumnName(col: Int) = when (col) {
            COL_DATE  -> L10n.colTradeDate
            COL_TYPE  -> L10n.colTradeType
            COL_PRICE -> L10n.colTradePrice
            COL_QTY   -> L10n.colTradeQty
            COL_NOTE  -> L10n.colTradeNote
            else -> ""
        }
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> = when (col) {
            COL_PRICE, COL_QTY -> Double::class.javaObjectType
            else -> String::class.java
        }
        override fun getValueAt(row: Int, col: Int): Any {
            val r = records[row]
            return when (col) {
                COL_DATE  -> if (r.tradeAt > 0) dateFmt.format(Instant.ofEpochMilli(r.tradeAt)) else "--"
                COL_TYPE  -> when (r.tradeType) {
                    "BUY"    -> L10n.tradeTypeBuy
                    "SELL"   -> L10n.tradeTypeSell
                    "ADJUST" -> L10n.tradeTypeAdjust
                    else     -> r.tradeType
                }
                COL_PRICE -> r.price
                COL_QTY   -> r.quantity
                COL_NOTE  -> r.note
                else -> ""
            }
        }
    }

    private val table       = JBTable(tableModel)
    private val addBtn      = JButton(L10n.btnAddTrade2)
    private val summaryLbl  = JLabel("").apply { font = font.deriveFont(11f); foreground = Color(0x888aaa) }

    init {
        title = "${stock.alias.ifBlank { stock.name }}  ${L10n.dlgTradeHistory}"
        isModal = true
        init()
        loadRecords()
        setupTable()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(600, 380)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        summaryLbl.text = buildSummary()
        panel.add(summaryLbl, BorderLayout.NORTH)

        panel.add(JBScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> =
        arrayOf(cancelAction.also { it.putValue(Action.NAME, L10n.btnClose) })

    override fun createSouthAdditionalPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(addBtn) }

    private fun buildSummary(): String {
        val buyQty  = records.filter { it.tradeType == "BUY"  }.sumOf { it.quantity }
        val sellQty = records.filter { it.tradeType == "SELL" }.sumOf { it.quantity }
        return "${L10n.tradeTypeBuy} ${Fmt.qty(buyQty)} 股 / ${L10n.tradeTypeSell} ${Fmt.qty(sellQty)} 股 / 共 ${records.size} 条记录"
    }

    private fun setupTable() {
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.rowHeight = 24
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowSorter = TableRowSorter(tableModel)

        // 列宽
        table.columnModel.getColumn(COL_DATE).preferredWidth  = 100
        table.columnModel.getColumn(COL_TYPE).preferredWidth  = 60
        table.columnModel.getColumn(COL_PRICE).preferredWidth = 90
        table.columnModel.getColumn(COL_QTY).preferredWidth   = 90
        table.columnModel.getColumn(COL_NOTE).preferredWidth  = 160

        // 居中
        val center = DefaultTableCellRenderer().also { it.horizontalAlignment = SwingConstants.CENTER }
        for (i in 0..4) table.columnModel.getColumn(i).cellRenderer = center

        // 类型列着色
        val scheme = StockliteState.getInstance().colorScheme
        val up = QuoteRenderer.positiveColor(scheme)
        val dn = QuoteRenderer.negativeColor(scheme)
        table.columnModel.getColumn(COL_TYPE).cellRenderer =
            object : DefaultTableCellRenderer() {
                init { horizontalAlignment = SwingConstants.CENTER }
                override fun getTableCellRendererComponent(
                    t: JTable?, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int
                ): Component {
                    val comp = super.getTableCellRendererComponent(t, v, sel, foc, row, col)
                    if (!sel) foreground = when (v?.toString()) {
                        L10n.tradeTypeBuy  -> up ?: QuoteRenderer.FLAT
                        L10n.tradeTypeSell -> dn ?: QuoteRenderer.FLAT
                        else               -> QuoteRenderer.FLAT
                    }
                    return comp
                }
            }

        // 右键删除
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                table.setRowSelectionInterval(row, row)
                val modelRow = table.convertRowIndexToModel(row)
                if (modelRow < 0 || modelRow >= records.size) return
                val popup = JPopupMenu()
                popup.add(JMenuItem(L10n.btnDelete).also { mi ->
                    mi.addActionListener {
                        val rec = records[modelRow]
                        state.deleteTradeRecord(rec.id)
                        loadRecords()
                        onRecordsChanged()
                    }
                })
                popup.show(table, e.x, e.y)
            }
        })

        addBtn.addActionListener {
            AddTradeRecordDialog(stock) { type, price, qty, tradeAt, note ->
                state.addTradeRecordAndSync(stock.id, stock.symbol, stock.name, type, price, qty, tradeAt, note)
                loadRecords()
                onRecordsChanged()
            }.show()
        }
    }

    private fun loadRecords() {
        records = state.getTradeRecordsForStock(stock.id).toMutableList()
        tableModel.fireTableDataChanged()
        summaryLbl.text = buildSummary()
    }
}
