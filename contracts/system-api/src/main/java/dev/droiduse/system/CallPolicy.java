package dev.droiduse.system;

import static dev.droiduse.system.DroidUseContract.*;

/** Shared structural rules; live call identity and emergency checks belong to Telecom. */
public final class CallPolicy {
    private CallPolicy() {}

    public static boolean isCallStream(int kind) {
        return kind >= STREAM_CALL_UPLINK && kind <= STREAM_PRIVATE_MIC;
    }

    public static boolean validAddress(String address) {
        return address != null && address.matches("\\+?[0-9]{3,20}");
    }

    public static void session(SessionSpec spec) {
        require(spec.mode == MODE_CALL_ASSIST, "CALL_MODE_REQUIRED");
        require(empty(spec.targetPackage) && spec.targetTaskId == -1
                && spec.requestedDisplayId == -1 && spec.requestedScreenshotFps == 0
                && !spec.allowGlobalSettings, "CALL_SESSION_HAS_DISPLAY_OPTIONS");
        require(empty(spec.callAddress) || validAddress(spec.callAddress), "INVALID_DIAL_ADDRESS");
        boolean telecom = false;
        for (int cap : spec.requestedCapabilities) {
            require(cap >= CAP_TELECOM && cap <= CAP_PRIVATE_CALL_COMMAND
                    || cap == CAP_STREAM_TRANSPORT, "INVALID_CALL_CAPABILITY");
            telecom |= cap == CAP_TELECOM;
            if (cap == CAP_CALL_AUDIO_CAPTURE || cap == CAP_CALL_TTS_INJECTION
                    || cap == CAP_PRIVATE_CALL_COMMAND) require(spec.allowCallAudio, "CALL_AUDIO_NOT_ALLOWED");
        }
        require(telecom, "TELECOM_CAPABILITY_REQUIRED");
    }

    public static void operation(SessionHandle handle, OperationRequest request) {
        require(handle.mode == MODE_CALL_ASSIST, "CALL_MODE_REQUIRED");
        TelecomOperation op = request.telecom;
        require(op != null && op.kind >= TELECOM_DIAL && op.kind <= TELECOM_DTMF,
                "INVALID_TELECOM_OPERATION");
        require(request.expectedFrameId > 0 && request.expectedWindowGeneration > 0,
                "CALL_OBSERVATION_REQUIRED");
        require(op.kind == TELECOM_DIAL ? validAddress(op.address) && empty(op.callId)
                : empty(op.address) && op.callId != null && op.callId.matches("[A-Za-z0-9-]{1,128}"),
                "INVALID_CALL_TARGET");
        require(op.kind == TELECOM_DTMF ? op.digits != null && op.digits.matches("[0-9*#]")
                : empty(op.digits), "INVALID_DTMF");
        require(op.kind == TELECOM_SET_AUDIO_ROUTE ? op.audioRoute == 1 || op.audioRoute == 2
                || op.audioRoute == 4 || op.audioRoute == 8 : op.audioRoute == 0,
                "INVALID_AUDIO_ROUTE");
        require(op.kind == TELECOM_SET_MUTED || !op.enabled, "UNEXPECTED_ENABLED");
    }

    public static void stream(SessionHandle handle, StreamSpec spec) {
        require(handle.mode == MODE_CALL_ASSIST, "CALL_MODE_REQUIRED");
        require(spec.direction == (spec.kind == STREAM_CALL_INJECTION
                ? STREAM_DIRECTION_CLIENT_TO_SYSTEM : STREAM_DIRECTION_SYSTEM_TO_CLIENT),
                "INVALID_CALL_STREAM_DIRECTION");
        require(spec.format == 2 && spec.channelCount == 1, "PCM16_MONO_REQUIRED");
        require(spec.capacityBytes >= 4096 && spec.capacityBytes <= 65536
                && spec.capacityBytes % 2 == 0, "INVALID_CALL_STREAM_CAPACITY");
        require(spec.callId != null && spec.callId.matches("[A-Za-z0-9-]{1,128}")
                && spec.expectedFrameId > 0 && spec.expectedCallGeneration > 0,
                "CALL_STREAM_OBSERVATION_REQUIRED");
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }
    private static void require(boolean value, String error) {
        if (!value) throw new IllegalArgumentException(error);
    }
}
