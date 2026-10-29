# StockLite VSCode 移植开发计划（并行会话版）

> 目标：将 StockLite（JetBrains 插件，Kotlin/Swing，约 1.8 万行）移植为 VSCode 扩展（TypeScript + Webview）。
> 本计划按"一个会话一个任务书"组织，除 S0 外各会话可并行执行，互不冲突。
> 新建会话时只需说：**"按 docs/vscode-port-plan.md 执行会话 SX"**，并在开头声明只改动该任务书"文件所有权"内的文件。

---

## 0. 总体决策

| 项 | 决策 | 理由 |
|---|---|---|
| 新仓库位置 | `D:\Users\huangdingxin\Desktop\electron\stocklite-vscode`（独立 git 仓库） | 与 JetBrains 版解耦，各自发版；数据源坑清单通过本文档同步 |
| 语言/构建 | TypeScript + esbuild 打包 | 官方推荐，单文件输出，启动快 |
| UI 载体 | Webview View（侧边栏）+ Webview Panel（图表/设置/弹窗） | Swing 表格与图表全部重写为 HTML/JS，自由度高 |
| K 线/分时图 | klinecharts（K 线 + 覆盖层画 B/S 点）；分时用 ECharts line 或 klinecharts line 类型 | 覆盖层机制天然适合买卖点标记 |
| 配置持久化 | `context.globalState`（JSON），键名与 JetBrains 版 `StockliteState` 字段对齐 | 为将来导入 JetBrains 配置留门 |
| 命令面板入口 | 所有命令/视图/菜单在 S0 一次性预注册进 `package.json` | **并行防冲突的核心手段**：后续会话不改 package.json |
| MVP 范围 | 状态栏行情 + 自选股表 + K线/分时图 + 涨跌提醒 + 设置 | 覆盖 80% 使用场景；基金/期货/加密/板块等二期 |

## 1. 目录结构与所有权边界（防冲突关键）

```
stocklite-vscode/
├── package.json            # S0 预注册全部 contributes，之后只有 S7 可改
├── esbuild.mjs             # S0
├── src/
│   ├── extension.ts        # S0：只做装配——按 feature 列表逐个 activate，之后不再改
│   ├── shared/             # S0：类型契约与公共设施（冻结，改动走 S7）
│   │   ├── types.ts        #   Quote / KlineBar / TradeRecord / TabDescriptor / Provider 接口
│   │   ├── events.ts       #   模块间事件总线（quotesUpdated / configChanged / alertFired）
│   │   ├── config.ts       #   globalState 读写封装
│   │   ├── http.ts         #   fetch 封装：超时/重试/gbk 解码（新浪是 GBK！）
│   │   └── mockProvider.ts #   假数据 Provider，供 UI 会话在 S1 完成前独立开发
│   ├── data/               # S1 独占：providers + 行情调度
│   ├── ui/watchlist/       # S2 独占：通用行情表 Webview 框架 + 自选股 tab
│   ├── ui/chart/           # S3 独占：图表 Webview（klinecharts 打包进 webview 资产）
│   ├── features/statusbar/ # S4 独占
│   ├── features/alerts/    # S4 独占
│   ├── features/settings/  # S5 独占
│   └── features/tabs/      # S6 独占：基金/期货/全球/加密等 tab 的 descriptor + provider 适配
├── webview/                # 各 ui/* 会话各自的 HTML 资产子目录，互不重叠
└── scripts/                # 各会话自带的 node 自测脚本（xxx.selftest.mts）
```

**铁律**：
1. 每个会话只允许新建/修改自己目录内的文件；需要动 `shared/` 或 `package.json` 时，把需求写进 `docs/contract-changes.md`（各会话可追加自己的一段，不改别人的段），由 S7 统一落实。
2. UI/功能会话一律对着 `shared/types.ts` 的接口和 `mockProvider` 开发，不依赖 S1 的真实 Provider。
3. `extension.ts` 里 feature 注册骨架由 S0 写好（`activateWatchlist/activateChart/activateStatusBar/...` 的空实现放各自目录导出），后到会话只填实现体。

