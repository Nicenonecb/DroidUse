package com.android.server.droiduse;

import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.CapabilityStatus;
import dev.droiduse.system.DroidUseContract;
import dev.droiduse.system.Observation;
import dev.droiduse.system.ObservationSpec;
import dev.droiduse.system.OperationAdmission;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.SessionUpdate;
import dev.droiduse.system.StreamHandle;
import dev.droiduse.system.StreamSpec;

import java.util.Set;

/** Fail-closed adapter used until real framework hooks are installed. */
final class UnavailableDroidUsePlatform implements DroidUsePlatform {
    @Override public CapabilityStatus[] capabilities() {
        CapabilityStatus[] result = new CapabilityStatus[20];
        for (int capability = DroidUseContract.CAP_APP_TASK_CONTROL;
             capability <= DroidUseContract.CAP_STREAM_TRANSPORT; capability++) {
            CapabilityStatus status = new CapabilityStatus();
            status.capabilityId = capability;
            status.availability = DroidUseContract.AVAILABILITY_UNAVAILABLE;
            status.reason = "FRAMEWORK_ADAPTER_MISSING";
            result[capability - DroidUseContract.CAP_APP_TASK_CONTROL] = status;
        }
        return result;
    }

    @Override public boolean coreReady() { return false; }
    @Override public PreparedSession prepare(SessionSpec spec, long epoch) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public Set<SystemSession.Protection> installedProtections(SessionHandle handle) {
        return Set.of();
    }
    @Override public void activate(SessionHandle handle, SessionSpec spec) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public Observation observe(SessionHandle handle, ObservationSpec spec) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public OperationAdmission execute(SessionHandle handle, OperationRequest request,
            Completion completion) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public StreamHandle openStream(SessionHandle handle, StreamSpec spec) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public void update(SessionHandle handle, SessionUpdate update) {
        throw new UnsupportedOperationException("Framework adapter missing");
    }
    @Override public boolean stopAndDrain(SessionHandle handle) { return true; }
    @Override public boolean closeStreams(SessionHandle handle) { return true; }
    @Override public boolean releaseResources(SessionHandle handle) { return true; }
}
