package com.codexvoice.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.call.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import com.codexvoice.app.core.remote.ClientState
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.speech.*

data class DashboardState(
    val current: Task? = null, val history: List<Task> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.DEMO,
    val remote: ClientState? = null, val demoMode: Boolean = true,
)
class DashboardViewModel(
    private val tasks: TaskRepository,
    private val settings: PreferencesRepository,
    private val calls: CallStateMachine = CallStateMachine(),
    private val notifications: NotificationController? = null,
    private val liveCalls: LiveCallController? = null,
    private val speech: SpeechSessionController? = null,
    private val controls:com.codexvoice.app.core.control.TaskControlController?=null,
    private val ownsRuntime: Boolean = true,
    private val chat:com.codexvoice.app.core.conversation.ChatController?=null,
) : ViewModel() {
    private val basicDashboard = combine(tasks.current, tasks.history, tasks.connection) { task, history, connection ->
        DashboardState(task, sortedHistory(history), connection)
    }
    val dashboard = (if (tasks is CompanionTaskRepository) tasks.data.map { data ->
        DashboardState(data.current, sortedHistory(data.history), data.connection, data.remote, data.demo)
    } else basicDashboard).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState(demoMode = tasks !is CompanionTaskRepository))
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    private val mutableCall = MutableStateFlow(calls.snapshot)
    val call = mutableCall.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    val notificationState = notifications?.state ?: MutableStateFlow(NotificationUiState()).asStateFlow()
    val liveCallState = liveCalls?.state ?: MutableStateFlow(LiveCallState()).asStateFlow()
    val callSettingsStatus = liveCalls?.status ?: MutableStateFlow("Call invitations disabled").asStateFlow()
    val speechState = SpeechRuntime.state
    val conversationState=com.codexvoice.app.core.conversation.ConversationRuntime.state
    val controlState=controls?.state?:MutableStateFlow(com.codexvoice.app.core.control.ControlState()).asStateFlow()
    val chatState=chat?.state?:MutableStateFlow(com.codexvoice.app.core.conversation.ChatState()).asStateFlow()
    val pendingIncomingAnswer=MutableStateFlow<String?>(null)
    fun incomingAnswer(event:String){pendingIncomingAnswer.value=event;openNotification(event)}
    fun consumeIncomingAnswer(){pendingIncomingAnswer.value=null}
    fun openChat(id:String){chat?.open(id)}
    fun selectChat(id:String){chat?.select(id)}
    fun hideChat(){chat?.hide()}
    fun sendChat(text:String){chat?.send(text)}
    fun callCodex(id:String,permitted:Boolean){liveCalls?.outgoing(id,permitted)}
    fun openControls(id:String){controls?.open(id)}
    fun hideControls(){controls?.hide()}
    fun interruptTask(){controls?.interrupt()}
    fun queueTask(text:String){controls?.queue(text)}
    fun confirmControl(id:String){controls?.confirm(id)}
    fun cancelControl(id:String){controls?.cancel(id)}
    fun denyApproval(a:com.codexvoice.app.core.control.ApprovalReview){controls?.deny(a)}
    fun registerReviewer(){controls?.register()}
    fun approveOnce(a:com.codexvoice.app.core.control.ApprovalReview){controls?.approve(a)}
    fun authorizedApproval(c:com.codexvoice.app.core.control.ApprovalChallenge,s:String?){controls?.authorized(c,s)}
    fun confirmVoice(id:String){speech?.confirmProposal(id)}
    fun cancelVoice(id:String){speech?.cancelProposal(id)}
    fun correctVoice(id:String,text:String){speech?.correctProposal(id,text)}
    fun selectVoiceThread(id:String){speech?.selectThread(id)}
    fun repeatSummary() { speech?.repeat() }
    fun stopSpeaking() { speech?.stop() }
    fun finishSpeaking() { speech?.finishSpeaking() }
    fun setSpeech(value: UserPreferences) = save { settings.setSpeech(value) }
    fun setCallInvitations(value: Boolean) = save { settings.setCallInvitations(value) }
    fun openLiveCall() { liveCalls?.openCurrent() }
    fun answerLiveCall(permitted: Boolean) { liveCalls?.answer(permitted) }
    fun declineLiveCall() { liveCalls?.decline() }
    fun endLiveCall() { liveCalls?.end() }
    fun muteLiveCall() { liveCalls?.mute() }
    fun speakerLiveCall() { liveCalls?.speaker() }
    fun testLiveAudio() { liveCalls?.tone() }
    fun dismissLiveCall() { liveCalls?.dismiss(); notifications?.dismiss() }
    fun liveCallResults() { liveCalls?.results(); notifications?.viewResults() }
    fun setNotifications(value: UserPreferences) = save { settings.setNotifications(value) }
    fun openNotification(id: String) { notifications?.open(id); liveCalls?.open(id) }
    fun viewNotificationResults() { notifications?.viewResults() }
    fun dismissNotification() { notifications?.dismiss() }
    fun notificationSystemChanged() { notifications?.systemStateChanged() }
    fun previewCall(reason: CallReason) {
        if (tasks is CompanionTaskRepository && !tasks.isDemo.value) return
        if (calls.snapshot.state != CallState.IDLE) return
        val task = tasks.completeDemoTask(reason)
        calls.incoming(task, reason)
        mutableCall.value = calls.snapshot
    }
    fun answer() { calls.answer(); mutableCall.value = calls.snapshot }
    fun decline() { calls.decline(); calls.dismiss(); mutableCall.value = calls.snapshot }
    fun hangUp() { calls.hangUp(); calls.dismiss(); mutableCall.value = calls.snapshot }
    fun toggleMute() { calls.toggleMute(); mutableCall.value = calls.snapshot }
    fun clearHistory() = tasks.clearHistory()
    fun resetDemo() = tasks.resetDemo()
    fun selectDemo(value: Boolean) = save { (tasks as? CompanionTaskRepository)?.selectDemo(value) }
    fun foreground(active: Boolean) { (tasks as? CompanionTaskRepository)?.foreground(active); liveCalls?.foreground(active) }
    fun pair(address: String, code: String) { (tasks as? CompanionTaskRepository)?.pair(address, code) }
    fun connectPc() { (tasks as? CompanionTaskRepository)?.connect() }
    fun disconnectPc() { (tasks as? CompanionTaskRepository)?.disconnect() }
    fun forgetPc() { (tasks as? CompanionTaskRepository)?.forget() }
    fun refreshPc() { (tasks as? CompanionTaskRepository)?.refresh() }
    override fun onCleared() { if (ownsRuntime) { controls?.close();speech?.close(); (tasks as? CompanionTaskRepository)?.close() }; super.onCleared() }
    fun setTheme(mode: ThemeMode) = save { settings.setTheme(mode) }
    fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) =
        save { settings.setCalling(completion, failure, approval) }
    fun setReduceMotion(value: Boolean) = save { settings.setReduceMotion(value) }
    fun dismissError() { mutableError.value = null }
    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (_: IOException) { mutableError.value = "Could not save preferences. Please try again." }
        }
    }
}

