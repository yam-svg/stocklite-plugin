package com.stocklite.plugin.ui.common

import java.text.DecimalFormat
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

/** 将 JTable 表头文字设为居中对齐 */
fun centerTableHeader(table: JTable) {
    table.tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }
        override fun getTableCellRendererComponent(
            t: JTable?, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ) = (super.getTableCellRendererComponent(t, value, sel, focus, row, col) as JLabel)
            .also { it.horizontalAlignment = SwingConstants.CENTER }
    }
}

object Fmt {
    private val price2  = DecimalFormat("#,##0.00")
    private val price4  = DecimalFormat("#,##0.0000")
    private val pct     = DecimalFormat("+0.00%;-0.00%")
    private val pctRaw  = DecimalFormat("+0.00;-0.00")
    private val number  = DecimalFormat("#,##0.####")

    fun price(v: Double): String  = if (v == 0.0) "--" else price2.format(v)
    fun nav(v: Double): String    = if (v == 0.0) "--" else price4.format(v)
    fun pct(v: Double): String    = if (v == 0.0) "--" else "${pctRaw.format(v)}%"
    fun qty(v: Double): String    = number.format(v)
    fun value(v: Double): String  = if (v == 0.0) "--" else price2.format(v)
    fun sign(v: Double): String   = if (v >= 0) "+" else ""
}
