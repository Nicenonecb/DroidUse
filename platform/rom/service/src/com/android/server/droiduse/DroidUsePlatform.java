package com.android.server.droiduse;

import dev.droiduse.executor.SystemSession;
import dev.droiduse.system.CapabilityStatus;
import dev.droiduse.system.Observation;
import dev.droiduse.system.ObservationSpec;
import dev.droiduse.system.OperationAdmission;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.OperationResult;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.SessionUpdate;
import dev.droiduse.system.StreamHandle;
import dev.droiduse.system.StreamSpec;

import java.util.Set;

/** Branch-specific Android adapters hidden behind the stable ROM Binder service. */
interface DroidUsePlatform {
    interface Completion { void complete(OperationResult result); }

    final class Failure extends Exception {
        final int errorCode;
        final String clientCode;

        Failure(int errorCode, String clientCode) {
            super(clientCode);
            this.errorCode = errorCode;
            this.clientCode = clientCode;
        }
    }

    final class PreparedSession {
        final int displayId;
        final Set<SystemSession.Protection> installedProtections;

        PreparedSession(int displayId, Set<SystemSession.Protection> installedProtections) {
            this.displayId = displayId;
            this.installedProtections = Set.copyOf(installedProtections);
        }
    }

    CapabilityStatus[] capabilities();
    boolean coreReady();
    PreparedSession prepare(SessionSpec spec, long epoch) throws Exception;
    Set<SystemSession.Protection> installedProtections(SessionHandle handle) throws Exception;
    void activate(SessionHandle handle, SessionSpec spec) throws Exception;
    Observation observe(SessionHandle handle, ObservationSpec spec) throws Exception;
    OperationAdmission execute(SessionHandle handle, OperationRequest request,
            Completion completion) throws Exception;
    StreamHandle openStream(SessionHandle handle, StreamSpec spec) throws Exception;
    void update(SessionHandle handle, SessionUpdate update) throws Exception;
    boolean stopAndDrain(SessionHandle handle);
    boolean closeStreams(SessionHandle handle);
    boolean releaseResources(SessionHandle handle);
}
