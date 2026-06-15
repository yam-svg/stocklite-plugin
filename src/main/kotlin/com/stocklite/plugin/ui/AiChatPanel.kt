package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.*
import javax.swing.*

/**
 * 通用 AI 多轮对话面板。
 *
 * 顶部：可滚动的对话记录（JTextPane HTML 渲染，AI 回复原样展示）。
 * 底部：单行输入框 + 发送按钮。
 *
 * 使用方式：
 * 1. 将本组件嵌入父容器。
 * 2. 调用 [startConversation] 开启对话并自动发送第一条分析请求。
 * 3. 用户可在输入框中继续提问；按 Enter 或点击"发送"即可。
 *
 * @param onAfterResponse  每次 AI 回复后的回调，参数为最新余额字符串（可能为 null）。
 */
class AiChatPanel(
    private val onAfterResponse: ((String?) -> Unit)? = null
) : JPanel(BorderLayout()) {

    // 对话历史，格式为 Pair(role, content)；role = "user" | "assistant"
    private val history = mutableListOf<Pair<String, String>>()
    private var systemPrompt = ""
    private var isWaiting    = false
    // 当前进度描述，由 onProgress 回调更新，渲染在等待占位符内
    private var progressText = L10n.aiAnalyzing

    // IDE 配色
    private val bgColor  = UIManager.getColor("TextArea.background") ?: Color(0x2B, 0x2B, 0x2B)
    private val fgColor  = UIManager.getColor("TextArea.foreground") ?: Color(0xBB, 0xBB, 0xBB)
    private val dimColor = UIManager.getColor("Label.disabledForeground") ?: Color(0x88, 0x88, 0x88)
    private val sepColor = UIManager.getColor("Separator.foreground") ?: Color(0x55, 0x55, 0x55)

    // ── 显示区域（HTML 渲染）────────────────────────────────────────────
    private val displayPane = JTextPane().apply {
        contentType = "text/html"
        isEditable  = false
        background  = bgColor
        border      = BorderFactory.createEmptyBorder(8, 10, 8, 10)
    }
    private val scroll = JBScrollPane(displayPane).apply { border = null }

    // ── 输入区域 ────────────────────────────────────────────────────────
    private val inputField = JTextField().apply {
        toolTipText = "输入问题后按 Enter 或点击发送"
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    private val sendBtn = JButton(L10n.btnSend).apply {
        preferredSize = Dimension(64, 28)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

    init {
        val inputRow = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    1, 0, 0, 0, sepColor
                ),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
            )
            add(inputField, BorderLayout.CENTER)
            add(sendBtn,    BorderLayout.EAST)
        }

        add(scroll,    BorderLayout.CENTER)
        add(inputRow,  BorderLayout.SOUTH)

        sendBtn.addActionListener    { onSend() }
        inputField.addActionListener { onSend() }

        setInputEnabled(false)
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * 开启新对话（清空历史），并立即以 [initialUserContent] 作为第一条用户消息发起请求。
     */
    fun startConversation(sysPrompt: String, initialUserContent: String) {
        systemPrompt = sysPrompt
        history.clear()

        val apiKey = StockliteState.getInstance().deepseekApiKey.trim()
        if (apiKey.isEmpty()) {
            showHint(L10n.aiNoApiKey)
            onAfterResponse?.invoke(null)
            return
        }

        progressText = L10n.aiAnalyzing
        history.add("user" to initialUserContent)
        renderHistory(showWaiting = true)
        setInputEnabled(false)

        val snapshot = history.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            val response = AiAnalysisService.chat(systemPrompt, snapshot, apiKey) { step ->
                SwingUtilities.invokeLater {
                    progressText = step
                    renderHistory(showWaiting = true)
                }
            }
            val balance  = AiAnalysisService.fetchBalance(apiKey)
            SwingUtilities.invokeLater {
                history.add("assistant" to response)
                renderHistory()
                setInputEnabled(true)
                inputField.requestFocusInWindow()
                onAfterResponse?.invoke(balance)
            }
        }
    }

    /**
     * 显示一条提示文字（非对话状态），例如"暂无数据"或"请填写 API Key"。
     */
    fun showHint(text: String) {
        history.clear()
        setInputEnabled(false)
        displayPane.text = buildHtml("<span style='color:${css(dimColor)};'>${escHtml(text)}</span>")
    }

    // ── Private ─────────────────────────────────────────────────────────

    private fun onSend() {
        val text = inputField.text.trim()
        if (text.isEmpty() || isWaiting) return

        val apiKey = StockliteState.getInstance().deepseekApiKey.trim()
        if (apiKey.isEmpty()) { showHint(L10n.aiNoApiKey); return }

        progressText = L10n.aiAnalyzing
        inputField.text = ""
        history.add("user" to text)
        renderHistory(showWaiting = true)
        setInputEnabled(false)

        val snapshot = history.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            val response = AiAnalysisService.chat(systemPrompt, snapshot, apiKey) { step ->
                SwingUtilities.invokeLater {
                    progressText = step
                    renderHistory(showWaiting = true)
                }
            }
            val balance  = AiAnalysisService.fetchBalance(apiKey)
            SwingUtilities.invokeLater {
                history.add("assistant" to response)
                renderHistory()
                setInputEnabled(true)
                inputField.requestFocusInWindow()
                onAfterResponse?.invoke(balance)
            }
        }
    }

    /**
     * 根据 [history] 重新渲染整个对话区域（HTML）。
     * [showWaiting] = true 时在末尾追加"AI 正在思考…"占位符。
     */
    private fun renderHistory(showWaiting: Boolean = false) {
        val body = StringBuilder()

        history.forEachIndexed { i, (role, content) ->
            if (i > 0) body.append(divider())
            if (i == 0 && role == "user") {
                // 首条用户上下文（行情数据）折叠显示
                body.append("""
                    <div style='color:${css(dimColor)}; font-size:11px;'>
                        ▶ 你（行情数据，已发送）
                    </div>
                """.trimIndent())
            } else if (role == "user") {
                body.append("""
                    <div style='color:${css(dimColor)}; margin-bottom:4px; font-size:11px;'>▶ 你</div>
                    <div style='color:${css(fgColor)};'>${escHtml(content).replace("\n", "<br>")}</div>
                """.trimIndent())
            } else {
                // AI 回复：完整渲染 markdown
                body.append("""
                    <div style='color:${css(dimColor)}; margin-bottom:6px; font-size:11px;'>◀ AI</div>
                    <div style='color:${css(fgColor)}; line-height:1.6;'>${mdToHtml(content)}</div>
                """.trimIndent())
            }
        }

        if (showWaiting) {
            if (history.isNotEmpty()) body.append(divider())
            body.append("""
                <div style='color:${css(dimColor)}; margin-bottom:6px; font-size:11px;'>◀ AI</div>
                <div style='color:${css(dimColor)};'>${escHtml(progressText)}</div>
            """.trimIndent())
        }

        displayPane.text = buildHtml(body.toString())
        // 滚到底部
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    /** 将 Markdown 文本转为 HTML，保留 AI 回复的原始格式 */
    private fun mdToHtml(text: String): String {
        var s = escHtml(text)

        // 粗体：**text** 或 __text__
        s = s.replace(Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)) { "<b>${it.groupValues[1]}</b>" }
        s = s.replace(Regex("""__(.+?)__""",       RegexOption.DOT_MATCHES_ALL)) { "<b>${it.groupValues[1]}</b>" }

        // 斜体：*text* 或 _text_（不含已处理的粗体标记）
        s = s.replace(Regex("""\*([^*\n]+?)\*""")) { "<i>${it.groupValues[1]}</i>" }

        // 行内代码：`code`
        s = s.replace(Regex("""`([^`]+?)`""")) {
            "<code style='background:${css(sepColor)};padding:1px 3px;border-radius:3px;'>${it.groupValues[1]}</code>"
        }

        // 中文结构标题 【...】 加粗并换色
        val titleColor = UIManager.getColor("Link.activeForeground")?.let { css(it) } ?: "#6a9fd8"
        s = s.replace(Regex("""【([^】]+)】""")) { "<b style='color:$titleColor;'>【${it.groupValues[1]}】</b>" }

        // 无序列表：- item 或 * item（行首）
        s = s.replace(Regex("""^[-*]\s+(.+)$""", RegexOption.MULTILINE)) { "• ${it.groupValues[1]}" }

        // 有序列表：1. item（行首）
        s = s.replace(Regex("""^\d+\.\s+(.+)$""", RegexOption.MULTILINE)) { "• ${it.groupValues[1]}" }

        // 段落：双换行 → 段落间距
        s = s.replace("\n\n", "</p><p style='margin:6px 0;'>")

        // 单换行 → <br>
        s = s.replace("\n", "<br>")

        return "<p style='margin:0;'>$s</p>"
    }

    private fun divider() =
        "<hr style='border:none; border-top:1px solid ${css(sepColor)}; margin:10px 0;'>"

    private fun buildHtml(body: String): String {
        val fg = css(fgColor)
        val bg = css(bgColor)
        return """
            <html>
            <head><style>
              body { font-family: sans-serif; font-size: 12px; color: $fg; background: $bg; margin: 0; padding: 0; }
              p { margin: 4px 0; }
              b { font-weight: bold; }
              i { font-style: italic; }
            </style></head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    /** Color → CSS `#rrggbb` */
    private fun css(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /** HTML 实体转义（先转 & 再转其他） */
    private fun escHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun setInputEnabled(enabled: Boolean) {
        isWaiting            = !enabled
        inputField.isEnabled = enabled
        sendBtn.isEnabled    = enabled
    }
}
