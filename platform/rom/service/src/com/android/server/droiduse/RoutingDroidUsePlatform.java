package com.android.server.droiduse;

import android.content.Context;
import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.*;
import java.util.*;

/** Routes by session mode; display and call ownership still share the manager's single session. */
final class RoutingDroidUsePlatform implements DroidUsePlatform {
    private final DroidUsePlatform display;
    private final DroidUsePlatform calls;
    RoutingDroidUsePlatform(Context context) {
        display = new ComputerControlDroidUsePlatform(context); calls = new CallDroidUsePlatform(context);
    }
    private DroidUsePlatform route(int mode) {
        return mode == DroidUseContract.MODE_CALL_ASSIST ? calls : display;
    }
    public CapabilityStatus[] capabilities() {
        Map<Integer, CapabilityStatus> result = new TreeMap<>();
        for (CapabilityStatus value : display.capabilities()) result.put(value.capabilityId, value);
        for (CapabilityStatus value : calls.capabilities()) result.put(value.capabilityId, value);
        if (calls.coreReady()) {
            CapabilityStatus stream = new CapabilityStatus();
            stream.capabilityId = DroidUseContract.CAP_STREAM_TRANSPORT;
            stream.availability = DroidUseContract.AVAILABILITY_DEGRADED;
            stream.reason = display.coreReady() ? "SCREENSHOT_FD_AND_CALL_PCM_PIPE" : "CALL_PCM_PIPE";
            result.put(stream.capabilityId, stream);
        }
        return result.values().toArray(new CapabilityStatus[0]);
    }
    public boolean coreReady() { return display.coreReady() || calls.coreReady(); }
    public PreparedSession prepare(SessionSpec s, long epoch) throws Exception { return route(s.mode).prepare(s, epoch); }
    public Set<SystemSession.Protection> installedProtections(SessionHandle h) throws Exception { return route(h.mode).installedProtections(h); }
    public void activate(SessionHandle h, SessionSpec s) throws Exception { route(h.mode).activate(h, s); }
    public Observation observe(SessionHandle h, ObservationSpec s) throws Exception { return route(h.mode).observe(h, s); }
    public OperationAdmission execute(SessionHandle h, OperationRequest r, Completion done) throws Exception { return route(h.mode).execute(h, r, done); }
    public StreamHandle openStream(SessionHandle h, StreamSpec s) throws Exception { return route(h.mode).openStream(h, s); }
    public void update(SessionHandle h, SessionUpdate s) throws Exception { route(h.mode).update(h, s); }
    public boolean stopAndDrain(SessionHandle h) { return route(h.mode).stopAndDrain(h); }
    public boolean closeStreams(SessionHandle h) { return route(h.mode).closeStreams(h); }
    public boolean releaseResources(SessionHandle h) { return route(h.mode).releaseResources(h); }
}
