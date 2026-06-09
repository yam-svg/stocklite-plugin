package com.stocklite.plugin.util

import com.stocklite.plugin.state.StockliteState

/**
 * 多语言字符串表。
 *
 * 所有属性均使用自定义 getter（无 backing field），每次访问都读取当前语言设置，
 * 无需手动刷新缓存。在面板的 [onLanguageChanged] 中只需重新触发 UI 渲染即可。
 *
 * 用法示例：
 *   JButton(L10n.btnRefresh)           // 创建时读当前语言
 *   btn.text = L10n.btnRefresh         // 语言切换后刷新
 */
object L10n {

    fun isEn(): Boolean = try {
        StockliteState.getInstance().language == "EN"
    } catch (_: Exception) {
        false
    }

    private fun s(zh: String, en: String) = if (isEn()) en else zh

    // ── 标签页 ───────────────────────────────────────────────────────────
    val tabStock       get() = s("股票", "Stocks")
    val tabFund        get() = s("基金", "Funds")
    val tabFuture      get() = s("期货", "Futures")
    val tabGlobal      get() = s("全球", "Global")

    // ── 通用列头 ─────────────────────────────────────────────────────────
    val colName        get() = s("名称",   "Name")
    val colSymbol      get() = s("代码",   "Symbol")
    val colPrice       get() = s("现价",   "Price")
    val colChangePct   get() = s("涨跌幅", "Change%")
    val colQty         get() = s("持仓",   "Qty")
    val colCost        get() = s("成本价", "Cost")
    val colValue       get() = s("市值",   "Value")
    val colPnl         get() = s("盈亏",   "P&L")
    val colPnlPct      get() = s("盈亏%",  "P&L%")

    // ── 基金列头 ─────────────────────────────────────────────────────────
    val colNav         get() = s("当前净值", "NAV")
    val colOfficialChg get() = s("官方涨跌", "Official Chg")
    val colNavDate     get() = s("净值日期", "NAV Date")
    val colTodayEst    get() = s("今日估算", "Today Est.")
    val colShares      get() = s("持仓份额", "Shares")
    val colCostNav     get() = s("成本净值", "Cost NAV")

    // ── 全球指数列头 ─────────────────────────────────────────────────────
    val colIndex       get() = s("指数", "Index")
    val colMarket      get() = s("市场", "Market")
    val colStatus      get() = s("状态", "Status")

    // ── 按钮文字 ─────────────────────────────────────────────────────────
    val btnRefresh      get() = s("刷新",    "Refresh")
    val btnManageGroups get() = s("管理分组", "Groups")
    val btnAddStock     get() = s("添加股票", "Add Stock")
    val btnAddFund      get() = s("添加基金", "Add Fund")
    val btnAddFuture    get() = s("添加期货", "Add Future")
    val btnEdit         get() = s("编辑",    "Edit")
    val btnDelete       get() = s("删除",    "Delete")
    val btnSearch       get() = s("搜索",    "Search")
    val btnCreate       get() = s("新建",    "New")
    val btnRename       get() = s("重命名",  "Rename")

    // ── 工具栏标签 ───────────────────────────────────────────────────────
    val lblGroup       get() = s("分组:",      "Group:")
    val lblTotalValue  get() = s("总市值:",    "Total Value:")
    val lblTotalPnl    get() = s("总盈亏:",    "Total P&L:")
    val lblGlobalTitle get() = s("全球主要指数", "Global Indices")
    val lblLastUpdate  get() = s("上次更新:",  "Updated:")

    // ── 表格单元值 ───────────────────────────────────────────────────────
    val cellOpen       get() = s("交易中", "Open")
    val cellClosed     get() = s("休市",   "Closed")

    // ── 市场状态文字 ─────────────────────────────────────────────────────
    val statusWeekend  get() = s("休市（周末）", "Closed (Weekend)")
    fun statusPreOpen(t: String) = s("未开市（$t 开盘）", "Pre-market (opens $t)")
    val statusAMOpen   get() = s("交易中（上午盘）", "Open (AM session)")
    val statusLunch    get() = s("午休中（13:00 开盘）", "Lunch break (opens 13:00)")
    val statusPMOpen   get() = s("交易中（下午盘）", "Open (PM session)")
    val statusClosed   get() = s("已收盘", "Closed")
    fun statusRateLimited(secs: Int) =
        s("⚠ 请求受限，刷新间隔已延长至 ${secs}s", "⚠ Rate limited, interval extended to ${secs}s")
    fun statusInterval(secs: Int) = s("${secs}s/次", "${secs}s/tick")

    // ── 系统分组名称 ─────────────────────────────────────────────────────
    val groupAllStocks     get() = s("全部股票", "All Stocks")
    val groupHoldingStocks get() = s("我的持有", "My Holdings")
    val groupAllFunds      get() = s("全部基金", "All Funds")
    val groupHoldingFunds  get() = s("我的持有", "My Holdings")
    val groupAllFutures    get() = s("全部期货", "All Futures")

    // ── 对话框标题 ───────────────────────────────────────────────────────
    val dlgAddStock      get() = s("添加股票",     "Add Stock")
    val dlgEditStock     get() = s("编辑股票",     "Edit Stock")
    val dlgAddFund       get() = s("添加基金",     "Add Fund")
    val dlgEditFund      get() = s("编辑基金",     "Edit Fund")
    val dlgAddFuture     get() = s("添加期货合约", "Add Futures Contract")
    val dlgManageGroups  get() = s("管理分组",     "Manage Groups")

