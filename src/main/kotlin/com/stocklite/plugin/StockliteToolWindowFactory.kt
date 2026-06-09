package com.stocklite.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.stocklite.plugin.settings.StockliteConfigurable
import com.stocklite.plugin.ui.StocklitePanel

class StockliteToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StocklitePanel()
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

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

        (toolWindow as? ToolWindowEx)?.setTitleActions(listOf(settingsAction))
    }
}
