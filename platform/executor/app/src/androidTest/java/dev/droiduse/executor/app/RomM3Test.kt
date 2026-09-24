package dev.droiduse.executor.app

import android.os.Binder
import android.os.ParcelFileDescriptor
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

/** Opt-in: opens only the fixture and the explicitly selected secondary test application. */
class RomM3Test {
    @Test fun switchRoundTripAndRejectOldTarget() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("M3 device test needs explicit opt-in", args.getString("m3") == "true")
        val other = requireNotNull(args.getString("m3TargetPackage"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = requireNotNull(RomSystemClient(context).service)
        assertTrue("Install M3 ROM first", api.capabilities.capabilities.any {
            it.capabilityId == CAP_APP_TASK_CONTROL && it.reason == "SCOPED_LAUNCH_AND_PICKER"
        })
        val results = LinkedBlockingQueue<OperationResult>()
        val callback = object : IDroidUseCallback.Stub() {
            override fun onOperationResult(result: OperationResult) { results.add(result) }
            override fun onSessionEvent(event: SessionEvent) {}
            override fun onCapabilitiesChanged(snapshot: CapabilitySnapshot) {}
        }
        val root = "dev.droiduse.executor.test"
        val handle = api.openSession(SessionSpec().apply {
            userId = 0; mode = MODE_ISOLATED_DISPLAY; targetPackage = root
            targetTaskId = -1; requestedDisplayId = -1
            requestedCapabilities = intArrayOf(CAP_DISPLAY_CAPTURE, CAP_INPUT_INJECTION)
            requestedScreenshotFps = 5; timeoutMs = 60_000
        }, Binder(), callback)
        fun observe(expected: String): Observation {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (true) {
                SystemClock.sleep(300)
                var frame: Observation? = null
                try {
                    frame = api.observe(handle, ObservationSpec().apply {
                        sessionId = handle.sessionId; epoch = handle.epoch
                        includeCapture = true; afterFrameId = -1
                    })
                } catch (error: RuntimeException) {
                    if (error.message !in setOf("SCREENSHOT_NOT_READY", STALE_OBSERVATION, RESOURCE_LIMIT))
                        throw error
                }
                frame?.capture?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream ->
                    assertTrue(stream.read() >= 0)
                } }
                if (frame != null && frame.packageName == expected) return frame
                check(SystemClock.elapsedRealtime() < deadline) { "Expected window $expected" }
            }
        }
        fun request(frame: Observation, id: String) = OperationRequest().apply {
            sessionId = handle.sessionId; epoch = handle.epoch; requestId = UUID.randomUUID().toString()
            expectedFrameId = frame.frameId; expectedWindowGeneration = frame.windowGeneration
            domain = DOMAIN_APP_TASK
            app = AppOperation().apply { kind = APP_OPEN_TARGET; targetId = id; taskId = -1; displayId = -1 }
        }
        fun rejected(request: OperationRequest) {
            try { api.execute(handle, request); fail("Stale target accepted") }
            catch (error: RuntimeException) {
                assertTrue(error.toString(), error.message in setOf(STALE_OBSERVATION, STALE_TARGET))
            }
        }
        fun launch(frame: Observation, labelPackage: String) {
            val target = frame.targets.single { it.kind == "open_app" && it.label.endsWith("($labelPackage)") }
            val request = request(frame, target.targetId)
            assertTrue(api.execute(handle, request).accepted)
            val result = requireNotNull(results.poll(5, TimeUnit.SECONDS))
            assertEquals(request.requestId, result.requestId)
            assertEquals(OK, result.code)
        }
        try {
            val old = observe(root)
            assertTrue("Requested secondary app is not an admitted target; declared: " +
                old.targets.joinToString { it.label }, old.targets.any { it.label.endsWith("($other)") })
            val oldTarget = old.targets.first { it.label.endsWith("($other)") }.targetId
            val fresh = observe(root)
            rejected(request(fresh, oldTarget))
            // A rejected operation also consumes its observation; obtain a new frame.
            val first = observe(root)
            val firstTask = first.taskId
            launch(first, other)
            val second = observe(other)
            assertEquals(handle.displayId, second.displayId)
            assertTrue(second.windowGeneration > first.windowGeneration)
            rejected(request(first, oldTarget))
            launch(second, root)
            val returned = observe(root)
            assertEquals(handle.displayId, returned.displayId)
            assertEquals("Switch must resume the existing isolated task", firstTask, returned.taskId)
        } finally { api.closeSession(handle, 0) }
    }
}
