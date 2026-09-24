package dev.droiduse.system;

import android.os.IBinder;
import dev.droiduse.system.CapabilityStatus;
import dev.droiduse.system.SessionHandle;
import dev.droiduse.system.SessionSpec;
import dev.droiduse.system.Observation;
import dev.droiduse.system.OperationRequest;
import dev.droiduse.system.StreamSpec;
import dev.droiduse.system.StreamHandle;

// ROM-private bridge; every transaction requires SYSTEM_UID.
/** @hide */
interface IDroidUseTelecom {
    CapabilityStatus[] getCapabilities();
    void open(in SessionHandle handle, in SessionSpec spec, IBinder owner);
    Observation observe(in SessionHandle handle);
    void execute(in SessionHandle handle, in OperationRequest request);
    StreamHandle openStream(in SessionHandle handle, in StreamSpec spec);
    void pause(in SessionHandle handle, boolean paused);
    boolean close(in SessionHandle handle);
    boolean healthy(in SessionHandle handle);
}
