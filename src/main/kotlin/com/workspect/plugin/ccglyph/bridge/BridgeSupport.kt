package com.workspect.plugin.ccglyph.bridge

import com.workspect.plugin.ccglyph.CCGlyphSettings
import com.workspect.plugin.ccglyph.TerminalBrowserPanel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Locates/extracts the bundled bridge executable and builds the statusLine + hooks JSON
 *  fragment injected into a session's runtime settings.json. */
object BridgeSupport {

    /** Root under which CCGlyph keeps per-session bridge state: `<root>/<sessionId>/{status.json,events.jsonl}`. */
    val stateRoot: File = File(System.getProperty("user.home"), ".claude/ccglyph/state")

    /** Temp dir holding the extracted bridge script(s), one per web-asset version. */
    private val bridgeDir: File =
        File(System.getProperty("java.io.tmpdir"), "ccglyph-bridge-${TerminalBrowserPanel.WEB_VERSION}")

    /** Absolute path to the bridge executable for this OS, extracting first if needed. */
    fun bridgePath(): String {
        ensureExtracted()
        return (if (CCGlyphSettings.isWindows) File(bridgeDir, "ccglyph-bridge.cmd")
                else File(bridgeDir, "ccglyph-bridge")).absolutePath
    }

    /** Extract the bridge scripts once per version; chmod +x on Unix. Idempotent. */
    fun ensureExtracted() {
        val marker = File(bridgeDir, ".done")
        if (marker.exists()) return
        bridgeDir.mkdirs()
        extract("/bridge/ccglyph-bridge", "ccglyph-bridge", executable = !CCGlyphSettings.isWindows)
        extract("/bridge/ccglyph-bridge.cmd", "ccglyph-bridge.cmd", executable = false)
        marker.writeText("ok")
    }

    private fun extract(resource: String, name: String, executable: Boolean) {
        val target = File(bridgeDir, name)
        javaClass.getResourceAsStream(resource)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: error("bridge resource not found in plugin jar: $resource")
        if (executable) runCatching {
            ProcessBuilder("chmod", "+x", target.absolutePath).start().waitFor()
        }
    }

    /** Hook events CCGlyph subscribes to (the inputs to the status state machine). Order follows the
     *  turn lifecycle so the list reads top-to-bottom as a session progresses. SubagentStart/Stop bracket
     *  an in-flight Agent tool call, so they sit beside the other tool events; each must also be mapped in
     *  [com.workspect.plugin.ccglyph.status.StatusController.deriveState] or it falls through to the
     *  previous state (no event is then emitted for it). */
    private val HOOK_EVENTS = listOf(
        "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure",
        "SubagentStart", "SubagentStop", "Notification", "Stop", "StopFailure",
        "SessionStart", "SessionEnd",
    )

    /** The JSON fragment (`statusLine` + `hooks`) deep-merged onto a profile's base settings.
     *  The hook command is identical for every event (it just appends the event JSON); the
     *  plugin's [com.workspect.plugin.ccglyph.status.StatusController] derives state from
     *  each event's `hook_event_name`. */
    fun injectionJson(sessionId: String, stateDir: String): JsonObject {
        // Single-quote the bridge path + state dir: claude runs hook/statusLine commands via the
        // shell, so paths with spaces (e.g. a username with a space) must be quoted.
        val hookCmd = "'${bridgePath()}' hook $sessionId '$stateDir'"
        val statusCmd = "'${bridgePath()}' status $sessionId '$stateDir'"
        return buildJsonObject {
            putJsonObject("statusLine") {
                put("type", "command")
                put("command", statusCmd)
                put("padding", 0)
            }
            putJsonObject("hooks") {
                for (evt in HOOK_EVENTS) {
                    putJsonArray(evt) {
                        add(buildJsonObject {
                            putJsonArray("hooks") {
                                add(buildJsonObject {
                                    put("type", "command")
                                    put("command", hookCmd)
                                })
                            }
                        })
                    }
                }
            }
        }
    }
}
