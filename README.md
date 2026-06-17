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
| 港股行情（腾讯 API）              | ✅          |
| 美股行情（Yahoo Finance）       | ✅          |
| 搜索添加（股票/基金/期货）            | ✅          |
| 盈亏计算（颜色标注）                | ✅          |
| 分组管理（新建/重命名/删除）           | ✅          |
| 数据持久化（IDE 配置目录）           | ✅          |
| 日内走势图（内嵌面板，点击涨跌幅触发）       | ✅          |
| K 线周期切换（日内/日K/周K/月K）      | ✅          |
| 价格闪烁动画（行情更新时单元格变色）        | ✅          |
| 价格到价提醒（IDE 气泡通知）          | ✅          |
| 右键菜单（编辑/删除/提醒/复制/浏览器）     | ✅          |
| 快速筛选（工具栏搜索框）              | ✅          |
| 列宽记忆（拖动后自动持久化）            | ✅          |
| 刷新间隔可配置（股票/基金/全球指数各自独立）   | ✅          |
| 数据导入/导出（JSON 格式）           | ✅          |
| 网络断线提示                    | ✅          |
| 输入验证（成本价/持仓数量/目标价）        | ✅          |
| 面板隐藏时暂停刷新（生命周期优化）         | ✅          |
| 价格自动小数位（2/3/4 位，兼容 ETF）   | ✅          |
| 中英文界面切换（Settings → StockLite → 界面语言） | ✅          |
| 基金持仓明细（右键 → 持仓明细，含涨跌幅列）       | ✅          |
| AI 深度分析（DeepSeek，多轮对话，5 种专业 Prompt） | ✅          |
| AI 联网搜索（Tavily 工具调用，可配置搜索轮次）     | ✅          |
| AI 深度推理模式（deepseek-reasoner）          | ✅          |
| AI 回复 HTML 富文本渲染（Markdown 格式化）      | ✅          |
| AI 分析实时进度显示                          | ✅          |
| 基金到价提醒去重（相邻刷新不重复弹出）            | ✅          |
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
输出路径：`build/distributions/stocklite-plugin-1.5.0.zip`

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

## 新功能说明（v1.5.0）

### 股指期货席位持仓（全球模块）
全球指数列表底部新增席位持仓行，展示 A 股收盘后各主力机构在股指期货上的多空变化，辅助判断下一交易日方向：

| 字段   | 说明                                    |
|------|-----------------------------------------|
| 主力多  | IF+IH+IC+IM 合计，排行榜前二十多头席位净变化（手）  |
| 主力空  | IF+IH+IC+IM 合计，排行榜前二十空头席位净变化（手）  |
| 中信多  | 中信期货/中信建投合计多头净变化（手）              |
| 中信空  | 中信期货/中信建投合计空头净变化（手）              |

数据来源：东方财富 `datacenter-web.eastmoney.com`（结算后更新，当日无数据时自动回退至最近四个交易日）。

### 基金净值日期修复
- 国内基金在开盘后不再把当日估值时间（如 `09:30:00`）当成净值更新日期显示
- 净值日期始终来自 fundgz `jzrq` 字段或 F10 官方历史净值表，确保与官方涨跌幅对应同一份数据

### QDII 基金数据整合
- 重构为统一双源流程：所有基金先走 fundgz 获取估算和名称，QDII 再额外调用 F10 获取官方净值
- 按净值日期比较，取较新的那份数据，彻底消除净值更新时段数据交替闪烁问题
- QDII 基金不再展示今日估算（境外基金估算不准确，已移除）

## 新功能说明（v1.4.0）

### AI 对话富文本渲染
AI 回复改用 `JTextPane` + HTML 渲染，完整支持 Markdown 格式：

| AI 输出 | 渲染效果 |
|--------|---------|
| `**粗体**` | **粗体** |
| `*斜体*` | *斜体* |
| `` `代码` `` | 灰底等宽字体 |
| `【标题】` | 蓝色加粗，段落标题突出显示 |
| `- 条目` / `1. 条目` | `•` 列表项 |
| 双空行 | 段落间距 |

颜色跟随 IDE 亮色/暗色主题自动适配。

### AI 分析进度显示
等待 AI 回复时，对话区实时显示当前步骤：
- `🤖 AI 正在思考（deepseek-chat）...` — 普通对话
- `🔍 正在搜索：贵州茅台 2026年最新公告` — 联网搜索中
- `💭 AI 正在整合搜索结果...` — 搜索完成，等待最终回答

