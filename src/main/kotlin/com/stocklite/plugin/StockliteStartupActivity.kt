package com.stocklite.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.stocklite.plugin.service.BreadthWatcherService
import com.stocklite.plugin.service.ForecastWatcherService
import com.stocklite.plugin.service.FundNavWatcherService
import com.stocklite.plugin.service.PortfolioWatcherService
import com.stocklite.plugin.service.StockAlertWatcherService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.AlertManager

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
        app.getService(BreadthWatcherService::class.java)
        app.getService(ForecastWatcherService::class.java)

        // 新手指引入口气泡：仅新安装首次启动出现一次。
        // 新老用户以 hadPersistedConfig 判定（老用户升级时 stocklite.xml 存在、loadState 会置位），
        // 不能用"数据是否为空"——面板的内置数据播种同样会写入数据。
        showOnboardingHintOnceForNewUsers()
    }

    private fun showOnboardingHintOnceForNewUsers() {
        val state = StockliteState.getInstance()
        if (state.onboardingHintShown) return
        state.onboardingHintShown = true
        if (!state.hadPersistedConfig) AlertManager.notifyOnboardingHint()
    }
}
