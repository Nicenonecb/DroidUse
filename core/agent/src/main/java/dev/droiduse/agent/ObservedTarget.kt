package dev.droiduse.agent

import org.json.JSONArray
import org.json.JSONObject

/** Backend-owned, frame-scoped handles. A model cannot supply an Intent, URI or file path. */
enum class TargetOperation(val actionName: String) {
    SELECT_FILE("select_file"), OPEN_APP("open_app"), OPEN_LINK("open_link"),
    NOTIFICATION_OPEN("notification_open"), NOTIFICATION_CLEAR("notification_clear"),
    NOTIFICATION_REPLY("notification_reply"), PERMISSION_GRANT("permission_grant"),
    PERMISSION_REVOKE("permission_revoke"), SET_WIFI("set_wifi"), CONNECT_WIFI("connect_wifi"),
    SET_VOLUME("set_volume"), SET_BRIGHTNESS("set_brightness");
    fun acceptsValue(value: String?): Boolean = if (this == NOTIFICATION_REPLY)
        value != null && value.isNotBlank() && value.length <= 4000 else value == null
    companion object {
        val actionNames=entries.map { it.actionName }.toSet()
        fun fromAction(name: String)=entries.find { it.actionName==name }
    }
}

data class ObservedTarget(val id: String,val operation: TargetOperation,val label: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(label.isNotBlank() && label.length<=500)
    }
    companion object {
        fun validate(targets: List<ObservedTarget>) {
            require(targets.size<=100 && targets.map { it.id }.distinct().size==targets.size)
        }
        fun prompt(targets: List<ObservedTarget>): String {
            validate(targets)
            return JSONArray(targets.map {
                JSONObject().put("targetId",it.id).put("kind",it.operation.actionName).put("label",it.label)
            }).toString()
        }
        fun actions(declared: Set<String>,targets: List<ObservedTarget>): Set<String> {
            validate(targets)
            val available=targets.map { it.operation.actionName }.toSet()
            return declared.filter { it !in TargetOperation.actionNames || it in available }.toSet()
        }
        fun accepts(frame: TaskLoop.Frame,action: TaskLoop.Action.Target): Boolean =
            action.operation.acceptsValue(action.value) && action.operation.actionName in frame.supportedActions &&
                frame.targets.any { it.id==action.targetId && it.operation==action.operation }
    }
}