### AI 功能增强设置（Settings → StockLite → AI 功能增强）

| 选项 | 说明 |
|------|------|
| 注入实时行情数据 | 在 System Prompt 末尾附加当前时间，AI 知道数据是实时的 |
| 联网搜索 | 通过 Tavily API 让 AI 搜索最新新闻/公告，需填写 Tavily Key |
| 最大搜索轮次 | AI 最多调用多少次搜索工具（2~20，默认 8），最后一轮强制输出文字 |
| 深度推理模式 | 自动使用 `deepseek-reasoner` 模型，分析更深入但速度更慢 |
| 最大输出 Token | 控制单次回复长度（200~4096，默认 1500） |

Tavily 免费套餐：每月 1000 次请求，前往 [tavily.com](https://tavily.com) 注册获取 Key（以 `tvly-` 开头）。

## 新功能说明（v1.3.0）

### 基金持仓明细
在任意基金行上右键，选择**持仓明细**，弹出当季股票投资明细对话框：

| 列名       | 说明                                   |
|----------|--------------------------------------|
| 序号       | 持仓排名                                 |
| 股票代码     | 东方财富链接，可点击跳转                         |
| 股票名称     | —                                    |
| 占净值比例   | 该股占基金净资产比例                           |
| 持仓股数（万股） | —                                    |
| 持仓市值（万元） | —                                    |
| 较上期变化   | 与上期季报相比持仓股数变化                        |
| **涨跌幅** | A 股：异步加载实时/收盘涨跌幅（红涨绿跌）；境外股票显示"--" |

数据来源：东方财富 F10 接口（季报），涨跌幅来源：新浪行情 API。

### AI 深度分析
在股票 / 基金 / 期货 / 全球指数行上右键，选择 **AI 深度分析**，打开多轮对话面板：

- **模型选择**：从 DeepSeek API 动态拉取可用模型列表
- **专业 Prompt**：内置 5 种 System Prompt 一键切换
  - 宏观分析：产业政策、宏观经济视角
  - 技术分析：价格走势、形态与指标
  - 基本面分析：财务数据、估值与竞争壁垒
  - 量化分析：统计规律、因子与回测思路
  - 风险分析：风险点识别与应对
- **多轮对话**：保留历史上下文，可连续追问
- **配置入口**：Settings → Tools → StockLite → AI 分析（填写 DeepSeek API Key）

## 新功能说明（v1.2.0）

### 价格闪烁动画
行情数据更新时，价格变化的单元格会短暂闪烁（350ms）：涨价闪红色，跌价闪青色（跟随全局配色方案）。
可在 **Settings → Tools → StockLite → 功能开关** 中关闭。

### 价格到价提醒
在股票/基金右键菜单中选择"设置提醒"，填写目标价和方向（上穿/下穿）。到价时触发 IDE 右下角气泡通知。
每条提醒触发一次后自动失效，避免重复打扰。可在设置中关闭此功能。

### 右键上下文菜单
在任意表格行上右键：
- **股票/基金**：编辑、删除、设置提醒、删除提醒、复制代码、复制名称、在浏览器中打开
- **期货/全球指数**：删除（期货）、复制名称、在浏览器中打开

### K 线周期切换
图表面板顶部新增周期选择栏（日内 / 日K / 周K / 月K）。
- **日内**：当日分钟线，Y 轴显示涨跌幅%（与昨收基准线对比）
- **日K/周K/月K**：历史 K 线，Y 轴显示绝对价格
- A 股使用新浪 K 线 API；港股/美股/全球指数使用 Yahoo Finance

### 港股 / 美股支持
添加股票时可搜索港股代码（5 位数字，如 `00700`）或美股代码（纯大写字母，如 `AAPL`）：
- 港股行情：腾讯 `qt.gtimg.cn` API
- 美股行情：Yahoo Finance v8 chart API
- 美股历史 K 线：Yahoo Finance 日/周/月线

### 数据导入/导出
**Settings → Tools → StockLite → 数据管理**：
- **导出**：将所有股票、基金、期货、分组和设置保存为 JSON 文件
- **导入**：从 JSON 文件恢复数据（追加模式，不清空现有数据）

### 刷新间隔配置
**Settings → Tools → StockLite → 刷新间隔**：
- 股票刷新间隔：3~60 秒（默认 5 秒）
- 基金刷新间隔：10~120 秒（默认 30 秒）
- 全球指数刷新间隔：3~60 秒（默认 5 秒）
修改后立即生效，无需重启。

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
