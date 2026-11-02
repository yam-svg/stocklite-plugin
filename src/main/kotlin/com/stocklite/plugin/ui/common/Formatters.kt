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

/**
 * 按【模型】列序号取 TableColumn。
 * columnModel.getColumn(i) 的参数是视图序号：用户拖动列头后视图顺序 ≠ 模型顺序，
 * 若再按模型顺序套用渲染器/列宽就会挂错列（现象：拖拽涨跌幅列后刷新，单元格显示 ChangePctValue 源码）。
 */
fun JTable.viewColumn(modelIndex: Int): javax.swing.table.TableColumn? {
    val view = convertColumnIndexToView(modelIndex)
    return if (view >= 0) columnModel.getColumn(view) else null
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
