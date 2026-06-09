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
    private val price3  = DecimalFormat("#,##0.000")
    private val price4  = DecimalFormat("#,##0.0000")
    private val pct     = DecimalFormat("+0.00%;-0.00%")
    private val pctRaw  = DecimalFormat("+0.00;-0.00")
    private val number  = DecimalFormat("#,##0.####")

    /**
     * 自动检测小数位数：
     *   - 2 位：绝大多数 A 股（如 1800.00）
     *   - 3 位：ETF / LOF（如 4.513）
     *   - 4 位：净值类价格（如 1.2345）
     */
    fun price(v: Double): String {
        if (v == 0.0) return "--"
        val r2 = Math.round(v * 100) / 100.0
        val r3 = Math.round(v * 1000) / 1000.0
        return when {
            Math.abs(v - r2) < 5e-4 -> price2.format(v)
            Math.abs(v - r3) < 5e-5 -> price3.format(v)
            else                     -> price4.format(v)
        }
    }

    fun nav(v: Double): String    = if (v == 0.0) "--" else price4.format(v)
    fun pct(v: Double): String    = if (v == 0.0) "--" else "${pctRaw.format(v)}%"
    fun qty(v: Double): String    = number.format(v)
    fun value(v: Double): String  = if (v == 0.0) "--" else price2.format(v)
    fun sign(v: Double): String   = if (v >= 0) "+" else ""
}
