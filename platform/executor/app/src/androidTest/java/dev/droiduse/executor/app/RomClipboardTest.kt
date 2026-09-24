package dev.droiduse.executor.app

import android.net.Uri
import android.os.Binder
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Synthetic text only. Opt-in against a ROM with patch 0011; never reads personal clipboard. */
class RomClipboardTest {
    @Test fun copyCutPasteRejectStaleEditorAndClearOnClose() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3Clipboard") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = requireNotNull(RomSystemClient(context).service)
        assertTrue(api.capabilities.capabilities.any { it.reason == "SCOPED_TEXT_CLIPBOARD" })
        val results = LinkedBlockingQueue<OperationResult>()
        val callback = object : IDroidUseCallback.Stub() {
            override fun onOperationResult(result: OperationResult) { results.add(result) }
            override fun onSessionEvent(event: SessionEvent) {}
            override fun onCapabilitiesChanged(snapshot: CapabilitySnapshot) {}
        }
        fun open() = api.openSession(SessionSpec().apply {
            userId = 0; mode = MODE_ISOLATED_DISPLAY; targetPackage = "dev.droiduse.executor.test"
            targetTaskId = -1; requestedDisplayId = -1
            requestedCapabilities = intArrayOf(CAP_DISPLAY_CAPTURE, CAP_INPUT_INJECTION)
            requestedScreenshotFps = 5; timeoutMs = 60_000
        }, Binder(), callback)
        fun observe(h: SessionHandle): Observation {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (true) {
                SystemClock.sleep(350)
                try {
                    return api.observe(h, ObservationSpec().apply {
                        sessionId = h.sessionId; epoch = h.epoch; includeCapture = true; afterFrameId = -1
                    }).also { it.capture?.close(); it.capture = null }
                } catch (error: RuntimeException) {
                    if (error.message !in setOf("SCREENSHOT_NOT_READY", RESOURCE_LIMIT, STALE_OBSERVATION)
                        || SystemClock.elapsedRealtime() >= deadline) throw error
                }
            }
        }
        fun request(h: SessionHandle, f: Observation, input: InputOperation) = OperationRequest().apply {
            sessionId = h.sessionId; epoch = h.epoch; requestId = UUID.randomUUID().toString()
            expectedFrameId = f.frameId; expectedWindowGeneration = f.windowGeneration
            domain = DOMAIN_INPUT; this.input = input
        }
        fun run(h: SessionHandle, kind: Int, text: String? = null): Observation {
            val f = observe(h)
            val r = request(h, f, InputOperation().apply {
                this.kind = kind; this.text = text; editorGeneration = f.editorGeneration
                x1 = f.width / 2; y1 = 290
            })
            assertTrue(api.execute(h, r).accepted)
            val done = requireNotNull(results.poll(5, TimeUnit.SECONDS))
            assertEquals(r.requestId, done.requestId); assertEquals(OK, done.code)
            return f
        }
        fun text(): String = context.contentResolver.query(
            Uri.parse("content://dev.droiduse.m2.fixture/state"), null, null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(it.getColumnIndexOrThrow("text"))
        }
        val h = open()
        try {
            run(h, INPUT_TAP)
            assertFalse("Empty session must not offer paste", "edit_paste" in observe(h).editorActions.orEmpty())
            run(h, INPUT_TEXT, "剪贴板 test 123")
            run(h, INPUT_SELECT_ALL)
            run(h, INPUT_COPY)
            assertEquals("剪贴板 test 123", text())
            run(h, INPUT_CUT)
            assertEquals("", text())
            run(h, INPUT_PASTE)
            assertEquals("剪贴板 test 123", text())
            val previous = observe(h)
            val fresh = observe(h)
            val stale = request(h, fresh, InputOperation().apply {
                kind = INPUT_PASTE; editorGeneration = previous.editorGeneration
            })
            try { api.execute(h, stale); fail("Stale editor accepted") }
            catch (error: RuntimeException) { assertEquals(STALE_OBSERVATION, error.message) }
        } finally { api.closeSession(h, 0) }
        val next = open()
        try {
            run(next, INPUT_TAP)
            assertFalse("Clipboard leaked into a new session", "edit_paste" in observe(next).editorActions.orEmpty())
        } finally { api.closeSession(next, 0) }
    }
}
