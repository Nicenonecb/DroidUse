package dev.droiduse.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SceneReportTest {
    private fun report()=JSONObject().put("state","UNEXPECTED_POPUP").put("handling","ASK_USER").put("reasonCode","DIALOG_VISIBLE")
    @Test fun reportedSceneNeverBecomesVerifiedEvidenceOrCopiesFreeText() {
        val scene=report().put("verified",true).put("source","SYSTEM").put("text","验证码123456")
        val safe=JSONObject(TracePrivacy.modelRecord(JSONObject().put("frameId","f1")
            .put("reply",JSONObject().put("kind","ask_user").put("reason","private text").put("scene",scene)).toString()))
        assertEquals("f1",safe.getString("frameId"))
        assertEquals("MODEL_REPORTED",safe.getJSONObject("scene").getString("source"))
        assertFalse(safe.getJSONObject("scene").getBoolean("verified"))
        assertFalse(safe.toString().contains("123456"));assertFalse(safe.toString().contains("private text"))
    }
    @Test fun malformedAndFreeformSceneFieldsAreDropped() {
        assertNull(SceneReport.sanitized(null))
        for(key in listOf("state","handling","reasonCode")) {
            assertNull(SceneReport.sanitized(report().put(key,"secret-token")))
            assertNull(SceneReport.sanitized(report().put(key,42)))
            val missing=report();missing.remove(key);assertNull(SceneReport.sanitized(missing))
        }
    }
    @Test fun reportedRecoveryAndSkippingDoNotCreateSuccess() {
        for(intent in listOf("RETRY","SKIP_OPTIONAL","WAIT")) {
            val safe=SceneReport.sanitized(report().put("handling",intent))!!
            assertFalse(safe.getBoolean("verified"));assertFalse(safe.has("outcome"))
        }
    }
    @Test fun reportDoesNotChangeActionParsingOrPermitArbitraryActionData() {
        val reply=JSONObject().put("kind","select_file").put("targetId","f1").put("scene",report())
        val action=(VisionTaskModel.parseDecision(reply) as TaskLoop.Decision.Act).action
        assertEquals(TaskLoop.Action.Target(TargetOperation.SELECT_FILE,"f1"),action)
        assertThrows(IllegalArgumentException::class.java) { VisionTaskModel.parseDecision(reply.put("uri","content://private")) }
    }
}
