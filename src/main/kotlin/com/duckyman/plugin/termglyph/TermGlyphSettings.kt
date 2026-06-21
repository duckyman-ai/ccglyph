package com.duckyman.plugin.termglyph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.AppEditorFontOptions

/**
 * Application-level (global) terminal settings — stored in termglyph.xml
 * Registered as an applicationService in plugin.xml
 */
@State(name = "TermGlyphSettings", storages = [Storage("termglyph.xml")])
class TermGlyphSettings : PersistentStateComponent<TermGlyphSettings.State> {

    data class State(
        var fontFamily: String = DEFAULT_FONT, // default: JetBrains Mono — bundled via @font-face so it renders on every OS without a system-font install (NOT follow-IDE, because a non-bundled editor font wouldn't render on Windows)
        var fallbackFont: String = "",   // "" = follow the IDE editor's fallback font
        var fontSize: Int = 0,           // 0 = follow the IDE editor font size
        var lineHeight: Double = 1.0,    // xterm.js line-height multiplier (1.0 = normal, the IDE terminal's default). Plain value; NOT follow-IDE.
        var letterSpacing: Double = 0.0, // xterm.js letterSpacing in PIXELS (additive gap between cells; 0 = normal). NB: NOT the IDE terminal's "Column width" — that's a cell-width multiplier (1.0 = normal), a different unit, so the two can't map 1:1.
        var shellPath: String = defaultShell(),          // default: the user's login shell ($SHELL)
        var scrollback: Int = 10000,
        var cursorStyle: String = "block",
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) {
        // Migrate stale Unix shell paths on Windows (e.g. "/bin/zsh" from before Windows support was
        // added, or from synced settings).  A Unix path will never work on Windows → reset to default.
        if (isWindows && state.shellPath.startsWith("/")) {
            state.shellPath = defaultShell()
        }
        // Migrate the old hardcoded defaults to the "follow IDE" sentinels so upgrading users get
        // IDE-derived values (and the terminal keeps tracking the IDE until they customise).
        if (state.fontSize == 14) state.fontSize = 0
        if (state.fallbackFont == "monospace") state.fallbackFont = ""
        // lineHeight is now a plain value (default 1.0 = the IDE terminal's default). Migrate the old
        // "follow IDE" sentinel (0.0) and the legacy 1.2 → the 1.0 default.
        if (state.lineHeight <= 0.0 || state.lineHeight == 1.2) state.lineHeight = 1.0
        // letterSpacing is now a plain xterm letterSpacing in px (0 = normal). Migrate the old "follow IDE
        // column width" sentinel (-1.0) back to the normal default 0.
        if (state.letterSpacing < 0.0) state.letterSpacing = 0.0
        myState = state
    }

    // --- live-reload listeners ---
    // Each open terminal panel registers itself; when settings are applied in the
    // Settings dialog, all panels regenerate their config immediately (no restart needed).
    private val listeners = mutableListOf<Runnable>()

    fun addListener(listener: Runnable) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Runnable) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun notifySettingsChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) {
            runCatching { l.run() }
        }
    }

    companion object {
        const val DEFAULT_FONT = "JetBrains Mono"

        /** The IDE's editor font size — used as the terminal's default font size until the user customises it.
         *  Safe to call at service-init time (guarded; falls back to 13). */
        fun editorFontSize(): Int =
            runCatching { EditorColorsManager.getInstance().globalScheme.editorFontSize }.getOrDefault(13)

        /** The IDE editor's primary font family (Settings → Editor → Font → Font) — used as the terminal's
         *  default font. Reads app-level font preferences first, then the global scheme. Falls back to
         *  DEFAULT_FONT (JetBrains Mono). NOTE: the editor font is normally monospace (e.g. JetBrains Mono). */
        fun editorFontFamily(): String =
            runCatching {
                val appFamilies = AppEditorFontOptions.getInstance().fontPreferences.effectiveFontFamilies
                if (appFamilies.isNotEmpty()) return@runCatching appFamilies[0]
                val schemeFamilies = EditorColorsManager.getInstance().globalScheme.fontPreferences.effectiveFontFamilies
                schemeFamilies.firstOrNull() ?: DEFAULT_FONT
            }.getOrDefault(DEFAULT_FONT)

        /** The IDE editor's first fallback font (Settings → Editor → Font → Fallback fonts).
         *  Reads the app-level font preferences first (bypasses scheme delegation edge cases), then the
         *  global scheme. When no distinct fallback is configured (the common case — most users have only a
         *  primary font), mirrors the primary font (e.g. JetBrains Mono) — which is also what the IDE shows
         *  in the Fallback slot when none is explicitly set. */
        fun editorFallbackFont(): String =
            runCatching {
                // Try the app-level font preferences first (Settings → Editor → Font).
                val appPrefs = AppEditorFontOptions.getInstance().fontPreferences
                val appFamilies = appPrefs.effectiveFontFamilies
                if (appFamilies.size > 1) return@runCatching appFamilies[1]
                // Fall back to the global scheme's effective preferences.
                val schemeFamilies = EditorColorsManager.getInstance().globalScheme.fontPreferences.effectiveFontFamilies
                if (schemeFamilies.size > 1) schemeFamilies[1] else editorFontFamily()
            }.getOrDefault(editorFontFamily())

        /** The user's login shell ($SHELL) on Unix; on Windows prefers PowerShell 7 (pwsh) when installed —
         *  it handles Unicode/emoji far better than the Windows PowerShell 5.1 that ships with every Windows
         *  (5.1 mangles emoji even with UTF-8 set). Falls back to PowerShell 5.1, then cmd.exe. */
        fun defaultShell(): String {
            if (isWindows) {
                val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                // PowerShell 7+ (MSI install) — best Unicode/emoji support. Not on every machine.
                val pwsh7 = "$programFiles\\PowerShell\\7\\pwsh.exe"
                if (java.io.File(pwsh7).exists()) return pwsh7
                val sysRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
                // Windows PowerShell 5.1 (built into every Windows 10/11).
                val ps = "$sysRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
                if (java.io.File(ps).exists()) return ps
                // Absolute last resort — cmd.exe lives at %ComSpec% or System32.
                System.getenv("ComSpec")?.takeIf { it.isNotBlank() }?.let { return it }
                return "$sysRoot\\system32\\cmd.exe"
            }
            return System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        }

        val isWindows: Boolean
            get() = System.getProperty("os.name").lowercase().contains("win")

        fun getInstance(): TermGlyphSettings =
            ApplicationManager.getApplication().getService(TermGlyphSettings::class.java)

    }
}
