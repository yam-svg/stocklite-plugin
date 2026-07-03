package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
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
                    val json = exportToJson()
                    chooser.selectedFile.writeText(json)
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
                    val json = chooser.selectedFile.readText()
                    importFromJson(json)
                    statusLbl.text = L10n.dlgImportSuccess
                } catch (e: Exception) {
                    statusLbl.text = L10n.dlgImportFailed
                }
            }
        }

        btnPanel.add(exportBtn)
        btnPanel.add(importBtn)
        panel.add(btnPanel, BorderLayout.NORTH)
        panel.add(statusLbl, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun exportToJson(): String {
        val state = StockliteState.getInstance()
        val root = JSONObject()

        root.put("stocks", JSONArray().also { arr ->
            state.stocks.forEach { s ->
                arr.put(JSONObject().apply {
                    put("symbol", s.symbol); put("name", s.name); put("alias", s.alias); put("groupId", s.groupId)
                    put("costPrice", s.costPrice); put("quantity", s.quantity)
                    put("sortOrder", s.sortOrder)
                })
            }
        })
        root.put("stockGroups", JSONArray().also { arr ->
            state.stockGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("funds", JSONArray().also { arr ->
            state.funds.forEach { f ->
                arr.put(JSONObject().apply {
                    put("code", f.code); put("name", f.name); put("alias", f.alias); put("groupId", f.groupId)
                    put("costNav", f.costNav); put("shares", f.shares); put("sortOrder", f.sortOrder)
                })
            }
        })
        root.put("fundGroups", JSONArray().also { arr ->
            state.fundGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("futures", JSONArray().also { arr ->
            state.futures.forEach { f ->
                arr.put(JSONObject().apply {
                    put("symbol", f.symbol); put("name", f.name); put("alias", f.alias)
                    put("groupId", f.groupId); put("sortOrder", f.sortOrder)
                })
            }
        })
        root.put("futureGroups", JSONArray().also { arr ->
            state.futureGroups.forEach { g ->
                arr.put(JSONObject().apply { put("id", g.id); put("name", g.name) })
            }
        })
        root.put("globalIndexAliases", JSONObject().apply {
            state.globalIndexAliases.forEach { (symbol, alias) -> put(symbol, alias) }
        })
        root.put("settings", JSONObject().apply {
            put("colorScheme", state.colorScheme)
            put("language", state.language)
        })
        root.put("version", "1.1.0")
        return root.toString(2)
    }

    private fun importFromJson(json: String) {
        val root = JSONObject(json)
        val state = StockliteState.getInstance()

        // stockGroups
        root.optJSONArray("stockGroups")?.let { arr ->
            state.stockGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.stockGroups.add(StockGroupData().apply {
                    id = o.optString("id", java.util.UUID.randomUUID().toString())
                    name = o.optString("name", "")
                })
            }
        }
        // stocks
        root.optJSONArray("stocks")?.let { arr ->
            state.stocks.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.stocks.add(StockData().apply {
                    id = java.util.UUID.randomUUID().toString()
                    symbol = o.optString("symbol", "")
                    name = o.optString("name", "")
                    alias = o.optString("alias", "")
                    groupId = o.optString("groupId", "")
                    costPrice = o.optDouble("costPrice", 0.0)
                    quantity = o.optDouble("quantity", 0.0)
                    sortOrder = o.optInt("sortOrder", i)
                })
            }
        }
        // fundGroups
        root.optJSONArray("fundGroups")?.let { arr ->
            state.fundGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.fundGroups.add(FundGroupData().apply {
                    id = o.optString("id", java.util.UUID.randomUUID().toString())
                    name = o.optString("name", "")
                })
            }
        }
        // funds
        root.optJSONArray("funds")?.let { arr ->
            state.funds.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.funds.add(FundData().apply {
                    id = java.util.UUID.randomUUID().toString()
                    code = o.optString("code", "")
                    name = o.optString("name", "")
                    alias = o.optString("alias", "")
                    groupId = o.optString("groupId", "")
                    costNav = o.optDouble("costNav", 0.0)
                    shares = o.optDouble("shares", 0.0)
                    sortOrder = o.optInt("sortOrder", i)
                })
            }
        }
        // futureGroups
        root.optJSONArray("futureGroups")?.let { arr ->
            state.futureGroups.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.futureGroups.add(FutureGroupData().apply {
                    id = o.optString("id", java.util.UUID.randomUUID().toString())
                    name = o.optString("name", "")
                })
            }
        }
        // futures
        root.optJSONArray("futures")?.let { arr ->
            state.futures.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                state.futures.add(FutureData().apply {
                    id = java.util.UUID.randomUUID().toString()
                    symbol = o.optString("symbol", "")
                    name = o.optString("name", "")
                    alias = o.optString("alias", "")
                    groupId = o.optString("groupId", "")
                    sortOrder = o.optInt("sortOrder", i)
                })
            }
        }
        // globalIndexAliases
        root.optJSONObject("globalIndexAliases")?.let { obj ->
            state.globalIndexAliases.clear()
            obj.keys().forEach { symbol -> state.globalIndexAliases[symbol] = obj.getString(symbol) }
        }
        // settings
        root.optJSONObject("settings")?.let { s ->
            if (s.has("colorScheme")) state.colorScheme = s.getString("colorScheme")
            if (s.has("language")) state.language = s.getString("language")
        }
        // notify all listeners
        state.notifyColumnSettingsChanged()
        state.notifyLanguageChanged()
    }
}
