# TermGlyph

<p align="center"><img src="src/main/resources/pluginIcon.svg" width="120" alt="TermGlyph"></p>

<p align="center"><b>A terminal tool window for IntelliJ-based IDEs that renders Claude Code cleanly — no overlapping, hard-to-read text — and handles complex scripts (Thai, emoji) correctly.</b></p>

<p align="center">Runs on <b>macOS</b> and <b>Windows</b>.</p>

<p align="center"><img src="docs/images/screenshot.png" width="720" alt="TermGlyph screenshot — Claude Code in a terminal tab with Thai text and emoji"></p>

TermGlyph is an **alternative terminal** that runs [xterm.js](https://xtermjs.org/) 6 (with **Unicode 11**) inside the IDE's JCEF (Chromium Embedded) browser. xterm.js handles terminal columns precisely, so tools like **Claude Code** — whose input box, borders, and dense output can otherwise overlap and become hard to read — display cleanly. It also composes combining and wide characters correctly.

> TermGlyph is a *companion* option — the IDE's built-in terminal stays fully available. Use whichever fits the task.

---

## Why

The input box, borders, and dense output of TUIs like **Claude Code** rely on precise terminal column handling. When a terminal counts columns wrong (e.g. treating a combining character as a full column), the layout drifts — text overlaps, boxes break, and it gets hard to read. TermGlyph uses xterm.js with Unicode 11, which gets column widths right, so Claude Code and other TUIs render cleanly.

It also fixes combining and wide characters — Thai vowels/tone marks (◌ั ◌้ ◌่), emoji, CJK — they compose with correct spacing.

## Features

- ✅ **Clean rendering** — Claude Code's input box/borders and dense output display without overlapping; Thai/emoji/wide chars compose correctly (xterm.js Unicode 11).
- 🗂 **Multiple tabs + native side-by-side split** — Split Right / Split Down / Change Orientation / Unsplit, exactly like the IDE's own terminal.
- 🔍 **Find** (Cmd/Ctrl+F) — navigate matches; the active match is highlighted.
- 📋 **Copy / Paste / Clear** — native right-click menu.
- 🏷 **Tab icons & titles follow the running process** — e.g. "Claude Code" with its icon while claude runs.
- 🎨 **Follows your IDE theme** — background/foreground from the editor color scheme; the 16 ANSI colours come from *Editor › Color Scheme › Console Colors*. Truecolor (`COLORTERM=truecolor`) is advertised so tools like `starship`/`bat`/`eza` are vivid.
- ⚡ **Fast** — WebGL renderer (auto-falls back to DOM), output batching, anti-freeze repaints.
- 🪟 **Settings** — *Settings → Tools → TermGlyph* for font/shell/scrollback/cursor.

## Requirements — JCEF

TermGlyph renders through **JCEF (Chromium Embedded)**, which is bundled with some IDEs and installable on others. It's declared as an **optional** dependency, so it installs everywhere; if JCEF is unavailable the tool window shows a "JCEF Required" banner with install instructions instead of the terminal.

| IDE | JCEF | How to get it |
|-----|------|---------------|
| IntelliJ IDEA **Ultimate** | ✅ Built-in | Works out of the box |
| WebStorm / PyCharm Pro / Rider / other pro IDEs | ✅ Built-in | Works out of the box |
| IntelliJ IDEA **Community** | ❌ Not available | Cannot run (no JCEF for Community) |
| Android Studio **2026.1.1+** (Quail 1) | ⚙️ Via plugin | Install **"Web Browser (JCEF)"** from Marketplace |
| Android Studio **2026.2+** (planned) | ✅ Built-in | Nothing extra needed |

`JBCefApp.isSupported()` is the single runtime gate — it's checked before any JCEF API is touched.

Minimum IDE: **2025.3** (the native split API + build target).

## Usage

| Action | Shortcut |
|--------|----------|
| New terminal tab | **+** beside the tabs |
| Close tab | **×** on the tab |
| Split right / down | right-click the tab → **Split Right / Split Down** |
| Find | **Cmd+F** (mac) / **Ctrl+F** |
| Copy | **Cmd+C** (mac) / **Ctrl+Shift+C** |
| Paste | right-click → **Paste** |
| Clear screen | **Cmd+K** (mac) / **Ctrl+K** |
| Send Ctrl+C (SIGINT) | **Ctrl+C** |
| New line (multi-line input) | **Shift+Enter** (e.g. Claude Code) |
| Settings | gear (⋮) menu → **Settings…** |

Each terminal opens at the **project folder**. The tab's icon/title follows the running process (e.g. Claude Code).

## Thai rendering test

After installing, run:

```bash
echo "สวัสดี ทดสอบสระบน: กั กิ กี กุ กู เก แก"
echo "ทดสอบวรรณยุกต์: ก่ ก้ ก๊ ก๋"
echo "ทดสอบซ้อน: เก้า แก้ไข ใช้งาน"
```

If these render with no extra spacing → it's working. ✅

## Limitations

- **Remote Development / JetBrains Gateway / SSH / dev-containers:** **not supported** — TermGlyph is **desktop-only**. Its terminal UI (xterm.js in JCEF) is a heavyweight component that renders only on the local machine, so it can't be sent over the Remote Development frontend/backend link. Use the IDE's built-in terminal for remote sessions.

## For plugin developers

Built with the [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) on Gradle 9, Kotlin 2.4, JBR 17 toolchain. The 16-colour palette is sourced from `ConsoleHighlighter`. See [`docs/USAGE.md`](docs/USAGE.md) for the full usage guide.

Marketplace payment rules (for reference, this plugin is **free**): [How plugin developers are paid](https://plugins.jetbrains.com/docs/marketplace/getting-paid.html).

## Third-party assets

- **[xterm.js](https://xtermjs.org/)** + addons (fit, unicode11, webgl) — the terminal renderer.

## License

[MIT License](LICENSE) — © 2026 Duckyman.

---

*TermGlyph is an independent project and is not affiliated with or endorsed by JetBrains, Anthropic, or the xterm.js project.*