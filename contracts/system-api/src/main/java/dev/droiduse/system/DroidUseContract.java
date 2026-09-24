package dev.droiduse.system;

/**
 * Stable numeric and string identifiers shared by the ROM service and updateable Executor.
 * @hide
 */
public final class DroidUseContract {
    private DroidUseContract() {}

    public static final int INTERFACE_VERSION = 1;

    public static final int AVAILABILITY_AVAILABLE = 1;
    public static final int AVAILABILITY_DEGRADED = 2;
    public static final int AVAILABILITY_UNAVAILABLE = 3;
    public static final int AVAILABILITY_DISABLED = 4;

    public static final int MODE_ISOLATED_DISPLAY = 1;
    public static final int MODE_PHYSICAL_CONTROL = 2;
    public static final int MODE_CALL_ASSIST = 3;
    public static final int MODE_MEDIA_OBSERVE = 4;

    public static final int CAP_SYSTEM_SERVICE = 1;
    public static final int CAP_IDENTITY = 2;
    public static final int CAP_SESSION_OWNERSHIP = 3;
    public static final int CAP_APP_TASK_CONTROL = 4;
    public static final int CAP_VIRTUAL_DISPLAY = 5;
    public static final int CAP_PHYSICAL_DISPLAY = 6;
    public static final int CAP_DISPLAY_CAPTURE = 7;
    public static final int CAP_UI_SEMANTICS = 8;
    public static final int CAP_INPUT_INJECTION = 9;
    public static final int CAP_SYSTEM_NAVIGATION = 10;
    public static final int CAP_IME_CLIPBOARD = 11;
    public static final int CAP_NOTIFICATIONS_SYSTEM_UI = 12;
    public static final int CAP_PACKAGE_PERMISSIONS = 13;
    public static final int CAP_APP_POLICY = 14;
    public static final int CAP_DEVICE_SETTINGS = 15;
    public static final int CAP_CONNECTIVITY = 16;
    public static final int CAP_POWER = 17;
    public static final int CAP_TELECOM = 18;
    public static final int CAP_CALL_AUDIO_CAPTURE = 19;
    public static final int CAP_CALL_TTS_INJECTION = 20;
    public static final int CAP_PRIVATE_CALL_COMMAND = 21;
    public static final int CAP_MEDIA_AUDIO_CAPTURE = 22;
    public static final int CAP_STREAM_TRANSPORT = 23;
    public static final int CAP_MULTI_USER_RESERVED = 24;
    public static final int CAP_BOOT_UNLOCK = 25;
    public static final int CAP_FAILURE_CLEANUP = 26;
    public static final int CAP_RESOURCE_LIMITS = 27;
    public static final int CAP_DIAGNOSTICS = 28;
    public static final int CAP_DYNAMIC_CONFIG = 29;
    public static final int CAP_SELINUX = 30;
    public static final int CAP_APK_COMPATIBILITY = 31;

    public static final int DOMAIN_APP_TASK = 1;
    public static final int DOMAIN_DISPLAY = 2;
    public static final int DOMAIN_INPUT = 3;
    public static final int DOMAIN_SYSTEM_UI = 4;
    public static final int DOMAIN_PACKAGE = 5;
    public static final int DOMAIN_DEVICE = 6;
    public static final int DOMAIN_CONNECTIVITY = 7;
    public static final int DOMAIN_POWER = 8;
    public static final int DOMAIN_TELECOM = 9;

    public static final int APP_QUERY = 100;
    public static final int APP_LAUNCH = 101;
    public static final int APP_SWITCH = 102;
    public static final int APP_MOVE_TO_DISPLAY = 103;
    public static final int APP_FINISH_TASK = 104;
    public static final int APP_FORCE_STOP = 105;
    public static final int APP_RESTORE = 106;
    public static final int APP_OPEN_TARGET = 107;

    public static final int DISPLAY_CREATE = 200;
    public static final int DISPLAY_RESIZE = 201;
    public static final int DISPLAY_ROTATE = 202;
    public static final int DISPLAY_DESTROY = 203;

    public static final int INPUT_TAP = 300;
    public static final int INPUT_DOUBLE_TAP = 301;
    public static final int INPUT_LONG_PRESS = 302;
    public static final int INPUT_SWIPE = 303;
    public static final int INPUT_DRAG = 304;
    public static final int INPUT_MULTI_TOUCH = 305;
    public static final int INPUT_KEY = 306;
    public static final int INPUT_TEXT = 307;
    public static final int INPUT_SELECTION = 308;
    public static final int INPUT_DELETE = 309;
    public static final int INPUT_EDITOR_ACTION = 310;
    public static final int INPUT_CLIPBOARD_READ = 311;
    public static final int INPUT_CLIPBOARD_WRITE = 312;
    public static final int INPUT_COPY = 313;
    public static final int INPUT_CUT = 314;
    public static final int INPUT_PASTE = 315;
    public static final int INPUT_SELECT_ALL = 316;

    public static final int POINTER_DOWN = 0;
    public static final int POINTER_UP = 1;
    public static final int POINTER_MOVE = 2;
    public static final int POINTER_CANCEL = 3;

    public static final int SYSTEM_UI_NOTIFICATION_QUERY = 400;
    public static final int SYSTEM_UI_NOTIFICATION_REPLY = 401;
    public static final int SYSTEM_UI_NOTIFICATION_CLICK = 402;
    public static final int SYSTEM_UI_NOTIFICATION_CLEAR = 403;
    public static final int SYSTEM_UI_STATUS_BAR_EXPAND = 404;
    public static final int SYSTEM_UI_STATUS_BAR_COLLAPSE = 405;
    public static final int SYSTEM_UI_QUICK_SETTING = 406;

