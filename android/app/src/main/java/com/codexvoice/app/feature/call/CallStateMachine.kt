package com.codexvoice.app.feature.call

import com.codexvoice.app.core.model.CallReason
import com.codexvoice.app.core.model.Task

enum class CallState { IDLE, INCOMING, ACTIVE, ENDED, DECLINED }
data class CallSession(
    val id: String, val task: Task, val reason: CallReason,
    val startedAt: Long, val answeredAt: Long? = null, val endedAt: Long? = null,
    val demoMuted: Boolean = false,
)
data class CallSnapshot(val state: CallState = CallState.IDLE, val session: CallSession? = null)

/** Pure local call lifecycle. No microphone, audio transport or background ringing. */
class CallStateMachine(private val now: () -> Long = System::currentTimeMillis) {
    var snapshot = CallSnapshot()
        private set
    private var sequence = 0
    fun incoming(task: Task, reason: CallReason): Boolean {
        if (snapshot.state != CallState.IDLE) return false
        snapshot = CallSnapshot(CallState.INCOMING, CallSession("demo-call-${++sequence}", task, reason, now()))
        return true
    }
    fun answer(): Boolean = transition(CallState.INCOMING, CallState.ACTIVE) { it.copy(answeredAt = now()) }
    fun decline(): Boolean = transition(CallState.INCOMING, CallState.DECLINED) { it.copy(endedAt = now()) }
    fun hangUp(): Boolean = transition(CallState.ACTIVE, CallState.ENDED) { it.copy(endedAt = now()) }
    fun toggleMute(): Boolean {
        if (snapshot.state != CallState.ACTIVE) return false
        snapshot = snapshot.copy(session = snapshot.session!!.let { it.copy(demoMuted = !it.demoMuted) })
        return true
    }
    fun dismiss(): Boolean {
        if (snapshot.state !in setOf(CallState.ENDED, CallState.DECLINED)) return false
        snapshot = CallSnapshot()
        return true
    }
    private fun transition(from: CallState, to: CallState, update: (CallSession) -> CallSession): Boolean {
        if (snapshot.state != from) return false
        snapshot = CallSnapshot(to, update(snapshot.session!!))
        return true
    }
}

