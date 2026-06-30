package com.duckyman.plugin.ccglyph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Auto-activate the CCGlyph tool window when a project opens, so the terminal icon is
 * immediately visible in the tool-window stripe and the panel is ready to use without the
 * user hunting through View → Tool Windows.
 *
 * Registered via <projectActivity> in plugin.xml.
 */
class CCGlyphStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Show the tool-window icon immediately (EDT).
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                val tw = ToolWindowManager.getInstance(project).getToolWindow("CCGlyph")
                tw?.show(null)
            }
        }
        // Pre-extract xterm static assets (1.3MB) on a background thread so the first terminal
        // tab opens fast — the user never waits for this I/O.
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { TerminalBrowserPanel.ensureAssetsExtracted() }
        }
    }
}
