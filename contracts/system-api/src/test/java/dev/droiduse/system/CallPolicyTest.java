package dev.droiduse.system;

import static dev.droiduse.system.DroidUseContract.*;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CallPolicyTest {
    private SessionHandle handle() {
        SessionHandle h = new SessionHandle(); h.sessionId = "session"; h.epoch = 1;
        h.mode = MODE_CALL_ASSIST; h.displayId = -1; return h;
    }
    private SessionSpec session() {
        SessionSpec s = new SessionSpec(); s.mode = MODE_CALL_ASSIST; s.targetTaskId = -1;
        s.requestedDisplayId = -1; s.timeoutMs = 60000; s.requestedCapabilities = new int[] {CAP_TELECOM}; return s;
    }
    private OperationRequest request(int kind) {
        OperationRequest r = new OperationRequest(); r.sessionId = "session"; r.epoch = 1;
        r.requestId = "request"; r.expectedFrameId = 1; r.expectedWindowGeneration = 1;
        r.domain = DOMAIN_TELECOM; r.telecom = new TelecomOperation(); r.telecom.kind = kind;
        r.telecom.callId = "call-1"; return r;
    }
    private StreamSpec stream() {
        StreamSpec s = new StreamSpec(); s.sessionId = "session"; s.epoch = 1;
        s.kind = STREAM_CALL_DOWNLINK; s.direction = STREAM_DIRECTION_SYSTEM_TO_CLIENT;
        s.format = 2; s.sampleRateHz = 16000; s.channelCount = 1; s.capacityBytes = 65536;
        s.callId = "call-1"; s.expectedFrameId = 1; s.expectedCallGeneration = 1; return s;
    }
    @Test public void callModeRejectsScreenOptionsAndUnconsentedAudio() {
        SessionSpec s = session(); DroidUseContractValidator.validateSessionSpec(s);
        s.targetPackage = "com.example.app";
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateSessionSpec(s));
        s.targetPackage = null; s.requestedCapabilities = new int[] {CAP_TELECOM, CAP_CALL_AUDIO_CAPTURE};
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateSessionSpec(s));
        s.allowCallAudio = true; DroidUseContractValidator.validateSessionSpec(s);
        s.requestedCapabilities = new int[] {CAP_TELECOM, CAP_INPUT_INJECTION};
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateSessionSpec(s));
    }
    @Test public void dialRejectsUriMmiPauseAndInjectedCallHandle() {
        OperationRequest r = request(TELECOM_DIAL); r.telecom.callId = null;
        for (String address : new String[] {"tel:12345", "*#06#", "123,456", "123;456", "123\n456", "", "+"}) {
            r.telecom.address = address;
            assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
        }
        r.telecom.address = "+8613800000000";
        DroidUseContractValidator.validateOperation(handle(), r);
        r.telecom.callId = "another-call";
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
    }
    @Test public void callOperationsRejectOtherModesAndMissingObservation() {
        OperationRequest r = request(TELECOM_END); DroidUseContractValidator.validateOperation(handle(), r);
        SessionHandle screen = handle(); screen.mode = MODE_ISOLATED_DISPLAY;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(screen, r));
        r.expectedFrameId = -1;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
        r.expectedFrameId = 1; r.telecom.kind = 999;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
    }
    @Test public void dtmfAndRouteHaveNarrowShapes() {
        OperationRequest r = request(TELECOM_DTMF); r.telecom.digits = "#";
        DroidUseContractValidator.validateOperation(handle(), r);
        r.telecom.digits = "123";
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
        r.telecom.kind = TELECOM_SET_AUDIO_ROUTE; r.telecom.digits = null; r.telecom.audioRoute = 8;
        DroidUseContractValidator.validateOperation(handle(), r);
        r.telecom.audioRoute = 15;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(handle(), r));
    }
    @Test public void streamsRejectWrongDirectionModeFormatAndUnboundTarget() {
        StreamSpec s = stream(); DroidUseContractValidator.validateStream(handle(), s);
        s.kind = STREAM_CALL_INJECTION;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateStream(handle(), s));
        s.direction = STREAM_DIRECTION_CLIENT_TO_SYSTEM; DroidUseContractValidator.validateStream(handle(), s);
        s.format = 4;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateStream(handle(), s));
        s.format = 2; s.callId = null;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateStream(handle(), s));
        s.callId = "call-1"; s.capacityBytes = 1_048_576;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateStream(handle(), s));
    }
    @Test public void everyObservationIsSingleUseAndReplacementInvalidatesIt() {
        CallObservationGate gate = new CallObservationGate(); long old = gate.observe(100);
        long current = gate.observe(200);
        assertThrows(IllegalStateException.class, () -> gate.consume(old, gate.generation(), 201));
        gate.consume(current, gate.generation(), 202);
        assertThrows(IllegalStateException.class, () -> gate.consume(current, gate.generation(), 203));
    }
    @Test public void stateRoutePauseAndExpiryInvalidateObservations() {
        CallObservationGate gate = new CallObservationGate(); long frame = gate.observe(10), generation = gate.generation();
        gate.invalidate();
        assertThrows(IllegalStateException.class, () -> gate.consume(frame, generation, 11));
        long next = gate.observe(20);
        assertThrows(IllegalStateException.class, () -> gate.consume(next, gate.generation(), 5020));
        assertThrows(IllegalStateException.class, () -> gate.consume(next, gate.generation(), 19));
    }
    @Test public void framedPcmRoundTripsWithoutChangingSamples() {
        byte[] pcm = {1, 2, 3, 4}; byte[] packet = CallPcmFrame.encode(3, 1_000_000_000L, pcm, pcm.length);
        CallPcmFrame decoded = CallPcmFrame.parse(Arrays.copyOf(packet, 24), 3, 1_010_000_000L, 16000);
        assertEquals(4, decoded.size); assertArrayEquals(pcm, Arrays.copyOfRange(packet, 24, packet.length));
    }
    @Test public void injectionRejectsReplayStaleFutureOversizedAndOddSamples() {
        byte[] header = Arrays.copyOf(CallPcmFrame.encode(0, 1_000_000_000L, new byte[640], 640), 24);
        assertThrows(IllegalArgumentException.class, () -> CallPcmFrame.parse(header, 1, 1_000_000_000L, 16000));
        assertThrows(IllegalArgumentException.class, () -> CallPcmFrame.parse(header, 0, 1_600_000_000L, 16000));
        assertThrows(IllegalArgumentException.class, () -> CallPcmFrame.parse(header, 0, 100_000_000L, 16000));
        assertThrows(IllegalArgumentException.class, () -> CallPcmFrame.parse(header, 0, 1_000_000_000L, 8000));
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 3);
        assertThrows(IllegalArgumentException.class, () -> CallPcmFrame.parse(header, 0, 1_000_000_000L, 16000));
    }
}
