package com.android.server.droiduse;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;

import com.android.server.SystemService;

import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.CapabilitySnapshot;
import dev.droiduse.system.CapabilityStatus;
import dev.droiduse.system.DroidUseContract;
import dev.droiduse.system.DroidUseContractValidator;
import dev.droiduse.system.IDroidUseCallback;
import dev.droiduse.system.IDroidUseSystem;
import dev.droiduse.system.Observation;
import dev.droiduse.system.ObservationSpec;
import dev.droiduse.system.OperationAdmission;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.OperationResult;
import dev.droiduse.system.SessionEvent;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.SessionUpdate;
import dev.droiduse.system.StreamHandle;
import dev.droiduse.system.StreamSpec;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** ROM-owned authority. Model, OCR, ASR and TTS code must never run in this process. */
public final class DroidUseManagerService extends SystemService {
    public static final String SERVICE_NAME = "droiduse";
    private static final String EXECUTOR_PACKAGE = "dev.droiduse.executor";
    private static final String DEVICE_CONFIG_NAMESPACE = "droiduse";
    private static final File TRUST_FILE =
            new File("/system_ext/etc/droiduse/executor-cert.sha256");
    private static final File SEPOLICY_MARKER =
            new File("/system_ext/etc/droiduse/sepolicy-version");
    private static final long CONTROL_CALL_TIMEOUT_SECONDS = 10;
    private static final long PROTECTION_LIFETIME_MS = 30_000;
    private static final long PROTECTION_HEARTBEAT_MS = 10_000;

    private final Context mContext;
    private final HandlerThread mControlThread = new HandlerThread("DroidUseControl");
    private final AtomicLong mNextEpoch = new AtomicLong(1);
    private Handler mControl;
    private final DroidUsePlatform mPlatform;
    private byte[] mExpectedCertificate;
    private SessionRecord mCurrent;
    private boolean mEnabled = true;

    private static final class SessionRecord {
        final int ownerUid;
        final SessionSpec spec;
        final SessionHandle handle;
        final SystemSession state;
        final IBinder clientToken;
        final IDroidUseCallback callback;
        final IBinder.DeathRecipient deathRecipient;
        long protectionProof = 1;

        SessionRecord(int ownerUid, SessionSpec spec, SessionHandle handle,
                SystemSession state, IBinder clientToken, IDroidUseCallback callback,
                IBinder.DeathRecipient deathRecipient) {
            this.ownerUid = ownerUid;
            this.spec = spec;
            this.handle = handle;
            this.state = state;
            this.clientToken = clientToken;
            this.callback = callback;
            this.deathRecipient = deathRecipient;
        }
    }

    public DroidUseManagerService(Context context) {
        super(context);
        mContext = context;
        mPlatform = new RoutingDroidUsePlatform(context);
    }

    @Override public void onStart() {
        mExpectedCertificate = readCertificateDigest();
        mControlThread.start();
        mControl = new Handler(mControlThread.getLooper());
        publishBinderService(SERVICE_NAME, mBinderService);
    }

    @Override public void onBootPhase(int phase) {
        if (phase != PHASE_SYSTEM_SERVICES_READY) return;
        mEnabled = DeviceConfig.getBoolean(DEVICE_CONFIG_NAMESPACE, "enabled", true);
        DeviceConfig.addOnPropertiesChangedListener(DEVICE_CONFIG_NAMESPACE,
                command -> mControl.post(command), properties -> {
                    boolean enabled = properties.getBoolean("enabled", true);
                    mControl.post(() -> {
                        mEnabled = enabled;
                        if (!enabled && mCurrent != null) stopAndCleanup(mCurrent, DroidUseContract.DISABLED);
                        notifyCapabilitiesChanged();
                    });
                });
    }

