package com.codexvoice.app

import com.codexvoice.app.core.conversation.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ConversationStateTest {
    private fun payload():com.google.gson.JsonObject {
        val id=UUID.randomUUID().toString()
        return JsonParser.parseString("""{"callId":"$id","phase":"awaiting_confirmation","error":"","selection":{"threadId":"thread","label":"Controlled task","project":"Example","mode":"mock"},"proposal":{"id":"$id","text":"Review the screen","expiresAt":"2026-10-12T00:00:45.000Z","threadId":"thread","project":"Example"},"submission":null,"entries":[{"id":"$id","sequence":1,"kind":"system","text":"Review the screen","spoken":"Should I send it?","timestamp":"2026-10-12T00:00:00.000Z"}]}""").asJsonObject
    }
    @Test fun proposalBindingAndCorrectionStateAreParsedWithoutInventedAcceptance(){
        val state=ConversationWire.parse(payload(),123);assertEquals("Review the screen",state.proposal!!.text);assertNull(state.submission);assertEquals("Review instruction",conversationLabel(state));assertEquals(123L,state.entries.single().receivedAt)
    }
    @Test fun strictPayloadRejectsExtraRemoteOperations(){
        val p=payload();p.addProperty("shell","arbitrary")
        try{ConversationWire.validate("conversation.status.response",p);fail("Unknown field accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun recreationMergeIsIdempotentAndClearDiscardsAllConversationText(){
        val state=ConversationWire.parse(payload());ConversationRuntime.receive(state);ConversationRuntime.update{it.copy(lastSpokenSequence=1)};ConversationRuntime.receive(state)
        assertEquals(1,ConversationRuntime.state.value.entries.size);assertEquals(1L,ConversationRuntime.state.value.lastSpokenSequence)
        ConversationRuntime.clear();assertNull(ConversationRuntime.state.value.proposal);assertTrue(ConversationRuntime.state.value.entries.isEmpty())
    }
    @Test fun actualCodexResponseSupportsBoundedMultilineText(){
        val p=payload();p["entries"].asJsonArray.first().asJsonObject.addProperty("text","Actual response\nwith a second line")
        assertTrue(ConversationWire.parse(p).entries.single().text.contains('\n'))
    }
}
