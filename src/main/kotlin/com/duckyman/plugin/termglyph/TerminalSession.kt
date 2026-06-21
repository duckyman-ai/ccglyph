package com.duckyman.plugin.termglyph

import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.charset.StandardCharsets

class TerminalSession(
    private val onOutput: (String) -> Unit,
    private val workDir: String,
    private val darkBg: Boolean = true,
    /** Shell path resolved from the IDE's terminal settings (TerminalProjectOptionsProvider).
     *  When non-blank this is the same shell the user chose for the built-in terminal and takes
     *  precedence over TermGlyph's own settings.  Null/blank → fall back to the resolution below. */
    ideShellPath: String? = null,
) {
    private val isWindows = TermGlyphSettings.isWindows

    // On Unix the user's login shell + --login to read .zshrc/.bashrc.
    // On Windows shells don't use --login (PowerShell / cmd.exe don't recognise it).
    // Priority:
    //  1. TermGlyph's own shellPath setting (what the user picked in Settings → Tools → TermGlyph) — this
    //     MUST win, otherwise the IDE's built-in terminal shell setting silently overrides the user's
    //     explicit TermGlyph choice (e.g. they pick pwsh 7 here but the IDE default powershell.exe 5.1 runs).
    //  2. IDE's terminal setting (TerminalProjectOptionsProvider) as a fallback.
    //  3. Platform-specific default (PowerShell on Windows, $SHELL or /bin/zsh on Unix)
    private val shellPath: String = run {
        val configured = TermGlyphSettings.getInstance().state.shellPath.trim()
        if (configured.isNotBlank()) {
            validatePlatform(configured)?.let { return@run it }
        }
        if (!ideShellPath.isNullOrBlank()) {
            validatePlatform(ideShellPath)?.let { return@run it }
        }
        TermGlyphSettings.defaultShell()
    }

    /** Returns null if [path] can't spawn on this platform (so the caller falls back to the default):
     *  - blank, or a Unix "/" path on Windows (CreateProcess Code 2);
     *  - an absolute Windows path (drive-letter or UNC) whose file doesn't exist. (Bare names like
     *    "powershell.exe" are kept — PTY4J resolves them via PATH, so File.exists() would wrongly reject them.) */
    private fun validatePlatform(path: String): String? {
        if (path.isBlank()) return null
        if (isWindows) {
            if (path.startsWith("/")) return null
            val looksAbsolute = path.length >= 2 && path[1] == ':' || path.startsWith("\\\\")
            if (looksAbsolute && !java.io.File(path).exists()) return null
        }
        return path
    }

    /** Build the shell launch command.
     *  - Unix: `--login` (reads .zshrc/.bashrc).
     *  - Windows PowerShell: `-NoExit -Command` sets the Console output/input encoding to UTF-8 so the
     *    session emits UTF-8 (Windows shells default to a legacy code page → our UTF-8 reader would
     *    otherwise show mojibake and break emoji/Thai), then drops to the interactive prompt.
     *  - Windows cmd: `/k chcp 65001` switches the console to the UTF-8 code page, then stays interactive.
     *  - Other Windows shells (wsl, …): launched bare. */
    private fun buildCommand(shell: String): Array<String> {
        if (!isWindows) return arrayOf(shell, "--login")
        val name = java.io.File(shell).name.lowercase()
        return when {
            name.contains("pwsh") -> arrayOf(
                // pwsh 7+ (.NET Core) on a UTF-8 system (Windows 11 default + the JBR's file.encoding=UTF-8) already
                // defaults to UTF-8 output — we launch it INTERACTIVELY (just -NoLogo) with NO -Command encoding
                // hack, exactly like the built-in IDE terminal. Setting [Console]::OutputEncoding / chcp from a
                // -Command here was mangling supplementary chars (emoji) to U+FFFD in the PTY output; leaving pwsh
                // to its default avoids that. (If a non-UTF-8 system shows mojibake, the encoding hack can return
                // — but it breaks emoji, so default-first is the better trade.)
                shell, "-NoLogo"
            )
            name.contains("powershell") -> arrayOf(
                // Windows PowerShell 5.1 (.NET Framework): [Console]::OutputEncoding does NOT reliably switch
                // the console code page, so `chcp 65001` IS required here. (5.1's emoji support is poor anyway,
                // and pwsh 7 is the preferred shell — see defaultShell.)
                shell, "-NoLogo", "-NoExit", "-Command",
                "chcp 65001 > \$null; " +
                    "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " +
                    "[Console]::InputEncoding=[System.Text.Encoding]::UTF8; " +
                    "\$OutputEncoding=[System.Text.Encoding]::UTF8"
            )
            name == "cmd.exe" -> arrayOf(shell, "/k", "chcp 65001 >nul")
            else -> arrayOf(shell)
        }
    }

    // Start the PTY. On Windows we FORCE ConPTY first (PtyProcessBuilder.setUseWinConPty(true)): ConPTY is
    // the modern pseudoconsole and handles emoji / wide / supplementary chars correctly, whereas the legacy
    // WinPty backend (which PTY4J falls back to otherwise) mangles emoji to U+FFFD — that's why emoji showed
    // as � while Thai (BMP) was fine. If ConPTY can't start (rare on Win10 1809+), we retry with the default
    // backend (WinPty) so the terminal still opens, just without emoji. We also retry across shell candidates
    // (resolved shell → default → PowerShell 5.1 → cmd) so a missing/mismatched shell path never blocks init.
    private val process: com.pty4j.PtyProcess = run {
        val sysRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val candidates = linkedSetOf(
            shellPath,
            TermGlyphSettings.defaultShell(),
            "$sysRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            "$sysRoot\\system32\\cmd.exe",
        ).toList()
        // On Windows try ConPTY first, then the default backend (WinPty). On Unix there's only one backend.
        val backends = if (isWindows) listOf(true, false) else listOf(false)
        var lastError: Throwable? = null
        for (useConPty in backends) {
            for (shell in candidates) {
                try {
                    return@run createPty(shell, useConPty)
                } catch (t: Throwable) {
                    lastError = t   // this backend+shell didn't launch — try the next
                }
            }
        }
        throw lastError ?: java.io.IOException("Couldn't create PTY")
    }

    /** Build + start one PTY for [shell] with the given backend. [useConPty] is Windows-only (ConPTY vs WinPty). */
    private fun createPty(shell: String, useConPty: Boolean): com.pty4j.PtyProcess {
        val builder = PtyProcessBuilder()
            .setCommand(buildCommand(shell))
            .setEnvironment(System.getenv().toMutableMap().apply {
                put("TERM", "xterm-256color")
                put("COLORTERM", "truecolor")
                put("COLORFGBG", if (darkBg) "15;0" else "0;15")
                put("LANG", "en_US.UTF-8")
                put("LC_ALL", "en_US.UTF-8")
            })
            .setDirectory(workDir)
            .setInitialColumns(80)
            .setInitialRows(24)
        if (isWindows) builder.setUseWinConPty(useConPty)   // true = ConPTY (emoji); false = legacy WinPty
        return builder.start()
    }

    private val writer = process.outputStream
        .bufferedWriter(StandardCharsets.UTF_8)

    // Use a Reader instead of reading raw bytes — InputStreamReader holds the leftover bytes of a
    // multi-byte char (Thai/tone marks/emoji) across read() calls so the char is never split in half,
    // preventing the "" (U+FFFD) symptom. (Previously we read byte chunks and decoded UTF-8 per
    // chunk, so a char straddling a chunk boundary became .)
    private val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)

    init {
        Thread {
            val cbuf = CharArray(4096)
            while (process.isAlive) {
                val n = runCatching { reader.read(cbuf) }.getOrDefault(-1)
                if (n == -1) break
                if (n > 0) onOutput(String(cbuf, 0, n))
            }
        }.apply {
            isDaemon = true
            name = "termglyph-reader"
            start()
        }
    }

    fun write(input: String) {
        runCatching {
            writer.write(input)
            writer.flush()
        }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching {
            process.winSize = WinSize(cols, rows)
        }
    }

    /** Live descendant processes of the shell (foreground commands such as claude/vim/build).
     *  Uses the JDK ProcessHandle (macOS = sysctl, Linux = /proc) — for prompting confirmation before closing a tab.
     *  Returns the basename of each process (deduped); an empty list means only an idle shell is running. */
    fun runningProcesses(): List<String> {
        if (!process.isAlive) return emptyList()
        return runCatching {
            ProcessHandle.of(process.pid())
                .map { ph -> ph.descendants().toList() }
                .orElse(emptyList())
        }.getOrDefault(emptyList())
            .filter { it.isAlive }
            .mapNotNull { ph -> ph.info().command().orElse(null)?.substringAfterLast('/')?.ifBlank { null } }
            .distinct()
    }

    fun destroy() {
        runCatching { process.destroy() }
    }
}

