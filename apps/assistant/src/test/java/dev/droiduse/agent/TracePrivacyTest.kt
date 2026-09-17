package dev.droiduse.agent

import org.junit.Assert.*
import org.junit.Test

class TracePrivacyTest {
    @Test fun freeformModelContentAndInputAreNeverWritten() {
        val raw="""{"frameId":"frame-1","reply":{"kind":"text","value":"123456","note":"api-key-secret","claim":"password=abc","evidence":"secret"}}"""
        val safe=TracePrivacy.modelRecord(raw)
        listOf("123456","api-key-secret","password=abc","secret").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("inputRedacted"));assertTrue(safe.contains("frame-1"))
    }
    @Test fun sensitivePagesAreRecognizedAndOrdinaryReadingIsAllowed() {
        for(text in listOf("请输入验证码","输入密码","API Key: example","PASSWORD")) assertTrue(TracePrivacy.sensitiveScene(text))
        assertFalse(TracePrivacy.sensitiveScene("第1章 都市脑洞 1/100"))
        assertEquals("key=[REDACTED]",TracePrivacy.redactKnownSecrets("key=abc",listOf("abc")))
    }
}
