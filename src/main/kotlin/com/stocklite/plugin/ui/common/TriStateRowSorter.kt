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

    companion object {
        /** 把 "--"、千分位逗号、"+"、"%" 等展示格式解析回数值；不可解析返回 null */
        fun parseNum(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().removePrefix("+").removeSuffix("%").replace(",", "").toDoubleOrNull()
            else -> null
        }

        /**
         * 数值列通用比较器：String 存储的数值列（如 "55.28"、"18,500"）若走默认字典序，
         * 会出现 "150" < "55" < "6" 这类不严格升降序，统一改用数值比较；
         * "--" 等无法解析的缺失值按最小值处理（升序置顶、降序沉底）。
         */
        val numericComparator = Comparator<Any?> { a, b ->
            val da = parseNum(a)
            val db = parseNum(b)
            when {
                da != null && db != null -> da.compareTo(db)
                da != null -> 1
                db != null -> -1
                else -> (a?.toString() ?: "").compareTo(b?.toString() ?: "")
            }
        }
    }

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
