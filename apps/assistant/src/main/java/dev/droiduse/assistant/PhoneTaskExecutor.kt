package dev.droiduse.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import dev.droiduse.agent.ReadingProgress
import dev.droiduse.agent.TaskLoop
import dev.droiduse.agent.TextTarget
import dev.droiduse.agent.ResourcePolicy
import dev.droiduse.agent.ResourcePressureException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** APK-owned OCR, navigation evidence and bulk reading. Transport stays entirely on the phone. */
class PhoneTaskExecutor(context: Context, private val pureVision: Boolean = false) : TaskLoop.Executor {
    private val token=context.assets.open("bridge-token").bufferedReader().use { it.readText().trim() }
    private val stopped=AtomicBoolean(false)
    private val paused=AtomicBoolean(false)
    private val handoff=AtomicBoolean(false)
    private val sessionLock=Any()
    private var session: String?=null
    private var backendActions: Set<String> = emptySet()
    private val leaseWorker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task,"phone-session-lease").apply { isDaemon=true }
    }
    private val leaseLost=AtomicBoolean(false)
    private val ocrKind=OcrKind.selected(context)
    private val ocr: OcrEngine?=if(pureVision) null else when(ocrKind) {
        OcrKind.PADDLE_TINY -> PaddleTinyOcr(context)
        OcrKind.ML_KIT -> PhoneOcr()
    }
    private val budgetMs=if(pureVision) 600000L else 240000L
    private val started=SystemClock.elapsedRealtime()
    private val directory=File(context.noBackupFilesDir,"phone-run-$started").apply { mkdirs() }
    private val resources=RuntimeResourceGuard(context)
    private var resourceCheckAt=0L
    private var resourceDecision=ResourcePolicy.Decision()
    private var previousResourceDecision: ResourcePolicy.Decision?=null
    private var lastCaptureAt=0L
    private var afterInput: String?=null
    private var previousPixels: String?=null
    private var previousApp: String?=null
    private fun sceneEvent(event: String, frameId: String) {
        File(directory,"scene-events.jsonl").appendText(JSONObject().put("event",event)
            .put("frameId",frameId).put("elapsedMs",SystemClock.elapsedRealtime()-started).toString()+"\n")
    }
    private var sequence=0
    private val navigation=ArrayDeque<JSONObject>()
    private var reading=JSONObject().put("complete",false)
    private var readingStarted=false
    private var latest: Observation?=null
    private data class Observation(val frame: TaskLoop.Frame,val rows: List<ReadingProgress.Row>,val targets: List<ReadingProgress.Row>,val file: File)
    init { check(BuildConfig.DEBUG) }
    private fun call(path: String,body: JSONObject=JSONObject()): JSONObject {
        val http=URI("http://127.0.0.1:18765$path").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.connectTimeout=3000;http.readTimeout=25000;http.doOutput=true;http.instanceFollowRedirects=false
            http.setRequestProperty("Authorization","Bearer $token");http.setRequestProperty("Content-Type","application/json")
            http.outputStream.use { it.write(body.toString().toByteArray()) }
            val status=http.responseCode
            if(status!=200) {
                val response=http.errorStream?.use { it.readNBytes(2049) }
                val reported=if(response!=null && response.size<=2048) runCatching { JSONObject(String(response,Charsets.UTF_8)).optString("error") }.getOrNull() else null
                // Only literal protocol reasons can be logged; never persist arbitrary response text.
                val reason=reported?.takeIf { it in setOf("OBSERVATION_APP_CHANGED_OR_UNKNOWN","TARGET_APP_NOT_RESUMED",
                    "SESSION_NOT_READY","HOST_PROTECTION_LOST","HOST_EXITED","HOST_TIMEOUT","PHONE_LOCKED",
                    "APP_USED_ON_MAIN_DISPLAY","INVALID_SESSION","CLIENT_LEASE_EXPIRED") } ?: "REQUEST_REJECTED"
                File(directory,"transport-error.json").writeText(JSONObject().put("path",path).put("status",status).put("reason",reason).toString())
                error("手机执行进程拒绝请求 HTTP $status")
            }
            val bytes=http.inputStream.use { it.readNBytes(8_388_609) };require(bytes.size<=8_388_608)
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { http.disconnect() }
    }
    private fun body()=synchronized(sessionLock) { JSONObject().put("sessionId",requireNotNull(session)) }
    private fun <T> measure(kind: String,operation: () -> T): T {
        val start=SystemClock.elapsedRealtime()
        try { return operation() } finally {
            File(directory,"metrics.jsonl").appendText(JSONObject().put("kind",kind).put("ms",SystemClock.elapsedRealtime()-start).put("elapsedMs",SystemClock.elapsedRealtime()-started).toString()+"\n")
        }
    }
    private fun checkResources() {
        val now=SystemClock.elapsedRealtime()
        if(now-resourceCheckAt>=500) {
            resourceCheckAt=now
            val sample=resources.sample()
            resourceDecision=ResourcePolicy.evaluate(sample)
            if(resourceDecision!=previousResourceDecision) {
                File(directory,"resource-events.jsonl").appendText(JSONObject()
                    .put("elapsedMs",now-started).put("thermal",sample.thermal)
                    .put("batteryPercent",sample.batteryPercent ?: JSONObject.NULL)
                    .put("lowMemory",sample.lowMemory).put("trimCritical",sample.trimCritical)
                    .put("pauseReason",resourceDecision.pauseReason ?: JSONObject.NULL)
                    .put("captureIntervalMs",resourceDecision.captureIntervalMs).toString()+"\n")
                previousResourceDecision=resourceDecision
            }
        }
        resourceDecision.pauseReason?.let { throw ResourcePressureException(it) }
    }
    private fun active() {
        if(handoff.get()) throw dev.droiduse.agent.HandoffRequestedException()
        if(leaseLost.get()) throw java.io.IOException("EXECUTOR_LEASE_LOST")
        checkResources()
        while(paused.get()) { if(handoff.get()) throw dev.droiduse.agent.HandoffRequestedException();checkResources();check(!stopped.get());check(SystemClock.elapsedRealtime()-started<budgetMs-5000);Thread.sleep(50) }
        check(!stopped.get());check(SystemClock.elapsedRealtime()-started<budgetMs-5000)
    }
    private fun waitFor(ms: Int) {
        val until=SystemClock.elapsedRealtime()+ms
        while(SystemClock.elapsedRealtime()<until) { active();Thread.sleep(minOf(50L,(until-SystemClock.elapsedRealtime()).coerceAtLeast(1))) }
    }
    override fun begin(): Boolean=synchronized(sessionLock) {
        active();val reply=call("/begin",JSONObject().put("budgetMs",budgetMs));session=reply.getString("sessionId")
        val advertised=reply.optJSONArray("supportedActions") ?: JSONArray()
        backendActions=(0 until advertised.length()).map { advertised.getString(it) }.toSet()
            .intersect(setOf("tap","swipe","back","double_tap","long_press","drag","multi_touch"))
        if(stopped.get()) { cancel();return false }
        leaseWorker.scheduleWithFixedDelay({
            if(!stopped.get()) try { call("/heartbeat",body()) }
            catch (_: Exception) { if(!stopped.get()) leaseLost.set(true) }
        },3,3,java.util.concurrent.TimeUnit.SECONDS)
        reply.getBoolean("ready") && reply.getString("backend")=="PHONE_SHELL_EXPERIMENT"
    }
    private fun rowJson(row: ReadingProgress.Row)=JSONObject().put("text",row.text).put("x",row.x).put("y",row.y).put("confidence",row.confidence ?: JSONObject.NULL)
        .put("left",row.left).put("top",row.top).put("right",row.right).put("bottom",row.bottom)
    private fun capture(): Observation {
        active()
        val remaining=resourceDecision.captureIntervalMs-(SystemClock.elapsedRealtime()-lastCaptureAt)
        if(remaining>0) waitFor(remaining.toInt())
        lastCaptureAt=SystemClock.elapsedRealtime()
        val f=measure("phone_capture") { call("/observe",body()) }
        val encoded=f.getString("pngBase64");require(encoded.length<=4_194_304)
        val bytes=Base64.decode(encoded,Base64.DEFAULT)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth==720 && bounds.outHeight==1280 && bounds.outMimeType=="image/png")
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        val recognized=try { if(pureVision) PhoneOcr.Result(emptyList(),emptyList()) else measure("phone_ocr") { requireNotNull(ocr).recognize(bitmap) } }
            finally { bitmap.recycle() }
        active()
        val text=recognized.rows.joinToString("\n") { it.text }
        if(dev.droiduse.agent.TracePrivacy.sensitiveScene(text)) {
            File(directory,"scene-events.jsonl").appendText(JSONObject().put("event","SENSITIVE_SCENE").put("elapsedMs",SystemClock.elapsedRealtime()-started).toString()+"\n")
            throw dev.droiduse.agent.SensitiveSceneException()
        }
        val app=f.getString("app")
        require(app.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        if(previousApp!=null && previousApp!=app) {
            File(directory,"scene-events.jsonl").appendText(JSONObject().put("event","APP_CHANGED")
                .put("frameId",f.getString("id")).put("fromApp",previousApp).put("toApp",app)
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).toString()+"\n")
        }
        previousApp=app
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if(afterInput!=null && fingerprint==previousPixels) sceneEvent("PIXELS_UNCHANGED_AFTER_ACTION",f.getString("id"))
        if(!pureVision && text.isBlank()) sceneEvent("OCR_EMPTY",f.getString("id"))
        if(text.contains("加载失败") || text.contains("网络异常")) sceneEvent("LOAD_ERROR_TEXT_VISIBLE",f.getString("id"))
        File(directory,"frame-links.jsonl").appendText(JSONObject().put("frameId",f.getString("id"))
            .put("afterRequestId",afterInput ?: JSONObject.NULL).put("screenshotSaved",!pureVision).toString()+"\n")
        afterInput=null;previousPixels=fingerprint
        val file=File(directory,"%04d.png".format(sequence++))
        // Pure vision has no local sensitivity evidence: do not persist raw pixels in that mode.
        if(!pureVision) file.writeBytes(bytes)
        val rows=JSONArray(recognized.rows.map(::rowJson));val targets=JSONArray(recognized.targets.map(::rowJson))
        File(directory,file.nameWithoutExtension+".json").writeText(JSONObject().put("frameId",f.getString("id")).put("app",app).put("engine",if(pureVision) "NONE" else ocrKind.name).put("text",text).put("rows",rows).put("targets",targets).toString())
        if(!readingStarted && text.isNotBlank()) {
            navigation.addLast(JSONObject().put("text",text.take(6000)).put("evidence",file.name));while(navigation.size>20) navigation.removeFirst()
        }
        val context=JSONObject().put("backend","PHONE_SHELL_EXPERIMENT").put("pureVision",pureVision).put("screenshot",file.name).put("ocr",targets).put("navigation",JSONArray(navigation.toList())).put("reading",reading)
        val frame=TaskLoop.Frame(f.getString("id"),f.getInt("display"),720,1280,0,f.getLong("capturedAt"),f.getString("app"),encoded,
            contextText=context.toString(),completionReady=pureVision || reading.optBoolean("complete",false),
            supportedActions=backendActions + setOf("wait") + if(!pureVision && app=="com.dragon.read" && "swipe" in backendActions) setOf("read_chapters") else emptySet())
        return Observation(frame,recognized.rows,recognized.targets,file).also { latest=it }
    }
    override fun observe(): TaskLoop.Frame=capture().frame
    private fun dispatch(requestId: String,frame: TaskLoop.Frame,action: JSONObject): TaskLoop.Outcome {
        active()
        val log=File(directory,"actions.jsonl")
        val record=JSONObject(dev.droiduse.agent.TracePrivacy.modelRecord(JSONObject().put("reply",action).toString()))
        log.appendText(JSONObject().put("event","ACTION_PENDING").put("requestId",requestId).put("beforeFrameId",frame.id).put("action",record).toString()+"\n")
        val reply=measure("phone_input") { call("/action",body().put("requestId",requestId).put("frameId",frame.id).put("action",action)) }
        val code=reply.getString("code")
        log.appendText(JSONObject().put("event","ACTION_RESULT").put("requestId",requestId)
            .put("outcome",if(code in setOf("EXECUTED","STALE_OBSERVATION","UNSUPPORTED")) code else "UNKNOWN_OUTCOME").toString()+"\n")
        if(code=="EXECUTED") afterInput=requestId
        return when(code) {
            "EXECUTED" -> TaskLoop.Outcome.EXECUTED
            "STALE_OBSERVATION" -> TaskLoop.Outcome.STALE_OBSERVATION
            "UNSUPPORTED" -> TaskLoop.Outcome.UNSUPPORTED
            else -> TaskLoop.Outcome.UNKNOWN_OUTCOME
        }
    }
    override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome {
        active()
        if(dev.droiduse.agent.ActionCatalog.kind(action) !in frame.supportedActions) return TaskLoop.Outcome.UNSUPPORTED
        val observed=latest ?: return TaskLoop.Outcome.STALE_OBSERVATION
        if(observed.frame.id!=frame.id || SystemClock.elapsedRealtime()-frame.capturedAt>30000) return TaskLoop.Outcome.STALE_OBSERVATION
        if(action is TaskLoop.Action.ReadChapters) { if(pureVision) return TaskLoop.Outcome.UNSUPPORTED;readChapters();return TaskLoop.Outcome.EXECUTED }
        if(action is TaskLoop.Action.Wait) { waitFor(action.durationMs);return TaskLoop.Outcome.EXECUTED }
        val a=JSONObject()
        when(action) {
            is TaskLoop.Action.Tap -> {
                val target=(if(pureVision) null else action.target)?.let { name -> TextTarget.resolve(name,action.x,action.y,observed.targets)
                    ?: run { sceneEvent("TEXT_TARGET_MISSING_OR_AMBIGUOUS",frame.id);return TaskLoop.Outcome.UNSUPPORTED } }
                a.put("kind","tap").put("x",target?.x ?: action.x).put("y",target?.y ?: action.y)
            }
            is TaskLoop.Action.Swipe -> a.put("kind","swipe").put("x1",action.x1).put("y1",action.y1).put("x2",action.x2).put("y2",action.y2).put("durationMs",action.durationMs)
            is TaskLoop.Action.MultiTouch -> a.put("kind","multi_touch").put("durationMs",action.durationMs)
                .put("fingers",JSONArray(action.fingers.map { path -> JSONArray(path.map { JSONObject().put("x",it.x).put("y",it.y) }) }))
            is TaskLoop.Action.DoubleTap -> a.put("kind","double_tap").put("x",action.x).put("y",action.y)
            is TaskLoop.Action.LongPress -> a.put("kind","long_press").put("x",action.x).put("y",action.y).put("durationMs",action.durationMs)
            is TaskLoop.Action.Drag -> a.put("kind","drag").put("x1",action.x1).put("y1",action.y1).put("x2",action.x2).put("y2",action.y2).put("holdMs",action.holdMs).put("durationMs",action.durationMs)
            TaskLoop.Action.Back -> a.put("kind","back")
            else -> return TaskLoop.Outcome.UNSUPPORTED
        }
        val result=dispatch(requestId,frame,a);if(result==TaskLoop.Outcome.EXECUTED) waitFor(400)
        return result
    }
    private fun readChapters() {
        readingStarted=true;val progress=ReadingProgress();val pages=JSONArray()
        try {
            for(index in 0 until 140) {
                active();check(SystemClock.elapsedRealtime()-started<215000) { "阅读预算不足" }
                val observation=if(index==0) requireNotNull(latest) else capture()
                check(observation.frame.app=="com.dragon.read") { "READING_APP_CHANGED" }
                val complete=progress.accept(observation.rows)
                if(complete) {
                    reading=JSONObject().put("complete",true).put("chapters",pages).put("boundary",JSONObject().put("chapter",4).put("page",progress.page).put("evidence",observation.file.name))
                    File(directory,"reading.json").writeText(reading.toString());return
                }
                val text=observation.rows.joinToString("\n") { it.text }
                check(listOf("解锁本章","购买本章","验证码").none { text.contains(it) }) { "阅读需要人工处理" }
                pages.put(JSONObject().put("chapter",progress.chapter).put("page",progress.page).put("text",text).put("evidence",observation.file.name))
                reading=JSONObject().put("complete",false).put("chapters",pages)
                File(directory,"reading.json").writeText(reading.toString())
                val result=dispatch(UUID.randomUUID().toString(),observation.frame,JSONObject().put("kind","swipe").put("x1",620).put("y1",650).put("x2",100).put("y2",650).put("durationMs",160))
                check(result==TaskLoop.Outcome.EXECUTED) { "翻页结果不明，停止" };waitFor(250)
            }
            error("超过阅读页数上限")
        } catch(e: Exception) {
            File(directory,"reading-error.json").writeText(JSONObject().put("errorType",e.javaClass.simpleName).put("page",progress.page).put("chapter",progress.chapter).toString());throw e
        }
    }
    /** Caller serializes these operations with TaskLoop.step; no OCR, model, or disk logging. */
    fun requestHandoff() { handoff.set(true);paused.set(true) }
    fun leaveHandoff() { handoff.set(false);latest=null;afterInput=null;previousPixels=null }
    fun manualObserve(): TaskLoop.Frame {
        check(handoff.get() && !stopped.get() && !leaseLost.get())
        checkResources()
        val f=call("/observe",body())
        return TaskLoop.Frame(f.getString("id"),f.getInt("display"),f.getInt("width"),f.getInt("height"),
            f.getInt("rotation"),f.getLong("capturedAt"),f.getString("app"),f.getString("pngBase64"))
    }
    fun manualInput(frame: TaskLoop.Frame, action: JSONObject): Boolean {
        check(handoff.get() && !stopped.get() && !leaseLost.get());checkResources()
        require(action.getString("kind") in setOf("tap","swipe","back"))
        return call("/action",body().put("requestId",UUID.randomUUID().toString()).put("frameId",frame.id)
            .put("action",action)).getString("code")=="EXECUTED"
    }
    override fun checkHealth() {
        if(stopped.get()) return
        if(leaseLost.get()) throw java.io.IOException("EXECUTOR_LEASE_LOST")
        checkResources()
    }
    override fun setPaused(paused: Boolean) { this.paused.set(paused) }
    override fun cancel() {
        stopped.set(true)
        leaseWorker.shutdownNow()
        try {
            synchronized(sessionLock) {
                if(session!=null) { try { call("/cancel",body()) } finally { session=null } }
            }
        } finally {
            try { ocr?.close() } finally { resources.close() }
        }
    }
}
