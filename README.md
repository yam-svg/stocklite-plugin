# StockLite JetBrains 插件

股票 / 基金 / 期货行情与持仓管理，原生 Kotlin/Swing 实现，适配所有 JetBrains IDE（2024.1+）。

## 功能

| 功能                        | 状态         |
|---------------------------|------------|
| 股票持仓管理（CRUD + 分组）         | ✅          |
| 基金持仓管理                    | ✅          |
| 期货持仓管理                    | ✅          |
| 实时行情（新浪 API，GBK 解码）       | ✅          |
| 基金估值（天天基金）                | ✅          |
| 全球指数（Yahoo Finance）       | ✅          |
| 搜索添加（股票/基金/期货）            | ✅          |
| 盈亏计算（颜色标注）                | ✅          |
| 分组管理（新建/重命名/删除）           | ✅          |
| 数据持久化（IDE 配置目录）           | ✅          |
| 日内走势图（内嵌面板，点击涨跌幅触发）       | ✅          |
| 价格自动小数位（2/3/4 位，兼容 ETF）   | ✅          |
| 拖拽排序                      | ❌（已移除）     |
| 资讯/新闻                     | ❌（已排除）     |
| 系统托盘                      | ❌（插件架构不支持） |

## 日内走势图

点击任意标的的**涨跌幅**列，行情面板底部会展开一块 260px 高的走势图区域：

- **Y 轴显示涨跌幅 %**（以昨收为基准，0% 处画虚线"昨收"基准线）
- 涨时用红色（或绿色，跟随颜色方案设置），跌时对应另一色，使用 BaselineSeries 填充
- 鼠标悬停显示 Tooltip：时间 + 涨跌幅 % + 原始价格
- 再次点击同一标的，或点击面板右上角 ✕，关闭图表
- 复用同一 JBCefBrowser 实例，避免重复创建开销
- 图表库：[lightweight-charts v4.2.0](https://github.com/tradingview/lightweight-charts)（CDN 加载）

**数据来源：**

| 类型         | API                                      |
|------------|------------------------------------------|
| A 股 / 基金   | 新浪 `CN_MarketDataService.getKLineData`（scale=1，仅取当日） |
| 国内期货（nf_）  | 新浪 `InnerFuturesNewService.getFewMinLine?type=1` |
| 国际期货（hf_） | 新浪 `GlobalFuturesService.getGlobalFuturesMinLine` |
| 全球指数       | Yahoo Finance `v8/finance/chart?interval=5m&range=1d` |

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
输出路径：`build/distributions/stocklite-plugin-1.0.0.zip`

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
    ├── InlineChartPanel.kt         # 内嵌日内走势图面板（JBCefBrowser）
    ├── common/
    │   ├── Formatters.kt           # 数值格式化（自动 2/3/4 位小数）
    │   └── QuoteRenderer.kt        # 表格着色渲染器（红涨绿跌）
    └── dialogs/
        ├── AddStockDialog.kt       # 添加/编辑股票
        ├── AddFundDialog.kt        # 添加/编辑基金
        ├── AddFutureDialog.kt      # 添加/编辑期货
        ├── ManageGroupsDialog.kt   # 分组管理
        └── ChartDialog.kt          # 弹窗式走势图（备用，未使用）
```

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
