package dev.droiduse.agent

import org.json.JSONObject

object TracePrivacy {
    private val sensitive = Regex("密码|验证码|口令|私钥|助记词|API[ _-]?KEY|ACCESS[ _-]?TOKEN|SECRET[ _-]?KEY|PASSWORD|PASSCODE|ONE[ -]?TIME[ -]?CODE", RegexOption.IGNORE_CASE)
    fun sensitiveScene(text: String): Boolean = sensitive.containsMatchIn(text)
    /** Model prose is not a safe audit format; keep only allowlisted machine fields. */
    fun modelRecord(raw: String): String {
        val record=JSONObject(raw);val reply=record.optJSONObject("reply") ?: JSONObject()
        val result=JSONObject()
        val id=record.optString("frameId")
        if(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) result.put("frameId",id)
        SceneReport.sanitized(reply.optJSONObject("scene"))?.let { result.put("scene",it) }
        val kind=reply.optString("kind")
        if(kind in setOf("tap","double_tap","long_press","drag","multi_touch","swipe","text","back","wait","read_chapters","finish","ask_user","select_file_at") + EditorOperation.actionNames + TargetOperation.actionNames + DeviceOperation.actionNames) result.put("kind",kind)
        for(key in listOf("x","y","x1","y1","x2","y2","durationMs","holdMs","start","end","before","after")) {
            val value=reply.opt(key)
            if(value is Int && value in -8192..16384) result.put(key,value)
        }
        val fingers=reply.optJSONArray("fingers")
        if(kind=="multi_touch" && fingers?.length()==2) {
            val safe=org.json.JSONArray();var valid=true
            for(i in 0 until 2) {
                val path=fingers.optJSONArray(i)
                if(path==null || path.length() !in 2..32) { valid=false;break }
                val points=org.json.JSONArray()
                for(j in 0 until path.length()) {
                    val point=path.optJSONObject(j);val x=point?.opt("x");val y=point?.opt("y")
                    if(x !is Int || y !is Int || x !in 0..8191 || y !in 0..8191) { valid=false;break }
                    points.put(JSONObject().put("x",x).put("y",y))
                }
                safe.put(points)
            }
            if(valid) result.put("fingers",safe)
        }
        if(kind in DeviceOperation.actionNames && reply.opt("value") is Int) {
            val value=reply.getInt("value")
            if(value in requireNotNull(DeviceOperation.fromAction(kind)).range) result.put("value",value)
        } else if(reply.has("value")) result.put("inputRedacted",true)
        if(reply.opt("passed") is Boolean) result.put("passed",reply.getBoolean("passed"))
        return result.toString()
    }
    fun redactKnownSecrets(text: String,secrets: List<String>): String = secrets.filter { it.isNotBlank() }
        .fold(text) { value,secret -> value.replace(secret,"[REDACTED]") }
}
class SensitiveSceneException : IllegalStateException("SENSITIVE_SCENE")