    private final IDroidUseSystem.Stub mBinderService = new IDroidUseSystem.Stub() {
        @Override public CapabilitySnapshot getCapabilities() {
            authorizeCaller();
            return callOnControl(DroidUseManagerService.this::capabilities);
        }

        @Override public SessionHandle openSession(SessionSpec spec, IBinder clientToken,
                IDroidUseCallback callback) {
            int caller = authorizeCaller();
            return callOnControl(() -> openSessionOnControl(caller, spec, clientToken, callback));
        }

        @Override public Observation observe(SessionHandle handle, ObservationSpec spec) {
            int caller = authorizeCaller();
            return callOnControl(() -> {
                SessionRecord record = owned(caller, handle);
                try { DroidUseContractValidator.validateObservation(record.handle, spec); }
                catch (IllegalArgumentException e) { throw invalid(e); }
                if (!record.state.canAct(SystemClock.elapsedRealtime())) fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
                try { return mPlatform.observe(record.handle, spec); }
                catch (Exception e) { throw platformFailure(e); }
            });
        }

        @Override public OperationAdmission execute(SessionHandle handle, OperationRequest request) {
            int caller = authorizeCaller();
            return callOnControl(() -> executeOnControl(caller, handle, request));
        }

        @Override public StreamHandle openStream(SessionHandle handle, StreamSpec spec) {
            int caller = authorizeCaller();
            return callOnControl(() -> {
                SessionRecord record = owned(caller, handle);
                DroidUseContractValidator.validateStream(record.handle, spec);
                if (!record.state.canAct(SystemClock.elapsedRealtime())) fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
                try { return mPlatform.openStream(record.handle, spec); }
                catch (Exception e) { throw platformFailure(e); }
            });
        }

        @Override public void updateSession(SessionHandle handle, SessionUpdate update) {
            int caller = authorizeCaller();
            callOnControl(() -> { updateOnControl(caller, handle, update); return null; });
        }

        @Override public void closeSession(SessionHandle handle, int reason) {
            int caller = authorizeCaller();
            callOnControl(() -> {
                SessionRecord record = owned(caller, handle);
                stopAndCleanup(record, "CLIENT_CLOSE_" + reason);
                return null;
            });
        }

        @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
            if (!DumpUtilsBridge.checkDumpPermission(mContext, out)) return;
            callOnControl(() -> {
                dumpOnControl(out);
                return null;
            });
        }
    };

    private SessionHandle openSessionOnControl(int caller, SessionSpec spec, IBinder token,
            IDroidUseCallback callback) {
        if (!mEnabled) fail(DroidUseContract.ERROR_DISABLED, DroidUseContract.DISABLED);
        if (!capabilities().coreReady)
            fail(DroidUseContract.ERROR_UNSUPPORTED, "CORE_BACKEND_NOT_READY");
        UserManager users = mContext.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked(UserHandle.SYSTEM))
            fail(DroidUseContract.ERROR_USER_NOT_UNLOCKED, DroidUseContract.USER_NOT_UNLOCKED);
        try { DroidUseContractValidator.validateSessionSpec(spec); }
        catch (IllegalArgumentException e) { throw invalid(e); }
        if (mCurrent != null) fail(DroidUseContract.ERROR_BUSY, DroidUseContract.BUSY);
        if (token == null || callback == null || !token.isBinderAlive())
            fail(DroidUseContract.ERROR_INVALID_REQUEST, "CLIENT_DISCONNECTED");
        ensureCapabilitiesAvailable(spec.requestedCapabilities);

        long epoch = mNextEpoch.getAndIncrement();
        DroidUsePlatform.PreparedSession prepared;
        try { prepared = mPlatform.prepare(spec, epoch); }
        catch (Exception e) { throw platformFailure(e); }
        long now = SystemClock.elapsedRealtime();
        SessionHandle handle = new SessionHandle();
        handle.sessionId = UUID.randomUUID().toString();
        handle.epoch = epoch;
        handle.userId = spec.userId;
        handle.mode = spec.mode;
        handle.displayId = prepared.displayId;
        handle.openedAtElapsedRealtimeMs = now;
        SystemSession state = new SystemSession(handle.sessionId, epoch, caller, spec.userId,
                mode(spec.mode), prepared.displayId, toSet(spec.requestedCapabilities),
                now + spec.timeoutMs, 64);
        IBinder.DeathRecipient death = () -> mControl.post(() -> {
            if (mCurrent != null && mCurrent.handle.epoch == epoch)
                stopAndCleanup(mCurrent, "CLIENT_DIED");
        });
        try { token.linkToDeath(death, 0); }
        catch (RemoteException e) {
            mPlatform.releaseResources(handle);
            fail(DroidUseContract.ERROR_INVALID_REQUEST, "CLIENT_DISCONNECTED");
        }
        SessionRecord record = new SessionRecord(caller, spec, handle, state, token, callback, death);
        mCurrent = record;
        if (!state.protectionReady(epoch, 1, prepared.installedProtections,
                now, now + PROTECTION_LIFETIME_MS)) {
            stopAndCleanup(record, "PROTECTION_NOT_READY");
            fail(DroidUseContract.ERROR_UNKNOWN, "PROTECTION_NOT_READY");
        }
        if (!state.activate(now).contains(SystemSession.Effect.ACTIVATE_TARGET)) {
            stopAndCleanup(record, "ACTIVATION_REJECTED");
            fail(DroidUseContract.ERROR_UNKNOWN, "ACTIVATION_REJECTED");
        }
        try { mPlatform.activate(handle, spec); }
        catch (Exception e) {
            stopAndCleanup(record, "ACTIVATION_FAILED");
            throw platformFailure(e);
        }
        mControl.postDelayed(() -> refreshProtections(record), PROTECTION_HEARTBEAT_MS);
        sendEvent(record, DroidUseContract.EVENT_READY, DroidUseContract.OK, "READY");
        return handle;
    }

    private void refreshProtections(SessionRecord record) {
        if (mCurrent != record) return;
        long now = SystemClock.elapsedRealtime();
        if (!record.state.tick(now).isEmpty()) {
            stopAndCleanup(record, "SESSION_OR_PROTECTION_EXPIRED");
            return;
        }
        try {
            if (!record.state.protectionReady(record.handle.epoch, ++record.protectionProof,
                    mPlatform.installedProtections(record.handle), now,
                    now + PROTECTION_LIFETIME_MS)) {
                stopAndCleanup(record, "PROTECTION_REFRESH_REJECTED");
                return;
            }
        } catch (Exception e) {
            stopAndCleanup(record, "PROTECTION_REFRESH_FAILED");
            return;
        }
        mControl.postDelayed(() -> refreshProtections(record), PROTECTION_HEARTBEAT_MS);
    }

    private OperationAdmission executeOnControl(int caller, SessionHandle handle,
            OperationRequest request) {
        SessionRecord record = owned(caller, handle);
        try { DroidUseContractValidator.validateOperation(record.handle, request); }
        catch (IllegalArgumentException e) { throw invalid(e); }
        enforceTargetOwnership(record, request);
        SystemSession.Admission admission = record.state.admit(request.requestId, SystemClock.elapsedRealtime());
        if (admission != SystemSession.Admission.ACCEPTED) {
            OperationAdmission denied = new OperationAdmission();
            denied.sessionId = handle.sessionId;
            denied.requestId = request.requestId;
            denied.accepted = false;
            denied.code = admission.name();
            denied.message = admission.name();
            denied.acceptedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
            return denied;
        }
        try {
            OperationAdmission result = mPlatform.execute(record.handle, request,
                    completed -> mControl.post(() -> completeOperation(record, request.requestId, completed)));
            if (!result.accepted) record.state.complete(request.requestId);
            return result;
        }
        catch (Exception e) {
            record.state.complete(request.requestId);
            throw platformFailure(e);
        }
    }

    private void completeOperation(SessionRecord record, String requestId, OperationResult result) {
        if (mCurrent != record || result == null
                || !record.handle.sessionId.equals(result.sessionId)
                || !requestId.equals(result.requestId)
                || !record.state.complete(requestId)) return;
        try { record.callback.onOperationResult(result); }
        catch (RemoteException ignored) { stopAndCleanup(record, "CALLBACK_DIED"); }
    }

    private void updateOnControl(int caller, SessionHandle handle, SessionUpdate update) {
        SessionRecord record = owned(caller, handle);
        if (update == null)
            fail(DroidUseContract.ERROR_INVALID_REQUEST, "SESSION_UPDATE_REQUIRED");
        if (!handle.sessionId.equals(update.sessionId) || handle.epoch != update.epoch)
            fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
        long now = SystemClock.elapsedRealtime();
        if (update.kind == DroidUseContract.SESSION_UPDATE_PAUSE) {
            if (!record.state.pause(now)) fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
        } else if (update.kind == DroidUseContract.SESSION_UPDATE_RESUME) {
            if (!record.state.resume(now)) fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
        } else if (update.kind == DroidUseContract.SESSION_UPDATE_TARGET
                || update.kind == DroidUseContract.SESSION_UPDATE_LIMITS) {
            fail(DroidUseContract.ERROR_UNSUPPORTED, "SESSION_UPDATE_NOT_IMPLEMENTED");
        } else {
            fail(DroidUseContract.ERROR_INVALID_REQUEST, "UNKNOWN_SESSION_UPDATE");
        }
        try { mPlatform.update(record.handle, update); }
        catch (Exception e) { throw platformFailure(e); }
    }

    private SessionRecord owned(int caller, SessionHandle handle) {
        try { DroidUseContractValidator.validateHandle(handle); }
        catch (IllegalArgumentException e) { throw invalid(e); }
        SessionRecord record = mCurrent;
        if (record == null || caller != record.ownerUid
                || handle.epoch != record.handle.epoch
                || !handle.sessionId.equals(record.handle.sessionId))
            fail(DroidUseContract.ERROR_STALE_SESSION, DroidUseContract.STALE_SESSION);
        return record;
    }

    private static void enforceTargetOwnership(SessionRecord record, OperationRequest request) {
        String requestedTarget = null;
        if (request.app != null) requestedTarget = request.app.packageName;
        if (request.device != null && request.device.targetPackage != null
                && !request.device.targetPackage.isEmpty()) {
            requestedTarget = request.device.targetPackage;
        }
        if (requestedTarget == null || requestedTarget.isEmpty()) return;
        if (record.spec.targetPackage == null || record.spec.targetPackage.isEmpty()
                || !record.spec.targetPackage.equals(requestedTarget)) {
            fail(DroidUseContract.ERROR_INVALID_REQUEST, DroidUseContract.STALE_TARGET);
        }
    }

    private void stopAndCleanup(SessionRecord record, String reason) {
        record.state.stop(reason);
        if (record.state.getState() == SystemSession.State.STOPPING) {
            boolean targets = platformStep(() -> mPlatform.stopAndDrain(record.handle));
            record.state.requestsDrained(record.handle.epoch, targets);
            record.state.targetsStopped(record.handle.epoch, targets);
        }
        if (record.state.getState() == SystemSession.State.DRAINING) {
            record.state.streamsClosed(record.handle.epoch,
                    platformStep(() -> mPlatform.closeStreams(record.handle)));
        }
        if (record.state.getState() == SystemSession.State.RELEASING) {
            record.state.resourcesReleased(record.handle.epoch,
                    platformStep(() -> mPlatform.releaseResources(record.handle)));
        }
        if (record.state.getState() == SystemSession.State.CLOSED) {
            record.clientToken.unlinkToDeath(record.deathRecipient, 0);
            sendEvent(record, DroidUseContract.EVENT_CLOSED, DroidUseContract.CANCELLED, reason);
            if (mCurrent == record) mCurrent = null;
        } else {
            sendEvent(record, DroidUseContract.EVENT_STOPPING, DroidUseContract.UNKNOWN_OUTCOME,
                    "CLEANUP_PENDING");
            mControl.postDelayed(() -> {
                if (mCurrent == record) stopAndCleanup(record, "CLEANUP_RETRY");
            }, 1_000);
        }
    }

    private static boolean platformStep(java.util.function.Supplier<Boolean> step) {
        try { return Boolean.TRUE.equals(step.get()); }
        catch (RuntimeException ignored) { return false; }
    }

    private CapabilitySnapshot capabilities() {
        CapabilityStatus[] platform = mPlatform.capabilities();
        CapabilityStatus[] result = new CapabilityStatus[31];
        for (int capability = 1; capability <= 31; capability++)
            result[capability - 1] = status(capability,
                    DroidUseContract.AVAILABILITY_UNAVAILABLE, "NOT_IMPLEMENTED");
        set(result, status(1, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(2, mExpectedCertificate == null
                ? DroidUseContract.AVAILABILITY_UNAVAILABLE : DroidUseContract.AVAILABILITY_AVAILABLE,
                mExpectedCertificate == null ? "TRUST_CONFIG_MISSING" : ""));
        set(result, status(3, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        for (CapabilityStatus value : platform) set(result, value);
        set(result, status(24, DroidUseContract.AVAILABILITY_DEGRADED, "PRIMARY_USER_ONLY"));
        set(result, status(25, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(26, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(27, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(28, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        set(result, status(29, DroidUseContract.AVAILABILITY_AVAILABLE, ""));
        boolean sepolicy = SELinux.isSELinuxEnforced() && SEPOLICY_MARKER.isFile();
        set(result, status(30, sepolicy ? DroidUseContract.AVAILABILITY_AVAILABLE
                : DroidUseContract.AVAILABILITY_UNAVAILABLE, sepolicy ? "" : "SEPOLICY_NOT_VERIFIED"));
        set(result, status(31, mExpectedCertificate == null
                ? DroidUseContract.AVAILABILITY_UNAVAILABLE : DroidUseContract.AVAILABILITY_AVAILABLE,
                mExpectedCertificate == null ? "TRUST_CONFIG_MISSING" : ""));
        CapabilitySnapshot snapshot = new CapabilitySnapshot();
        snapshot.interfaceVersion = DroidUseContract.INTERFACE_VERSION;
        snapshot.coreReady = mEnabled && mExpectedCertificate != null && sepolicy && mPlatform.coreReady();
        snapshot.backend = "ROM_SYSTEM_V1";
        snapshot.generatedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
        snapshot.capabilities = result;
        return snapshot;
    }

    private int authorizeCaller() {
        int uid = Binder.getCallingUid();
        if (uid < Process.FIRST_APPLICATION_UID || UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM
                || mExpectedCertificate == null) throw new SecurityException("DroidUse caller not trusted");
        PackageManager packages = mContext.getPackageManager();
        String[] names = packages.getPackagesForUid(uid);
        if (names == null || !Arrays.asList(names).contains(EXECUTOR_PACKAGE))
            throw new SecurityException("DroidUse caller package mismatch");
        try {
            PackageInfo info = packages.getPackageInfoAsUser(EXECUTOR_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES, UserHandle.USER_SYSTEM);
            if (info.applicationInfo == null || info.applicationInfo.uid != uid
                    || info.signingInfo == null) throw new SecurityException("DroidUse caller identity missing");
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers.length != 1 || !MessageDigest.isEqual(mExpectedCertificate,
                    MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())))
                throw new SecurityException("DroidUse caller certificate mismatch");
            return uid;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("DroidUse caller verification failed", e);
        }
    }

    private byte[] readCertificateDigest() {
        try {
            String value = Files.readString(TRUST_FILE.toPath()).trim().toLowerCase(Locale.ROOT);
            if (!value.matches("[0-9a-f]{64}")) return null;
            byte[] result = new byte[32];
            for (int i = 0; i < result.length; i++)
                result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            return result;
        } catch (Exception ignored) { return null; }
    }

    private void ensureCapabilitiesAvailable(int[] requested) {
        CapabilitySnapshot snapshot = capabilities();
        for (int id : requested) {
            CapabilityStatus status = snapshot.capabilities[id - 1];
            if (status.availability != DroidUseContract.AVAILABILITY_AVAILABLE
                    && status.availability != DroidUseContract.AVAILABILITY_DEGRADED)
                fail(DroidUseContract.ERROR_UNSUPPORTED, "CAPABILITY_" + id + "_" + status.reason);
        }
    }

    private void notifyCapabilitiesChanged() {
        SessionRecord record = mCurrent;
        if (record == null) return;
        try { record.callback.onCapabilitiesChanged(capabilities()); }
        catch (RemoteException ignored) { stopAndCleanup(record, "CALLBACK_DIED"); }
    }

    private void sendEvent(SessionRecord record, int kind, String code, String message) {
        SessionEvent event = new SessionEvent();
        event.sessionId = record.handle.sessionId;
        event.epoch = record.handle.epoch;
        event.kind = kind;
        event.code = code;
        event.message = message;
        event.atElapsedRealtimeMs = SystemClock.elapsedRealtime();
        try { record.callback.onSessionEvent(event); }
        catch (RemoteException ignored) { /* client death recipient owns cleanup */ }
    }

    private void dumpOnControl(PrintWriter out) {
        CapabilitySnapshot value = capabilities();
        out.println("DroidUseManagerService:");
        out.println("  interfaceVersion=" + value.interfaceVersion);
        out.println("  enabled=" + mEnabled);
        out.println("  coreReady=" + value.coreReady);
        out.println("  backend=" + value.backend);
        out.println("  activeSession=" + (mCurrent == null ? "none" : mCurrent.handle.sessionId));
        if (mCurrent != null) {
            out.println("  mode=" + mCurrent.handle.mode);
            out.println("  displayId=" + mCurrent.handle.displayId);
            out.println("  state=" + mCurrent.state.getState());
            out.println("  pendingRequests=" + mCurrent.state.pendingRequestCount());
        }
        for (CapabilityStatus status : value.capabilities)
            out.println("  capability[" + status.capabilityId + "]="
                    + status.availability + (status.reason.isEmpty() ? "" : " " + status.reason));
    }

    private <T> T callOnControl(java.util.concurrent.Callable<T> task) {
        if (mControl == null) fail(DroidUseContract.ERROR_UNKNOWN, "SERVICE_NOT_STARTED");
        if (Thread.currentThread() == mControl.getLooper().getThread()) {
            try { return task.call(); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        mControl.post(() -> {
            try { result.complete(task.call()); }
            catch (Throwable t) { result.completeExceptionally(t); }
        });
        try { return result.get(CONTROL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } catch (Exception e) {
            throw new ServiceSpecificException(DroidUseContract.ERROR_TIMEOUT, DroidUseContract.TIMEOUT);
        }
    }

    private static SystemSession.Mode mode(int value) {
        return switch (value) {
            case DroidUseContract.MODE_ISOLATED_DISPLAY -> SystemSession.Mode.ISOLATED_DISPLAY;
            case DroidUseContract.MODE_PHYSICAL_CONTROL -> SystemSession.Mode.PHYSICAL_CONTROL;
            case DroidUseContract.MODE_CALL_ASSIST -> SystemSession.Mode.CALL_ASSIST;
            case DroidUseContract.MODE_MEDIA_OBSERVE -> SystemSession.Mode.MEDIA_OBSERVE;
            default -> throw new IllegalArgumentException("Invalid mode");
        };
    }

    private static java.util.Set<Integer> toSet(int[] values) {
        java.util.HashSet<Integer> result = new java.util.HashSet<>();
        for (int value : values) result.add(value);
        return result;
    }

    private static CapabilityStatus status(int id, int availability, String reason) {
        CapabilityStatus result = new CapabilityStatus();
        result.capabilityId = id;
        result.availability = availability;
        result.reason = reason;
        return result;
    }

    private static void set(CapabilityStatus[] values, CapabilityStatus value) {
        values[value.capabilityId - 1] = value;
    }

    private static ServiceSpecificException invalid(IllegalArgumentException error) {
        int code = DroidUseContract.UNSUPPORTED_USER.equals(error.getMessage())
                ? DroidUseContract.ERROR_UNSUPPORTED_USER : DroidUseContract.ERROR_INVALID_REQUEST;
        return new ServiceSpecificException(code, error.getMessage());
    }

    private static ServiceSpecificException platformFailure(Exception error) {
        if (error instanceof ServiceSpecificException service) return service;
        if (error instanceof DroidUsePlatform.Failure failure)
            return new ServiceSpecificException(failure.errorCode, failure.clientCode);
        if (error instanceof UnsupportedOperationException)
            return new ServiceSpecificException(DroidUseContract.ERROR_UNSUPPORTED, error.getMessage());
        return new ServiceSpecificException(DroidUseContract.ERROR_UNKNOWN,
                error.getClass().getSimpleName());
    }

    private static void fail(int code, String message) { throw new ServiceSpecificException(code, message); }
}