## 2. 契约（S0 产出，所有会话的依赖）

`shared/types.ts` 核心接口（S0 细化，此处定骨架）：

```ts
interface Quote { code: string; name: string; price: number; prevClose: number;
  changePct: number; volume?: number; amount?: number; extra?: Record<string,string|number>; }
interface KlineBar { date: string; open: number; close: number; high: number; low: number; volume?: number; }
interface MinutePoint { time: string; price: number; avgPrice?: number; volume?: number; }
interface QuoteProvider {
  id: string;                       // 'stock' | 'fund' | 'future' | 'global' | 'crypto' ...
  fetchQuotes(codes: string[]): Promise<Quote[]>;          // 必须支持批量
  fetchKline(code: string, period: 'day'|'week'|'month', count: number): Promise<KlineBar[]>;
  fetchMinute(code: string): Promise<MinutePoint[]>;
}
interface TabDescriptor { id: string; title: string; providerId: string;
  columns: ColumnDef[];             // 列定义（含"封单手数""今日盈亏"等扩展列）
  addCommand: string;               // 对应预注册的"添加xx"命令
  optional?: boolean; }             // 可关闭的 tab
```

事件总线（模块间唯一通信方式，避免互相 import 实现类）：
`quotesUpdated(tabId, quotes)` / `configChanged(key)` / `openChart(code, name)` / `alertFired(alert)`。

## 3. 会话任务书

### S0 —— 地基（串行，必须最先完成）✅ 已完成（2026-10-29）
- ✅ 仓库已建于 `D:\Users\huangdingxin\Desktop\electron\stocklite-vscode`（git 已 init 未提交），esbuild + tsconfig + F5 launch/tasks 就绪。
- ✅ `shared/` 全部产出：types / events（事件总线，含 `viewAddRequested`/`refreshRequested` 两个路由事件）/ config（StateKeys+SettingsKeys 注册表）/ http（超时+指数退避重试+GBK）/ mockProvider / placeholder（占位视图与 CSP+nonce HTML 模板）。
- ✅ `package.json` 预注册 8 个 webview view（tab 开关用 `when: config.stocklite.tabs.*` 实现）、20 条命令、view/title 菜单（`+`/刷新按 viewId 广播）、配置项（轮询间隔、红涨绿跌、状态栏、指数双轨）。
- ✅ `extension.ts` 装配 + 6 个 feature 空实现（watchlist/chart/tabs/statusbar/alerts/settings 各自目录，占位命令提示归属会话）。
- ✅ 验收：`npm run compile` 零错误；`npm run selftest` 17/17 通过。待用户 F5 冒烟（侧边栏应见 8 个占位 tab + 状态栏占位项）。
- **契约速查（S1~S6 必读）**：跨模块通信只走 `EventBus`；tab 显隐改配置不改代码；"添加/刷新"按钮经 `viewAddRequested`/`refreshRequested` 事件到达本会话，勿在 package.json 加命令；globalState 键一律用 `StateKeys`。

原任务书：
- 初始化仓库 + esbuild + ESLint/Prettier + F5 调试配置。
- 产出 `shared/` 全部文件（types/events/config/http/mockProvider）。
- `package.json` 预注册：所有视图容器与视图（股票/基金/期货/全球/板块/新股/美股/加密 8 个 webview view + 图表/设置 panel 命令）、全部命令（添加各品种、删除、导入导出、打开设置、打开图表、设置提醒、盈亏日历等 ≈30 个）、状态栏项、右键菜单、`contributes.configuration` 基础项（轮询间隔、涨跌颜色、红涨绿跌）。
- `extension.ts` 装配骨架 + 各 feature 空实现（能 F5 起来看到空 tab 和状态栏占位）。
- 验收：`npm run compile` 通过；F5 出开发宿主，8 个空 tab、命令面板可见全部命令。

