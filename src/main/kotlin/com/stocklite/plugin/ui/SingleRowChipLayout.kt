package com.stocklite.plugin.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent

/**
 * 单行 chip 布局（替代 FlowLayout 用于大盘概览各 chip 行）：
 * FlowLayout 宽度不足时会把放不下的组件整组折到第二行，而行高只有一行，折行内容被裁切不可见——
 * 表现为“面板一窄就一批 chip 同时消失”。本布局不换行，改为按从左到右的顺序贪心摆放，
 * 放不下的组件挨个 setVisible(false) 整体隐藏；宽度恢复后再挨个还原，体验更平滑。
 *
 * 首选尺寸始终按“全部可见”计算（与可见性无关），行高恒定，避免隐藏/显示切换引发布局抖动。
 */
internal class SingleRowChipLayout(private val hgap: Int, private val vgap: Int) : LayoutManager {

    override fun addLayoutComponent(name: String, comp: Component) {}
    override fun removeLayoutComponent(comp: Component) {}

    override fun preferredLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        var w = 0
        var h = 0
        var n = 0
        for (c in target.components) {
            if (c !is JComponent) continue
            val ps = c.preferredSize
            if (ps.width <= 0) continue   // 空文本组件（如无同期数据时的变化 chip）不计入尺寸
            w += ps.width + if (n > 0) hgap else 0
            if (ps.height > h) h = ps.height
            n++
        }
        val ins = target.insets
        Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom + 2 * vgap)
    }

    /** 最小尺寸只保底行高，允许面板收缩到小于全部 chip 的宽度，从而触发挨个隐藏 */
    override fun minimumLayoutSize(target: Container): Dimension = synchronized(target.treeLock) {
        val pref = preferredLayoutSize(target)
        Dimension(60, pref.height)
    }

    override fun layoutContainer(target: Container) {
        synchronized(target.treeLock) {
            val ins = target.insets
            val right = target.width - ins.right
            val availH = (target.height - ins.top - ins.bottom).coerceAtLeast(0)
            var x = ins.left
            var stopped = false   // 一旦有 chip 放不下，其后全部隐藏（保持从左到右的优先顺序，避免窄 chip 上浮错位）
            for (c in target.components) {
                if (c !is JComponent) continue
                val ps = c.preferredSize
                if (ps.width <= 0) {      // 空文本组件：隐藏即可，不打断后续 chip 的显示链
                    if (c.isVisible) c.isVisible = false
                    continue
                }
                val nextX = if (x == ins.left) x else x + hgap
                val fits = !stopped && nextX + ps.width <= right
                if (!fits) stopped = true
                if (c.isVisible != fits) c.isVisible = fits
                if (fits) {
                    val ph = ps.height.coerceAtMost(availH)
                    val y = ins.top + ((availH - ph) / 2).coerceAtLeast(0)
                    c.setBounds(nextX, y, ps.width, ph)
                    x = nextX + ps.width
                }
            }
        }
    }
}
