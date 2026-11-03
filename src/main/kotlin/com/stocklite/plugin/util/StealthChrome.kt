package com.stocklite.plugin.util

import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable

/**
 * 隐秘模式的静态界面文字转换器：遍历组件树，把按钮、标签、表格表头文案经
 * StealthName.uiText 转拼音（标签页标题不在子组件里，由 StocklitePanel 单独刷）。
 *
 * 原文存在组件 clientProperty：关档/换档时无损还原；若业务方在两次刷新之间
 * 自己更新了文案（当前文本 ≠ 上次写入的），则把当前文本当作新原文，不会叠加转换。
 * 表头只改 headerValue 不动 TableModel.getColumnName——点击/悬浮逻辑靠
 * getColumnName 比对 L10n 原串识别列，不能污染。
 */
object StealthChrome {

    private const val RAW   = "stocklite.stealth.raw"    // 原始文案
    private const val SHOWN = "stocklite.stealth.shown"  // 上次由本类写入的文案

    fun refresh(root: Component) = walk(root)

    private fun walk(c: Component) {
        when (c) {
            is JTable -> refreshHeaders(c)
            is AbstractButton -> c.text?.let { syncText(c, it)?.let { t -> if (t != c.text) c.text = t } }
            is JLabel -> c.text?.let { syncText(c, it)?.let { t -> if (t != c.text) c.text = t } }
        }
        if (c is Container) c.components.forEach { walk(it) }
    }

    /** 维护 raw/shown 标记并返回当前应展示的文本 */
    private fun syncText(comp: JComponent, current: String): String? {
        val shown = comp.getClientProperty(SHOWN) as? String
        // 当前文本不是我们写的 → 视为业务新原文（如状态标签刷新）
        val raw = if (shown == null || current != shown) current
                  else comp.getClientProperty(RAW) as? String ?: current
        comp.putClientProperty(RAW, raw)
        return StealthName.uiText(raw).also { comp.putClientProperty(SHOWN, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshHeaders(t: JTable) {
        val raws  = t.getClientProperty("$RAW.hdr")   as? MutableMap<Int, String> ?: HashMap()
        val shown = t.getClientProperty("$SHOWN.hdr") as? MutableMap<Int, String> ?: HashMap()
        for (i in 0 until t.columnCount) {
            val col = t.columnModel.getColumn(i)
            val cur = col.headerValue?.toString() ?: continue
            // 列显隐重建后 headerValue 回到原串，同样按新原文收养
            val raw = if (shown[i] == null || cur != shown[i]) cur else raws[i] ?: cur
            raws[i] = raw
            val out = StealthName.uiText(raw)
            shown[i] = out
            if (out != cur) col.headerValue = out
        }
        t.putClientProperty("$RAW.hdr", raws)
        t.putClientProperty("$SHOWN.hdr", shown)
        t.tableHeader?.repaint()
    }
}
