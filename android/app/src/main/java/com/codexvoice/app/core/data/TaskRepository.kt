package com.codexvoice.app.core.data

import com.codexvoice.app.core.model.*
import kotlinx.coroutines.flow.StateFlow

interface TaskRepository {
    val current: StateFlow<Task?>
    val history: StateFlow<List<Task>>
    val connection: StateFlow<ConnectionStatus>
    fun completeDemoTask(reason: CallReason): Task
    fun clearHistory()
    fun resetDemo()
}

