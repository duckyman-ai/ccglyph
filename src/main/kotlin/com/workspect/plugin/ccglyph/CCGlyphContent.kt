package com.workspect.plugin.ccglyph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.workspect.plugin.ccglyph.launch.LaunchSpec
import com.workspect.plugin.ccglyph.launch.SessionLauncher
import com.workspect.plugin.ccglyph.profile.Profile
import com.workspect.plugin.ccglyph.profile.ProfileService
import javax.swing.Icon

/** Shared terminal-content builder + session counter + icon tables.
 *  Used by both the tool-window factory (new tab) and the native split-content provider (Split Right/Down),
 *  so every terminal — whether opened as a tab or a split pane — is wired the same way (process→tab title/icon
 *  swap, close-confirmation via PANEL_KEY, disposal) and draws its name from one shared counter. */
internal object CCGlyphContent {

    /** Terminal brand icon (blue→purple gradient, matches the tool-window stripe icon). */
    val TERMINAL_ICON: Icon = IconLoader.getIcon("/icons/ccglyph.svg", CCGlyphContent::class.java)

    /** Maps a docked Content → its TerminalBrowserPanel (used by the close-confirmation + repaint listeners). */
    val PANEL_KEY = Key.create<TerminalBrowserPanel>("ccglyph.panel")

