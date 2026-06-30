package com.stocklite.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class PortfolioStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId()          = PortfolioStatusWidget.ID
    override fun getDisplayName() = "StockLite 持仓"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = PortfolioStatusWidget()
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
