package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.state.*
import com.stocklite.plugin.util.L10n
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.*

class ImportExportDialog : DialogWrapper(true) {

    init {
        title = L10n.dlgExportTitle
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val exportBtn = JButton(L10n.btnExportData)
        val importBtn = JButton(L10n.btnImportData)
        val statusLbl = JLabel("")

        exportBtn.addActionListener {
            val chooser = JFileChooser()
            chooser.selectedFile = File("stocklite_backup.json")
            if (chooser.showSaveDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
                try {
                    chooser.selectedFile.writeText(exportToJson())
                    statusLbl.text = "${L10n.dlgExportSuccess} ${chooser.selectedFile.absolutePath}"
                } catch (e: Exception) {
                    statusLbl.text = "Error: ${e.message}"
                }
            }
        }

        importBtn.addActionListener {
            val chooser = JFileChooser()
            if (chooser.showOpenDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
                try {
                    importFromJson(chooser.selectedFile.readText())
                    statusLbl.text = L10n.dlgImportSuccess
                } catch (e: Exception) {
                    statusLbl.text = L10n.dlgImportFailed
                }
            }
        }

        btnPanel.add(exportBtn); btnPanel.add(importBtn)
        panel.add(btnPanel, BorderLayout.NORTH)
        panel.add(statusLbl, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    // ── 导出 ───────────────────────────────────────────────────────────────

    private fun exportToJson(): String {
        val state = StockliteState.getInstance()
        val root  = JSONObject()

        root.put("version", "1.2.0")

        // ── 股票 ──
        root.put("stockGroups", JSONArray().also { arr ->
            state.stockGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("stocks", JSONArray().also { arr ->
            state.stocks.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)   // 保留原始 ID，交易记录的 stockId 引用依赖于此
                    put("symbol", s.symbol); put("name", s.name); put("alias", s.alias)
                    put("groupId", s.groupId); put("costPrice", s.costPrice)
                    put("quantity", s.quantity); put("sortOrder", s.sortOrder)
                    put("createdAt", s.createdAt); put("updatedAt", s.updatedAt)
                    put("snapshotQty", s.snapshotQty)
                    put("snapshotCostPrice", s.snapshotCostPrice)
                })
            }
        })
        root.put("tradeRecords", JSONArray().also { arr ->
            state.tradeRecords.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id); put("stockId", r.stockId)
                    put("symbol", r.symbol); put("stockName", r.stockName)
                    put("tradeType", r.tradeType); put("price", r.price)
                    put("quantity", r.quantity); put("note", r.note)
                    put("tradeAt", r.tradeAt); put("createdAt", r.createdAt)
                })
            }
        })

        // ── 基金 ──
        root.put("fundGroups", JSONArray().also { arr ->
            state.fundGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("funds", JSONArray().also { arr ->
            state.funds.forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id)
                    put("code", f.code); put("name", f.name); put("alias", f.alias)
                    put("groupId", f.groupId); put("costNav", f.costNav)
                    put("shares", f.shares); put("sortOrder", f.sortOrder)
                    put("createdAt", f.createdAt)
                })
            }
        })

        // ── 期货 ──
        root.put("futureGroups", JSONArray().also { arr ->
            state.futureGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("futures", JSONArray().also { arr ->
            state.futures.forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id)
                    put("symbol", f.symbol); put("name", f.name); put("alias", f.alias)
                    put("groupId", f.groupId); put("sortOrder", f.sortOrder)
                    put("createdAt", f.createdAt)
                })
            }
        })

        // ── 价格提醒 ──
        root.put("priceAlerts", JSONArray().also { arr ->
            state.priceAlerts.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id); put("symbol", a.symbol); put("name", a.name)
                    put("targetPrice", a.targetPrice); put("alertType", a.alertType)
                    put("enabled", a.enabled); put("triggered", a.triggered)
                    put("createdAt", a.createdAt)
                })
            }
        })

        // ── 全球指数 ──
        root.put("globalIndexOrder", JSONArray().also { arr ->
            state.globalIndexOrder.forEach { arr.put(it) }
        })
        root.put("globalIndexAliases", JSONObject().apply {
            state.globalIndexAliases.forEach { (k, v) -> put(k, v) }
        })

        // ── 设置（不含 API Key 等敏感字段）──
        root.put("settings", JSONObject().apply {
            put("colorScheme",            state.colorScheme)
            put("language",               state.language)
            put("refreshIntervalStock",   state.refreshIntervalStock)
            put("refreshIntervalFund",    state.refreshIntervalFund)
            put("refreshIntervalGlobal",  state.refreshIntervalGlobal)
            put("enablePriceAlerts",      state.enablePriceAlerts)
            put("enableFundNavAlert",     state.enableFundNavAlert)
            put("enablePortfolioStatusBar", state.enablePortfolioStatusBar)
            put("enableUsMarketPanel",    state.enableUsMarketPanel)
            put("stockVisibleColumns",    JSONArray().also { arr ->
                state.stockVisibleColumns.forEach { arr.put(it) }
            })
            put("fundVisibleColumns",     JSONArray().also { arr ->
                state.fundVisibleColumns.forEach { arr.put(it) }
            })
        })

        return root.toString(2)
    }

    // ── 导入 ───────────────────────────────────────────────────────────────

    private fun importFromJson(json: String) {
        val root  = JSONObject(json)
        val state = StockliteState.getInstance()

        fun newUuid() = java.util.UUID.randomUUID().toString()

        // ── 股票分组 ──
        root.optJSONArray("stockGroups")?.let { arr ->
            state.stockGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.stockGroups.add(StockGroupData().apply {
                    id = o.optString("id", newUuid()); name = o.optString("name", "")
                })
            }
        }

        // ── 股票（保留原始 id，交易记录的 stockId 依赖此 id）──
        root.optJSONArray("stocks")?.let { arr ->
            state.stocks.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.stocks.add(StockData().apply {
                    id               = o.optString("id", newUuid())
                    symbol           = o.optString("symbol", "")
                    name             = o.optString("name", "")
                    alias            = o.optString("alias", "")
                    groupId          = o.optString("groupId", "")
                    costPrice        = o.optDouble("costPrice", 0.0)
                    quantity         = o.optDouble("quantity", 0.0)
                    sortOrder        = o.optInt("sortOrder", i)
                    createdAt        = o.optLong("createdAt", 0L)
                    updatedAt        = o.optLong("updatedAt", 0L)
                    snapshotQty      = o.optDouble("snapshotQty", -1.0)
                    snapshotCostPrice = o.optDouble("snapshotCostPrice", 0.0)
                })
            }
        }

        // ── 交易记录 ──
        root.optJSONArray("tradeRecords")?.let { arr ->
            state.tradeRecords.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.tradeRecords.add(TradeRecordData().apply {
                    id        = o.optString("id", newUuid())
                    stockId   = o.optString("stockId", "")
                    symbol    = o.optString("symbol", "")
                    stockName = o.optString("stockName", "")
                    tradeType = o.optString("tradeType", "BUY")
                    price     = o.optDouble("price", 0.0)
                    quantity  = o.optDouble("quantity", 0.0)
                    note      = o.optString("note", "")
                    tradeAt   = o.optLong("tradeAt", 0L)
                    createdAt = o.optLong("createdAt", 0L)
                })
            }
        }

        // ── 基金分组 ──
        root.optJSONArray("fundGroups")?.let { arr ->
            state.fundGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.fundGroups.add(FundGroupData().apply {
                    id = o.optString("id", newUuid()); name = o.optString("name", "")
                })
            }
        }

        // ── 基金 ──
        root.optJSONArray("funds")?.let { arr ->
            state.funds.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.funds.add(FundData().apply {
                    id        = o.optString("id", newUuid())
                    code      = o.optString("code", "")
                    name      = o.optString("name", "")
                    alias     = o.optString("alias", "")
                    groupId   = o.optString("groupId", "")
                    costNav   = o.optDouble("costNav", 0.0)
                    shares    = o.optDouble("shares", 0.0)
                    sortOrder = o.optInt("sortOrder", i)
                    createdAt = o.optLong("createdAt", 0L)
                })
            }
        }

        // ── 期货分组 ──
        root.optJSONArray("futureGroups")?.let { arr ->
            state.futureGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.futureGroups.add(FutureGroupData().apply {
                    id = o.optString("id", newUuid()); name = o.optString("name", "")
                })
            }
        }

        // ── 期货 ──
        root.optJSONArray("futures")?.let { arr ->
            state.futures.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.futures.add(FutureData().apply {
                    id        = o.optString("id", newUuid())
                    symbol    = o.optString("symbol", "")
                    name      = o.optString("name", "")
                    alias     = o.optString("alias", "")
                    groupId   = o.optString("groupId", "")
                    sortOrder = o.optInt("sortOrder", i)
                    createdAt = o.optLong("createdAt", 0L)
                })
            }
        }

        // ── 价格提醒 ──
        root.optJSONArray("priceAlerts")?.let { arr ->
            state.priceAlerts.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.priceAlerts.add(PriceAlertData().apply {
                    id          = o.optString("id", newUuid())
                    symbol      = o.optString("symbol", "")
                    name        = o.optString("name", "")
                    targetPrice = o.optDouble("targetPrice", 0.0)
                    alertType   = o.optString("alertType", "ABOVE")
                    enabled     = o.optBoolean("enabled", true)
                    triggered   = o.optBoolean("triggered", false)
                    createdAt   = o.optLong("createdAt", 0L)
                })
            }
        }

        // ── 全球指数 ──
        root.optJSONArray("globalIndexOrder")?.let { arr ->
            state.globalIndexOrder.clear()
            for (i in 0 until arr.length()) state.globalIndexOrder.add(arr.getString(i))
        }
        root.optJSONObject("globalIndexAliases")?.let { obj ->
            state.globalIndexAliases.clear()
            obj.keys().forEach { k -> state.globalIndexAliases[k] = obj.getString(k) }
        }

        // ── 设置 ──
        root.optJSONObject("settings")?.let { s ->
            if (s.has("colorScheme"))              state.colorScheme              = s.getString("colorScheme")
            if (s.has("language"))                 state.language                 = s.getString("language")
            if (s.has("refreshIntervalStock"))     state.refreshIntervalStock     = s.getInt("refreshIntervalStock")
            if (s.has("refreshIntervalFund"))      state.refreshIntervalFund      = s.getInt("refreshIntervalFund")
            if (s.has("refreshIntervalGlobal"))    state.refreshIntervalGlobal    = s.getInt("refreshIntervalGlobal")
            if (s.has("enablePriceAlerts"))        state.enablePriceAlerts        = s.getBoolean("enablePriceAlerts")
            if (s.has("enableFundNavAlert"))       state.enableFundNavAlert       = s.getBoolean("enableFundNavAlert")
            if (s.has("enablePortfolioStatusBar")) state.enablePortfolioStatusBar = s.getBoolean("enablePortfolioStatusBar")
            if (s.has("enableUsMarketPanel"))      state.enableUsMarketPanel      = s.getBoolean("enableUsMarketPanel")
            s.optJSONArray("stockVisibleColumns")?.let { arr ->
                state.stockVisibleColumns = (0 until arr.length()).map { arr.getString(it) }.toMutableList() as ArrayList
            }
            s.optJSONArray("fundVisibleColumns")?.let { arr ->
                state.fundVisibleColumns = (0 until arr.length()).map { arr.getString(it) }.toMutableList() as ArrayList
            }
        }

        state.notifyColumnSettingsChanged()
        state.notifyLanguageChanged()
        state.notifyRefreshIntervalChanged()
        state.notifyFeatureToggleChanged()
    }
}
