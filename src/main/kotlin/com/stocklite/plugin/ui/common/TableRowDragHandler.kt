package com.stocklite.plugin.ui.common

import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.*
import javax.swing.table.TableRowSorter

/**
 * 为 JTable 安装行拖拽排序支持。
 * onMove(fromModelRow, toModelRow) 在拖放完成后调用，语义为“先把 from 行移出，再插入到 toModelRow 位置”（to 为最终位置）。
 * 调用方负责更新数据和刷新表格。
 */
object TableRowDragHandler {

    private val DROP_LINE_KEYS = arrayOf("Table.dropLineColor", "Table.dropLineShortColor")

    fun install(table: JTable, onMove: (fromModelRow: Int, toModelRow: Int) -> Unit) {
        table.dragEnabled = true
        table.dropMode = DropMode.INSERT_ROWS

        // JDK 用主题的 Table.dropLineColor 画插入指示线（3px），该键在 IDE 主题里常为灰色网格线色甚至未注册，
        // 拖拽时几乎不可见。拖拽期间临时换成主题选中蓝色，结束后还原。
        val dropColor = UIManager.getColor("Table.selectionBackground") ?: Color(0x35, 0x74, 0xF0)
        var savedColors: Array<Any?>? = null
        fun highlightOn() {
            if (savedColors != null) return
            savedColors = Array(DROP_LINE_KEYS.size) { UIManager.get(DROP_LINE_KEYS[it]) }
            DROP_LINE_KEYS.forEach { UIManager.put(it, dropColor) }
        }
        fun highlightOff() {
            savedColors?.let { saved -> DROP_LINE_KEYS.forEachIndexed { i, k -> UIManager.put(k, saved[i]) } }
            savedColors = null
        }
        // 拖拽移出表格或被取消时 dropLocation 置 null，此时恢复原色
        table.addPropertyChangeListener("dropLocation") { e -> if (e.newValue == null) highlightOff() }

        table.transferHandler = object : TransferHandler() {

            override fun getSourceActions(c: JComponent) = MOVE

            override fun createTransferable(c: JComponent): Transferable? {
                val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return null
                highlightOn()
                return StringSelection(table.convertRowIndexToModel(viewRow).toString())
            }

            override fun canImport(support: TransferSupport) =
                support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

            override fun importData(support: TransferSupport): Boolean {
                highlightOff()
                if (!canImport(support)) return false
                val dl = support.dropLocation as? JTable.DropLocation ?: return false
                val rowCount = table.rowCount
                if (rowCount == 0) return false
                val fromModelRow = support.transferable
                    .getTransferData(DataFlavor.stringFlavor).toString().toIntOrNull() ?: return false

                // INSERT_ROWS 的 dl.row 是插入点索引（含被拖行自身）：悬停在第 r 行上半=插入到 r 前，下半=插到 r 后，
                // ==rowCount 表示放到末尾。而调用方契约是“先移出再插入到 to”，向下移动时需 -1，
                // 否则落点比插入线低一格（旧版向下拖动不跟手的根因）。
                val raw = dl.row.coerceIn(0, rowCount)
                val toModelRow = if (raw >= rowCount) {
                    rowCount - 1
                } else {
                    val m = table.convertRowIndexToModel(raw)
                    if (fromModelRow < m) m - 1 else m
                }
                if (fromModelRow == toModelRow) return false
                (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
                onMove(fromModelRow, toModelRow)
                return true
            }
        }
    }
}
