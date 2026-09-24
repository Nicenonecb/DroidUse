package dev.droiduse.executor.app

import android.os.*
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*

/** Authenticated Assistant calls enter via ExecutorService; the ROM owns call identity and audio. */
internal class ExecutorCalls(private val client: RomSystemClient, private val lock: Any) {
    private data class Active(val uid: Int, val clientToken: IBinder, val death: IBinder.DeathRecipient,
        val romOwner: IBinder, val handle: SessionHandle, var closed: Boolean = false,
        var stopping: Boolean = false, var paused: Boolean = false,
        val descriptors: MutableMap<Int, ParcelFileDescriptor> = mutableMapOf())
    private var active: Active? = null
    private val handler = Handler(Looper.getMainLooper())
    fun busy() = active != null
    fun matches(id: String) = active?.handle?.sessionId == id
    private fun api() = requireNotNull(client.service) { "ROM_UNAVAILABLE" }
    private fun owned(id: String, uid: Int): Active = requireNotNull(active).also {
        if (it.uid != uid || it.handle.sessionId != id) throw SecurityException("Call session owner mismatch")
    }
    fun open(uid: Int, token: IBinder, options: Bundle): Bundle {
        check(active == null) { "BUSY" }
        require(options.keySet().all { it in setOf("callAddress", "allowCallAudio", "allowInjection") })
        val capture = options.getBoolean("allowCallAudio")
        val injection = options.getBoolean("allowInjection")
        val spec = SessionSpec().apply {
            mode = MODE_CALL_ASSIST; userId = 0; targetTaskId = -1; requestedDisplayId = -1
            timeoutMs = 30 * 60 * 1000L; allowCallAudio = capture || injection
            callAddress = options.getString("callAddress")
            requestedCapabilities = (listOf(CAP_TELECOM) +
                if (capture) listOf(CAP_CALL_AUDIO_CAPTURE) else emptyList())
                .plus(if (injection) listOf(CAP_CALL_TTS_INJECTION) else emptyList()).toIntArray()
        }
        DroidUseContractValidator.validateSessionSpec(spec)
        val owner = Binder()
        val callback = object : IDroidUseCallback.Stub() {
            override fun onCapabilitiesChanged(value: CapabilitySnapshot) = Unit
            override fun onOperationResult(value: OperationResult) = Unit
            override fun onSessionEvent(value: SessionEvent) {
                if (value.kind == EVENT_CLOSED) handler.post {
                    synchronized(lock) { active?.takeIf { it.handle.sessionId == value.sessionId }?.let {
                        it.closed = true; cleanup()
                    } }
                }
            }
        }
        val death = IBinder.DeathRecipient { handler.post { synchronized(lock) {
            if (active?.romOwner === owner) cleanup()
        } } }
        token.linkToDeath(death, 0)
        try {
            val handle = api().openSession(spec, owner, callback)
            active = Active(uid, token, death, owner, handle)
            if (!token.isBinderAlive) { cleanup(); error("CLIENT_DIED") }
            return Bundle().apply { putString("code", "READY"); putString("sessionId", handle.sessionId) }
        } catch (error: Exception) { token.unlinkToDeath(death, 0); throw error }
    }
    fun status(id: String, uid: Int): Bundle {
        val session = owned(id, uid)
        return Bundle().apply { putString("code", when {
            session.closed -> "CLOSED"; session.stopping -> "STOPPING"; session.paused -> "PAUSED"; else -> "READY"
        }); putString("sessionId", id) }
    }
    fun observe(id: String, uid: Int): Bundle {
        val h = owned(id, uid).handle
        val reply = api().observe(h, ObservationSpec().apply { sessionId = h.sessionId; epoch = h.epoch; afterFrameId = -1 })
        return Bundle().apply {
            putString("code", "OBSERVED"); putString("sessionId", id); putLong("frameId", reply.frameId)
            putLong("callGeneration", reply.windowGeneration); putLong("capturedAt", reply.capturedAtElapsedRealtimeMs)
            putStringArray("actions", reply.scopedActions ?: emptyArray())
            putParcelableArrayList("calls", ArrayList(reply.calls.orEmpty().map { call -> Bundle().apply {
                putString("callId", call.callId); putInt("state", call.state); putIntArray("operations", call.operations)
                putInt("audioRoute", call.audioRoute); putInt("supportedAudioRoutes", call.supportedAudioRoutes)
                putBoolean("muted", call.muted)
            } }))
        }
    }
    fun execute(id: String, uid: Int, requestId: String, fields: Bundle): Bundle {
        require(fields.keySet().all { it in setOf("kind", "callId", "address", "digits", "audioRoute", "enabled", "frameId", "callGeneration") })
        val h = owned(id, uid).handle
        val request = OperationRequest().apply {
            sessionId = id; epoch = h.epoch; this.requestId = requestId; domain = DOMAIN_TELECOM
            expectedFrameId = fields.getLong("frameId", -1); expectedWindowGeneration = fields.getLong("callGeneration", -1)
            telecom = TelecomOperation().apply {
                kind = fields.getInt("kind"); callId = fields.getString("callId"); address = fields.getString("address")
                digits = fields.getString("digits"); audioRoute = fields.getInt("audioRoute"); enabled = fields.getBoolean("enabled")
            }
        }
        DroidUseContractValidator.validateOperation(h, request)
        val reply = api().execute(h, request)
        return Bundle().apply { putString("code", reply.code); putBoolean("accepted", reply.accepted)
            putString("message", reply.message); putString("sessionId", id) }
    }
    fun stream(id: String, uid: Int, fields: Bundle): Bundle {
        require(fields.keySet().all { it in setOf("kind", "callId", "frameId", "callGeneration", "sampleRateHz") })
        val h = owned(id, uid).handle
        val spec = StreamSpec().apply {
            sessionId = id; epoch = h.epoch; kind = fields.getInt("kind"); callId = fields.getString("callId")
            expectedFrameId = fields.getLong("frameId", -1); expectedCallGeneration = fields.getLong("callGeneration", -1)
            direction = if (kind == STREAM_CALL_INJECTION) STREAM_DIRECTION_CLIENT_TO_SYSTEM else STREAM_DIRECTION_SYSTEM_TO_CLIENT
            format = 2; sampleRateHz = fields.getInt("sampleRateHz", 16000); channelCount = 1; capacityBytes = 65536
        }
        DroidUseContractValidator.validateStream(h, spec)
        val reply = api().openStream(h, spec)
        owned(id, uid).descriptors.put(spec.kind, reply.descriptor)?.close()
        return Bundle().apply { putString("code", "STREAM_OPENED"); putString("sessionId", id)
            putString("streamId", reply.streamId); putInt("transport", reply.transport)
            putInt("sampleRateHz", reply.sampleRateHz); putInt("format", reply.format)
            putInt("channelCount", reply.channelCount); putParcelable("descriptor", reply.descriptor) }
    }
    fun pause(id: String, uid: Int, paused: Boolean) {
        val h = owned(id, uid).handle
        api().updateSession(h, SessionUpdate().apply {
            sessionId = id; epoch = h.epoch; kind = if (paused) SESSION_UPDATE_PAUSE else SESSION_UPDATE_RESUME
        })
        owned(id, uid).let { session ->
            session.paused = paused
            session.descriptors.values.forEach { it.close() }; session.descriptors.clear()
        }
    }
    fun close(id: String, uid: Int) { owned(id, uid); cleanup() }
    fun cleanup() {
        val session = active ?: return
        session.stopping = true
        session.descriptors.values.forEach { runCatching { it.close() } }; session.descriptors.clear()
        try {
            if (!session.closed && api().asBinder().isBinderAlive) api().closeSession(session.handle, 0)
            if (session.closed || !api().asBinder().isBinderAlive) {
                session.clientToken.unlinkToDeath(session.death, 0); active = null
            } else handler.postDelayed({ synchronized(lock) { if (active === session) cleanup() } }, 1000)
        } catch (_: Exception) {
            handler.postDelayed({ synchronized(lock) { if (active === session) cleanup() } }, 1000)
        }
    }
}
