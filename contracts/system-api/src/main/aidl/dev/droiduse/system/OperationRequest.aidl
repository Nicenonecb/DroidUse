package dev.droiduse.system;

import dev.droiduse.system.AppOperation;
import dev.droiduse.system.DeviceOperation;
import dev.droiduse.system.DisplayOperation;
import dev.droiduse.system.InputOperation;
import dev.droiduse.system.TelecomOperation;

/** @hide */
parcelable OperationRequest {
    String sessionId;
    long epoch;
    String requestId;
    long expectedFrameId;
    long expectedWindowGeneration;
    int domain;
    @nullable InputOperation input;
    @nullable AppOperation app;
    @nullable DisplayOperation display;
    @nullable DeviceOperation device;
    @nullable TelecomOperation telecom;
}
