package dev.droiduse.agent

import org.json.JSONObject

/** Advisory model observations, never execution receipts or proof of recovery. */
object SceneReport {
    private val states=setOf("READY","LOADING","LOAD_ERROR","UNEXPECTED_POPUP","NO_PROGRESS","UNKNOWN")
    private val handling=setOf("CONTINUE","WAIT","RETRY","SKIP_OPTIONAL","ASK_USER")
    private val reasons=setOf("LOADING_INDICATOR","ERROR_MESSAGE","DIALOG_VISIBLE","TARGET_MISSING",
        "UNCHANGED_PAGE","OPTIONAL_STEP","USER_CONFIRMATION","NONE","UNKNOWN")
    const val prompt="""可附加scene对象记录当前判断：state仅可为READY/LOADING/LOAD_ERROR/UNEXPECTED_POPUP/NO_PROGRESS/UNKNOWN；handling仅可为CONTINUE/WAIT/RETRY/SKIP_OPTIONAL/ASK_USER；reasonCode仅可为LOADING_INDICATOR/ERROR_MESSAGE/DIALOG_VISIBLE/TARGET_MISSING/UNCHANGED_PAGE/OPTIONAL_STEP/USER_CONFIRMATION/NONE/UNKNOWN。scene不得包含页面原文、验证码或密码。不确定用UNKNOWN。handling只是本步意图，不能宣称重试成功；不可为完成任务跳过必需步骤。scene不改变动作权限，也不会触发自动兜底。"""

    fun sanitized(scene: JSONObject?): JSONObject? {
        if(scene==null) return null
        val state=scene.opt("state")
        val intent=scene.opt("handling")
        val reason=scene.opt("reasonCode")
        if(state !is String || state !in states || intent !is String || intent !in handling || reason !is String || reason !in reasons) return null
        return JSONObject().put("state",state).put("handling",intent).put("reasonCode",reason)
            .put("source","MODEL_REPORTED").put("verified",false)
    }
}
