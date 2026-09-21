package dev.droiduse.system;

import android.os.IBinder;
import dev.droiduse.system.CapabilitySnapshot;
import dev.droiduse.system.IDroidUseCallback;
import dev.droiduse.system.Observation;
import dev.droiduse.system.ObservationSpec;
import dev.droiduse.system.OperationAdmission;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.SessionUpdate;
import dev.droiduse.system.StreamHandle;
import dev.droiduse.system.StreamSpec;

/** @hide */
interface IDroidUseSystem {
    CapabilitySnapshot getCapabilities();
    SessionHandle openSession(in SessionSpec spec, IBinder clientToken, IDroidUseCallback callback);
    Observation observe(in SessionHandle handle, in ObservationSpec spec);
    OperationAdmission execute(in SessionHandle handle, in OperationRequest request);
    StreamHandle openStream(in SessionHandle handle, in StreamSpec spec);
    void updateSession(in SessionHandle handle, in SessionUpdate update);
    void closeSession(in SessionHandle handle, int reason);
}
