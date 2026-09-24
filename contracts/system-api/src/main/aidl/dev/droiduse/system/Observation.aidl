package dev.droiduse.system;

import android.os.ParcelFileDescriptor;
import dev.droiduse.system.SemanticNode;
import dev.droiduse.system.ActionTarget;

/** @hide */
parcelable Observation {
    String sessionId;
    long epoch;
    long frameId;
    long capturedAtElapsedRealtimeMs;
    int displayId;
    int width;
    int height;
    int rotation;
    String packageName;
    int taskId;
    long windowGeneration;
    long editorGeneration;
    boolean protectedContent;
    String captureMimeType;
    @nullable ParcelFileDescriptor capture;
    SemanticNode[] semantics;
    @nullable ActionTarget[] targets;
    @nullable String[] scopedActions;
    @nullable String[] editorActions;
    @nullable String systemContext;
}
