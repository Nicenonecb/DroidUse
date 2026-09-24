package dev.droiduse.executor.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Binder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Verifies the real system picker, isolated input and returned URI grant using synthetic text. */
class RomPickerTest {
    @Test fun selectDocumentAndReturnReadableUriToSameTask() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3Picker") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val api = requireNotNull(RomSystemClient(context).service)
        val results = LinkedBlockingQueue<OperationResult>()
        val callback = object : IDroidUseCallback.Stub() {
            override fun onOperationResult(result: OperationResult) { results.add(result) }
            override fun onSessionEvent(event: SessionEvent) {}
            override fun onCapabilitiesChanged(snapshot: CapabilitySnapshot) {}
        }
        val h = api.openSession(SessionSpec().apply {
            userId = 0; mode = MODE_ISOLATED_DISPLAY; targetPackage = "dev.droiduse.executor.test"
            targetTaskId = -1; requestedDisplayId = -1
            requestedCapabilities = intArrayOf(CAP_DISPLAY_CAPTURE, CAP_INPUT_INJECTION)
            requestedScreenshotFps = 5; timeoutMs = 60_000
        }, Binder(), callback)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun observe(expected: String): Observation {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (true) {
                SystemClock.sleep(350)
                var frame: Observation? = null
                try {
                    frame = api.observe(h, ObservationSpec().apply {
                        sessionId = h.sessionId; epoch = h.epoch; includeCapture = true; afterFrameId = -1
                    })
                    frame.capture?.close(); frame.capture = null
                } catch (error: RuntimeException) {
                    if (error.message !in setOf("SCREENSHOT_NOT_READY", RESOURCE_LIMIT, STALE_OBSERVATION))
                        throw error
                }
                if (frame != null && frame.packageName == expected) return frame
                check(SystemClock.elapsedRealtime() < deadline) { "Expected picker window $expected" }
            }
        }
        fun tap(frame: Observation, x: Int, y: Int) {
            val request = OperationRequest().apply {
                sessionId = h.sessionId; epoch = h.epoch; requestId = UUID.randomUUID().toString()
                expectedFrameId = frame.frameId; expectedWindowGeneration = frame.windowGeneration
                domain = DOMAIN_INPUT; input = InputOperation().apply { kind = INPUT_TAP; x1 = x; y1 = y }
            }
            assertTrue(api.execute(h, request).accepted)
            val done = requireNotNull(results.poll(5, TimeUnit.SECONDS))
            assertEquals(request.requestId, done.requestId); assertEquals(OK, done.code)
        }
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.text?.toString() == "m3-validation-proof.txt")
                return Rect().also { node.getBoundsInScreen(it) }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        try {
            val initial = observe("dev.droiduse.executor.test")
            tap(initial, initial.width / 2, 470)
            val picker = observe("com.android.documentsui")
            assertEquals(h.displayId, picker.displayId)
            assertTrue("select_file_at" in picker.scopedActions.orEmpty())
            assertTrue("Picker must not declare arbitrary app launches", picker.targets.isNullOrEmpty())
            val windows = automation.windowsOnAllDisplays[h.displayId].orEmpty()
            val bounds = windows.firstNotNullOfOrNull { find(it.root) }
            assertNotNull("Synthetic file not exposed in isolated picker", bounds)
            tap(picker, bounds!!.centerX(), bounds.centerY())
            val returned = observe("dev.droiduse.executor.test")
            assertEquals(initial.taskId, returned.taskId)
            assertFalse("select_file_at" in returned.scopedActions.orEmpty())
            context.contentResolver.query(Uri.parse("content://dev.droiduse.m2.fixture/state"),
                null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(it.getColumnIndexOrThrow("fileUri")).startsWith("content://"))
                assertEquals("M3 isolated document proof\n", it.getString(it.getColumnIndexOrThrow("fileText")))
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            api.closeSession(h, 0)
        }
    }
}
