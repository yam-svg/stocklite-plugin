package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * 通用分组管理对话框（股票 / 基金 / 期货共用）。
 */
class ManageGroupsDialog(
    private val groups: MutableList<out Any>,
    private val onCreate: (String) -> Unit,
    private val onRename: (String, String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onDone: () -> Unit
) : DialogWrapper(null, true) {

    private val listModel = DefaultListModel<String>()
    private val groupList = JBList(listModel)

    init {
        title = L10n.dlgManageGroups
        init()
        reloadList()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))
        panel.preferredSize = Dimension(300, 360)

        groupList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        panel.add(JBScrollPane(groupList), BorderLayout.CENTER)

        val btnPanel  = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val addBtn    = JButton(L10n.btnCreate)
        val renameBtn = JButton(L10n.btnRename)
        val delBtn    = JButton(L10n.btnDelete)
        btnPanel.add(addBtn); btnPanel.add(renameBtn); btnPanel.add(delBtn)
        panel.add(btnPanel, BorderLayout.SOUTH)

        addBtn.addActionListener {
            val name = Messages.showInputDialog(
                L10n.dlgNewGroupPrompt, L10n.dlgNewGroupTitle, null
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return@addActionListener
            onCreate(name)
            reloadList()
        }

        renameBtn.addActionListener {
            val idx = groupList.selectedIndex.takeIf { it >= 0 } ?: return@addActionListener
            val g = groups[idx]
            val oldName = nameOf(g)
            val newName = Messages.showInputDialog(
                L10n.dlgRenamePrompt, L10n.dlgRenameTitle, null, oldName, null
            )?.trim()?.takeIf { it.isNotEmpty() } ?: return@addActionListener
            onRename(idOf(g), newName)
            reloadList()
        }

        delBtn.addActionListener {
            val idx = groupList.selectedIndex.takeIf { it >= 0 } ?: return@addActionListener
            val g = groups[idx]
            val confirm = JOptionPane.showConfirmDialog(
                panel,
                L10n.dlgConfirmDeleteGroup(nameOf(g)),
                L10n.dlgConfirmTitle,
                JOptionPane.YES_NO_OPTION
            )
            if (confirm == JOptionPane.YES_OPTION) {
                onDelete(idOf(g))
                reloadList()
            }
        }

        return panel
    }

    private fun reloadList() {
        listModel.clear()
        groups.forEach { listModel.addElement(nameOf(it)) }
    }

    override fun doOKAction() {
        super.doOKAction()
        onDone()
    }

    // 反射读 id / name（Kotlin 属性字段需用 getDeclaredField + isAccessible）
    private fun fieldValue(obj: Any, name: String): String {
        val f = obj.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(obj) as String
    }
    private fun idOf(g: Any): String   = fieldValue(g, "id")
    private fun nameOf(g: Any): String = fieldValue(g, "name")
}
