package com.workspect.plugin.ccglyph

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.TitledSeparator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.FormBuilder
import com.workspect.plugin.ccglyph.profile.ProfilesConfigurable
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.ListCellRenderer
import javax.swing.SpinnerNumberModel
import kotlin.math.roundToInt

/** Settings → Tools → CCGlyph */
class CCGlyphSettingsConfigurable : SearchableConfigurable {

    private val settings = CCGlyphSettings.getInstance()
    /** Profiles table — embedded on this page (live-edited; changes save immediately). */
    private val profiles = ProfilesConfigurable()

    private lateinit var fontCombo: ComboBox<String>
    private lateinit var fallbackCombo: ComboBox<String>
    private lateinit var sizeSpinner: JSpinner
    private lateinit var lineHeightSpinner: JSpinner
    private lateinit var letterSpacingSpinner: JSpinner
    private lateinit var shellCombo: ComboBox<String>
    private lateinit var plusModeCombo: ComboBox<String>
    // Status chip & effects (global — apply to every profile/session, see CCGlyphSettings.State).
    private lateinit var beamCb: JCheckBox
    private lateinit var tabCb: JCheckBox
    private lateinit var chipCb: JCheckBox
    private lateinit var chipModelCb: JCheckBox
    private lateinit var chipCostCb: JCheckBox
    private lateinit var chipCtxCb: JCheckBox
    private lateinit var dismissCb: JCheckBox
    private lateinit var updateCb: JCheckBox

    override fun getId(): String = "ccglyph.settings"
    override fun getDisplayName(): String = "Claude Code Glyph"

