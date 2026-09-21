package dev.droiduse.system;

/** @hide */
parcelable ObservationSpec {
    String sessionId;
    long epoch;
    long afterFrameId;
    boolean includeCapture;
    boolean includeSemantics;
    int maxWidth;
    int maxHeight;
}
