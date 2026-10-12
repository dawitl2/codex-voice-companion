package com.codexvoice.app.core.calls

import android.content.Context

/** Fixed lifecycle codes only; no bearer, IDs, endpoints, samples or exception text. */
object CallDiagnostics {
    private val stages = setOf("offer_received","answer_requested","grant_stale","grant_not_visible","grant_rejected","service_requested","media_ready","capture_started","media_lost","user_ended","ui_dismissed","control_timeout")
    @Synchronized fun record(context: Context, stage: String) {
        require(stage in stages); val prefs = context.getSharedPreferences("call-diagnostics", Context.MODE_PRIVATE)
        prefs.edit().putString("last", stage).putInt(stage, prefs.getInt(stage, 0) + 1).commit()
    }
    fun summary(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences("call-diagnostics", Context.MODE_PRIVATE)
        return mapOf("last" to (prefs.getString("last", "none") ?: "none")) + stages.associateWith { prefs.getInt(it, 0) }
    }
}
