package dev.droiduse.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One serialized worker runs step(); stop/pause can be signalled from the UI thread.
 * Acknowledged input is never task success: completion requires a fresh observation and verifier.
 */
class TaskLoop(private val executor: Executor, private val model: Model, private val now: () -> Long,
               private val task: String, private val maxSteps: Int = 30,
               private val deadline: Long = now() + 180_000,
               private val maxFrameAgeMs: Long = 5000,
               private val trace: (String, Long) -> Unit = { _, _ -> },
               private val audit: (org.json.JSONObject) -> Unit = {},
               private val retainForUser: Boolean = false) {
    data class Frame(val id: String, val display: Int, val width: Int, val height: Int,
                     val rotation: Int, val capturedAt: Long, val app: String,
                     val pngBase64: String, val editorGeneration: Long? = null, val contextText: String = "",
                     val completionReady: Boolean = true,
                     val supportedActions: Set<String> = setOf("tap","swipe","back","wait") +
                         if(editorGeneration != null) setOf("text") else emptySet(),
                     val targets: List<ObservedTarget> = emptyList())
    data class Point(val x: Int,val y: Int)
    sealed interface Action {
        data class Device(val operation: DeviceOperation,val value: Int=0) : Action
        data class PickFile(val x: Int,val y: Int) : Action
        data class Target(val operation: TargetOperation,val targetId: String) : Action
        data class MultiTouch(val fingers: List<List<Point>>,val durationMs: Int) : Action
        data class Tap(val x: Int, val y: Int, val target: String? = null) : Action
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : Action
        data class DoubleTap(val x: Int,val y: Int) : Action
        data class LongPress(val x: Int,val y: Int,val durationMs: Int) : Action
        data class Drag(val x1: Int,val y1: Int,val x2: Int,val y2: Int,val holdMs: Int,val durationMs: Int) : Action
        data class Text(val value: String, val editorGeneration: Long) : Action
        data class Edit(val operation: EditorOperation,val editorGeneration: Long,
            val start: Int=0,val end: Int=0,val before: Int=0,val after: Int=0) : Action
        data object Back : Action
        data class Wait(val durationMs: Int) : Action
        data object ReadChapters : Action
    }
    sealed interface Decision {
        data class Act(val action: Action) : Decision
        data class Finish(val claim: String) : Decision
        data class AskUser(val reason: String) : Decision
    }
    data class Verification(val passed: Boolean, val frameId: String, val evidence: String)
    enum class Outcome { EXECUTED, STALE_OBSERVATION, UNSUPPORTED, UNKNOWN_OUTCOME, ISOLATION_LOST }
    enum class State { NEW, RUNNING, PAUSED, COMPLETED, NEEDS_USER, STOPPED, FAILED }
    interface Executor {
        fun begin(): Boolean
        fun observe(): Frame
        fun submit(requestId: String, frame: Frame, action: Action): Outcome
        /** Must be callable concurrently with observe/submit. */
        fun cancel()
        /** Signal without blocking the UI; a bulk operation must cooperate. */
        fun setPaused(paused: Boolean) {}
        /** Read-only guard, including while paused; must not capture or inject input. */
        fun checkHealth() {}
    }
    interface Model {
        fun actionOutcome(outcome: Outcome) {}
        fun decide(task: String, frame: Frame): Decision
        fun verify(task: String, claim: String, frame: Frame): Verification
        fun cancel()
    }
    enum class Failure { HEALTH_CHECK_FAILED, AUDIT_FAILURE, MODEL_TIMEOUT, EXECUTOR_TIMEOUT, NETWORK_FAILURE, EXECUTOR_DISCONNECTED, INVALID_RESULT }
    @Volatile var failure: Failure? = null; private set
    private var stage = "begin"
    @Volatile var state = State.NEW; private set
    @Volatile var message = "尚未开始"; private set
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var steps = 0
    private var pendingRequest: String? = null
    private val seenFrames = mutableSetOf<String>()
    private var closed = false
    private var begun = false
    @Volatile private var resumePending = false
    @Volatile var awaitingUser = false; private set
    init { require(task.isNotBlank() && task.length <= 4000 && maxSteps in 1..100) }
    fun pause() { paused.set(true); executor.setPaused(true) }
    fun resume() {
        check(!closed && !cancelled.get()) { "Session is closed" }
        if(awaitingUser || paused.get()) resumePending=true
        if(awaitingUser) { awaitingUser=false;state=State.RUNNING;failure=null }
        executor.setPaused(false); paused.set(false)
    }
    fun requestStop() { cancelled.set(true) }
    fun stop() {
        requestStop()
        model.cancel()
        // Do not wait behind an in-flight model call or input operation.
        try { executor.cancel() } catch (_: Exception) { }
    }
    fun step(): State {
        if(!awaitingUser && state in setOf(State.COMPLETED,State.NEEDS_USER,State.STOPPED,State.FAILED)) return state
        if(cancelled.get()) return finish(State.STOPPED,"已停止；未自动重试任何动作",false)
        if(begun && !closed) try { executor.checkHealth() }
        catch(error: ResourcePressureException) {
            return finish(State.NEEDS_USER,"资源保护已暂停任务：${error.reason}；已释放会话，未重发动作",false)
        } catch(error: Exception) {
            failure=if(error is java.io.IOException) Failure.EXECUTOR_DISCONNECTED else Failure.HEALTH_CHECK_FAILED
            return finish(State.FAILED,"${failure!!.name}：会话健康检查失败，已停止",false)
        }
        if(awaitingUser) {
            if(cancelled.get()) return finish(State.STOPPED,"已停止",false)
            if(now()>=deadline) return finish(State.FAILED,"接管等待超时，已释放会话",false)
            return state
        }
        if (state in setOf(State.COMPLETED, State.NEEDS_USER, State.STOPPED, State.FAILED)) return state
        if (cancelled.get()) return finish(State.STOPPED,"已停止；未自动重试任何动作")
        if (now() >= deadline) return finish(State.FAILED,"任务超时，已停止")
        if (paused.get()) { state = State.PAUSED; message = "已暂停"; return state }
        try {
            if (!begun) {
                if (!measured("begin") { executor.begin() }) return finish(State.FAILED,"系统隔离未就绪，未调用模型或操作手机")
                begun = true
            }
            if (interrupted()) return state
            state = State.RUNNING
            if (++steps > maxSteps) return finish(State.NEEDS_USER,"已达到步骤上限，需要人工检查")
            val frame = measured("observe") { executor.observe() }
            validateFresh(frame)
            emit("OBSERVED",org.json.JSONObject().put("frameId",frame.id).put("afterRequestId",pendingRequest ?: org.json.JSONObject.NULL))
            pendingRequest=null
            if(resumePending) {
                emit("RESUMED_FROM_FRESH_OBSERVATION",org.json.JSONObject().put("frameId",frame.id))
                resumePending=false
            }
            if (interrupted()) return state
            val decision = measured("model_decide") { model.decide(task,frame) }
            if (interrupted()) return state
            when (decision) {
                is Decision.AskUser -> return finish(State.NEEDS_USER,decision.reason.take(2000))
                is Decision.Finish -> {
                    require(decision.claim.isNotBlank())
                    if(!frame.completionReady) return finish(State.NEEDS_USER,"执行证据未齐全，不能确认完成")
                    val verificationFrame = measured("observe") { executor.observe() }; validateFresh(verificationFrame)
                    emit("VERIFICATION_FRAME",org.json.JSONObject().put("frameId",verificationFrame.id))
                    require(verificationFrame.completionReady)
                    if (interrupted()) return state
                    val verification = measured("model_verify") { model.verify(task,decision.claim,verificationFrame) }
                    if (interrupted()) return state
                    return if (verification.passed && verification.frameId == verificationFrame.id && verification.evidence.isNotBlank())
                        finish(State.COMPLETED,"${decision.claim}\n\n模型复核：${verification.evidence.take(2000)}")
                    else finish(State.NEEDS_USER,"结果未通过新画面验证，请人工检查")
                }
                is Decision.Act -> {
                    if(ActionCatalog.kind(decision.action) !in frame.supportedActions)
                        return finish(State.NEEDS_USER,"当前后端未声明支持此动作，未执行")
                    validateAction(frame,decision.action)
                    // Execution service rechecks this same frame and live policy before dispatch.
                    if (now() - frame.capturedAt > maxFrameAgeMs) {
                        model.actionOutcome(Outcome.STALE_OBSERVATION)
                        emit("REOBSERVE_REQUIRED",org.json.JSONObject().put("frameId",frame.id).put("reason","FRAME_EXPIRED_BEFORE_SUBMIT"))
                        message = "观察已过期，重新观察"; return state
                    }
                    val requestId=UUID.randomUUID().toString()
                    emit("ACTION_PENDING",org.json.JSONObject().put("requestId",requestId).put("beforeFrameId",frame.id).put("action",ActionCatalog.kind(decision.action)))
                    // Keep the correlation even if input reached the device but its reply was lost.
                    pendingRequest=requestId
                    val outcome=measured("action") { executor.submit(requestId,frame,decision.action) }
                    model.actionOutcome(outcome)
                    emit("ACTION_RESULT",org.json.JSONObject().put("requestId",requestId).put("outcome",outcome.name))
                    if(outcome!=Outcome.EXECUTED && outcome!=Outcome.UNKNOWN_OUTCOME) pendingRequest=null
                    when (outcome) {
                        Outcome.EXECUTED -> message = "动作已执行，下一步重新观察；尚未确认任务完成"
                        Outcome.STALE_OBSERVATION -> {
                            emit("REOBSERVE_REQUIRED",org.json.JSONObject().put("frameId",frame.id).put("reason","BACKEND_REJECTED_STALE_FRAME"))
                            message = "画面变化，重新观察"
                        }
                        Outcome.UNKNOWN_OUTCOME -> return finish(State.NEEDS_USER,"动作结果不明，已停止，未重发")
                        Outcome.UNSUPPORTED -> return finish(State.NEEDS_USER,"此动作尚不支持")
                        Outcome.ISOLATION_LOST -> return finish(State.FAILED,"隔离保护失效，已停止")
                    }
                }
            }
        } catch (error: Exception) {
            if(error is HandoffRequestedException) { paused.set(true);state=State.PAUSED;message="已暂停，可人工接管";return state }
            if(error is AuditWriteException) { failure=Failure.AUDIT_FAILURE;return finish(State.NEEDS_USER,"审计记录保存失败，停止并等待人工检查") }
            if(error is SensitiveSceneException) return finish(State.NEEDS_USER,"检测到敏感页面，未保存当前截图和文字、未交给模型；需要人工处理")
            if(error is ResourcePressureException) return finish(State.NEEDS_USER,
                "资源保护已暂停任务：${error.reason}；请待设备恢复后检查现场，未重发动作",false)
            failure = when {
                error is java.net.SocketTimeoutException -> if(stage.startsWith("model")) Failure.MODEL_TIMEOUT else Failure.EXECUTOR_TIMEOUT
                error is java.io.IOException -> if(stage.startsWith("model")) Failure.NETWORK_FAILURE else Failure.EXECUTOR_DISCONNECTED
                else -> Failure.INVALID_RESULT
            }
            try {
                emit("STAGE_FAILED",org.json.JSONObject().put("stage",stage).put("failure",failure!!.name))
                if(stage=="action" && pendingRequest!=null) {
                    emit("ACTION_RESULT",org.json.JSONObject().put("requestId",pendingRequest)
                        .put("outcome",Outcome.UNKNOWN_OUTCOME.name))
                    return finish(if(cancelled.get()) State.STOPPED else State.NEEDS_USER,
                        "${failure!!.name}：动作回执未确认，结果未知；已暂停，未重发，请检查现场")
                }
            } catch (_: AuditWriteException) {
                failure=Failure.AUDIT_FAILURE
                return finish(State.NEEDS_USER,"审计记录保存失败，停止并等待人工检查",false)
            }
            return finish(if(cancelled.get()) State.STOPPED else State.FAILED,
                "${failure!!.name}：连接或结果校验失败，已停止，未重发动作")
        }
        return state
    }
    private fun emit(event: String,data: org.json.JSONObject=org.json.JSONObject()) {
        try { audit(data.put("event",event).put("step",steps).put("atMs",now())) }
        catch (_: Exception) { throw AuditWriteException() }
    }
    private fun <T> measured(name: String, block: () -> T): T {
        stage=name
        val start=now()
        try { return block() } finally { try { trace(name,now()-start) } catch (_: Exception) { } }
    }
    private fun interrupted(): Boolean {
        if(cancelled.get()) { finish(State.STOPPED,"已停止"); return true }
        if(now() >= deadline) { finish(State.FAILED,"任务超时"); return true }
        if(paused.get()) { state=State.PAUSED; message="已暂停，恢复后重新观察"; return true }
        return false
    }
    private fun validateFresh(frame: Frame) {
        ObservedTarget.validate(frame.targets)
        require(seenFrames.add(frame.id)) { "reused observation frame" }
        require(frame.id.isNotBlank() && frame.display > 0 && frame.width in 1..8192 && frame.height in 1..8192)
        require(frame.rotation in 0..3 && frame.app.isNotBlank() && frame.pngBase64.isNotBlank())
        require(frame.capturedAt <= now() && now()-frame.capturedAt <= maxFrameAgeMs)
    }
    fun validateAction(f: Frame,a: Action) {
        require(f.display>0 && f.width in 1..8192 && f.height in 1..8192 && f.rotation in 0..3 && f.capturedAt<=now())
        require(ActionCatalog.kind(a) in f.supportedActions)
        fun point(x: Int,y: Int) { require(x in 0 until f.width && y in 0 until f.height) }
        when(a) {
            is Action.Device -> require(a.value in a.operation.range)
            is Action.PickFile -> point(a.x,a.y)
            is Action.Target -> require(ObservedTarget.accepts(f,a))
            is Action.Tap -> point(a.x,a.y)
            is Action.MultiTouch -> {
                require(a.fingers.size==2 && a.durationMs in 100..3000)
                val count=a.fingers.first().size
                require(count in 2..32 && a.fingers.all { it.size==count })
                a.fingers.flatten().forEach { point(it.x,it.y) }
            }
            is Action.DoubleTap -> point(a.x,a.y)
            is Action.LongPress -> { point(a.x,a.y);require(a.durationMs in 500..3000) }
            is Action.Drag -> { point(a.x1,a.y1);point(a.x2,a.y2);require(a.holdMs in 0..1500 && a.durationMs in 100..3000) }
            is Action.Swipe -> { point(a.x1,a.y1); point(a.x2,a.y2); require(a.durationMs in 100..2000) }
            is Action.Edit -> {
                require(a.editorGeneration>0 && a.editorGeneration==f.editorGeneration)
                when(a.operation) {
                    EditorOperation.SELECT -> require(a.start in 0..16384 && a.end in a.start..16384 && a.before==0 && a.after==0)
                    EditorOperation.DELETE -> require(a.before in 0..4096 && a.after in 0..4096 && a.before+a.after>0 && a.start==0 && a.end==0)
                    else -> require(a.start==0 && a.end==0 && a.before==0 && a.after==0)
                }
            }
            is Action.Text -> { require(a.value.isNotEmpty() && a.value.length <= 16384 && Charsets.UTF_8.newEncoder().canEncode(a.value)); require(a.editorGeneration > 0 && a.editorGeneration == f.editorGeneration) }
            Action.Back -> Unit
            is Action.Wait -> require(a.durationMs in 200..2000)
            Action.ReadChapters -> require(f.contextText.isNotBlank())
        }
    }
    private fun finish(next: State,text: String,keepSession: Boolean = true): State {
        state=next; message=text
        try { emit("TASK_STATE",org.json.JSONObject().put("state",next.name).put("failure",failure?.name ?: org.json.JSONObject.NULL)) }
        catch (_: Exception) { failure=Failure.AUDIT_FAILURE;state=State.NEEDS_USER;message="审计记录保存失败，停止并等待人工检查" }
        awaitingUser=retainForUser && keepSession && state==State.NEEDS_USER && failure!=Failure.AUDIT_FAILURE
        if(awaitingUser) { paused.set(true);executor.setPaused(true) }
        if(!closed && !awaitingUser) {
            closed=true
            try { executor.cancel() } catch (_: Exception) { message += "；执行服务未确认清理" }
        }
        return state
    }
}

class HandoffRequestedException : IllegalStateException("HANDOFF_REQUESTED")
class AuditWriteException : IllegalStateException("AUDIT_WRITE_FAILED")
