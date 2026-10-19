package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.UIUtil
import com.stocklite.plugin.settings.StockliteConfigurable
import com.stocklite.plugin.ui.StocklitePanel
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 新手指引对话框：把藏在右键菜单、特定列点击、默认关闭开关后面的功能
 * 做成可点击的清单，"去看看"直达对应标签页或设置页演示。
 * 入口：工具窗口标题栏 ? 按钮（首次安装另有气泡提示其位置）。
 */
class OnboardingDialog(
    private val project: Project,
    private val root: StocklitePanel
) : DialogWrapper(true) {

    init {
        title = L10n.onbTitle
        isResizable = true
        init()
    }

    /** 只读清单无"确定"语义，只保留关闭按钮（按钮文案与其余对话框一致） */
    override fun createActions(): Array<Action> =
        arrayOf(cancelAction.also { it.putValue(Action.NAME, L10n.btnClose) })

    private fun openSettings(): () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, StockliteConfigurable::class.java)
    }

    /** 跳转标签页；目标页因功能开关未启用而不在选项卡中时，降级为打开设置页引导开启 */
    private fun tabJump(target: JComponent): () -> Unit = {
        if (!root.selectTab(target)) openSettings()()
    }

    private data class Tip(
        val title: String,
        val detail: String,
        val buttonLabel: String? = null,
        val jump: (() -> Unit)? = null
    )

    override fun createCenterPanel(): JComponent {
        val tips = listOf(
            Tip(L10n.onbTChart,     L10n.onbTipChart,    L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTSummary,   L10n.onbTipSummary,  L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTTrades,    L10n.onbTipTrades,   L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTAlert,     L10n.onbTipAlert,    L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTSort,      L10n.onbTipSort,     L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTEtf,       L10n.onbTipEtf,      L10n.onbBtnStockTab, tabJump(root.stockPanel)),
            Tip(L10n.onbTFund,      L10n.onbTipFundNav,  L10n.onbBtnFundTab,  tabJump(root.fundPanel)),
            Tip(L10n.onbTStatusBar, L10n.onbTipStatusBar),
            Tip(L10n.onbTAi,        L10n.onbTipAi,       L10n.onbBtnSettings, openSettings()),
            Tip(L10n.onbTToggle,    L10n.onbTipToggle,   L10n.onbBtnSettings, openSettings()),
            Tip(L10n.onbTImport,    L10n.onbTipImport,   L10n.onbBtnSettings, openSettings()),
        )

        // 不套滚动容器、不用嵌套布局：行高被视口压缩过一次导致标题与说明重叠。
        // 11 行总高约 600px，直接平铺由 DialogWrapper 按 preferred 撑开。
        val list = JPanel(GridLayout(0, 1, 0, 6))
        val intro = JLabel("<html><body style='width:520px'>${L10n.onbIntro}</body></html>")
        intro.foreground = UIUtil.getLabelDisabledForeground()
        intro.border = BorderFactory.createEmptyBorder(0, 6, 4, 6)
        list.add(intro)
        tips.forEach { list.add(row(it)) }
        return list
    }

    /** 单条指引：标题+说明压进同一个 HTML JLabel（高度由 HTML 排版自算，无嵌套压缩风险） */
    private fun row(tip: Tip): JComponent {
        val row = JPanel(BorderLayout(10, 0))
        row.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        val label = JLabel(
            "<html><body style='width:470px'>" +
            "<b>${tip.title}</b><br>" +
            "<span style='color:#8a8a8a'>${tip.detail}</span>" +
            "</body></html>"
        )
        row.add(label, BorderLayout.CENTER)

        if (tip.buttonLabel != null && tip.jump != null) {
            val btn = JButton(tip.buttonLabel)
            btn.addActionListener {
                close(0)
                SwingUtilities.invokeLater { tip.jump() }
            }
            row.add(btn, BorderLayout.EAST)
        }
        return row
    }
}
