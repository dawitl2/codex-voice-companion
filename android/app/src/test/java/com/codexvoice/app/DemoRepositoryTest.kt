package com.codexvoice.app
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.demo.DemoTaskRepository
import org.junit.Assert.*
import org.junit.Test

class DemoRepositoryTest {
    @Test fun startsHonestAndDeterministic() {
        val a = DemoTaskRepository(); val b = DemoTaskRepository()
        assertEquals(ConnectionStatus.DEMO, a.connection.value)
        assertEquals(a.history.value, b.history.value)
        assertEquals(TaskStatus.RUNNING, a.current.value!!.status)
        assertTrue(a.history.value.all { it.summary.contains("Sample") })
    }
    @Test fun completionUpdatesCurrentAndHistory() {
        val r = DemoTaskRepository { 9999999999999L }; val t = r.completeDemoTask(CallReason.COMPLETION)
        assertEquals(TaskStatus.COMPLETED, t.status); assertEquals(t, r.current.value)
        assertEquals(t, r.history.value.first())
    }
    @Test fun failureIsDistinct() {
        val t = DemoTaskRepository().completeDemoTask(CallReason.FAILURE)
        assertEquals(TaskStatus.FAILED, t.status); assertTrue(t.summary.contains("simulated"))
    }
    @Test fun eventsUseUniqueLocalIds() {
        val r = DemoTaskRepository { 1000 }; assertNotEquals(r.completeDemoTask(CallReason.FAILURE).id, r.completeDemoTask(CallReason.FAILURE).id)
    }
    @Test fun clearAndResetProduceMeaningfulStates() {
        val r = DemoTaskRepository(); r.clearHistory(); assertTrue(r.history.value.isEmpty())
        r.resetDemo(); assertEquals(3, r.history.value.size); assertEquals(TaskStatus.RUNNING, r.current.value!!.status)
    }
    @Test fun historyIsBounded() {
        val r = DemoTaskRepository(); repeat(70) { r.completeDemoTask(CallReason.COMPLETION) }
        assertEquals(50, r.history.value.size)
    }
    @Test fun sortingIsChronologicalWithoutMutatingSource() {
        val source = DemoTaskRepository().history.value.reversed(); val sorted = sortedHistory(source)
        assertTrue(sorted.zipWithNext().all { (a, b) -> a.updatedAt >= b.updatedAt })
        assertNotEquals(source, sorted)
    }
    @Test fun failureFilterOnlyReturnsFailures() {
        val source = DemoTaskRepository().history.value
        val failures = sortedHistory(source, TaskStatus.FAILED)
        assertEquals(1, failures.size); assertEquals(TaskStatus.FAILED, failures.single().status)
    }
    @Test fun emptyHistoryAndAllStatusLabelsSupported() {
        assertTrue(sortedHistory(emptyList()).isEmpty())
        assertEquals(TaskStatus.entries.size, TaskStatus.entries.map { it.label() }.distinct().size)
    }
}

