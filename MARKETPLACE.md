# 上架 JetBrains Marketplace 操作清单

## 一、打包

```bash
./gradlew buildPlugin
```

产物：`build/distributions/stocklite-plugin-1.0.0.zip`

---

## 二、签名（上架必须）

### 2.1 生成证书（只需做一次）

```bash
# 生成私钥
openssl genrsa -out private.pem 4096

# 生成自签名证书（有效期 10 年）
openssl req -x509 -key private.pem -out certificate.crt -days 3650 \
  -subj "/CN=超大只番薯/O=StockLite/C=CN"

# 导出 chain（单证书情况下 chain = certificate 本身）
cat certificate.crt > chain.crt
```

### 2.2 设置环境变量

**Windows PowerShell：**

```powershell
$env:PRIVATE_KEY        = [System.IO.File]::ReadAllText("private.pem")
$env:CERTIFICATE_CHAIN  = [System.IO.File]::ReadAllText("chain.crt")
$env:PRIVATE_KEY_PASSWORD = ""   # 如果私钥没有密码则留空
```

### 2.3 执行签名 + 打包

```bash
./gradlew signPlugin
```

签名后产物在：`build/distributions/stocklite-plugin-1.0.0-signed.zip`

---

## 三、上传到 Marketplace

1. 打开 [plugins.jetbrains.com](https://plugins.jetbrains.com)，登录 JetBrains 账号
2. 点右上角头像 → **Upload Plugin**
3. 填写表单：

| 字段          | 填写内容                                |
|-------------|-------------------------------------|
| Plugin File | `stocklite-plugin-1.0.0-signed.zip` |
| Plugin Name | `StockLite`                         |
| Category    | `Tools Integration`                 |
| License     | `MIT`（或你选择的协议）                      |
| Tags        | `stock, finance, market, 股票, 行情`    |

4. 点 **Submit** → 等待人工审核（1–3 个工作日）

---

## 四、需要准备的截图（审核加分项）

建议截 4 张图：

| 图 | 内容                   |
|---|----------------------|
| 1 | 股票面板，展示持仓列表 + 实时涨跌颜色 |
| 2 | 基金面板，展示估值 + 盈亏       |
| 3 | 全球指数面板               |
| 4 | 添加股票对话框（搜索界面）        |

**截图规格：** 宽度至少 1280px，PNG 格式

**截图方法：**

1. 运行 `./gradlew runIde` 启动沙箱 IDE
2. 添加几只股票/基金测试数据
3. 截图保存

---

## 五、Marketplace 页面描述（直接复制粘贴）

已写入 `plugin.xml` 的 `<description>` 字段，会自动显示。

---

## 六、后续版本更新流程

1. 修改 `build.gradle.kts` 中的 `version`
2. 在 `plugin.xml` 的 `<change-notes>` 追加新版本说明
3. 重新签名打包：`./gradlew signPlugin`
4. 在 Marketplace 插件页面 → **Upload Update**

---

## 七、审核常见拒绝原因

| 问题            | 对策                       |
|---------------|--------------------------|
| 插件未签名         | 按第二节完成签名                 |
| 描述只有中文        | `plugin.xml` 已加入英文说明 ✅   |
| `vendor` 邮箱无效 | 已填 `1436393509@qq.com` ✅ |
| 截图为空          | 按第四节准备截图                 |
| API 访问违规      | 本插件仅请求公开行情 API，无风险 ✅     |