### S1 —— 数据源层（并行，纯 TS 无 UI）
- 从 Kotlin `MarketDataService/ChartDataService/HttpUtil` 移植全部 provider：
  - 股票批量（新浪 `hq.sinajs.cn` 批量 + 腾讯 `qt.gtimg.cn` 兜底）、盘口/竞价字段（封单 fields[10]/[20] 单位是**股**；腾讯 47/48=涨跌停价）
  - K 线：腾讯/东财日周月；腾讯港股 K 线字段序 `[date,open,close,high,low]`；恒指科技走腾讯 `hkHSTECH`（Yahoo 无此数据）
  - 全球指数（Yahoo + 37 指数分组 + EGX30 类稀疏源"前收兜底=现价"）、基金（腾讯 jj 批量 + 东财 F10 两级）、期货/外汇（新浪 hf_）、加密三源降级 Bitget→Gate→binance.vision、板块（东财 push2，pz=100 分页+重试）、新股、美股（新浪 gb_ 批量，f21/24/25/26 映射）
  - 已知坑必须照搬：**深证分钟线成交额减半→东财 trends2 ndays=2**；东财随机断连是常态；新浪 GBK 编码。
- 行情调度器：交易时段感知轮询（MarketTimeUtil 移植：A股/港/美时段+休市降频）、请求合并、失败降级链。
- 自测：`scripts/data.selftest.mts` 用 node 直跑，逐 provider 断言字段非空/量级合理，覆盖休市日降级路径。
- 验收：自测全绿；实现 `shared` 的 Provider 接口并通过契约一致性脚本。

### S2 —— 通用行情表框架 + 自选股 tab（并行，对 mock 开发）
- 通用 Webview 表格：列渲染（涨跌红绿色阶、扩展列插槽）、表头三态排序、行拖拽排序、分组 tab 条、右键菜单（删除/设提醒/看交易记录/打开图表）、工具栏（添加/批量导入/导入导出/管理分组）。
- 自选股 tab 接通用框架：持仓列（成本/数量/今日盈亏/盈亏）、交易记录录入（QuickInput 流程）、批量导入（粘贴文本解析，移植 `StockTextParser`）。
- 添加股票走 `QuickPick`（输入代码/拼音首字母？→ 先做代码+名称搜索，走 S1 搜索接口，未完成前用 mock）。
- 自测：`npm run compile` + 手动 F5 步骤清单写进 PR 描述。

### S3 —— 图表 Webview（并行，对 mock 开发）✅ 已完成（2026-10-29）
- ✅ 图表 Panel（klinecharts 9.8，UMD 打入 `webview/chart/assets/` 本地资产，CSP nonce 无远程脚本）：日/周/月 K + VOL 副图 ↔ 当日分时（面积线昨收定色 + 均价线指标 + 昨收虚线基准），周期切换/自定义 tooltip/明暗主题与红涨绿跌即时跟随。
- ✅ B/S 标记移植 `TradeMarkerUtil`（K 线顺延/聚合/排序 + 日内反查容差 max(1分, 0.1%)）；"400ms 结算兜底"在 TS 侧不需要——宿主本地即有交易记录，标记随数据同推、`tradesChanged` 即时重算重推，另有请求序号防竞态。
- ✅ 盈亏日历独立 Panel：`pnl.ts` 移植 `PnlChartService`（加权成本/手动持仓合成/组合聚合），webview 内 SVG 走势折线 + 月历热图 + 当日各股明细，全部纯前端渲染。
- ✅ 验收：S3 范围 tsc 零错误（`scripts/tsconfig.chart.json`）；`node scripts/chart.selftest.mjs` 31/31；S0 契约 selftest 17/17 不回归。交付细节与 F5 步骤见仓库根 `S3-DELIVERY.md`。
- **移交 S7**：真实数据注入点 `src/ui/chart/dataSource.ts`（setChartDataProvider）；klinecharts 入 devDependencies（现为 --no-save + 资产入库）；详见 contract-changes.md S3 段。

