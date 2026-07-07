package com.stocklite.plugin.ui.common

import com.intellij.ui.JBColor
import com.stocklite.plugin.state.StockliteState
import java.awt.Color
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

enum class QuoteColumnType { PLAIN, PRICE, PRICE4, QTY, PCT, VALUE, PNL, DIRECTION }

open class QuoteRenderer(private val colType: QuoteColumnType = QuoteColumnType.PLAIN) :
    DefaultTableCellRenderer() {

    companion object {
        val RED   = JBColor(Color(0xE53935), Color(0xFF6B6B))
        val GREEN = JBColor(Color(0x2E9D4F), Color(0x66BB6A))
        val FLAT  = JBColor(Color(0x888888), Color(0xAAAAAA))

        /** 哨兵：今日官方净值已更新，估算已失效 */
        const val SENTINEL_OFFICIAL_UPDATED = 1e10
        /** 哨兵：暂无估算（非交易时段/假日） */
        const val SENTINEL_NO_ESTIMATE      = -1e10

        /** 根据当前颜色设置返回"正值"颜色 */
        fun positiveColor(scheme: String) = when (scheme) {
            "RED_UP"   -> RED
            "RED_DOWN" -> GREEN
            else       -> null   // NONE：不着色
        }

        /** 根据当前颜色设置返回"负值"颜色 */
        fun negativeColor(scheme: String) = when (scheme) {
            "RED_UP"   -> GREEN
            "RED_DOWN" -> RED
            else       -> null   // NONE：不着色
        }
    }

    init {
        horizontalAlignment = SwingConstants.CENTER
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?,
        isSelected: Boolean, hasFocus: Boolean,
        row: Int, column: Int
    ): Component {
        val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel

        val numVal = when (value) {
            is Double -> value
            is String -> value.replace(",", "").replace("%", "").replace("+", "").toDoubleOrNull()
            else -> null
        }

        if (!isSelected) {
            val scheme = StockliteState.getInstance().colorScheme
            foreground = when (colType) {
                QuoteColumnType.PCT, QuoteColumnType.PNL -> when {
                    numVal == null || numVal == 0.0                          -> FLAT
                    numVal >= SENTINEL_OFFICIAL_UPDATED                      -> FLAT  // 哨兵：不着色
                    numVal <= SENTINEL_NO_ESTIMATE                           -> FLAT  // 哨兵：不着色
                    numVal > 0 -> positiveColor(scheme) ?: table.foreground
                    else       -> negativeColor(scheme) ?: table.foreground
                }
                QuoteColumnType.DIRECTION -> when (value?.toString()) {
                    "多" -> positiveColor(scheme) ?: table.foreground
                    "空" -> negativeColor(scheme) ?: table.foreground
                    else -> table.foreground
                }
                else -> table.foreground
            }
        }

        text = when (colType) {
            QuoteColumnType.PLAIN     -> value?.toString() ?: ""
            QuoteColumnType.PRICE     -> if (numVal != null) Fmt.price(numVal) else value?.toString() ?: ""
            QuoteColumnType.PRICE4    -> if (numVal != null) Fmt.nav(numVal) else value?.toString() ?: ""
            QuoteColumnType.QTY       -> if (numVal != null) Fmt.qty(numVal) else value?.toString() ?: ""
            QuoteColumnType.PCT       -> when {
                numVal == null                            -> value?.toString() ?: ""
                numVal >= SENTINEL_OFFICIAL_UPDATED       -> "官方✓"
                numVal <= SENTINEL_NO_ESTIMATE            -> "-"
                else                                      -> Fmt.pct(numVal)
            }
            QuoteColumnType.VALUE     -> if (numVal != null) Fmt.value(numVal) else value?.toString() ?: ""
            QuoteColumnType.PNL       -> if (numVal != null) Fmt.value(numVal) else value?.toString() ?: ""
            QuoteColumnType.DIRECTION -> value?.toString() ?: ""
        }

        return comp
    }
}
