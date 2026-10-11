package com.codexvoice.app

import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.remote.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionRepositoryTest {
    private fun snapshot(mode: String = "mock") = RemoteProtocol.parse(RemoteProtocol.message("state.snapshot", snapshotFixture().plus("mode" to mode))).snapshot!!
    @Test fun mapsRealHistoryWithAssociationsAndDuration() {
        val history = remoteHistory(snapshot("real")); assertEquals(1, history.size)
        assertEquals("event-1", history.single().id); assertEquals("Fixture", history.single().title)
        assertEquals("Project", history.single().project); assertEquals(60L, history.single().durationSeconds)
        assertFalse(history.single().simulated); assertEquals(TaskStatus.COMPLETED, history.single().status)
    }
    @Test fun marksBridgeMockAsSimulatedAndMapsSelectedCurrentTurn() {
        assertTrue(remoteHistory(snapshot()).single().simulated)
        assertEquals("turn-1", remoteCurrent(snapshot())!!.id)
    }
    @Test fun unknownOutcomeNeverBecomesCompletedOrRunning() { assertEquals(TaskStatus.UNKNOWN, remoteStatus("unknown")) }
    @Test fun remoteEmptyStateContainsNoDemoFixtures() = runTest {
        val client = RemoteClient(backgroundScope, FakeSockets(), MemoryCredentials())
        val repo = CompanionTaskRepository(client, backgroundScope); runCurrent()
        assertFalse(repo.isDemo.value); assertNull(repo.current.value); assertTrue(repo.history.value.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, repo.connection.value)
    }
    @Test fun demoModeIsExplicitAndPersistsChoiceWithoutMixingHistory() = runTest {
        val client = RemoteClient(backgroundScope, FakeSockets(), MemoryCredentials()); var saved = false
        val repo = CompanionTaskRepository(client, backgroundScope, saveDemo = { saved = it }); runCurrent()
        repo.selectDemo(true); runCurrent(); assertTrue(saved); assertEquals(3, repo.history.value.size)
        repo.selectDemo(false); runCurrent(); assertFalse(saved); assertTrue(repo.history.value.isEmpty())
    }
    @Test fun remoteModeRejectsDemoCallCreationAndLocalHistoryEdits() = runTest {
        val repo = CompanionTaskRepository(RemoteClient(backgroundScope, FakeSockets(), MemoryCredentials()), backgroundScope); runCurrent()
        try { repo.completeDemoTask(CallReason.COMPLETION); fail() } catch (_: IllegalStateException) { }
        repo.clearHistory(); repo.resetDemo(); runCurrent(); assertTrue(repo.history.value.isEmpty())
    }
}
