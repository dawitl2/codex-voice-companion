package com.codexvoice.app.core.notifications

import com.codexvoice.app.core.remote.RemoteSnapshot
import com.google.gson.JsonObject

fun acceptsPush(event: PushEvent, preferences: PushPreferences, seen: Map<String, Long>, now: Long): Boolean =
    preferences.allows(event.kind) && event.expires > now && !seen.containsKey(event.eventId) && seen.count { it.value > now } < 500

fun notificationDetailsAvailable(event: PushEvent, paired: Boolean, connected: Boolean, snapshot: RemoteSnapshot?, details: JsonObject?): Boolean =
    paired && connected && snapshot != null && details != null && snapshot.mode == event.mode &&
        details["eventId"]?.asString == event.eventId && details["mode"]?.asString == event.mode
