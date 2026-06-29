package com.workspect.plugin.ccglyph.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Visual / semantic state of a Claude Code session, derived from hook events. */
enum class ClaudeState {
    IDLE, THINKING, TOOL_RUNNING, WAITING_PERMISSION, WAITING_INPUT, ERROR;

    /** Busy states animate the beam + tab. */
    val isBusy: Boolean get() = this == THINKING || this == TOOL_RUNNING
    val isWaiting: Boolean get() = this == WAITING_PERMISSION || this == WAITING_INPUT

    /** Turn-over resting states: the turn is done and Claude is awaiting the next prompt (IDLE outright, or
     *  WAITING_INPUT after it fires an idle_prompt). A late continuation event fired AFTER the main Stop —
     *  e.g. a background subagent's SubagentStop tearing down — must not wake these back to THINKING, or the
     *  beam latches on "thinking" forever. See [StatusController.deriveState]'s latch. (WAITING_PERMISSION
     *  is NOT turn-done: it's a mid-turn pause for approval, so the PostToolUse after approval still applies.) */
    val isTurnDone: Boolean get() = this == IDLE || this == WAITING_INPUT
}

/** Rich status snapshot parsed from the statusLine payload (the bridge's `status.json`). */
data class StatusSnapshot(
    val model: String = "",
    val costUsd: Double = 0.0,
    val contextPct: Int = 0,        // 0..100 of the context window
    val contextTokens: Long = 0L,
    val contextSize: Long = 0L,
    val exceeds200k: Boolean = false,
    val rateFiveHourPct: Double = 0.0,
    val rateSevenDayPct: Double = 0.0,
    val linesAdded: Long = 0L,
    val linesRemoved: Long = 0L,
) {
    companion object {
        /** Parse a statusLine JSON payload; null if it can't be read/parsed. */
        fun parse(json: String): StatusSnapshot? = runCatching {
            val o = Json.parseToJsonElement(json).jsonObject
            fun JsonObject.str(vararg path: String): String {
                var e: JsonElement = this
                for (p in path) { e = (e as? JsonObject)?.get(p) ?: return "" }
                return (e as? JsonPrimitive)?.contentOrNull ?: ""
            }
            fun JsonObject.num(vararg path: String): Double {
                var e: JsonElement = this
                for (p in path) { e = (e as? JsonObject)?.get(p) ?: return 0.0 }
                return (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
            }
            StatusSnapshot(
                model = o.str("model", "display_name"),
                costUsd = o.num("cost", "total_cost_usd"),
                contextPct = o.num("context_window", "used_percentage").toInt(),
                contextTokens = o.num("context_window", "total_input_tokens").toLong(),
                contextSize = o.num("context_window", "context_window_size").toLong(),
                exceeds200k = o.str("exceeds_200k_tokens").equals("true", ignoreCase = true),
                rateFiveHourPct = o.num("rate_limits", "five_hour", "used_percentage"),
                rateSevenDayPct = o.num("rate_limits", "seven_day", "used_percentage"),
                linesAdded = o.num("cost", "total_lines_added").toLong(),
                linesRemoved = o.num("cost", "total_lines_removed").toLong(),
            )
        }.getOrNull()
    }
}
