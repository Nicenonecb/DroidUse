package dev.droiduse.system;

/** Frame-scoped system actions. Values and packages are chosen by the ROM, never by the model.
 * @hide
 */
public final class SystemActionPolicy {
    public static final int CONNECT_SAVED_WIFI = DroidUseContract.CONNECTIVITY_CONNECT_SAVED_WIFI;
    private SystemActionPolicy() {}

    public static int kind(String action) {
        if (action == null) return -1;
        return switch (action) {
            case "notification_open" -> DroidUseContract.SYSTEM_UI_NOTIFICATION_CLICK;
            case "notification_clear" -> DroidUseContract.SYSTEM_UI_NOTIFICATION_CLEAR;
            case "notification_reply" -> DroidUseContract.SYSTEM_UI_NOTIFICATION_REPLY;
            case "permission_grant" -> DroidUseContract.PACKAGE_GRANT_RUNTIME_PERMISSION;
            case "permission_revoke" -> DroidUseContract.PACKAGE_REVOKE_RUNTIME_PERMISSION;
            case "set_volume" -> DroidUseContract.DEVICE_SET_VOLUME;
            case "set_brightness" -> DroidUseContract.DEVICE_SET_BRIGHTNESS;
            case "set_wifi" -> DroidUseContract.CONNECTIVITY_SET_WIFI;
            case "connect_wifi" -> CONNECT_SAVED_WIFI;
            default -> -1;
        };
    }

    public static int domain(int kind) {
        return switch (kind) {
            case DroidUseContract.SYSTEM_UI_NOTIFICATION_CLICK, DroidUseContract.SYSTEM_UI_NOTIFICATION_CLEAR,
                    DroidUseContract.SYSTEM_UI_NOTIFICATION_REPLY -> DroidUseContract.DOMAIN_SYSTEM_UI;
            case DroidUseContract.PACKAGE_GRANT_RUNTIME_PERMISSION,
                    DroidUseContract.PACKAGE_REVOKE_RUNTIME_PERMISSION -> DroidUseContract.DOMAIN_PACKAGE;
            case DroidUseContract.DEVICE_SET_VOLUME, DroidUseContract.DEVICE_SET_BRIGHTNESS ->
                    DroidUseContract.DOMAIN_DEVICE;
            case DroidUseContract.CONNECTIVITY_SET_WIFI, CONNECT_SAVED_WIFI -> DroidUseContract.DOMAIN_CONNECTIVITY;
            default -> -1;
        };
    }

    public static boolean validValue(int kind, String value) {
        return kind == DroidUseContract.SYSTEM_UI_NOTIFICATION_REPLY
                ? value != null && !value.isBlank() && value.length() <= 4000 : value == null;
    }

    public static boolean validRequest(int domain, DeviceOperation op) {
        return op != null && domain(op.kind) == domain && domain > 0
                && op.targetId != null && op.targetId.matches("[A-Za-z0-9_-]{1,100}")
                && op.targetPackage == null && op.intValue == 0 && !op.boolValue
                && op.stringValues == null && op.sourceStreamId == null && validValue(op.kind, op.stringValue);
    }
}
