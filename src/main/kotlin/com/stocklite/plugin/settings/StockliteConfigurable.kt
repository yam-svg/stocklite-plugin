package com.stocklite.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.dialogs.ImportExportDialog
import com.stocklite.plugin.util.L10n
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class StockliteConfigurable : Configurable {

    // 语言
    private val langRadios = linkedMapOf(
        "ZH" to JRadioButton(L10n.settingsLangZh),
        "EN" to JRadioButton(L10n.settingsLangEn)
    )
    private val langGroup = ButtonGroup().also { g -> langRadios.values.forEach { g.add(it) } }

    // 颜色方案
    private val colorRadios = linkedMapOf(
        "RED_UP"   to JRadioButton(L10n.settingsRedUp),
        "RED_DOWN" to JRadioButton(L10n.settingsRedDown),
        "NONE"     to JRadioButton(L10n.settingsNoColor)
    )
    private val colorGroup = ButtonGroup().also { g -> colorRadios.values.forEach { g.add(it) } }

    // 刷新间隔 spinners
    private val stockIntervalSpinner  = JSpinner(SpinnerNumberModel(5, 3, 60, 1))
    private val fundIntervalSpinner   = JSpinner(SpinnerNumberModel(30, 10, 120, 5))
    private val globalIntervalSpinner = JSpinner(SpinnerNumberModel(5, 3, 60, 1))

    // 功能开关
    private val alertsCheckBox       = JBCheckBox(L10n.settingsPriceAlerts)
    private val fundNavAlertCheckBox = JBCheckBox(L10n.settingsFundNavAlert)

    // AI 分析
    private val apiKeyField    = JPasswordField(30)
    private val modelCombo     = JComboBox<String>()
    private val fetchModelsBtn = JButton("↻").apply { toolTipText = "从 DeepSeek 获取可用模型列表" }
    private val balanceLabel   = JLabel("").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        foreground = java.awt.Color.GRAY
    }
    private val queryBalanceBtn = JButton("查询余额")

    // AI 功能增强
    private val aiInjectDataCheckBox = JBCheckBox(
        "<html>注入实时行情数据<br><font color='gray' size='2'>将当前价格、涨跌幅等发送给 AI，提升分析准确性</font></html>"
    )
    private val aiWebSearchCheckBox = JBCheckBox(
        "<html>联网搜索（需要 Tavily API Key）<br><font color='gray' size='2'>让 AI 搜索相关新闻、公告、财报等最新信息</font></html>"
    )
    private val tavilyKeyField       = JPasswordField(25)
    private val webSearchRoundsSpinner = JSpinner(SpinnerNumberModel(8, 2, 20, 1))
    private val tavilyLinkLabel = JLabel(
        "<html><a href='https://tavily.com'>免费获取 Tavily Key</a></html>"
    ).apply {
        cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                try { java.awt.Desktop.getDesktop().browse(java.net.URI("https://tavily.com")) }
                catch (_: Exception) {}
            }
        })
    }
    private val aiDeepReasonCheckBox = JBCheckBox(
        "<html>深度推理模式（自动使用 deepseek-reasoner）<br><font color='gray' size='2'>分析更深入，但速度更慢、消耗 Token 更多</font></html>"
    )
    private val aiMaxTokensSpinner = JSpinner(SpinnerNumberModel(1500, 200, 4096, 100))
    // Tavily Key 行，整体显示/隐藏
    private lateinit var tavilyRow: JPanel

    // 股票可选列
    private val stockOptional get() = listOf(
        "symbol"      to L10n.settingsOptStockSymbol,
        "quantity"    to L10n.settingsOptStockQty,
        "cost"        to L10n.settingsOptStockCost,
        "marketValue" to L10n.settingsOptStockValue,
        "pnl"         to L10n.settingsOptStockPnl,
        "pnlPercent"  to L10n.settingsOptStockPnlPct
    )

    // 基金可选列
    private val fundOptional get() = listOf(
        "code"        to L10n.settingsOptFundCode,
        "shares"      to L10n.settingsOptFundShares,
        "costNav"     to L10n.settingsOptFundCostNav,
        "marketValue" to L10n.settingsOptFundValue,
        "pnl"         to L10n.settingsOptFundPnl,
        "pnlPercent"  to L10n.settingsOptFundPnlPct
    )

    private val stockBoxes = mutableMapOf<String, JBCheckBox>()
    private val fundBoxes  = mutableMapOf<String, JBCheckBox>()

    override fun getDisplayName() = "StockLite"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 8, 2, 8)
            anchor = GridBagConstraints.WEST
            fill   = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx   = 0
        }

        fun sep(text: String, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(12, 8, 2, 8)
            panel.add(JSeparator(), gbc)
            gbc.gridy = row + 1; gbc.insets = Insets(2, 8, 4, 8)
            panel.add(JLabel("<html><b>$text</b></html>"), gbc)
        }

        fun alwaysRow(label: String, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(2, 8, 2, 8)
            panel.add(JBCheckBox(label).apply { isSelected = true; isEnabled = false }, gbc)
        }

        fun optionalRow(key: String, label: String, boxes: MutableMap<String, JBCheckBox>, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(2, 8, 2, 8)
            val cb = JBCheckBox(label); boxes[key] = cb; panel.add(cb, gbc)
        }

        fun intervalRow(label: String, spinner: JSpinner, row: Int) {
            gbc.gridy = row; gbc.insets = Insets(2, 8, 2, 8)
            val rowPanel = JPanel().apply {
                layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)
                add(JLabel(label))
                spinner.preferredSize = java.awt.Dimension(70, spinner.preferredSize.height)
                add(spinner)
            }
            panel.add(rowPanel, gbc)
        }

        var row = 0

        // ── 界面语言 ──
        sep(L10n.settingsLanguage, row); row += 2
        langRadios.values.forEach { rb -> gbc.gridy = row++; panel.add(rb, gbc) }

        // ── 颜色方案 ──
        sep(L10n.settingsColorScheme, row); row += 2
        colorRadios.values.forEach { rb -> gbc.gridy = row++; panel.add(rb, gbc) }

        // ── 刷新间隔 ──
        sep(L10n.settingsRefreshIntervals, row); row += 2
        intervalRow(L10n.settingsStockInterval,  stockIntervalSpinner,  row++)
        intervalRow(L10n.settingsFundInterval,   fundIntervalSpinner,   row++)
        intervalRow(L10n.settingsGlobalInterval, globalIntervalSpinner, row++)

        // ── 功能开关 ──
        sep(L10n.settingsFeatures, row); row += 2
        gbc.gridy = row++; panel.add(alertsCheckBox,      gbc)
        gbc.gridy = row++; panel.add(fundNavAlertCheckBox, gbc)

        // ── AI 分析 ──
        sep(L10n.settingsAiSection, row); row += 2
        gbc.gridy = row++
        val aiKeyRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(JLabel(L10n.settingsAiApiKey))
            add(Box.createHorizontalStrut(4))
            add(apiKeyField)
        }
        panel.add(aiKeyRow, gbc)
        gbc.gridy = row++
        val aiModelRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(JLabel(L10n.settingsAiModel))
            add(Box.createHorizontalStrut(4))
            add(modelCombo)
            add(Box.createHorizontalStrut(4))
            add(fetchModelsBtn)
        }
        panel.add(aiModelRow, gbc)
        fetchModelsBtn.addActionListener { loadModels(String(apiKeyField.password)) }
        gbc.gridy = row++
        val balanceRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(queryBalanceBtn); add(Box.createHorizontalStrut(8)); add(balanceLabel)
        }
        panel.add(balanceRow, gbc)
        queryBalanceBtn.addActionListener { queryBalance(String(apiKeyField.password)) }
        gbc.gridy = row++
        panel.add(JLabel("<html><font color='gray'><i>${L10n.settingsAiHint}</i></font></html>"), gbc)

        // ── AI 功能增强 ──
        sep("AI 功能增强", row); row += 2
        gbc.gridy = row++; panel.add(aiInjectDataCheckBox, gbc)
        gbc.gridy = row++; panel.add(aiWebSearchCheckBox, gbc)

        tavilyRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Tavily Key："))
            add(tavilyKeyField)
            add(Box.createHorizontalStrut(6))
            add(tavilyLinkLabel)
            add(Box.createHorizontalStrut(16))
            add(JLabel("最大搜索轮次："))
            webSearchRoundsSpinner.preferredSize = java.awt.Dimension(60, webSearchRoundsSpinner.preferredSize.height)
            add(webSearchRoundsSpinner)
            add(Box.createHorizontalStrut(4))
            add(JLabel("<html><font color='gray'>（2 ~ 20）</font></html>"))
        }
        gbc.gridy = row++; gbc.insets = Insets(0, 24, 2, 8)
        panel.add(tavilyRow, gbc)
        gbc.insets = Insets(2, 8, 2, 8)

        gbc.gridy = row++; panel.add(aiDeepReasonCheckBox, gbc)

        gbc.gridy = row++
        val maxTokensRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(JLabel("最大输出 Token："))
            add(Box.createHorizontalStrut(4))
            aiMaxTokensSpinner.preferredSize = java.awt.Dimension(80, aiMaxTokensSpinner.preferredSize.height)
            add(aiMaxTokensSpinner)
            add(Box.createHorizontalStrut(6))
            add(JLabel("<html><font color='gray'>（200 ~ 4096）</font></html>"))
        }
        panel.add(maxTokensRow, gbc)

        // 联网搜索开关联动 Tavily Key 行的显示
        aiWebSearchCheckBox.addActionListener { updateTavilyVisibility() }

        // ── 数据管理 ──
        sep(L10n.settingsDataMgmt, row); row += 2
        gbc.gridy = row++
        val importExportBtn = JButton(L10n.btnExportData + " / " + L10n.btnImportData)
        importExportBtn.addActionListener { ImportExportDialog().show() }
        panel.add(importExportBtn, gbc)

        // ── 股票列 ──
        sep(L10n.settingsStockCols, row); row += 2
        alwaysRow(L10n.settingsStockName,   row++)
        alwaysRow(L10n.settingsStockPrice,  row++)
        alwaysRow(L10n.settingsStockChange, row++)
        for ((key, label) in stockOptional) optionalRow(key, label, stockBoxes, row++)

        // ── 基金列 ──
        sep(L10n.settingsFundCols, row); row += 2
        alwaysRow(L10n.settingsFundName,       row++)
        alwaysRow(L10n.settingsFundNav,         row++)
        alwaysRow(L10n.settingsFundOfficialChg, row++)
        alwaysRow(L10n.settingsFundNavDate,     row++)
        alwaysRow(L10n.settingsFundTodayEst,    row++)
        for ((key, label) in fundOptional) optionalRow(key, label, fundBoxes, row++)

        // 撑开底部
        gbc.gridy = row; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        reset()
        return JBScrollPane(panel).apply { border = null }
    }

    override fun isModified(): Boolean {
        val state = StockliteState.getInstance()
        val sv = state.stockVisibleColumns.toSet()
        val fv = state.fundVisibleColumns.toSet()
        val selectedScheme = colorRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "RED_UP"
        val selectedLang   = langRadios.entries.firstOrNull  { it.value.isSelected }?.key ?: "ZH"
        return stockBoxes.any { (k, cb) -> cb.isSelected != sv.contains(k) } ||
               fundBoxes.any  { (k, cb) -> cb.isSelected != fv.contains(k) } ||
               selectedScheme != state.colorScheme ||
               selectedLang   != state.language ||
               (stockIntervalSpinner.value as Int)  != state.refreshIntervalStock ||
               (fundIntervalSpinner.value as Int)   != state.refreshIntervalFund  ||
               (globalIntervalSpinner.value as Int) != state.refreshIntervalGlobal ||
               alertsCheckBox.isSelected      != state.enablePriceAlerts ||
               fundNavAlertCheckBox.isSelected != state.enableFundNavAlert ||
               String(apiKeyField.password)        != state.deepseekApiKey ||
               modelCombo.selectedItem?.toString() != state.deepseekModel  ||
               aiInjectDataCheckBox.isSelected  != state.aiInjectRealTimeData  ||
               aiWebSearchCheckBox.isSelected      != state.aiEnableWebSearch        ||
               String(tavilyKeyField.password)     != state.aiTavilyApiKey           ||
               (webSearchRoundsSpinner.value as Int) != state.aiWebSearchMaxRounds   ||
               aiDeepReasonCheckBox.isSelected  != state.aiEnableDeepReasoning ||
               (aiMaxTokensSpinner.value as Int) != state.aiMaxTokens
    }

    override fun apply() {
        val state = StockliteState.getInstance()
        state.stockVisibleColumns = ArrayList(stockBoxes.filter { it.value.isSelected }.keys.sorted())
        state.fundVisibleColumns  = ArrayList(fundBoxes.filter  { it.value.isSelected }.keys.sorted())
        state.colorScheme = colorRadios.entries.firstOrNull { it.value.isSelected }?.key ?: "RED_UP"
        state.language    = langRadios.entries.firstOrNull  { it.value.isSelected }?.key ?: "ZH"
        state.refreshIntervalStock  = stockIntervalSpinner.value  as Int
        state.refreshIntervalFund   = fundIntervalSpinner.value   as Int
        state.refreshIntervalGlobal = globalIntervalSpinner.value as Int
        state.enablePriceAlerts  = alertsCheckBox.isSelected
        state.enableFundNavAlert = fundNavAlertCheckBox.isSelected
        state.deepseekApiKey     = String(apiKeyField.password)
        state.deepseekModel      = modelCombo.selectedItem?.toString() ?: "deepseek-chat"
        state.aiInjectRealTimeData  = aiInjectDataCheckBox.isSelected
        state.aiEnableWebSearch     = aiWebSearchCheckBox.isSelected
        state.aiTavilyApiKey        = String(tavilyKeyField.password)
        state.aiWebSearchMaxRounds  = webSearchRoundsSpinner.value as Int
        state.aiEnableDeepReasoning = aiDeepReasonCheckBox.isSelected
        state.aiMaxTokens           = aiMaxTokensSpinner.value as Int
        state.notifyColumnSettingsChanged()
        state.notifyLanguageChanged()
        state.notifyRefreshIntervalChanged()
    }

    override fun reset() {
        val state = StockliteState.getInstance()
        val sv = state.stockVisibleColumns.toSet()
        val fv = state.fundVisibleColumns.toSet()
        stockBoxes.forEach { (k, cb) -> cb.isSelected = k in sv }
        fundBoxes.forEach  { (k, cb) -> cb.isSelected = k in fv }
        (colorRadios[state.colorScheme] ?: colorRadios["RED_UP"])?.isSelected = true
        (langRadios[state.language]     ?: langRadios["ZH"])?.isSelected = true
        stockIntervalSpinner.value  = state.refreshIntervalStock
        fundIntervalSpinner.value   = state.refreshIntervalFund
        globalIntervalSpinner.value = state.refreshIntervalGlobal
        alertsCheckBox.isSelected       = state.enablePriceAlerts
        fundNavAlertCheckBox.isSelected = state.enableFundNavAlert
        apiKeyField.text                = state.deepseekApiKey
        // 若 combo 为空（首次打开），先用保存的值填入作为占位；有 Key 时自动拉取列表
        val savedModel = state.deepseekModel.ifBlank { "deepseek-chat" }
        if (modelCombo.itemCount == 0) modelCombo.addItem(savedModel)
        modelCombo.selectedItem = savedModel
        if (state.deepseekApiKey.isNotBlank()) loadModels(state.deepseekApiKey)
        // AI 功能增强
        aiInjectDataCheckBox.isSelected  = state.aiInjectRealTimeData
        aiWebSearchCheckBox.isSelected   = state.aiEnableWebSearch
        tavilyKeyField.text              = state.aiTavilyApiKey
        webSearchRoundsSpinner.value     = state.aiWebSearchMaxRounds
        aiDeepReasonCheckBox.isSelected  = state.aiEnableDeepReasoning
        aiMaxTokensSpinner.value         = state.aiMaxTokens
        if (::tavilyRow.isInitialized) updateTavilyVisibility()
    }

    /** Tavily Key 行跟随联网搜索开关显示/隐藏 */
    private fun updateTavilyVisibility() {
        if (::tavilyRow.isInitialized) tavilyRow.isVisible = aiWebSearchCheckBox.isSelected
    }

    /**
     * 后台线程请求 DeepSeek /models，回调 EDT 更新 modelCombo。
     * 保留当前已选模型；若列表中没有则追加。
     */
    private fun queryBalance(apiKey: String) {
        if (apiKey.isBlank()) { balanceLabel.text = "请先填写 API Key"; return }
        queryBalanceBtn.isEnabled = false
        balanceLabel.text = "查询中…"
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                val result = com.stocklite.plugin.service.AiAnalysisService.fetchBalance(apiKey)
                javax.swing.SwingUtilities.invokeLater {
                    queryBalanceBtn.isEnabled = true
                    balanceLabel.text = result ?: "查询失败，请检查 API Key 或网络"
                    balanceLabel.foreground = if (result != null) java.awt.Color.GRAY
                                             else java.awt.Color(0xCC, 0x33, 0x33)
                }
            }
    }

    private fun loadModels(apiKey: String) {
        if (apiKey.isBlank()) return
        fetchModelsBtn.isEnabled = false
        val previousSelection = modelCombo.selectedItem?.toString()
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                val models = com.stocklite.plugin.service.AiAnalysisService.fetchModels(apiKey)
                javax.swing.SwingUtilities.invokeLater {
                    fetchModelsBtn.isEnabled = true
                    if (models.isNullOrEmpty()) return@invokeLater
                    modelCombo.removeAllItems()
                    models.forEach { modelCombo.addItem(it) }
                    // 恢复之前选中的模型（若仍在列表中）
                    val target = previousSelection?.takeIf { models.contains(it) }
                        ?: models.firstOrNull() ?: return@invokeLater
                    modelCombo.selectedItem = target
                }
            }
    }
}
