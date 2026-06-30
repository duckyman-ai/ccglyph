package com.duckyman.plugin.ccglyph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.execution.process.ConsoleHighlighter
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.KeyStroke
import com.duckyman.plugin.ccglyph.status.ClaudeState
import com.duckyman.plugin.ccglyph.status.StatusController
import com.duckyman.plugin.ccglyph.status.StatusSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TerminalBrowserPanel(parentDisposable: Disposable, workDir: String, shellPath: String? = null, private val launchSpec: com.duckyman.plugin.ccglyph.launch.LaunchSpec? = null) {

    private val disposable: Disposable = parentDisposable
    private val browser = JBCefBrowser()
    /** Flowing gradient beam, painted as a SWING overlay over the browser's top edge — NOT a CSS animation.
     *  JCEF OSR has no GPU compositor, so a CSS beam would force a Chromium frame every tick and lag xterm.
     *  Driven from applyStatusToJs() (same state the chip/tab use). See BeamOverlay. */
    private val beam = BeamOverlay()
    private var session: TerminalSession

    // --- output batching ---
    // Instead of calling executeJavaScript per chunk (crossing the JCEF boundary each time = the root
    // cause of stuttering during heavy scroll/output), we accumulate output into a buffer and flush it
    // in one of two ways: small echo chunks (≤80 chars) are flushed on the next EDT tick so typing feels
    // instant; larger streaming chunks are batched via an Alarm (~8ms) to avoid flooding JCEF.
    // The Alarm is NOT tied to parentDisposable → we dispose it ourselves in dispose() only after
    // setting the flag (to prevent the platform disposing the Alarm concurrently with the reader
    // thread, which would cause addRequest to hit an already-disposed Alarm).
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    private val outBuf = StringBuilder()
    private val outLock = Any()
    @Volatile private var flushPending = false
    @Volatile private var urgentFlushPending = false
    @Volatile private var disposed = false

    // --- process detection (output-triggered, used to switch the tab icon + title) ---
    // When shell output flows in → check the process tree for known tools (DETECT_PRIORITY — first match wins);
    // only invoke the callback on a state change. CCGlyphContent wires the callback: the icon comes from
    // iconFor() (null = idle → the session's creation icon) and the title from the app's OSC title (onTerminalTitle).
    @Volatile private var lastCheckMs: Long = 0L
    @Volatile private var currentProcess: String? = null
    var onProcessChange: ((String?) -> Unit)? = null

    /** Fired when the running program emits an OSC 0/2 "set title" escape (e.g. Claude Code's session title,
     *  unless CLAUDE_CODE_DISABLE_TERMINAL_TITLE is set). Kotlin wires this to the tab's displayName so the tab
     *  shows the app's own title, like the built-in terminal. */
    var onTerminalTitle: ((String) -> Unit)? = null

    /** Context-menu "New Tab" / "Close Tab" hooks (wired by the factory, which owns the tool window). Null on a
     *  split pane → the menu items are no-ops there. */
    var onNewTab: (() -> Unit)? = null
    var onCloseTab: (() -> Unit)? = null

    /** Fired once when the PTY process exits (claude Ctrl+C quit / crash / manual destroy). Wired by CCGlyphContent
     *  to auto-close the tab. Fires on the reader thread → the handler marshals to the EDT. */
    var onExit: (() -> Unit)? = null

    /** Status callback for the tab blinker (wired by CCGlyphContent); null on non-bridged sessions. */
    var onStatus: ((ClaudeState) -> Unit)? = null
    private var statusController: StatusController? = null
    @Volatile private var warnedHighCtx = false
    @Volatile private var warnedRate = false
    /** Last Claude state seen from the bridge — used to re-drive the tab blinker immediately on a settings
     *  toggle (StatusController.onUpdate is change-gated, so without this the tab colour wouldn't update
     *  until the next real state change). Null on non-bridged sessions. */
    @Volatile private var lastStatusState: ClaudeState? = null
    /** Last status snapshot (model/ctx) — kept so an optimistic IDLE flip (typing during WAITING) can keep
     *  the chip's model/context content instead of blanking it. */
    @Volatile private var lastSnapshot: StatusSnapshot? = null
    /** Optimistic-clear latch: once the user starts typing during a WAITING state we push IDLE locally and
     *  suppress WAITING re-application (from snap-only updates) until a non-WAITING state arrives, which
     *  clears this. See inputQuery + StatusController.onUpdate. */
    @Volatile private var waitingDismissed = false

    // --- live-reload: settings / theme changes push to xterm immediately ---
    // On a Settings change also re-apply the current status, so a chip/beam/tab toggle takes effect at once
    // pushConfigToJs refreshes the JS-driven chip (+ its flags). The beam (Swing BeamOverlay) and the tab colour
    // (Swing TabBlinker) are NOT in JS — re-drive them here from the last state; each re-reads its own Settings
    // flag (beamEnabled / tabColorEnabled) so a live toggle takes effect at once.
    private val settingsListener = Runnable {
        pushConfigToJs()
        lastStatusState?.let { state ->
            onStatus?.invoke(state)
            beam.setBeamState(state, (lastSnapshot?.contextPct ?: 0) >= 80)
        }
    }
    val isDisposed: Boolean get() = disposed

    // JS → Kotlin: receive keyboard input from xterm.js.
    // Cast to JBCefBrowserBase: the create(JBCefBrowser) overload is @Deprecated(forRemoval=true);
    // resolve to the non-deprecated create(JBCefBrowserBase) overload (JBCefBrowser extends it).
    private val inputQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler { input ->
            session.write(input)
            // Optimistic clear: the WAITING effect is an attention signal, so once the user starts responding
            // (any input keystroke / paste) flip to IDLE locally instead of waiting for the UserPromptSubmit /
            // permission-decision hook. Suppressed again on the next real (non-WAITING) state — see onUpdate.
            if (input.isNotEmpty() && !waitingDismissed && lastStatusState?.isWaiting == true) {
                waitingDismissed = true
                val snap = lastSnapshot
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) {
                        applyStatusToJs(ClaudeState.IDLE, snap)
                        onStatus?.invoke(ClaudeState.IDLE)
                    }
                }
            }
            null
        }
    }

    // JS → Kotlin: receive resize events from xterm.js (see inputQuery re: JBCefBrowserBase cast)
    private val resizeQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler { size ->
            runCatching {
                val parts = size.split(",")
                session.resize(parts[0].trim().toInt(), parts[1].trim().toInt())
            }
            null
        }
    }

    // JS → Kotlin: copy the xterm selection to the system clipboard. Needed because the WebGL renderer paints to a
    // canvas, so the browser can't read the selected text natively (Cmd+C grabbed nothing / partial DOM text). We read
    // xterm's own selection (term.getSelection(), the full highlighted span) and put it on the clipboard here.
    private val copyQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler { text ->
            runCatching {
                val selection = java.awt.datatransfer.StringSelection(text)
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
            null
        }
    }

    // JS → Kotlin: paste from the system clipboard into the terminal (the Paste menu / Ctrl+V). The canvas can't read
    // the clipboard natively, so we read it here and push it to xterm via term.paste(). JS escapes via our jsEscape.
    private val pasteQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler {
            runCatching {
                val text = (java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String).orEmpty()
                if (text.isNotEmpty()) {
                    val escaped = jsEscape(text)
                    browser.cefBrowser.executeJavaScript(
                        "try { window.term.paste(\"$escaped\"); } catch(e){}", browser.cefBrowser.url, 0
                    )
                }
            }
            null
        }
    }

    // JS → Kotlin: right-click → show a native Swing context menu at the click point (Copy/Paste/Clear).
    // A Swing JPopupMenu is themed by the IDE automatically (looks native), unlike an in-page HTML menu.
    private val menuQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler { coords ->
            val p = coords.split(",")
            val x = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val y = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) showContextMenu(x, y)
            }
            null
        }
    }

    // JS → Kotlin: receive OSC title changes (term.onTitleChange) so the tab can show the running app's own title.
    // Only honoured while a known tool is running (e.g. Claude Code's session title) — the shell also emits a title
    // (the current path), which would clutter the tab, so it's ignored and the tab stays at the session name when idle.
    private val titleQuery = JBCefJSQuery.create(browser as JBCefBrowserBase).apply {
        addHandler { title ->
            if (currentProcess != null) runCatching { onTerminalTitle?.invoke(title) }
            null
        }
    }

    init {
        // Whether the editor scheme is dark → passed to the PTY env (COLORFGBG) so TUI apps pick the right theme.
        val darkBg = isDarkColor(EditorColorsManager.getInstance().globalScheme.defaultBackground)
        session = TerminalSession({ output -> appendOutput(output) }, workDir, darkBg, shellPath, launchSpec) { onExit?.invoke() }

        // inject sendInput and onTerminalResize after the page finishes loading.
        // Also removes the loading overlay (#tg-loading) injected by extractWebResources.
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, f: CefFrame, httpStatusCode: Int) {
                // Show the beam strip only once the terminal page has loaded — otherwise it paints the terminal
                // bg colour over the still-blank loading area and reads as a black line during the pre-load moment.
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) { beam.isVisible = true; root.revalidate() }
                }
                browser.cefBrowser.executeJavaScript("""
                    window.sendInput = function(data) {
                        ${inputQuery.inject("data")}
                    };
                    window.onTerminalResize = function(cols, rows) {
                        ${resizeQuery.inject("cols + ',' + rows")}
                    };
                    // Copy bridge: tgCopySelection() reads xterm's full selection and ships it to Kotlin → clipboard.
                    window.tgCopy = function(text) {
                        ${copyQuery.inject("text")}
                    };
                    window.tgCopySelection = function() {
                        try {
                            // Prefer the live selection; fall back to the last one remembered (right-click can clear the live one).
                            var sel = (window.term && window.term.hasSelection()) ? window.term.getSelection() : tgLastSelection;
                            if (sel) window.tgCopy(sel);
                        } catch (e) {}
                    };
                    // Paste from the system clipboard into the terminal (Paste menu).
                    window.tgRequestPaste = function() {
                        ${pasteQuery.inject("")}
                    };
                    // Right-click → native Swing context menu at the click point (Copy/Paste/Clear).
                    window.tgShowMenu = function(x, y) {
                        ${menuQuery.inject("x + ',' + y")}
                    };
                    // OSC title bridge: term.onTitleChange → Kotlin (updates the tab's displayName).
                    window.onTerminalTitle = function(t) {
                        ${titleQuery.inject("t")}
                    };
                    // Flush the current size to the PTY immediately — the inline script runs fit()
                    // before onLoadEnd injects this bridge, which causes the first resize to be dropped
                    // (PTY stuck at 80×24); flushing here lets the shell/SIGWINCH draw the prompt/TUI
                    // at the real size, instead of "not right-aligned / missing the bottom section
                    // until the user manually resizes".
                    if (window.tgResize) window.tgResize();
                """.trimIndent(), browser.cefBrowser.url, 0)
            }
        }, browser.cefBrowser)

        // Extract web resources (terminal.html + the various xterm files) to a temp dir, then load via file://.
        // Using jar:// directly makes relative script src in JCEF fail to resolve → blank page.
        // Extract off the EDT (involves ~1.3MB of file I/O), then loadURL on the EDT → prevents UI
        // freezing while opening a tab.
        ApplicationManager.getApplication().executeOnPooledThread {
            val htmlFile = extractWebResources()
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) runCatching { browser.loadURL(htmlFile.toURI().toString()) }
            }
        }

        // Register Ctrl+C (SIGINT) and Cmd/Ctrl+K (clear) as component-scoped shortcuts
        // → when the terminal is focused, IntelliJ will capture these before global actions (e.g. Cmd+K = Commit).
        registerShortcuts()

        // Live-reload: when settings are applied (Settings → Tools → CCGlyph) or the IDE
        // editor color scheme changes, push the new config to xterm immediately.
        CCGlyphSettings.getInstance().addListener(settingsListener)
        ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
            EditorColorsManager.TOPIC,
            object : EditorColorsListener {
                override fun globalSchemeChange(scheme: EditorColorsScheme?) { pushConfigToJs() }
            }
        )

        // Bridge-backed session? Poll its status dir and drive the beam/chip + tab effects.
        val spec = launchSpec
        if (spec?.hasBridge == true) {
            statusController = StatusController(spec.sessionId, spec.stateDir).apply {
                onUpdate = { state, snap ->
                    // While the WAITING effect is optimistically dismissed (user started typing), ignore
                    // WAITING re-fires (e.g. a snap-only update) — stay cleared until a real non-WAITING
                    // state arrives, which also releases the latch.
                    if (!disposed && !(state.isWaiting && waitingDismissed)) {
                        if (!state.isWaiting) waitingDismissed = false
                        lastStatusState = state
                        if (snap != null) lastSnapshot = snap
                        applyStatusToJs(state, snap)
                        onStatus?.invoke(state)
                        snap?.let { checkThresholds(it) }
                    }
                }
                start()
            }
        }
    }

    /** Browser in CENTER, beam in NORTH (its own 6px strip). The beam CANNOT overlay the browser: this build uses
     *  IntelliJ's REMOTE JCEF (com.jetbrains.cef.remote) whose browser surface is heavyweight, so a lightweight
     *  Swing component painted over it is always hidden behind it — an overlapping beam was never visible. A NORTH
     *  strip is a separate region (no overlap → renders fine) and stays pure-Swing → no Chromium frames → no xterm
     *  lag. The strip is always present (6px); it clears to transparent when idle. */
    private val root = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        isOpaque = false
        add(browser.component, java.awt.BorderLayout.CENTER)
        add(beam, java.awt.BorderLayout.NORTH)
    }

    val component: JComponent get() = root

    /** Register component-scoped keyboard shortcuts (override the global keymap when the terminal is focused).
     *  IDE actions call tgClear()/tgCtrlC() instead of relying on a JS keydown handler
     *  (the JS handler doesn't work when the IDE steals the keystroke before it reaches JCEF, e.g. Cmd+K = Commit). */
    private fun registerShortcuts() {
        val clearAction = object : AnAction("Clear CCGlyph Terminal") {
            override fun actionPerformed(e: AnActionEvent) = clearScreen()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        clearAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.META_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK), null),
            ),
            browser.component, disposable
        )
        // Ctrl+C is "smart" (matches the IDE terminal): copy the selection if one exists, else send SIGINT.
        // The decision is made in JS (window.tgCtrlC) so both this component-scoped shortcut and the JS keydown
        // handler share one source of truth — see tgCtrlC in terminal.html.
        val interruptAction = object : AnAction("Copy or Interrupt CCGlyph Terminal (Ctrl+C)") {
            override fun actionPerformed(e: AnActionEvent) = ctrlC()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        interruptAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), null)),
            browser.component, disposable
        )
        // Copy the selection: Cmd+C (mac) / Ctrl+Shift+C (Win/Linux). The WebGL renderer paints to a canvas so the
        // browser can't copy the selected text itself; this routes xterm's full selection to the clipboard (see copyQuery).
        // (Ctrl+C without Shift stays SIGINT, above.) No selection → no-op.
        val copyAction = object : AnAction("Copy CCGlyph Selection") {
            override fun actionPerformed(e: AnActionEvent) = copySelection()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        copyAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), null),
                KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), null
                ),
            ),
            browser.component, disposable
        )
        // Paste: Cmd+V (mac) / Ctrl+V (Win/Linux). The canvas can't read the clipboard, so this routes through
        // requestPaste() (same path as the right-click Paste menu). Also handled in JS (terminal.html) as a fallback
        // in case the IDE grabs the keystroke before the component-scoped shortcut fires.
        val pasteAction = object : AnAction("Paste to CCGlyph Terminal") {
            override fun actionPerformed(e: AnActionEvent) = requestPaste()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        pasteAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.META_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK), null),
            ),
            browser.component, disposable
        )
        // Find in terminal: Cmd+F (mac) / Ctrl+F. The IDE grabs Find as a global action, so this component-scoped
        // shortcut takes over when the terminal is focused → toggles the xterm search bar (window.tgFindToggle).
        val findAction = object : AnAction("Find in CCGlyph Terminal") {
            override fun actionPerformed(e: AnActionEvent) = findToggle()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        findAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), null),
            ),
            browser.component, disposable
        )
        // Shift+Enter → insert a newline (LF) for multi-line input in apps like Claude Code. Registered
        // component-scoped so IntelliJ sends LF here instead of the IDE grabbing Shift+Enter.
        val newlineAction = object : AnAction("New line (Shift+Enter)") {
            override fun actionPerformed(e: AnActionEvent) = sendNewline()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }
        newlineAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), null)),
            browser.component, disposable
        )
    }

    /** Toggle the in-terminal find bar (Cmd+F / Ctrl+F). */
    fun findToggle() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgFindToggle && window.tgFindToggle();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Copy the current xterm selection to the system clipboard (Cmd+C / Ctrl+Shift+C). No-op if nothing is selected. */
    fun copySelection() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgCopySelection && window.tgCopySelection();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Paste the system clipboard into the terminal (Paste menu / context menu). */
    fun requestPaste() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgRequestPaste && window.tgRequestPaste();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Show a native (action-system) context menu at the given point — NewUI styling (rounded, padded, icons).
     *  Built via ActionManager so it matches the IDE's own popup menus, instead of a raw JPopupMenu. */
    private fun showContextMenu(x: Int, y: Int) {
        val group = DefaultActionGroup().apply {
            add(menuAction("Find…", "Find in terminal", AllIcons.Actions.Find) { findToggle() })
            add(menuAction("New Tab", "Open a new terminal tab", AllIcons.General.Add) { onNewTab?.invoke() })
            add(menuAction("Close Tab", "Close this terminal", AllIcons.Actions.Close) { onCloseTab?.invoke() })
            addSeparator()
            add(menuAction("Copy", "Copy selection", AllIcons.Actions.Copy) { copySelection() })
            add(menuAction("Paste", "Paste from clipboard", AllIcons.Actions.MenuPaste) { requestPaste() })
            add(menuAction("Clear", "Clear the terminal", AllIcons.Actions.Refresh) { clearScreen() })
        }
        val popup = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionPopupMenu("CCGlyphTerminalContext", group)
        popup.setTargetComponent(browser.component)
        popup.component.show(browser.component, x, y)
    }

    /** A simple menu action with an icon (used by the right-click context menu). */
    private fun menuAction(text: String, desc: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, desc, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    /** Called by the IDE action (Cmd/Ctrl+K) → clear the terminal like Terminal.app / iTerm2. */
    fun clearScreen() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgClear && window.tgClear();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Smart Ctrl+C (matches the IDE terminal): if xterm has a selection, copy it; otherwise send SIGINT.
     *  Delegates to window.tgCtrlC in terminal.html so the JS keydown handler and this action stay in sync. */
    fun ctrlC() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgCtrlC && window.tgCtrlC();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Shift+Enter → send a newline (LF) so apps like Claude Code insert a line break instead of submitting. */
    fun sendNewline() {
        session.write("\n")
    }

    /** Force a full repaint of the terminal canvas.
     *  Called when the panel regains focus/selection (see CCGlyphTerminalFactory) — JCEF/Chromium drops
     *  the occluded canvas surface while the tab/window is backgrounded, so the screen looks frozen/stale
     *  until a paint is forced. Drives window.tgRefresh in terminal.html. */
    fun refresh() {
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgRefresh && window.tgRefresh();", browser.cefBrowser.url, 0
            )
        }
    }

    /** Push a Claude state + status snapshot to the in-terminal effects (gradient beam + status chip). */
    fun applyStatusToJs(state: ClaudeState, snapshot: StatusSnapshot?) {
        if (disposed) return
        val highCtx = (snapshot?.contextPct ?: 0) >= 80
        beam.setBeamState(state, highCtx)   // Swing strip beam (a CSS animation would lag xterm; overlay can't paint over remote JCEF)
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.ccgSetState && window.ccgSetState('${state.name}', ${highCtx});",
                browser.cefBrowser.url, 0
            )
        }
        val payload = buildJsonObject {
            put("model", snapshot?.model ?: "")
            put("costUsd", snapshot?.costUsd ?: 0.0)
            put("contextPct", snapshot?.contextPct ?: 0)
        }
        val jsonStr = Json.encodeToString(JsonElement.serializer(), payload)
        val escaped = jsEscape(jsonStr)
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.ccgUpdateStatus && window.ccgUpdateStatus(\"$escaped\");",
                browser.cefBrowser.url, 0
            )
        }
    }

    /** Proactively warn once when the context window or the rate-limit quota gets near full. */
    private fun checkThresholds(snap: StatusSnapshot) {
        if (!warnedHighCtx && snap.contextPct >= 90) {
            warnedHighCtx = true
            ccgNotify("Context window ${snap.contextPct}% full",
                "Consider running /compact soon — the context window is almost full.")
        } else if (snap.contextPct < 70) {
            warnedHighCtx = false
        }
        if (!warnedRate && snap.rateFiveHourPct >= 85) {
            warnedRate = true
            ccgNotify("Rate limit ${snap.rateFiveHourPct.toInt()}% used",
                "You're close to the 5-hour usage quota.")
        } else if (snap.rateFiveHourPct < 60) {
            warnedRate = false
        }
    }

    private fun ccgNotify(title: String, content: String) {
        runCatching {
            val notif = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("ccglyph")
                .createNotification(title, content, com.intellij.notification.NotificationType.WARNING)
            notif.notify(com.intellij.openapi.project.ProjectManager.getInstance().defaultProject)
        }
    }

    /** Descendant processes currently running in this session (used to ask for confirmation before closing a tab — see CCGlyphTerminalFactory). */
    fun runningProcesses(): List<String> = session.runningProcesses()

    /** Output-triggered: latch onto moments when shell output flows in to check the process tree
     *  for known tools (ordered by DETECT_PRIORITY — first match wins).
     *  - start: the tool emits its banner (immediate output) → detected.
     *  - exit: the shell draws a new prompt (output) → no longer in process list; if there's
     *    no output, it's caught the next time the user types (keystroke echo is output).
     *  Throttle: when idle check frequently (300ms) to catch start quickly; when busy check
     *  less often (800ms) to avoid scanning every chunk during heavy output. */
    private fun maybeUpdateProcessState() {
        if (disposed) return
        val now = System.currentTimeMillis()
        val throttle = if (currentProcess != null) PROCESS_CHECK_BUSY_MS else PROCESS_CHECK_IDLE_MS
        if (now - lastCheckMs < throttle) return
        lastCheckMs = now
        val detected = runCatching {
            val procs = session.runningProcesses().map { it.lowercase() }.toSet()
            DETECT_PRIORITY.firstOrNull { it in procs }
        }.getOrDefault(currentProcess)
        if (detected != currentProcess) {
            currentProcess = detected
            runCatching { onProcessChange?.invoke(detected) }
        }
    }

    /** Generate terminal.html with current config + a loading overlay injected, and return the file.
     *  Static xterm assets are extracted once at startup (ensureAssetsExtracted) — this only
     *  regenerates the HTML (~2KB write) which is near-instant.
     *
     *  A #tg-loading overlay div is injected before the config — it shows "Starting terminal…"
     *  centred on the theme background and is removed by the onLoadEnd bridge once xterm is ready.
     *  Because everything is in ONE HTML page (loaded by a single loadURL call), there's no
     *  navigation race — the overlay renders as soon as the page paints. */
    private fun extractWebResources(): File {
        ensureAssetsExtracted()    // no-op if already done at startup; safety net if startup hasn't finished
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bgHex = toHex(scheme.defaultBackground)
        val fgHex = toHex(scheme.defaultForeground)
        val loadingOverlay = """
            <div id="tg-loading" style="position:fixed;top:0;left:0;width:100%;height:100%;
            display:flex;align-items:center;justify-content:center;
            background:$bgHex;z-index:1000;font-family:system-ui,sans-serif;">
            <div style="color:$fgHex;opacity:0.40;font-size:15px;user-select:none;">Starting terminal…</div>
            </div>
        """.trimIndent()
        val baseHtml = javaClass.getResourceAsStream("/terminal.html").use { stream ->
            stream?.bufferedReader()?.use { it.readText() }
        } ?: error("terminal.html not found in resources")
        val html = baseHtml
            .replace("<!--TG_CONFIG-->", "$loadingOverlay\n<script>window.TG_CONFIG=${buildConfigJson()};</script>")
            .replace("<!--TG_FONTS-->", buildFontFaces())
        val htmlFile = File(webDir, "terminal.html")
        htmlFile.writeText(html, Charsets.UTF_8)
        return htmlFile
    }

    /** Build the @font-face block embedding the bundled JetBrains Mono (woff2) as base64 data: URIs.
     *  Inlining (vs a file:// src) is required because JCEF/Chromium on Windows blocks @font-face fonts
     *  loaded via a file:// URL (CORS on the file origin) — a file:// src silently fails there, leaving
     *  'JetBrains Mono' a broken family so xterm measures a fallback (e.g. Segoe UI Emoji = wide) → text
     *  "far apart". A data: URI is inline → no fetch → no CORS → loads on every OS. */
    private fun buildFontFaces(): String {
        fun dataUri(res: String): String? {
            val bytes = runCatching {
                javaClass.getResourceAsStream(res)?.use { it.readBytes() }
            }.getOrNull() ?: return null
            return "data:font/woff2;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        }
        val regular = dataUri("/fonts/JetBrainsMono-Regular.woff2")
        val bold = dataUri("/fonts/JetBrainsMono-Bold.woff2")
        val italic = dataUri("/fonts/JetBrainsMono-Italic.woff2")
        val boldItalic = dataUri("/fonts/JetBrainsMono-BoldItalic.woff2")
        return buildString {
            if (regular != null) append("@font-face{font-family:'JetBrains Mono';font-style:normal;font-weight:400;src:url(${regular}) format('woff2');font-display:block;}")
            if (bold != null) append("@font-face{font-family:'JetBrains Mono';font-style:normal;font-weight:700;src:url(${bold}) format('woff2');font-display:block;}")
            if (italic != null) append("@font-face{font-family:'JetBrains Mono';font-style:italic;font-weight:400;src:url(${italic}) format('woff2');font-display:block;}")
            if (boldItalic != null) append("@font-face{font-family:'JetBrains Mono';font-style:italic;font-weight:700;src:url(${boldItalic}) format('woff2');font-display:block;}")
        }
    }

    /** Build the JSON config object for new Terminal(...) from CCGlyphSettings + the current editor color scheme. */
    private fun buildConfigJson(): String {
        val s = CCGlyphSettings.getInstance().state
        // Theme genuinely follows the IDE's editor color scheme (there's no theme setting anymore) —
        // bg/fg/cursor/selection are pulled directly from the scheme; the 16-color ANSI palette picks
        // dark/light based on the bg luminance → the terminal blends into the editor (same background,
        // borders disappear).
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bgColor = scheme.defaultBackground
        val fgColor = scheme.defaultForeground
        // Selection background: use the editor scheme's selection colour; if the scheme doesn't define one (or it's
        // identical to the background → invisible), fall back to a clearly visible blue (light/dark aware). This is what
        // drag-select renders with, so it must be visible. (The find active-match highlight is a separate decoration —
        // see terminal.html; it doesn't use this colour.)
        val schemeSel = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
        val selColor = when {
            schemeSel != null && schemeSel != bgColor -> schemeSel
            isDarkColor(bgColor) -> java.awt.Color(0x36, 0x6c, 0xb0)   // dark theme → visible blue
            else -> java.awt.Color(0xad, 0xd6, 0xff)                   // light theme → visible light blue
        }
        // ANSI 16-colour palette pulled from the IDE's Console Colors (Editor › Color Scheme › Console Colors)
        // so the terminal matches the IDE's console exactly, like the built-in terminal; bg/fg/cursor/selection
        // from the editor scheme. Any Console Color the scheme leaves null falls back to the vivid preset.
        val theme = ansiPalette(scheme, isDarkColor(bgColor)).toMutableMap()
        theme["background"] = toHex(bgColor)
        theme["foreground"] = toHex(fgColor)
        theme["cursor"] = toHex(fgColor)
        theme["cursorAccent"] = toHex(bgColor)
        theme["selectionBackground"] = toHex(selColor)
        val themeJson = theme.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"$v\""
        }
        val font = s.fontFamily.replace("\\", "\\\\").replace("\"", "\\\"")
        // Resolve the "follow IDE" sentinels (fontSize=0 / fallbackFont="") to the live IDE values.
        val fallback = (if (s.fallbackFont.isNotBlank()) s.fallbackFont else CCGlyphSettings.editorFallbackFont())
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val fontSize = if (s.fontSize > 0) s.fontSize else CCGlyphSettings.editorFontSize()
        val lineHeight = Math.round(s.lineHeight * 10.0) / 10.0
        return "{" +
            // Emoji glyph fallback chain (per-glyph): JetBrains Mono (and most monospace fonts) ship no
            // colour-emoji glyphs. The browser/xterm WebGL renderer doesn't always auto-fallback for emoji,
            // so list the OS emoji fonts explicitly — Segoe UI Emoji (Windows), Apple Color Emoji (mac),
            // Noto Color Emoji (Linux). This is purely additive and a no-op on mac (it already uses Apple
            // Color Emoji); on Windows it's what makes emoji actually render instead of tofu.
            // Font fallback chain. Order matters: a generic `monospace` sits RIGHT AFTER the user's font +
            // fallback and BEFORE the emoji fonts. Reason: xterm measures char width from the FIRST family in
            // the list that loads a face. If the primary (e.g. JetBrains Mono) can't load — which on Windows
            // can happen when neither a system copy nor the @font-face is available — xterm must fall to a
            // REAL monospace next (Consolas/etc.), NOT to an emoji font (Segoe UI Emoji has wide, irregular
            // metrics → text renders "far apart"/เพี้ยน). Putting monospace before the emoji fonts guarantees
            // a correct-width cell even when the primary font fails. Emoji still render: the browser resolves
            // fonts per-GLYPH down the chain, so an emoji codepoint skips monospace (no emoji glyphs) and lands
            // on Segoe UI Emoji / Apple Color Emoji / Noto Color Emoji.
            "\"fontFamily\":\"$font, $fallback, monospace, 'Segoe UI Emoji', 'Apple Color Emoji', 'Noto Color Emoji'\"," +
            "\"fontSize\":$fontSize," +
            "\"lineHeight\":$lineHeight," +
            "\"letterSpacing\":${s.letterSpacing}," +
            "\"scrollback\":${s.scrollback}," +
            "\"cursorStyle\":\"${s.cursorStyle}\"," +
            "\"allowTransparency\":false," +
            "\"allowProposedApi\":true," +   // unlocks registerDecoration() — the find active-match highlight (yellow bg + black text)
            "\"theme\":$themeJson," +
            // Status chip flags (global). terminal.html reads window.TG_CONFIG.ccg to gate the chip (and
            // re-renders on live-reload via tgReloadConfig). The gradient beam AND the tab-colour effect are
            // both Swing overlays (BeamOverlay / TabBlinker, gated in Kotlin) — not CSS — so the only JS flag
            // that matters here is showChip + the per-field toggles. `beam` is kept in the payload for back-compat
            // (vestigial in JS; the Swing BeamOverlay re-reads beamEnabled directly). Cost defaults off.
            "\"ccg\":{\"beam\":${s.beamEnabled},\"showChip\":${s.showStatusChip},\"model\":${s.chipShowModel},\"cost\":${s.chipShowCost},\"ctx\":${s.chipShowContext}}" +
            "}"
    }

    private fun toHex(c: java.awt.Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    /** Push the current config (settings + editor color scheme) to xterm.js without reloading the page.
     *  Called by settings listener (font/size/cursor change) and theme listener (IDE color scheme change). */
    private fun pushConfigToJs() {
        if (disposed) return
        val json = buildConfigJson()
        val escaped = jsEscape(json)
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.tgReloadConfig && window.tgReloadConfig(\"$escaped\");",
                browser.cefBrowser.url, 0
            )
        }
    }

    private fun isDarkColor(c: java.awt.Color): Boolean =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) < 140.0

    /** ANSI 16-colour palette sourced from the IDE's **Console Colors** (Editor › Color Scheme › Console Colors)
     *  via `ConsoleHighlighter`'s `TextAttributesKey`s, so the terminal matches the IDE console exactly (like the
     *  built-in terminal). [dark] selects the fallback preset for any Console Color the scheme leaves undefined.
     *  Mapping follows the standard IntelliJ-console ↔ ANSI layout (GRAY=normal white, DARKGRAY=bright black). */
    private fun ansiPalette(scheme: EditorColorsScheme, dark: Boolean): Map<String, String> {
        val preset = ansiPreset(dark)
        fun hex(key: TextAttributesKey, name: String): String =
            scheme.getAttributes(key)?.foregroundColor?.let { toHex(it) } ?: preset.getValue(name)
        return mapOf(
            "black" to hex(ConsoleHighlighter.BLACK, "black"),
            "red" to hex(ConsoleHighlighter.RED, "red"),
            "green" to hex(ConsoleHighlighter.GREEN, "green"),
            "yellow" to hex(ConsoleHighlighter.YELLOW, "yellow"),
            "blue" to hex(ConsoleHighlighter.BLUE, "blue"),
            "magenta" to hex(ConsoleHighlighter.MAGENTA, "magenta"),
            "cyan" to hex(ConsoleHighlighter.CYAN, "cyan"),
            "white" to hex(ConsoleHighlighter.GRAY, "white"),
            "brightBlack" to hex(ConsoleHighlighter.DARKGRAY, "brightBlack"),
            "brightRed" to hex(ConsoleHighlighter.RED_BRIGHT, "brightRed"),
            "brightGreen" to hex(ConsoleHighlighter.GREEN_BRIGHT, "brightGreen"),
            "brightYellow" to hex(ConsoleHighlighter.YELLOW_BRIGHT, "brightYellow"),
            "brightBlue" to hex(ConsoleHighlighter.BLUE_BRIGHT, "brightBlue"),
            "brightMagenta" to hex(ConsoleHighlighter.MAGENTA_BRIGHT, "brightMagenta"),
            "brightCyan" to hex(ConsoleHighlighter.CYAN_BRIGHT, "brightCyan"),
            "brightWhite" to hex(ConsoleHighlighter.WHITE, "brightWhite"),
        )
    }

    /** Fallback ANSI palette (no bg/fg) for Console Colors the scheme leaves null.
     *  Dark preset = classic macOS Terminal.app / xterm values (saturated, distinct bright variants). */
    private fun ansiPreset(dark: Boolean): Map<String, String> = if (dark) mapOf(
        "black" to "#000000", "brightBlack" to "#666666",
        "red" to "#990000", "brightRed" to "#e50000",
        "green" to "#00a600", "brightGreen" to "#00d900",
        "yellow" to "#999900", "brightYellow" to "#e5e500",
        "blue" to "#0000b2", "brightBlue" to "#0000ff",
        "magenta" to "#b200b2", "brightMagenta" to "#e500e5",
        "cyan" to "#3dd0dc", "brightCyan" to "#5cf0f5",
        "white" to "#bfbfbf", "brightWhite" to "#e5e5e5",
    ) else mapOf(
        "black" to "#000000", "brightBlack" to "#666666",
        "red" to "#cd3131", "brightRed" to "#cd3131",
        "green" to "#00bc7c", "brightGreen" to "#14ce14",
        "yellow" to "#949800", "brightYellow" to "#b5ba1f",
        "blue" to "#0451a5", "brightBlue" to "#0451a5",
        "magenta" to "#bc05bc", "brightMagenta" to "#bc05bc",
        "cyan" to "#0598bc", "brightCyan" to "#0598bc",
        "white" to "#555555", "brightWhite" to "#a5a5a5",
    )

    /** Escape a string so it is safe to embed inside a JS double-quoted string literal. */
    private fun jsEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when {
            c == '\\'      -> sb.append("\\\\")
            c == '"'       -> sb.append("\\\"")
            c == '\n'      -> sb.append("\\n")
            c == '\r'      -> sb.append("\\r")
            c == '\t'      -> sb.append("\\t")
            c.code < 0x20  -> sb.append("\\u%04x".format(c.code))   // other control chars
            // Emoji / astral chars are a UTF-16 surrogate pair (2 chars). Escape EACH surrogate to \uXXXX
            // (e.g. 🎉 → 🎉) instead of appending it raw: the JCEF executeJavaScript bridge mangles
            // raw surrogate chars, which is why emoji rendered as � while BMP chars (Thai) were fine. JS
            // recombines the two \u escapes into the code point, so xterm.write receives the real emoji.
            Character.isSurrogate(c) -> sb.append("\\u%04x".format(c.code))
            else           -> sb.append(c)                          // Thai characters + general UTF-8 (BMP)
        }
        return sb.toString()
    }

    /** Append output to the buffer and schedule a flush.
     *
     *  Every access to the alarm is under outLock and checks disposed → dispose (also under outLock)
     *  therefore can never addRequest against an already-disposed Alarm → prevents SEVERE
     *  "Already disposed" during shutdown.
     *
     *  Two flush paths:
     *  - **Echo (≤80 chars)**: flush on the next EDT tick via invokeLater → typing feels instant.
     *  - **Streaming (>80 chars)**: batch via Alarm (~8ms) → fewer executeJavaScript calls, higher throughput.
     *  Already-pending flushes just accumulate into the buffer regardless of path. */
    private fun appendOutput(chunk: String) {
        maybeUpdateProcessState()   // output-triggered: check the process tree to switch the tab icon + title
        synchronized(outLock) {
            if (disposed) return
            outBuf.append(chunk)
            if (!flushPending) {
                flushPending = true
                if (outBuf.length <= ECHO_FLUSH_THRESHOLD) {
                    // Small chunk (typing echo) — respond on the next Swing tick, no Alarm delay.
                    ApplicationManager.getApplication().invokeLater { flushNow() }
                } else {
                    // Large output (Claude streaming) — batch via Alarm to avoid flooding JCEF.
                    runCatching { alarm.addRequest(this::flushNow, FLUSH_DELAY_MS) }
                }
            } else if (outBuf.length > MAX_BUFFER_FLUSH && !urgentFlushPending) {
                // A burst is piling up faster than the batched Alarm can drain → flush now on the next
                // EDT tick instead of letting the buffer grow unbounded (keeps heavy output responsive
                // and bounds memory). Only one urgent flush is in flight at a time (urgentFlushPending).
                urgentFlushPending = true
                ApplicationManager.getApplication().invokeLater { flushNow() }
            }
        }
    }

    /** Flush everything pending in the buffer down to xterm in a single executeJavaScript call. */
    private fun flushNow() {
        val data: String = synchronized(outLock) {
            flushPending = false
            urgentFlushPending = false
            if (disposed || outBuf.isEmpty()) return
            val s = outBuf.toString()
            outBuf.setLength(0)
            s
        }
        // Escape for a JS double-quoted string before every executeJavaScript
        // (handles backticks, ${...}, control chars; Thai/UTF-8 passes through unchanged).
        val escaped = jsEscape(data)
        runCatching {
            browser.cefBrowser.executeJavaScript(
                "window.receiveOutput(\"$escaped\");",
                browser.cefBrowser.url, 0
            )
        }
        // If new output arrived during the flush → schedule another flush.
        synchronized(outLock) {
            if (!disposed && outBuf.isNotEmpty() && !flushPending) {
                flushPending = true
                runCatching { alarm.addRequest(this::flushNow, FLUSH_DELAY_MS) }
            }
        }
    }

    fun dispose() {
        // Always set the flag first (under outLock) → the reader thread sees it and stops addRequest.
        synchronized(outLock) {
            disposed = true
            runCatching { alarm.cancelAllRequests() }
        }
        runCatching { statusController?.dispose() }
        // Unregister live-reload listeners (theme listener is auto-cleaned via messageBus.dispose on parentDisposable).
        runCatching { CCGlyphSettings.getInstance().removeListener(settingsListener) }
        runCatching { alarm.dispose() }
        session.destroy()
        inputQuery.dispose()
        resizeQuery.dispose()
        browser.dispose()
        beam.dispose()
    }

    /**
     * A thin gradient "beam" painted as a SWING strip directly above the terminal (its own 6px region, NOT
     * overlapping the browser).
     *
     * WHY SWING, NOT CSS: the JCEF browser renders every frame of a continuous CSS animation and pushes it into
     * Swing, competing with xterm and lagging the terminal (worst while Claude streams — exactly when the beam
     * animates). A Swing beam repaints only its own strip on the EDT and never makes Chromium render, so the beam
     * flows with zero impact on xterm.
     *
     * WHY A SEPARATE STRIP, NOT AN OVERLAY: this build's JCEF is the remote variant (com.jetbrains.cef.remote),
     * whose browser surface is heavyweight. A lightweight Swing component painted OVER it is always hidden behind
     * it — an overlapping beam was never visible. So the beam lives in its own BorderLayout.NORTH region (no
     * overlap, renders fine). The strip is always 6px; when idle it paints the terminal's background colour so it
     *  blends in (no visible strip until a state activates).
     *
     * Look matches the old CSS beam: a soft glow (vertical alpha fade, opaque top → transparent bottom) + state
     * colours (purple/blue running, amber waiting, red error, red/orange near-limit) + a flowing phase.
     */
    private class BeamOverlay : JComponent() {
        private val timer = javax.swing.Timer(FRAME_MS) { advance() }.apply { isRepeats = true }
        private var phase = 0f
        @Volatile private var mode = Mode.IDLE
        private var bi: java.awt.image.BufferedImage? = null
        private var biW = 0
        private var biH = 0

        private enum class Mode { IDLE, RUNNING, WAITING, ERROR, HIGH }

        init { isOpaque = true; isVisible = false }

        /** The strip is always 6px tall (BorderLayout.NORTH uses this height); the terminal is permanently 6px
         *  shorter so it never resizes on a state change. */
        override fun getPreferredSize(): java.awt.Dimension = java.awt.Dimension(1024, BEAM_H)

        /** Drive the beam from the same (state, high-context) the chip/tab use. Re-reads `beamEnabled` each call
         *  so the live Settings toggle takes effect immediately (mirrors TabBlinker). Thread-safe (Swing work on EDT). */
        fun setBeamState(state: ClaudeState, high: Boolean) {
            val enabled = CCGlyphSettings.getInstance().state.beamEnabled
            val next = when {
                !enabled -> Mode.IDLE
                high -> Mode.HIGH
                state == ClaudeState.ERROR -> Mode.ERROR
                state.isBusy -> Mode.RUNNING
                state.isWaiting -> Mode.WAITING
                else -> Mode.IDLE
            }
            if (next == mode) return
            mode = next
            ApplicationManager.getApplication().invokeLater {
                if (next == Mode.IDLE) {
                    if (timer.isRunning) timer.stop()
                    repaint()   // repaint so the strip reverts to the terminal bg colour (blends in when idle)
                } else if (!timer.isRunning) {
                    phase = 0f
                    timer.start()
                }
            }
        }

        fun dispose() { if (timer.isRunning) timer.stop() }

        /** The terminal's background colour — the strip fills with this so it blends in when idle. Matches what
         *  buildConfigJson sets as the xterm theme background (scheme.defaultBackground); re-read each paint so a
         *  theme change takes effect at once. */
        private fun terminalBg(): java.awt.Color =
            runCatching { EditorColorsManager.getInstance().globalScheme.defaultBackground }
                .getOrDefault(java.awt.Color(0x1e, 0x1e, 0x1e))

        private fun advance() {
            val w = width
            if (w <= 0) return
            // One full flow cycle ≈ 2.4s running / 1.6s waiting (matches the old CSS durations) at ~30fps.
            val cycle = if (mode == Mode.WAITING) WAIT_FRAMES else RUN_FRAMES
            phase += w.toFloat() / cycle
            if (phase >= w) phase -= w.toFloat()
            repaint()
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g as java.awt.Graphics2D
            val w = width; val h = height
            if (w <= 0 || h <= 0) return
            // Always fill with the terminal's own background colour first. The strip sits directly above the
            // terminal, so matching its bg makes the strip invisible when idle (no black gap) and gives the glow
            // a colour to fade into when active. isOpaque=true (init) tells Swing not to paint anything behind us.
            g2.color = terminalBg()
            g2.fillRect(0, 0, w, h)
            if (mode == Mode.IDLE) return
            val (colors, fracs) = palette(mode) ?: return
            // Render into a tiny ARGB scratch image: (1) the flowing horizontal gradient — REPEAT is seamless
            // because each palette's end colour == its start — then (2) a vertical alpha fade via DST_IN so the
            // bar reads as a soft glow bleeding down from the top edge (exactly the old CSS mask).
            var img = bi
            if (img == null || biW != w || biH != h) {
                img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                bi = img; biW = w; biH = h
            }
            val period = w.toFloat()
            val bg = img.createGraphics()
            bg.composite = java.awt.AlphaComposite.Src
            bg.paint = java.awt.LinearGradientPaint(
                -phase, 0f, period - phase, 0f, fracs, colors,
                java.awt.MultipleGradientPaint.CycleMethod.REPEAT
            )
            bg.fillRect(0, 0, w, h)
            bg.composite = java.awt.AlphaComposite.DstIn
            bg.paint = java.awt.GradientPaint(
                0f, 0f, java.awt.Color(255, 255, 255, 255),
                0f, h.toFloat(), java.awt.Color(255, 255, 255, 0)
            )
            bg.fillRect(0, 0, w, h)
            bg.dispose()
            g2.drawImage(img, 0, 0, null)
        }

        private fun palette(m: Mode): Pair<Array<java.awt.Color>, FloatArray>? = when (m) {
            Mode.RUNNING -> ary(0x7c3aed, 0x2563eb, 0x06b6d4, 0x2563eb, 0x7c3aed) to floatArrayOf(0f, .25f, .5f, .75f, 1f)
            Mode.WAITING -> ary(0xb45309, 0xf59e0b, 0xfde68a, 0xf59e0b, 0xb45309) to floatArrayOf(0f, .25f, .5f, .75f, 1f)
            // Pure red (red-700 ↔ red-500), distinct from HIGH's red→orange (near-limit) so a failed tool doesn't read
            // as "almost out of context". Symmetric ends keep the REPEAT seam invisible like the other palettes.
            Mode.ERROR -> ary(0xb91c1c, 0xef4444, 0xb91c1c) to floatArrayOf(0f, .5f, 1f)
            Mode.HIGH -> ary(0xef4444, 0xf97316, 0xef4444) to floatArrayOf(0f, .5f, 1f)
            Mode.IDLE -> null
        }

        private fun ary(vararg rgb: Int) = Array(rgb.size) { java.awt.Color(rgb[it]) }

        private companion object {
            const val FRAME_MS = 33      // ~30fps — smooth for a beam, half the Swing work of 60fps
            const val RUN_FRAMES = 72    // 2.4s flow cycle (matches the old running duration)
            const val WAIT_FRAMES = 48   // 1.6s flow cycle (matches the old waiting duration)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(TerminalBrowserPanel::class.java)

        /** Delay for batching output before flushing — short enough that echo feels instant,
         *  long enough to catch most bursts and avoid flooding JCEF with executeJavaScript calls. */
        const val FLUSH_DELAY_MS = 8

        /** If the pending buffer is ≤ this many chars, bypass the Alarm and flush on the next
         *  EDT tick via invokeLater — makes typing feel responsive (echo returns instantly).
         *  Larger output (Claude streaming) still goes through Alarm batching for throughput. */
        const val ECHO_FLUSH_THRESHOLD = 80

        /** If a streaming burst pushes the pending buffer past this size while an Alarm flush is already
         *  scheduled, stop waiting for the Alarm and flush immediately on the next EDT tick — keeps heavy
         *  output responsive and bounds memory during pathological bursts (e.g. dumping a huge file). */
        const val MAX_BUFFER_FLUSH = 48 * 1024

        /** Throttle for output-triggered process checks — check frequently when idle to catch start quickly, lighten the load when busy. */
        const val PROCESS_CHECK_IDLE_MS = 300L
        const val PROCESS_CHECK_BUSY_MS = 800L

        /** Process names checked in output-triggered detection — first match wins.
         *  Specific tools (claude/vim) before generic runtimes (python/node). */
        val DETECT_PRIORITY = listOf(
            "claude", "codex", "gemini",    // AI assistants
            "vim", "nvim", "vi",            // editors
            "gradle", "gradlew", "mvn",     // build
            "cargo",                         // Rust
            "docker", "docker-compose",     // containers
            "git",                           // VCS
            "flutter",                       // framework
            "npm", "yarn", "pnpm",          // package managers
            "adb",                           // Android
            "python", "python3", "go",      // runtimes
            "node",                          // Node.js
            "agy",                           // Google Antigravity
        )

        /** Version of the extracted web + bridge assets. Bump every time you edit terminal.html/xterm OR the
         *  bridge scripts (ccglyph-bridge / ccglyph-bridge.cmd): both are extracted once into a temp dir keyed
         *  on this version (with a `.done` marker), so a stale version keeps serving the OLD assets. */
        const val WEB_VERSION = "v26"

        /** Height (px) of the Swing BeamOverlay strip — matches the old CSS beam height. */
        const val BEAM_H = 6

        /** Static xterm assets that are extracted once at startup (not per-tab). */
        private val STATIC_ASSETS = listOf(
            "/xterm/xterm.js",
            "/xterm/xterm.css",
            "/xterm/xterm-addon-fit.js",
            "/xterm/xterm-addon-unicode11.js",
            "/xterm/xterm-addon-webgl.js",
            // NOTE: the JetBrains Mono woff2 files are NOT extracted here — they're inlined as base64
            // data: URIs into terminal.html (buildFontFaces) so they load without a file:// fetch. JCEF on
            // Windows blocks @font-face src that points at a file:// URL (CORS on the file origin), so a
            // file:// @font-face silently fails there → 'JetBrains Mono' becomes a broken family → xterm
            // measures the next available font in the list (e.g. Segoe UI Emoji = wide) → text "far apart".
            // A data: URI is inline → no fetch → no CORS → loads on every OS.
        )

        /** Temp directory where xterm assets + terminal.html live. */
        private val webDir = File(System.getProperty("java.io.tmpdir"), "ccglyph-web-$WEB_VERSION")

        private val extractLock = Any()
        @Volatile private var assetsExtracted = false

        /**
         * Extract static xterm files (js/css/addons) to a temp directory — once per IDE session.
         * Safe to call from any thread, safe to call multiple times (no-op after first).
         *
         * Called at project-open (CCGlyphStartupActivity) so the 1.3MB file I/O is done
         * before the user ever opens a terminal tab.  Also called lazily by the first tab
         * if startup hasn't finished yet.
         */
        fun ensureAssetsExtracted() {
            if (assetsExtracted) return
            synchronized(extractLock) {
                if (assetsExtracted) return
                for (res in STATIC_ASSETS) {
                    val target = File(webDir, res.removePrefix("/"))
                    target.parentFile?.mkdirs()
                    TerminalBrowserPanel::class.java.getResourceAsStream(res)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("resource not found in plugin jar: $res")
                }
                assetsExtracted = true
                LOG.info("[CCGlyph] static assets v$WEB_VERSION extracted to ${webDir.absolutePath}")
            }
        }
    }
}
