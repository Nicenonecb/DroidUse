package dev.droiduse.agent
import org.junit.Assert.*
import org.junit.Test

class TaskLoopTest {
    @Test fun modelAuditFailureStopsBeforeInputAndReleasesSession() {
        val device=Device()
        val model=Model().apply { onDecide={throw AuditWriteException()} }
        val loop=TaskLoop(device,model,{clock},"测试",retainForUser=true)
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
        assertEquals(TaskLoop.Failure.AUDIT_FAILURE,loop.failure)
        assertEquals(0,device.actions);assertEquals(1,device.stops);assertFalse(loop.awaitingUser)
    }
    @Test fun recoveryJournalFollowsFreshObservationAndRecordsStaleRejection() {
        val records=mutableListOf<org.json.JSONObject>()
        val device=Device().apply { outcome=TaskLoop.Outcome.STALE_OBSERVATION }
        val loop=TaskLoop(device,Model(),{clock},"测试",audit={records.add(it)})
        loop.step()
        assertEquals("BACKEND_REJECTED_STALE_FRAME",records.single { it.getString("event")=="REOBSERVE_REQUIRED" }.getString("reason"))
        loop.pause();loop.step();loop.resume()
        assertFalse(records.any { it.getString("event")=="RESUMED_FROM_FRESH_OBSERVATION" })
        loop.step()
        val resumed=records.single { it.getString("event")=="RESUMED_FROM_FRESH_OBSERVATION" }
        assertEquals("frame2",resumed.getString("frameId"))
        assertTrue(records.indexOf(resumed)>records.indexOfLast { it.getString("event")=="OBSERVED" })
        assertFalse(records.any { it.optString("state")=="COMPLETED" })
    }
    @Test fun lostActionReplyPausesWithoutReplayAndLinksFreshResumeFrame() {
        for(error in listOf(java.net.SocketTimeoutException("private-token"),
            java.io.IOException("private-token"),IllegalStateException("private-token"))) {
            val device=Device().apply { actionError=error }
            val model=Model()
            val records=mutableListOf<org.json.JSONObject>()
            val loop=TaskLoop(device,model,{clock},"测试",retainForUser=true,audit={records.add(it)})
            assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
            assertTrue(loop.awaitingUser);assertEquals(0,device.stops)
            loop.step();assertEquals(1,device.actions);assertEquals(1,model.calls)
            val request=records.single { it.getString("event")=="ACTION_PENDING" }.getString("requestId")
            val result=records.single { it.getString("event")=="ACTION_RESULT" }
            assertEquals(request,result.getString("requestId"))
            assertEquals("UNKNOWN_OUTCOME",result.getString("outcome"))
            assertEquals("action",records.single { it.getString("event")=="STAGE_FAILED" }.getString("stage"))
            assertFalse(records.toString().contains("private-token"));assertFalse(loop.message.contains("private-token"))
            device.actionError=null
            // The new observation can change the decision: no old click is replayed.
            model.decision=TaskLoop.Decision.AskUser("检查新画面")
            loop.resume();loop.step()
            assertEquals(2,device.observes);assertEquals(1,device.actions)
            assertEquals(request,records.last { it.getString("event")=="OBSERVED" }.getString("afterRequestId"))
            loop.requestStop();loop.step();assertEquals(1,device.stops)
        }
    }
    @Test fun failureJournalCannotLeaveUntrackedLiveSession() {
        val device=Device().apply { actionError=java.net.SocketTimeoutException() }
        val loop=TaskLoop(device,Model(),{clock},"测试",retainForUser=true,audit={
            if(it.getString("event") in setOf("STAGE_FAILED","TASK_STATE")) throw java.io.IOException()
        })
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
        assertEquals(TaskLoop.Failure.AUDIT_FAILURE,loop.failure)
        assertFalse(loop.awaitingUser);assertEquals(1,device.stops);assertEquals(1,device.actions)
    }

    @Test fun retainedAndManuallyPausedTasksStillReleaseOnPressure() {
        for(awaiting in listOf(false,true)) {
            val device=Device();val model=Model()
            if(awaiting) model.decision=TaskLoop.Decision.AskUser("处理登录")
            val loop=TaskLoop(device,model,{clock},"测试",retainForUser=true)
            loop.step();if(!awaiting) loop.pause()
            val calls=model.calls
            device.healthError=ResourcePressureException("LOW_MEMORY")
            assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
            assertFalse(loop.awaitingUser);assertEquals(1,device.stops);assertEquals(calls,model.calls)
            assertThrows(IllegalStateException::class.java) { loop.resume() }
        }
    }
    @Test fun executorDisconnectionDuringPauseIsNotSuccessfulOrRetained() {
        val device=Device();val loop=TaskLoop(device,Model(),{clock},"测试",retainForUser=true)
        loop.step();loop.pause();device.healthError=java.io.IOException("private transport body")
        assertEquals(TaskLoop.State.FAILED,loop.step())
        assertEquals(TaskLoop.Failure.EXECUTOR_DISCONNECTED,loop.failure)
        assertFalse(loop.message.contains("private transport body"));assertEquals(1,device.stops)
    }

