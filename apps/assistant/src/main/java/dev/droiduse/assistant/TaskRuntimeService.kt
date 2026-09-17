package dev.droiduse.assistant

import android.app.*
import android.content.*
import android.os.*
import dev.droiduse.agent.*
import dev.droiduse.ipc.IExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** The service owns the worker; an Activity only observes and controls it. */
class TaskRuntimeService : Service() {
    data class Snapshot(val running: Boolean = false, val paused: Boolean = false,
        val message: String = "尚未开始", val result: String = "",
        val manual: Boolean = false,val manualImage: String? = null,val manualBusy: Boolean = false)
    inner class LocalBinder : Binder() { val runtime get() = this@TaskRuntimeService }
    private val mutable = MutableStateFlow(Snapshot())
    val snapshots = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: TaskLoop? = null
    private var phoneExecutor: PhoneTaskExecutor? = null
    private var manualFrame: TaskLoop.Frame? = null
    private val operation = Mutex()
    private var journalFile: File?=null
    private fun handoffEvent(event: String) {
        try { journalFile?.appendText(JSONObject().put("event",event).put("atMs",SystemClock.elapsedRealtime()).toString()+"\n") }
        catch (_: Exception) { stopTask() }
    }
    private var worker: Job? = null
    private var rom: IExecutor? = null
    private var bound = false
    private val checkpoint by lazy { File(noBackupFilesDir, "runtime-state.json") }
    private var runId: String?=null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { rom = IExecutor.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { rom = null; stopTask() }
        override fun onBindingDied(name: ComponentName) { rom = null; stopTask() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tasks", "后台任务", NotificationManager.IMPORTANCE_LOW))
        val previous = runCatching { JSONObject(checkpoint.readText()) }.getOrNull()
        runId=previous?.optString("runId")?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
        if (previous?.optBoolean("running") == true || previous?.optString("phase") == "INTERRUPTED") {
            val progress=runId?.let { id -> runCatching {
                val file=File(noBackupFilesDir,"task-checkpoint-$id.json");require(file.length() in 1..8192)
                TaskCheckpoint.parse(JSONObject(file.readText()))
            }.getOrNull() }
            mutable.value = Snapshot(message = "上次任务因进程退出而中断；未重放任何动作。"+
                (progress?.interruptedSummary() ?: "没有可验证的步骤检查点。")+"请检查现场后重新发起。")
            persist("INTERRUPTED")
        }
        bound = runCatching { bindService(Intent().setComponent(ComponentName("dev.droiduse.executor",
            "dev.droiduse.executor.app.ExecutorService")), connection, BIND_AUTO_CREATE) }.getOrDefault(false)
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopTask() else {
            foreground()
            if (worker?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun foreground() {
        val stop = PendingIntent.getService(this, 1, Intent(this, TaskRuntimeService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(31, Notification.Builder(this, "tasks").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DroidUse 正在执行后台任务").setContentText("点此查看，或停止任务")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build())
    }
    fun runTask(profile: ModelProfile, task: String, phone: Boolean, pureVision: Boolean) {
        if (worker?.isActive == true) return
        require(!phone || BuildConfig.DEBUG)
        val executor = if (phone) PhoneTaskExecutor(applicationContext, pureVision)
            else BinderTaskExecutor(requireNotNull(rom) { "执行服务未连接" })
        phoneExecutor=executor as? PhoneTaskExecutor
        foreground()
        val started = SystemClock.elapsedRealtime()
        runId=started.toString()
        val progressFile=android.util.AtomicFile(File(noBackupFilesDir,"task-checkpoint-$started.json"))
        var progress=TaskCheckpoint()
        val metrics = File(noBackupFilesDir, "task-metrics-$started.jsonl")
        val decisions = File(noBackupFilesDir, "task-decisions-$started.jsonl")
        val record: (String) -> Unit = {
            try { decisions.appendText(TracePrivacy.modelRecord(it) + "\n") }
            catch (_: Exception) { throw dev.droiduse.agent.AuditWriteException() }
        }
        val journal=File(noBackupFilesDir,"task-journal-$started.jsonl");journalFile=journal
        val model = if (pureVision) PureVisionTaskModel(profile, record) else VisionTaskModel(profile, record)
        val current = TaskLoop(executor, model, SystemClock::elapsedRealtime, task,
            maxSteps = if (pureVision) 100 else 50,
            deadline = started + if (pureVision) 600000 else 240000,
            maxFrameAgeMs = if (phone) 30000 else 5000,
            trace = { kind, ms -> metrics.appendText(JSONObject().put("kind", kind).put("ms", ms)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).toString() + "\n") },
            audit = {
                journal.appendText(it.toString()+"\n")
                val next=progress.advance(it)
                var stream: java.io.FileOutputStream?=null
                try {
                    stream=progressFile.startWrite()
                    stream.write(next.json().toString().toByteArray())
                    progressFile.finishWrite(stream)
                    progress=next
                } catch(error: Exception) {
                    if(stream!=null) progressFile.failWrite(stream)
                    throw error
                }
            },retainForUser=phone)
        loop = current
        mutable.value = Snapshot(running = true, message = "正在检查隔离条件…")
        persist("STARTING")
        worker = scope.launch {
            try {
                while (isActive) {
                    val phase = operation.withLock { withContext(Dispatchers.IO) { current.step() } }
                    val running = current.awaitingUser || phase in setOf(TaskLoop.State.NEW, TaskLoop.State.RUNNING, TaskLoop.State.PAUSED)
                    mutable.value = mutable.value.copy(running = running, message = current.message,
                        result = if (running) "" else current.message)
                    if(phase!=TaskLoop.State.PAUSED && !current.awaitingUser) persist(phase.name)
                    if (!running) {
                        withContext(Dispatchers.IO) {
                            File(noBackupFilesDir, "task-result-$started.json").writeText(JSONObject()
                                .put("state", phase.name).put("message", if(TracePrivacy.sensitiveScene(current.message)) "结果涉及敏感信息，内容未写入日志" else TracePrivacy.redactKnownSecrets(current.message,listOf(profile.apiKey)))
                                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                                .put("backend", if (pureVision) "PHONE_PURE_VISION" else if (phone) "PHONE_SHELL_EXPERIMENT" else "ROM").toString())
                        }
                        break
                    }
                    delay(if (phase == TaskLoop.State.PAUSED || current.awaitingUser) 200 else 50)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { current.stop() }
                loop = null;phoneExecutor=null;manualFrame=null
                mutable.value = mutable.value.copy(running = false, paused = false,manual=false,manualImage=null,manualBusy=false)
                persist(current.state.name)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    fun enterHandoff() {
        val current=loop ?: return
        val executor=phoneExecutor ?: return
        if(mutable.value.manualBusy || mutable.value.manual) return
        current.pause();executor.requestHandoff();handoffEvent("HANDOFF_REQUESTED")
        mutable.value=mutable.value.copy(paused=true,manual=true,manualBusy=true)
        refreshHandoff()
    }
    private fun refreshHandoff(action: JSONObject? = null) {
        scope.launch {
            try {
                operation.withLock {
                    val executor=phoneExecutor ?: return@withLock
                    withContext(Dispatchers.IO) {
                        if(action!=null) check(executor.manualInput(requireNotNull(manualFrame),action))
                        manualFrame=executor.manualObserve()
                    }
                    mutable.value=mutable.value.copy(manualImage=manualFrame?.pngBase64,manualBusy=false)
                }
            } catch (_: Exception) {
                mutable.value=mutable.value.copy(manualBusy=false,manualImage=null,message="接管画面或操作无法确认；请刷新检查，未重发动作")
            }
        }
    }
    fun handoffAction(action: JSONObject?) {
        if(!mutable.value.manual || mutable.value.manualBusy) return
        mutable.value=mutable.value.copy(manualBusy=true)
        refreshHandoff(action)
    }
    fun returnToAi() {
        if(!mutable.value.manual || mutable.value.manualBusy) return
        scope.launch { operation.withLock {
            phoneExecutor?.leaveHandoff();manualFrame=null;handoffEvent("HANDOFF_RETURNED")
            val current=loop ?: return@withLock
            if(!runCatching { current.resume() }.isSuccess) return@withLock
            mutable.value=mutable.value.copy(manual=false,manualImage=null,paused=false,message="已交回 AI，先重新观察")
        } }
    }
    fun togglePause() {
        if(mutable.value.manual) return
        val current = loop ?: return
        val pause = !mutable.value.paused
        if (pause) current.pause() else if(!runCatching { current.resume() }.isSuccess) return
        mutable.value = mutable.value.copy(paused = pause)
    }
    fun stopTask() {
        loop?.let { current -> current.requestStop(); scope.launch(Dispatchers.IO) { current.stop() } }
        if (loop == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun persist(phase: String) {
        // Deliberately excludes task text, credentials and model content.
        val atomic = android.util.AtomicFile(checkpoint)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(JSONObject().put("running", mutable.value.running).put("phase", phase)
                .put("runId",runId ?: JSONObject.NULL)
                .put("failure", loop?.failure?.name ?: JSONObject.NULL).put("updatedAt", System.currentTimeMillis()).toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (_: Exception) { if (stream != null) atomic.failWrite(stream) }
    }
    override fun onDestroy() {
        loop?.let { current -> current.requestStop(); scope.launch(NonCancellable + Dispatchers.IO) { current.stop() } }
        if (bound) unbindService(connection)
        scope.cancel()
        super.onDestroy()
    }
}
