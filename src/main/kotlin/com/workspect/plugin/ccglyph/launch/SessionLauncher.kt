@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.workspect.plugin.ccglyph.launch

import com.workspect.plugin.ccglyph.CCGlyphSettings
import com.workspect.plugin.ccglyph.bridge.BridgeSupport
import com.workspect.plugin.ccglyph.profile.Profile
import com.workspect.plugin.ccglyph.profile.envMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Resolves a [Profile] into a [LaunchSpec]: builds/merges the runtime settings.json (profile env
 *  overriding the settings.json `env` block, + bridge statusLine/hooks), then constructs the
 *  `exec claude` argv run inside a login shell (so PATH/rc are set up and `claude` is found). */
object SessionLauncher {

    private val jsonFmt = Json { prettyPrint = true }
    private val log = com.intellij.openapi.diagnostic.logger<SessionLauncher>()

    /** A plain interactive login shell — no Claude, no bridge, no status effects. Used for the "+" new-tab /
     *  first-tab / reopen paths so a quick terminal matches the universal "new terminal = shell" convention
     *  (a Claude session is launched deliberately, via the Profiles button with a profile). */
    fun plainShell(workDir: String): LaunchSpec {
        val shell = resolveHostShell()
        val command = if (CCGlyphSettings.isWindows) arrayOf(shell, "-NoLogo")
                      else arrayOf(shell, "--login")
        return LaunchSpec(command = command, env = emptyMap(), cwd = workDir, icon = "shell", tabTitle = "")
    }

    fun launch(profile: Profile, projectDir: String, injectBridge: Boolean): LaunchSpec {
        val sessionId = UUID.randomUUID().toString()
        val sessionStateDir = File(BridgeSupport.stateRoot, sessionId).apply { mkdirs() }
        val isWin = CCGlyphSettings.isWindows

        // 1) Base settings: read the profile's settings.json if a path is set (path-driven).
        val base = readBaseSettings(profile)

        // 2) Profile env overrides the settings.json `env` block key-for-key (merged INTO the settings
        //    so claude applies it; also set as real process env below for non-claude tools).
        val profileEnv = profile.envMap()
        val envOverlay: JsonObject = if (profileEnv.isNotEmpty()) buildJsonObject {
            putJsonObject("env") { for ((k, v) in profileEnv) put(k, v) }
        } else buildJsonObject {}
        val withEnv = deepMerge(base, envOverlay)

        // 3) Merge the bridge statusLine + hooks on top (deep merge).
        val merged: JsonObject =
            if (injectBridge) deepMerge(withEnv, BridgeSupport.injectionJson(sessionId, BridgeSupport.stateRoot.absolutePath)) as JsonObject
            else withEnv as JsonObject

        // 4) Write the runtime settings.json handed to `claude --settings` (only if non-empty).
        val runtimePath: String? = if (merged.isNotEmpty()) {
            val f = File(sessionStateDir, "settings.json")
            Files.write(f.toPath(), jsonFmt.encodeToString(JsonElement.serializer(), merged).toByteArray())
            f.absolutePath
        } else null

        // 5) Build the claude argv (settings/model/permission-mode/resume/extra).
        val claudeArgs = buildList {
            if (runtimePath != null) { add("--settings"); add(runtimePath) }
            if (profile.model.isNotBlank()) { add("--model"); add(profile.model) }
            if (profile.permissionMode.isNotBlank()) { add("--permission-mode"); add(profile.permissionMode) }
            if (profile.extraArgs.isNotBlank()) addAll(profile.extraArgs.trim().split(Regex("\\s+")))
        }

        // 6) Wrap in a login shell; optionally `claude update` first (runs in the terminal, then claude).
        val shell = resolveHostShell()
        val claudeCmd = (if (isWin) "claude " else "exec claude ") +
            claudeArgs.joinToString(" ") { if (isWin) shellQuoteWin(it) else shellQuote(it) }
        val script = if (CCGlyphSettings.getInstance().state.updateClaudeBeforeStart) "claude update && $claudeCmd" else claudeCmd
        val command = if (isWin) arrayOf(shell, "-NoLogo", "-Command", script)
                      else arrayOf(shell, "-l", "-c", script)

        // 7) Process env: profile vars (also) + bridge vars + optional CLAUDE_CONFIG_DIR isolation.
        val env = buildMap {
            putAll(profileEnv)
            if (injectBridge) {
                put("CCGLYPH_SESSION_ID", sessionId)
                put("CCGLYPH_STATE_DIR", BridgeSupport.stateRoot.absolutePath)
                // The user's own statusLine command — handed to the bridge via env (not the command line, to
                // avoid shell-quoting issues) so it can pass it through instead of swallowing it. Their
                // statusline keeps rendering in the terminal AND the chip still works (same JSON, two views).
                resolveUserStatusLine(profile, base)?.let { put("CCGLYPH_USER_STATUSLINE", it) }
            }
            if (profile.configDir.isNotBlank()) put("CLAUDE_CONFIG_DIR", expandPath(profile.configDir))
        }

        return LaunchSpec(
            command = command,
            env = env,
            cwd = projectDir,
            sessionId = sessionId,
            stateDir = BridgeSupport.stateRoot.absolutePath,
            runtimeSettingsPath = runtimePath,
            tabTitle = profile.name.ifBlank { "Claude" },
            icon = profile.icon.ifBlank { "claude" },
        )
    }

