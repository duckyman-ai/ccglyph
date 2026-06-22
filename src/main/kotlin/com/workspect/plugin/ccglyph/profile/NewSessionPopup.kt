package com.workspect.plugin.ccglyph.profile

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.Point

/** Profile picker popup: lists every profile plus a "Manage Profiles…" entry separated by a rule.
 *  [onProfile] receives the chosen profile; "Manage Profiles…" opens the settings page instead.
 *  When [anchor] is given the popup opens just below it (for a header button); otherwise it opens
 *  centred on the focused component. */
object NewSessionPopup {

    private const val MANAGE = "__manage__"
    private const val MANAGE_LABEL = "Manage Profiles…"

    fun show(project: Project, anchor: Component? = null, onProfile: (Profile?) -> Unit) {
        val rows = mutableListOf<String>()
        val keyForRow = mutableMapOf<String, String>()
        fun row(text: String, key: String) { rows.add(text); keyForRow[text] = key }

        for (p in ProfileService.getInstance().profiles()) {
            row("${p.name.ifBlank { "Profile" }}   —   ${describe(p)}", p.id)
        }
        row(MANAGE_LABEL, MANAGE)

        val step = object : BaseListPopupStep<String>("New Session", rows) {
            override fun getTextFor(value: String) = value
            override fun isSpeedSearchEnabled() = true
            // A rule above "Manage Profiles…" so it reads as a distinct footer action, not another profile.
            override fun getSeparatorAbove(value: String): ListSeparator? =
                if (value == MANAGE_LABEL) ListSeparator() else null
            override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
                val key = selectedValue?.let { keyForRow[it] } ?: return PopupStep.FINAL_CHOICE
                when (key) {
                    MANAGE -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        // Defer so the popup closes first (onChosen returns FINAL_CHOICE below); opening the modal
                        // settings dialog synchronously here keeps the popup open underneath it.
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, com.workspect.plugin.ccglyph.CCGlyphSettingsConfigurable::class.java)
                    }
                    else -> ProfileService.getInstance().byId(key)?.let {
                        ProfileService.getInstance().setLastUsed(it.id)
                        onProfile(it)
                    }
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        if (anchor != null && anchor.isShowing) {
            popup.show(RelativePoint(anchor, Point(0, anchor.height + 2)))
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun describe(p: Profile): String = buildString {
        if (p.model.isNotBlank()) append(p.model).append(" · ")
        append(if (p.settingsPath.isNotBlank())
            "ref: " + (p.settingsPath.substringAfterLast('/').ifBlank { p.settingsPath })
        else "default settings")
    }
}
