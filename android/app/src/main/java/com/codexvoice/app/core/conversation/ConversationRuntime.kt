package com.codexvoice.app.core.conversation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Call-only memory survives Activity recreation; no conversation text is persisted. */
object ConversationRuntime {
    private val mutable=MutableStateFlow(ConversationState());val state=mutable.asStateFlow()
    fun update(block:(ConversationState)->ConversationState){mutable.update(block)}
    fun clear(){mutable.value=ConversationState()}
    fun receive(next:ConversationState){mutable.update{old->
        if(old.callId!=next.callId)next else next.copy(entries=(old.entries+next.entries).distinctBy{it.id}.sortedBy{it.sequence}.takeLast(8),lastSpokenSequence=old.lastSpokenSequence)
    }}
}
