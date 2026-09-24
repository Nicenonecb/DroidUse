package dev.droiduse.system;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validation shared by the Executor adapter and the ROM service.
 * @hide
 */
public final class DroidUseContractValidator {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final long MAX_AUTOMATION_TIMEOUT_MS = 4L * 60L * 60L * 1000L;
    private static final int MAX_SCREENSHOT_FPS = 5;
    private static final int MAX_OBSERVATION_DIMENSION = 4_096;
    private static final int MAX_STREAM_BYTES = 64 * 1024 * 1024;

    private DroidUseContractValidator() {}

    public static void validateSessionSpec(SessionSpec spec) {
        require(spec != null, "session spec required");
        require(spec.userId == 0, DroidUseContract.UNSUPPORTED_USER);
        require(spec.mode >= DroidUseContract.MODE_ISOLATED_DISPLAY
                && spec.mode <= DroidUseContract.MODE_MEDIA_OBSERVE, "invalid session mode");
        require(spec.targetPackage == null || spec.targetPackage.isEmpty()
                || PACKAGE_NAME.matcher(spec.targetPackage).matches(), "invalid target package");
        require(spec.targetTaskId >= -1, "invalid target task");
        require(spec.requestedDisplayId >= -1, "invalid requested display");
        require(spec.requestedScreenshotFps >= 0
                && spec.requestedScreenshotFps <= MAX_SCREENSHOT_FPS, "invalid screenshot fps");
        require(spec.timeoutMs >= 1_000 && spec.timeoutMs <= MAX_AUTOMATION_TIMEOUT_MS,
                "invalid session timeout");
        require(spec.requestedCapabilities != null
                && spec.requestedCapabilities.length <= DroidUseContract.CAP_APK_COMPATIBILITY,
                "invalid capability list");
        Set<Integer> unique = new HashSet<>();
        for (int capability : spec.requestedCapabilities) {
            require(capability >= DroidUseContract.CAP_SYSTEM_SERVICE
                    && capability <= DroidUseContract.CAP_APK_COMPATIBILITY,
                    "unknown capability");
            require(unique.add(capability), "duplicate capability");
        }
        if (spec.mode == DroidUseContract.MODE_CALL_ASSIST) CallPolicy.session(spec);
        else require(!spec.allowCallAudio && (spec.callAddress == null || spec.callAddress.isEmpty()),
                "call options require call mode");
    }

    public static void validateHandle(SessionHandle handle) {
        require(handle != null, "session handle required");
        require(validOpaqueId(handle.sessionId), "invalid session id");
        require(handle.epoch > 0, "invalid session epoch");
        require(handle.userId == 0, DroidUseContract.UNSUPPORTED_USER);
        require(handle.mode >= DroidUseContract.MODE_ISOLATED_DISPLAY
                && handle.mode <= DroidUseContract.MODE_MEDIA_OBSERVE, "invalid session mode");
        require(handle.displayId >= -1, "invalid display id");
    }

