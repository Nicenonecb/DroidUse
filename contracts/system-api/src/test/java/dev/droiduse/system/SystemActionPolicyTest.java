package dev.droiduse.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class SystemActionPolicyTest {
    private DeviceOperation operation(String action) {
        DeviceOperation op = new DeviceOperation();
        op.kind = SystemActionPolicy.kind(action);
        op.targetId = "issued-handle";
        return op;
    }
    @Test public void rejectsForgedScopeAndCallerValues() {
        DeviceOperation op = operation("permission_grant");
        int domain = DroidUseContract.DOMAIN_PACKAGE;
        assertTrue(SystemActionPolicy.validRequest(domain, op));
        assertFalse(SystemActionPolicy.validRequest(DroidUseContract.DOMAIN_DEVICE, op));
        op.targetPackage = "another.app";
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.targetPackage = null; op.stringValue = "android.permission.CAMERA";
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.stringValue = null; op.intValue = 100;
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.intValue = 0; op.targetId = "../package";
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.targetId = "issued-handle"; op.sourceStreamId = "external";
        assertFalse(SystemActionPolicy.validRequest(domain, op));
    }
    @Test public void replyIsTheOnlyCallerTextField() {
        DeviceOperation op = operation("notification_reply");
        int domain = DroidUseContract.DOMAIN_SYSTEM_UI;
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.stringValue = "synthetic reply";
        assertTrue(SystemActionPolicy.validRequest(domain, op));
        op.stringValue = "x".repeat(4001);
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op.stringValue = "   ";
        assertFalse(SystemActionPolicy.validRequest(domain, op));
        op = operation("set_brightness"); op.stringValue = "100";
        assertFalse(SystemActionPolicy.validRequest(DroidUseContract.DOMAIN_DEVICE, op));
    }
    @Test public void operationRequiresFreshObservation() {
        SessionHandle h = new SessionHandle();
        h.sessionId = "s"; h.epoch = 1; h.mode = DroidUseContract.MODE_ISOLATED_DISPLAY; h.displayId = 2;
        OperationRequest r = new OperationRequest();
        r.sessionId = "s"; r.epoch = 1; r.requestId = "r";
        r.domain = DroidUseContract.DOMAIN_DEVICE; r.device = operation("set_volume");
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(h, r));
        r.expectedFrameId = 5; r.expectedWindowGeneration = 2;
        DroidUseContractValidator.validateOperation(h, r);
        r.device.boolValue = true;
        assertThrows(IllegalArgumentException.class, () -> DroidUseContractValidator.validateOperation(h, r));
    }
}
