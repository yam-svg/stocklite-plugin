package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.ImportCandidate
import com.stocklite.plugin.state.ImportStatus
import com.stocklite.plugin.state.StockGroupData
import com.stocklite.plugin.util.L10n
import com.stocklite.plugin.util.StockTextParser
import java.awt.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/** 批量导入确认后的单条添加请求，字段与 state.createStock 对齐 */
data class BatchImportItem(
    val symbol: String,
    val name: String,
    val groupId: String,
    val cost: Double,
    val qty: Double
)

/**
 * 批量导入股票：
 * 1. 粘贴一大段文本 → StockTextParser 纯词法拆分
 * 2. MarketDataService.resolveImportCandidates 批量校验（带进度）
 * 3. 表格勾选可添加的股票，逐行可指定分组（也可把勾选行统一分配到某分组）
 * 4. 成本默认回填现价、数量默认 0 —— 与单只添加逻辑一致
 */
class BatchImportStockDialog(
    private val groups: List<StockGroupData>,
    private val defaultGroupId: String,
    private val existingSymbols: Set<String>,
    private val onImport: (List<BatchImportItem>) -> Unit
) : DialogWrapper(null, true) {

    private val groupNames = groups.map { it.name }.toTypedArray()
    private val defaultGroupIndex = groups.indexOfFirst { it.id == defaultGroupId }.takeIf { it >= 0 } ?: 0
    /** 会话内可变副本：本窗口添加过的股票再次解析时也能标"已存在" */
    private val existingSet = existingSymbols.toMutableSet()

    private fun isImportable(s: ImportStatus) = s == ImportStatus.OK || s == ImportStatus.AMBIGUOUS

    /** 表格一行：校验结果 + 用户可编辑的选择/分组/成本/数量 */
    private inner class Row(var cand: ImportCandidate) {
        val importable: Boolean get() = isImportable(cand.status)
        var checked: Boolean = importable
        var groupIndex: Int = defaultGroupIndex
        var cost: String = if (cand.price > 0) String.format("%.2f", cand.price) else "0"
        var qty: String  = "0"
    }

    private val rows  = mutableListOf<Row>()
    private val busy  = AtomicBoolean(false)

    private val pasteArea   = JTextArea(5, 40)
    private val parseBtn    = JButton(L10n.biParse)
    private val statusLabel = JLabel(" ")

    private val tableModel = object : DefaultTableModel(
        arrayOf(L10n.biColCheck, L10n.colSymbol, L10n.colName, L10n.colCost, L10n.colQty, L10n.biColGroup, L10n.biColStatus), 0
    ) {
        override fun getRowCount(): Int = rows.size
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int): Boolean {
            val r = rows.getOrNull(row) ?: return false
            return when (column) {
                0, 5 -> r.importable
                3, 4 -> r.importable && r.checked
                else -> false
            }
        }
        override fun getValueAt(row: Int, col: Int): Any {
            val r = rows[row]
            return when (col) {
                0 -> r.checked
                1 -> r.cand.symbol
                2 -> r.cand.name
                3 -> r.cost
                4 -> r.qty
                5 -> groupNames.getOrElse(r.groupIndex) { "" }
                else -> statusText(r)
            }
        }
        override fun setValueAt(aValue: Any?, row: Int, col: Int) {
            val r = rows.getOrNull(row) ?: return
            when (col) {
                0 -> { r.checked = aValue as? Boolean ?: false; updateSummary() }
                3 -> r.cost = aValue?.toString() ?: ""
                4 -> r.qty  = aValue?.toString() ?: ""
                5 -> r.groupIndex = (aValue as? String)?.let { groupNames.indexOf(it) }.takeIf { it != null && it >= 0 } ?: r.groupIndex
            }
            fireTableCellUpdated(row, col)
        }
    }

    private val table = JBTable(tableModel)

    init {
        title = L10n.dlgBatchImport
        init()
        isOKActionEnabled = false
        // OK = 添加当前勾选（窗口保持打开，可分批继续操作）；关闭用右侧按钮
        setOKButtonText(L10n.btnBatchImport)
        setCancelButtonText(L10n.btnClose)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 6))
        panel.preferredSize = Dimension(760, 600)

        // ── 粘贴区 ──
        val pastePanel = JPanel(BorderLayout(4, 4))
        pastePanel.add(JLabel(L10n.biPasteHint), BorderLayout.NORTH)
        pastePanel.add(JBScrollPane(pasteArea), BorderLayout.CENTER)
        val parseBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        parseBar.add(parseBtn)
        parseBar.add(statusLabel)
        pastePanel.add(parseBar, BorderLayout.SOUTH)
        panel.add(pastePanel, BorderLayout.NORTH)

        // ── 操作条 + 表格 ──
        setupTable()
        val selectValidBtn = JButton(L10n.biSelectAllValid)
        val clearBtn       = JButton(L10n.biClearAll)
        val assignCombo    = JComboBox(groupNames)
        selectValidBtn.addActionListener {
            rows.forEach { if (it.importable) it.checked = true }
            if (rows.isNotEmpty()) tableModel.fireTableRowsUpdated(0, rows.size - 1)
            updateSummary()
        }
        clearBtn.addActionListener {
            rows.forEach { it.checked = false }
            if (rows.isNotEmpty()) tableModel.fireTableRowsUpdated(0, rows.size - 1)
            updateSummary()
        }
        // 变更下拉即把分组应用到当前勾选的所有行
        assignCombo.addActionListener {
            val idx = assignCombo.selectedIndex
            if (idx < 0 || rows.isEmpty()) return@addActionListener
            rows.forEach { if (it.checked && it.importable) it.groupIndex = idx }
            tableModel.fireTableRowsUpdated(0, rows.size - 1)
        }

        val listBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        listBar.add(selectValidBtn); listBar.add(clearBtn)
        listBar.add(Box.createHorizontalStrut(16))
        listBar.add(JLabel(L10n.biAssignChecked)); listBar.add(assignCombo)

        val center = JPanel(BorderLayout(0, 4))
        center.add(listBar, BorderLayout.NORTH)
        center.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)

        parseBtn.addActionListener { doParse() }
        return panel
    }

    private fun setupTable() {
        val model = table.columnModel
        fun col(i: Int, width: Int) {
            model.getColumn(i).apply {
                preferredWidth = width
                maxWidth = if (i == 2) Int.MAX_VALUE else width + 40
            }
        }
        col(0, 50); col(1, 90); col(2, 160); col(3, 80); col(4, 70); col(5, 110); col(6, 150)

        // 分组列：下拉编辑；状态列：按校验结果着色 + 悬浮提示原文/同名候选
        model.getColumn(5).cellEditor = DefaultCellEditor(JComboBox(groupNames))
        model.getColumn(6).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
                val r = rows.getOrNull(t.convertRowIndexToModel(row))
                if (r != null && !isSelected) {
                    c.foreground = when (r.cand.status) {
                        ImportStatus.OK        -> if (r.cand.nameCorrected) Color(0xd6b355) else Color(0x66bb6a)
                        ImportStatus.NOT_FOUND -> Color(0x888aaa)
                        ImportStatus.EXISTS    -> Color(0xe0a040)
                        ImportStatus.AMBIGUOUS -> Color(0xe0a040)
                    }
                    toolTipText = buildString {
                        append(L10n.biTipRaw); append(r.cand.raw)
                        if (r.cand.alternates.isNotEmpty()) {
                            append("\n"); append(L10n.biTipAmbiguous); append(r.cand.alternates.joinToString(", "))
                        }
                    }
                }
                return c
            }
        }
        table.rowHeight = 24
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    }

    // ── 解析 + 校验 ───────────────────────────────────────────────────────

    private fun doParse() {
        if (!busy.compareAndSet(false, true)) return
        parseBtn.isEnabled = false
        val tokens = StockTextParser.parse(pasteArea.text)
        if (tokens.isEmpty()) {
            JOptionPane.showMessageDialog(contentPane, L10n.biParsedNoResult)
            busy.set(false); parseBtn.isEnabled = true
            return
        }
        statusLabel.text = "${L10n.biResolving} 0/${tokens.size}"
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = try {
                MarketDataService.resolveImportCandidates(tokens, existingSet) { done, total ->
                    SwingUtilities.invokeLater { statusLabel.text = "${L10n.biResolving} $done/$total" }
                }
            } catch (_: Exception) { emptyList() }
            SwingUtilities.invokeLater {
                rows.clear()
                // 可添加的排上面，未找到/已存在沉底（组内保持原文顺序，排序稳定）
                candidates.sortedBy { !isImportable(it.status) }.forEach { rows.add(Row(it)) }
                tableModel.fireTableDataChanged()
                updateSummary()
                if (tokens.size >= StockTextParser.MAX_ENTRIES)
                    statusLabel.text += "  ·  " + L10n.biTooMany.replace("{0}", StockTextParser.MAX_ENTRIES.toString())
                busy.set(false)
                parseBtn.isEnabled = true
            }
        }
    }

    private fun updateSummary() {
        val importable = rows.count { it.importable }
        val selected   = rows.count { it.importable && it.checked }
        statusLabel.text = "${L10n.biCheckedSummary} $importable · ${L10n.biSelectedSummary} $selected"
        isOKActionEnabled = selected > 0
    }

    private fun statusText(r: Row): String = when (r.cand.status) {
        ImportStatus.OK        -> if (r.cand.nameCorrected) "${L10n.biStOk} · ${L10n.biStNameCorrected}" else L10n.biStOk
        ImportStatus.NOT_FOUND -> L10n.biStNotFound
        ImportStatus.EXISTS    -> L10n.biStExists
        ImportStatus.AMBIGUOUS -> L10n.biStAmbiguous
    }

    // ── 确认添加（不关窗：可继续勾选下一批，按"关闭"退出）─────────────────

    override fun doOKAction() {
        val selected = rows.filter { it.importable && it.checked }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(contentPane, L10n.biNothingChecked)
            return
        }
        val items = ArrayList<BatchImportItem>(selected.size)
        for (r in selected) {
            val cost = r.cost.trim().toDoubleOrNull()
            val qty  = r.qty.trim().toDoubleOrNull()
            if (cost == null || qty == null) {
                JOptionPane.showMessageDialog(contentPane, L10n.biBadNumber.replace("{0}", r.cand.name))
                return
            }
            if (cost < 0)  { JOptionPane.showMessageDialog(contentPane, L10n.validationCostPositive()); return }
            if (qty  < 0)  { JOptionPane.showMessageDialog(contentPane, L10n.validationQtyNotNegative()); return }
            items.add(BatchImportItem(r.cand.symbol, r.cand.name, groups.getOrNull(r.groupIndex)?.id ?: defaultGroupId, cost, qty))
        }
        onImport(items)
        // 已添加的行标"已存在"并沉底，方便继续勾选其余股票
        items.forEach { existingSet.add(it.symbol) }
        selected.forEach {
            it.cand   = it.cand.copy(status = ImportStatus.EXISTS)
            it.checked = false
        }
        rows.sortBy { !it.importable }
        tableModel.fireTableDataChanged()
        updateSummary()
        statusLabel.text = L10n.biAdded.replace("{0}", items.size.toString()) + " · " + statusLabel.text
    }
}
