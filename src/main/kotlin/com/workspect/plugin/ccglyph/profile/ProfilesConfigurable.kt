package com.workspect.plugin.ccglyph.profile

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** Settings → Tools → CCGlyph Profiles: a table of profiles with a native CommonActionsPanel (Add/Edit/
 *  Duplicate/Remove) above it, built via [ToolbarDecorator]. Double-click a row to edit. Edits apply live to
 *  [ProfileService] (which persists on save). */
class ProfilesConfigurable : SearchableConfigurable {

    override fun getId() = "ccglyph.profiles"
    override fun getDisplayName() = "CCGlyph Profiles"

    private lateinit var table: JBTable
    private val tableModel = ProfileTableModel()

    override fun createComponent(): JPanel {
        table = JBTable(tableModel).apply {
            isStriped = true
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            rowHeight = 24
            preferredScrollableViewportSize = java.awt.Dimension(720, 160)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (e.clickCount >= 2) editSelected() }
            })
        }
        // ToolbarDecorator builds the native CommonActionsPanel — the bordered action bar you see across the
        // IDE's list/table settings — with Add/Edit/Remove wired to the live-edit methods below. Duplicate is
        // an extra action. setToolbarPosition(TOP) places the bar above the table instead of the default right.
        return ToolbarDecorator.createDecorator(table)
            .disableUpDownActions()
            .setToolbarPosition(ActionToolbarPosition.TOP)
            .setAddAction { addProfile() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .addExtraAction(object : AnActionButton("Duplicate", AllIcons.Actions.Copy) {
                init { addCustomUpdater { table.selectedRow >= 0 } }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = duplicateSelected()
            })
            .createPanel()
    }

    // We mutate ProfileService's live list directly, so there's no separate commit state.
    override fun isModified() = false
    override fun apply() {}
    override fun reset() = tableModel.fireTableDataChanged()
    override fun disposeUIResources() {}

    private fun selectedIndex(): Int = table.selectedRow

    private fun addProfile() {
        val svc = ProfileService.getInstance()
        val p = Profile(id = svc.newId(), name = svc.uniqueName("New Profile"))
        if (ProfileEditorDialog(ProjectManager.getInstance().defaultProject, p).showAndGet()) {
            svc.add(p)
            tableModel.fireTableDataChanged()
        }
    }

    private fun editSelected() {
        val i = selectedIndex(); if (i < 0) return
        val p = ProfileService.getInstance().profiles()[i]
        if (ProfileEditorDialog(ProjectManager.getInstance().defaultProject, p).showAndGet()) {
            tableModel.fireTableRowsUpdated(i, i)
        }
    }

    private fun duplicateSelected() {
        val i = selectedIndex(); if (i < 0) return
        val svc = ProfileService.getInstance()
        val src = svc.profiles()[i]
        svc.add(src.copy(id = svc.newId(), name = svc.uniqueName(src.name)))
        tableModel.fireTableDataChanged()
    }

    private fun removeSelected() {
        val i = selectedIndex(); if (i < 0) return
        val svc = ProfileService.getInstance()
        svc.remove(svc.profiles()[i])
        tableModel.fireTableDataChanged()
    }

    private class ProfileTableModel : AbstractTableModel() {
        private val cols = arrayOf("Name", "Model", "Settings JSON", "Env")
        private fun list() = ProfileService.getInstance().profiles()
        override fun getRowCount() = list().size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getValueAt(r: Int, c: Int): String {
            val p = list()[r]
            return when (c) {
                0 -> p.name
                1 -> p.model
                2 -> p.settingsPath
                3 -> if (p.env.isBlank()) "" else "${p.env.lineSequence().count { it.contains('=') }} vars"
                else -> ""
            }
        }
    }
}