    public static void validateOperation(SessionHandle handle, OperationRequest request) {
        validateHandle(handle);
        require(request != null, "operation required");
        require(handle.sessionId.equals(request.sessionId) && handle.epoch == request.epoch,
                DroidUseContract.STALE_SESSION);
        require(validOpaqueId(request.requestId), "invalid request id");
        require(request.expectedFrameId >= -1 && request.expectedWindowGeneration >= -1,
                "invalid observation generation");
        int payloads = present(request.input) + present(request.app) + present(request.display)
                + present(request.device) + present(request.telecom);
        require(payloads == 1, "exactly one operation payload required");
        if (handle.mode == DroidUseContract.MODE_CALL_ASSIST)
            require(request.domain == DroidUseContract.DOMAIN_TELECOM, "call mode requires telecom domain");
        switch (request.domain) {
            case DroidUseContract.DOMAIN_APP_TASK -> {
                require(request.app != null, "app payload required");
                if (request.app.kind == DroidUseContract.APP_OPEN_TARGET) {
                    require(validOpaqueId(request.app.targetId), "target handle required");
                    require(request.app.packageName == null || request.app.packageName.isEmpty(),
                            "target handles cannot supply packages");
                    require(request.app.taskId == -1 && request.app.displayId == -1,
                            "target handles cannot supply tasks or displays");
                    require(request.expectedFrameId > 0 && request.expectedWindowGeneration > 0,
                            "target handles require a current observation");
                }
            }
            case DroidUseContract.DOMAIN_DISPLAY -> require(request.display != null, "display payload required");
            case DroidUseContract.DOMAIN_INPUT -> {
                require(request.input != null, "input payload required");
                int kind = request.input.kind;
                if (kind == DroidUseContract.INPUT_SELECTION
                        || kind >= DroidUseContract.INPUT_COPY && kind <= DroidUseContract.INPUT_SELECT_ALL) {
                    require(request.input.editorGeneration > 0 && request.expectedFrameId > 0
                            && request.expectedWindowGeneration > 0, "editor observation required");
                    require(request.input.text == null || request.input.text.isEmpty(),
                            "clipboard actions cannot supply text");
                    if (kind == DroidUseContract.INPUT_SELECTION) {
                        require(request.input.selectionStart >= 0
                                && request.input.selectionEnd >= request.input.selectionStart,
                                "invalid selection");
                    }
                }
            }
            case DroidUseContract.DOMAIN_SYSTEM_UI,
                 DroidUseContract.DOMAIN_PACKAGE,
                 DroidUseContract.DOMAIN_DEVICE,
                 DroidUseContract.DOMAIN_CONNECTIVITY,
                 DroidUseContract.DOMAIN_POWER -> {
                require(request.device != null, "device payload required");
                if (SystemActionPolicy.domain(request.device.kind) > 0) {
                    require(SystemActionPolicy.validRequest(request.domain, request.device), "invalid scoped system action");
                    require(request.expectedFrameId > 0 && request.expectedWindowGeneration > 0,
                            "system actions require a current observation");
                }
            }
            case DroidUseContract.DOMAIN_TELECOM -> CallPolicy.operation(handle, request);
            default -> throw new IllegalArgumentException("invalid operation domain");
        }
    }

    public static void validateObservation(SessionHandle handle, ObservationSpec spec) {
        validateHandle(handle);
        require(spec != null, "observation spec required");
        require(handle.sessionId.equals(spec.sessionId) && handle.epoch == spec.epoch,
                DroidUseContract.STALE_SESSION);
        require(spec.afterFrameId >= -1, "invalid observation frame");
        require(spec.maxWidth >= 0 && spec.maxWidth <= MAX_OBSERVATION_DIMENSION,
                "invalid observation width");
        require(spec.maxHeight >= 0 && spec.maxHeight <= MAX_OBSERVATION_DIMENSION,
                "invalid observation height");
    }

    public static void validateStream(SessionHandle handle, StreamSpec spec) {
        validateHandle(handle);
        require(spec != null, "stream spec required");
        require(handle.sessionId.equals(spec.sessionId) && handle.epoch == spec.epoch,
                DroidUseContract.STALE_SESSION);
        require(spec.kind >= DroidUseContract.STREAM_SCREENSHOT
                && spec.kind <= DroidUseContract.STREAM_MEDIA_PLAYBACK, "invalid stream kind");
        require(spec.direction == DroidUseContract.STREAM_DIRECTION_SYSTEM_TO_CLIENT
                || spec.direction == DroidUseContract.STREAM_DIRECTION_CLIENT_TO_SYSTEM,
                "invalid stream direction");
        require(spec.capacityBytes > 0 && spec.capacityBytes <= MAX_STREAM_BYTES,
                "invalid stream capacity");
        if (spec.kind != DroidUseContract.STREAM_SCREENSHOT) {
            require(spec.sampleRateHz == 8_000 || spec.sampleRateHz == 16_000
                    || spec.sampleRateHz == 48_000, "unsupported sample rate");
            require(spec.channelCount == 1 || spec.channelCount == 2,
                    "unsupported channel count");
        }
        if (CallPolicy.isCallStream(spec.kind)) CallPolicy.stream(handle, spec);
        else require(handle.mode != DroidUseContract.MODE_CALL_ASSIST, "call mode requires call stream");
    }

    private static int present(Object value) { return value == null ? 0 : 1; }

    private static boolean validOpaqueId(String value) {
        return value != null && OPAQUE_ID.matcher(value).matches();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
