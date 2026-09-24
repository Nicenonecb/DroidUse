package dev.droiduse.telecom;

import static dev.droiduse.system.DroidUseContract.*;
import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.telecom.*;
import android.telephony.TelephonyManager;
import dev.droiduse.system.*;
import java.util.*;

/** Main-looper owned state. One session may assist exactly one ordinary cellular call. */
final class CallController {
    private static CallController instance;
    static CallController get(Context context) {
        if (instance == null) instance = new CallController(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Call, Call.Callback> calls = new LinkedHashMap<>();
    private final CallObservationGate gate = new CallObservationGate();
    private final CallAudioStreams streams;
    private InCallService service;
    private CallAudioState audio;
    private SessionHandle session;
    private SessionSpec options;
    private Call bound;
    private String callId;
    private boolean paused;
    private boolean dialSent;
    private long deadline;
    private long dialDeadline;
    private IBinder owner;
    private final IBinder.DeathRecipient death = () -> main.post(this::shutdown);

    private CallController(Context context) {
        this.context = context;
        streams = new CallAudioStreams(context);
    }
    private long now() { return SystemClock.elapsedRealtime(); }
    private void check(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }
    private boolean unlocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isDeviceLocked()
                && context.getSystemService(UserManager.class).isUserUnlocked();
    }
    void added(InCallService source, Call call) {
        service = source;
        audio = source.getCallAudioState();
        Call.Callback callback = new Call.Callback() {
            @Override public void onStateChanged(Call c, int state) { changed(); }
            @Override public void onDetailsChanged(Call c, Call.Details d) { changed(); }
            @Override public void onParentChanged(Call c, Call parent) { changed(); }
            @Override public void onChildrenChanged(Call c, List<Call> children) { changed(); }
            @Override public void onCallDestroyed(Call c) { removed(c); }
        };
        calls.put(call, callback); call.registerCallback(callback, main); changed();
    }
    void removed(Call call) {
        Call.Callback callback = calls.remove(call);
        if (callback != null) call.unregisterCallback(callback);
        changed();
    }
    void disconnected() {
        for (Map.Entry<Call, Call.Callback> item : calls.entrySet()) item.getKey().unregisterCallback(item.getValue());
        calls.clear(); service = null; audio = null; changed();
    }
    void audioChanged(CallAudioState value) {
        if (!Objects.equals(audio, value)) { audio = value; changed(); }
    }
    private void changed() {
        gate.invalidate(); streams.close();
        // A new call, route, mute or call state must never inherit a previous audio stream.
        if (session != null && !safeCallSet()) shutdown();
    }
    private boolean eligible(Call call) {
        Call.Details details = call.getDetails();
        if (details == null || details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
                || details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
                || details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)
                || call.getParent() != null || !call.getChildren().isEmpty()) return false;
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (details.getAccountHandle() == null
                || !UserHandle.SYSTEM.equals(details.getAccountHandle().getUserHandle())) return false;
        PhoneAccount account = telecom.getPhoneAccount(details.getAccountHandle());
        if (account == null || !account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) return false;
        Uri address = details.getHandle();
        if (address == null || !"tel".equals(address.getScheme())) return false;
        try { return !context.getSystemService(TelephonyManager.class).isEmergencyNumber(address.getSchemeSpecificPart()); }
        catch (RuntimeException e) { return false; }
    }
    private boolean safeCallSet() {
        if (calls.size() > 1 || calls.keySet().stream().anyMatch(c -> !eligible(c))) return false;
        if (bound != null) return calls.containsKey(bound)
                && bound.getState() != Call.STATE_DISCONNECTED && bound.getState() != Call.STATE_DISCONNECTING;
        return true;
    }
    CapabilityStatus[] capabilities() {
        boolean intercept = false;
        try { intercept = context.getSystemService(AudioManager.class).isPstnCallAudioInterceptable(); }
        catch (RuntimeException ignored) { }
        return new CapabilityStatus[] {
            status(CAP_TELECOM, AVAILABILITY_DEGRADED, "SINGLE_CELLULAR_CALL"),
            status(CAP_CALL_AUDIO_CAPTURE, intercept ? AVAILABILITY_DEGRADED : AVAILABILITY_UNAVAILABLE,
                    intercept ? "CALL_SOURCES_REQUIRE_DEVICE_PROBE" : "PSTN_INTERCEPTION_UNAVAILABLE"),
            status(CAP_CALL_TTS_INJECTION, intercept ? AVAILABILITY_DEGRADED : AVAILABILITY_UNAVAILABLE,
                    intercept ? "PCM_UPLINK_RUNTIME_CHECK_REQUIRED" : "PSTN_INTERCEPTION_UNAVAILABLE"),
            status(CAP_PRIVATE_CALL_COMMAND, AVAILABILITY_UNAVAILABLE, "PRIVATE_MIC_ROUTE_NOT_VERIFIED")
        };
    }
    private CapabilityStatus status(int id, int availability, String reason) {
        CapabilityStatus result = new CapabilityStatus(); result.capabilityId = id;
        result.availability = availability; result.reason = reason; return result;
    }
    void open(SessionHandle handle, SessionSpec spec, IBinder token) throws RemoteException {
        DroidUseContractValidator.validateSessionSpec(spec);
        check(session == null && streams.drained(), BUSY); check(unlocked(), USER_NOT_UNLOCKED);
        check(safeCallSet(), "UNSUPPORTED_CALL_SET");
        check(token != null && token.isBinderAlive(), "OWNER_DIED");
        if (spec.callAddress != null && !spec.callAddress.isEmpty()) {
            check(calls.isEmpty(), "DIAL_REQUIRES_IDLE_PHONE");
            check(!context.getSystemService(TelephonyManager.class).isEmergencyNumber(spec.callAddress), "EMERGENCY_CALL_NOT_SUPPORTED");
        }
        owner = token; owner.linkToDeath(death, 0);
        session = handle; options = spec; paused = false; dialSent = false;
        bound = null; callId = null; deadline = now() + spec.timeoutMs; gate.invalidate();
        main.post(watchdog);
    }
    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (session == null) return;
            if (!unlocked() || now() >= deadline || !safeCallSet() || !owner.isBinderAlive()
                    || dialSent && bound == null && now() >= dialDeadline) { shutdown(); return; }
            main.postDelayed(this, 250);
        }
    };
    private void owned(SessionHandle handle) {
        check(session != null && session.epoch == handle.epoch && session.sessionId.equals(handle.sessionId), STALE_SESSION);
        check(healthy(handle), "CALL_SESSION_LOST");
    }
    boolean healthy(SessionHandle handle) {
        return session != null && session.epoch == handle.epoch && session.sessionId.equals(handle.sessionId)
                && now() < deadline && unlocked() && safeCallSet() && owner.isBinderAlive();
    }
    Observation observe(SessionHandle handle) {
        owned(handle); check(!paused, "PAUSED");
        if (bound == null && calls.size() == 1) {
            Call candidate = calls.keySet().iterator().next();
            if (dialSent) {
                Call.Details details = candidate.getDetails();
                check(details.getCallDirection() == Call.Details.DIRECTION_OUTGOING
                        && details.getHandle() != null && options.callAddress.equals(details.getHandle().getSchemeSpecificPart()),
                        "DIALED_CALL_IDENTITY_MISMATCH");
            } else check(options.callAddress == null || options.callAddress.isEmpty(), "DIAL_NOT_SENT");
            bound = candidate; callId = UUID.randomUUID().toString();
        }
        Observation result = new Observation(); result.sessionId = session.sessionId; result.epoch = session.epoch;
        result.displayId = -1; result.taskId = -1; result.packageName = ""; result.captureMimeType = "";
        result.capturedAtElapsedRealtimeMs = now(); result.frameId = gate.observe(now());
        result.windowGeneration = gate.generation(); result.semantics = new SemanticNode[0];
        result.scopedActions = bound == null && !dialSent && options.callAddress != null && !options.callAddress.isEmpty()
                ? new String[] {"telecom_dial"} : new String[0];
        if (bound == null) result.calls = new CallSnapshot[0];
        else {
            CallSnapshot snapshot = new CallSnapshot(); snapshot.callId = callId; snapshot.state = bound.getState();
            snapshot.operations = operations(bound); snapshot.muted = audio != null && audio.isMuted();
            snapshot.audioRoute = audio == null ? 0 : audio.getRoute();
            snapshot.supportedAudioRoutes = audio == null ? 0 : audio.getSupportedRouteMask();
            result.calls = new CallSnapshot[] {snapshot};
        }
        return result;
    }
    private int[] operations(Call call) {
        List<Integer> values = new ArrayList<>();
        int state = call.getState();
        if (state == Call.STATE_RINGING) values.add(TELECOM_ANSWER);
        if (state == Call.STATE_ACTIVE || state == Call.STATE_RINGING || state == Call.STATE_HOLDING
                || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) values.add(TELECOM_END);
        if (call.getDetails().can(Call.Details.CAPABILITY_HOLD)) {
            if (state == Call.STATE_ACTIVE) values.add(TELECOM_HOLD);
            if (state == Call.STATE_HOLDING) values.add(TELECOM_UNHOLD);
        }
        if (state == Call.STATE_ACTIVE && audio != null) {
            values.add(TELECOM_SET_MUTED); values.add(TELECOM_SET_AUDIO_ROUTE); values.add(TELECOM_DTMF);
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
    void execute(SessionHandle handle, OperationRequest request) {
        owned(handle); check(!paused, "PAUSED");
        DroidUseContractValidator.validateOperation(handle, request);
        gate.consume(request.expectedFrameId, request.expectedWindowGeneration, now());
        TelecomOperation op = request.telecom;
        if (op.kind == TELECOM_DIAL) {
            check(!dialSent && bound == null && calls.isEmpty() && op.address.equals(options.callAddress), "DIAL_NOT_AUTHORIZED");
            check(!context.getSystemService(TelephonyManager.class).isEmergencyNumber(op.address), "EMERGENCY_CALL_NOT_SUPPORTED");
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            PhoneAccountHandle account = telecom.getDefaultOutgoingPhoneAccount("tel");
            PhoneAccount info = account == null ? null : telecom.getPhoneAccount(account);
            check(info != null && UserHandle.SYSTEM.equals(account.getUserHandle())
                    && info.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION), "SELECT_DEFAULT_SIM_FIRST");
            Bundle extras = new Bundle(); extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
            dialSent = true; dialDeadline = now() + 30_000;
            telecom.placeCall(Uri.fromParts("tel", op.address, null), extras); return;
        }
        check(bound != null && callId.equals(op.callId), STALE_TARGET);
        check(Arrays.stream(operations(bound)).anyMatch(kind -> kind == op.kind), "CALL_OPERATION_UNAVAILABLE");
        streams.close();
        switch (op.kind) {
            case TELECOM_ANSWER -> bound.answer(VideoProfile.STATE_AUDIO_ONLY);
            case TELECOM_END -> bound.disconnect();
            case TELECOM_HOLD -> bound.hold();
            case TELECOM_UNHOLD -> bound.unhold();
            case TELECOM_SET_MUTED -> service.setMuted(op.enabled);
            case TELECOM_SET_AUDIO_ROUTE -> {
                check((audio.getSupportedRouteMask() & op.audioRoute) != 0, AUDIO_ROUTE_UNAVAILABLE);
                service.setAudioRoute(op.audioRoute);
            }
            case TELECOM_DTMF -> {
                Call target = bound; target.playDtmfTone(op.digits.charAt(0));
                main.postDelayed(target::stopDtmfTone, 150);
            }
            default -> throw new IllegalArgumentException("INVALID_TELECOM_OPERATION");
        }
    }
    StreamHandle stream(SessionHandle handle, StreamSpec spec) throws Exception {
        owned(handle); check(!paused && options.allowCallAudio, "CALL_AUDIO_NOT_ALLOWED");
        DroidUseContractValidator.validateStream(handle, spec);
        check(bound != null && callId.equals(spec.callId) && bound.getState() == Call.STATE_ACTIVE, STALE_TARGET);
        int cap = spec.kind == STREAM_CALL_INJECTION ? CAP_CALL_TTS_INJECTION : CAP_CALL_AUDIO_CAPTURE;
        check(Arrays.stream(options.requestedCapabilities).anyMatch(value -> value == cap), "CAPABILITY_NOT_REQUESTED");
        gate.consume(spec.expectedFrameId, spec.expectedCallGeneration, now());
        return streams.open(handle, spec);
    }
    void pause(SessionHandle handle, boolean value) { owned(handle); paused = value; gate.invalidate(); streams.close(); }
    boolean close(SessionHandle handle) {
        if (session == null) return streams.drained();
        check(session.epoch == handle.epoch && session.sessionId.equals(handle.sessionId), STALE_SESSION); shutdown();
        return streams.drained();
    }
    void shutdown() {
        streams.close(); gate.invalidate(); main.removeCallbacks(watchdog);
        if (bound != null) bound.stopDtmfTone();
        if (owner != null) owner.unlinkToDeath(death, 0);
        session = null; options = null; owner = null; bound = null; callId = null;
        // Closing assistance leaves the user's telephone call connected and current mute unchanged.
    }
}
