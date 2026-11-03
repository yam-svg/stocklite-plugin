package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.stocklite.plugin.service.MarketDataService
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * 全球指数显示选择对话框，分两个 Tab：
 * - 常用指数：勾选 = 显示（默认全勾选，取消即隐藏）；
 * - 更多指数：扩展市场指数，默认全不勾选，勾选 = 显式开启显示。
 * "全选/全不选"作用于当前选中的 Tab。
 * 打开时后台实探各数据源，逐项标注当前网络环境下的可达性（大陆 Yahoo 阻断等场景帮助用户
 * 理解哪些指数开了也没数据；不可达的指数面板上也会被自动隐藏）。
 */
class GlobalIndexPickerDialog(hidden: Set<String>, enabledOptional: Set<String>) : DialogWrapper(null, true) {

    private val baseLabels = mutableMapOf<String, String>()

    private fun boxesFor(items: List<MarketDataService.GlobalIndexConfig>, checked: (String) -> Boolean) =
        items.associate { cfg ->
            val base = "${cfg.nameCn} (${cfg.nameEn})"
            baseLabels[cfg.symbol] = base
            cfg.symbol to JBCheckBox("$base  ·  ${L10n.netStatusProbing}", checked(cfg.symbol))
        }

    private val mainBoxes = boxesFor(MarketDataService.GLOBAL_INDEXES.filterNot { it.optional }) { it !in hidden }
    private val optBoxes  = boxesFor(MarketDataService.GLOBAL_INDEXES.filter     { it.optional }) { it in  enabledOptional }
    private val tabs      = JTabbedPane()
    private val recheckBtn   = JButton(L10n.btnNetRecheck)
    private val checkedAtLbl = JLabel("").apply {
        font = font.deriveFont(11f)
        foreground = java.awt.Color(0x888aaa)
    }
    private var probing = false

    init {
        title = L10n.globalIndexPickerTitle
        init()
        recheckBtn.addActionListener { startNetProbe() }
        startNetProbe()
    }

    /** 后台线程对涉及的源各实探一次（Yahoo 被墙时最长 ~10s），回来按候选源逐个标注复选框 */
    private fun startNetProbe() {
        if (probing) return
        probing = true
        recheckBtn.isEnabled = false
        checkedAtLbl.text = L10n.netStatusProbing
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = MarketDataService.probeGlobalIndexNetwork()
            SwingUtilities.invokeLater {
                probing = false
                recheckBtn.isEnabled = true
                info.forEach { (sym, st) ->
                    val box = mainBoxes[sym] ?: optBoxes[sym] ?: return@forEach
                    val base = baseLabels[sym] ?: return@forEach
                    // 源可达 ≠ 该指数有数据：分开标注，避免"设置显示可用、面板一直是 0.00"
                    val status = when {
                        st.reachable && st.okKey != null -> L10n.netStatusOk(L10n.netSourceName(st.okKey))
                        st.okKey != null                 -> L10n.netStatusNoData(L10n.netSourceName(st.okKey))
                        else -> L10n.netStatusOffline(st.sourceKeys.joinToString("/") { L10n.netSourceName(it) })
                    }
                    box.text = "$base  ·  $status"
                }
                checkedAtLbl.text = L10n.netCheckedAt(
                    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
            }
        }
    }

    /** 常用区中被取消（隐藏）的 symbol 集合 */
    fun hiddenSymbols(): Set<String> = mainBoxes.filterValues { !it.isSelected }.keys
    /** 更多区中被显式开启的 symbol 集合 */
    fun enabledOptionalSymbols(): Set<String> = optBoxes.filterValues { it.isSelected }.keys

    private fun gridOf(boxes: Map<String, JBCheckBox>): JPanel =
        JPanel(GridLayout(0, 2, 12, 4)).apply { boxes.values.forEach { add(it) } }

    override fun createCenterPanel(): JComponent {
        tabs.addTab(L10n.globalIndexTabMain, gridOf(mainBoxes))
        tabs.addTab(L10n.globalIndexTabMore, gridOf(optBoxes))
        val root = JPanel(BorderLayout(0, 6))
        val quick = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        fun currentBoxes() = if (tabs.selectedIndex == 0) mainBoxes else optBoxes
        quick.add(JButton(L10n.btnSelectAll).apply {
            addActionListener { currentBoxes().values.forEach { it.isSelected = true } }
        })
        quick.add(JButton(L10n.biClearAll).apply {
            addActionListener { currentBoxes().values.forEach { it.isSelected = false } }
        })
        quick.add(recheckBtn)
        quick.add(checkedAtLbl)
        root.add(quick, BorderLayout.NORTH)
        root.add(tabs,  BorderLayout.CENTER)
        return root
    }
}
