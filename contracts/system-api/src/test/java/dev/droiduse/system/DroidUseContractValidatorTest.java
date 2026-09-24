package dev.droiduse.system;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DroidUseContractValidatorTest {
    @Test public void clipboardRequiresCurrentEditorAndCannotInjectText() {
        SessionHandle handle = handle();
        OperationRequest request = new OperationRequest();
        request.sessionId = handle.sessionId; request.epoch = handle.epoch;
        request.requestId = "clipboard-1"; request.expectedFrameId = 3;
        request.expectedWindowGeneration = 2; request.domain = DroidUseContract.DOMAIN_INPUT;
        request.input = new InputOperation(); request.input.kind = DroidUseContract.INPUT_PASTE;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.input.editorGeneration = 4;
        DroidUseContractValidator.validateOperation(handle, request);
        request.input.text = "injected clipboard";
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.input.text = null; request.expectedFrameId = -1;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.expectedFrameId = 3; request.input.kind = DroidUseContract.INPUT_SELECTION;
        request.input.selectionStart = 2; request.input.selectionEnd = 1;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
    }
    @Test public void acceptsMainUserPhysicalSession() {
        SessionSpec spec = new SessionSpec();
        spec.userId = 0;
        spec.mode = DroidUseContract.MODE_PHYSICAL_CONTROL;
        spec.targetPackage = "com.example.target";
        spec.targetTaskId = -1;
        spec.requestedDisplayId = 0;
        spec.requestedCapabilities = new int[] {
                DroidUseContract.CAP_PHYSICAL_DISPLAY,
                DroidUseContract.CAP_INPUT_INJECTION
        };
        spec.requestedScreenshotFps = 2;
        spec.timeoutMs = 60_000;
        DroidUseContractValidator.validateSessionSpec(spec);
    }

    @Test public void rejectsOtherUsersAndDuplicateCapabilities() {
        SessionSpec spec = new SessionSpec();
        spec.userId = 10;
        spec.mode = DroidUseContract.MODE_PHYSICAL_CONTROL;
        spec.targetPackage = "com.example.target";
        spec.targetTaskId = -1;
        spec.requestedDisplayId = 0;
        spec.requestedCapabilities = new int[] { DroidUseContract.CAP_INPUT_INJECTION };
        spec.requestedScreenshotFps = 1;
        spec.timeoutMs = 60_000;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateSessionSpec(spec));
        spec.userId = 0;
        spec.requestedCapabilities = new int[] {
                DroidUseContract.CAP_INPUT_INJECTION,
                DroidUseContract.CAP_INPUT_INJECTION
        };
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateSessionSpec(spec));
    }

    @Test public void operationRequiresOneMatchingPayload() {
        SessionHandle handle = handle();
        OperationRequest request = new OperationRequest();
        request.sessionId = handle.sessionId;
        request.epoch = handle.epoch;
        request.requestId = "request-1";
        request.expectedFrameId = 1;
        request.expectedWindowGeneration = 2;
        request.domain = DroidUseContract.DOMAIN_INPUT;
        request.input = new InputOperation();
        DroidUseContractValidator.validateOperation(handle, request);

        request.app = new AppOperation();
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
    }

    @Test public void validatesBoundedSpeechStream() {
        SessionHandle handle = handle();
        handle.mode = DroidUseContract.MODE_CALL_ASSIST;
        StreamSpec spec = new StreamSpec();
        spec.sessionId = handle.sessionId;
        spec.epoch = handle.epoch;
        spec.kind = DroidUseContract.STREAM_CALL_DOWNLINK;
        spec.direction = DroidUseContract.STREAM_DIRECTION_SYSTEM_TO_CLIENT;
        spec.format = 2;
        spec.sampleRateHz = 16_000;
        spec.channelCount = 1;
        spec.capacityBytes = 65_536;
        spec.callId = "call-1"; spec.expectedFrameId = 1; spec.expectedCallGeneration = 1;
        DroidUseContractValidator.validateStream(handle, spec);
        spec.capacityBytes = Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateStream(handle, spec));
    }

    @Test public void validatesObservationBoundsAndEpoch() {
        SessionHandle handle = handle();
        ObservationSpec spec = new ObservationSpec();
        spec.sessionId = handle.sessionId;
        spec.epoch = handle.epoch;
        spec.afterFrameId = -1;
        spec.maxWidth = 1080;
        spec.maxHeight = 2400;
        DroidUseContractValidator.validateObservation(handle, spec);

        spec.maxWidth = 4097;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateObservation(handle, spec));
        spec.maxWidth = 1080;
        spec.epoch++;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateObservation(handle, spec));
    }

    private static SessionHandle handle() {
        SessionHandle handle = new SessionHandle();
        handle.sessionId = "session-1";
        handle.epoch = 1;
        handle.userId = 0;
        handle.mode = DroidUseContract.MODE_PHYSICAL_CONTROL;
        handle.displayId = 0;
        return handle;
    }

    @Test public void opaqueLaunchRejectsInjectedPackageTaskAndMissingFrame() {
        SessionHandle handle = handle();
        OperationRequest request = new OperationRequest();
        request.sessionId = handle.sessionId; request.epoch = handle.epoch;
        request.requestId = "launch-1"; request.expectedFrameId = 7;
        request.expectedWindowGeneration = 2;
        request.domain = DroidUseContract.DOMAIN_APP_TASK;
        request.app = new AppOperation();
        request.app.kind = DroidUseContract.APP_OPEN_TARGET;
        request.app.targetId = "server-issued-handle";
        request.app.taskId = -1; request.app.displayId = -1;
        DroidUseContractValidator.validateOperation(handle, request);
        request.app.packageName = "com.android.settings";
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.app.packageName = null;
        request.app.taskId = 10;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.app.taskId = -1;
        request.app.displayId = 0;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.app.displayId = -1;
        request.expectedFrameId = -1;
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
        request.expectedFrameId = 7;
        request.app.targetId = "https://example.com/arbitrary";
        assertThrows(IllegalArgumentException.class,
                () -> DroidUseContractValidator.validateOperation(handle, request));
    }
}
