package dev.droiduse.executor.app

import android.os.Bundle
import android.os.Binder
import android.os.ParcelFileDescriptor
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One target, one ROM-owned display. All entry points are serialized by ExecutorService. */
internal class RomSession(private val api: IDroidUseSystem, private val cacheDir: java.io.File) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<OperationResult>>()
    private val requests = HashSet<String>()
    private val ownerToken = Binder()
    @Volatile var closed = false; private set
    private var latest: Observation? = null
    private var capture: ParcelFileDescriptor? = null
    private lateinit var handle: SessionHandle
    val id: String get() = handle.sessionId
    private val callback = object : IDroidUseCallback.Stub() {
        override fun onOperationResult(result: OperationResult) { pending[result.requestId]?.complete(result) }
        override fun onSessionEvent(event: SessionEvent) {
            if (event.kind == EVENT_CLOSED || event.kind == EVENT_STOPPING) {
                closed = true
                pending.values.forEach { it.completeExceptionally(IllegalStateException("ISOLATION_LOST")) }
            }
        }
        override fun onCapabilitiesChanged(snapshot: CapabilitySnapshot) {
            if (!snapshot.coreReady) closed = true
        }
    }
    fun open(target: String) {
        handle = api.openSession(SessionSpec().apply {
            userId = 0; mode = MODE_ISOLATED_DISPLAY; targetPackage = target
            targetTaskId = -1; requestedDisplayId = -1
            requestedCapabilities = intArrayOf(CAP_DISPLAY_CAPTURE, CAP_INPUT_INJECTION)
            requestedScreenshotFps = 5; timeoutMs = 30 * 60 * 1000L
        }, ownerToken, callback)
    }
    fun observe(): Bundle {
        check(!closed) { "ISOLATION_LOST" }
        closeCapture()
        latest = null
        val spec = ObservationSpec().apply {
            sessionId = handle.sessionId; epoch = handle.epoch; afterFrameId = -1
            includeCapture = true
        }
        val frame = observeWhenReady(spec)
        val source = requireNotNull(frame.capture) { "CAPTURE_UNAVAILABLE" }
        frame.capture = null
        capture = CaptureFile.copy(source, cacheDir)
        latest = frame
        return Bundle().apply {
            putString("code", "OBSERVED"); putString("sessionId", id)
            putString("frameId", frame.frameId.toString()); putInt("displayId", frame.displayId)
            putInt("width", frame.width); putInt("height", frame.height); putInt("rotation", frame.rotation)
            putLong("capturedAt", frame.capturedAtElapsedRealtimeMs); putString("packageName", frame.packageName)
            putBoolean("sensitive", frame.protectedContent); putLong("editorGeneration", frame.editorGeneration)
            // M2 supports insertion into the isolated display's current editor only.
            // Selection/delete/composition remain undeclared until ROM exposes editor ownership.
            putStringArray("editorActions", arrayOf("text"))
            putParcelable("captureFile", capture)
        }
    }
    private fun observeWhenReady(spec: ObservationSpec): Observation {
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        while (true) {
            check(!closed) { "ISOLATION_LOST" }
            try { return api.observe(handle, spec) }
            catch (error: RuntimeException) {
                if (error.javaClass.name != "android.os.ServiceSpecificException" ||
                    error.message !in setOf("SCREENSHOT_NOT_READY", RESOURCE_LIMIT) ||
                    android.os.SystemClock.elapsedRealtime() >= deadline) throw error
                // Only retry read-only capture readiness; actions are never replayed.
                android.os.SystemClock.sleep(200)
            }
        }
    }
    fun execute(requestId: String, action: Bundle): String {
        if (closed) return "ISOLATION_LOST"
        val frame = latest ?: return STALE_OBSERVATION
        if (action.getString("frameId") != frame.frameId.toString() ||
            action.getInt("displayId", -1) != frame.displayId ||
            action.getString("packageName") != frame.packageName ||
            action.getInt("width") != frame.width || action.getInt("height") != frame.height ||
            action.getInt("rotation", -1) != frame.rotation ||
            action.getLong("capturedAt", -1) != frame.capturedAtElapsedRealtimeMs) return STALE_OBSERVATION
        val input = InputOperation()
        fun point(x: String, y: String) = action.containsKey(x) && action.containsKey(y) &&
            action.getInt(x) in 0 until frame.width && action.getInt(y) in 0 until frame.height
        when (action.getString("kind")) {
            "tap" -> {
                if (!point("x", "y")) return "INVALID_REQUEST"
                input.kind = INPUT_TAP; input.x1 = action.getInt("x"); input.y1 = action.getInt("y")
            }
            "swipe" -> {
                if (!point("x1", "y1") || !point("x2", "y2")) return "INVALID_REQUEST"
                input.kind = INPUT_SWIPE; input.x1 = action.getInt("x1"); input.y1 = action.getInt("y1")
                input.x2 = action.getInt("x2"); input.y2 = action.getInt("y2")
            }
            "back" -> { input.kind = INPUT_KEY; input.keyCode = android.view.KeyEvent.KEYCODE_BACK }
            "text" -> {
                val value = action.getString("value") ?: return "INVALID_REQUEST"
                if (value.length !in 1..4000) return "INVALID_REQUEST"
                if (action.getLong("editorGeneration", -1) != frame.editorGeneration) return STALE_OBSERVATION
                input.kind = INPUT_TEXT; input.text = value; input.editorGeneration = frame.editorGeneration
            }
            else -> return UNSUPPORTED
        }
        if (requests.size >= 4096) return RESOURCE_LIMIT
        if (!requests.add(requestId)) return "DUPLICATE_REQUEST"
        val completion = CompletableFuture<OperationResult>()
        pending[requestId] = completion
        // Never reuse the same screenshot to authorize a second action.
        latest = null
        closeCapture()
        try {
            val admission = api.execute(handle, OperationRequest().apply {
                sessionId = handle.sessionId; epoch = handle.epoch; this.requestId = requestId
                expectedFrameId = frame.frameId; expectedWindowGeneration = frame.windowGeneration
                domain = DOMAIN_INPUT; this.input = input
            })
            if (!admission.accepted) return admission.code
            return if (completion.get(5, TimeUnit.SECONDS).code == OK) "EXECUTED" else UNKNOWN_OUTCOME
        } finally { pending.remove(requestId) }
    }
    fun pause(paused: Boolean) {
        api.updateSession(handle, SessionUpdate().apply {
            sessionId = handle.sessionId; epoch = handle.epoch
            kind = if (paused) SESSION_UPDATE_PAUSE else SESSION_UPDATE_RESUME
        })
        latest = null
        closeCapture()
    }
    fun close() {
        try { api.closeSession(handle, 0) }
        catch (error: RuntimeException) {
            if (error.javaClass.name != "android.os.ServiceSpecificException" || error.message != STALE_SESSION) throw error
        }
        closed = true; latest = null; closeCapture()
    }
    private fun closeCapture() { capture?.close(); capture = null }
}
