package com.workspect.plugin.ccglyph.profile

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

/**
 * Application-level store of Claude Code launch profiles, persisted in `ccglyph-profiles.xml`.
 *
 * Registered as an `applicationService` in plugin.xml. The New-Session popup reads
 * [profiles]; [SessionLauncher][com.workspect.plugin.ccglyph.launch.SessionLauncher] resolves a
 * chosen profile into a [LaunchSpec][com.workspect.plugin.ccglyph.launch.LaunchSpec].
 */
@State(name = "CCGlyphProfiles", storages = [Storage("ccglyph-profiles.xml")])
class ProfileService : PersistentStateComponent<ProfileService.State> {

    data class State(
        var profiles: MutableList<Profile> = mutableListOf(),
        var lastUsedProfileId: String = "",
        /** When true (default), every plugin-launched session injects the status bridge. */
        var injectBridgeByDefault: Boolean = true,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    /** All profiles, seeding a "Claude" profile on first use so the popup is never empty. */
    fun profiles(): MutableList<Profile> {
        if (myState.profiles.isEmpty()) {
            myState.profiles.add(Profile(id = newId(), name = "Claude"))
        }
        return myState.profiles
    }

    fun byId(id: String): Profile? = profiles().firstOrNull { it.id == id }
    fun lastUsed(): Profile? = byId(myState.lastUsedProfileId) ?: profiles().firstOrNull()
    fun setLastUsed(id: String) { myState.lastUsedProfileId = id }
    fun add(profile: Profile) { profiles().add(profile) }
    fun remove(profile: Profile) { profiles().remove(profile) }
    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** A name unique across profiles (excluding [exceptId]); appends "(2)", "(3)", … on collision. */
    fun uniqueName(base: String, exceptId: String? = null): String {
        val taken = profiles().filter { it.id != exceptId }.map { it.name }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    companion object {
        fun getInstance(): ProfileService =
            ApplicationManager.getApplication().getService(ProfileService::class.java)
    }
}
