package com.codexvoice.app
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.call.*
import org.junit.Assert.*
import org.junit.Test

class CallStateMachineTest {
    private var clock = 1000L
    private val task = Task("demo", "Task", "Project", TaskStatus.COMPLETED, 1000, 10, "Sample")
    private fun machine() = CallStateMachine { clock }
    @Test fun startsIdle() { assertEquals(CallSnapshot(), machine().snapshot) }
    @Test fun answerWithoutIncomingRejected() { val m = machine(); assertFalse(m.answer()); assertEquals(CallState.IDLE, m.snapshot.state) }
    @Test fun declineWithoutIncomingRejected() { assertFalse(machine().decline()) }
    @Test fun hangUpWithoutActiveRejected() { assertFalse(machine().hangUp()) }
    @Test fun muteWithoutActiveRejected() { assertFalse(machine().toggleMute()) }
    @Test fun idleDismissRejected() { assertFalse(machine().dismiss()) }
    @Test fun incomingPreservesTaskAndReason() {
        val m = machine(); assertTrue(m.incoming(task, CallReason.FAILURE))
        assertEquals(CallState.INCOMING, m.snapshot.state)
        assertEquals(task, m.snapshot.session!!.task)
        assertEquals(CallReason.FAILURE, m.snapshot.session!!.reason)
        assertEquals(1000L, m.snapshot.session!!.startedAt)
    }
    @Test fun secondIncomingDoesNotReplaceSession() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); val before = m.snapshot
        assertFalse(m.incoming(task.copy(id = "other"), CallReason.FAILURE)); assertEquals(before, m.snapshot)
    }
    @Test fun answeredTimestampAndEndArePreserved() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); clock = 2000
        assertTrue(m.answer()); assertEquals(2000L, m.snapshot.session!!.answeredAt)
        clock = 3000; assertTrue(m.hangUp())
        assertEquals(CallState.ENDED, m.snapshot.state); assertEquals(3000L, m.snapshot.session!!.endedAt)
        assertTrue(m.dismiss()); assertEquals(CallSnapshot(), m.snapshot)
    }
    @Test fun declinedHasNoAnswerTime() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); clock = 2000
        assertTrue(m.decline()); assertEquals(CallState.DECLINED, m.snapshot.state)
        assertNull(m.snapshot.session!!.answeredAt); assertEquals(2000L, m.snapshot.session!!.endedAt)
        assertTrue(m.dismiss())
    }
    @Test fun incomingCannotHangUpOrDismiss() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); assertFalse(m.hangUp()); assertFalse(m.dismiss())
    }
    @Test fun activeCannotAnswerOrDeclineAgain() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); m.answer()
        assertFalse(m.answer()); assertFalse(m.decline()); assertFalse(m.dismiss())
    }
    @Test fun muteTogglesOnlyDemoState() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); m.answer()
        assertTrue(m.toggleMute()); assertTrue(m.snapshot.session!!.demoMuted)
        assertTrue(m.toggleMute()); assertFalse(m.snapshot.session!!.demoMuted)
    }
    @Test fun subsequentCallHasDifferentLocalId() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); val first = m.snapshot.session!!.id
        m.decline(); m.dismiss(); m.incoming(task, CallReason.FAILURE)
        assertNotEquals(first, m.snapshot.session!!.id)
    }
    @Test fun terminalActionsAreRejected() {
        val m = machine(); m.incoming(task, CallReason.COMPLETION); m.answer(); m.hangUp()
        assertFalse(m.answer()); assertFalse(m.decline()); assertFalse(m.hangUp()); assertFalse(m.toggleMute())
    }
}

