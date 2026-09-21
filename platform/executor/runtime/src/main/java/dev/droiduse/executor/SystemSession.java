package dev.droiduse.executor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authoritative state machine for one ROM-owned privileged session.
 *
 * Android adapters supply protection proofs and execute effects. This class never trusts a
 * display, target, call, window or stream identifier supplied by the model. Call every method
 * from one serialized system-server control lane.
 */
public final class SystemSession {
    private static final long MAX_PROTECTION_LIFETIME_MS = 30_000L;
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public enum Mode { ISOLATED_DISPLAY, PHYSICAL_CONTROL, CALL_ASSIST, MEDIA_OBSERVE }
    public enum State { PREPARING, READY, ACTIVE, PAUSED, STOPPING, DRAINING, RELEASING, CLOSED }
    public enum Protection {
        TARGET_OWNERSHIP,
        DISPLAY_ROUTE,
        INPUT_ROUTE,
        AUDIO_ROUTE,
        MICROPHONE_ROUTE,
        CAMERA_ROUTE,
        TELECOM_ROUTE,
        STREAM_LIMITS,
    }
    public enum Effect {
        ACTIVATE_TARGET,
        REVOKE_ADMISSION,
        STOP_TARGETS,
        CLOSE_STREAMS,
        RELEASE_RESOURCES,
    }
    public enum Admission { ACCEPTED, DUPLICATE, INVALID, NOT_READY, LIMIT_REACHED }

    private final String id;
    private final long epoch;
    private final int ownerUid;
    private final int userId;
    private final Mode mode;
    private final int displayId;
    private final Set<Integer> requestedCapabilities;
    private final long sessionDeadline;
    private final int maxPendingRequests;
    private State state = State.PREPARING;
    private String stopReason;
    private long validUntil = Long.MIN_VALUE;
    private long acceptedProof = -1L;
    private final Set<String> pendingRequests = new LinkedHashSet<>();
    private final Set<String> seenRequests = new LinkedHashSet<>();

    public SystemSession(String id, long epoch, int ownerUid, int userId, Mode mode,
            int displayId, Set<Integer> requestedCapabilities, long sessionDeadline,
            int maxPendingRequests) {
        require(id != null && OPAQUE_ID.matcher(id).matches(), "Invalid session id");
        require(epoch > 0 && ownerUid >= 10_000 && userId == ownerUid / 100_000,
                "Invalid owner");
        require(userId == 0, "Only the primary user is implemented");
        require(mode != null, "Mode required");
        require(requestedCapabilities != null && !requestedCapabilities.isEmpty()
                && requestedCapabilities.stream().allMatch(value -> value >= 1 && value <= 31),
                "Invalid capabilities");
        require(sessionDeadline > 0 && maxPendingRequests >= 1 && maxPendingRequests <= 256,
                "Invalid limits");
        switch (mode) {
            case ISOLATED_DISPLAY -> require(displayId > 0, "Isolated display must not be primary");
            case PHYSICAL_CONTROL -> require(displayId == 0, "Physical control requires primary display");
            case CALL_ASSIST, MEDIA_OBSERVE -> require(displayId >= -1, "Invalid optional display");
        }
        this.id = id;
        this.epoch = epoch;
        this.ownerUid = ownerUid;
        this.userId = userId;
        this.mode = mode;
        this.displayId = displayId;
        this.requestedCapabilities = Set.copyOf(requestedCapabilities);
        this.sessionDeadline = sessionDeadline;
        this.maxPendingRequests = maxPendingRequests;
    }

    public String getId() { return id; }
    public long getEpoch() { return epoch; }
    public int getOwnerUid() { return ownerUid; }
    public int getUserId() { return userId; }
    public Mode getMode() { return mode; }
    public int getDisplayId() { return displayId; }
    public Set<Integer> getRequestedCapabilities() { return requestedCapabilities; }
    public State getState() { return state; }
    public String getStopReason() { return stopReason; }

    public Set<Protection> requiredProtections() {
        return switch (mode) {
            case ISOLATED_DISPLAY -> Set.of(
                    Protection.TARGET_OWNERSHIP,
                    Protection.DISPLAY_ROUTE,
                    Protection.INPUT_ROUTE,
                    Protection.AUDIO_ROUTE,
                    Protection.MICROPHONE_ROUTE,
                    Protection.CAMERA_ROUTE,
                    Protection.STREAM_LIMITS);
            case PHYSICAL_CONTROL -> Set.of(
                    Protection.TARGET_OWNERSHIP,
                    Protection.DISPLAY_ROUTE,
                    Protection.INPUT_ROUTE,
                    Protection.STREAM_LIMITS);
            case CALL_ASSIST -> Set.of(
                    Protection.TELECOM_ROUTE,
                    Protection.AUDIO_ROUTE,
                    Protection.MICROPHONE_ROUTE,
                    Protection.STREAM_LIMITS);
            case MEDIA_OBSERVE -> Set.of(
                    Protection.AUDIO_ROUTE,
                    Protection.STREAM_LIMITS);
        };
    }

