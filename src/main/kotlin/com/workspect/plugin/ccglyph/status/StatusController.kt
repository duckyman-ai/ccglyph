package com.workspect.plugin.ccglyph.status

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Polls a session's bridge state dir (`status.json` + `events.jsonl`) and publishes
 *  [ClaudeState] transitions + [StatusSnapshot] updates to [onUpdate], marshalled to the EDT.
 *
 *  One controller per bridge-backed session (created by [com.workspect.plugin.ccglyph.TerminalBrowserPanel]
 *  when the panel's LaunchSpec has a bridge), disposed with the panel. Polling (rather than
 *  WatchService) keeps it reliable on macOS; ~150 ms latency is fine for visual effects. */
class StatusController(
    private val sessionId: String,
    private val stateDir: String,
) {
    private val log = logger<StatusController>()
    private val sessionDir = File(stateDir, sessionId)
    private val statusFile = File(sessionDir, "status.json")
    private val eventsFile = File(sessionDir, "events.jsonl")

    @Volatile private var lastStatusMtime = 0L
    @Volatile private var lastEventsLen = 0L
    @Volatile private var lastState: ClaudeState = ClaudeState.IDLE
    @Volatile private var lastSnapshot: StatusSnapshot? = null
    @Volatile private var disposed = false
    private var future: ScheduledFuture<*>? = null

    /** Fired on the EDT when the derived state or the status snapshot changes. */
    var onUpdate: ((state: ClaudeState, snapshot: StatusSnapshot?) -> Unit)? = null

    fun start() {
        future?.cancel(false)
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { log.warn(it) } },
            400, 150, TimeUnit.MILLISECONDS,
        )
    }

    private fun tick() {
        if (disposed) return
        var snap: StatusSnapshot? = lastSnapshot
        var snapChanged = false
        var newState = lastState
        var stateChanged = false

        // status.json — the latest statusLine payload (model, cost, context %, rate limits).
        if (statusFile.isFile && statusFile.lastModified() != lastStatusMtime) {
            lastStatusMtime = statusFile.lastModified()
            val parsed = statusFile.readText().let { StatusSnapshot.parse(it) }
            if (parsed != null && parsed != lastSnapshot) { lastSnapshot = parsed; snap = parsed; snapChanged = true }
        }

        // events.jsonl — hook events; derive the state machine from the latest event.
        val ev = readLastEvent()
        if (ev != null) {
            val derived = deriveState(ev)
            if (derived != lastState) { lastState = derived; newState = derived; stateChanged = true }
        }

        if (stateChanged || snapChanged) {
            val s = newState
            val sn = snap
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) onUpdate?.invoke(s, sn)
            }
        }
    }

    /** Read the last non-blank line of events.jsonl (only if it grew) and pull out the
     *  `hook_event_name` (and `notification_type`, used to split idle vs permission). */
    private fun readLastEvent(): EventInfo? {
        if (!eventsFile.isFile) return null
        val len = eventsFile.length()
        if (len <= lastEventsLen) return null
        lastEventsLen = len
        val text = runCatching { eventsFile.readText() }.getOrNull() ?: return null
        val lastLine = text.lineSequence().lastOrNull { it.isNotBlank() } ?: return null
        val event = Regex(""""hook_event_name"\s*:\s*"([^"]+)"""").find(lastLine)?.groupValues?.getOrNull(1)
            ?: return null
        val ntype = Regex(""""notification_type"\s*:\s*"([^"]+)"""").find(lastLine)?.groupValues?.getOrNull(1)
        return EventInfo(event, ntype)
    }

    private fun deriveState(ev: EventInfo): ClaudeState = when (ev.name) {
        "UserPromptSubmit" -> ClaudeState.THINKING
        "PreToolUse" -> ClaudeState.TOOL_RUNNING
        "PostToolUse" -> ClaudeState.THINKING
        "Notification" ->
            if (ev.notificationType == "idle_prompt") ClaudeState.WAITING_INPUT
            else ClaudeState.WAITING_PERMISSION
        "Stop" -> ClaudeState.IDLE
        "StopFailure" -> ClaudeState.ERROR
        "SessionStart", "SessionEnd" -> ClaudeState.IDLE
        else -> lastState
    }

    fun dispose() {
        disposed = true
        future?.cancel(false)
    }

    private data class EventInfo(val name: String, val notificationType: String?)
}
