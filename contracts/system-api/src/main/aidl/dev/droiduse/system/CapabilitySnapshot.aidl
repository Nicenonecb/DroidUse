package dev.droiduse.system;

import dev.droiduse.system.CapabilityStatus;

/** @hide */
parcelable CapabilitySnapshot {
    int interfaceVersion;
    boolean coreReady;
    String backend;
    long generatedAtElapsedRealtimeMs;
    CapabilityStatus[] capabilities;
}
