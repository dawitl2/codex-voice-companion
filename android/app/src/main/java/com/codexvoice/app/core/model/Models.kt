package com.codexvoice.app.core.model

enum class TaskStatus { RUNNING, COMPLETED, FAILED, INTERRUPTED, AWAITING_APPROVAL, UNKNOWN }
enum class ConnectionStatus { DEMO, DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class CallReason { COMPLETION, FAILURE }
data class Task(
    val id: String, val title: String, val project: String, val status: TaskStatus,
    val updatedAt: Long, val durationSeconds: Long?, val summary: String, val simulated: Boolean = true,
)
data class TaskEvent(val id: String, val task: Task)
data class UserPreferences(
    val theme: ThemeMode = ThemeMode.DARK,
    val callOnCompletion: Boolean = true,
    val callOnFailure: Boolean = true,
    val callOnApproval: Boolean = true,
    val reduceMotion: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val alertOnInterruption: Boolean = false,
    val notificationSound: Boolean = true,
    val notificationVibration: Boolean = true,
)
fun TaskStatus.label() = when (this) {
    TaskStatus.RUNNING -> "Running"
    TaskStatus.COMPLETED -> "Completed"
    TaskStatus.FAILED -> "Failed"
    TaskStatus.INTERRUPTED -> "Interrupted"
    TaskStatus.AWAITING_APPROVAL -> "Needs approval"
    TaskStatus.UNKNOWN -> "Needs reconciliation"
}
fun sortedHistory(tasks: List<Task>, status: TaskStatus? = null): List<Task> =
    tasks.filter { status == null || it.status == status }
        .sortedWith(compareByDescending<Task> { it.updatedAt }.thenBy { it.id })

