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
    fun open(target: String, globalSettings: Boolean = false) {
        handle = api.openSession(SessionSpec().apply {
            allowGlobalSettings = globalSettings
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
            putString("systemContext", frame.systemContext ?: "")
            putString("code", "OBSERVED"); putString("sessionId", id)
            putString("frameId", frame.frameId.toString()); putInt("displayId", frame.displayId)
            putInt("width", frame.width); putInt("height", frame.height); putInt("rotation", frame.rotation)
            putLong("capturedAt", frame.capturedAtElapsedRealtimeMs); putString("packageName", frame.packageName)
            putBoolean("sensitive", frame.protectedContent); putLong("editorGeneration", frame.editorGeneration)
            // A null tail field means an older M2 ROM; an empty array means no safe editor.
            putStringArray("editorActions", frame.editorActions ?: arrayOf("text"))
            putStringArray("scopedActions", frame.scopedActions ?: emptyArray())
            putParcelableArrayList("targets", ArrayList(frame.targets.orEmpty().map { target ->
                Bundle().apply {
                    putString("targetId", target.targetId); putString("kind", target.kind)
                    putString("label", target.label)
                }
            }))
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
        var app: AppOperation? = null
        var device: DeviceOperation? = null
        fun point(x: String, y: String) = action.containsKey(x) && action.containsKey(y) &&
            action.getInt(x) in 0 until frame.width && action.getInt(y) in 0 until frame.height
        when (action.getString("kind")) {
            "tap", "select_file_at" -> {
                if (action.getString("kind") == "select_file_at" &&
                    "select_file_at" !in frame.scopedActions.orEmpty()) return UNSUPPORTED
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
                if (frame.editorActions != null && "text" !in frame.editorActions) return UNSUPPORTED
                val value = action.getString("value") ?: return "INVALID_REQUEST"
                if (value.length !in 1..4000) return "INVALID_REQUEST"
                if (action.getLong("editorGeneration", -1) != frame.editorGeneration) return STALE_OBSERVATION
                input.kind = INPUT_TEXT; input.text = value; input.editorGeneration = frame.editorGeneration
            }
            "edit_select", "edit_select_all", "edit_copy", "edit_cut", "edit_paste" -> {
                val kind = action.getString("kind")!!
                if (kind !in frame.editorActions.orEmpty()) return UNSUPPORTED
                if (action.getLong("editorGeneration", -1) != frame.editorGeneration) return STALE_OBSERVATION
                input.editorGeneration = frame.editorGeneration
                input.kind = when (kind) {
                    "edit_select" -> INPUT_SELECTION
                    "edit_select_all" -> INPUT_SELECT_ALL
                    "edit_copy" -> INPUT_COPY
                    "edit_cut" -> INPUT_CUT
                    else -> INPUT_PASTE
                }
                if (kind == "edit_select") {
                    input.selectionStart = action.getInt("start", -1)
                    input.selectionEnd = action.getInt("end", -1)
                    if (input.selectionStart < 0 || input.selectionEnd < input.selectionStart) return "INVALID_REQUEST"
                }
            }
            "open_app" -> {
                val targetId = action.getString("targetId") ?: return "INVALID_REQUEST"
                if (frame.targets.orEmpty().none { it.kind == "open_app" && it.targetId == targetId })
                    return STALE_OBSERVATION
                app = AppOperation().apply {
                    kind = APP_OPEN_TARGET; this.targetId = targetId; taskId = -1; displayId = -1
                }
            }
            else -> {
                val kind = SystemActionPolicy.kind(action.getString("kind"))
                if (kind < 0) return UNSUPPORTED
                val targetId = action.getString("targetId") ?: return "INVALID_REQUEST"
                if (frame.targets.orEmpty().none { it.kind == action.getString("kind") && it.targetId == targetId })
                    return STALE_OBSERVATION
                device = DeviceOperation().apply { this.kind = kind; this.targetId = targetId; stringValue = action.getString("value") }
                if (!SystemActionPolicy.validRequest(SystemActionPolicy.domain(kind), device)) return "INVALID_REQUEST"
            }
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
                if (device != null) { domain = SystemActionPolicy.domain(device.kind); this.device = device }
                else if (app == null) { domain = DOMAIN_INPUT; this.input = input }
                else { domain = DOMAIN_APP_TASK; this.app = app }
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
