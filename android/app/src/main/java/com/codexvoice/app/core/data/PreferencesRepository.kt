package com.codexvoice.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.codexvoice.app.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean)
    suspend fun setReduceMotion(value: Boolean)
    suspend fun setNotifications(value: UserPreferences) { throw IOException("Notification preferences unavailable") }
    suspend fun setCallInvitations(value: Boolean) { throw IOException("Call preferences unavailable") }
    suspend fun setSpeech(value: UserPreferences) { throw IOException("Speech preferences unavailable") }
}

class DataStorePreferencesRepository(private val store: DataStore<Preferences>) : PreferencesRepository {
    private val theme = stringPreferencesKey("theme")
    private val lightDefaultApplied = booleanPreferencesKey("light_default_applied")
    private val completion = booleanPreferencesKey("completion")
    private val failure = booleanPreferencesKey("failure")
    private val approval = booleanPreferencesKey("approval")
    private val motion = booleanPreferencesKey("reduce_motion")
    private val enabled = booleanPreferencesKey("notifications_enabled")
    private val interruption = booleanPreferencesKey("alert_interruption")
    private val sound = booleanPreferencesKey("notification_sound")
    private val vibration = booleanPreferencesKey("notification_vibration")
    private val calls = booleanPreferencesKey("call_invitations")
    private val spoken = booleanPreferencesKey("spoken_summaries")
    private val voice = stringPreferencesKey("speech_voice")
    private val rate = floatPreferencesKey("speech_rate")
    private val pitch = floatPreferencesKey("speech_pitch")
    private val automatic = booleanPreferencesKey("auto_speak_summary")
    private val autoSend = booleanPreferencesKey("auto_send_instructions")
    private val latestChatCalling = booleanPreferencesKey("latest_chat_calling_v2")
    override val preferences = store.data.onStart {
        // Apply the requested light theme to the existing phone once; later choices persist.
        store.edit { p -> if(p[lightDefaultApplied]!=true){p[theme]=ThemeMode.LIGHT.name;p[lightDefaultApplied]=true} }
        // The owner explicitly requested automatic incoming completion calls in this update.
        // This changes app preferences only; Android notification/microphone grants remain authoritative.
        store.edit { p -> if(p[latestChatCalling]!=true){p[enabled]=true;p[calls]=true;p[completion]=true;p[latestChatCalling]=true} }
    }.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { p -> UserPreferences(
        ThemeMode.entries.firstOrNull { it.name == p[theme] } ?: ThemeMode.LIGHT,
        p[completion] ?: true, p[failure] ?: true, p[approval] ?: true, p[motion] ?: false,
        p[enabled] ?: false, p[interruption] ?: false, p[sound] ?: true, p[vibration] ?: true,
        p[calls] ?: false,
        p[spoken] ?: true, p[voice] ?: "", (p[rate] ?: 1f).coerceIn(.5f,1.5f), (p[pitch] ?: 1f).coerceIn(.5f,1.5f), p[automatic] ?: true, p[autoSend] ?: true,
    ) }
    override suspend fun setTheme(mode: ThemeMode) { store.edit { it[theme] = mode.name } }
    override suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) {
        store.edit { it[this.completion] = completion; it[this.failure] = failure; it[this.approval] = approval }
    }
    override suspend fun setReduceMotion(value: Boolean) { store.edit { it[motion] = value } }
    override suspend fun setCallInvitations(value: Boolean) { store.edit { it[calls] = value } }
    override suspend fun setSpeech(value: UserPreferences) { store.edit {
        require(value.speechVoice.length <= 128 && value.speechRate.isFinite() && value.speechPitch.isFinite())
        it[spoken]=value.spokenSummaries; it[voice]=value.speechVoice; it[rate]=value.speechRate.coerceIn(.5f,1.5f); it[pitch]=value.speechPitch.coerceIn(.5f,1.5f); it[automatic]=value.autoSpeakSummary;it[autoSend]=value.autoSendInstructions
    } }
    override suspend fun setNotifications(value: UserPreferences) { store.edit {
        it[enabled] = value.notificationsEnabled; it[completion] = value.callOnCompletion; it[failure] = value.callOnFailure; it[approval] = value.callOnApproval
        it[interruption] = value.alertOnInterruption; it[sound] = value.notificationSound; it[vibration] = value.notificationVibration
    } }
}