    /** Each live terminal panel (tab or split pane) → its assigned slot number. Single source of truth for both
     *  "is any terminal still alive?" (the keys) and "which slot numbers are taken?" (the values). Weak-backed so a
     *  disposed panel frees its slot automatically if the explicit release is missed. */
    private val panelSlots = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<TerminalBrowserPanel, Int>()
    )

    /** True if at least one terminal panel is still alive somewhere (a tab or a split pane), optionally ignoring
     *  [excluding] (the panel currently being removed, which may still read as "live" at removal-event time).
     *  Used to tell a genuine "closed the last terminal" apart from a split's content-move — splitting empties the
     *  top-level content manager (contentCount → 0) while the panels stay alive in the split cells. */
    fun hasLivePanels(excluding: TerminalBrowserPanel? = null): Boolean =
        panelSlots.keys.any { it !== excluding && !it.isDisposed }

    /** Assign [panel] the lowest free slot and return its display name ("Local" for slot 1, otherwise "Local (n)").
     *  Freed slots are reused, so closing a tab and opening a new one reclaims its number instead of climbing forever
     *  (Local 3 → close → reopen → Local 3 again, not Local 4) — matches the built-in terminal. */
    fun assignSessionName(panel: TerminalBrowserPanel): String {
        val used = panelSlots.values.toHashSet()
        var slot = 1
        while (slot in used) slot++
        panelSlots[panel] = slot
        return if (slot == 1) "Local" else "Local ($slot)"
    }

    /** Free a panel's slot (on close/dispose). */
    fun releaseSlot(panel: TerminalBrowserPanel) = panelSlots.remove(panel)

    /** True while a native split is in progress (set by CCGlyphSplitContentProvider.createContentCopy, cleared on the
     *  next EDT tick). The close-confirmation veto (contentRemoveQuery) checks this to avoid popping the "terminate
     *  session" dialog when the split merely MOVES a running terminal's content into a cell (a real close sets this false). */
    @Volatile var splitting = false

    /** Build a fully-wired terminal Content (panel + process→tab title/icon swap + disposal). Does NOT add it to a
     *  content manager — the factory adds+selects it (for a new tab); the platform adds it (for a native split). */
    fun createContent(project: Project, disposable: Disposable, workDir: String, launchSpec: LaunchSpec? = null): Content {
        // A terminal is always a Claude Code session: with no explicit spec (e.g. a native split pane) fall back
        // to the last-used profile (or Default) — never leave it as a plain shell.
        val spec = launchSpec ?: defaultClaudeSpec(project, workDir)
        // NOTE: we no longer read TerminalProjectOptionsProvider.getShellPath() here — on 2025.3+/Android Studio
        // 2026.1 it's a blocking call that's FORBIDDEN on the EDT (throws IllegalStateException "This method is
        // forbidden on EDT"). The shell is resolved inside TerminalSession from CCGlyph's own setting, falling
        // back to the platform default — see TerminalSession.shellPath.
        val panel = TerminalBrowserPanel(disposable, workDir, null, spec)
        val fallbackTitle = assignSessionName(panel)
        val title = spec.tabTitle.takeIf { it.isNotBlank() } ?: fallbackTitle
        val content = ContentFactory.getInstance().createContent(panel.component, title, false)
        // Initial tab icon: the profile's icon (or claude) for profile sessions, else the brand icon.
        content.icon = iconFor(spec.icon) ?: TERMINAL_ICON
        // ⚠️ Tool window content tabs do not show icons by default — BaseLabel.updateTextAndIcon
        // checks SHOW_CONTENT_ICON first, otherwise it discards the icon via setIcon(null) → must opt in with this flag
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, java.lang.Boolean.TRUE)
        content.isCloseable = true     // → native × on the tab (on hover/active)
        content.putUserData(PANEL_KEY, panel)
        // Status-driven tab blink (the "blinking / alternating colours" effect) for bridge-backed sessions.
        val blinker = TabBlinker(content)
        panel.onStatus = { state -> blinker.setState(state) }
        // The tab's ICON follows the running process (claude → coral icon, etc.). The TITLE is driven by the app's
        // OSC escape (onTerminalTitle below) so it shows the app's own title (e.g. Claude Code's session title) like
        // the built-in terminal; when a process EXITS we reset the title to the session name (an app that emits no
        // OSC title keeps the session name throughout). Fires from the reader thread → marshal to the EDT.
        panel.onProcessChange = { processName ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || panel.isDisposed) return@invokeLater
                runCatching {
                    // Icon stays as the profile/claude icon set at creation — no auto-detect swapping.
                    if (processName == null) content.displayName = title   // process exited → title back to session name
                }
            }
        }
        // App-set terminal title (OSC 0/2) → tab name. e.g. Claude Code's session title (unless
        // CLAUDE_CODE_DISABLE_TERMINAL_TITLE). Blank → fall back to the session name.
        panel.onTerminalTitle = { osc ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || panel.isDisposed) return@invokeLater
                runCatching { content.displayName = osc.ifBlank { title } }
            }
        }
        content.setDisposer {
            blinker.dispose()
            panel.dispose()
            panelSlots.remove(panel)
        }
        return content
    }

    /** The spec used when a terminal is opened without an explicit profile — the last-used profile (or a fresh
     *  "Default"), launched as a Claude session. Shared by the new-tab path and the native split provider so a
     *  split pane is a Claude session, not a plain shell. */
    private fun defaultClaudeSpec(project: Project, workDir: String): LaunchSpec {
        val svc = ProfileService.getInstance()
        val profile = svc.lastUsed() ?: Profile().apply { id = svc.newId(); name = "Default" }
        return SessionLauncher.launch(profile, workDir, svc.state.injectBridgeByDefault)
    }

    /** Maps process name → tab icon. Lazy — each entry loaded on first access so a broken
     *  SVG doesn't prevent other icons from loading.  Null values fall back to TERMINAL_ICON. */
    private val iconMap = mutableMapOf<String, Icon?>()
    private fun iconFor(name: String): Icon? =
        iconMap.getOrPut(name) { ICON_PATHS[name]?.let { loadIcon(it) } }

    private fun loadIcon(path: String): Icon? = IconLoader.findIcon(path, CCGlyphContent::class.java)

    /** Process name → icon resource path. */
    private val ICON_PATHS: Map<String, String> = mapOf(
        "claude" to "/icons/claude-code.svg",
        "codex" to "/icons/codex.svg",
        "gemini" to "/icons/gemini-cli.svg",
        "vim" to "/icons/vim.svg",
        "nvim" to "/icons/vim.svg",
        "vi" to "/icons/vim.svg",
        "gradle" to "/icons/gradle.svg",
        "gradlew" to "/icons/gradle.svg",
        "mvn" to "/icons/maven.svg",
        "cargo" to "/icons/rust.svg",
        "docker" to "/icons/docker.svg",
        "docker-compose" to "/icons/docker.svg",
        "git" to "/icons/git.svg",
        "flutter" to "/icons/flutter.svg",
        "npm" to "/icons/npm.svg",
        "yarn" to "/icons/yarn.svg",
        "pnpm" to "/icons/yarn.svg",
        "adb" to "/icons/adb.svg",
        "python" to "/icons/python.svg",
        "python3" to "/icons/python.svg",
        "go" to "/icons/go.svg",
        "node" to "/icons/nodejs.svg",
        "agy" to "/icons/antigravity.svg",
    )

    /** Animates a tool-window tab's colour to reflect the Claude session state (the
     *  "blinking / alternating colour" effect). Idle clears the colour; busy/waiting blink. */
    private class TabBlinker(private val content: Content) {
        private val timer = javax.swing.Timer(450) { tick() }
        private var on = false
        @Volatile private var state: com.workspect.plugin.ccglyph.status.ClaudeState =
            com.workspect.plugin.ccglyph.status.ClaudeState.IDLE

        fun setState(newState: com.workspect.plugin.ccglyph.status.ClaudeState) {
            state = newState
            if (newState.isBusy || newState.isWaiting) {
                if (!timer.isRunning) { on = false; timer.start() }
            } else {
                if (timer.isRunning) timer.stop()
                content.setTabColor(null)
            }
        }

        private fun colors(): Pair<java.awt.Color, java.awt.Color>? = when (state) {
            com.workspect.plugin.ccglyph.status.ClaudeState.THINKING ->
                java.awt.Color(0x4c, 0x1d, 0x95) to java.awt.Color(0x1e, 0x3a, 0x8a)   // purple ↔ blue
            com.workspect.plugin.ccglyph.status.ClaudeState.TOOL_RUNNING ->
                java.awt.Color(0x1e, 0x3a, 0x8a) to java.awt.Color(0x0e, 0x74, 0x9e)   // blue ↔ cyan
            com.workspect.plugin.ccglyph.status.ClaudeState.WAITING_PERMISSION,
            com.workspect.plugin.ccglyph.status.ClaudeState.WAITING_INPUT ->
                java.awt.Color(0xb4, 0x53, 0x09) to java.awt.Color(0x42, 0x3a, 0x06)   // amber blink
            else -> null
        }

        private fun tick() {
            val (a, b) = colors() ?: return
            content.setTabColor(if (on) a else b)
            on = !on
        }

        fun dispose() { timer.stop() }
    }
}
