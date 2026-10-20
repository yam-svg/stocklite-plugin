package com.stocklite.plugin.ui

import com.intellij.ui.table.JBTable
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.Fmt
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 日历底部明细表的一行：某股票当日盈亏与收益率 */
data class DayStockRow(val name: String, val pnl: Double, val pct: Double)

/**
 * 盈亏日历：按月展示每日盈亏热图（GitHub 贡献图风格）。
 * 输入为「日期 -> 当日盈亏」映射，颜色深浅按当日盈亏绝对值缩放，
 * 涨跌颜色跟随全局涨跌色方案。
 */
class PnlCalendarPanel : JPanel(BorderLayout()) {

    private val shZone = ZoneId.of("Asia/Shanghai")

    private var daily: Map<LocalDate, Double> = emptyMap()
    private var detailsByDate: Map<LocalDate, List<DayStockRow>> = emptyMap()
    private var selectedDate: LocalDate? = null
    private var month: YearMonth = YearMonth.now(shZone)

    private val monthLbl   = JLabel("", SwingConstants.CENTER)
    private val prevBtn    = flatBtn("◀")
    private val nextBtn    = flatBtn("▶")
    private val todayBtn   = flatBtn(L10n.calendarToday)
    private val summaryLbl = JLabel(" ")
    private val gridPanel  = JPanel(GridLayout(0, 7, 3, 3))

    // ── 底部：点击日期后展示各股当日盈亏明细 ──
    private val detailTitle = JLabel(L10n.calendarDetailHint)
    private var detailRows: List<DayStockRow> = emptyList()
    private val detailModel = object : AbstractTableModel() {
        override fun getRowCount()    = detailRows.size
        override fun getColumnCount() = 3
        override fun getColumnName(c: Int) = when (c) {
            0 -> L10n.colName; 1 -> L10n.calendarDayPnl; else -> L10n.colChangePct
        }
        override fun getValueAt(r: Int, c: Int): Any = when (c) {
            0 -> detailRows[r].name; 1 -> detailRows[r].pnl; else -> detailRows[r].pct
        }
    }
    private val detailTable = JBTable(detailModel)
    private val detailScroll = JBScrollPane(detailTable)
    private val detailPanel = JPanel(BorderLayout(0, 2))

    init {
        border = BorderFactory.createEmptyBorder(6, 10, 6, 10)

        monthLbl.font = monthLbl.font.deriveFont(Font.BOLD, 13f)
        prevBtn.addActionListener { month = month.minusMonths(1); clearSelection(); render() }
        nextBtn.addActionListener { month = month.plusMonths(1);  clearSelection(); render() }
        todayBtn.addActionListener { month = YearMonth.now(shZone); clearSelection(); render() }

        val navPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(prevBtn); add(monthLbl); add(nextBtn); add(todayBtn)
        }
        summaryLbl.font = summaryLbl.font.deriveFont(12f)
        summaryLbl.foreground = QuoteRenderer.FLAT