    public static final int PACKAGE_INSTALL = 500;
    public static final int PACKAGE_UPDATE = 501;
    public static final int PACKAGE_UNINSTALL = 502;
    public static final int PACKAGE_ENABLE = 503;
    public static final int PACKAGE_DISABLE = 504;
    public static final int PACKAGE_GRANT_RUNTIME_PERMISSION = 505;
    public static final int PACKAGE_REVOKE_RUNTIME_PERMISSION = 506;

    public static final int DEVICE_SET_APP_OP = 600;
    public static final int DEVICE_SET_ROLE = 601;
    public static final int DEVICE_SET_BATTERY_POLICY = 602;
    public static final int DEVICE_SET_VOLUME = 603;
    public static final int DEVICE_SET_BRIGHTNESS = 604;
    public static final int DEVICE_SET_ROTATION = 605;
    public static final int DEVICE_SET_DND = 606;
    public static final int DEVICE_SET_SCREEN_TIMEOUT = 607;
    public static final int DEVICE_SET_LANGUAGE = 608;

    public static final int CONNECTIVITY_SET_WIFI = 700;
    public static final int CONNECTIVITY_SET_BLUETOOTH = 701;
    public static final int CONNECTIVITY_SET_HOTSPOT = 702;
    public static final int CONNECTIVITY_SET_MOBILE_DATA = 703;
    public static final int CONNECTIVITY_SET_VPN = 704;
    public static final int CONNECTIVITY_QUERY_STATUS = 705;
    public static final int CONNECTIVITY_SET_AUDIO_ROUTE = 706;
    public static final int CONNECTIVITY_CONNECT_SAVED_WIFI = 707;

    public static final int POWER_WAKE = 800;
    public static final int POWER_LOCK = 801;
    public static final int POWER_ACQUIRE_WAKE_LOCK = 802;
    public static final int POWER_RELEASE_WAKE_LOCK = 803;
    public static final int POWER_REBOOT = 804;
    public static final int POWER_SHUTDOWN = 805;

    public static final int TELECOM_DIAL = 900;
    public static final int TELECOM_ANSWER = 901;
    public static final int TELECOM_END = 902;
    public static final int TELECOM_HOLD = 903;
    public static final int TELECOM_UNHOLD = 904;
    public static final int TELECOM_SET_MUTED = 905;
    public static final int TELECOM_SET_AUDIO_ROUTE = 906;
    public static final int TELECOM_DTMF = 907;

    public static final int STREAM_SCREENSHOT = 1;
    public static final int STREAM_CALL_UPLINK = 2;
    public static final int STREAM_CALL_DOWNLINK = 3;
    public static final int STREAM_CALL_MIXED = 4;
    public static final int STREAM_CALL_INJECTION = 5;
    public static final int STREAM_PRIVATE_MIC = 6;
    public static final int STREAM_MEDIA_PLAYBACK = 7;

    public static final int STREAM_DIRECTION_SYSTEM_TO_CLIENT = 1;
    public static final int STREAM_DIRECTION_CLIENT_TO_SYSTEM = 2;

    public static final int SESSION_UPDATE_PAUSE = 1;
    public static final int SESSION_UPDATE_RESUME = 2;
    public static final int SESSION_UPDATE_TARGET = 3;
    public static final int SESSION_UPDATE_LIMITS = 4;

    public static final int EVENT_READY = 1;
    public static final int EVENT_CAPABILITIES_CHANGED = 2;
    public static final int EVENT_TARGET_CHANGED = 3;
    public static final int EVENT_AUDIO_ROUTE_CHANGED = 4;
    public static final int EVENT_RESOURCE_LIMIT = 5;
    public static final int EVENT_STOPPING = 6;
    public static final int EVENT_CLOSED = 7;

    public static final String OK = "OK";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String DISABLED = "DISABLED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String UNSUPPORTED_USER = "UNSUPPORTED_USER";
    public static final String USER_NOT_UNLOCKED = "USER_NOT_UNLOCKED";
    public static final String BUSY = "BUSY";
    public static final String STALE_SESSION = "STALE_SESSION";
    public static final String STALE_TARGET = "STALE_TARGET";
    public static final String STALE_OBSERVATION = "STALE_OBSERVATION";
    public static final String RESOURCE_LIMIT = "RESOURCE_LIMIT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String CANCELLED = "CANCELLED";
    public static final String UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME";
    public static final String AUDIO_ROUTE_UNAVAILABLE = "AUDIO_ROUTE_UNAVAILABLE";
    public static final String PROTECTED_CONTENT = "PROTECTED_CONTENT";

    public static final int ERROR_DISABLED = 1;
    public static final int ERROR_UNSUPPORTED = 2;
    public static final int ERROR_UNSUPPORTED_USER = 3;
    public static final int ERROR_USER_NOT_UNLOCKED = 4;
    public static final int ERROR_BUSY = 5;
    public static final int ERROR_STALE_SESSION = 6;
    public static final int ERROR_INVALID_REQUEST = 7;
    public static final int ERROR_RESOURCE_LIMIT = 8;
    public static final int ERROR_TIMEOUT = 9;
    public static final int ERROR_UNKNOWN = 10;
}
