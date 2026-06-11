package com.stocklite.plugin.ui

import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.UIManager

/**
 * AI 市场分析面板（可折叠，嵌入行情面板底部）。
 *
 * 纯手动模式：只有用户点击 ↻ 按钮才会调用 AI API，不自动触发，避免浪费 Token。
 * 父面板每次行情刷新后调用 [updateContext] 静默更新数据快照；
 * 用户点击刷新时，使用最新快照重新开启对话。
 * 展开后用户还可在底部输入框与 AI 进一步对话。
 */
/**
 * @param modulePrompt  各模块专用的 system prompt，由父面板传入。
 *                      默认使用股票模块提示词（向后兼容）。
 */
class AiAnalysisPanel(
    private val modulePrompt: String = AiAnalysisService.promptForStock
) : JPanel(BorderLayout()) {

    private var latestContext = ""
    private var isExpanded    = false

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

    // ── Header 控件 ───────────────────────────────────────────────────
    private val toggleBtn = JButton("▶").apply {
        isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        cursor = Cursor(Cursor.HAND_CURSOR)
        font   = font.deriveFont(Font.BOLD, 11f)
        preferredSize = Dimension(22, 22)
    }

    private val titleLabel = JLabel(L10n.aiPanelTitle).apply {
        font = font.deriveFont(Font.BOLD, 11f)
    }

    private val statusLabel = JLabel("").apply {
        font       = font.deriveFont(Font.PLAIN, 10f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }

    private val balanceLabel = JLabel("").apply {
        font       = font.deriveFont(Font.PLAIN, 10f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }

    private val refreshBtn = JButton("↻").apply {
        isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        cursor     = Cursor(Cursor.HAND_CURSOR)
        font       = font.deriveFont(14f)
        toolTipText = L10n.aiRefresh
    }

    // ── 对话面板 ──────────────────────────────────────────────────────
    private val chatPanel = AiChatPanel(onAfterResponse = { balance ->
        statusLabel.text  = "  ${LocalTime.now().format(TIME_FMT)}"
        balanceLabel.text = balance?.let { "  |  $it" } ?: ""
        refreshBtn.isEnabled = true
    })

    /** 包裹 chatPanel 的可折叠容器 */
    private val contentWrapper = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(0, 200)   // 比原来 110px 高，容纳对话记录 + 输入框
        isVisible     = false
        add(chatPanel, BorderLayout.CENTER)
    }

    init {
        border = BorderFactory.createMatteBorder(
            1, 0, 0, 0,
            UIManager.getColor("Separator.foreground") ?: Color.GRAY
        )

        // ── Header ──
        val header = JPanel(BorderLayout()).apply {
            border   = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            isOpaque = false
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply { isOpaque = false }
        left.add(toggleBtn)
        left.add(titleLabel)
        left.add(statusLabel)
        left.add(balanceLabel)
        header.add(left,       BorderLayout.WEST)
        header.add(refreshBtn, BorderLayout.EAST)

        add(header,         BorderLayout.NORTH)
        add(contentWrapper, BorderLayout.CENTER)

        // ── Listeners ──
        toggleBtn.addActionListener { toggle() }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { toggle() }
        })
        refreshBtn.addActionListener { doAnalyze() }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * 父面板在每次行情刷新后调用此方法，静默更新数据快照。
     * 不触发 AI 调用，等待用户手动点击 ↻。
     */
    fun updateContext(context: String) {
        if (context.isBlank()) return
        latestContext = context
    }

    /** 父面板 dispose 时调用（保留签名以兼容调用方）。 */
    fun dispose() { /* 无定时器，无需清理 */ }

    // ── Private ───────────────────────────────────────────────────────

    private fun toggle() {
        isExpanded              = !isExpanded
        toggleBtn.text          = if (isExpanded) "▼" else "▶"
        contentWrapper.isVisible = isExpanded
        revalidate(); repaint()
        (parent as? JComponent)?.revalidate()
    }

    private fun doAnalyze() {
        if (latestContext.isBlank()) {
            chatPanel.showHint(L10n.aiNoData); return
        }
        val apiKey = StockliteState.getInstance().deepseekApiKey.trim()
        if (apiKey.isEmpty()) {
            chatPanel.showHint(L10n.aiNoApiKey); return
        }

        refreshBtn.isEnabled = false
        statusLabel.text     = ""
        chatPanel.startConversation(
            sysPrompt          = modulePrompt,
            initialUserContent = "以下是当前市场实时数据，请给出简短分析：\n\n$latestContext"
        )
    }
}
