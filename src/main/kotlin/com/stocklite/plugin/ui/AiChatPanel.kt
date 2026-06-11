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
 * 顶部：可滚动的对话记录（AI 和用户消息交替显示）。
 * 底部：单行输入框 + 发送按钮。
 *
 * 使用方式：
 * 1. 将本组件嵌入父容器。
 * 2. 调用 [startConversation] 开启对话并自动发送第一条分析请求。
 * 3. 用户可在输入框中继续提问；按 Enter 或点击"发送"即可。
 *
 * @param onAfterResponse  每次 AI 回复后的回调，参数为最新余额字符串（可能为 null）。
 *                         可用于更新父组件的余额标签。
 */
class AiChatPanel(
    private val onAfterResponse: ((String?) -> Unit)? = null
) : JPanel(BorderLayout()) {

    // 对话历史，格式为 Pair(role, content)；role = "user" | "assistant"
    private val history = mutableListOf<Pair<String, String>>()
    private var systemPrompt = ""
    private var isWaiting    = false

    // ── 显示区域 ────────────────────────────────────────────────────────
    private val displayArea = JTextArea().apply {
        lineWrap = true; wrapStyleWord = true; isEditable = false
        background = UIManager.getColor("TextArea.background") ?: Color(0x2B, 0x2B, 0x2B)
        foreground = UIManager.getColor("TextArea.foreground") ?: Color.LIGHT_GRAY
        border     = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        font       = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }

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
        val scroll = JBScrollPane(displayArea).apply { border = null }

        val inputRow = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    1, 0, 0, 0,
                    UIManager.getColor("Separator.foreground") ?: Color.GRAY
                ),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
            )
            add(inputField, BorderLayout.CENTER)
            add(sendBtn,    BorderLayout.EAST)
        }

        add(scroll,    BorderLayout.CENTER)
        add(inputRow,  BorderLayout.SOUTH)

        sendBtn.addActionListener   { onSend() }
        inputField.addActionListener { onSend() }

        // 初始状态：禁用输入，直到 startConversation 被调用
        setInputEnabled(false)
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * 开启新对话（清空历史），并立即以 [initialUserContent] 作为第一条用户消息发起请求。
     *
     * @param sysPrompt           DeepSeek system prompt
     * @param initialUserContent  第一条用户消息（通常是格式化的行情数据）
     */
    fun startConversation(sysPrompt: String, initialUserContent: String) {
        systemPrompt = sysPrompt
        history.clear()

        val apiKey = StockliteState.getInstance().deepseekApiKey.trim()
        if (apiKey.isEmpty()) {
            displayArea.text = L10n.aiNoApiKey
            setInputEnabled(false)
            onAfterResponse?.invoke(null)   // 通知父组件重新启用按钮
            return
        }

        history.add("user" to initialUserContent)
        renderHistory(showWaiting = true)
        setInputEnabled(false)

        val snapshot = history.toList()   // 快照，避免背景线程并发读写
        ApplicationManager.getApplication().executeOnPooledThread {
            val response = AiAnalysisService.chat(systemPrompt, snapshot, apiKey)
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
     * 同时清空历史并禁用输入。
     */
    fun showHint(text: String) {
        history.clear()
        displayArea.text = text
        setInputEnabled(false)
    }

    // ── Private ─────────────────────────────────────────────────────────

    private fun onSend() {
        val text = inputField.text.trim()
        if (text.isEmpty() || isWaiting) return

        val apiKey = StockliteState.getInstance().deepseekApiKey.trim()
        if (apiKey.isEmpty()) {
            showHint(L10n.aiNoApiKey); return
        }

        inputField.text = ""
        history.add("user" to text)
        renderHistory(showWaiting = true)
        setInputEnabled(false)

        val snapshot = history.toList()   // 快照
        ApplicationManager.getApplication().executeOnPooledThread {
            val response = AiAnalysisService.chat(systemPrompt, snapshot, apiKey)
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
     * 根据 [history] 重新渲染整个对话区域。
     * [showWaiting] = true 时在末尾追加"◀ AI\n…"占位符。
     */
    private fun renderHistory(showWaiting: Boolean = false) {
        val sb = StringBuilder()

        // 首条是用户的初始上下文（行情数据），内容很长，折叠显示
        history.forEachIndexed { i, (role, content) ->
            if (i > 0) sb.append("\n\n────────────────────────────────\n\n")
            if (i == 0 && role == "user") {
                // 初始上下文：只显示前两行作为折叠摘要，其余省略
                sb.append("▶ 你（行情数据，已发送）")
            } else {
                val label = if (role == "user") "▶ 你" else "◀ AI"
                sb.append("$label\n$content")
            }
        }

        if (showWaiting) {
            if (history.isNotEmpty()) sb.append("\n\n────────────────────────────────\n\n")
            sb.append("◀ AI\n${L10n.aiAnalyzing}")
        }

        displayArea.text = sb.toString()
        // 滚到底部
        displayArea.caretPosition = maxOf(0, displayArea.document.length - 1)
    }

    private fun setInputEnabled(enabled: Boolean) {
        isWaiting          = !enabled
        inputField.isEnabled = enabled
        sendBtn.isEnabled    = enabled
    }
}
