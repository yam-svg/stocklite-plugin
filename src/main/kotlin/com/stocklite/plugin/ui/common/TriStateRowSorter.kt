package com.stocklite.plugin.ui.common

import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

/**
 * 三态排序的 TableRowSorter：点击表头循环 升序 → 降序 → 无排序（还原默认顺序）。
 *
 * Swing 默认只有两态（升序 ↔ 降序），第三下点击清空 sortKeys 即可恢复数据原始顺序。
 */
class TriStateRowSorter(model: TableModel) : TableRowSorter<TableModel>(model) {

    override fun toggleSortOrder(column: Int) {
        val keys = sortKeys
        val primary = keys.firstOrNull()
        // 当前列已是主排序键：升序 → 降序 → 清空
        if (primary != null && primary.column == column) {
            when (primary.sortOrder) {
                SortOrder.ASCENDING -> {
                    sortKeys = listOf(RowSorter.SortKey(column, SortOrder.DESCENDING))
                }
                SortOrder.DESCENDING -> {
                    sortKeys = emptyList()
                }
                else -> {
                    sortKeys = listOf(RowSorter.SortKey(column, SortOrder.ASCENDING))
                }
            }
        } else {
            // 切到新列：默认升序
            sortKeys = listOf(RowSorter.SortKey(column, SortOrder.ASCENDING))
        }
    }
}
