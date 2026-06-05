# StockLite JetBrains 插件

股票 / 基金 / 期货行情与持仓管理，原生 Kotlin/Swing 实现，适配所有 JetBrains IDE（2024.1+）。

## 功能

| 功能                  | 状态         |
|---------------------|------------|
| 股票持仓管理（CRUD + 分组）   | ✅          |
| 基金持仓管理              | ✅          |
| 期货持仓管理              | ✅          |
| 实时行情（新浪 API，GBK 解码） | ✅          |
| 基金估值（天天基金）          | ✅          |
| 全球指数（Yahoo Finance） | ✅          |
| 搜索添加（股票/基金/期货）      | ✅          |
| 盈亏计算（颜色标注）          | ✅          |
| 分组管理（新建/重命名/删除）     | ✅          |
| 数据持久化（IDE 配置目录）     | ✅          |
| 日内走势图               | ❌（已移除）     |
| 拖拽排序                | ❌（已移除）     |
| 资讯/新闻               | ❌（已排除）     |
| 系统托盘                | ❌（插件架构不支持） |

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
│   └── MarketDataService.kt        # 所有行情/搜索 HTTP 调用
├── util/
│   ├── HttpUtil.kt                 # HTTP 工具（含 GBK 解码）
│   └── MarketTimeUtil.kt           # 市场开市时间判断
└── ui/
    ├── StocklitePanel.kt           # 主面板（标签页）
    ├── StockPanel.kt               # 股票标签页
    ├── FundPanel.kt                # 基金标签页
    ├── FuturePanel.kt              # 期货标签页
    ├── GlobalPanel.kt              # 全球指数标签页
    ├── common/
    │   ├── Formatters.kt           # 数值格式化
    │   └── QuoteRenderer.kt        # 表格着色渲染器（红涨绿跌）
    └── dialogs/
        ├── AddStockDialog.kt       # 添加/编辑股票
        ├── AddFundDialog.kt        # 添加/编辑基金
        ├── AddFutureDialog.kt      # 添加/编辑期货
        └── ManageGroupsDialog.kt   # 分组管理
```

## 数据存储

数据保存在 IDE 配置目录下的 `stocklite.xml`：
- Windows: `%APPDATA%\JetBrains\<IDE>\options\stocklite.xml`
- macOS: `~/Library/Application Support/JetBrains/<IDE>/options/stocklite.xml`
- Linux: `~/.config/JetBrains/<IDE>/options/stocklite.xml`