    override fun createComponent(): JComponent {
        val s = settings.state
        // Prepend the default font and "monospace" (guarantees that JetBrains Mono is in the list
        // even when the system does not register it as a family), then append every system font alphabetically.
        val sysFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
        val fontList = (linkedSetOf(CCGlyphSettings.DEFAULT_FONT, "monospace") + sysFonts).toTypedArray()
        val fontPreview = fontPreviewRenderer()
        fontCombo = ComboBox(fontList).apply {
            renderer = fontPreview
            selectedItem = s.fontFamily.takeIf { it in fontList } ?: CCGlyphSettings.DEFAULT_FONT
        }
        // Show the effective value for the "follow IDE" sentinels (fontSize=0 / fallbackFont="") so the
        // user sees what's in use, and Apply stays disabled if left unchanged (preserving follow).
        // lineHeight & letterSpacing are plain xterm values (no IDE source).
        val effFallback = s.fallbackFont.takeIf { it.isNotBlank() } ?: CCGlyphSettings.editorFallbackFont()
        val effFontSize = if (s.fontSize > 0) s.fontSize else CCGlyphSettings.editorFontSize()
        fallbackCombo = ComboBox(fontList).apply {
            renderer = fontPreview
            selectedItem = effFallback.takeIf { it in fontList } ?: CCGlyphSettings.editorFontFamily()
        }
        sizeSpinner = JSpinner(SpinnerNumberModel(effFontSize, 6, 72, 1))
        lineHeightSpinner = JSpinner(SpinnerNumberModel(s.lineHeight, 1.0, 3.0, 0.1))
        letterSpacingSpinner = JSpinner(SpinnerNumberModel(s.letterSpacing, 0.0, 8.0, 0.5))
        // What the "+" button opens (also reopen / in-tab new-tab). The first tab is always a Claude session.
        plusModeCombo = ComboBox(arrayOf("Claude session", "Plain terminal")).apply {
            selectedItem = if (s.plusOpensPlainShell) "Plain terminal" else "Claude session"
        }
        // Shell is an editable dropdown populated with common shells that are actually executable on this
        // system, plus the IDE's own terminal shell setting so it's always selectable.
        shellCombo = ComboBox(commonShells()).apply {
            isEditable = true
            selectedItem = s.shellPath
        }
        // Widen the shell dropdown to accommodate long Windows paths (e.g. C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe).
        shellCombo.preferredSize = Dimension(
            maxOf(fontCombo.preferredSize.width * 2, 400), shellCombo.preferredSize.height
        )

        // ⋯ opens a file chooser for the shell executable (starts at /usr/bin on macOS). Extra horizontal
        // padding makes the small icon easier to hit.
        val browseIcon = AllIcons.Actions.MoreHorizontal
        val browseButton = JButton(browseIcon).apply {
            toolTipText = "Browse for shell executable..."
            isFocusPainted = false
            margin = java.awt.Insets(2, 10, 2, 10)
            addActionListener {
                val desc = FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle("Select Shell")
                    .withDescription("Choose a shell executable")
                val initial = LocalFileSystem.getInstance().findFileByPath("/usr/bin")
                    ?: LocalFileSystem.getInstance().findFileByPath("/bin")
                FileChooser.chooseFile(desc, null, initial)?.let { shellCombo.selectedItem = it.path }
            }
        }

        beamCb = JCheckBox("Gradient beam", s.beamEnabled)
        tabCb = JCheckBox("Tab color", s.tabColorEnabled)
        chipCb = JCheckBox("Show status chip", s.showStatusChip)
        chipModelCb = JCheckBox("Model", s.chipShowModel)
        chipCostCb = JCheckBox("Cost (USD)", s.chipShowCost)
        chipCtxCb = JCheckBox("Context %", s.chipShowContext)
        dismissCb = JCheckBox("Clear the waiting effect when I start typing", s.dismissWaitingOnInput)
        updateCb = JCheckBox("Update Claude Code before starting a session", s.updateClaudeBeforeStart)
        // "Show in chip" sub-options only matter when the chip is on — grey them out when it's off.
        chipCb.addItemListener { syncChipSubEnabled() }
        syncChipSubEnabled()

        // Inline cells keep UI DSL's label column and baselines aligned; section headers go through section().
        return panel {
            section("Terminal")
            row("Font:") { cell(fontCombo); label("Fallback:"); cell(fallbackCombo) }
            row("Font size:") { cell(sizeSpinner); label("Line height:"); cell(lineHeightSpinner); label("Spacing (px):"); cell(letterSpacingSpinner) }
            row("Shell:") { cell(shellCombo); cell(browseButton) }
            section("Claude Code")
            row("New tab (+) opens:") { cell(plusModeCombo) }
            row { cell(updateCb) }
            section("Status & Effects")
            row("Effects:") { cell(beamCb); cell(tabCb) }
            row { cell(chipCb) }
            // "Show in chip" sits under "Show status chip" — nest it in a left-padded panel so it reads as a sub-row.
            row {
                cell(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 28, 0)).apply {
                    border = BorderFactory.createEmptyBorder(0, 20, 0, 0)
                    add(javax.swing.JLabel("Show in chip:"))
                    add(chipModelCb); add(chipCostCb); add(chipCtxCb)
                })
            }
            row { cell(dismissCb) }
            section("Profiles")
            row { cell(profiles.createComponent()).align(AlignX.FILL).resizableColumn() }
        }
    }

    /** Section header = TitledSeparator. A bare TitledSeparator in a UI DSL cell collapses to no rule, so wrap it
     *  in a FormBuilder panel (GridBagLayout fill=HORIZONTAL grows the rule). A wide preferredSize makes the rule
     *  render immediately; an uncapped maximumSize + align(FILL)+resizableColumn stretches it to the dialog width. */
    private fun Panel.section(title: String) {
        row {
            val header = FormBuilder.createFormBuilder().addComponent(TitledSeparator(title)).panel
            header.preferredSize = Dimension(625, header.preferredSize.height)
            header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
            cell(header).align(AlignX.FILL).resizableColumn()
        }
    }

    override fun isModified(): Boolean {
        val s = settings.state
        val effFallback = s.fallbackFont.takeIf { it.isNotBlank() } ?: CCGlyphSettings.editorFallbackFont()
        val effFontSize = if (s.fontSize > 0) s.fontSize else CCGlyphSettings.editorFontSize()
        return fontCombo.selectedItem != s.fontFamily ||
            fallbackCombo.selectedItem != effFallback ||
            sizeSpinner.value != effFontSize ||
            round1((lineHeightSpinner.value as Number).toDouble()) != round1(s.lineHeight) ||
            round1((letterSpacingSpinner.value as Number).toDouble()) != round1(s.letterSpacing) ||
            (shellCombo.selectedItem as? String) != s.shellPath ||
            (plusModeCombo.selectedItem == "Plain terminal") != s.plusOpensPlainShell ||
            beamCb.isSelected != s.beamEnabled ||
            tabCb.isSelected != s.tabColorEnabled ||
            chipCb.isSelected != s.showStatusChip ||
            chipModelCb.isSelected != s.chipShowModel ||
            chipCostCb.isSelected != s.chipShowCost ||
            chipCtxCb.isSelected != s.chipShowContext ||
            dismissCb.isSelected != s.dismissWaitingOnInput ||
            updateCb.isSelected != s.updateClaudeBeforeStart
    }

    override fun apply() {
        val s = settings.state
        s.fontFamily = fontCombo.selectedItem as String
        // For the "follow IDE" fields, keep the sentinel (0 / "") when the user left the value at its
        // effective default — so changing an unrelated setting doesn't lock these to a concrete value.
        val effFallback = s.fallbackFont.takeIf { it.isNotBlank() } ?: CCGlyphSettings.editorFallbackFont()
        val newFallback = fallbackCombo.selectedItem as String
        s.fallbackFont = if (newFallback == effFallback) s.fallbackFont else newFallback
        val effFontSize = if (s.fontSize > 0) s.fontSize else CCGlyphSettings.editorFontSize()
        val newSize = (sizeSpinner.value as Number).toInt()
        s.fontSize = if (newSize == effFontSize) s.fontSize else newSize
        s.lineHeight = round1((lineHeightSpinner.value as Number).toDouble())
        s.letterSpacing = round1((letterSpacingSpinner.value as Number).toDouble())
        s.shellPath = (shellCombo.selectedItem as? String)?.trim()?.ifBlank { CCGlyphSettings.defaultShell() }
            ?: CCGlyphSettings.defaultShell()
        s.plusOpensPlainShell = plusModeCombo.selectedItem == "Plain terminal"
        s.beamEnabled = beamCb.isSelected
        s.tabColorEnabled = tabCb.isSelected
        s.showStatusChip = chipCb.isSelected
        s.chipShowModel = chipModelCb.isSelected
        s.chipShowCost = chipCostCb.isSelected
        s.chipShowContext = chipCtxCb.isSelected
        s.dismissWaitingOnInput = dismissCb.isSelected
        s.updateClaudeBeforeStart = updateCb.isSelected
        // Live-reload: push new config to all open terminal tabs immediately (no restart needed).
        settings.notifySettingsChanged()
    }

    override fun reset() {
        val s = settings.state
        fontCombo.selectedItem = s.fontFamily
        fallbackCombo.selectedItem = s.fallbackFont.takeIf { it.isNotBlank() } ?: CCGlyphSettings.editorFallbackFont()
        sizeSpinner.value = if (s.fontSize > 0) s.fontSize else CCGlyphSettings.editorFontSize()
        lineHeightSpinner.value = s.lineHeight
        letterSpacingSpinner.value = s.letterSpacing
        shellCombo.selectedItem = s.shellPath
        plusModeCombo.selectedItem = if (s.plusOpensPlainShell) "Plain terminal" else "Claude session"
        beamCb.isSelected = s.beamEnabled
        tabCb.isSelected = s.tabColorEnabled
        chipCb.isSelected = s.showStatusChip
        chipModelCb.isSelected = s.chipShowModel
        chipCostCb.isSelected = s.chipShowCost
        chipCtxCb.isSelected = s.chipShowContext
        dismissCb.isSelected = s.dismissWaitingOnInput
        updateCb.isSelected = s.updateClaudeBeforeStart
        syncChipSubEnabled()
    }

    /** Enable/disable the "Show in chip" sub-options to match the "Show status chip" toggle. */
    private fun syncChipSubEnabled() {
        val on = chipCb.isSelected
        chipModelCb.isEnabled = on
        chipCostCb.isEnabled = on
        chipCtxCb.isEnabled = on
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0

    /** Renders each font name in its own typeface (a font preview), like the IDE's editor font picker.
     *  Names not registered as families (e.g. "monospace") keep the default UI font. */
    private fun fontPreviewRenderer(): ListCellRenderer<Any> =
        object : DefaultListCellRenderer() {
            private val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean,
            ): Component {
                super.getListCellRendererComponent(list, value, index, selected, hasFocus)
                val name = value as? String
                if (name != null && available.contains(name)) {
                    font = Font(name, Font.PLAIN, list?.font?.size ?: font.size)
                }
                return this
            }
        }

    /** Likely available shells → populate the dropdown (only those that are actually executable).
     *  Always includes the IDE's own terminal shell setting so the user can pick it. */
    private fun commonShells(): Array<String> {
        val existing = mutableSetOf<String>()

        // Include the IDE's terminal shell setting (Settings → Tools → Terminal → Shell path).
        runCatching { TerminalLocalOptions.getInstance().shellPath }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { existing += it }

        // Unix shells (checked for executability).
        val unixShells = listOf(
            "/bin/zsh", "/bin/bash", "/bin/sh", "/bin/dash", "/bin/fish",
            "/usr/bin/zsh", "/usr/bin/bash", "/usr/bin/sh", "/usr/bin/fish",
            "/opt/homebrew/bin/zsh", "/opt/homebrew/bin/fish",
            "/usr/local/bin/zsh", "/usr/local/bin/fish",
        )
        existing += unixShells.filter { File(it).canExecute() }

        // Windows shells (checked for executability via SystemRoot / ProgramFiles).
        if (CCGlyphSettings.isWindows) {
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val sysRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
            val winShells = listOf(
                "$programFiles\\PowerShell\\7\\pwsh.exe",                       // PowerShell 7+ (best Unicode/emoji)
                "$sysRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",  // Windows PowerShell 5.1
                "$sysRoot\\System32\\cmd.exe",
                "$sysRoot\\System32\\wsl.exe",
            )
            existing += winShells.filter { File(it).exists() }
        }

        if (existing.isEmpty()) {
            existing += if (CCGlyphSettings.isWindows) listOf("powershell.exe", "cmd.exe")
            else listOf("/bin/zsh", "/bin/bash", "/bin/sh")
        }
        return existing.toTypedArray()
    }
}
