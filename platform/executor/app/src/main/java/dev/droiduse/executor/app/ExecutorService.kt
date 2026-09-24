package dev.droiduse.executor.app

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import dev.droiduse.ipc.IExecutor
import dev.droiduse.systemclient.DroidUseContract.*

/** Signature-authenticated APK bridge; the ROM owns target isolation and cleanup. */
class ExecutorService : Service() {
    private data class Session(val uid: Int, val token: IBinder, val death: IBinder.DeathRecipient,
                               val rom: RomSession, var paused: Boolean = false)
    private val lock = Any()
    private val cleanup = Handler(Looper.getMainLooper())
    private var current: Session? = null
    private lateinit var romSystem: RomSystemClient
    private lateinit var calls: ExecutorCalls
    override fun onCreate() { super.onCreate(); romSystem = RomSystemClient(this); calls = ExecutorCalls(romSystem, lock) }
    private fun authorize(): Int {
        enforceCallingPermission("dev.droiduse.permission.EXECUTE", "Signature permission required")
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if ("dev.droiduse.assistant" !in packages || packageManager.checkSignatures(uid, applicationInfo.uid) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Caller not authorized")
        return uid
    }
    private fun result(code: String, id: String = "") = Bundle().apply {
        putString("code", code); putString("sessionId", id)
        putString("message", when (code) {
            "READY" -> "ROM 隔离执行已就绪。"
            "CANCELLED" -> "任务已停止。"
            "PAUSED" -> "已暂停。"
            "TARGET_REQUIRED" -> "请先选择目标应用。"
            "ISOLATION_NOT_READY" -> "ROM 执行能力未就绪。"
            else -> code
        })
        putLong("at", SystemClock.elapsedRealtime())
    }
    private fun ready(): Boolean {
        val snapshot = romSystem.probe().snapshot ?: return false
        return snapshot.interfaceVersion == INTERFACE_VERSION && snapshot.coreReady &&
            listOf(CAP_DISPLAY_CAPTURE, CAP_INPUT_INJECTION, CAP_IDENTITY, CAP_SESSION_OWNERSHIP,
                CAP_FAILURE_CLEANUP, CAP_SELINUX).all { id ->
                snapshot.capabilities.any { it.capabilityId == id && it.availability == AVAILABILITY_AVAILABLE }
            } && listOf(CAP_APP_TASK_CONTROL, CAP_VIRTUAL_DISPLAY).all { id ->
                snapshot.capabilities.any { it.capabilityId == id && it.availability in 1..2 }
            }
    }
    private fun owned(id: String, uid: Int): Session {
        val session = current ?: throw IllegalStateException("Session closed")
        if (session.rom.id != id || session.uid != uid) throw SecurityException("Session owner mismatch")
        return session
    }
    private fun clear() {
        val session = current ?: return
        session.rom.close()
        session.token.unlinkToDeath(session.death, 0)
        current = null
    }
    private fun cleanupEventually(session: Session) {
        synchronized(lock) {
            if (current !== session) return
            try { clear() }
            catch (_: Exception) { cleanup.postDelayed({ cleanupEventually(session) }, 1000) }
        }
    }
    // Preserve the authenticated Assistant uid locally, then call ROM as Executor.
    private fun call(block: (Int) -> Bundle): Bundle {
        val uid = authorize()
        val identity = Binder.clearCallingIdentity()
        try {
            synchronized(lock) {
                return try { block(uid) }
                catch (_: RemoteException) { result("ISOLATION_LOST") }
                catch (_: java.util.concurrent.TimeoutException) { result("UNKNOWN_OUTCOME") }
                catch (_: java.util.concurrent.ExecutionException) { result("ISOLATION_LOST") }
                catch (error: RuntimeException) {
                    if (error.javaClass.name != "android.os.ServiceSpecificException") throw error
                    result(when (error.message) {
                        STALE_SESSION, USER_NOT_UNLOCKED -> "ISOLATION_LOST"
                        "TARGET_VISIBLE_ON_OTHER_DISPLAY", "WINDOW_TARGET_NOT_ALLOWED" -> "ISOLATION_LOST"
                        STALE_TARGET -> STALE_OBSERVATION
                        "PROTECTED_WINDOW" -> UNSUPPORTED
                        UNSUPPORTED, BUSY, RESOURCE_LIMIT, STALE_OBSERVATION -> requireNotNull(error.message)
                        else -> "UNKNOWN_OUTCOME"
                    })
                }
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private val api = object : IExecutor.Stub() {
        override fun getCapabilities(): Bundle = call {
            val probe = romSystem.probe()
            val available = ready()
            result(if (available) "READY" else "ISOLATION_NOT_READY").apply {
                putParcelableArrayList("callCapabilities", ArrayList(probe.snapshot?.capabilities.orEmpty()
                    .filter { it.capabilityId in CAP_TELECOM..CAP_PRIVATE_CALL_COMMAND }.map { value -> Bundle().apply {
                        putInt("id", value.capabilityId); putInt("availability", value.availability); putString("reason", value.reason)
                    } }))
                putInt("protocolVersion", 3); putBoolean("ready", available)
                putString("backend", "ROM_SYSTEM_V1"); putBoolean("targetRequired", true)
                val actions = if (available) mutableListOf("tap", "swipe") else mutableListOf()
                if (available && probe.snapshot?.capabilities?.any {
                    it.capabilityId == CAP_APP_TASK_CONTROL && it.availability == AVAILABILITY_DEGRADED &&
                        it.reason == "SCOPED_LAUNCH_AND_PICKER"
                } == true) actions.addAll(listOf("open_app", "select_file_at"))
                if (available && probe.snapshot?.capabilities?.any { it.capabilityId==CAP_SYSTEM_NAVIGATION &&
                        (it.availability==AVAILABILITY_AVAILABLE || it.availability==AVAILABILITY_DEGRADED && it.reason=="BACK_AND_KEY_EVENTS_ONLY") } == true) actions.add("back")
                if (available && probe.snapshot?.capabilities?.any { it.capabilityId==CAP_IME_CLIPBOARD &&
                        (it.availability==AVAILABILITY_AVAILABLE || it.availability==AVAILABILITY_DEGRADED && it.reason=="TEXT_INPUT_ONLY") } == true) actions.add("text")
                if (available && probe.snapshot?.capabilities?.any {
                    it.capabilityId == CAP_IME_CLIPBOARD && it.availability == AVAILABILITY_DEGRADED &&
                        it.reason == "SCOPED_TEXT_CLIPBOARD"
                } == true) actions.addAll(listOf("text", "edit_select", "edit_select_all", "edit_copy", "edit_cut", "edit_paste"))
                val scoped = mapOf(
                    CAP_NOTIFICATIONS_SYSTEM_UI to ("TASK_APP_NOTIFICATIONS" to listOf("notification_open", "notification_clear", "notification_reply")),
                    CAP_PACKAGE_PERMISSIONS to ("TASK_APP_RUNTIME_PERMISSIONS" to listOf("permission_grant", "permission_revoke")),
                    CAP_DEVICE_SETTINGS to ("OPT_IN_GLOBAL_SETTINGS" to listOf("set_volume", "set_brightness")),
                    CAP_CONNECTIVITY to ("OPT_IN_SAVED_WIFI" to listOf("set_wifi", "connect_wifi")))
                if (available) scoped.forEach { (id, scope) ->
                    if (probe.snapshot?.capabilities?.any { it.capabilityId == id &&
                            it.availability == AVAILABILITY_DEGRADED && it.reason == scope.first } == true)
                        actions.addAll(scope.second)
                }
                putBoolean("sessionOptions", true)
                putStringArray("actions", actions.toTypedArray())
                putBoolean("romServiceFound", probe.serviceFound)
                putBoolean("romCoreReady", probe.snapshot?.coreReady == true)
                probe.failure?.let { putString("romProbeFailure", it) }
            }
        }
        override fun beginSession(clientToken: IBinder): Bundle = call { result("TARGET_REQUIRED") }
        override fun beginTargetSession(clientToken: IBinder, targetPackage: String): Bundle =
            beginTargetSessionWithOptions(clientToken, targetPackage, Bundle())
        override fun beginTargetSessionWithOptions(clientToken: IBinder, targetPackage: String, options: Bundle): Bundle = call { uid ->
            if (current != null || calls.busy()) return@call result("BUSY")
            if (!targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) ||
                targetPackage in setOf(packageName, "dev.droiduse.assistant")) return@call result("INVALID_TARGET")
            if (!ready()) return@call result("ISOLATION_NOT_READY")
            val rom = RomSession(requireNotNull(romSystem.service), cacheDir)
            val death = IBinder.DeathRecipient {
                synchronized(lock) {
                    if (current?.rom === rom) {
                        current?.let { cleanupEventually(it) }
                    }
                }
            }
            clientToken.linkToDeath(death, 0)
            try {
                rom.open(targetPackage, options.getBoolean("allowGlobalSettings", false))
                current = Session(uid, clientToken, death, rom)
                if (!clientToken.isBinderAlive || rom.closed) { clear(); result("ISOLATION_LOST") }
                else result("READY", rom.id)
            } catch (error: Exception) { clientToken.unlinkToDeath(death, 0); throw error }
        }
        override fun getStatus(sessionId: String): Bundle = call { uid ->
            if (calls.matches(sessionId)) return@call calls.status(sessionId, uid)
            val s = owned(sessionId, uid)
            result(if (s.rom.closed) "ISOLATION_LOST" else if (s.paused) "PAUSED" else "READY", sessionId)
        }
        override fun pauseSession(sessionId: String): Bundle = call { uid ->
            if (calls.matches(sessionId)) { calls.pause(sessionId, uid, true); return@call result("PAUSED", sessionId) }
            val s = owned(sessionId, uid); s.rom.pause(true); s.paused = true; result("PAUSED", sessionId)
        }
        override fun resumeSession(sessionId: String): Bundle = call { uid ->
            if (calls.matches(sessionId)) { calls.pause(sessionId, uid, false); return@call result("READY", sessionId) }
            val s = owned(sessionId, uid); s.rom.pause(false); s.paused = false; result("READY", sessionId)
        }
        override fun cancelSession(sessionId: String): Bundle = call { uid ->
            if (calls.matches(sessionId)) { calls.close(sessionId, uid); return@call result("STOPPING", sessionId) }
            owned(sessionId, uid); clear(); result("CANCELLED", sessionId)
        }
        override fun observe(sessionId: String): Bundle = call { uid ->
            val s = owned(sessionId, uid)
            if (s.rom.closed) result("ISOLATION_LOST", sessionId)
            else if (s.paused) result("PAUSED", sessionId) else s.rom.observe()
        }
        override fun submitAction(sessionId: String, requestId: String, action: Bundle): Bundle = call { uid ->
            val s = owned(sessionId, uid)
            if (!requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return@call result("INVALID_REQUEST", sessionId)
            result(if (s.paused) "PAUSED" else s.rom.execute(requestId, action), sessionId)
        }
        override fun beginCallSession(clientToken: IBinder, options: Bundle): Bundle = call { uid ->
            if (current != null || calls.busy()) result("BUSY") else calls.open(uid, clientToken, options)
        }
        override fun observeCallSession(sessionId: String): Bundle = call { calls.observe(sessionId, it) }
        override fun submitCallOperation(sessionId: String, requestId: String, operation: Bundle): Bundle =
            call { calls.execute(sessionId, it, requestId, operation) }
        override fun openCallAudio(sessionId: String, spec: Bundle): Bundle = call { calls.stream(sessionId, it, spec) }
    }
    override fun onBind(intent: Intent): IBinder = api
    override fun onUnbind(intent: Intent): Boolean {
        synchronized(lock) { current?.let { cleanupEventually(it) }; calls.cleanup() }
        return false
    }
    override fun onDestroy() {
        synchronized(lock) { current?.let { cleanupEventually(it) }; calls.cleanup() }
        super.onDestroy()
    }
}
