package com.codexvoice.app.feature.demo

import com.codexvoice.app.core.data.TaskRepository
import com.codexvoice.app.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DemoTaskRepository(private val now: () -> Long = System::currentTimeMillis) : TaskRepository {
    private var sequence = 0
    private val baseTime = 1791720000000L
    private fun sampleCurrent() = Task("demo-current", "Android UI implementation", "Codex Voice",
        TaskStatus.RUNNING, baseTime, null, "Sample task. No PC connection is active.")
    private fun samples() = listOf(
        Task("demo-1", "Refine task cards", "Codex Voice", TaskStatus.COMPLETED, baseTime - 600000, 142,
            "Sample result: improved spacing and status labels. No real Codex output."),
        Task("demo-2", "Run bridge checks", "Codex Voice", TaskStatus.FAILED, baseTime - 1800000, 84,
            "Sample result: a fixture test failed. Review the task before continuing."),
        Task("demo-3", "Explore connection options", "Codex Voice", TaskStatus.INTERRUPTED, baseTime - 3600000, 63,
            "Sample result: this task was stopped before completion."),
    )
    private val mutableCurrent = MutableStateFlow<Task?>(sampleCurrent())
    private val mutableHistory = MutableStateFlow(samples())
    private val mutableConnection = MutableStateFlow(ConnectionStatus.DEMO)
    override val current = mutableCurrent.asStateFlow()
    override val history = mutableHistory.asStateFlow()
    override val connection = mutableConnection.asStateFlow()
    override fun completeDemoTask(reason: CallReason): Task {
        val failed = reason == CallReason.FAILURE
        val task = Task("demo-event-${++sequence}", if (failed) "Review failing tests" else "Android UI implementation",
            "Codex Voice", if (failed) TaskStatus.FAILED else TaskStatus.COMPLETED, now(), 128,
            if (failed) "Sample summary: one test failed. Review the failure before continuing. This is simulated data."
            else "Sample summary: the interface is ready for review. Dashboard, history and call screens are available. This is simulated data.")
        mutableCurrent.value = task
        mutableHistory.value = sortedHistory(listOf(task) + mutableHistory.value).take(50)
        return task
    }
    override fun clearHistory() { mutableHistory.value = emptyList() }
    override fun resetDemo() {
        mutableCurrent.value = sampleCurrent()
        mutableHistory.value = samples()
    }
}

