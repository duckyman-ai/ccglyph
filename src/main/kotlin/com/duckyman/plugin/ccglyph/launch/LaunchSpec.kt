package com.duckyman.plugin.ccglyph.launch

/** A fully-resolved PTY launch — the argv + env + cwd to exec, plus the bridge wiring.
 *
 *  - [command] is exec'd by pty4j. For a plain terminal it's `[shell, "--login"]`;
 *    for a profile it's `[shell, "-l", "-c", "exec claude ..."]`.
 *  - [sessionId] is the CCGlyph UUID used as the bridge state sub-dir; empty when the
 *    session has no bridge (plain terminal, or bridge disabled).
 *  - [stateDir] is the bridge root (contains `<sessionId>/status.json` + `events.jsonl`). */
data class LaunchSpec(
    val command: Array<String>,
    val env: Map<String, String>,
    val cwd: String,
    val sessionId: String = "",
    val stateDir: String = "",
    val runtimeSettingsPath: String? = null,
    val tabTitle: String = "Local",
    /** Process-icon name shown on the tab from session start (claude/codex/…). */
    val icon: String = "claude",
) {
    /** True when this session feeds the status bridge (hooks + statusLine injected). */
    val hasBridge: Boolean get() = sessionId.isNotEmpty() && stateDir.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaunchSpec) return false
        return command.contentEquals(other.command) && sessionId == other.sessionId
    }

    override fun hashCode(): Int = command.contentHashCode() * 31 + sessionId.hashCode()
}
