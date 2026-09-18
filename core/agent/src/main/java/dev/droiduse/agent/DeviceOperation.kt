package dev.droiduse.agent

/** Every operation is scoped to the background session, never the primary device globally. */
enum class DeviceOperation(val actionName: String,val range: IntRange=0..0) {
    CAMERA_CAPTURE("camera_capture"), CAMERA_VIDEO_START("camera_video_start"), CAMERA_VIDEO_STOP("camera_video_stop"),
    RECORD_START("record_start"), RECORD_STOP("record_stop"),
    SCAN_CODE("scan_code"), MEDIA_PLAY("media_play"), MEDIA_PAUSE("media_pause"),
    MEDIA_SEEK("media_seek",0..86400000), MEDIA_VOLUME("media_volume",0..100),
    MEDIA_SPEED("media_speed",25..400), MEDIA_SUBTITLES("media_subtitles",0..1),
    MEDIA_FULLSCREEN("media_fullscreen",0..1), ROTATE("display_rotate",0..3), DISPLAY_AWAKE("display_awake",0..1),
    NOTIFICATIONS("open_notifications"), SHARE("open_share"), FILE_PICKER("open_file_picker"),
    APP_HOME("app_home"), APP_RESTART("app_restart");
    companion object {
        val actionNames=entries.map { it.actionName }.toSet()
        fun fromAction(name: String)=entries.firstOrNull { it.actionName==name }
    }
}