### S4 —— 状态栏 + 提醒引擎（并行）✅ 已完成（2026-10-29）
- ✅ 状态栏轮播（codes 留空自动取自选股+已选全球指数前 8）+ charts.red/green 着色跟随红涨绿跌 + Markdown hover 明细 + 点击 QuickPick 菜单（看图表/设提醒/暂停/固定/管理列表/刷新/设置）。
- ✅ 提醒引擎：`features/alerts/engine.ts` 纯逻辑（无 vscode，可 node 直测）——到价/涨跌幅边沿触发+滞回带+当日重启抑制；封单锐减逐常量对齐 Kotlin（滑窗/断崖/竞价排除/当日去重）；大盘宽度异动为新增启发式（涨跌差温和区→极端区突变），轮询分档照搬 BreadthWatcherService。双通道喂数据：quotesUpdated 被动 + 15s 起独立后台轮询，共用边沿状态防双弹。
- ✅ 提醒管理 QuickPick 流（新建/启停/改阈值/删除 + 封单/宽度两个系统规则开关），系统规则以扩展 type 与到价提醒同存 `StateKeys.alerts`。selftest 57/57（`scripts/alerts-statusbar.selftest.ts`）；S4 范围 tsc 零错误。
- **S7 切换点**：`features/{alerts,statusbar}/providerRef.ts`（globalThis Symbol.for 过渡槽，附议 S2 registry 提案共三处）；`alertFired` 广播的 `asContract()` 待 AlertType 正式化后删；S1 请约定 `Quote.extra.sealLots` = 带符号封单手数（详见 contract-changes.md S4 段）。

原任务书：
- 状态栏：轮播自选/指数行情（可配置列表与间隔），涨跌着色（跟随红涨绿跌设置），hover 明细，点击弹 QuickPick 操作菜单。
- 提醒引擎（AlertManager/StockAlertWatcher 移植）：价格上/下破、涨跌幅阈值、封单锐减/断崖特判、大盘宽度异动（BreadthWatcher）；VSCode notification + 状态栏闪烁（error/warning 图标态）。
- 提醒设置 UI 用 QuickPick/InputBox 流程实现（SetAlertDialog 的等价物），不依赖 S5。

### S5 —— 设置页 + 周边（并行）
- Webview 设置页：轮询间隔、涨跌配色、状态栏内容、各 tab 开关（默认：股票/基金/期货/全球）、全球指数选择（双轨可见性：enabledOptionalIndexes/hiddenGlobalIndexes 单入口，弹窗双 tab 交互照搬）、导入/导出配置（与 JetBrains 版 JSON 键对齐，支持迁移导入）、打赏弹窗（本地图片）、意见反馈（fanshu.xyz 接口，code==20000 为成功）、首启引导（判据=globalState 是否首次写入，勿用"数据为空"判断——播种会写数据）。

### S6 —— 其余 tab 批量接入（并行，依赖 S2 的通用框架完成，否则先做 provider 适配层）
- 为基金（净值/持仓弹窗）、期货（内置 hf_NQ/hf_OIL 播种）、全球指数（37 指数+显示过滤+双轨控制）、加密（三源+网络状态徽章）、板块、新股、美股各写 `TabDescriptor` + 列定义 + provider 适配 + 各自的添加对话框（QuickPick）。
- 每个 tab 一个子目录，内部自包含。

### S7 —— 集成 + 发布（串行，最后）
- 落实 `contract-changes.md`；跨模块联调（真实 provider 全链路、事件总线、命令全量冒烟清单）。
- README（英文简短开场+中文详细介绍主体，沿用 Marketplace 定稿叙事）、LICENSE、图标、`vsce package/publish`、Open VSX 发布（Cursor/Windsurf 覆盖）、CHANGELOG。
- 版本策略：VSCode 版从 1.0.0 起独立版本号。

