package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.state.SectorQuote
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.QuoteRenderer
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class UsMarketPanel : JPanel(BorderLayout()), StockliteState.LanguageListener {

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

    private val statusLabel = JLabel("--").apply {
        font = font.deriveFont(11f); foreground = Color(0x888aaa)
    }
    private val pctLabels  = mutableMapOf<String, JLabel>()
    private val nameLabels = mutableMapOf<String, JLabel>()

    private lateinit var closeBtn: JButton
    private lateinit var liveBtn:  JButton
    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
    }

    init {
        StockliteState.getInstance().addLanguageListener(this)
        buildUI()
        buildGroups()
        scheduleRefresh()
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
        val symbolToName = MarketDataService.US_SECTOR_ETFS.toMap()

        for ((groupName, symbols) in GROUPS) {
            // 组标题
            val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }
            titlePanel.add(JLabel(groupName).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = Color(0x888aaa)
            })
            contentPanel.add(titlePanel)

            // 卡片网格：每行 4 列
            val grid = JPanel(GridLayout(0, 4, 6, 4)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }

            for (symbol in symbols) {
                val nameCn = symbolToName[symbol] ?: symbol
                val card = buildCard(symbol, nameCn)
                grid.add(card)
            }

            contentPanel.add(grid)
            contentPanel.add(Box.createVerticalStrut(6))
        }
        contentPanel.revalidate()
    }

    private fun buildCard(symbol: String, nameCn: String): JPanel {
        val card = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0x3a3a5a), 1),
                BorderFactory.createEmptyBorder(5, 6, 5, 6)
            )
            preferredSize = Dimension(0, 46)
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }

        val gbc = GridBagConstraints()

        // 行1：中文名（左对齐）
        val nameLabel = JLabel(nameCn).apply { font = font.deriveFont(10f); foreground = Color(0xaaaacc) }
        nameLabels[symbol] = nameLabel
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        card.add(nameLabel, gbc)

        // 行2：ETF代码（左）+ 涨跌幅（右）
        val symLabel = JLabel(symbol).apply { font = font.deriveFont(Font.BOLD, 11f) }
        gbc.gridx = 0; gbc.gridy = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0
        card.add(symLabel, gbc)

        val pctLabel = JLabel("--").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            horizontalAlignment = SwingConstants.RIGHT
        }
        pctLabels[symbol] = pctLabel
        gbc.gridx = 1; gbc.gridy = 1; gbc.anchor = GridBagConstraints.EAST; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
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
            btn.foreground = if (v == selected) Color(0xcdd6f4) else Color(0x888aaa)
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
        liveBtn.toolTipText = null
    }

    fun fetchAsync() {
        if (!panelActive) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fetched = MarketDataService.getUsSectorQuotes()
            SwingUtilities.invokeLater {
                quotes = fetched
                val now = java.time.LocalTime.now()
                    .let { String.format("%02d:%02d", it.hour, it.minute) }
                statusLabel.text = "${L10n.lblLastUpdate} $now"
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
        refreshTimer = Timer(60_000) { fetchAsync() }.also { it.isRepeats = true; it.start() }
    }
}
