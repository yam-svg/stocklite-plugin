# StockLite JetBrains 插件

股票 / 基金 / 期货行情与持仓管理，另含 18 个全球指数看板，原生 Kotlin/Swing 实现，适配所有 JetBrains IDE（2024.1+）。

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
| K 线周期切换（日内/日K/周K/月K，日K起为真实蜡烛图） | ✅          |
| 全球指数市场快捷筛选（全部/CN/HK/US/其他）  | ✅          |
| 全球指数数据延迟提示（区分实时/延迟来源）      | ✅          |
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
| A股大盘概览（涨跌家数/涨跌停/成交额/大中小盘/领涨领跌板块/主力资金） | ✅          |
| 股指期货多空持仓摘要（中信+主力机构，四大品种全合约月份汇总）    | ✅          |
| 盘后多空信号汇总（启发式评分 + 可选 AI 二次解读）        | ✅          |
| 改名功能（股票/基金/期货/全球指数右键自定义别名）         | ✅          |
| 大盘概览数据收盘后/重启后保留（磁盘持久化兜底）          | ✅          |
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
输出路径：`build/distributions/stocklite-plugin-1.7.0.zip`

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

## 新功能说明（v1.7.0）

### A股大盘概览
全球面板底部新增大盘概览区域：涨跌家数（沪深 + 北交所，此前遗漏北交所约200~300家，已修复）、涨停/跌停家数、两市成交额（中证全指成交额代理）、大/中/小盘代理指数（沪深300/中证500/中证1000涨跌幅）、领涨/领跌板块 TOP6、主力资金净流入（沪深两市合计）。已排除北向资金（2024年8月起官方已停止披露，不再具备参考意义）。

### 股指期货多空持仓摘要
汇总中信期货（代客）及全市场前20名主力机构在 IH/IF/IC/IM 四大股指期货品种**全部合约月份**（而非仅主力合约）的净持仓变化，展示为当日净操作手数（如"+3551手"），悬浮查看各品种明细。已与第三方数据比对验证聚合口径一致。

### 盘后多空信号汇总
综合 A50/纳指期货、股指期货多空操作、主力资金流向、大盘宽度，给出 -100~100 的启发式倾向评分（非严格预测），悬浮显示每项因子的具体依据。仅在 A 股收盘（15:00 Asia/Shanghai）后生成，收盘前显示"待收盘"；股指期货因子会校验数据是否为当日出炉，不是当日数据时自动排除该因子，避免用旧数据误判下一交易日走势。股指期货因子区分方向性加仓（IF/IH，权重更高）与偏对冲性质的量化加仓（IC/IM，权重下调）；配置 DeepSeek API Key 后，额外提供 AI 结合同一批数据的二次解读。

### 改名功能
股票、基金、期货、全球指数模块均支持右键"修改名称"，设置自定义显示别名，不影响底层代码/数据，导入导出 JSON 备份同步包含别名。

### 数据保留与稳定性修复
- 大盘概览各子项（涨跌家数/两市成交额/主力资金等）新增磁盘持久化快照兜底，收盘后或插件重启后会继续显示最后一次成功获取的数据，而不是立即变成"--"，超过合理有效期（3天）仍无新数据才隐藏。
- 修复涨跌停家数一直获取不到的问题：东方财富接口的 `date` 参数此前为空导致返回 `rc:102` 无数据，现已显式传入当日日期。
- 排查并修复切换到全球面板后持续大量消耗网络的问题：仅 Yahoo 提供数据的指数（VIX/富时/DAX/CAC40/台湾加权/印度SENSEX等）此前每次刷新面板都会发起未缓存的请求，现新增 60 秒本地缓存。

## 新功能说明（v1.6.0）

### 全球指数新增 7 只
科创50、VIX 恐慌指数、英国富时100、德国 DAX、法国 CAC40、台湾加权指数、印度 SENSEX。新增前均实测确认 Yahoo/新浪确实返回真实行情数据，避免引入无数据或数据源指向错误标的的指数（如新浪 `gb_dax` 实际指向一只不相关的美股 ETF，已排除）。全球指数从 11 个增至 18 个。

### K 线图全面升级为真实蜡烛图
- 股票、期货、全球指数模块的**日K/周K/月K**均改为开高低收蜡烛图（此前为折线/面积图），**日内走势保持分时折线图不变**
- 蜡烛图固定"涨红跌绿"配色，不受 Settings 里涨跌配色方案影响
- 悬浮提示新增相对上一根K线收盘价的涨跌幅显示
- 只在数据源确实提供真实 OHLC 时才画蜡烛图，否则保留折线图，不伪造数据

### 国内期货新增日K线历史数据
此前国内/国际期货只支持日内分钟线，日K/周K/月K 切换无数据。现接入新浪 `InnerFuturesNewService.getDailyKLine` / `GlobalFuturesService.getGlobalFuturesDailyKLine`，周K/月K 由日线数据本地聚合得出。

### 修复韩国综合指数涨跌幅计算错误
Yahoo 分钟线数据在实际收盘（15:30 KST）前约 30 分钟就已截断，导致取"最后一个数据点"当前收盘价算出的涨跌幅偏差超过 1 个百分点（如 -3.10% vs 实际 -2.04%）。现校验数据点时间是否接近该市场官方收盘时间，不通过时自动回退至 Yahoo 官方 `previousClose`/`chartPreviousClose` 字段。

### 修复全球指数开盘/休市判断
原用于识别节假日的 Yahoo `v7/finance/quote` 市场状态接口已被 Yahoo 加上鉴权、返回 HTTP 401，此前节假日期间会被误判为"交易中"。现基于已在用、无需鉴权的 Yahoo K 线接口（`v8/finance/chart`）自带的 `currentTradingPeriod` 字段判断当日是否为真实交易日，按市场缓存 30 分钟，不增加额外请求负担。

### 全球模块数据延迟提示
沪深、港股行情为实时（新浪/腾讯直连），其余指数（含美股道琼斯/纳指/标普500）约有 15 分钟延迟（Yahoo/新浪海外行情免费接口限制）。说明见表格上方常驻提示条，AI 深度分析上下文中延迟指数也会标注。

### 全球模块市场快捷筛选
顶部新增 5 个筛选标签：**全部 / CN / HK / US / 其他**，点击可快速按市场缩小显示范围，与搜索框文字筛选可叠加使用。

## 新功能说明（v1.5.3）

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
