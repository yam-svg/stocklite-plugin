package com.stocklite.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.stocklite.plugin.service.FundNavWatcherService
import com.stocklite.plugin.service.PortfolioWatcherService
import com.stocklite.plugin.service.StockAlertWatcherService

/**
 * IDE 启动时（首个项目打开后）触发后台监听服务实例化。
 *
 * ApplicationService 默认懒加载：只有代码显式调用 getService() 才会实例化。
 * 本类通过 postStartupActivity 在项目打开时访问两个服务，
 * 触发其 init 块执行（启动轮询计时器），
 * 使价格提醒与基金净值通知在面板不打开的情况下也能正常工作。
 */
class StockliteStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val app = ApplicationManager.getApplication()
        // 仅访问服务以触发实例化；init 块内的 Timer 负责后续轮询
        app.getService(FundNavWatcherService::class.java)
        app.getService(StockAlertWatcherService::class.java)
        app.getService(PortfolioWatcherService::class.java)
    }
}
