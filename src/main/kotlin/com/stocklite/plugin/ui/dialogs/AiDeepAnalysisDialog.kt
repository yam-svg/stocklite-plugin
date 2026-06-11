package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.service.AiAnalysisService
import com.stocklite.plugin.ui.AiChatPanel
import com.stocklite.plugin.util.L10n
import java.awt.*
import javax.swing.*

/**
 * 单标的 AI 深度分析 + 多轮对话弹窗。
 *
 * 打开后自动发起第一次分析；用户可在底部输入框继续追问。
 * 点击"↻ 重新分析"可清空历史、重新开始。
 *
 * @param displayTitle  弹窗标题（通常为"名称 (代码)"）
 * @param itemContext   格式化的单条行情数据，直接传给 AI
 */
class AiDeepAnalysisDialog(
    private val displayTitle: String,
    private val itemContext:  String
) : DialogWrapper(true) {

    private val balanceLabel = JLabel("").apply {
        font       = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }

    private val reAnalyzeBtn = JButton("↻  ${L10n.btnAiDeepAnalysis}").apply {
        addActionListener { startAnalysis() }
    }

    private val chatPanel = AiChatPanel(onAfterResponse = { balance ->
        balanceLabel.text = balance?.let { "  $it" } ?: ""
        reAnalyzeBtn.isEnabled = true
    })

    init {
        title   = "${L10n.btnAiDeepAnalysis} — $displayTitle"
        isModal = true
        init()
        startAnalysis()
    }

    override fun createCenterPanel(): JComponent {
        val wrapper = JPanel(BorderLayout(0, 0)).apply {
            border         = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            preferredSize  = Dimension(600, 420)
        }
        wrapper.add(chatPanel, BorderLayout.CENTER)
        return wrapper
    }

    /** 只保留"关闭"按钮 */
    override fun createActions(): Array<Action> = arrayOf(cancelAction.also {
        (it as? DialogWrapper.CancelAction)?.putValue(Action.NAME, L10n.btnClose)
    })

    /** 南部追加：重新分析按钮 + 余额标签 */
    override fun createSouthAdditionalPanel(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(reAnalyzeBtn)
            add(balanceLabel)
        }

    // ── 分析逻辑 ────────────────────────────────────────────────────────

    private fun startAnalysis() {
        reAnalyzeBtn.isEnabled = false
        chatPanel.startConversation(
            sysPrompt            = AiAnalysisService.promptForItem,
            initialUserContent   = "请对以下金融产品进行深度分析：\n\n$itemContext"
        )
    }
}