        val top = JPanel(BorderLayout()).apply {
            add(navPanel, BorderLayout.WEST)
            add(summaryLbl, BorderLayout.EAST)
        }
        // ── 底部明细表装配 ──
        detailTitle.font = detailTitle.font.deriveFont(11f)
        detailTable.rowHeight = 22
        detailTable.setShowGrid(false)
        detailTable.tableHeader.reorderingAllowed = false
        detailTable.columnModel.getColumn(0).preferredWidth = 320
        detailTable.columnModel.getColumn(1).preferredWidth = 160
        detailTable.columnModel.getColumn(2).preferredWidth = 120
        detailTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        val centerRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
            override fun getTableCellRendererComponent(
                t: JTable?, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(t, v, sel, foc, row, col)
                if (!sel && col > 0) {
                    val d = v as? Double ?: 0.0
                    foreground = when {
                        d > 0 -> QuoteRenderer.positiveColor(StockliteState.getInstance().colorScheme) ?: foreground
                        d < 0 -> QuoteRenderer.negativeColor(StockliteState.getInstance().colorScheme) ?: foreground
                        else  -> QuoteRenderer.FLAT
                    }
                }
                return comp
            }
        }
        detailTable.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
            override fun getTableCellRendererComponent(
                t: JTable?, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int
            ): Component {
                val comp = centerRenderer.getTableCellRendererComponent(t, v, sel, foc, row, col)
                val d = v as? Double ?: 0.0
                (comp as JLabel).text = if (d == 0.0) "--" else "${Fmt.sign(d)}${Fmt.value(abs(d))}"
                return comp
            }
        }
        detailTable.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.CENTER }
            override fun getTableCellRendererComponent(
                t: JTable?, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int
            ): Component {
                val comp = centerRenderer.getTableCellRendererComponent(t, v, sel, foc, row, col)
                (comp as JLabel).text = "%.2f%%".format(v as? Double ?: 0.0)
                return comp
            }
        }
        detailScroll.border = BorderFactory.createEmptyBorder()
        detailScroll.preferredSize = Dimension(0, 130)
        detailPanel.add(detailTitle,  BorderLayout.NORTH)
        detailPanel.add(detailScroll, BorderLayout.CENTER)

        gridPanel.isOpaque = false
        add(top, BorderLayout.NORTH)
        add(gridPanel, BorderLayout.CENTER)
        add(detailPanel, BorderLayout.SOUTH)
        render()
    }

    fun setData(map: Map<LocalDate, Double>, details: Map<LocalDate, List<DayStockRow>> = emptyMap()) {
        daily = map
        detailsByDate = details
        // 明细区高度按数据量一次定死（最多 140px），点击切换日期不再改变整体尺寸
        val maxRows = details.values.maxOfOrNull { it.size } ?: 0
        detailScroll.preferredSize = Dimension(0,
            if (maxRows == 0) 60 else minOf(maxRows * 22 + detailTable.tableHeader.preferredSize.height + 2, 140))
        // 默认定位到今天；若今天的月份完全无数据，跳到最近一个有数据的月份
        if (monthHasNoData(month) && map.isNotEmpty()) {
            month = map.keys.maxOrNull()?.let { YearMonth.from(it) } ?: month
        }
        // 进入即选中当天；当天无明细则选中当前月最近一个有明细的日期，都没有则选中全局最近
        val today = LocalDate.now(shZone)
        selectedDate = when {
            detailsByDate.containsKey(today) -> today
            else -> detailsByDate.keys.filter { YearMonth.from(it) == month }.maxOrNull()
                ?: detailsByDate.keys.maxOrNull()
        }
        populateDetail()
        render()
    }

    private fun clearSelection() {
        selectedDate = null
        populateDetail()
    }

    /** 点击有数据的格子：高亮该日并在底部展示各股当日盈亏明细 */
    private fun selectDay(date: LocalDate) {
        selectedDate = if (selectedDate == date) null else date
        populateDetail()
        render()
    }

    /** 依据当前 selectedDate 刷新底部明细区（不改变布局尺寸） */
    private fun populateDetail() {
        val date = selectedDate
        val rows = date?.let { detailsByDate[it] } ?: emptyList()
        detailRows = rows.sortedByDescending { abs(it.pnl) }
        detailModel.fireTableDataChanged()
        detailTitle.foreground = QuoteRenderer.FLAT
        if (date == null) {
            detailTitle.text = L10n.calendarDetailHint
        } else if (rows.isEmpty()) {
            detailTitle.text = L10n.calendarNoDetail
        } else {
            val sum = rows.sumOf { it.pnl }
            val scheme = StockliteState.getInstance().colorScheme
            val sumHex = hexOf(resolve(when {
                sum > 0 -> QuoteRenderer.positiveColor(scheme) ?: QuoteRenderer.FLAT
                sum < 0 -> QuoteRenderer.negativeColor(scheme) ?: QuoteRenderer.FLAT
                else -> QuoteRenderer.FLAT
            }))
            detailTitle.text = "<html>${date} &nbsp;${L10n.calendarDayPnl}" +
                (if (sum == 0.0) " --" else " <b style='color:$sumHex'>${Fmt.sign(sum)}${Fmt.value(abs(sum))}</b>") + "</html>"
        }
    }

    private fun monthHasNoData(m: YearMonth) = daily.keys.none { YearMonth.from(it) == m }

    private fun render() {
        val scheme = StockliteState.getInstance().colorScheme
        val upColor = QuoteRenderer.positiveColor(scheme)
        val dnColor = QuoteRenderer.negativeColor(scheme)
        monthLbl.text = monthLabel(month)

        val today   = LocalDate.now(shZone)
        val base    = resolve(parentOrOwnBackground())
        val byMonth = daily.filterKeys { YearMonth.from(it) == month }
        val maxAbs  = byMonth.values.maxOfOrNull { abs(it) } ?: 0.0

        gridPanel.removeAll()

        // ── 星期表头 ──
        for (wd in L10n.calendarWeekdays.split(",")) {
            gridPanel.add(JLabel(wd, SwingConstants.CENTER).apply {
                font = font.deriveFont(11f); foreground = QuoteRenderer.FLAT
                preferredSize = Dimension(0, 20)
            })
        }

        // ── 日历格：周一为一周起点，固定 6 行 ──
        val firstDay  = month.atDay(1)
        val offset    = firstDay.dayOfWeek.value - 1   // Mon=0 .. Sun=6
        val daysInMon = month.lengthOfMonth()
        val minMonth  = minVisibleMonth()
        for (i in 0 until 42) {
            val dayNum = i - offset + 1
            val cell: JComponent = if (dayNum in 1..daysInMon) {
                val date = month.atDay(dayNum)
                val pnl  = daily[date]
                if (date.isAfter(today)) emptyCell(base)
                else dayCell(date, dayNum, pnl, maxAbs, upColor, dnColor, base, today)
            } else emptyCell(base)
            gridPanel.add(cell)
        }

        // ── 当月统计 ──
        val monthSum = byMonth.values.sum()
        val winDays  = byMonth.values.count { it > 0 }
        val loseDays = byMonth.values.count { it < 0 }
        val sumHex = hexOf(when {
            monthSum > 0 -> upColor ?: QuoteRenderer.FLAT
            monthSum < 0 -> dnColor ?: QuoteRenderer.FLAT
            else -> QuoteRenderer.FLAT
        })
        summaryLbl.foreground = QuoteRenderer.FLAT
        summaryLbl.text = "<html>${L10n.calendarMonthPnl} <b style='color:$sumHex'>${Fmt.sign(monthSum)}${compact(monthSum)}</b>" +
            "&nbsp;&nbsp;${L10n.calendarWinDays} $winDays&nbsp;&nbsp;${L10n.calendarLoseDays} $loseDays</html>"

        prevBtn.isEnabled  = month > minMonth
        nextBtn.isEnabled  = month < YearMonth.now(shZone)

        gridPanel.revalidate(); gridPanel.repaint()
    }

    private fun minVisibleMonth(): YearMonth {
        val dataMin = daily.keys.minOrNull()?.let { YearMonth.from(it) }
        val now = YearMonth.now(shZone)
        return listOfNotNull(dataMin, now.minusMonths(24)).minByOrNull { it } ?: now
    }

    private fun dayCell(
        date: LocalDate, dayNum: Int, pnl: Double?, maxAbs: Double,
        upColor: Color?, dnColor: Color?, base: Color, today: LocalDate
    ): JComponent {
        val bg = when {
            pnl == null  -> blend(Color.BLACK, base, 0.15)
            pnl == 0.0   -> blend(QuoteRenderer.FLAT, base, 0.12)
            else -> {
                val side = if (pnl > 0) upColor ?: QuoteRenderer.FLAT else dnColor ?: QuoteRenderer.FLAT
                val t = if (maxAbs > 0) sqrt(abs(pnl) / maxAbs) else 0.0
                blend(resolve(side), base, 0.10 + 0.55 * t)
            }
        }
        val dayLbl = JLabel(dayNum.toString()).apply {
            font = font.deriveFont(10f)
            foreground = if (date == today) Color(0xf5c518) else QuoteRenderer.FLAT
            border = BorderFactory.createEmptyBorder(2, 4, 0, 0)
        }
        val text = when {
            pnl == null -> " "
            pnl == 0.0  -> "0"
            else        -> "${Fmt.sign(pnl)}${compact(pnl)}"
        }
        val valLbl = JLabel(text, SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, if (text.length > 7) 10f else 11f)
            foreground = when {
                pnl == null -> base
                pnl > 0     -> upColor ?: QuoteRenderer.FLAT
                pnl < 0     -> dnColor ?: QuoteRenderer.FLAT
                else        -> QuoteRenderer.FLAT
            }
        }

        val selected = date == selectedDate
        val cell = JPanel(BorderLayout()).apply {
            background = bg
            border = when {
                selected -> BorderFactory.createLineBorder(Color(0x4e9af1), 2)
                date == today -> BorderFactory.createLineBorder(Color(0xf5c518), 1)
                else -> BorderFactory.createLineBorder(blend(resolve(QuoteRenderer.FLAT), base, 0.15), 1)
            }
            add(dayLbl, BorderLayout.NORTH)
            add(valLbl, BorderLayout.CENTER)
            toolTipText = if (pnl == null) date.toString()
            else "$date  ${L10n.calendarDayPnl}: ${Fmt.sign(pnl)}${"%.2f".format(abs(pnl))}"
            preferredSize = Dimension(0, 46)
        }
        if (pnl != null) {
            cell.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            cell.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) selectDay(date)
                }
            })
        }
        return cell
    }

    private fun emptyCell(base: Color): JComponent = JPanel().apply {
        background = base.darker()
        preferredSize = Dimension(0, 46)
    }

    private fun monthLabel(m: YearMonth) =
        if (L10n.isEn()) "%d-%02d".format(m.year, m.monthValue)
        else "${m.year}年${m.monthValue}月"

    /** 金额紧凑显示：亿/万/整数 */
    private fun compact(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e8 -> "%.2f亿".format(v / 1e8)
            a >= 1e4 -> "%.1f万".format(v / 1e4)
            else     -> "%.0f".format(v)
        }
    }

    private fun flatBtn(text: String) = JButton(text).apply {
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
        font = font.deriveFont(11f)
        foreground = Color(0x888aaa)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(if (text.length > 1) 48 else 26, 24)
    }

    private fun parentOrOwnBackground(): Color =
        (if (isShowing && parent != null) parent.background else background) ?: Color(0x2b2b2b)

    /** JBColor/动态色按当前主题解析成固定 RGB */
    private fun resolve(c: Color): Color = Color(c.red, c.green, c.blue)

    private fun hexOf(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /** 将 color 以 alpha 比例混到 base 上，得到不透明混色（避免半透明背景绘制问题） */
    private fun blend(color: Color, base: Color, alpha: Double): Color {
        val a = alpha.coerceIn(0.0, 1.0)
        return Color(
            (base.red   * (1 - a) + color.red   * a).roundToInt(),
            (base.green * (1 - a) + color.green * a).roundToInt(),
            (base.blue  * (1 - a) + color.blue  * a).roundToInt()
        )
    }
}
