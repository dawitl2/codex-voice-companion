package com.codexvoice.app.core.notifications

import android.content.Context

/** Fixed stage codes only: never payloads, FIDs, credentials or SDK exception text. */
object PushDiagnostics {
    @Synchronized fun record(context: Context, stage: String) {
        if(stage !in setOf("registered", "service_received", "sender_rejected", "envelope_rejected", "local_filtered", "posted", "call_service_requested", "system_blocked", "receiver_error")) return
        val prefs = context.getSharedPreferences("push-diagnostics", Context.MODE_PRIVATE)
        prefs.edit().putString("last_stage", stage).putInt(stage, prefs.getInt(stage, 0) + 1).commit()
    }
    fun summary(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences("push-diagnostics", Context.MODE_PRIVATE)
        return mapOf("lastStage" to (prefs.getString("last_stage", "none") ?: "none"), "callbacks" to prefs.getInt("service_received", 0),
            "senderRejected" to prefs.getInt("sender_rejected", 0), "envelopeRejected" to prefs.getInt("envelope_rejected", 0), "posted" to prefs.getInt("posted", 0))
    }
}
