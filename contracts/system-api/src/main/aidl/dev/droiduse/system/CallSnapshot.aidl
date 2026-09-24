package dev.droiduse.system;

/** @hide */
parcelable CallSnapshot {
    String callId;
    int state;
    int[] operations;
    int audioRoute;
    int supportedAudioRoutes;
    boolean muted;
}
