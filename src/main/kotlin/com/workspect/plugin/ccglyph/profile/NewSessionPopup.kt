package com.workspect.plugin.ccglyph.profile

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.workspect.plugin.ccglyph.CCGlyphContent
import java.awt.Component
import java.awt.Point
import javax.swing.Icon

/** What the user picked from the New-Session popup. "Manage Profiles…" is handled internally
 *  (it opens Settings) and never reaches [NewSessionPopup.show]'s onChoose. */
sealed interface NewSessionChoice {
    /** A plain interactive shell — no Claude, no bridge, no status effects. */
    data object PlainTerminal : NewSessionChoice
    /** Start a Claude Code session with this profile. */
    data class ProfileSession(val profile: Profile) : NewSessionChoice
}

/** New-Session popup, laid out as sections separated by rules:
 *    1. Plain terminal   — a quick non-Claude shell
 *    2. <profiles>       — Claude sessions, one per profile
 *    3. Manage Profiles… — opens Settings (footer)
 *
 *  [onChoose] receives the selection (a plain terminal or a profile); "Manage Profiles…" opens the
 *  settings page instead. When [anchor] is given the popup opens just below it (for a header button);
 *  otherwise it opens centred on the focused component. */
object NewSessionPopup {

    private const val PLAIN = "__plain__"
    private const val PLAIN_LABEL = "Plain terminal"
    private const val MANAGE = "__manage__"
    private const val MANAGE_LABEL = "Manage Profiles…"

    fun show(project: Project, anchor: Component? = null, onChoose: (NewSessionChoice) -> Unit) {
        val rows = mutableListOf<String>()
        val keyForRow = mutableMapOf<String, String>()
        fun row(text: String, key: String) { rows.add(text); keyForRow[text] = key }

        // Section 1 — quick terminal (a non-Claude shell).
        row(PLAIN_LABEL, PLAIN)
        // Section 2 — profiles (Claude sessions). Remember the first profile's row text so we can draw a
        // rule above it (separating it from the Plain-terminal section).
        val profiles = ProfileService.getInstance().profiles()
        val firstProfileText: String? = profiles.firstOrNull()?.let { p ->
            labelFor(p).also { row(it, p.id) }
        }
        for (p in profiles.drop(1)) row(labelFor(p), p.id)
        // Footer — manage.
        row(MANAGE_LABEL, MANAGE)

        val step = object : BaseListPopupStep<String>("New Session", rows) {
            override fun getTextFor(value: String) = value
            override fun isSpeedSearchEnabled() = true
            // Icon per row: Plain terminal → brand; profiles → their tab icon; Manage → none.
            override fun getIconFor(value: String): Icon? {
                val key = keyForRow[value] ?: return null
                return when (key) {
                    PLAIN -> CCGlyphContent.TERMINAL_ICON
                    MANAGE -> null
                    else -> ProfileService.getInstance().byId(key)?.let { CCGlyphContent.profileIcon(it.icon) }
                }
            }
            // Rules between sections: above the first profile (Terminal ↔ Profiles) and above Manage (footer).
            override fun getSeparatorAbove(value: String): ListSeparator? =
                if (value == firstProfileText || value == MANAGE_LABEL) ListSeparator() else null
            override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
                val key = selectedValue?.let { keyForRow[it] } ?: return PopupStep.FINAL_CHOICE
                when (key) {
                    MANAGE -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        // Defer so the popup closes first (onChosen returns FINAL_CHOICE below); opening the modal
                        // settings dialog synchronously here keeps the popup open underneath it.
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, com.workspect.plugin.ccglyph.CCGlyphSettingsConfigurable::class.java)
                    }
                    PLAIN -> onChoose(NewSessionChoice.PlainTerminal)
                    else -> ProfileService.getInstance().byId(key)?.let {
                        ProfileService.getInstance().setLastUsed(it.id)
                        onChoose(NewSessionChoice.ProfileSession(it))
                    }
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        // Pad the top of the list so the first row isn't flush against the popup's top edge.
        fun findList(c: java.awt.Component): javax.swing.JList<*>? = when (c) {
            is javax.swing.JList<*> -> c
            is java.awt.Container -> c.components.firstNotNullOfOrNull { findList(it) }
            else -> null
        }
        findList(popup.content)?.border = javax.swing.BorderFactory.createEmptyBorder(6, 0, 6, 0)
        if (anchor != null && anchor.isShowing) {
            popup.show(RelativePoint(anchor, Point(0, anchor.height + 2)))
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun labelFor(p: Profile): String =
        "${p.name.ifBlank { "Profile" }}   —   ${describe(p)}"

    private fun describe(p: Profile): String = buildString {
        if (p.model.isNotBlank()) append(p.model).append(" · ")
        append(if (p.settingsPath.isNotBlank())
            "ref: " + (p.settingsPath.substringAfterLast('/').ifBlank { p.settingsPath })
        else "default settings")
    }
}
