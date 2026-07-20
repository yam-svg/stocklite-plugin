package com.stocklite.plugin.ui

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.TextPresentation
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.QuoteRenderer
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import com.intellij.util.Consumer
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class PortfolioStatusWidget : StatusBarWidget, TextPresentation {

    data class HoldingRow(
        val name: String, val symbol: String,
        val qty: Double, val price: Double, val cost: Double,
        val changePct: Double, val pnl: Double, val todayPnl: Double
    )

    companion object {
        const val ID = "StockLite.Portfolio"

        @Volatile private var totalPnl   = 0.0
        @Volatile private var todayPnl   = 0.0
        @Volatile private var totalValue = 0.0
        @Volatile private var holdings: List<HoldingRow> = emptyList()

        private val instances = CopyOnWriteArrayList<PortfolioStatusWidget>()

        fun update(totalValue: Double, totalPnl: Double, todayPnl: Double, holdings: List<HoldingRow>) {
            this.totalValue = totalValue
            this.totalPnl   = totalPnl
            this.todayPnl   = todayPnl
            this.holdings   = holdings
            SwingUtilities.invokeLater {
                instances.forEach { it.statusBar?.updateWidget(ID) }
            }
        }
    }

    private var statusBar: StatusBar? = null

    override fun ID() = ID
    override fun getPresentation(): TextPresentation = this
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar; instances.add(this) }
    override fun dispose() { statusBar = null; instances.remove(this) }

    // ── TextPresentation ──

    override fun getText(): String {
        if (holdings.none { it.qty > 0 }) return ""
        return "持仓  ${Fmt.sign(totalPnl)}${Fmt.value(totalPnl)}  今日 ${Fmt.sign(todayPnl)}${Fmt.value(todayPnl)}"
    }

    override fun getTooltipText() = "StockLite 持仓概览，点击查看详情"
    override fun getAlignment()   = Component.LEFT_ALIGNMENT

    override fun getClickConsumer() = Consumer<MouseEvent> { e -> showPopover(e) }

    private fun showPopover(trigger: MouseEvent) {
        val h = holdings.filter { it.qty > 0 }
        if (h.isEmpty()) return
        val scheme = StockliteState.getInstance().colorScheme

        // ── 持仓汇总行 ──
        val summaryPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 6))
        fun colorLabel(text: String, v: Double): JLabel {
            val lbl = JLabel(text)
            lbl.foreground = when {
                v > 0 -> QuoteRenderer.positiveColor(scheme) ?: lbl.foreground
                v < 0 -> QuoteRenderer.negativeColor(scheme) ?: lbl.foreground
                else  -> QuoteRenderer.FLAT
            }
            return lbl
        }
        val totalCost   = h.sumOf { it.cost * it.qty }
        val totalPnlPct = if (totalCost > 0) totalPnl / totalCost * 100.0 else 0.0

        summaryPanel.add(JLabel("总市值 ${Fmt.value(totalValue)}"))
        summaryPanel.add(colorLabel("总盈亏 ${Fmt.sign(totalPnl)}${Fmt.value(totalPnl)}", totalPnl))
        summaryPanel.add(colorLabel("收益率 ${Fmt.pct(totalPnlPct)}", totalPnlPct))
        summaryPanel.add(colorLabel("今日盈亏 ${Fmt.sign(todayPnl)}${Fmt.value(todayPnl)}", todayPnl))

        // ── 持仓明细表 ──
        val cols = arrayOf("名称", "代码", "现价", "涨跌幅", "持仓盈亏", "收益率", "今日盈亏")
        val model = object : AbstractTableModel() {
            override fun getRowCount()    = h.size
            override fun getColumnCount() = cols.size
            override fun getColumnName(c: Int) = cols[c]
            override fun getColumnClass(c: Int) = if (c < 2) String::class.java else Double::class.java
            override fun getValueAt(row: Int, col: Int): Any = h[row].let { r ->
                when (col) {
                    0 -> r.name; 1 -> r.symbol
                    2 -> r.price; 3 -> r.changePct; 4 -> r.pnl
                    5 -> if (r.cost > 0) (r.price - r.cost) / r.cost * 100.0 else 0.0
                    6 -> r.todayPnl
                    else -> ""
                }
            }
        }
        val table = JTable(model)
        table.setDefaultRenderer(Double::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
            ): Component {
                val lbl = super.getTableCellRendererComponent(t, value, sel, focus, row, col) as JLabel
                val v   = value as? Double ?: 0.0
                lbl.horizontalAlignment = SwingConstants.CENTER
                if (!sel) lbl.foreground = when {
                    v > 0 -> QuoteRenderer.positiveColor(scheme) ?: t.foreground
                    v < 0 -> QuoteRenderer.negativeColor(scheme) ?: t.foreground
                    else  -> QuoteRenderer.FLAT
                }
                lbl.text = when (col) {
                    2 -> Fmt.price(v)
                    3, 5 -> Fmt.pct(v)
                    4, 6 -> if (v == 0.0) "--" else "${Fmt.sign(v)}${Fmt.value(v)}"
                    else -> Fmt.value(v)
                }
                return lbl
            }
        })
        table.setDefaultRenderer(String::class.java, DefaultTableCellRenderer())
        table.rowHeight = 24
        table.tableHeader.reorderingAllowed = false
        table.setShowGrid(false)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.columnModel.getColumn(0).preferredWidth = 100
        table.columnModel.getColumn(1).preferredWidth = 80
        for (i in 2..6) table.columnModel.getColumn(i).preferredWidth = 80

        val tableHeight = minOf(h.size * 24 + table.tableHeader.preferredSize.height + 4, 280)
        val scroll = JBScrollPane(table)
        scroll.preferredSize = Dimension(590, tableHeight)

        val panel = JPanel(BorderLayout())
        panel.add(summaryPanel, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("StockLite 持仓详情")
            .setResizable(true)
            .setMovable(true)
            .createPopup()

        popup.show(RelativePoint(trigger.component, java.awt.Point(trigger.x, 0)))
    }
}