    /** The user's own statusLine command (if any), so the bridge can pass it through. Looked up in the
     *  profile's settings.json first, then the global / isolated-config-dir settings.json. Only `command`-type
     *  statusLines are passed through. Returns null when the user has none (→ the chip is the only status view). */
    private fun resolveUserStatusLine(profile: Profile, base: JsonObject): String? {
        statusLineCommandOf(base)?.let { return it }
        val cfgDir = profile.configDir.takeIf { it.isNotBlank() }?.let { expandPath(it) }
            ?: File(System.getProperty("user.home"), ".claude").path
        runCatching {
            val text = File(cfgDir, "settings.json").takeIf { it.isFile }?.readText() ?: return@runCatching null
            statusLineCommandOf(Json.parseToJsonElement(text) as? JsonObject)
        }.getOrNull()?.let { return it }
        return null
    }

    /** Pull `statusLine.command` out of a settings object; null unless it's a usable command-type statusLine. */
    private fun statusLineCommandOf(obj: JsonObject?): String? {
        val sl = obj?.get("statusLine") as? JsonObject ?: return null
        val type = (sl["type"] as? JsonPrimitive)?.contentOrNull
        if (type != null && type != "command") return null   // only pass command-type statusLines through
        return (sl["command"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** Read the profile's base settings as a JsonObject (empty when no path / unreadable). */
    private fun readBaseSettings(profile: Profile): JsonObject {
        val rawPath = profile.settingsPath.ifBlank { return buildJsonObject {} }
        val file = File(expandPath(rawPath))
        if (!file.isFile) {
            warn(profile, "Settings file not found: ${file.absolutePath}")
            return buildJsonObject {}
        }
        return runCatching {
            Json.parseToJsonElement(file.readText()).let { it as? JsonObject ?: buildJsonObject {} }
        }.getOrElse {
            warn(profile, "Settings file is not a JSON object: ${file.absolutePath}")
            buildJsonObject {}
        }
    }

    /** Expand a leading `~`, the `$USER_HOME$` IntelliJ macro, and `$VAR`/`${VAR}` in a path. */
    private fun expandPath(p: String): String {
        var s = p.trim()
        s = s.replace("\$USER_HOME$", System.getProperty("user.home"))
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1)
        s = Regex("""\$\{(\w+)\}""").replace(s) { System.getenv(it.groupValues[1]) ?: it.value }
        s = Regex("""\$(\w+)""").replace(s) { System.getenv(it.groupValues[1]) ?: it.value }
        return s
    }

    private fun warn(profile: Profile, message: String) {
        log.warn("[ccglyph] profile '${profile.name}': $message")
        runCatching {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("ccglyph")
                .createNotification("CCGlyph — ${profile.name}", message, com.intellij.notification.NotificationType.WARNING)
                .notify(com.intellij.openapi.project.ProjectManager.getInstance().defaultProject)
        }
    }

    private fun resolveHostShell(): String =
        if (CCGlyphSettings.isWindows) CCGlyphSettings.defaultShell()
        else System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    private fun shellQuoteWin(s: String): String =
        if (s.contains(' ') || s.contains('"')) "\"" + s.replace("\"", "\\\"") + "\"" else s

    /** Deep-merge [overlay] onto [base]: objects recurse, arrays concatenate, scalars overwrite. */
    private fun deepMerge(base: JsonElement, overlay: JsonElement): JsonElement = when {
        base is JsonObject && overlay is JsonObject -> buildJsonObject {
            for ((k, v) in base) put(k, deepMerge(v, overlay[k] ?: JsonNull))
            for ((k, v) in overlay) if (k !in base) put(k, v)
        }
        base is JsonArray && overlay is JsonArray -> buildJsonArray { addAll(base); addAll(overlay) }
        overlay is JsonNull -> base
        else -> overlay
    }
}
