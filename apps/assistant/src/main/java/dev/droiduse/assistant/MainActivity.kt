package dev.droiduse.assistant

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
    var task by mutableStateOf("")
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
    private fun lost() { api = null; connected = false; session = ""; paused = false; message = "执行服务已断开；任务不会自动恢复。" }
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
        val profile=profiles.profiles.firstOrNull { it.id == profiles.activeId }
        val service=api
        if(profile == null || (service == null && !adbExperiment)) { message="请先配置模型并连接执行服务。"; return }
        try { profile.validate(); require(task.isNotBlank() && task.length <= 4000) }
        catch (_: Exception) { message="请检查模型配置，并输入1～4000字的任务。"; return }
        val owner = runtime ?: run { message="任务服务尚未连接。"; return }
        try {
            getApplication<Application>().startForegroundService(Intent(getApplication(), TaskRuntimeService::class.java))
            owner.runTask(profile, task, adbExperiment, pureVision)
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
    fun execute() { if (taskRunning || session.isNotEmpty()) { message = "请先停止当前会话。"; return }; command { beginSession(token) } }
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
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006C67), background = Color(0xFFF5F8F7))) {
                var page by remember { mutableIntStateOf(0) }
                DisposableEffect(state.taskRunning) {
                    if(state.taskRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                DisposableEffect(page,state.manual) {
                    if (page == 1 || state.manual) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { }
                }
                if(state.manual) HandoffDialog(state)
                Scaffold(bottomBar = { NavigationBar {
                    listOf("任务", "模型", "诊断").forEachIndexed { i, label ->
                        NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Text(listOf("◎", "◇", "≡")[i]) }, label = { Text(label) })
                    }
                } }) { padding ->
                    Column(Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Spacer(Modifier.height(8.dp)); Text("DroidUse", style = MaterialTheme.typography.headlineLarge)
                        Text("开发预览 · 模型可配置，支持手机本地实验。", style = MaterialTheme.typography.bodyMedium)
                        Card(Modifier.fillMaxWidth()) { Text(state.runtimeMessage.ifBlank { state.message }, Modifier.padding(16.dp)) }
                        when (page) {
                            0 -> {
                                Text("新任务", style = MaterialTheme.typography.titleLarge)
                                Text("本地 OCR：${state.ocrKind.label}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OcrKind.entries.forEach { kind ->
                                        FilterChip(selected = state.ocrKind == kind, onClick = { state.selectOcr(kind) },
                                            enabled = !state.taskRunning, label = { Text(kind.label) })
                                    }
                                }
                                Text("当前模型：" + (state.profiles.profiles.firstOrNull { it.id == state.profiles.activeId }?.name ?: "尚未配置"))
                                OutlinedTextField(state.task, { state.task = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("例如：在百度搜索杭州周末天气") }, minLines = 3)
                                Text("生成计划会将任务发送至你配置的服务；执行前必须通过系统隔离检查。", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { state.model(state.profiles.profiles.firstOrNull { it.id == state.profiles.activeId }, false) }, enabled = !state.busy && !state.taskRunning) { Text("生成计划") }
                                    OutlinedButton(onClick = state::execute, enabled = state.connected && state.session.isEmpty() && !state.busy && !state.taskRunning) { Text("执行检查") }
                                }
                                Button(onClick = { state.runTask() }, enabled = state.connected && !state.busy && !state.taskRunning && state.session.isEmpty()) { Text("运行任务") }
                                if(BuildConfig.DEBUG) OutlinedButton(onClick = { state.runTask(true) },enabled = !state.busy && !state.taskRunning && state.session.isEmpty()) { Text("手机本地实验 · 番茄小说") }
                                if(BuildConfig.DEBUG) OutlinedButton(onClick = { state.runTask(true,true) },enabled = !state.busy && !state.taskRunning && state.session.isEmpty()) { Text("纯视觉实验 · 番茄小说") }
                                Text("运行时会向所选模型发送任务和后台截图；系统未就绪时不会发送。手机本地实验须先通过 ADB 启动调试执行进程，运行时无需电脑。", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { if(state.taskRunning) state.pauseTask() else state.togglePause() }, enabled = state.session.isNotEmpty() || state.taskRunning) { Text(if (state.paused || state.taskPaused) "恢复" else "暂停") }
                                    OutlinedButton(onClick = state::stop) { Text("停止") }
                                }
                                OutlinedButton(onClick=state::enterHandoff,enabled=state.taskRunning && !state.manual) { Text("查看后台并接管") }
                                if (state.result.isNotBlank()) { Text("计划或任务结果", style = MaterialTheme.typography.titleMedium); Text(state.result) }
                            }
                            1 -> ModelSettings(state)
                            2 -> {
                                Text("运行状态", style = MaterialTheme.typography.titleLarge)
                                Text("执行服务：${if (state.connected) "已连接" else "未连接"}\n协议版本：2\n系统隔离：未就绪\n独立中文输入：待 ROM 接入\n资源申请前拦截：待 ROM 接入\n配置存储：${if (state.storageReady) "设备密钥加密" else "不可用"}")
                                Text("当前版本可配置模型、测试连接、生成计划和检查执行条件。不会把 ADB 实验结果当作 ROM 验收。")
                                OutlinedButton(onClick = state::reconnect, enabled = !state.connected) { Text("重新连接") }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun ModelSettings(state: AssistantState) {
    var editing by remember { mutableStateOf<ModelProfile?>(null) }
    var draftId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(Protocol.CHAT_COMPLETIONS) }
    var show by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<ModelProfile?>(null) }
    fun draft() = ModelProfile(id = editing?.id ?: draftId, name = name, baseUrl = url, model = model, apiKey = key, protocol = protocol)
    Text("模型连接", style = MaterialTheme.typography.titleLarge)
    state.profiles.profiles.forEach { profile ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(profile.name + if (profile.id == state.profiles.activeId) " · 当前" else "")
            Text(profile.model, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { state.select(profile.id) }, enabled = !state.busy && !state.taskRunning) { Text("使用") }
                TextButton(onClick = { editing = profile; name = profile.name; url = profile.baseUrl; model = profile.model; key = profile.apiKey; protocol = profile.protocol; show = false }) { Text("编辑") }
                TextButton(onClick = { deletion = profile }, enabled = !state.busy && !state.taskRunning) { Text("删除") }
            }
        } }
    }
    Text(if (editing == null) "添加配置" else "编辑配置", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS 服务地址（含版本路径）") }, placeholder = { Text("https://服务域名/v1") }, singleLine = true)
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示") } })
    Protocol.entries.forEach { p -> Row { RadioButton(protocol == p, onClick = { protocol = p }); TextButton(onClick = { protocol = p }) { Text(p.label) } } }
    Text("密钥仅加密保存在本机。测试会向此地址发送一个短请求，可能消耗额度。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { state.save(draft()) }, enabled = state.storageReady && !state.busy) { Text("保存") }
        OutlinedButton(onClick = { state.model(draft(), true) }, enabled = !state.busy && !state.taskRunning) { Text("测试连接") }
        TextButton(onClick = { editing = null; draftId = java.util.UUID.randomUUID().toString(); name = ""; url = ""; model = ""; key = ""; show = false }) { Text("新建") }
    }
    deletion?.let { p -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${p.name}？") }, text = { Text("将移除此设备保存的连接配置。") },
        confirmButton = { TextButton(onClick = { state.delete(p.id); if (editing?.id == p.id) { editing = null; key = "" }; deletion = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}
