package com.codexvoice.app
import com.codexvoice.app.core.conversation.ChatWire
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class ChatWireTest{
    private fun status()=JsonParser.parseString("""{"threadId":"managed","label":"Work","project":"Acceptance","mode":"real","policy":"workspace-write","selected":true,"turns":[{"id":"turn","status":"completed","instruction":"Create voice-check.txt","response":"Created","startedAt":"2026-10-13T00:00:00Z","finishedAt":"2026-10-13T00:00:01Z"}],"progress":"Completed"}""").asJsonObject
    @Test fun actualAcceptedInstructionAndResultHaveDistinctOrigins(){val state=ChatWire.parse(status());assertEquals("Create voice-check.txt",state.turns.single().instruction);assertEquals("Created",state.turns.single().response);assertTrue(state.selected);assertEquals("real",state.mode)}
    @Test fun unrecognizedPolicyAndExtraOperationFailClosed(){val row=status();row.addProperty("policy","danger-full-access");assertThrows(IllegalArgumentException::class.java){ChatWire.parse(row)};val extra=status();extra.addProperty("shell","echo");assertThrows(IllegalArgumentException::class.java){ChatWire.parse(extra)}}
}
