package com.android.server.droiduse;

import android.app.KeyguardManager;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.computercontrol.ComputerControlSession;
import android.companion.virtual.computercontrol.ComputerControlSessionParams;
import android.content.Context;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualTouchEvent;
import android.media.Image;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.OsConstants;
import android.view.Display;
import android.view.KeyEvent;

import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.AppOperation;
import dev.droiduse.system.CapabilityStatus;
import dev.droiduse.system.DroidUseContract;
import dev.droiduse.system.InputOperation;
import dev.droiduse.system.Observation;
import dev.droiduse.system.ObservationSpec;
import dev.droiduse.system.OperationAdmission;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.OperationResult;
import dev.droiduse.system.PointerSample;
import dev.droiduse.system.SemanticNode;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.SessionUpdate;
import dev.droiduse.system.StreamHandle;
import dev.droiduse.system.StreamSpec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android 16 adapter backed by the platform's trusted ComputerControl virtual display. */
final class ComputerControlDroidUsePlatform implements DroidUsePlatform {
    private static final long SESSION_CREATE_TIMEOUT_SECONDS = 8;
    private static final long SCREENSHOT_WAIT_MS = 500;
    private static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_SCREENSHOT_FPS = 1;

    private static final Set<SystemSession.Protection> ISOLATED_PROTECTIONS = Set.of(
            SystemSession.Protection.TARGET_OWNERSHIP,
            SystemSession.Protection.DISPLAY_ROUTE,
            SystemSession.Protection.INPUT_ROUTE,
            SystemSession.Protection.AUDIO_ROUTE,
            SystemSession.Protection.MICROPHONE_ROUTE,
            SystemSession.Protection.CAMERA_ROUTE,
            SystemSession.Protection.STREAM_LIMITS);

    private static final class ActiveSession {
        final long epoch;
        final String targetPackage;
        final ComputerControlSession control;
        final int displayId;
        final int screenshotFps;
        final AtomicBoolean remotelyClosed;
        long latestFrameId = -1;
        long lastCaptureElapsedRealtimeMs;

        ActiveSession(long epoch, String targetPackage, ComputerControlSession control,
                int displayId, int screenshotFps, AtomicBoolean remotelyClosed) {
            this.epoch = epoch;
            this.targetPackage = targetPackage;
            this.control = control;
            this.displayId = displayId;
            this.screenshotFps = screenshotFps;
            this.remotelyClosed = remotelyClosed;
        }
    }

    private final Context mContext;
    private final KeyguardManager mKeyguard;
    private final DisplayManager mDisplays;
    private ActiveSession mActive;

    ComputerControlDroidUsePlatform(Context context) {
        mContext = context;
        mKeyguard = context.getSystemService(KeyguardManager.class);
        mDisplays = context.getSystemService(DisplayManager.class);
    }

