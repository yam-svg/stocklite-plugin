package com.stocklite.plugin.ui.dialogs

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.stocklite.plugin.service.FundHoldingsService
import com.stocklite.plugin.state.FundHoldingItem
import com.stocklite.plugin.state.FundHoldingsResult
import com.stocklite.plugin.util.HttpUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * 基金持仓详情弹窗。
 *
 * 展示东方财富最新公布的持仓明细（股票型/混合型/指数型/QDII/ETF）。
 * 打开后自动异步拉取数据，支持刷新。
 *
 * @param fundName  基金名称（用于标题显示）
 * @param fundCode  基金代码（6 位）
 */
class FundHoldingsDialog(
    private val fundName: String,
    private val fundCode: String
) : DialogWrapper(true) {

    // ── 数据 ────────────────────────────────────────────────────────────
    private var items: List<FundHoldingItem> = emptyList()

    // ── 状态面板 ─────────────────────────────────────────────────────────
    private val statusLabel = JLabel("").apply {
        horizontalAlignment = SwingConstants.CENTER
        font = font.deriveFont(Font.PLAIN, 13f)
    }
    private val contentCards = JPanel(CardLayout())   // "loading" | "error" | "table"

    // ── 信息条（报告期、持仓数量等）────────────────────────────────────
    private val infoLabel = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
    }
    private val dataSourceLabel = JLabel("数据来源：东方财富，持仓数据通常延迟 1~3 个月").apply {
        font = font.deriveFont(Font.ITALIC, 10f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
    }
    private val refreshBtn = JButton("↻ 刷新").apply {
        toolTipText = "重新获取最新持仓数据"
    }
    private val openWebBtn = JButton("在浏览器中查看").apply {
        toolTipText = "在东方财富基金 F10 页面查看完整持仓"
    }

    // ── 表格 ────────────────────────────────────────────────────────────
    private val COL_NAMES  = arrayOf("序号", "股票代码", "股票名称", "占净值比例", "持仓股数(万股)", "持仓市值(万元)", "较上期变化", "涨跌幅")
    private val COL_RANK   = 0
    private val COL_CODE   = 1
    private val COL_NAME   = 2
    private val COL_PCT    = 3
    private val COL_SHARES = 4
    private val COL_VALUE  = 5
    private val COL_CHANGE = 6
    private val COL_CHG    = 7   // 实时/收盘涨跌幅，异步填充

    /** 股票代码 → 涨跌幅（%），Double.NaN 表示数据未到或不支持（如海外股） */
    private val chgMap = mutableMapOf<String, Double>()

    private val tableModel = object : AbstractTableModel() {
        override fun getRowCount()             = items.size
        override fun getColumnCount()          = COL_NAMES.size
        override fun getColumnName(col: Int)   = COL_NAMES[col]
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getColumnClass(col: Int): Class<*> = when (col) {
            COL_RANK, COL_PCT, COL_CHG -> Double::class.javaObjectType
            else -> String::class.java
        }
        override fun getValueAt(row: Int, col: Int): Any {
            val h = items[row]
            return when (col) {
                COL_RANK   -> h.rank.toDouble()
                COL_CODE   -> h.stockCode
                COL_NAME   -> h.stockName
                COL_PCT    -> h.navPercent
                COL_SHARES -> h.holdShares
                COL_VALUE  -> h.holdValue
                COL_CHANGE -> h.change
                COL_CHG    -> chgMap[h.stockCode] ?: Double.NaN
                else -> ""
            }
        }
    }

    private val table = JBTable(tableModel).apply {
        rowHeight = 26
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
    }

    // ── init ─────────────────────────────────────────────────────────────
    init {
        title   = "基金持仓详情 — $fundName ($fundCode)"
        isModal = true
        init()
        setupTable()
        loadData()

        refreshBtn.addActionListener  { loadData(forceRefresh = true) }
        openWebBtn.addActionListener  { BrowserUtil.browse("https://fundf10.eastmoney.com/jjcc_$fundCode.html") }
    }

    // ── DialogWrapper ────────────────────────────────────────────────────

    override fun createCenterPanel(): JComponent {
        // ── 加载中面板 ──
        val loadingPanel = JPanel(GridBagLayout()).apply {
            add(JLabel("⏳ 正在获取持仓数据，请稍候…").apply {
                font = font.deriveFont(Font.PLAIN, 13f)
            })
        }

        // ── 错误面板 ──
        val errorPanel = JPanel(GridBagLayout()).apply {
            add(statusLabel)
        }

        // ── 表格面板 ──
        val tableScroll = JBScrollPane(table).apply { border = null }
        val tablePanel  = JPanel(BorderLayout()).apply { add(tableScroll, BorderLayout.CENTER) }

        contentCards.add(loadingPanel, "loading")
        contentCards.add(errorPanel,   "error")
        contentCards.add(tablePanel,   "table")
        showCard("loading")

        // ── 汇总条 ──
        val infoRow = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                    UIManager.getColor("Separator.foreground") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            add(infoLabel,       BorderLayout.WEST)
            add(dataSourceLabel, BorderLayout.EAST)
        }

        // ── 整体布局 ──
        val wrapper = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = Dimension(860, 480)
            add(infoRow,       BorderLayout.NORTH)
            add(contentCards,  BorderLayout.CENTER)
        }
        return wrapper
    }

    /** 只保留关闭按钮 */
    override fun createActions(): Array<Action> =
        arrayOf(cancelAction.also { (it as? DialogWrapper.CancelAction)?.putValue(Action.NAME, "关闭") })

    /** 南部：刷新 + 浏览器 */
    override fun createSouthAdditionalPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(refreshBtn); add(Box.createHorizontalStrut(8)); add(openWebBtn)
        }

    // ── 表格设置 ─────────────────────────────────────────────────────────

    private fun setupTable() {
        // 列宽
        table.columnModel.getColumn(COL_RANK).apply   { preferredWidth = 40;  maxWidth = 50 }
        table.columnModel.getColumn(COL_CODE).apply   { preferredWidth = 80;  maxWidth = 100 }
        table.columnModel.getColumn(COL_NAME).apply   { preferredWidth = 150 }
        table.columnModel.getColumn(COL_PCT).apply    { preferredWidth = 90;  maxWidth = 120 }
        table.columnModel.getColumn(COL_SHARES).apply { preferredWidth = 110; maxWidth = 150 }
        table.columnModel.getColumn(COL_VALUE).apply  { preferredWidth = 130; maxWidth = 170 }
        table.columnModel.getColumn(COL_CHANGE).apply { preferredWidth = 75;  maxWidth = 95  }
        table.columnModel.getColumn(COL_CHG).apply    { preferredWidth = 75;  maxWidth = 95  }

        // 右对齐渲染（序号 / 占净值比例 / 数量 / 市值）
        val rightRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.RIGHT
        }
        val pctRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                val d = (value as? Double) ?: 0.0
                text = "${"%.2f".format(d)}%"
                horizontalAlignment = SwingConstants.RIGHT
            }
        }
        table.columnModel.getColumn(COL_RANK).cellRenderer   = rightRenderer
        table.columnModel.getColumn(COL_PCT).cellRenderer    = pctRenderer
        table.columnModel.getColumn(COL_SHARES).cellRenderer = rightRenderer
        table.columnModel.getColumn(COL_VALUE).cellRenderer  = rightRenderer

        // 较上期变化：颜色渲染
        table.columnModel.getColumn(COL_CHANGE).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, col: Int
            ): Component {
                val c = super.getTableCellRendererComponent(t, value, selected, focused, row, col)
                horizontalAlignment = SwingConstants.CENTER
                val text = value?.toString() ?: ""
                foreground = when {
                    selected -> t.selectionForeground
                    text.contains("增") || text.contains("新进") ->
                        Color(0x00, 0xAA, 0x44)
                    text.contains("减") || text.contains("退出") ->
                        Color(0xCC, 0x22, 0x22)
                    else -> UIManager.getColor("Table.foreground") ?: Color.BLACK
                }
                return c
            }
        }

        // 涨跌幅：红涨绿跌（A 股惯例），NaN 显示 "--"，含加载中样式
        table.columnModel.getColumn(COL_CHG).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, col: Int
            ): Component {
                val d = value as? Double
                val display = when {
                    d == null || d.isNaN() -> "--"
                    else -> "${"%.2f".format(d)}%"
                }
                val c = super.getTableCellRendererComponent(t, display, selected, focused, row, col)
                horizontalAlignment = SwingConstants.RIGHT
                foreground = when {
                    selected || d == null || d.isNaN() ->
                        if (selected) t.selectionForeground
                        else UIManager.getColor("Table.foreground") ?: Color.BLACK
                    d > 0.0  -> Color(0xCC, 0x22, 0x22)   // 上涨：红
                    d < 0.0  -> Color(0x00, 0xAA, 0x44)   // 下跌：绿
                    else     -> UIManager.getColor("Table.foreground") ?: Color.BLACK
                }
                return c
            }
        }

        // 可排序
        table.rowSorter = TableRowSorter(tableModel)

        // 双击股票名称/代码 → 东财查看
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val viewRow = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (modelRow < 0 || modelRow >= items.size) return
                val code = items[modelRow].stockCode
                if (code.isNotBlank()) {
                    BrowserUtil.browse("https://quote.eastmoney.com/concept/${
                        if (code.startsWith("6")) "sh$code" else "sz$code"
                    }.html")
                }
            }
        })
    }

    // ── 数据加载 ─────────────────────────────────────────────────────────

    private fun loadData(forceRefresh: Boolean = false) {
        showCard("loading")
        refreshBtn.isEnabled = false
        infoLabel.text = ""

        if (forceRefresh) {
            // 清掉缓存，下次 fetchHoldings 会重新拉取
            // （FundHoldingsService 的缓存通过重新请求即可覆盖，
            //   此处直接调用即可，service 内部超时或 forceRefresh 逻辑可扩展）
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = FundHoldingsService.fetchHoldings(fundCode)
            SwingUtilities.invokeLater { applyResult(result) }
        }
    }

    private fun applyResult(result: FundHoldingsResult) {
        refreshBtn.isEnabled = true

        if (result.error != null) {
            statusLabel.text = "<html><center>${result.error}</center></html>"
            showCard("error")
            return
        }

        items = result.items
        chgMap.clear()
        tableModel.fireTableDataChanged()

        // 信息条
        val dateStr  = if (result.reportDate.isNotBlank()) "报告期：${result.reportDate}" else ""
        val labelStr = if (result.reportLabel.isNotBlank()) "（${result.reportLabel}）" else ""
        val countStr = if (result.totalCount > result.items.size)
            "  |  共持有 ${result.totalCount} 只股票，展示前 ${result.items.size} 只"
        else
            "  |  持有 ${result.items.size} 只股票"

        infoLabel.text = "  $dateStr$labelStr$countStr"

        if (items.isEmpty()) {
            statusLabel.text = "<html><center>暂无股票持仓明细。<br>该基金可能为债券型或货币市场基金。</center></html>"
            showCard("error")
        } else {
            showCard("table")
            // 异步拉取持仓股票的实时/收盘涨跌幅
            val snapshot = items.toList()
            ApplicationManager.getApplication().executeOnPooledThread {
                val fetched = fetchQuotesForHoldings(snapshot)
                SwingUtilities.invokeLater {
                    chgMap.putAll(fetched)
                    tableModel.fireTableDataChanged()
                }
            }
        }
    }

    /**
     * 使用新浪行情接口批量拉取持仓股票的涨跌幅。
     *
     * 仅处理 A 股（6位纯数字股票代码）；海外股（如 NVDA）跳过返回 NaN。
     * 新浪接口返回 GBK 编码，但我们只读取数字字段，无需特殊处理。
     *
     * @return Map<stockCode, changePercent>，仅包含成功拉取的条目
     */
    private fun fetchQuotesForHoldings(holdings: List<FundHoldingItem>)
        : Map<String, Double> {

        // 构造新浪格式的 symbol（仅处理纯数字 6 位 A 股代码）
        val codeToSymbol = mutableMapOf<String, String>()
        for (h in holdings) {
            val code = h.stockCode
            if (code.length == 6 && code.all { it.isDigit() }) {
                val prefix = when (code[0]) {
                    '6', '9'       -> "sh"
                    '0', '2', '3'  -> "sz"
                    '4', '8'       -> "bj"
                    else           -> "sz"
                }
                codeToSymbol[code] = "$prefix$code"
            }
            // 字母代码（NVDA 等海外股）直接跳过
        }
        if (codeToSymbol.isEmpty()) return emptyMap()

        val url = "http://hq.sinajs.cn/list=${codeToSymbol.values.joinToString(",")}"
        // 新浪用 GBK，但我们只取纯数字字段，用 UTF-8 也能读取
        val raw = HttpUtil.getGbk(url, "https://finance.sina.com.cn/")
            ?: return emptyMap()

        // 解析格式：var hq_str_sh600519="贵州茅台,开盘,昨收,现价,最高,最低,...";
        // 索引 2 = 昨收，索引 3 = 现价
        val result = mutableMapOf<String, Double>()
        val lineRe = Regex("""hq_str_[a-z]+(\d{6})\s*=\s*"([^"]*?)"""")
        for (m in lineRe.findAll(raw)) {
            val code   = m.groupValues[1]
            val fields = m.groupValues[2].split(",")
            if (fields.size < 4) continue
            val prevClose = fields[2].toDoubleOrNull() ?: continue
            val current   = fields[3].toDoubleOrNull() ?: continue
            if (prevClose <= 0.0) continue
            result[code] = (current - prevClose) / prevClose * 100.0
        }
        return result
    }

    private fun showCard(name: String) {
        (contentCards.layout as CardLayout).show(contentCards, name)
    }
}
