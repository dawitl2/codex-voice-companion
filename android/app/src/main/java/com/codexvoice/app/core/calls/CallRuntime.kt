package com.codexvoice.app.core.calls

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process memory only. No bearer in an Intent, DataStore, saved state or diagnostics. */
object CallRuntime {
    private val mutable = MutableStateFlow(LiveCallState()); val state = mutable.asStateFlow()
    private var pending: MediaGrant? = null
    @Synchronized fun prepare(grant: MediaGrant) { pending = grant }
    @Synchronized fun consume(): MediaGrant? = pending.also { pending = null }
    fun update(action: (LiveCallState) -> LiveCallState) { mutable.update(action) }
    fun command(context: Context, action: String) {
        if (!state.value.serviceRunning) return
        try { context.startService(Intent(context, CallAudioService::class.java).setAction(action)) }
        catch (_: Exception) { update { it.copy(problem = "Audio service control failed; use the PC to end this call.") } }
    }
    fun dismiss() { if (!state.value.serviceRunning) { pending = null; mutable.value = LiveCallState() } }
}