    @Override public CapabilityStatus[] capabilities() {
        CapabilityStatus[] result = new CapabilityStatus[20];
        for (int id = DroidUseContract.CAP_APP_TASK_CONTROL;
             id <= DroidUseContract.CAP_STREAM_TRANSPORT; id++) {
            result[id - DroidUseContract.CAP_APP_TASK_CONTROL] = status(id,
                    DroidUseContract.AVAILABILITY_UNAVAILABLE, "NOT_IMPLEMENTED");
        }
        if (!coreReady()) return result;
        set(result, status(DroidUseContract.CAP_APP_TASK_CONTROL,
                DroidUseContract.AVAILABILITY_DEGRADED, "LAUNCH_SWITCH_RESTORE_ONLY"));
        set(result, status(DroidUseContract.CAP_VIRTUAL_DISPLAY,
                DroidUseContract.AVAILABILITY_DEGRADED, "FIXED_SIZE_SESSION_DISPLAY"));
        set(result, status(DroidUseContract.CAP_DISPLAY_CAPTURE,
                DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(DroidUseContract.CAP_INPUT_INJECTION,
                DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(DroidUseContract.CAP_SYSTEM_NAVIGATION,
                DroidUseContract.AVAILABILITY_DEGRADED, "BACK_AND_KEY_EVENTS_ONLY"));
        set(result, status(DroidUseContract.CAP_IME_CLIPBOARD,
                DroidUseContract.AVAILABILITY_DEGRADED, "TEXT_INPUT_ONLY"));
        set(result, status(DroidUseContract.CAP_STREAM_TRANSPORT,
                DroidUseContract.AVAILABILITY_DEGRADED, "SCREENSHOT_FD_ONLY"));
        return result;
    }

    @Override public boolean coreReady() {
        return android.companion.virtualdevice.flags.Flags.computerControlAccess()
                && android.companion.virtualdevice.flags.Flags.computerControlActivityPolicyStrict()
                && android.companion.virtualdevice.flags.Flags.computerControlTyping()
                && android.companion.virtualdevice.flags.Flags.enableAnimationsPerDisplay()
                && android.companion.virtualdevice.flags.Flags.defaultDeviceCameraAccessPolicy()
                && android.media.audiopolicy.Flags.recordAudioDeviceAwarePermission()
                && mContext.getSystemService(VirtualDeviceManager.class) != null;
    }

    @Override public PreparedSession prepare(SessionSpec spec, long epoch) throws Exception {
        if (spec.mode != DroidUseContract.MODE_ISOLATED_DISPLAY) {
            throw unsupported("ONLY_ISOLATED_DISPLAY_IMPLEMENTED");
        }
        if (spec.targetPackage == null || spec.targetPackage.isEmpty()) {
            throw invalid("TARGET_PACKAGE_REQUIRED");
        }
        if (mActive != null) throw new Failure(DroidUseContract.ERROR_BUSY, DroidUseContract.BUSY);
        ensureUnlocked();
        if (!coreReady()) throw unsupported("COMPUTER_CONTROL_NOT_READY");

        VirtualDeviceManager manager = mContext.getSystemService(VirtualDeviceManager.class);
        if (manager == null) throw unsupported("VIRTUAL_DEVICE_MANAGER_MISSING");
        ComputerControlSessionParams params = new ComputerControlSessionParams.Builder()
                .setName("droiduse-" + epoch)
                .setTargetPackageNames(List.of(spec.targetPackage))
                .setDisplayAlwaysUnlocked(false)
                .build();
        CompletableFuture<ComputerControlSession> created = new CompletableFuture<>();
        AtomicBoolean remotelyClosed = new AtomicBoolean(false);
        manager.requestComputerControlSession(params, Runnable::run,
                new ComputerControlSession.Callback() {
                    @Override public void onSessionPending(IntentSender ignored) {
                        created.completeExceptionally(
                                unsupported("COMPUTER_CONTROL_CONSENT_REQUIRED"));
                    }

                    @Override public void onSessionCreated(ComputerControlSession session) {
                        if (!created.complete(session)) session.close();
                    }

                    @Override public void onSessionCreationFailed(int errorCode) {
                        created.completeExceptionally(new Failure(
                                DroidUseContract.ERROR_UNSUPPORTED,
                                "COMPUTER_CONTROL_CREATE_FAILED_" + errorCode));
                    }

                    @Override public void onSessionClosed() {
                        remotelyClosed.set(true);
                    }
                });

        ComputerControlSession control;
        try {
            control = created.get(SESSION_CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            created.cancel(false);
            throw new Failure(DroidUseContract.ERROR_TIMEOUT, DroidUseContract.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
        if (remotelyClosed.get()) {
            control.close();
            throw new Failure(DroidUseContract.ERROR_UNKNOWN, "COMPUTER_CONTROL_CLOSED_EARLY");
        }
        int fps = spec.requestedScreenshotFps == 0
                ? DEFAULT_SCREENSHOT_FPS : spec.requestedScreenshotFps;
        mActive = new ActiveSession(epoch, spec.targetPackage, control,
                control.getVirtualDisplayId(), fps, remotelyClosed);
        return new PreparedSession(mActive.displayId, ISOLATED_PROTECTIONS);
    }

    @Override public Set<SystemSession.Protection> installedProtections(SessionHandle handle)
            throws Exception {
        ActiveSession active = active(handle);
        if (isLocked() || !coreReady() || active.remotelyClosed.get()) return Set.of();
        return ISOLATED_PROTECTIONS;
    }

    @Override public void activate(SessionHandle handle, SessionSpec spec) throws Exception {
        ActiveSession active = activeUnlocked(handle);
        if (!active.targetPackage.equals(spec.targetPackage)) throw staleTarget();
        active.control.launchApplication(active.targetPackage);
    }

    @Override public Observation observe(SessionHandle handle, ObservationSpec spec)
            throws Exception {
        ActiveSession active = activeUnlocked(handle);
        if (spec.includeSemantics) throw unsupported("UI_SEMANTICS_NOT_IMPLEMENTED");
        Observation result = new Observation();
        result.sessionId = handle.sessionId;
        result.epoch = handle.epoch;
        result.displayId = active.displayId;
        result.packageName = active.targetPackage;
        result.taskId = -1;
        result.windowGeneration = 1;
        result.editorGeneration = 1;
        result.semantics = new SemanticNode[0];
        result.captureMimeType = "";
        result.frameId = active.latestFrameId;
        result.capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();

        Display display = mDisplays == null ? null : mDisplays.getDisplay(active.displayId);
        result.rotation = display == null ? 0 : display.getRotation();
        if (!spec.includeCapture) return result;

        long now = SystemClock.elapsedRealtime();
        long minimumIntervalMs = 1_000L / active.screenshotFps;
        if (active.lastCaptureElapsedRealtimeMs != 0
                && now - active.lastCaptureElapsedRealtimeMs < minimumIntervalMs) {
            throw new Failure(DroidUseContract.ERROR_RESOURCE_LIMIT,
                    DroidUseContract.RESOURCE_LIMIT);
        }
        Image image = acquireScreenshot(active.control);
        if (image == null) {
            throw new Failure(DroidUseContract.ERROR_TIMEOUT, "SCREENSHOT_NOT_READY");
        }
        try {
            result.width = image.getWidth();
            result.height = image.getHeight();
            result.capture = encodeScreenshot(image, spec.maxWidth, spec.maxHeight);
            result.captureMimeType = "image/png";
            result.frameId = ++active.latestFrameId;
            result.capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
            active.lastCaptureElapsedRealtimeMs = result.capturedAtElapsedRealtimeMs;
            return result;
        } finally {
            image.close();
        }
    }

    @Override public OperationAdmission execute(SessionHandle handle, OperationRequest request,
            Completion completion) throws Exception {
        ActiveSession active = activeUnlocked(handle);
        if (request.expectedFrameId >= 0
                && request.expectedFrameId != active.latestFrameId) {
            throw new Failure(DroidUseContract.ERROR_INVALID_REQUEST,
                    DroidUseContract.STALE_OBSERVATION);
        }
        switch (request.domain) {
            case DroidUseContract.DOMAIN_APP_TASK -> executeApp(active, request.app);
            case DroidUseContract.DOMAIN_INPUT -> executeInput(active, request.input);
            default -> throw unsupported("OPERATION_DOMAIN_NOT_IMPLEMENTED");
        }

        long now = SystemClock.elapsedRealtime();
        OperationAdmission admission = new OperationAdmission();
        admission.sessionId = handle.sessionId;
        admission.requestId = request.requestId;
        admission.accepted = true;
        admission.code = DroidUseContract.OK;
        admission.message = DroidUseContract.OK;
        admission.acceptedAtElapsedRealtimeMs = now;

        OperationResult result = new OperationResult();
        result.sessionId = handle.sessionId;
        result.requestId = request.requestId;
        result.code = DroidUseContract.OK;
        result.message = DroidUseContract.OK;
        result.completedAtElapsedRealtimeMs = now;
        result.resultingFrameId = active.latestFrameId;
        completion.complete(result);
        return admission;
    }

    private void executeApp(ActiveSession active, AppOperation operation) throws Exception {
        if (operation == null) throw invalid("APP_OPERATION_REQUIRED");
        if (operation.packageName != null && !operation.packageName.isEmpty()
                && !active.targetPackage.equals(operation.packageName)) throw staleTarget();
        switch (operation.kind) {
            case DroidUseContract.APP_LAUNCH,
                 DroidUseContract.APP_SWITCH,
                 DroidUseContract.APP_RESTORE ->
                    active.control.launchApplication(active.targetPackage);
            default -> throw unsupported("APP_OPERATION_NOT_IMPLEMENTED");
        }
    }

    private void executeInput(ActiveSession active, InputOperation input) throws Exception {
        if (input == null) throw invalid("INPUT_OPERATION_REQUIRED");
        switch (input.kind) {
            case DroidUseContract.INPUT_TAP -> active.control.tap(input.x1, input.y1);
            case DroidUseContract.INPUT_DOUBLE_TAP -> {
                active.control.tap(input.x1, input.y1);
                SystemClock.sleep(100);
                active.control.tap(input.x1, input.y1);
            }
            case DroidUseContract.INPUT_LONG_PRESS -> active.control.longPress(input.x1, input.y1);
            case DroidUseContract.INPUT_SWIPE, DroidUseContract.INPUT_DRAG ->
                    active.control.swipe(input.x1, input.y1, input.x2, input.y2);
            case DroidUseContract.INPUT_MULTI_TOUCH -> sendPointerSamples(active, input.pointerSamples);
            case DroidUseContract.INPUT_KEY -> sendKey(active, input.keyCode);
            case DroidUseContract.INPUT_TEXT -> {
                if (input.text == null) throw invalid("INPUT_TEXT_REQUIRED");
                active.control.insertText(input.text, false, false);
            }
            case DroidUseContract.INPUT_DELETE -> {
                int before = Math.max(1, input.deleteBefore);
                for (int i = 0; i < before; i++) sendKey(active, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < input.deleteAfter; i++)
                    sendKey(active, KeyEvent.KEYCODE_FORWARD_DEL);
            }
            case DroidUseContract.INPUT_EDITOR_ACTION -> sendKey(active, KeyEvent.KEYCODE_ENTER);
            default -> throw unsupported("INPUT_OPERATION_NOT_IMPLEMENTED");
        }
    }

    private static void sendKey(ActiveSession active, int keyCode) {
        active.control.sendKeyEvent(new VirtualKeyEvent.Builder()
                .setKeyCode(keyCode).setAction(VirtualKeyEvent.ACTION_DOWN).build());
        active.control.sendKeyEvent(new VirtualKeyEvent.Builder()
                .setKeyCode(keyCode).setAction(VirtualKeyEvent.ACTION_UP).build());
    }

    private static void sendPointerSamples(ActiveSession active, PointerSample[] samples)
            throws Exception {
        if (samples == null || samples.length == 0) throw invalid("POINTER_SAMPLES_REQUIRED");
        long previousOffset = 0;
        long startedAtNanos = SystemClock.uptimeMillis() * 1_000_000L;
        for (PointerSample sample : samples) {
            if (sample == null || sample.pointerId < 0 || sample.pointerId > 15
                    || sample.timeOffsetMs < previousOffset || sample.timeOffsetMs > 10_000) {
                throw invalid("INVALID_POINTER_SAMPLE");
            }
            long delay = sample.timeOffsetMs - previousOffset;
            if (delay > 0) SystemClock.sleep(delay);
            previousOffset = sample.timeOffsetMs;
            int action = pointerAction(sample.action);
            active.control.sendTouchEvent(new VirtualTouchEvent.Builder()
                    .setPointerId(sample.pointerId)
                    .setToolType(action == VirtualTouchEvent.ACTION_CANCEL
                            ? VirtualTouchEvent.TOOL_TYPE_PALM
                            : VirtualTouchEvent.TOOL_TYPE_FINGER)
                    .setAction(action)
                    .setX(sample.x)
                    .setY(sample.y)
                    .setPressure(action == VirtualTouchEvent.ACTION_UP ? 0 : 1)
                    .setMajorAxisSize(1)
                    .setEventTimeNanos(startedAtNanos + sample.timeOffsetMs * 1_000_000L)
                    .build());
        }
    }

    private static int pointerAction(int action) throws Failure {
        return switch (action) {
            case DroidUseContract.POINTER_DOWN -> VirtualTouchEvent.ACTION_DOWN;
            case DroidUseContract.POINTER_UP -> VirtualTouchEvent.ACTION_UP;
            case DroidUseContract.POINTER_MOVE -> VirtualTouchEvent.ACTION_MOVE;
            case DroidUseContract.POINTER_CANCEL -> VirtualTouchEvent.ACTION_CANCEL;
            default -> throw invalid("INVALID_POINTER_ACTION");
        };
    }

    private static Image acquireScreenshot(ComputerControlSession control) {
        long deadline = SystemClock.elapsedRealtime() + SCREENSHOT_WAIT_MS;
        Image image;
        do {
            image = control.getScreenshot();
            if (image != null) return image;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    private static ParcelFileDescriptor encodeScreenshot(Image image, int maxWidth, int maxHeight)
            throws Exception {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        Bitmap cropped = null;
        Bitmap output = null;
        try {
            padded.copyPixelsFromBuffer(plane.getBuffer());
            cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            float widthScale = maxWidth > 0 ? (float) maxWidth / width : 1f;
            float heightScale = maxHeight > 0 ? (float) maxHeight / height : 1f;
            float scale = Math.min(1f, Math.min(widthScale, heightScale));
            output = scale < 1f
                    ? Bitmap.createScaledBitmap(cropped, Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)), true)
                    : cropped;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!output.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                throw new IllegalStateException("SCREENSHOT_ENCODE_FAILED");
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length == 0 || encoded.length > MAX_CAPTURE_BYTES) {
                throw new Failure(DroidUseContract.ERROR_RESOURCE_LIMIT,
                        DroidUseContract.RESOURCE_LIMIT);
            }
            SharedMemory shared = SharedMemory.create("droiduse-screenshot", encoded.length);
            try {
                ByteBuffer mapping = shared.mapReadWrite();
                try { mapping.put(encoded); }
                finally { SharedMemory.unmap(mapping); }
                if (!shared.setProtect(OsConstants.PROT_READ)) {
                    throw new IllegalStateException("SCREENSHOT_PROTECT_FAILED");
                }
                return shared.getFdDup();
            } finally {
                shared.close();
            }
        } finally {
            if (output != null && output != cropped) output.recycle();
            if (cropped != null && cropped != padded) cropped.recycle();
            padded.recycle();
        }
    }

    @Override public StreamHandle openStream(SessionHandle handle, StreamSpec spec) throws Exception {
        activeUnlocked(handle);
        throw unsupported("STREAMING_NOT_IMPLEMENTED");
    }

    @Override public void update(SessionHandle handle, SessionUpdate update) throws Exception {
        activeUnlocked(handle);
    }

    @Override public boolean stopAndDrain(SessionHandle handle) {
        return mActive == null || matches(handle);
    }

    @Override public boolean closeStreams(SessionHandle handle) {
        return mActive == null || matches(handle);
    }

    @Override public boolean releaseResources(SessionHandle handle) {
        if (!matches(handle)) return true;
        ActiveSession active = mActive;
        try {
            active.control.close();
            mActive = null;
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ActiveSession activeUnlocked(SessionHandle handle) throws Exception {
        ensureUnlocked();
        return active(handle);
    }

    private ActiveSession active(SessionHandle handle) throws Failure {
        ActiveSession active = mActive;
        if (active == null || active.epoch != handle.epoch
                || active.displayId != handle.displayId || active.remotelyClosed.get()) {
            throw new Failure(DroidUseContract.ERROR_STALE_SESSION,
                    DroidUseContract.STALE_SESSION);
        }
        return active;
    }

    private boolean matches(SessionHandle handle) {
        ActiveSession active = mActive;
        return active != null && active.epoch == handle.epoch
                && active.displayId == handle.displayId;
    }

    private void ensureUnlocked() throws Failure {
        if (isLocked()) {
            throw new Failure(DroidUseContract.ERROR_USER_NOT_UNLOCKED,
                    DroidUseContract.USER_NOT_UNLOCKED);
        }
    }

    private boolean isLocked() {
        return mKeyguard == null || mKeyguard.isDeviceLocked();
    }

    private static Failure unsupported(String code) {
        return new Failure(DroidUseContract.ERROR_UNSUPPORTED, code);
    }

    private static Failure invalid(String code) {
        return new Failure(DroidUseContract.ERROR_INVALID_REQUEST, code);
    }

    private static Failure staleTarget() {
        return invalid(DroidUseContract.STALE_TARGET);
    }

    private static CapabilityStatus status(int id, int availability, String reason) {
        CapabilityStatus result = new CapabilityStatus();
        result.capabilityId = id;
        result.availability = availability;
        result.reason = reason;
        return result;
    }

    private static void set(CapabilityStatus[] values, CapabilityStatus value) {
        values[value.capabilityId - DroidUseContract.CAP_APP_TASK_CONTROL] = value;
    }
}
