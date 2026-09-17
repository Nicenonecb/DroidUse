package dev.droiduse.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskCheckpointTest {
    private fun event(name: String,step: Int=1)=JSONObject().put("event",name).put("step",step)
    private fun pending()=TaskCheckpoint().advance(event("OBSERVED").put("frameId","f1"))
        .advance(event("ACTION_PENDING").put("requestId","r1"))
    @Test fun interruptionBetweenSubmissionAndReceiptRemainsUnknown() {
        val restored=TaskCheckpoint.parse(JSONObject(pending().json().toString()))
        assertEquals("r1",restored.requestId);assertEquals("f1",restored.frameId)
        assertEquals(0,restored.executedActions);assertTrue(restored.interruptedSummary().contains("结果未知"))
        assertTrue(restored.interruptedSummary().contains("不能重发"))
    }
    @Test fun successfulReceiptCountsOnceWithoutClaimingTaskCompletion() {
        val result=event("ACTION_RESULT").put("requestId","r1").put("outcome","EXECUTED")
        val checkpoint=pending().advance(result).advance(result)
        assertEquals(1,checkpoint.executedActions);assertNull(checkpoint.state)
        assertTrue(checkpoint.interruptedSummary().contains("不代表任务完成"))
        val final=checkpoint.advance(event("TASK_STATE").put("state","COMPLETED"))
        assertEquals("COMPLETED",TaskCheckpoint.parse(final.json()).state)
    }
    @Test fun wrongRequestConflictingReceiptAndStepRollbackAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { pending().advance(event("ACTION_RESULT").put("requestId","other").put("outcome","EXECUTED")) }
        val unknown=pending().advance(event("ACTION_RESULT").put("requestId","r1").put("outcome","UNKNOWN_OUTCOME"))
        assertThrows(IllegalArgumentException::class.java) { unknown.advance(event("ACTION_RESULT").put("requestId","r1").put("outcome","EXECUTED")) }
        assertThrows(IllegalArgumentException::class.java) { pending().advance(event("OBSERVED",0).put("frameId","f0")) }
    }
    @Test fun checkpointNeverCopiesTaskTextInputOrExceptionBody() {
        val checkpoint=TaskCheckpoint().advance(event("ACTION_PENDING").put("requestId","r1")
            .put("task","private task").put("value","123456").put("error","api-key-secret"))
        assertFalse(checkpoint.json().toString().contains("private"))
        assertFalse(checkpoint.json().toString().contains("123456"))
        assertFalse(checkpoint.json().toString().contains("api-key"))
    }
    @Test fun malformedPersistedDataDoesNotInventProgress() {
        for(json in listOf(pending().json().put("version",2),pending().json().put("executedActions",30),
            pending().json().put("requestId",JSONObject.NULL),pending().json().put("outcome","secret"))) {
            assertThrows(IllegalArgumentException::class.java) { TaskCheckpoint.parse(json) }
        }
        // The max-steps rejection occurs on the 101st attempt and must remain auditable.
        assertEquals(101,TaskCheckpoint.parse(TaskCheckpoint(step=100).advance(event("TASK_STATE",101)
            .put("state","NEEDS_USER")).json()).step)
    }
}