    /** A proof is accepted only while the previous proof is still valid. */
    public boolean protectionReady(long eventEpoch, long proof, Set<Protection> installed,
            long now, long expiresAt) {
        if (eventEpoch != epoch || !Set.of(State.PREPARING, State.READY, State.ACTIVE, State.PAUSED)
                .contains(state)) return false;
        if (proof <= acceptedProof || expiresAt <= now
                || expiresAt - now > MAX_PROTECTION_LIFETIME_MS) return false;
        if (state != State.PREPARING && now >= validUntil) return false;
        if (installed == null || !installed.containsAll(requiredProtections())) return false;
        acceptedProof = proof;
        validUntil = expiresAt;
        if (state == State.PREPARING) state = State.READY;
        return true;
    }

    public List<Effect> activate(long now) {
        if (state != State.READY) return Collections.emptyList();
        if (!protectionValid(now)) return stop("protection expired before activation");
        state = State.ACTIVE;
        return List.of(Effect.ACTIVATE_TARGET);
    }

    public boolean pause(long now) {
        if (state != State.ACTIVE || !protectionValid(now)) return false;
        state = State.PAUSED;
        return true;
    }

    public boolean resume(long now) {
        if (state != State.PAUSED || !protectionValid(now)) return false;
        state = State.ACTIVE;
        return true;
    }

    public Admission admit(String requestId, long now) {
        if (requestId == null || !OPAQUE_ID.matcher(requestId).matches()) return Admission.INVALID;
        if (seenRequests.contains(requestId)) return Admission.DUPLICATE;
        if (!canAct(now)) return Admission.NOT_READY;
        if (pendingRequests.size() >= maxPendingRequests) return Admission.LIMIT_REACHED;
        // Record before dispatch. A timeout may mean the operation happened; never auto-retry.
        seenRequests.add(requestId);
        pendingRequests.add(requestId);
        return Admission.ACCEPTED;
    }

    public boolean complete(String requestId) { return pendingRequests.remove(requestId); }

    public boolean canAct(long now) { return state == State.ACTIVE && protectionValid(now); }

    public boolean protectionValid(long now) {
        return Set.of(State.READY, State.ACTIVE, State.PAUSED).contains(state)
                && now < validUntil && now < sessionDeadline;
    }

    public List<Effect> tick(long now) {
        if (Set.of(State.READY, State.ACTIVE, State.PAUSED).contains(state)
                && (now >= validUntil || now >= sessionDeadline)) {
            return stop(now >= sessionDeadline
                    ? "session deadline expired" : "protection heartbeat expired");
        }
        return Collections.emptyList();
    }

    /** Closing always revokes admission before asking adapters to stop external effects. */
    public List<Effect> stop(String reason) {
        if (Set.of(State.STOPPING, State.DRAINING, State.RELEASING, State.CLOSED).contains(state))
            return Collections.emptyList();
        stopReason = reason;
        validUntil = Long.MIN_VALUE;
        state = State.STOPPING;
        return List.of(Effect.REVOKE_ADMISSION, Effect.STOP_TARGETS);
    }

    public List<Effect> targetsStopped(long eventEpoch, boolean verified) {
        if (eventEpoch != epoch || state != State.STOPPING || !verified
                || !pendingRequests.isEmpty()) return Collections.emptyList();
        state = State.DRAINING;
        return List.of(Effect.CLOSE_STREAMS);
    }

    /** Platform confirmation means queued/in-flight effects were cancelled or reached a known end. */
    public boolean requestsDrained(long eventEpoch, boolean verified) {
        if (eventEpoch != epoch || state != State.STOPPING || !verified) return false;
        pendingRequests.clear();
        return true;
    }

    public List<Effect> streamsClosed(long eventEpoch, boolean verified) {
        if (eventEpoch != epoch || state != State.DRAINING || !verified)
            return Collections.emptyList();
        state = State.RELEASING;
        return List.of(Effect.RELEASE_RESOURCES);
    }

    public void resourcesReleased(long eventEpoch, boolean success) {
        if (eventEpoch == epoch && state == State.RELEASING && success) state = State.CLOSED;
    }

    public int pendingRequestCount() { return pendingRequests.size(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
