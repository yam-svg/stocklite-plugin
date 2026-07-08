# StockLite JetBrains 插件

股票 / 基金 / 期货行情与持仓管理，另含全球指数、美股板块看板，原生 Kotlin/Swing 实现，适配所有 JetBrains IDE（2024.1+）。

**当前版本：1.8.0**

## 功能

| 功能                        | 状态         |
|---------------------------|------------|
| 股票持仓管理（CRUD + 分组）         | ✅          |
| 基金持仓管理                    | ✅          |
| 期货持仓管理                    | ✅          |
| 实时行情（新浪 API，GBK 解码）       | ✅          |
| 基金估值（天天基金）                | ✅          |
| 全球指数（Yahoo Finance）       | ✅          |
| 港股行情（腾讯 API）              | ✅          |
| 美股行情（Yahoo Finance）       | ✅          |
| **美股板块面板（30+ ETF，收盘/实时双视图）** | ✅ 1.8 新增   |
| **美股盘前/盘后涨跌幅标注（股票面板）**     | ✅ 1.8 新增   |
| **美股搜索增强（东方财富全库，支持中文名）**   | ✅ 1.8 新增   |
| **K 线图均线（MA5/MA10/MA20）**  | ✅ 1.8 新增   |
| **K 线图成交量副图**             | ✅ 1.8 新增   |
| **A 股成交量昨日同期对比**          | ✅ 1.8 新增   |
| 搜索添加（股票/基金/期货）            | ✅          |
| 盈亏计算（颜色标注）                | ✅          |
| 分组管理（新建/重命名/删除）           | ✅          |
| 数据持久化（IDE 配置目录）           | ✅          |
| 日内走势图（内嵌面板，点击涨跌幅触发）       | ✅          |
| K 线周期切换（日内/日K/周K/月K，真实蜡烛图） | ✅          |
| 全球指数市场快捷筛选（全部/CN/HK/US/其他）  | ✅          |
| 全球指数数据延迟提示                | ✅          |
| 价格到价提醒（IDE 气泡通知）          | ✅          |
| 右键菜单（编辑/删除/提醒/复制/浏览器）     | ✅          |
| 快速筛选（工具栏搜索框）              | ✅          |
| 列宽记忆（拖动后自动持久化）            | ✅          |
| 刷新间隔可配置（股票/基金/全球指数各自独立）   | ✅          |
| 数据导入/导出（JSON 格式）           | ✅          |
| 输入验证（成本价/持仓数量/目标价）        | ✅          |
| 面板隐藏时暂停刷新（生命周期优化）         | ✅          |
| 价格自动小数位（2/3/4 位，兼容 ETF）   | ✅          |
| 中英文界面切换                   | ✅          |
| 基金持仓明细（右键 → 持仓明细，含涨跌幅列）  | ✅          |
| AI 深度分析（DeepSeek，多轮对话）    | ✅          |
| AI 联网搜索（Tavily 工具调用）       | ✅          |
| AI 深度推理模式（deepseek-reasoner） | ✅          |
| A 股大盘概览（涨跌家数/涨跌停/成交额/板块/主力资金） | ✅          |
| 股指期货多空持仓摘要                | ✅          |
| 盘后多空信号汇总（启发式 + 可选 AI 解读）  | ✅          |
| 改名功能（各模块右键自定义别名）          | ✅          |
| IDE 状态栏持仓概览               | ✅          |
| 拖拽排序                      | ❌（已移除）     |
| 资讯/新闻                     | ❌（已排除）     |
| 系统托盘                      | ❌（插件架构不支持） |

## 内嵌走势图

点击任意标的的**涨跌幅**列，行情面板底部会展开一块 260px 高的走势图区域，支持**日内 / 日K / 周K / 月K**四档周期一键切换：