    // ── 对话框表单标签 ───────────────────────────────────────────────────
    val dlgSearch        get() = s("搜索:",    "Search:")
    val dlgSymbolLbl     get() = s("代码:",    "Symbol:")
    val dlgNameLbl       get() = s("名称:",    "Name:")
    val dlgCostLbl       get() = s("成本价:",   "Cost Price:")
    val dlgQtyLbl        get() = s("持仓数量:", "Quantity:")
    val dlgGroupLbl      get() = s("所属分组:", "Group:")
    val dlgFundCodeLbl   get() = s("基金代码:", "Fund Code:")
    val dlgFundNameLbl   get() = s("基金名称:", "Fund Name:")
    val dlgCostNavLbl    get() = s("成本净值:", "Cost NAV:")
    val dlgSharesLbl     get() = s("持仓份额:", "Shares:")
    val dlgFutureGrpLbl  get() = s("分组:",    "Group:")

    // ── 对话框提示信息 ───────────────────────────────────────────────────
    val dlgNoStockFound  get() = s("未找到相关股票",     "No stocks found")
    val dlgNoFundFound   get() = s("未找到相关基金",     "No funds found")
    val dlgNoFutureFound get() = s("未找到相关期货合约", "No futures found")

    fun dlgConfirmDelete(name: String) =
        s("确定删除「$name」？", "Delete \"$name\"?")
    fun dlgConfirmDeleteGroup(name: String) =
        s("删除分组「$name」？（该分组下的条目将移至其他分组）",
          "Delete group \"$name\"? (Items will be moved to another group.)")
    val dlgConfirmTitle  get() = s("删除确认", "Confirm Delete")
    val dlgNewGroupPrompt get() = s("输入分组名称", "Enter group name")
    val dlgNewGroupTitle get() = s("新建分组",     "New Group")
    val dlgRenamePrompt  get() = s("输入新名称",   "Enter new name")
    val dlgRenameTitle   get() = s("重命名",       "Rename")

    // ── 内嵌走势图 ───────────────────────────────────────────────────────
    val chartLoading     get() = s("数据加载中…", "Loading…")
    val chartNoData      get() = s("暂无当日数据", "No data for today")
    val chartCloseTip    get() = s("关闭图表",     "Close chart")
    val chartPrevClose   get() = s("昨收",         "Prev Close")
    val chartUnsupported get() = s(
        "当前 IDE 不支持内嵌浏览器（需 JetBrains IDE 2023.3+）",
        "Inline browser not supported (requires JetBrains IDE 2023.3+)"
    )

    // ── 设置页 ───────────────────────────────────────────────────────────
    val settingsLanguage    get() = s("界面语言",           "Interface Language")
    val settingsLangZh      get() = s("中文",               "中文 (Chinese)")
    val settingsLangEn      get() = s("English",            "English")
    val settingsColorScheme get() = s("涨跌幅颜色",          "Color Scheme")
    val settingsRedUp       get() = s("红涨绿跌（中国惯例）", "Red=Up / Green=Down (CN)")
    val settingsRedDown     get() = s("绿涨红跌（欧美惯例）", "Green=Up / Red=Down (US/EU)")
    val settingsNoColor     get() = s("无颜色",              "No color")
    val settingsStockCols   get() = s("股票列设置",           "Stock Columns")
    val settingsFundCols    get() = s("基金列设置",           "Fund Columns")

    // 设置页始终显示列标签
    private val always      get() = s("（始终显示）", " (always shown)")
    val settingsStockName   get() = colName      + always
    val settingsStockPrice  get() = colPrice     + always
    val settingsStockChange get() = colChangePct + always
    val settingsFundName    get() = colName      + always
    val settingsFundNav     get() = colNav       + always
    val settingsFundOfficialChg get() = colOfficialChg + always
    val settingsFundNavDate get() = s(
        "净值日期（始终显示，日期变今天即说明已更新）",
        "NAV Date (always shown; changes to today when official NAV is published)"
    )
    val settingsFundTodayEst get() = s(
        "今日估算（始终显示，官方净值更新后显示 官方✓）",
        "Today Est. (always shown; shows Official✓ after NAV is published)"
    )

    // 设置页可选列标签
    val settingsOptStockSymbol  get() = s("代码",    "Symbol")
    val settingsOptStockQty     get() = s("持仓数量", "Quantity")
    val settingsOptStockCost    get() = s("成本价",   "Cost Price")
    val settingsOptStockValue   get() = s("市值",     "Market Value")
    val settingsOptStockPnl     get() = s("盈亏",     "P&L")
    val settingsOptStockPnlPct  get() = s("盈亏%",    "P&L%")

    val settingsOptFundCode     get() = s("代码",    "Code")
    val settingsOptFundShares   get() = s("持仓份额", "Shares")
    val settingsOptFundCostNav  get() = s("成本净值", "Cost NAV")
    val settingsOptFundValue    get() = s("市值",     "Market Value")
    val settingsOptFundPnl      get() = s("盈亏",     "P&L")
    val settingsOptFundPnlPct   get() = s("盈亏%",    "P&L%")
}