    @Test fun retainedUserPauseResumesWithNewFrameAndStopReleases() {
        val device=Device();val model=Model().apply { decision=TaskLoop.Decision.AskUser("请处理验证码") }
        val loop=TaskLoop(device,model,{clock},"测试",retainForUser=true)
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step());assertTrue(loop.awaitingUser)
        assertEquals(0,device.stops);loop.step();assertEquals(1,device.observes)
        model.decision=TaskLoop.Decision.Act(TaskLoop.Action.Tap(10,20))
        loop.resume();loop.step();assertEquals(2,device.observes);assertEquals(1,device.actions)
        loop.requestStop();assertEquals(TaskLoop.State.STOPPED,loop.step());assertEquals(1,device.stops)
    }

    @Test fun actionJournalLinksNextObservationAndNeverRecordsTaskText() {
        val records=mutableListOf<org.json.JSONObject>()
        val loop=TaskLoop(Device(),Model(),{clock},"secret task",audit={records.add(it)})
        loop.step();loop.step()
        val pending=records.first { it.getString("event")=="ACTION_PENDING" }
        val after=records.first { it.getString("event")=="OBSERVED" && !it.isNull("afterRequestId") }
        assertEquals(pending.getString("requestId"),after.getString("afterRequestId"))
        assertFalse(records.toString().contains("secret task"))
    }
    @Test fun auditFailurePreventsUnrecordedAction() {
        val device=Device()
        val loop=TaskLoop(device,Model(),{clock},"测试",audit={throw java.io.IOException("disk full")})
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step());assertEquals(0,device.actions)
    }

    @Test fun unsupportedGestureIsStoppedBeforeSubmit() {
        val device=Device();val model=Model().apply { decision=TaskLoop.Decision.Act(TaskLoop.Action.DoubleTap(10,20)) }
        val loop=TaskLoop(device,model,{clock},"双击")
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step());assertEquals(0,device.actions)
    }
    @Test fun declaredGestureStillValidatesDurationAndCoordinates() {
        val device=Device().apply { supported += "long_press" }
        val model=Model().apply { decision=TaskLoop.Decision.Act(TaskLoop.Action.LongPress(10,20,4000)) }
        assertEquals(TaskLoop.State.FAILED,TaskLoop(device,model,{clock},"长按").step());assertEquals(0,device.actions)
    }

    @Test fun modelTimeoutIsRecordedWithoutLeakingExceptionOrRetrying() {
        val device=Device()
        val model=Model().apply { onDecide={ throw java.net.SocketTimeoutException("secret-token") } }
        val loop=TaskLoop(device,model,{clock},"搜索")
        assertEquals(TaskLoop.State.FAILED,loop.step())
        assertEquals(TaskLoop.Failure.MODEL_TIMEOUT,loop.failure)
        assertFalse(loop.message.contains("secret-token"))
        loop.step();assertEquals(1,model.calls);assertEquals(0,device.actions)
    }

    @Test fun readingWithoutBackendEvidenceIsRejectedBeforeDispatch() {
        val d=Device().apply { supported += "read_chapters" };val m=Model().apply{decision=TaskLoop.Decision.Act(TaskLoop.Action.ReadChapters)}
        assertEquals(TaskLoop.State.FAILED,TaskLoop(d,m,{clock},"读前三章").step());assertEquals(0,d.actions)
    }
    @Test fun traceFailureDoesNotBreakVerifiedResultAndClaimIsRetained() {
        val d=Device();val m=Model().apply{decision=TaskLoop.Decision.Finish("三章摘要")}
        val loop=TaskLoop(d,m,{clock},"阅读",trace={_,_->error("disk full")})
        assertEquals(TaskLoop.State.COMPLETED,loop.step());assertTrue(loop.message.contains("三章摘要"))
    }
    private var clock=100L
    private class Device : TaskLoop.Executor {
        var actionError: Exception?=null
        var healthError: Exception?=null
        override fun checkHealth() { healthError?.let { throw it } }
        var supported=setOf("tap","swipe","back","wait","text")
        var ready=true; var begins=0; var observes=0; var actions=0; var stops=0
        var ids=emptyList<String>()
        var display=9; var captured=100L; var outcome=TaskLoop.Outcome.EXECUTED
        var completionReady=true
        override fun begin(): Boolean { begins++; return ready }
        override fun observe(): TaskLoop.Frame { observes++; return TaskLoop.Frame(ids.getOrNull(observes-1) ?: "frame$observes",display,100,200,0,captured,"test.app","synthetic-png",3,completionReady=completionReady,supportedActions=supported) }
        override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome { actions++; actionError?.let { throw it }; return outcome }
        override fun cancel() { stops++ }
    }
    private class Model : TaskLoop.Model {
        var calls=0; var verified=0; var decision: TaskLoop.Decision=TaskLoop.Decision.Act(TaskLoop.Action.Tap(10,20))
        var onDecide: () -> Unit = {}; var verifies=true
        override fun decide(task: String,frame: TaskLoop.Frame): TaskLoop.Decision { calls++; onDecide(); return decision }
        override fun verify(task: String,claim: String,frame: TaskLoop.Frame): TaskLoop.Verification { verified++; return TaskLoop.Verification(verifies,frame.id,"页面已显示目标结果") }
        override fun cancel() {}
    }
    @Test fun unreadyNeverObservesOrCallsModel() {
        val d=Device().apply{ready=false};val m=Model();val loop=TaskLoop(d,m,{clock},"搜索")
        assertEquals(TaskLoop.State.FAILED,loop.step());assertEquals(0,m.calls);assertEquals(0,d.observes)
    }
    @Test fun executedIsNotCompletedAndNextStepObservesAgain() {
        val d=Device();val m=Model();val loop=TaskLoop(d,m,{clock},"搜索")
        assertEquals(TaskLoop.State.RUNNING,loop.step());assertEquals(TaskLoop.State.RUNNING,loop.step())
        assertEquals(2,d.observes);assertEquals(2,d.actions)
    }
    @Test fun completionRequiresNewFrameAndVerification() {
        val d=Device();val m=Model().apply{decision=TaskLoop.Decision.Finish("完成")};val loop=TaskLoop(d,m,{clock},"搜索")
        assertEquals(TaskLoop.State.COMPLETED,loop.step());assertEquals(2,d.observes);assertEquals(1,m.verified);assertEquals(1,d.stops)
    }
    @Test fun rejectedVerificationNeedsHuman() {
        val d=Device();val m=Model().apply{decision=TaskLoop.Decision.Finish("完成");verifies=false}
        assertEquals(TaskLoop.State.NEEDS_USER,TaskLoop(d,m,{clock},"搜索").step())
    }
    @Test fun missingReadingEvidenceCannotBeOverriddenByModel() {
        val d=Device().apply{completionReady=false};val m=Model().apply{decision=TaskLoop.Decision.Finish("读完了")}
        assertEquals(TaskLoop.State.NEEDS_USER,TaskLoop(d,m,{clock},"阅读").step());assertEquals(0,m.verified)
    }
    @Test fun unknownOutcomeIsNeverRetried() {
        val d=Device().apply{outcome=TaskLoop.Outcome.UNKNOWN_OUTCOME};val loop=TaskLoop(d,Model(),{clock},"搜索")
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step());loop.step();assertEquals(1,d.actions)
    }
    @Test fun stopDuringModelDiscardsLateAction() {
        val d=Device();val m=Model();val loop=TaskLoop(d,m,{clock},"搜索");m.onDecide={loop.stop()}
        assertEquals(TaskLoop.State.STOPPED,loop.step());assertEquals(0,d.actions)
    }
    @Test fun pauseBeforeStartStillRequiresBeginAfterResume() {
        val d=Device();val loop=TaskLoop(d,Model(),{clock},"搜索");loop.pause()
        assertEquals(TaskLoop.State.PAUSED,loop.step());assertEquals(0,d.begins)
        loop.resume();loop.step();assertEquals(1,d.begins)
    }
    @Test fun pauseDuringModelDiscardsDecisionThenReobserves() {
        val d=Device();val m=Model();val loop=TaskLoop(d,m,{clock},"搜索");m.onDecide={loop.pause()}
        assertEquals(TaskLoop.State.PAUSED,loop.step());assertEquals(0,d.actions)
        m.onDecide={};loop.resume();loop.step();assertEquals(2,d.observes);assertEquals(1,d.actions)
    }
    @Test fun staleFrameAfterSlowModelDoesNotInject() {
        val d=Device();val m=Model().apply{onDecide={clock=6000}}
        assertEquals(TaskLoop.State.RUNNING,TaskLoop(d,m,{clock},"搜索").step());assertEquals(0,d.actions)
    }
    @Test fun mainDisplayAndOutOfBoundsAreRejected() {
        val d=Device().apply{display=0};assertEquals(TaskLoop.State.FAILED,TaskLoop(d,Model(),{clock},"搜索").step());assertEquals(0,d.actions)
        val d2=Device();val m=Model().apply{decision=TaskLoop.Decision.Act(TaskLoop.Action.Tap(100,20))}
        assertEquals(TaskLoop.State.FAILED,TaskLoop(d2,m,{clock},"搜索").step());assertEquals(0,d2.actions)
    }
    @Test fun nonconsecutiveReplayedFrameIsRejectedBeforeAnotherDecision() {
        val d=Device().apply{ids=listOf("a","b","a")};val m=Model();val loop=TaskLoop(d,m,{clock},"搜索")
        loop.step();loop.step();assertEquals(TaskLoop.State.FAILED,loop.step())
        assertEquals(2,m.calls);assertEquals(2,d.actions)
    }
    @Test fun wrongEditorAndBudgetExhaustionAreRejected() {
        val d=Device();val m=Model().apply{decision=TaskLoop.Decision.Act(TaskLoop.Action.Text("中文",4))}
        assertEquals(TaskLoop.State.FAILED,TaskLoop(d,m,{clock},"搜索").step());assertEquals(0,d.actions)
        val loop=TaskLoop(Device(),Model(),{clock},"搜索",maxSteps=1);loop.step();assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
    }
}
