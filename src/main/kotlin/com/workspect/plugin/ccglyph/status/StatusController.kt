package com.workspect.plugin.ccglyph.status

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    private val eventFmt = Json { ignoreUnknownKeys = true }

    /** SubagentStart/SubagentStop carry `agent_id` but fire in the parent (main) session, so they must
     *  not be treated as subagent-internal by the `agent_id` filter in [parseEvent]. */
    private val SUBAGENT_LIFECYCLE_EVENTS = setOf("SubagentStart", "SubagentStop")

    /** Events that legitimately start a new turn / bring the session out of IDLE. Everything else is a
     *  continuation of a turn already in progress, so it's ignored while IDLE (see [deriveState]). */
    private val TURN_STARTERS = setOf(
        "UserPromptSubmit", "PreToolUse", "SubagentStart", "Notification", "StopFailure", "SessionStart",
    )

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

        // events.jsonl — fold EVERY main-session event through the state machine in order, starting from
        // IDLE. The state machine is a pure fold over the event log, so re-deriving from scratch on each
        // growth is deterministic and correct no matter how many events landed in one 150ms poll window.
        // This fixes two flaws the old "read only the newest line" design had:
        //  (1) a Stop could be MASKED by a continuation event appended in the same window (the Stop was then
        //      never seen → beam never went idle); folding applies the Stop first.
        //  (2) the late-event latch in deriveState only works if the Stop was actually applied — folding
        //      guarantees that, so a post-Stop SubagentStop is dropped instead of latching THINKING.
        if (eventsFile.isFile && eventsFile.length() != lastEventsLen) {
            lastEventsLen = eventsFile.length()
            val text = runCatching { eventsFile.readText() }.getOrNull()
            if (text != null) {
                var s = ClaudeState.IDLE
                for (line in text.lineSequence().filter { it.isNotBlank() }) {
                    val info = parseEvent(line) ?: continue
                    if (!info.isMainSession) continue
                    s = deriveState(s, info)
                }
                if (s != lastState) { lastState = s; newState = s; stateChanged = true }
            }
        }

        if (stateChanged || snapChanged) {
            val s = newState
            val sn = snap
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) onUpdate?.invoke(s, sn)
            }
        }
    }

    /** Parse one event line into an [EventInfo]. [isMainSession] is false for hooks Claude Code fired
     *  inside a subagent (those carry an injected `agent_id`). SubagentStart/SubagentStop fire in the
     *  parent session to announce a subagent and also carry `agent_id`, so they're exempted. */
    private fun parseEvent(line: String): EventInfo? {
        val obj = runCatching { eventFmt.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val name = (obj["hook_event_name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val ntype = (obj["notification_type"] as? JsonPrimitive)?.contentOrNull
        val subagentInternal = name !in SUBAGENT_LIFECYCLE_EVENTS && obj["agent_id"] != null
        return EventInfo(name, ntype, isMainSession = !subagentInternal)
    }

    private fun deriveState(prev: ClaudeState, ev: EventInfo): ClaudeState {
        // Once a turn is OVER (IDLE, or WAITING_INPUT after Claude fires an idle_prompt), ignore left-over
        // continuation events: Claude Code emits a SubagentStop — sometimes a PostToolUse too — *after* the
        // main Stop (a background subagent tearing down late), and crucially AFTER the idle_prompt as well.
        // Without this latch that late event flips a finished session back to THINKING and the beam latches
        // on "thinking" forever. The latch must cover WAITING_INPUT, not just IDLE: a late SubagentStop
        // landing while WAITING_INPUT (the normal post-turn state) is exactly the stuck-thinking bug.
        // `prev` is passed in (not read from the field) so the whole event log can be folded from IDLE each
        // poll in tick() — otherwise the latch would reference stale cross-tick state.
        if (prev.isTurnDone && ev.name !in TURN_STARTERS) return prev
        return when (ev.name) {
            "UserPromptSubmit" -> ClaudeState.THINKING
            "PreToolUse" -> ClaudeState.TOOL_RUNNING
            "PostToolUse" -> ClaudeState.THINKING
            // A failed tool surfaces as an error (red beam/tab). Transient: the next PreToolUse or Stop
            // recovers the state, so it only flashes rather than latching on ERROR.
            "PostToolUseFailure" -> ClaudeState.ERROR
            // A spawned subagent is still "tool running" (the Agent tool call is in flight); when it returns,
            // the main thread resumes thinking over its result — same cadence as PreToolUse → PostToolUse.
            "SubagentStart" -> ClaudeState.TOOL_RUNNING
            "SubagentStop" -> ClaudeState.THINKING
            "Notification" ->
                if (ev.notificationType == "idle_prompt") ClaudeState.WAITING_INPUT
                else ClaudeState.WAITING_PERMISSION
            "Stop" -> ClaudeState.IDLE
            "StopFailure" -> ClaudeState.ERROR
            "SessionStart", "SessionEnd" -> ClaudeState.IDLE
            else -> prev
        }
    }

    fun dispose() {
        disposed = true
        future?.cancel(false)
    }

    private data class EventInfo(val name: String, val notificationType: String?, val isMainSession: Boolean)
}
