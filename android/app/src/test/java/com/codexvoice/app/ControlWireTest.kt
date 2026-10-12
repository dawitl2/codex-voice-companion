package com.codexvoice.app
import com.codexvoice.app.core.remote.RemoteProtocol
import com.codexvoice.app.core.control.ControlWire
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
class ControlWireTest {
    private val status="""{"threadId":"managed","project":"workspace","mode":"mock","task":{"id":"turn","status":"running","result":""},"progress":"Working","operations":[],"approvals":[],"reviewer":null}"""
    private fun envelope(payload:String,type:String="control.status.response")="""{"version":2,"type":"$type","id":"${UUID.randomUUID()}","correlationId":"request","timestamp":"2026-10-12T00:00:00Z","payload":$payload}"""
    @Test fun strictControlStatusPreservesAuthoritativeState(){val m=RemoteProtocol.parse(envelope(status));val s=ControlWire.status(m.payload);assertEquals("running",s.task?.status);assertEquals("mock",s.mode);assertTrue(s.connected);assertFalse(s.trusted)}
    @Test fun extraFieldsUnknownStatusesAndUnboundedResultsFail(){for(p in listOf(status.replace("\"mode\":\"mock\"","\"mode\":\"mock\",\"command\":\"execute\""),status.replace("running","approved"),status.replace("\"result\":\"\"","\"result\":\"${"x".repeat(16001)}\""))){assertThrows(Exception::class.java){RemoteProtocol.parse(envelope(p))}}}
    @Test fun approvalChallengeCarriesExactSignedMessageAndExpiry(){val id=UUID.randomUUID().toString();val p="""{"id":"$id","transactionId":"${UUID.randomUUID()}","message":"exact bound challenge","expiresAt":"2026-10-12T00:00:30Z"}""";val c=ControlWire.challenge(RemoteProtocol.parse(envelope(p,"control.approval.challenge.response")).payload);assertEquals(id,c.id);assertEquals("exact bound challenge",c.message);assertTrue(c.expiresAt>0)}
    @Test fun explicitNullFieldsRemainInNegativeDecision(){val raw=RemoteProtocol.message("control.approval.decide",mapOf("challengeId" to com.google.gson.JsonNull.INSTANCE,"signature" to com.google.gson.JsonNull.INSTANCE));val p=JsonParser.parseString(raw).asJsonObject["payload"].asJsonObject;assertTrue(p.has("challengeId"));assertTrue(p["challengeId"].isJsonNull);assertTrue(p.has("signature"))}
}
