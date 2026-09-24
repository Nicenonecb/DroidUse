package dev.droiduse.executor.app

import android.os.*
import android.system.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Requires an explicitly established ordinary SIM test call; never dials, answers or hangs up. */
class RomM4Test {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private fun callback() = object : IDroidUseCallback.Stub() {
        override fun onOperationResult(value: OperationResult) = Unit
        override fun onSessionEvent(value: SessionEvent) = Unit
        override fun onCapabilitiesChanged(value: CapabilitySnapshot) = Unit
    }
    private fun spec(audio: Boolean = false, inject: Boolean = false) = SessionSpec().apply {
        userId = 0; mode = MODE_CALL_ASSIST; targetTaskId = -1; requestedDisplayId = -1; timeoutMs = 60000
        allowCallAudio = audio || inject
        requestedCapabilities = (listOf(CAP_TELECOM) + if (audio) listOf(CAP_CALL_AUDIO_CAPTURE) else emptyList())
            .plus(if (inject) listOf(CAP_CALL_TTS_INJECTION) else emptyList()).toIntArray()
    }
    private fun observe(api: IDroidUseSystem, handle: SessionHandle) = api.observe(handle, ObservationSpec().apply {
        sessionId = handle.sessionId; epoch = handle.epoch; afterFrameId = -1
    })
    private fun mute(handle: SessionHandle, frame: Observation, call: CallSnapshot) = OperationRequest().apply {
        sessionId = handle.sessionId; epoch = handle.epoch; requestId = UUID.randomUUID().toString()
        expectedFrameId = frame.frameId; expectedWindowGeneration = frame.windowGeneration; domain = DOMAIN_TELECOM
        telecom = TelecomOperation().apply { kind = TELECOM_SET_MUTED; callId = call.callId; enabled = call.muted }
    }
    @Test fun observesCellularCallRejectsStaleHandlesAndPause() {
        assumeTrue("Requires explicit active SIM test call", args.getString("m4Call") == "true")
        val api = requireNotNull(RomSystemClient(instrumentation.targetContext).service)
        val handle = api.openSession(spec(), Binder(), callback())
        try {
            val first = observe(api, handle)
            assertEquals(-1, first.displayId); assertNull(first.capture)
            val call = first.calls.single(); assertEquals(android.telecom.Call.STATE_ACTIVE, call.state)
            val fresh = observe(api, handle)
            assertThrows(RuntimeException::class.java) { api.execute(handle, mute(handle, first, call)) }
            val invalid = mute(handle, fresh, fresh.calls.single()).apply { telecom.callId = "not-a-real-call" }
            assertThrows(RuntimeException::class.java) { api.execute(handle, invalid) }
            api.updateSession(handle, SessionUpdate().apply {
                sessionId = handle.sessionId; epoch = handle.epoch; kind = SESSION_UPDATE_PAUSE
            })
            assertThrows(RuntimeException::class.java) { observe(api, handle) }
            api.updateSession(handle, SessionUpdate().apply {
                sessionId = handle.sessionId; epoch = handle.epoch; kind = SESSION_UPDATE_RESUME
            })
            assertEquals(call.callId, observe(api, handle).calls.single().callId)
        } finally { api.closeSession(handle, 0) }
    }
    @Test fun receivesBoundedPcmPacketsFromExplicitCaptureSource() {
        assumeTrue("Requires explicit audio test with consenting peer speaking", args.getString("m4Audio") == "true")
        val kind = args.getString("m4Source", "downlink").let {
            when (it) { "uplink" -> STREAM_CALL_UPLINK; "mixed" -> STREAM_CALL_MIXED
                "downlink" -> STREAM_CALL_DOWNLINK; else -> error("Unknown source") }
        }
        val api = requireNotNull(RomSystemClient(instrumentation.targetContext).service)
        val handle = api.openSession(spec(audio = true), Binder(), callback())
        try {
            val frame = observe(api, handle); val call = frame.calls.single()
            assertEquals(android.telecom.Call.STATE_ACTIVE, call.state)
            val stream = api.openStream(handle, StreamSpec().apply {
                sessionId = handle.sessionId; epoch = handle.epoch; this.kind = kind
                direction = STREAM_DIRECTION_SYSTEM_TO_CLIENT; format = 2; sampleRateHz = 16000
                channelCount = 1; capacityBytes = 65536; callId = call.callId
                expectedFrameId = frame.frameId; expectedCallGeneration = frame.windowGeneration
            })
            assertEquals(1, stream.transport)
            stream.descriptor.use { fd ->
                Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                val deadline = SystemClock.elapsedRealtime() + 5000
                fun read(size: Int): ByteArray {
                    val bytes = ByteArray(size); var at = 0
                    while (at < size) {
                        check(SystemClock.elapsedRealtime() < deadline) { "PCM source stalled" }
                        val poll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                        if (Os.poll(arrayOf(poll), 100) == 0) continue
                        try {
                            val count = Os.read(fd.fileDescriptor, bytes, at, size - at)
                            check(count > 0) { "PCM stream ended" }; at += count
                        } catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN) throw e }
                    }
                    return bytes
                }
                var nonZero = 0
                repeat(10) { seq ->
                    val packet = CallPcmFrame.parse(read(24), seq.toLong(), SystemClock.elapsedRealtimeNanos(), 16000)
                    nonZero += read(packet.size).count { it.toInt() != 0 }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putInt("pcmPackets", 10); putInt("nonZeroBytes", nonZero)
                    putString("scope", "transport smoke only; separation and far-end playback need human verification")
                })
                assertTrue("No signal; check selected source and test speech", nonZero > 0)
            }
        } finally { api.closeSession(handle, 0) }
    }
}