- **日内**：折线/面积图，Y 轴显示涨跌幅 %（以昨收为基准，0% 处画虚线"昨收"基准线），涨时用红色（或绿色，跟随颜色方案设置），跌时对应另一色，使用 BaselineSeries 填充
- **日K / 周K / 月K**：只要数据源提供真实开高低收（OHLC）就渲染**真实蜡烛图**——覆盖股票、A 股指数、全球指数、国内期货、国际期货；没有真实 OHLC 数据的标的（如国际期货日内分钟线本身不含 OHLC）自动回退为折线图，不会用收盘价伪造K线
- 蜡烛图固定"涨红跌绿"配色，不受涨跌配色设置影响；折线/面积图仍跟随全局颜色方案
- 鼠标悬停显示 Tooltip：蜡烛图显示开/高/低/收 + 相对上一根K线的涨跌幅；日内显示时间 + 涨跌幅 % + 原始价格
- 再次点击同一标的，或点击面板右上角 ✕，关闭图表
- 复用同一 JBCefBrowser 实例，避免重复创建开销
- 图表库：[lightweight-charts v4.2.0](https://github.com/tradingview/lightweight-charts)（CDN 加载）

**数据来源：**

| 类型            | 日内                                                    | 日K / 周K / 月K                                                                 |
|---------------|--------------------------------------------------------|---------------------------------------------------------------------------|
| A 股 / A股指数    | 新浪 `CN_MarketDataService.getKLineData`（scale=1，仅取当日）      | 同一接口（scale=240/1200/5000），含真实 OHLC                                          |
| 国内期货（nf_）     | 新浪 `InnerFuturesNewService.getFewMinLine?type=1`（含真实 OHLC） | 新浪 `InnerFuturesNewService.getDailyKLine`；周K/月K 由日线本地聚合                        |
| 国际期货（hf_）     | 新浪 `GlobalFuturesService.getGlobalFuturesMinLine`（仅有最新价，无 OHLC，图表回退折线） | 新浪 `GlobalFuturesService.getGlobalFuturesDailyKLine`（含真实 OHLC）；周K/月K 本地聚合 |
| 全球指数 / 港美股    | Yahoo Finance `v8/finance/chart?interval=5m&range=1d`（含真实 OHLC） | 同一接口切换 `interval=1d/1wk/1mo`，含真实 OHLC                                       |

## 构建步骤

### 前置要求
- JDK 17+
- Gradle 8.8（或通过 wrapper 自动下载）

### 初始化 Gradle Wrapper（首次）
```bash
cd stocklite-plugin
gradle wrapper --gradle-version 8.8
```
> 如果没有全局 Gradle，可从 [gradle.org](https://gradle.org/install/) 安装，或用 IntelliJ IDEA 打开项目后让 IDE 自动处理。

### 在 IDE 沙箱中运行（开发调试）
```bash
./gradlew runIde
```
> 这会启动一个独立的 IntelliJ IDEA 沙箱实例，插件已加载。

### 打包为可安装 ZIP
```bash
./gradlew buildPlugin
```
输出路径：`build/distributions/stocklite-plugin-1.8.0.zip`

> **注意：** `buildSearchableOptions` 已禁用（防止沙箱 JVM 崩溃，exit code 3）。
> 签名默认关闭，仅在 `SIGN_PLUGIN=true` 时启用，本地构建无需证书文件。

### 签名并发布（仅上架 Marketplace 时）
```bash
set SIGN_PLUGIN=true   # Windows
./gradlew signPlugin publishPlugin
```

### 安装到 IDE
1. 打开 JetBrains IDE
2. Settings → Plugins → ⚙️ → Install Plugin from Disk
3. 选择上面生成的 ZIP 文件
4. 重启 IDE
5. 在右侧工具栏（或 View → Tool Windows → StockLite）打开面板

## 项目结构

```
src/main/kotlin/com/stocklite/plugin/
├── StockliteToolWindowFactory.kt   # Tool Window 入口
├── state/
│   ├── Models.kt                   # 数据模型 + 行情类型
│   └── StockliteState.kt           # 持久化状态（XML）
├── service/
│   ├── MarketDataService.kt        # 所有行情/搜索 HTTP 调用
│   └── ChartDataService.kt         # 日内走势图数据（K 线 / 分钟线）
├── util/
│   ├── HttpUtil.kt                 # HTTP 工具（含 GBK 解码）
│   └── MarketTimeUtil.kt           # 市场开市时间判断
└── ui/
    ├── StocklitePanel.kt           # 主面板（标签页）
    ├── StockPanel.kt               # 股票标签页
    ├── FundPanel.kt                # 基金标签页
    ├── FuturePanel.kt              # 期货标签页
    ├── GlobalPanel.kt              # 全球指数标签页
    ├── UsMarketPanel.kt            # 美股板块标签页（1.8 新增）
    ├── InlineChartPanel.kt         # 内嵌日内走势图面板（JBCefBrowser）
    ├── common/
    │   ├── Formatters.kt           # 数值格式化（自动 2/3/4 位小数）
    │   └── QuoteRenderer.kt        # 表格着色渲染器（红涨绿跌）
    ├── common/
    │   ├── Formatters.kt           # 数值格式化（自动 2/3/4 位小数）
    │   ├── QuoteRenderer.kt        # 表格着色渲染器（红涨绿跌）
    │   └── FlashRenderer.kt        # 价格闪烁渲染器（继承 QuoteRenderer）
    └── dialogs/
        ├── AddStockDialog.kt       # 添加/编辑股票
        ├── AddFundDialog.kt        # 添加/编辑基金
        ├── AddFutureDialog.kt      # 添加/编辑期货
        ├── ManageGroupsDialog.kt   # 分组管理
        ├── SetAlertDialog.kt       # 设置价格到价提醒
        └── ImportExportDialog.kt   # 数据导入/导出
```

## v1.8.0 新功能说明

### 美股板块面板
新增第五个标签页"美股板块"，展示 30+ 主流板块 ETF 行情，按行业分组（科技/AI/半导体/云计算/数据中心/网络安全、工业/军工/商业航天/自动驾驶、能源/石油/天然气/光伏/核电/电网/新能源、材料/黄金/铜有色/锂电池、医疗/生物科技、金融/银行、消费、通信等），4 列网格布局。

**双视图切换：**
- **收盘**：永远显示上次正式收盘涨跌幅（`regularMarketPrice vs previousClose`）
- **实时**：根据美东时间自动识别当前时段，标签文字随之变化：
  - 04:00–09:30 ET → 显示"盘前"，取盘前最新价
  - 09:30–16:00 ET → 显示"盘中"，取实时价
  - 16:00–20:00 ET → 显示"盘后"，取盘后最新价

悬浮 tooltip 同时显示三段数据；60 秒自动刷新，面板隐藏时暂停。

### K 线图均线与成交量
- 日K/周K/月K 蜡烛图新增 **MA5（黄）/ MA10（蓝）/ MA20（紫）** 均线叠加
- 新增**成交量副图**（主图底部 20%，涨红跌绿半透明柱，与主图时间轴联动）
- Tooltip 同步展示均线数值与成交量（自动换算万/亿单位）

### A 股成交量昨日同期对比
两市成交额后新增昨日同期对比：今日实时成交额（000902 中证流通口径）vs 昨日同期（按今日已过分钟数精确对齐，用 sh000300 沪深300 分时分布折算），实时显示放量/缩量百分比，悬浮显示精确金额差。

### 美股搜索增强
股票搜索接入东方财富美股数据库，直接支持中文名搜索（如"英伟达"、"美光科技"、"闪迪"），覆盖全量美股标的。

### 美股盘前/盘后数据标注
股票面板美股标的在盘前/盘后时段，涨跌幅列自动显示双行：上行为收盘涨跌幅，下行为灰色小字"盘前 +0.35%"或"盘后 -0.12%"。

## 构建配置说明

`build.gradle.kts` 中有两项关键配置，解决常见构建问题：

```kotlin
// 禁用 buildSearchableOptions —— 该任务会启动沙箱 IDE 扫描设置项，
// 在部分环境下因内存/网络问题导致 JVM 崩溃（exit code 3）。
// 该任务仅影响"设置搜索"功能，禁用后不影响插件核心功能。
buildSearchableOptions {
    enabled = false
}

// 签名仅在 SIGN_PLUGIN=true 时启用，避免本地构建触发 downloadZipSigner 下载。
val signingEnabled = System.getenv("SIGN_PLUGIN") == "true"
signPlugin {
    enabled = signingEnabled
    // certificateChain / privateKey 仅在 signingEnabled 时配置
}
```

## 数据存储

数据保存在 IDE 配置目录下的 `stocklite.xml`：
- Windows: `%APPDATA%\JetBrains\<IDE>\options\stocklite.xml`
- macOS: `~/Library/Application Support/JetBrains/<IDE>/options/stocklite.xml`
- Linux: `~/.config/JetBrains/<IDE>/options/stocklite.xml`
