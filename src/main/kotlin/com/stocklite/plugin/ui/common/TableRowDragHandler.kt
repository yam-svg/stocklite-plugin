package com.stocklite.plugin.ui.common

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.table.TableRowSorter

/**
 * 为 JTable 安装行拖拽排序支持。
 * onMove(fromModelRow, toModelRow) 在拖放完成后调用，调用方负责更新数据和刷新表格。
 */
object TableRowDragHandler {

    fun install(table: JTable, onMove: (fromModelRow: Int, toModelRow: Int) -> Unit) {
        table.dragEnabled = true
        table.dropMode = DropMode.INSERT_ROWS
        table.transferHandler = object : TransferHandler() {

            override fun getSourceActions(c: JComponent) = MOVE

            override fun createTransferable(c: JComponent): StringSelection? {
                val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return null
                return StringSelection(table.convertRowIndexToModel(viewRow).toString())
            }

            override fun canImport(support: TransferSupport) =
                support.isDrop && support.isDataFlavorSupported(DataFlavor.stringFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val dl = support.dropLocation as? JTable.DropLocation ?: return false
                val rowCount = table.rowCount
                if (rowCount == 0) return false
                // INSERT_ROWS 时 dl.row 范围是 [0, rowCount]，需要 clamp
                val toViewRow = dl.row.coerceIn(0, rowCount - 1)
                val toModelRow = table.convertRowIndexToModel(toViewRow)
                val fromModelRow = support.transferable
                    .getTransferData(DataFlavor.stringFlavor).toString().toIntOrNull() ?: return false
                if (fromModelRow == toModelRow) return false
                (table.rowSorter as? TableRowSorter<*>)?.sortKeys = emptyList()
                onMove(fromModelRow, toModelRow)
                return true
            }
        }
    }
}
