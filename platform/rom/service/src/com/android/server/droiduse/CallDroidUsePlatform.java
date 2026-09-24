package com.android.server.droiduse;

import static dev.droiduse.system.DroidUseContract.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.*;
import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.*;
import java.util.Set;

/** Explicit, platform-signed ROM companion; no model or media processing in system_server. */
final class CallDroidUsePlatform implements DroidUsePlatform {
    private static final String PACKAGE = "dev.droiduse.telecom";
    private static final Set<SystemSession.Protection> PROTECTIONS = Set.of(
            SystemSession.Protection.TELECOM_ROUTE, SystemSession.Protection.AUDIO_ROUTE,
            SystemSession.Protection.MICROPHONE_ROUTE, SystemSession.Protection.STREAM_LIMITS);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder owner = new Binder();
    private volatile IDroidUseTelecom bridge;
    private IDroidUseTelecom sessionBridge;
    private long epoch;
    private boolean binding;
    CallDroidUsePlatform(Context context) { this.context = context; main.post(this::bind); }
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { bridge = IDroidUseTelecom.Stub.asInterface(binder); }
        public void onServiceDisconnected(ComponentName name) { bridge = null; }
        public void onBindingDied(ComponentName name) { reconnect(); }
        public void onNullBinding(ComponentName name) { reconnect(); }
        private void reconnect() {
            bridge = null;
            if (binding) { context.unbindService(this); binding = false; }
            main.postDelayed(CallDroidUsePlatform.this::bind, 5000);
        }
    };
    private void bind() {
        if (binding) return;
        try {
            PackageManager packages = context.getPackageManager();
            ApplicationInfo app = packages.getApplicationInfo(PACKAGE, 0);
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || packages.checkSignatures("android", PACKAGE) != PackageManager.SIGNATURE_MATCH) return;
            binding = context.bindServiceAsUser(new Intent().setComponent(new ComponentName(PACKAGE,
                    PACKAGE + ".CallControlService")), connection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
        } catch (Exception ignored) { }
        if (!binding) main.postDelayed(this::bind, 5000);
    }
    public CapabilityStatus[] capabilities() {
        IDroidUseTelecom current = bridge;
        if (current != null) try { return current.getCapabilities(); } catch (RemoteException | RuntimeException ignored) { }
        CapabilityStatus[] values = new CapabilityStatus[4];
        for (int i = 0; i < values.length; i++) {
            values[i] = new CapabilityStatus(); values[i].capabilityId = CAP_TELECOM + i;
            values[i].availability = AVAILABILITY_UNAVAILABLE; values[i].reason = "TELECOM_BRIDGE_UNAVAILABLE";
        }
        return values;
    }
    public boolean coreReady() { return bridge != null && bridge.asBinder().isBinderAlive(); }
    public PreparedSession prepare(SessionSpec spec, long epoch) throws Exception {
        if (!coreReady()) throw new Failure(ERROR_UNSUPPORTED, "TELECOM_BRIDGE_UNAVAILABLE");
        CallPolicy.session(spec); this.epoch = epoch; sessionBridge = bridge;
        return new PreparedSession(-1, PROTECTIONS);
    }
    private IDroidUseTelecom backend(SessionHandle h) throws Failure {
        if (epoch != h.epoch || sessionBridge == null || !sessionBridge.asBinder().isBinderAlive())
            throw new Failure(ERROR_STALE_SESSION, STALE_SESSION);
        return sessionBridge;
    }
    public void activate(SessionHandle h, SessionSpec s) throws Exception { backend(h).open(h, s, owner); }
    public Set<SystemSession.Protection> installedProtections(SessionHandle h) throws Exception {
        return backend(h).healthy(h) ? PROTECTIONS : Set.of();
    }
    public Observation observe(SessionHandle h, ObservationSpec s) throws Exception {
        if (s.includeCapture || s.includeSemantics) throw new Failure(ERROR_UNSUPPORTED, "CALL_MODE_HAS_NO_DISPLAY");
        return backend(h).observe(h);
    }
    public OperationAdmission execute(SessionHandle h, OperationRequest r, Completion completed) throws Exception {
        backend(h).execute(h, r);
        OperationAdmission result = new OperationAdmission(); result.sessionId = h.sessionId;
        result.requestId = r.requestId; result.accepted = true; result.code = OK;
        result.message = "TELECOM_REQUEST_DISPATCHED"; result.acceptedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
        OperationResult receipt = new OperationResult(); receipt.sessionId = h.sessionId; receipt.requestId = r.requestId;
        receipt.code = OK; receipt.message = "TELECOM_REQUEST_DISPATCHED_REOBSERVE_REQUIRED";
        receipt.completedAtElapsedRealtimeMs = result.acceptedAtElapsedRealtimeMs;
        completed.complete(receipt); return result;
    }
    public StreamHandle openStream(SessionHandle h, StreamSpec s) throws Exception { return backend(h).openStream(h, s); }
    public void update(SessionHandle h, SessionUpdate s) throws Exception {
        backend(h).pause(h, s.kind == SESSION_UPDATE_PAUSE);
    }
    public boolean stopAndDrain(SessionHandle h) { return true; } // Binder dispatch is synchronous on the control lane.
    public boolean closeStreams(SessionHandle h) {
        // A dead companion process has already lost its kernel FDs and audio clients.
        if (sessionBridge == null || !sessionBridge.asBinder().isBinderAlive()) return true;
        try { return backend(h).close(h); } catch (Exception e) { return false; }
    }
    public boolean releaseResources(SessionHandle h) {
        if (!closeStreams(h)) return false;
        epoch = 0; sessionBridge = null; return true;
    }
}