## 4. 并行时序

```
S0（地基，1 个会话）
 └─► S1 ∥ S2 ∥ S3 ∥ S4 ∥ S5      （S0 合入后同时开 5 个会话）
      └─► S6（等 S2 框架稳定；其 provider 适配可提前并入 S1 尾巴）
           └─► S7 集成发布
```
MVP（可先发一版攒安装量）= S0+S1(仅股票/全球)+S2+S3+S4+S5 精简版+S7；基金/期货/加密/板块在 1.1 跟进。

## 5. 每个并行会话的统一守则（写进各会话开场）

1. 只做本任务书范围内的事，只碰"独占目录"；契约不够用记入 `docs/contract-changes.md` 追加段，不自改 `shared/`。
2. 不执行 git commit/push（沿用 JetBrains 版惯例），改完留在工作区。
3. 交付标准：`npm run compile` 零错误 + 自带 selftest 通过 + 在 PR 描述里给出 F5 手动验收步骤。
4. 数据接口行为存疑时，以本仓库 Kotlin 源码为准（列出对应 .kt 文件路径），不要臆测字段含义；踩到新坑更新本文档 §3-S1 的坑清单。

## 6. 进度看板（集成终检 2026-10-29）

代码目录 `stocklite-vscode/`（git init 已建，全部改动留在工作区，提交归用户）。终检结果：`npm run compile` 零错误；六组自测全绿。

| 会话 | 范围 | 状态 | 自测 |
|---|---|---|---|
| S0 地基 | shared/ + extension.ts + 脚手架 | ✅ 完成 | 17/17 |
| S1 数据源 | src/data 16 文件（8 provider + 调度器） | ✅ 完成 | 离线 116/116，在线 139/139（`STOCKLITE_ONLINE=1 npm run selftest:data`） |
| S2 自选股 | src/ui/watchlist | ✅ 完成 | 45/45 |
| S3 图表 | 图表 + 盈亏日历（klinecharts） | ✅ 完成 | 31/31 |
| S4 提醒 | 状态栏轮播 + 提醒引擎（封单/宽度/到价） | ✅ 完成 | 57/57 |
| S5 设置 | 设置页/导入导出/打赏/反馈/引导 | ✅ 完成 | 25/25 |
| **S6 其余 Tab** | **src/features/tabs 基金/期货/全球/加密/板块/新股/美股** | ❌ **未实施**（仍是 S0 占位，`tabs/index.ts` 为 registerPlaceholderView×7） | — |
| S7 集成发布 | contract-changes 落实 + 联调 + 打包 | ⏳ 待 S6 | — |

### 终检修复记录（S1 板块在线自测连败 → push2 限流）
- 根因：东财 push2 边缘节点按"新建 TLS 连接速率/IP"限流，RST 概率随边缘 IP 与时间窗漂移（分钟级互换）；UA、ALPN、连接复用均非决定因素，高频探测本身会加剧限流。
- 对策（已落地）：`src/data/util.ts` 新增 `emGetText`（node:https + 浏览器 UA，永不 reject）；`sectors.ts` 每页 4 镜像域名轮换 ×2 轮、递增退避 1.2s→8s 跨限流窗口，中途断连返回已取部分由 60s 轮询补全；`breadth.ts` 三处 push2 调用同改；自测板块项整项重试 3 次、间隔 15s。

### S7 待办
1. 落实 `docs/contract-changes.md` 两条 S2 申请：①shared 增加 provider registry（S2 已落过渡版 `src/ui/watchlist/providers.ts`，需上移）；②导入导出纳入 globalState 键 `watchlistDefaultsSeeded`。
2. 补做 S6 后：真实 provider 全链路联调、F5 冒烟清单、README/LICENSE/图标、`vsce package` + Open VSX 发布（1.0.0 起独立版本号）。
