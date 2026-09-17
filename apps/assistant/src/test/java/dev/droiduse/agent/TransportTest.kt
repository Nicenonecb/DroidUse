package dev.droiduse.agent
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.io.*

class TransportTest {
    private val profile = ModelProfile(name="test",baseUrl="https://example.com/v1",model="fake",apiKey="synthetic-only")
    private class Connection(val status: Int = 200, val body: String = """{"choices":[{"message":{"content":"OK"}}]}""") : HttpURLConnection(URL("https://example.com")) {
        val sent = ByteArrayOutputStream(); var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getResponseCode() = status
    }
    @Test fun headersAndBodyAreProtocolSpecific() {
        for (protocol in Protocol.entries) {
            val c = Connection(body=if(protocol == Protocol.ANTHROPIC) """{"content":[{"type":"text","text":"OK"}]}""" else """{"choices":[{"message":{"content":"OK"}}]}""")
            val client=ModelClient { c }
            assertEquals("OK",client.probe(profile.copy(protocol=protocol)))
            assertFalse(c.instanceFollowRedirects); assertEquals("POST",c.requestMethod)
            if(protocol==Protocol.ANTHROPIC) { assertEquals(profile.apiKey,c.getRequestProperty("x-api-key")); assertNull(c.getRequestProperty("Authorization")) }
            else { assertEquals("Bearer ${profile.apiKey}",c.getRequestProperty("Authorization")); assertNull(c.getRequestProperty("x-api-key")) }
            assertFalse(c.sent.toString().contains(profile.apiKey)); assertTrue(c.closed)
        }
    }
    @Test fun redirectAndHttpErrorsAreNotRetried() {
        for (status in listOf(301,307,401,429,500)) {
            var calls=0; val connection=Connection(status)
            assertTrue(runCatching { ModelClient { calls++; connection }.probe(profile) }.isFailure)
            assertEquals(1,calls); assertTrue(connection.closed)
        }
    }
    @Test fun oversizedResponseIsRejectedAndClosed() {
        val c=Connection(body="x".repeat(1_048_577))
        assertTrue(runCatching { ModelClient { c }.probe(profile) }.isFailure); assertTrue(c.closed)
    }
    @Test fun cancellationDuringOpenIsCheckedBeforeSending() {
        val c=Connection(); lateinit var client: ModelClient
        client=ModelClient { client.cancel(); c }
        assertTrue(runCatching { client.probe(profile) }.isFailure)
        assertEquals(0,c.sent.size()); assertTrue(c.closed)
    }
}
