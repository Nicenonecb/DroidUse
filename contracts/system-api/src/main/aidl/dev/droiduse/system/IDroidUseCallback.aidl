package dev.droiduse.system;

import dev.droiduse.system.CapabilitySnapshot;
import dev.droiduse.system.OperationResult;
import dev.droiduse.system.SessionEvent;

/** @hide */
oneway interface IDroidUseCallback {
    void onOperationResult(in OperationResult result);
    void onSessionEvent(in SessionEvent event);
    void onCapabilitiesChanged(in CapabilitySnapshot snapshot);
}
