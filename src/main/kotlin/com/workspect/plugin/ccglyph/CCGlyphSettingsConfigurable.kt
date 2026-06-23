package com.workspect.plugin.ccglyph

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.TitledSeparator
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
    private lateinit var scrollbackSpinner: JSpinner
    private lateinit var cursorCombo: ComboBox<String>
    // Status chip & effects (global — apply to every profile/session, see CCGlyphSettings.State).
    private lateinit var beamCb: JCheckBox
    private lateinit var tabCb: JCheckBox
    private lateinit var chipCb: JCheckBox
    private lateinit var chipModelCb: JCheckBox
    private lateinit var chipCostCb: JCheckBox
    private lateinit var chipCtxCb: JCheckBox
    private lateinit var dismissCb: JCheckBox

    override fun getId(): String = "ccglyph.settings"
    override fun getDisplayName(): String = "Claude Code Glyph"

    override fun createComponent(): JComponent {
        val s = settings.state
        // Prepend the default font and "monospace" (guarantees that JetBrains Mono is in the list
        // even when the system does not register it as a family), then append every system font alphabetically.
        val sysFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
        val fontList = (linkedSetOf(CCGlyphSettings.DEFAULT_FONT, "monospace") + sysFonts).toTypedArray()
        // Each font name is rendered in its own font (like the IDE's editor font picker) so the user can
        // preview the typeface while choosing. Names not registered as families (e.g. "monospace") fall back
        // to the default UI font.
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
        scrollbackSpinner = JSpinner(SpinnerNumberModel(s.scrollback, 0, 1_000_000, 1000))
        cursorCombo = ComboBox(arrayOf("block", "underline", "bar")).apply {
            selectedItem = s.cursorStyle
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

        // The ⋯ button (More Horizontal) opens a file chooser to select the shell executable; on macOS it starts at /usr/bin.
        // The ⋯ icon alone is hard to hit, so give the button comfortable horizontal padding (10px sides,
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

        // Status chip & effects checkboxes (global). These are the only "behaviour" toggles here — the rest
        // of the page is terminal appearance + profiles. Beam and tab colour are independent toggles.
        beamCb = JCheckBox("Gradient beam", s.beamEnabled)
        tabCb = JCheckBox("Tab colour", s.tabColorEnabled)
        chipCb = JCheckBox("Show status chip", s.showStatusChip)
        chipModelCb = JCheckBox("Model", s.chipShowModel)
        chipCostCb = JCheckBox("Cost (USD)", s.chipShowCost)
        chipCtxCb = JCheckBox("Context %", s.chipShowContext)
        dismissCb = JCheckBox("Clear the waiting effect when I start typing", s.dismissWaitingOnInput)

        // UI DSL panel — the IntelliJ-native settings layout. Its first row sits flush at the top of the
        // settings content area (no leading inset), matching every other IDE settings page. Components are
        // added as inline cells so UI DSL aligns their baselines and the label column stays consistent.
        // Section headers go through section() (see below).
        return panel {
            section("Terminal")
            row("Font:") { cell(fontCombo); label("Fallback:"); cell(fallbackCombo) }
            row("Font size:") { cell(sizeSpinner); label("Line height:"); cell(lineHeightSpinner); label("Spacing (px):"); cell(letterSpacingSpinner) }
            row("Shell:") { cell(shellCombo); cell(browseButton) }
            row("Scrollback (lines):") { cell(scrollbackSpinner) }
            row("Cursor style:") { cell(cursorCombo) }
            section("Status & effects")
            row("Effects:") { cell(beamCb); cell(tabCb) }
            row { cell(chipCb) }
            row("Show in chip:") { cell(chipModelCb); cell(chipCostCb); cell(chipCtxCb) }
            row { cell(dismissCb) }
            section("Claude Code Profiles")
            row { cell(profiles.createComponent()).resizableColumn() }
        }
    }

    /** Section header = the native IntelliJ TitledSeparator (the same component the Edit-Profile dialog uses).
     *  TitledSeparator's rule only renders when the component is wide AND laid out by FormBuilder's GridBagLayout
     *  (fill=HORIZONTAL); a bare TitledSeparator in a UI DSL cell collapses to no rule. So it's wrapped in a
     *  one-row FormBuilder panel, given a wide-ish preferred size (so it renders) capped by a maximum width
     *  (so it doesn't stretch past the form fields and look oversized). resizableColumn lets it shrink if the
     *  row is narrower than the cap. */
    private fun Panel.section(title: String) {
        row {
            val header = FormBuilder.createFormBuilder().addComponent(TitledSeparator(title)).panel
            header.preferredSize = Dimension(795, header.preferredSize.height)
            header.maximumSize = Dimension(795, Int.MAX_VALUE)
            cell(header).resizableColumn()
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
            scrollbackSpinner.value != s.scrollback ||
            cursorCombo.selectedItem != s.cursorStyle ||
            beamCb.isSelected != s.beamEnabled ||
            tabCb.isSelected != s.tabColorEnabled ||
            chipCb.isSelected != s.showStatusChip ||
            chipModelCb.isSelected != s.chipShowModel ||
            chipCostCb.isSelected != s.chipShowCost ||
            chipCtxCb.isSelected != s.chipShowContext ||
            dismissCb.isSelected != s.dismissWaitingOnInput
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
        s.scrollback = (scrollbackSpinner.value as Number).toInt()
        s.cursorStyle = cursorCombo.selectedItem as String
        s.beamEnabled = beamCb.isSelected
        s.tabColorEnabled = tabCb.isSelected
        s.showStatusChip = chipCb.isSelected
        s.chipShowModel = chipModelCb.isSelected
        s.chipShowCost = chipCostCb.isSelected
        s.chipShowContext = chipCtxCb.isSelected
        s.dismissWaitingOnInput = dismissCb.isSelected
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
        scrollbackSpinner.value = s.scrollback
        cursorCombo.selectedItem = s.cursorStyle
        beamCb.isSelected = s.beamEnabled
        tabCb.isSelected = s.tabColorEnabled
        chipCb.isSelected = s.showStatusChip
        chipModelCb.isSelected = s.chipShowModel
        chipCostCb.isSelected = s.chipShowCost
        chipCtxCb.isSelected = s.chipShowContext
        dismissCb.isSelected = s.dismissWaitingOnInput
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
