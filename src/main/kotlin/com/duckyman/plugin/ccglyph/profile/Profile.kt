package com.duckyman.plugin.ccglyph.profile

/**
 * A launch recipe for a Claude Code session.
 *
 * Stored as a list in [ProfileService] and picked from the New-Session popup. **Path-driven**:
 * a non-blank [settingsPath] is read and merged into the session; [env] stacks on top of
 * (and overrides) the settings.json `env` block. Fields are `var` with defaults so IntelliJ's
 * XML state serialisation (com.intellij.util.xmlb) can round-trip them.
 */
data class Profile(
    var id: String = "",
    var name: String = "New Profile",
    /** Path to a settings.json to merge into the session (blank = claude's own defaults). */
    var settingsPath: String = "",
    /** `--model` override (e.g. "claude-opus-4-8"). Blank = claude's default. */
    var model: String = "",
    /** `--permission-mode`: default | acceptEdits | plan | bypassPermissions. Blank = unset. */
    var permissionMode: String = "",
    /** Extra raw `claude` args (e.g. "--resume <id>"). */
    var extraArgs: String = "",
    /** Extra env vars, one `KEY=VALUE` per line. Overrides the settings.json `env` block key-for-key. */
    var env: String = "",
    /** Optional CLAUDE_CONFIG_DIR — full isolation of credentials/history/projects. */
    var configDir: String = "",
    /** Process-icon name for the tab (claude/codex/...). Blank = auto-detect. */
    var icon: String = "",
)

/** Parse the `env` field into a map: one `KEY=VALUE` per line (blanks / `#` comments skipped). */
fun Profile.envMap(): Map<String, String> =
    env.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .associate {
            val eq = it.indexOf('=')
            it.substring(0, eq).trim() to it.substring(eq + 1)
        }
