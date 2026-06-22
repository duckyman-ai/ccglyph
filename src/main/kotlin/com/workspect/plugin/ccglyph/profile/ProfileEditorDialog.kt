package com.workspect.plugin.ccglyph.profile

import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

/** Modal editor for a single [Profile]. Sections use IntelliJ's TitledSeparator; the Env table is the
 *  resizable region (drag the dialog corner to grow it). Edits the profile in place on OK. */
class ProfileEditorDialog(
    project: Project?,
    private val profile: Profile,
) : DialogWrapper(project) {

    private val name = JTextField(profile.name)
    private val effectTheme = ComboBox(arrayOf("default", "subtle", "extra")).apply { selectedItem = profile.effectTheme }
    private val icon = JTextField(profile.icon)
    private val model = JTextField(profile.model)
    private val permissionMode = ComboBox(arrayOf("default", "acceptEdits", "plan", "auto", "dontAsk", "bypassPermissions")).apply {
        selectedItem = profile.permissionMode.takeIf { it.isNotBlank() } ?: "default"
    }
    private val extraArgs = JTextField(profile.extraArgs)
    private val updateBeforeStart = JCheckBox("Update Claude Code before starting", profile.updateBeforeStart)
    private val resumeLast = JCheckBox("Resume the last session in this profile", profile.resumeLast)
    private val configDir = TextFieldWithBrowseButton().apply {
        text = profile.configDir
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private val settingsPath = TextFieldWithBrowseButton().apply {
        text = profile.settingsPath
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("json"))
    }

    // Environment variables as an editable KEY/VALUE table (parsed from / serialised to the "KEY=VALUE\n…" string
    // that Profile.env stores). Cells are edited inline; Add/Remove come from the ToolbarDecorator action bar.
    private val envRows: MutableList<Array<String>> = profile.env
        .lineSequence()
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            when {
                idx >= 0 -> arrayOf(line.substring(0, idx), line.substring(idx + 1))
                line.isNotBlank() -> arrayOf(line.trim(), "")
                else -> null
            }
        }
        .toMutableList()

    private val envModel = object : AbstractTableModel() {
        override fun getRowCount() = envRows.size
        override fun getColumnCount() = 2
        override fun getColumnName(c: Int) = if (c == 0) "Name" else "Value"
        override fun isCellEditable(r: Int, c: Int) = true
        override fun getValueAt(r: Int, c: Int): String = envRows[r][c]
        override fun setValueAt(value: Any?, r: Int, c: Int) {
            envRows[r][c] = value.toString()
            fireTableCellUpdated(r, c)
        }
    }

    private val envTable = JBTable(envModel).apply {
        rowHeight = 24
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        preferredScrollableViewportSize = Dimension(460, 120)
        columnModel.getColumn(0).preferredWidth = 150
        columnModel.getColumn(1).preferredWidth = 310
    }

    init {
        title = if (profile.name.isBlank()) "New Profile" else "Edit Profile"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val top = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name", name, 1, false)
            .addComponent(TitledSeparator("Appearance"))
            .addLabeledComponent("Effect theme", effectTheme, 1, false)
            .addTooltip("Intensity of the status beam / tab effects (default · subtle · extra).")
            .addLabeledComponent("Tab icon", icon, 1, false)
            .addTooltip("Icon name: claude / codex / gemini / vim … blank = auto.")
            .addComponent(TitledSeparator("Claude Code"))
            .addLabeledComponent("Model", model, 1, false)
            .addTooltip("--model override (blank = claude's default).")
            .addLabeledComponent("Permission mode", permissionMode, 1, false)
            .addTooltip("Claude permission mode: default · acceptEdits · plan · auto · dontAsk · bypassPermissions.")
            .addLabeledComponent("Extra arguments", extraArgs, 1, false)
            .addTooltip("Additional `claude` CLI flags, e.g. --resume <id>.")
            .addComponent(updateBeforeStart)
            .addComponent(resumeLast)
            .panel

        val envHeader = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Environment"))
            .addLabeledComponent("Settings JSON", settingsPath, 1, false)
            .addTooltip("Merged into the session as settings.json (blank = claude defaults).")
            .panel
        // Env editor: an editable KEY/VALUE table wrapped by ToolbarDecorator (native CommonActionsPanel action
        // bar — Add appends a blank row, Remove deletes the selected row(s)). This is the resizable region.
        val envArea = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("Environment variables — override the settings.json env block"), BorderLayout.NORTH)
            add(ToolbarDecorator.createDecorator(envTable)
                .disableUpDownActions()
                .setToolbarPosition(ActionToolbarPosition.TOP)
                .setAddAction {
                    envRows.add(arrayOf("", ""))
                    envModel.fireTableRowsInserted(envRows.size - 1, envRows.size - 1)
                }
                .setRemoveAction {
                    val rows = envTable.selectedRows.sortedDescending()
                    if (rows.isNotEmpty()) {
                        for (i in rows) envRows.removeAt(i)
                        envModel.fireTableDataChanged()
                    }
                }
                .createPanel(), BorderLayout.CENTER)
        }
        val envSection = JPanel(BorderLayout(0, 8)).apply {
            add(envHeader, BorderLayout.NORTH)
            add(envArea, BorderLayout.CENTER)
        }

        val isolation = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Isolation"))
            .addLabeledComponent("Config directory", configDir, 1, false)
            .addTooltip("CLAUDE_CONFIG_DIR — separate credentials, history and projects (blank = shared ~/.claude).")
            .panel

        return JPanel(BorderLayout(0, 12)).apply {
            add(top, BorderLayout.NORTH)
            add(envSection, BorderLayout.CENTER)
            add(isolation, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent() = name

    override fun doOKAction() {
        // Commit any in-flight cell edit so its value lands in envRows before we serialise.
        if (envTable.isEditing) envTable.cellEditor?.stopCellEditing()
        if (name.text.isNullOrBlank()) { name.requestFocusInWindow(); return }
        profile.name = ProfileService.getInstance().uniqueName(name.text.trim(), profile.id)
        profile.effectTheme = (effectTheme.selectedItem as? String) ?: "default"
        profile.icon = icon.text.trim()
        profile.model = model.text.trim()
        profile.permissionMode = (permissionMode.selectedItem as? String).orEmpty()
        profile.extraArgs = extraArgs.text.trim()
        profile.updateBeforeStart = updateBeforeStart.isSelected
        profile.resumeLast = resumeLast.isSelected
        profile.configDir = configDir.text.trim()
        profile.settingsPath = settingsPath.text.trim()
        profile.env = envRows
            .filter { it[0].isNotBlank() }
            .joinToString("\n") { "${it[0]}=${it[1]}" }
        super.doOKAction()
    }
}
