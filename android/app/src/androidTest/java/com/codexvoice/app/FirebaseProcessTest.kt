package com.codexvoice.app

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.model.UserPreferences
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Separate instrumentation phases allow the PC to observe true process absence before sending. */
class FirebaseProcessTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun processPhase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        assumeTrue("Explicit physical FCM fixture required", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val phase = InstrumentationRegistry.getArguments().getString("processPhase") ?: error("phase required")
        val directory = File(context.noBackupFilesDir, "device-verification").apply { mkdirs() }
        val originalFile = File(directory, "process-original-preferences.json"); val proofFile = File(directory, "process-test-proof.json")
        val credentials = KeystoreCredentialStore(context); val settings = DataStorePreferencesRepository(context.preferencesStore)
        val store = PushStore.get(context)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        fun until(predicate: () -> Boolean) { val deadline = System.currentTimeMillis() + 60000; while (!predicate()) { check(System.currentTimeMillis() < deadline) { "Process fixture phase timed out" }; Thread.sleep(100) } }
        fun text(value: String) = try { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
        suspend fun cleanup() {
            if (!originalFile.exists()) return
            val original = Gson().fromJson(originalFile.readText(), UserPreferences::class.java)
            val ownedId = credentials.load()?.deviceId
            val ownedEvents = store.state.value.events.filter { it.pairingId == ownedId }.map { it.eventId }.toSet()
            for (eventId in ownedEvents) TaskNotifications.cancel(context, eventId)
            settings.setNotifications(original)
            store.update { it.copy(preferences = original.pushPreferences(), events = it.events.filterNot { event -> event.eventId in ownedEvents },
                seen = it.seen.filterKeys { id -> id !in ownedEvents }, receipts = it.receipts.filterNot { receipt -> receipt.eventId in ownedEvents }) }
            credentials.clear(); originalFile.delete(); proofFile.delete()
            assertNull(credentials.load()); assertEquals(original.notificationsEnabled, settings.preferences.first().notificationsEnabled)
        }
        when (phase) {
            "setup" -> {
                assumeTrue("Preserve personal pairing", credentials.load() == null)
                assumeTrue("Respect system notification block", TaskNotifications.permission(context))
                originalFile.writeText(Gson().toJson(settings.preferences.first())) // Non-secret preferences only.
                var prepared = false
                try {
                    val fixture = JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
                    settings.setNotifications(settings.preferences.first().copy(notificationsEnabled = false, callOnCompletion = true))
                    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    until { text("Settings") }; compose.onNode(hasText("Settings") and hasClickAction()).performClick()
                    compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor")); compose.onNodeWithText("PC monitor").performClick()
                    compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
                    compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
                    compose.onNodeWithText("Pair this PC").performScrollTo().performClick(); until { text("Connected") }
                    compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Enable notifications"))
                    compose.onNodeWithContentDescription("Enable notifications").performClick(); until { text("Registered with paired PC") }
                    proofFile.writeText(Gson().toJson(mapOf("callbacks" to PushDiagnostics.summary(context)["callbacks"], "events" to store.state.value.events.map { it.eventId })))
                    shell("input keyevent KEYCODE_HOME"); prepared = true
                } finally { if (!prepared) cleanup() }
            }
            "verify" -> {
                try {
                    val proof = JsonParser.parseString(proofFile.readText()).asJsonObject
                    val previous = proof.getAsJsonArray("events").map { it.asString }.toSet()
                    val event = store.state.value.events.single { it.eventId !in previous }
                    assertNotNull(PushParser.parse(event.data(), credentials.load(), System.currentTimeMillis()))
                    assertTrue((PushDiagnostics.summary(context)["callbacks"] as Int) > proof["callbacks"].asInt)
                    assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == event.eventId })
                    assertTrue(store.state.value.receipts.any { it.eventId == event.eventId && it.stage == "posted" })
                    assertEquals("mock", event.mode)
                } finally { cleanup() }
            }
            "cleanup" -> cleanup()
            else -> error("unknown phase")
        }
    }
}
