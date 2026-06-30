package com.duckyman.plugin.ccglyph

import com.duckyman.plugin.ccglyph.launch.LaunchSpec
import com.duckyman.plugin.ccglyph.launch.SessionLauncher
import com.duckyman.plugin.ccglyph.profile.NewSessionChoice
import com.duckyman.plugin.ccglyph.profile.NewSessionPopup
import com.duckyman.plugin.ccglyph.profile.ProfileService
import com.intellij.CommonBundle
import com.intellij.execution.ExecutionBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.GeneralSettings
import com.intellij.ide.IdeCoreBundle
import com.intellij.ide.ProcessCloseConfirmation
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Creates the CCGlyph ToolWindow — one switchable **tab per terminal session**, exactly like the built-in
 * terminal. The IDE's own `contentManager` renders the single tab strip (native look, no custom tabs, no double
 * row): the "+" beside the tabs opens a new terminal tab, the × closes one, and the tab's name/icon follows the
 * running process (e.g. "Claude Code" with its icon while claude runs). CWD of each terminal = the project folder.
 *
 * Native side-by-side split (Split Right / Split Down, etc.) is provided by `CCGlyphSplitContentProvider`
 * (registered via the `com.intellij.toolWindow.splitContentProvider` extension point) — the platform's own split
 * actions, in the tab's right-click context menu, exactly like the built-in terminal.
 */
class CCGlyphTerminalFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // JCEF is an optional dependency — if the Web Browser (JCEF) plugin is not installed
        // (common on Android Studio / Community Edition), show a helpful banner instead of crashing.
        if (!JBCefApp.isSupported()) {
            showJcefMissing(toolWindow)
            return
        }

        val ex = toolWindow as ToolWindowEx
        toolWindow.stripeTitle = "Claude Code Glyph"
        // Enable the platform's native split + tab drag-reorder for this tool window. The split actions
        // (TW.SplitRight/Down/Unsplit) are added to the tab context menu only when isTabsReorderingAllowed,
        // which requires the ALLOW_DND_FOR_TABS client property AND the "ide.allow.split.and.reorder.in.tool.window"
        // registry flag (on by default on 2024.2+). Our CCGlyphSplitContentProvider does the rest. Without this
        // line, "Split Right/Down" never appears in the right-click menu even though the provider is registered.
        ToolWindowContentUi.setAllowTabsReordering(toolWindow, true)
        // "+" beside the tabs opens a new terminal TAB (switchable, like the built-in terminal) — placed inline
        // in the content UI's tab strip via setTabActions (NOT setTitleActions, which puts it in the title bar).
        // Must be called on the EDT — createToolWindowContent is already on the EDT.
        ex.setTabActions(NewTerminalAction(toolWindow, project), ProfilesButton(toolWindow, project))
        // Gear (⋮) menu → "Settings..." (opens the CCGlyph settings page). Profile picking lives on the
        // header ProfilesButton now, so it's not duplicated here.
        ex.setAdditionalGearActions(DefaultActionGroup().apply {
            add(SettingsAction())
        })

        // Always keep ≥1 terminal: when all are closed, open a new one immediately (prevents it vanishing from the sidebar)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            // Before closing a tab: if a process is running (claude/vim/...), ask for confirmation first — cancel = veto (consume)
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                confirmCloseIfRunning(event, project)
            }
            override fun contentRemoved(event: ContentManagerEvent) {
                // Auto-reopen the last terminal so the tool window never goes empty — BUT only when no terminal panel is
                // left ANYWHERE. Splitting moves contents out of the top-level content manager into per-pane cells, so its
                // contentCount drops to 0 on every split; checking contentCount alone would wrongly spawn a phantom
                // "Local" tab each time. Checking live panels instead distinguishes a real "closed the last terminal"
                // from a split reorg. On a genuine last-close, free the closed panel's slot first so the reopen is "Local".
                val removed = event.content.getUserData(CCGlyphContent.PANEL_KEY)
                if (!CCGlyphContent.hasLivePanels(excluding = removed)) {
                    if (removed != null) CCGlyphContent.releaseSlot(removed)
                    // Reopen on the EDT and never let a failure here crash the tool window — e.g. if the shell
                    // can't launch on this machine, creating the terminal throws. Swallow it; the user can still
                    // open one via the "+" button, and the tab-close itself completes cleanly.
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        // Reopen as a Claude session (the plugin's purpose), NOT the user's "+" preference —
                        // otherwise closing the last tab reopens a plain shell and the tool window then sits
                        // on an empty shell instead of Claude the next time it's opened. Plain shells stay
                        // an explicit choice via the "+" button (see plusSpec).
                        val reopenWorkDir = project.basePath ?: System.getProperty("user.home")
                        runCatching { createTerminalContent(toolWindow.contentManager, toolWindow, project, CCGlyphContent.defaultClaudeSpec(project, reopenWorkDir)) }
                    }
                }
            }
            override fun selectionChanged(event: ContentManagerEvent) {
                // When a tab becomes selected, force a repaint — JCEF drops the occluded canvas surface while a tab is
                // in the background, so the screen looks stale until a paint is forced (the "blank until I click back" symptom).
                val panel = event.content.getUserData(CCGlyphContent.PANEL_KEY) ?: return
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !panel.isDisposed) panel.refresh()
                }
            }
        })

        // Repaint the selected terminal when the IDE window regains focus — same canvas-dropped-while-backgrounded
        // reason as selectionChanged, but for the "switch away from the IDE and back" case.
        WindowManager.getInstance().getFrame(project)?.addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent) = refreshSelected(toolWindow)
        })

        // The FIRST tab lands on a Claude Code session (the plugin's purpose) — last-used profile or Default.
        // Subsequent "+" taps open plain shells; see createTerminalContent.
        val workDir = project.basePath ?: System.getProperty("user.home")
        createTerminalContent(toolWindow.contentManager, toolWindow, project, CCGlyphContent.defaultClaudeSpec(project, workDir))
    }

    /** Show a banner when JCEF is missing (Android Studio / Community Edition without the JCEF plugin). */
    private fun showJcefMissing(toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())
        val text = """
            <html><body style='padding:24px;font-family:sans-serif;color:#bbb'>
            <h2 style='color:#999;font-weight:normal'>JCEF Required</h2>
            <p>CCGlyph needs the <b style='color:#ddd'>Web Browser (JCEF)</b> plugin to render the terminal.</p>
            <p>Open <b>Settings → Plugins → Marketplace</b>, search for <b>"Web Browser (JCEF)"</b>,<br/>
            install it, then <b>restart the IDE</b>.</p>
            <p style='color:#666;font-size:small;margin-top:24px'>Plugin ID: com.intellij.modules.jcef</p>
            """.trimIndent()
        panel.add(JLabel(text), BorderLayout.CENTER)
        val content = ContentFactory.getInstance().createContent(panel, "CCGlyph", false)
        content.isCloseable = false
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, java.lang.Boolean.TRUE)
        content.icon = CCGlyphContent.TERMINAL_ICON
        toolWindow.contentManager.addContent(content)
    }

    /** What the "+" / in-tab new-tab paths open — the user's preference (a Claude session by default,
     *  or a plain shell). The FIRST tab AND the auto-reopen (closing the last tab) always open a Claude
     *  session regardless (see createToolWindowContent + contentRemoved), as do explicit popup specs. */
    private fun plusSpec(project: Project, workDir: String): LaunchSpec =
        if (CCGlyphSettings.getInstance().state.plusOpensPlainShell) SessionLauncher.plainShell(workDir)
        else CCGlyphContent.defaultClaudeSpec(project, workDir)

    /** Opens a new terminal as a **switchable tab** in the given [contentManager] and selects it
     *  (the IDE renders the tab strip).  When called from the tool-window "+", [contentManager] is
     *  the one currently focused (from `PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER`), so a "+" click
     *  inside a split cell adds a terminal to that cell's tab strip, not the top-level one. */
    private fun createTerminalContent(contentManager: ContentManager, toolWindow: ToolWindow, project: Project, launchSpec: LaunchSpec? = null) {

        // Initial CWD = the project folder; Light projects without a base path → fall back to the user's home
        val workDir = project.basePath ?: System.getProperty("user.home")
        // No explicit spec = a quick terminal → the user's "+" preference (Claude session by default, or a
        // plain shell). The first tab and explicit popup/profile specs bypass this. (Native splits call
        // createContent directly with null → a Claude session — see CCGlyphContent.defaultClaudeSpec.)
        val spec = launchSpec ?: plusSpec(project, workDir)
        val content = CCGlyphContent.createContent(project, toolWindow.disposable, workDir, spec)
        // Wire the context-menu "New Tab" / "Close Tab" actions (the factory owns the tool window; the panel doesn't).
        content.getUserData(CCGlyphContent.PANEL_KEY)?.let { panel ->
            panel.onNewTab = { createTerminalContent(contentManager, toolWindow, project) }
            panel.onCloseTab = {
                ApplicationManager.getApplication().invokeLater {
                    // Remove from whatever manager actually owns this content (a split pane's manager, or the
                    // top-level) — removing from the top-level when the tab lives in a split pane would no-op.
                    if (!project.isDisposed) content.manager?.removeContent(content, true)
                }
            }
        }
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)

        // Force a layout pass on the next turn — JCEF components (heavyweight) sometimes don't get their full size until a
        // resize event fires, which makes xterm fit to an overly narrow size on first open; revalidate immediately to get the real size
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                content.component.revalidate()
                content.component.repaint()
            }
        }
    }

    /** Before closing a tab, if a descendant process is running in it → ask the user; cancel = a veto via event.consume(). */
    private fun confirmCloseIfRunning(event: ContentManagerEvent, project: Project) {
        // A native split moves the content into a cell (firing contentRemoveQuery) — that's not a close, so don't prompt.
        if (CCGlyphContent.splitting) return
        val panel = event.content.getUserData(CCGlyphContent.PANEL_KEY) ?: return
        if (!shouldTerminate(project, panel, event.content.displayName)) event.consume()
    }

    /** Force a repaint of the currently-selected terminal tab (used when the IDE window regains focus). */
    private fun refreshSelected(toolWindow: ToolWindow) {
        val panel = toolWindow.contentManager.selectedContent?.getUserData(CCGlyphContent.PANEL_KEY) ?: return
        if (!panel.isDisposed) panel.refresh()
    }

    /** Returns true if it's OK to terminate the terminal's running process (idle, or the user confirmed Terminate);
     *  false to veto. Shows the standard terminate dialog ONLY when a process is running AND the user hasn't already
     *  ticked "Don't ask again". Dialog matches the built-in terminal / Run config (mimics TerminateRemoteProcessDialog):
     *  title/text/button from ExecutionBundle, "Don't ask again" checkbox wired to GeneralSettings.processCloseConfirmation. */
    private fun shouldTerminate(project: Project, panel: TerminalBrowserPanel, displayName: String): Boolean {
        // Avoid prompting during tests/headless or after the project is closed → always allow (don't block shutdown)
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment || project.isDisposed) return true
        if (panel.runningProcesses().isEmpty()) return true   // idle → close without asking
        // User previously ticked "Don't ask again" (an IDE-wide setting) → don't ask, just close
        val gs = GeneralSettings.getInstance()
        if (gs.processCloseConfirmation != ProcessCloseConfirmation.ASK) return true
        val name = "Terminal $displayName"
        val title = ExecutionBundle.message("process.is.running.dialog.title", name)
        val terminate = ExecutionBundle.message("button.terminate")
        val cancel = CommonBundle.message("button.cancel")
        // Two-line message (HTML): the question on top, the session name on its own bottom line (bold).
        // No nameless bundle key exists, so the question is hardcoded English (the title stays localised).
        val safeName = displayName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val message = "<html>Do you want to terminate the process?<br><b>$safeName</b></html>"
        var dontAsk = false
        val option = object : com.intellij.openapi.ui.DoNotAskOption {
            override fun isToBeShown(): Boolean = true              // checkbox is unticked by default
            override fun canBeHidden(): Boolean = true
            override fun shouldSaveOptionsOnCancel(): Boolean = false
            override fun getDoNotShowMessage(): String = IdeCoreBundle.message("dialog.options.do.not.ask")
            override fun setToBeShown(toBeShown: Boolean, exitCode: Int) {
                if (!toBeShown) dontAsk = true                       // ticked = remember the choice after the dialog closes
            }
        }
        // Use the SAME Messages.showDialog(...) overload the IDE's TerminateRemoteProcessDialog uses, so the
        // dialog is identical to the built-in terminal's confirm (title/message/icon + the window chrome, incl.
        // the close button). Verified non-deprecated on 2025.3 (the top-level DoNotAskOption variant).
        val rc = Messages.showDialog(project, message, title, arrayOf(terminate, cancel), 0, Messages.getWarningIcon(), option)
        // rc == 0 → Terminate was clicked; anything else (Cancel = 1, or window close) → veto (keep the tab).
        if (rc == 0) {
            if (dontAsk) gs.processCloseConfirmation = ProcessCloseConfirmation.TERMINATE
            return true
        }
        return false
    }

    // JCEF is an optional dependency. The tool window always shows; if JCEF is missing
    // we display a helpful banner instead of a blank panel.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun isApplicable(project: Project): Boolean = true

    /** The "+" beside the tabs → opens a new terminal TAB (a plain shell), matching the built-in terminal's
     *  "+" convention. A Claude Code session is launched via the Profiles button (▼) beside it.
     *  Uses the focused `ContentManager` from the `AnActionEvent` data context, so when the tool window
     *  is SPLIT into multiple panes the "+" adds a new terminal to the pane the user actually clicked in
     *  (each split cell has its own `ContentManager`). Falls back to the tool-window-wide CM. */
    private inner class NewTerminalAction(val toolWindow: ToolWindow, val project: Project) :
        AnAction("New Terminal", "Open a new terminal (plain shell)", AllIcons.General.Add) {

        override fun actionPerformed(e: AnActionEvent) {
            // Use the focused CM from the data context — in a split this returns the *cell's* CM,
            // not the tool-window-global one. Falls back to the global CM if no context is available.
            val cm = e.getData(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER)
                ?: toolWindow.contentManager
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) runCatching { createTerminalContent(cm, toolWindow, project) }
            }
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** Header button beside "+": a popup listing every profile to start a session with, plus a separator
     *  and "Manage Profiles…" at the bottom (opens the settings page). Reuses [NewSessionPopup] (same
     *  list the gear menu shows) but anchors the popup just under this button.
     *  Resolves the focused [ContentManager] from the event context so a new session lands in the
     *  correct split pane. */
    private inner class ProfilesButton(val toolWindow: ToolWindow, val project: Project) :
        AnAction("Profiles", "Start a Claude session, a plain terminal, or manage profiles", AllIcons.General.ChevronDown) {

        override fun actionPerformed(e: AnActionEvent) {
            // Resolve the focused CM synchronously while the event context is valid.
            val cm = e.getData(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER) ?: toolWindow.contentManager
            NewSessionPopup.show(project, anchor = e.inputEvent?.component) { choice ->
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val dir = project.basePath ?: System.getProperty("user.home")
                    val spec = when (choice) {
                        NewSessionChoice.PlainTerminal -> SessionLauncher.plainShell(dir)
                        is NewSessionChoice.ProfileSession ->
                            SessionLauncher.launch(choice.profile, dir, ProfileService.getInstance().state.injectBridgeByDefault)
                    }
                    runCatching { createTerminalContent(cm, toolWindow, project, spec) }
                }
            }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** "Settings..." in the gear menu (⋮) → opens Settings → Tools → CCGlyph directly */
    private class SettingsAction :
        AnAction("Settings...", "Open CCGlyph terminal settings", AllIcons.General.GearPlain) {

        override fun actionPerformed(e: AnActionEvent) {
            // Open the CCGlyph settings page directly by the configurable's class (id="ccglyph.settings", in the Tools group)
            ShowSettingsUtil.getInstance().showSettingsDialog(
                e.project, CCGlyphSettingsConfigurable::class.java
            )
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
