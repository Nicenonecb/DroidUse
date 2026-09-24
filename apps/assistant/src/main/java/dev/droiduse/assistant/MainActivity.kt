package dev.droiduse.assistant

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import dev.droiduse.agent.*
import dev.droiduse.ipc.IExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AssistantState(app: Application) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ProfileStore(app)
    var profiles by mutableStateOf(ProfileStore.State(emptyList(), null)); private set
    var message by mutableStateOf("正在连接执行服务…"); private set
    var result by mutableStateOf(""); private set
    var runtimeMessage by mutableStateOf(""); private set
    var manual by mutableStateOf(false); private set
    var manualImage by mutableStateOf<String?>(null); private set
    var manualBusy by mutableStateOf(false); private set
    var manualCanInput by mutableStateOf(false); private set
    var recoverable by mutableStateOf(false); private set
    fun handoffText(value: String) { runtime?.handoffText(value) }
    fun recoverTask() {
        if(taskRunning) return
        getApplication<Application>().startForegroundService(Intent(getApplication(),TaskRuntimeService::class.java))
        runtime?.recoverTask()
    }
    fun discardRecovery() { runtime?.discardRecovery() }
    fun enterHandoff() { runtime?.enterHandoff() }
    fun handoffAction(action: org.json.JSONObject?) { runtime?.handoffAction(action) }
    fun returnToAi() { runtime?.returnToAi() }
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var storageReady by mutableStateOf(false); private set
    var session by mutableStateOf(""); private set
    var paused by mutableStateOf(false); private set
    var ocrKind by mutableStateOf(OcrKind.selected(app)); private set
    fun selectOcr(kind: OcrKind) {
        if (taskRunning) return
        OcrKind.select(getApplication(), kind); ocrKind = kind
    }
    var allowGlobalSettings by mutableStateOf(GlobalSettingsPreference.enabled(app)); private set
    fun setGlobalSettings(enabled: Boolean) {
        if (taskRunning) return
        GlobalSettingsPreference.set(getApplication(), enabled); allowGlobalSettings = enabled
    }
    var task by mutableStateOf("")
    val targetApps = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName !in setOf(app.packageName, "dev.droiduse.executor") }
        .map { TargetApp(it.activityInfo.packageName, it.loadLabel(app.packageManager).toString()) }
        .distinctBy { it.packageName }.sortedBy { it.label }
    fun inferredTargetPackage(): String? = inferTargetPackage(task, targetApps)
    var romReady by mutableStateOf(false); private set
    var taskRunning by mutableStateOf(false); private set
    var taskPaused by mutableStateOf(false); private set
    private var runtime: TaskRuntimeService? = null
    private var runtimeBound = false
    private var runtimeObservation: Job? = null
    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as TaskRuntimeService.LocalBinder).runtime
            runtime = service
            runtimeObservation?.cancel()
            runtimeObservation = scope.launch {
                service.snapshots.collect { snapshot ->
                    taskRunning = snapshot.running; taskPaused = snapshot.paused
                    manual=snapshot.manual;manualImage=snapshot.manualImage;manualBusy=snapshot.manualBusy
                    manualCanInput=snapshot.manualCanInput;recoverable=snapshot.recoverable
                    if (snapshot.message != "尚未开始") runtimeMessage = snapshot.message
                    if (snapshot.running || snapshot.result.isNotEmpty()) result = snapshot.result
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null; taskRunning = false; taskPaused = false
            message = "任务服务已断开；未自动重放动作。"
        }
    }
    private var api: IExecutor? = null
    private val token = Binder()
    private var bound = false
    private var request: ModelClient? = null
    private var generation = 0L
    private val commands = Mutex()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            api = IExecutor.Stub.asInterface(binder); connected = true
            command { getCapabilities() }
        }
        override fun onServiceDisconnected(name: ComponentName) { lost() }
        override fun onBindingDied(name: ComponentName) { lost(); unbind() }
        override fun onNullBinding(name: ComponentName) { lost(); unbind() }
    }
    private fun lost() { api = null; connected = false; romReady = false; session = ""; paused = false; message = "执行服务已断开；任务不会自动恢复。" }
    private fun unbind() { if (bound) { getApplication<Application>().unbindService(connection); bound = false } }
    init {
        scope.launch {
            try {
                profiles = withContext(Dispatchers.IO) { store.installBuiltInIfNeeded(app,store.load()) }; storageReady = true
                val previous=withContext(Dispatchers.IO) { runCatching {
                    app.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("task-result-") && it.extension=="json" }
                        ?.maxByOrNull { it.lastModified() }?.let { org.json.JSONObject(it.readText()) }
                }.getOrNull() }
                if(previous!=null && !taskRunning) result="上次任务用时 ${previous.optLong("elapsedMs")/1000.0} 秒\n\n${previous.optString("message")}"
            }
            catch (_: Exception) { message = "本地配置无法解密或已损坏，已停止读写以保护原文件。" }
        }
        runtimeBound = app.bindService(Intent(app, TaskRuntimeService::class.java), runtimeConnection, Context.BIND_AUTO_CREATE)
        reconnect()
    }
    fun reconnect() {
        if (bound) return
        val app = getApplication<Application>()
        try {
            check(app.packageManager.checkSignatures(app.packageName, "dev.droiduse.executor") == PackageManager.SIGNATURE_MATCH)
            bound = app.bindService(Intent().setComponent(ComponentName("dev.droiduse.executor", "dev.droiduse.executor.app.ExecutorService")), connection, Context.BIND_AUTO_CREATE)
            if (!bound) message = "执行服务未安装或无法连接。"
        } catch (_: Exception) { message = "请安装同一签名的 DroidUse 执行服务。" }
    }
    fun save(profile: ModelProfile) {
        try { profile.validate() } catch (e: IllegalArgumentException) { message = e.message ?: "配置无效"; return }
        persist(ProfileStore.State(profiles.profiles.filterNot { it.id == profile.id } + profile, profiles.activeId ?: profile.id))
    }
    fun select(id: String) = persist(profiles.copy(activeId = id))
    fun delete(id: String) {
        val rest = profiles.profiles.filterNot { it.id == id }
        persist(ProfileStore.State(rest, if (profiles.activeId == id) rest.firstOrNull()?.id else profiles.activeId))
    }
    private fun persist(next: ProfileStore.State) {
        if (!storageReady || busy || taskRunning) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { store.save(next) }; profiles = next; message = "配置已加密保存。" }
            catch (_: Exception) { message = "保存失败，请检查配置数量（最多20套）和设备存储。" }
            finally { busy = false }
        }
    }
    fun model(profile: ModelProfile?, probe: Boolean) {
        if (busy || taskRunning) return
        if (profile == null) { message = "请先添加并选择模型配置。"; return }
        try { profile.validate(); if (!probe) require(task.isNotBlank() && task.length <= 4000) { "请输入1～4000字的任务" } }
        catch (e: IllegalArgumentException) { message = e.message ?: "输入无效"; return }
        val text = task
        val client = ModelClient(); request = client; val version = ++generation
        if (!probe) result = ""
        busy = true; message = if (probe) "正在测试连接…" else "正在生成计划，尚未操作手机…"
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) { if (probe) client.probe(profile) else client.plan(profile, text) }
                if (version == generation) { result = output; message = if (probe) "模型连接成功。" else "计划已生成，尚未执行。" }
            } catch (_: Exception) {
                if (version == generation) message = "请求失败：请检查网络、HTTPS 地址、协议、模型名称及额度。"
            } finally { if (version == generation) { busy = false; request = null } }
        }
    }
    fun runTask(adbExperiment: Boolean = false, pureVision: Boolean = false) {
        if(adbExperiment && !BuildConfig.DEBUG) return
        if(adbExperiment && task.isBlank()) task="在番茄小说找都市脑洞爽文，比较可见数据，选择综合数据较高的一本，阅读前三章并分别总结。"
        if (busy || taskRunning || session.isNotEmpty()) return
        val targetPackage = if (adbExperiment) "" else inferredTargetPackage()
        if (!adbExperiment && targetPackage == null) { message="请在任务中写明要操作的应用名称。"; return }
        val profile=profiles.profiles.firstOrNull { it.id == profiles.activeId }
        val service=api
        if(profile == null || (service == null && !adbExperiment)) { message="请先配置模型并连接执行服务。"; return }
        try { profile.validate(); require(task.isNotBlank() && task.length <= 4000) }
        catch (_: Exception) { message="请检查模型配置，并输入1～4000字的任务。"; return }
        val owner = runtime ?: run { message="任务服务尚未连接。"; return }
        try {
            result = ""
            getApplication<Application>().startForegroundService(Intent(getApplication(), TaskRuntimeService::class.java))
            owner.runTask(profile, task, adbExperiment, pureVision, targetPackage=targetPackage.orEmpty())
        } catch (_: Exception) {
            owner.stopTask(); message="任务启动失败，请检查执行服务与后台运行权限。"
        }
    }
    fun pauseTask() { runtime?.togglePause() }
    fun stop() {
        runtime?.stopTask()
        if (request != null) { ++generation; request?.cancel(); request = null; busy = false }
        if (connected && session.isNotEmpty()) command { cancelSession(session) }
    }
    fun execute() { if (taskRunning || session.isNotEmpty()) { message = "请先停止当前会话。"; return }; command { getCapabilities() } }
    fun togglePause() = command { if (paused) resumeSession(session) else pauseSession(session) }
    private fun command(block: IExecutor.() -> Bundle) {
        val service = api ?: run { message = "执行服务未连接。"; return }
        // Local Binder calls are short, but run off the UI thread and serialize responses.
        scope.launch { commands.withLock {
            if (api !== service) return@withLock
            try {
                val answer = withContext(Dispatchers.IO) { service.block() }
                if (api !== service) return@withLock
                message = answer.getString("message") ?: "无状态信息"
                if (answer.containsKey("ready")) romReady = answer.getBoolean("ready")
                answer.getString("sessionId")?.takeIf { it.isNotEmpty() }?.let { session = it }
                paused = answer.getString("code") == "PAUSED"
                if (answer.getString("code") == "CANCELLED") session = ""
            } catch (_: Exception) { message = "执行服务调用失败，未继续执行。" }
        } }
    }
    override fun onCleared() {
        runtimeObservation?.cancel()
        if (runtimeBound) getApplication<Application>().unbindService(runtimeConnection)
        request?.cancel(); unbind(); scope.cancel()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        val state = ViewModelProvider(this)[AssistantState::class.java]
        setContent {
            var page by remember { mutableIntStateOf(0) }
            DisposableEffect(state.taskRunning) {
                if (state.taskRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            DisposableEffect(page, state.manual) {
                if (page == 1 || state.manual) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { }
            }
            AssistantApp(state, page, onPageChange = { page = it })
        }
    }
}
