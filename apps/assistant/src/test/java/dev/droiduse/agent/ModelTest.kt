package dev.droiduse.agent
import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    private val p = ModelProfile(name="test", baseUrl="https://example.com/v1", model="model", apiKey="synthetic-secret")
    @Test fun rejectsUnsafeEndpointsAndHeaders() {
        listOf("http://example.com", "https://key@example.com", "https://example.com?key=secret", "https://example.com/#secret", "https://example.com:0").forEach {
            assertTrue(runCatching { p.copy(baseUrl=it).validate() }.isFailure)
        }
        assertTrue(runCatching { p.copy(apiKey="x\nAuthorization:y").validate() }.isFailure)
    }
    @Test fun formsEndpointsWithoutDoubleSuffix() {
        assertEquals("https://example.com/v1/chat/completions", p.endpoint())
        assertEquals(p.endpoint(), p.copy(baseUrl=p.endpoint()+"/").endpoint())
        assertEquals("https://example.com/v1/messages", p.copy(protocol=Protocol.ANTHROPIC).endpoint())
    }
    @Test fun credentialsNeverInPayloadOrDiagnosticString() {
        assertFalse(p.toString().contains(p.apiKey))
        val body = ModelClient().payload(p,"任务",16)
        assertFalse(body.toString().contains(p.apiKey)); assertEquals("任务", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }
    @Test fun decodesBothProtocolsAndRejectsEmptyOutput() {
        val client = ModelClient()
        assertEquals("中文",client.decode(p,"""{"choices":[{"message":{"content":"中文"}}]}"""))
        assertEquals("一\n二",client.decode(p.copy(protocol=Protocol.ANTHROPIC),"""{"content":[{"type":"text","text":"一"},{"type":"tool_use"},{"type":"text","text":"二"}]}"""))
        assertTrue(runCatching { client.decode(p,"""{"choices":[{"message":{"content":""}}]}""") }.isFailure)
        assertTrue(runCatching { client.decode(p,"not json") }.isFailure)
    }
    @Test fun cancellationBeforeRequestNeverSends() {
        val c = ModelClient(); c.cancel()
        assertTrue(runCatching { c.probe(p) }.isFailure)
    }
}
