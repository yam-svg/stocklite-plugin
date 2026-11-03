package com.stocklite.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.stocklite.plugin.settings.StockliteConfigurable
import com.stocklite.plugin.ui.StocklitePanel
import com.stocklite.plugin.ui.dialogs.OnboardingDialog

class StockliteToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StocklitePanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        // 内容销毁时释放面板在 ApiLogger/State 里的监听，防止重开工具窗口后旧面板堆积空转
        Disposer.register(content, Disposable { panel.dispose() })

        // 新手指引按钮（?）：常驻入口，随时可重看隐藏功能清单
        val guideAction = object : AnAction(
            "StockLite Guide",
            "Open the beginner guide",
            AllIcons.General.ContextHelp
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                OnboardingDialog(project, panel).show()
            }
        }

        // 隐秘模式快捷开关（👁）：点击循环 关闭→全拼→首字母→关闭，配合设置页三态单选
        fun stealthState() = com.stocklite.plugin.state.StockliteState.getInstance()
        val stealthAction = object : AnAction(
            "StockLite Stealth Mode",
            "Cycle stealth mode: off -> full pinyin -> pinyin initials",
            AllIcons.Actions.Show
        ) {
            override fun update(e: AnActionEvent) {
                val tip = when (stealthState().stealthMode) {
                    "FULL" -> "Stealth: FULL pinyin (click -> initials)"
                    "ABBR" -> "Stealth: INITIALS (click -> off)"
                    else   -> "Stealth: off (click -> full pinyin)"
                }
                e.presentation.description = tip
                e.presentation.isEnabled = true
            }
            override fun actionPerformed(e: AnActionEvent) {
                val st = stealthState()
                st.stealthMode = com.stocklite.plugin.util.StealthName.cycle(st.stealthMode)
                st.notifyStealthChanged()
            }
        }

        // 在工具窗口标题栏右侧添加设置齿轮按钮
        val settingsAction = object : AnAction(
            "StockLite Settings",
            "Open StockLite settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, StockliteConfigurable::class.java)
            }
        }

        (toolWindow as? ToolWindowEx)?.setTitleActions(listOf(guideAction, stealthAction, settingsAction))
    }
}
