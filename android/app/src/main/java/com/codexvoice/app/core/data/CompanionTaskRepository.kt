package com.codexvoice.app.core.data

import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.feature.demo.DemoTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*

fun remoteStatus(status: String) = when (status) {
    "starting", "running" -> TaskStatus.RUNNING
    "completed" -> TaskStatus.COMPLETED
    "failed" -> TaskStatus.FAILED
    "interrupted" -> TaskStatus.INTERRUPTED
    "awaitingApproval" -> TaskStatus.AWAITING_APPROVAL
    else -> TaskStatus.UNKNOWN
}
fun remoteHistory(snapshot: RemoteSnapshot): List<Task> = snapshot.history.mapNotNull { event ->
    val thread = snapshot.threads.find { it.id == event.threadId } ?: return@mapNotNull null
    val status = when (event.type) {
        "turn.started", "turn.progress" -> TaskStatus.RUNNING
        "turn.completed" -> TaskStatus.COMPLETED
        "turn.failed" -> TaskStatus.FAILED
        "turn.interrupted" -> TaskStatus.INTERRUPTED
        "approval.requested" -> TaskStatus.AWAITING_APPROVAL
        "codex.error" -> TaskStatus.UNKNOWN
        else -> return@mapNotNull null
    }
    val turn = snapshot.turns.find { it.id == event.turnId && it.threadId == event.threadId }
    val duration = turn?.finishedAt?.let { ((it - turn.startedAt) / 1000).coerceAtLeast(0) }
    Task(event.id, thread.label, thread.project, status, event.timestamp, duration, event.summary, simulated = snapshot.mode == "mock")
}.distinctBy { it.id }
fun remoteCurrent(snapshot: RemoteSnapshot): Task? {
    val thread = snapshot.threads.find { it.id == snapshot.selectedThreadId } ?: return null
    val turn = snapshot.turns.lastOrNull { it.threadId == thread.id } ?: return null
    val event = snapshot.history.lastOrNull { it.turnId == turn.id && it.threadId == thread.id }
    return Task(turn.id, thread.label, thread.project, remoteStatus(turn.status), event?.timestamp ?: turn.startedAt,
        turn.finishedAt?.let { ((it - turn.startedAt) / 1000).coerceAtLeast(0) }, event?.summary ?: "No progress summary available.", simulated = snapshot.mode == "mock")
}
data class CompanionData(val current: Task?, val history: List<Task>, val connection: ConnectionStatus, val remote: ClientState, val demo: Boolean)
class CompanionTaskRepository(private val client: RemoteClient, private val scope: CoroutineScope,
    initialDemo: Boolean = false, private val saveDemo: (Boolean) -> Unit = {}, private val demo: TaskRepository = DemoTaskRepository(),
    private val startCompanion:()->Unit={},private val stopCompanion:()->Unit={}) : TaskRepository {
    private val mutableDemo = MutableStateFlow(initialDemo); val isDemo = mutableDemo.asStateFlow()
    val remote = client.state
    val data = combine(isDemo, remote, demo.current, demo.history) { useDemo, state, sample, sampleHistory ->
        val connection = if (useDemo) ConnectionStatus.DEMO else when (state.status) {
            RemoteStatus.CONNECTED -> ConnectionStatus.CONNECTED
            RemoteStatus.CONNECTING, RemoteStatus.PAIRING, RemoteStatus.AUTHENTICATING, RemoteStatus.RECONNECTING -> ConnectionStatus.CONNECTING
            RemoteStatus.UNPAIRED, RemoteStatus.DISCONNECTED -> ConnectionStatus.DISCONNECTED
            else -> ConnectionStatus.ERROR
        }
        CompanionData(if (useDemo) sample else state.snapshot?.let(::remoteCurrent),
            if (useDemo) sampleHistory else state.snapshot?.let(::remoteHistory) ?: emptyList(), connection, state, useDemo)
    }.stateIn(scope, SharingStarted.Eagerly, CompanionData(null, emptyList(), if (initialDemo) ConnectionStatus.DEMO else ConnectionStatus.DISCONNECTED, ClientState(), initialDemo))
    override val current = data.map { it.current }.stateIn(scope, SharingStarted.Eagerly, null)
    override val history = data.map { it.history }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val connection = data.map { it.connection }.stateIn(scope, SharingStarted.Eagerly, if (initialDemo) ConnectionStatus.DEMO else ConnectionStatus.DISCONNECTED)
    fun selectDemo(enabled: Boolean) { saveDemo(enabled); mutableDemo.value = enabled; if (enabled) {client.disconnect();stopCompanion()} }
    fun foreground(active: Boolean) = client.foreground(active && !isDemo.value)
    fun pair(address: String, code: String) { if (!isDemo.value) {startCompanion();client.pair(address, code)} }
    fun connect() { if (!isDemo.value) { startCompanion();client.foreground(true); client.connectLatest() } }
    fun disconnect() {client.disconnect();stopCompanion()}
    fun forget() {client.forget();stopCompanion()}
    fun refresh() = client.refresh()
    fun close() { client.close(); scope.cancel() }
    override fun completeDemoTask(reason: CallReason): Task { check(isDemo.value) { "Demo mode required" }; return demo.completeDemoTask(reason) }
    override fun clearHistory() { if (isDemo.value) demo.clearHistory() }
    override fun resetDemo() { if (isDemo.value) demo.resetDemo() }
}
