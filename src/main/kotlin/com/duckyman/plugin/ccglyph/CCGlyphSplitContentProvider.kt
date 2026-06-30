package com.duckyman.plugin.ccglyph

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.toolWindow.ToolWindowSplitContentProvider
import com.intellij.ui.content.Content

/**
 * Native side-by-side split for the CCGlyph tool window — **Split Right / Split Down**, Change Orientation,
 * Unsplit, Move/Goto splitter, all the platform's own actions, exactly like the built-in terminal.
 *
 * Registered via the `com.intellij.toolWindow.splitContentProvider` extension point with
 * `toolWindowId="CCGlyph"` in plugin.xml. The platform invokes `createContentCopy` when the user splits a
 * terminal; we return a fresh terminal Content and the platform lays it out beside the source as a split pane
 * (it owns the splitter + the split actions + per-pane layout — we do none of that ourselves).
 *
 * `@Experimental`/`ToolWindowSplitContentProvider` exists on 2024.2+ (the plugin builds against 2025.3). On
 * 2024.1 the extension point is unknown → the registration is ignored and this class is never loaded, so the
 * core tab feature still works there (just no split).
 *
 * CWD: until OSC 7 directory tracking exists, a split pane opens at the project root rather than the source
 * pane's directory (a known gap — see CLAUDE.md "Known Constraints").
 */
class CCGlyphSplitContentProvider : ToolWindowSplitContentProvider {

    override fun createContentCopy(project: Project, content: Content): Content {
        // Mark "splitting" so the close-confirmation veto (contentRemoveQuery) doesn't pop the "terminate session"
        // dialog while the platform MOVES the (possibly running) terminal's content into a split cell. Cleared on the
        // next EDT tick — splitWithContent runs synchronously on the EDT right after this returns, so the flag is still
        // set while the content moves fire. createContentCopy is @RequiresEdt → safe to schedule the clear with invokeLater.
        CCGlyphContent.splitting = true
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { CCGlyphContent.splitting = false }
        // The panel needs a Disposable parent tied to the tool window's lifetime. Look up the tool window
        // (it's open during a split); fall back to the project if it isn't found (Project is Disposable).
        val disposable = ToolWindowManager.getInstance(project).getToolWindow("CCGlyph")?.disposable
            ?: project
        // CWD tracking (OSC 7) not implemented yet → fall back to the project folder.
        val workDir = project.basePath ?: System.getProperty("user.home")
        // Reuses the exact same wiring as a new tab (panel, process→tab title/icon, close-confirmation, disposal).
        return CCGlyphContent.createContent(project, disposable, workDir)
    }
}
