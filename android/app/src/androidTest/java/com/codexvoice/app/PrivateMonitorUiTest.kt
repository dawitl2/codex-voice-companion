package com.codexvoice.app

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Existing MainActivity/UI, production socket and Keystore; PC tasks are simulated fixtures. */
class PrivateMonitorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun keepTestVisible() { compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    @Test fun settingsPairDashboardHistoryRotationAndForgetOverPrivateWss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run scripts/device-verification.ps1 -Ui", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val fixture = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/codex-voice-fixture.json").use {
            JsonParser.parseString(android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()).asJsonObject
        }
        val store = KeystoreCredentialStore(instrumentation.targetContext)
        assumeTrue("Preserve any existing personal pairing", store.load() == null)
        fun untilText(text: String) = compose.waitUntil(45000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"))
            compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture.get("endpoint").asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture.get("code").asString)
            compose.onNodeWithText("Pair this PC").performScrollTo().performClick()
            untilText("Connected")
            compose.onNodeWithText("Home").performClick()
            untilText("PC BRIDGE MOCK · SIMULATED")
            val control = File(instrumentation.targetContext.noBackupFilesDir, "device-verification/control.txt")
            control.parentFile!!.mkdirs(); control.writeText("complete")
            untilText("Completed")
            compose.onNodeWithText("Preview completed-task call").assertDoesNotExist()
            compose.onNodeWithText("History").performClick()
            untilText("Task history")
            compose.onAllNodesWithText("Completed").onFirst().assertExists()
            compose.onNodeWithText("Clear sample history").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"))
            untilText("Connected")
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            untilText("Settings")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"))
            untilText("Connected")
            compose.onNodeWithText("Connected").performScrollTo()
            compose.onNodeWithText("Connected").assertIsDisplayed()
            compose.onNodeWithText("Forget paired PC").performScrollTo().performClick()
            compose.onNodeWithText("Forget").performClick()
            untilText("No PC paired")
            check(store.load() == null) { "Forget must remove the production protected credential" }
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            store.clear() // This test proceeds only when the app was initially unpaired.
        }
    }
}
