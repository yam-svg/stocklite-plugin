package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.SectorQuote
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class UsMarketPanel : JPanel(BorderLayout()),
    StockliteState.LanguageListener,
    StockliteState.FeatureToggleListener {

    /**
     * CLOSE：永远显示上次正式收盘涨跌幅（regularMarketPrice vs previousClose）
     * LIVE ：盘中=实时，盘前=preMarketPrice，盘后=postMarketPrice；按钮文字随 marketState 变
     */
    private enum class View { CLOSE, LIVE }

    // 板块分组定义：组名 → ETF 列表
    private val GROUPS = linkedMapOf(
        "科技 & AI" to listOf("XLK", "AIQ", "BOTZ", "ROBO", "SOXX", "SKYY", "DTCR", "HACK"),
        "工业 & 国防" to listOf("XLI", "ITA", "UFO", "DRIV"),
        "能源 & 新能源" to listOf("XLE", "XOP", "USO", "UNG", "ICLN", "TAN", "NLR", "GRID"),
        "材料 & 资源" to listOf("XLB", "GLD", "COPX", "LIT"),
        "医疗 & 消费 & 金融" to listOf("XLV", "XBI", "XLF", "KBE", "XLY", "XLP"),
        "其他" to listOf("XLU", "XLRE", "XLC")
    )

    private var view = View.CLOSE
    private var quotes: Map<String, SectorQuote> = emptyMap()
    private var panelActive = true
    private var refreshTimer: Timer? = null
    private var tickCount = 0

    private val statusLabel = JLabel("--").apply {
        font = font.deriveFont(11f); foreground = JBColor(0x666688, 0x888aaa)
    }
    private val pctLabels  = mutableMapOf<String, JLabel>()
    private val nameLabels = mutableMapOf<String, JLabel>()
    /** 各分组的卡片网格，窗口宽度变化时统一调整列数 */
    private val grids = mutableListOf<JPanel>()
    private var lastColumns = 0

    private lateinit var closeBtn: JButton
    private lateinit var liveBtn:  JButton
    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
    }

    init {
        StockliteState.getInstance().addLanguageListener(this)
        StockliteState.getInstance().addFeatureToggleListener(this)
        buildUI()
        buildGroups()
        contentPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyColumns()
        })
        // 功能开关关闭时不启动定时器，避免面板未开启也在后台持续拉取 30+ 个 ETF 数据
        if (StockliteState.getInstance().enableUsMarketPanel) scheduleRefresh()
        addHierarchyListener { _ ->
            val showing = isShowing
            if (showing != panelActive) {
                panelActive = showing
                if (showing) fetchAsync()
            }
        }
    }

    override fun onLanguageChanged() {
        closeBtn.text = L10n.lblSessionClose
        updateLiveBtnText()
        updateAllPctLabels()
        revalidate(); repaint()
    }

    /** 设置里开启/关闭美股板块时，同步启停后台轮询定时器 */
    override fun onFeatureToggleChanged() {
        val enabled = StockliteState.getInstance().enableUsMarketPanel
        if (enabled) {
            if (refreshTimer == null) scheduleRefresh()
            fetchAsync()
        } else {
            refreshTimer?.stop()
            refreshTimer = null
        }
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.add(JLabel(L10n.tabUsMarket).apply { font = font.deriveFont(Font.BOLD, 12f) })
        toolbar.add(JButton(L10n.btnRefresh).also { it.addActionListener { fetchAsync() } })
        toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 20) })

        closeBtn = tabBtn(L10n.lblSessionClose) { selectView(View.CLOSE) }
        liveBtn  = tabBtn(L10n.lblSessionLive)  { selectView(View.LIVE)  }
        toolbar.add(closeBtn)
        toolbar.add(liveBtn)
        toolbar.add(statusLabel)
        highlightView(View.CLOSE)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    private fun tabBtn(text: String, action: () -> Unit) = JButton(text).apply {
        isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
        font = font.deriveFont(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    private fun buildGroups() {
        contentPanel.removeAll()
        grids.clear()
        val symbolToName = MarketDataService.US_SECTOR_ETFS.toMap()

        for ((groupName, symbols) in GROUPS) {
            contentPanel.add(buildGroupHeader(groupName))

            // 卡片网格：列数随面板宽度自适应
            val grid = JPanel(GridLayout(0, desiredColumns(), 6, 4)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }
            for (symbol in symbols) {
                val nameCn = symbolToName[symbol] ?: symbol
                grid.add(buildCard(symbol, nameCn))
            }
            grids.add(grid)
            contentPanel.add(grid)
            contentPanel.add(Box.createVerticalStrut(6).apply { setAlignmentX(Component.LEFT_ALIGNMENT) })
        }
        contentPanel.revalidate()
        applyColumns()
    }

    /** 组标题：左侧加粗小标签 + 右侧细分隔线 */
    private fun buildGroupHeader(name: String) = JPanel(BorderLayout(8, 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        border = BorderFactory.createEmptyBorder(2, 0, 4, 0)
        add(JLabel(name).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor(0x666688, 0x888aaa)
        }, BorderLayout.WEST)
        add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.CENTER)
    }

    /** 按面板宽度计算卡片列数（目标卡片宽约 132px，2–8 列） */
    private fun desiredColumns(): Int =
        if (contentPanel.width < 100) 4
        else ((contentPanel.width - 16) / 132).coerceIn(2, 8)

    /** 宽度变化导致列数需求改变时，原地调整各网格列数并重新布局 */
    private fun applyColumns() {
        val cols = desiredColumns()
        if (cols == lastColumns) return
        lastColumns = cols
        for (g in grids) {
            (g.layout as? GridLayout)?.setColumns(cols)
            g.revalidate()
        }
    }

    private fun buildCard(symbol: String, nameCn: String): JPanel {
        val card = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xc8c8dc, 0x3a3a5a), 1),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
            )
            preferredSize = Dimension(0, 48)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }

        val gbc = GridBagConstraints()

        // 左列：中文名在上、代码在下；右列：涨跌幅大字纵向居中
        val nameLabel = JLabel(nameCn).apply { font = font.deriveFont(11f); foreground = JBColor(0x555577, 0xaaaacc) }
        nameLabels[symbol] = nameLabel
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.HORIZONTAL
        card.add(nameLabel, gbc)

        val symLabel = JLabel(symbol).apply {
            font = font.deriveFont(Font.BOLD, 10.5f)
            foreground = JBColor(0x444466, 0x9999bb)
        }
        gbc.gridy = 1
        card.add(symLabel, gbc)

        val pctLabel = JLabel("--").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            horizontalAlignment = SwingConstants.RIGHT
        }
        pctLabels[symbol] = pctLabel
        gbc.gridx = 1; gbc.gridy = 0; gbc.gridheight = 2; gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.EAST
        card.add(pctLabel, gbc)

        card.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val popup = JPopupMenu()
                popup.add(JMenuItem(L10n.btnCopySymbol).also { mi ->
                    mi.addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(symbol), null)
                    }
                })
                popup.show(card, e.x, e.y)
            }
        })

        return card
    }

    private fun selectView(v: View) {
        view = v
        highlightView(v)
        updateAllPctLabels()
    }

    private fun highlightView(selected: View) {
        listOf(closeBtn to View.CLOSE, liveBtn to View.LIVE).forEach { (btn, v) ->
            btn.foreground = if (v == selected) JBColor(0x222244, 0xcdd6f4) else JBColor(0x777799, 0x888aaa)
            btn.font = btn.font.deriveFont(if (v == selected) Font.BOLD else Font.PLAIN, 11f)
        }
    }

    private fun updateLiveBtnText() {
        liveBtn.text = when (MarketDataService.currentUsSession()) {
            MarketDataService.UsSession.PRE     -> L10n.lblSessionPre
            MarketDataService.UsSession.REGULAR -> L10n.lblSessionRegular
            MarketDataService.UsSession.POST    -> L10n.lblSessionPost
            MarketDataService.UsSession.CLOSED  -> L10n.lblSessionLive
        }
        // CLOSED 时段与"交易日但休市"两种情形都通过 tooltip 说明实时页签展示的是收盘数据
        liveBtn.toolTipText = when (MarketDataService.currentUsSession()) {
            MarketDataService.UsSession.CLOSED -> L10n.lblUsClosedLiveTip
            else -> marketClosedHint()
        }
    }

    /**
     * 休市日检测：处于盘前/盘中/盘后时段，但行情时间戳均不是今天（纽约时区）→ 美股休市。
     * 无法确认（Yahoo 兜底无延伸时间戳、全部延伸缺失）时不提示，避免误报。
     */
    private fun marketClosedHint(): String? {
        if (quotes.isEmpty()) return null
        val todayEt = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
            .toLocalDate().toString()
        val closed = when (MarketDataService.currentUsSession()) {
            MarketDataService.UsSession.REGULAR ->
                quotes.values.all { it.lastRegularDate != null && it.lastRegularDate != todayEt }
            MarketDataService.UsSession.PRE, MarketDataService.UsSession.POST ->
                quotes.values.any { it.extDate != null } && quotes.values.none { it.extDate == todayEt }
            else -> false
        }
        return if (closed) L10n.lblUsMarketClosed else null
    }

    fun fetchAsync() {
        if (!panelActive || !StockliteState.getInstance().enableUsMarketPanel) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getUsSectorQuotes()
            SwingUtilities.invokeLater {
                quotes = fetched
                val now = java.time.LocalTime.now()
                    .let { String.format("%02d:%02d", it.hour, it.minute) }
                val hint = marketClosedHint()
                statusLabel.text = "${L10n.lblLastUpdate} $now" + if (hint != null) "  ·  $hint" else ""
                updateLiveBtnText()
                updateAllPctLabels()
            }
        }
    }

    private fun updateAllPctLabels() {
        val scheme = StockliteState.getInstance().colorScheme
        val up   = QuoteRenderer.positiveColor(scheme)
        val dn   = QuoteRenderer.negativeColor(scheme)
        val flat = QuoteRenderer.FLAT

        for ((symbol, _) in MarketDataService.US_SECTOR_ETFS) {
            val label = pctLabels[symbol] ?: continue
            val q = quotes[symbol]
            if (q == null) { label.text = "--"; label.foreground = flat; continue }

            val pct: Double? = when (view) {
                View.CLOSE -> q.regularPct
                View.LIVE  -> when (MarketDataService.currentUsSession()) {
                    MarketDataService.UsSession.PRE     -> q.prePct  ?: q.regularPct
                    MarketDataService.UsSession.POST    -> q.postPct ?: q.regularPct
                    MarketDataService.UsSession.REGULAR -> q.regularPct
                    MarketDataService.UsSession.CLOSED  -> q.regularPct
                }
            }

            if (pct == null) {
                label.text = "--"; label.foreground = flat; label.toolTipText = null
            } else {
                val sign = if (pct >= 0) "+" else ""
                label.text = "$sign${"%.2f".format(pct)}%"
                label.foreground = when {
                    pct > 0 -> up ?: label.parent?.foreground ?: flat
                    pct < 0 -> dn ?: flat
                    else    -> flat
                }
                label.toolTipText = buildString {
                    append("收盘: ${"%.2f".format(q.regularPct)}%")
                    if (q.prePct  != null) append("  盘前: ${"%.2f".format(q.prePct)}%")
                    if (q.postPct != null) append("  盘后: ${"%.2f".format(q.postPct)}%")
                }
            }
        }
        contentPanel.repaint()
    }

    private fun scheduleRefresh() {
        // CLOSED 时段数据静态（收盘快照+周末/节假日），降频到 5 分钟一次；
        // 面板恢复可见时 HierarchyListener 会立即触发一次刷新
        refreshTimer = Timer(60_000) {
            if (MarketDataService.currentUsSession() != MarketDataService.UsSession.CLOSED ||
                tickCount++ % 5 == 0) fetchAsync()
        }.also { it.isRepeats = true; it.start() }
    }
}
