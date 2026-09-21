package dev.droiduse.executor.app

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import dev.droiduse.ipc.IExecutor
import java.util.UUID

/** Bound service; no shell, no exported UI, and no success adapter for missing ROM hooks. */
class ExecutorService : Service() {
    private data class Session(val id: String, val uid: Int, val token: IBinder,
                               val death: IBinder.DeathRecipient, var paused: Boolean = false)
    private val lock = Any()
    private var current: Session? = null
    private lateinit var romSystem: RomSystemClient

    override fun onCreate() {
        super.onCreate()
        romSystem = RomSystemClient(this)
    }
    private fun authorize(): Int {
        enforceCallingPermission("dev.droiduse.permission.EXECUTE", "Signature permission required")
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if ("dev.droiduse.assistant" !in packages || packageManager.checkSignatures(uid, applicationInfo.uid) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Caller not authorized")
        return uid
    }
    private fun result(code: String, message: String, id: String = "") = Bundle().apply {
        putString("code", code); putString("message", message); putString("sessionId", id)
        putLong("at", SystemClock.elapsedRealtime())
    }
    private fun blocked(id: String = "") = result("ISOLATION_NOT_READY",
        "系统尚未提供独立中文输入及完整资源保护。任务未启动，也未调用模型。", id)
    private fun owned(id: String, uid: Int): Session {
        val s = current ?: throw IllegalStateException("Session closed")
        if (s.id != id || s.uid != uid) throw SecurityException("Session owner mismatch")
        return s
    }
    private fun clear() {
        current?.let { it.token.unlinkToDeath(it.death, 0) }
        current = null
    }
    private val api = object : IExecutor.Stub() {
        override fun getCapabilities(): Bundle {
            authorize()
            val probe = romSystem.probe()
            return blocked().apply {
                putInt("protocolVersion", 2); putBoolean("ready", false)
                putStringArray("actions", emptyArray()); putString("backend", "ROM_REQUIRED")
                putStringArray("missing", arrayOf("INDEPENDENT_INPUT", "NATIVE_RESOURCE_GUARD", "FAILURE_CONTAINMENT"))
                putBoolean("romServiceFound", probe.serviceFound)
                probe.snapshot?.let { snapshot ->
                    putInt("romInterfaceVersion", snapshot.interfaceVersion)
                    putBoolean("romCoreReady", snapshot.coreReady)
                    putString("romBackend", snapshot.backend)
                    putIntArray("romAvailableCapabilities", snapshot.capabilities
                        .filter { it.availability == dev.droiduse.system.DroidUseContract.AVAILABILITY_AVAILABLE }
                        .map { it.capabilityId }.toIntArray())
                    putStringArray("romUnavailableCapabilities", snapshot.capabilities
                        .filter { it.availability != dev.droiduse.system.DroidUseContract.AVAILABILITY_AVAILABLE }
                        .map { "${it.capabilityId}:${it.reason}" }.toTypedArray())
                }
                probe.failure?.let { putString("romProbeFailure", it) }
            }
        }
        override fun beginSession(clientToken: IBinder): Bundle {
            val uid = authorize()
            synchronized(lock) {
                if (current != null) return result("BUSY", "已有会话，请先停止。")
                val id = UUID.randomUUID().toString()
                val death = IBinder.DeathRecipient { synchronized(lock) { if (current?.id == id) current = null } }
                try {
                    clientToken.linkToDeath(death, 0)
                } catch (_: RemoteException) {
                    return result("DISCONNECTED", "客户端已退出。")
                }
                current = Session(id, uid, clientToken, death)
                if (!clientToken.isBinderAlive) { clear(); return result("DISCONNECTED", "客户端已退出。") }
                return blocked(id)
            }
        }
        override fun getStatus(sessionId: String): Bundle {
            val uid = authorize()
            synchronized(lock) { val s = owned(sessionId, uid); return if (s.paused) result("PAUSED", "已暂停。", s.id) else blocked(s.id) }
        }
        override fun pauseSession(sessionId: String): Bundle {
            val uid = authorize()
            synchronized(lock) { owned(sessionId, uid).paused = true; return result("PAUSED", "已暂停。", sessionId) }
        }
        override fun resumeSession(sessionId: String): Bundle {
            val uid = authorize()
            synchronized(lock) { owned(sessionId, uid).paused = false; return blocked(sessionId) }
        }
        override fun cancelSession(sessionId: String): Bundle {
            val uid = authorize()
            synchronized(lock) { owned(sessionId, uid); clear(); return result("CANCELLED", "任务已停止。", sessionId) }
        }
        override fun observe(sessionId: String): Bundle {
            val uid = authorize()
            synchronized(lock) { owned(sessionId,uid); return blocked(sessionId) }
        }
        override fun submitAction(sessionId: String, requestId: String, action: Bundle): Bundle {
            val uid = authorize()
            synchronized(lock) {
                val s = owned(sessionId, uid)
                if (requestId.isBlank() || requestId.length > 128) return result("INVALID_REQUEST", "无效请求。", sessionId)
                return if (s.paused) result("PAUSED", "已暂停。", sessionId) else blocked(sessionId)
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = api
    override fun onUnbind(intent: Intent): Boolean { synchronized(lock) { clear() }; return false }
    override fun onDestroy() { synchronized(lock) { clear() }; super.onDestroy() }
}
