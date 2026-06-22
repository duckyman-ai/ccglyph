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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
            if (profile.resumeLast) add("--resume")
            if (profile.extraArgs.isNotBlank()) addAll(profile.extraArgs.trim().split(Regex("\\s+")))
        }

        // 6) Wrap in a login shell; optionally `claude update` first (runs in the terminal, then claude).
        val shell = resolveHostShell()
        val claudeCmd = (if (isWin) "claude " else "exec claude ") +
            claudeArgs.joinToString(" ") { if (isWin) shellQuoteWin(it) else shellQuote(it) }
        val script = if (profile.updateBeforeStart) "claude update && $claudeCmd" else claudeCmd
        val command = if (isWin) arrayOf(shell, "-NoLogo", "-Command", script)
                      else arrayOf(shell, "-l", "-c", script)

        // 7) Process env: profile vars (also) + bridge vars + optional CLAUDE_CONFIG_DIR isolation.
        val env = buildMap {
            putAll(profileEnv)
            if (injectBridge) {
                put("CCGLYPH_SESSION_ID", sessionId)
                put("CCGLYPH_STATE_DIR", BridgeSupport.stateRoot.absolutePath)
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
