package dev.droiduse.assistant

import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import dev.droiduse.agent.TaskLoop
import dev.droiduse.agent.ObservedTarget
import dev.droiduse.agent.TargetOperation
import dev.droiduse.ipc.IExecutor
import java.util.concurrent.atomic.AtomicBoolean

/** A real Binder adapter, with no shell fallback and no simulated ready capability. */
class BinderTaskExecutor(private val api: IExecutor) : TaskLoop.Executor {
    private val stopped=AtomicBoolean(false)
    private val lock=Any()
    private var session: String?=null
    private var declaredActions: Set<String> = emptySet()
    private val token=Binder()
    override fun begin(): Boolean {
        if(stopped.get()) return false
        val capabilities=api.capabilities
        if(!capabilities.getBoolean("ready") || capabilities.getInt("protocolVersion") < 2) return false
        declaredActions=capabilities.getStringArray("actions").orEmpty().toSet()
            .intersect(setOf("tap","swipe","back","text")+dev.droiduse.agent.EditorOperation.actionNames+TargetOperation.actionNames)
        synchronized(lock) {
            if(stopped.get()) return false
            val reply=api.beginSession(token)
            val id=reply.getString("sessionId")?.takeIf { it.isNotBlank() } ?: return false
            session=id
            if(stopped.get() || reply.getString("code") != "READY") { api.cancelSession(id);session=null;return false }
            return true
        }
    }
    private fun id(): String = synchronized(lock) { check(!stopped.get()); requireNotNull(session) }
    override fun observe(): TaskLoop.Frame {
        val reply=api.observe(id())
        val descriptor=reply.getParcelable("captureFile",ParcelFileDescriptor::class.java)
        // Close descriptors even when metadata/status is rejected.
        val bytes=descriptor?.use {
            require(reply.getString("code") == "OBSERVED")
            require(it.statSize in 1..3_145_728) { "Capture must be a bounded regular file, not a pipe" }
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readNBytes(3_145_729) }
        } ?: error("Capture unavailable")
        require(bytes.size in 1..3_145_728)
        val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,info)
        require(info.outMimeType == "image/png" && info.outWidth == reply.getInt("width") && info.outHeight == reply.getInt("height"))
        val targetBundles=reply.getParcelableArrayList("targets",Bundle::class.java).orEmpty()
        require(targetBundles.size<=100)
        val targets=targetBundles.map { target ->
            ObservedTarget(requireNotNull(target.getString("targetId")),
                requireNotNull(TargetOperation.fromAction(target.getString("kind") ?: "")),
                requireNotNull(target.getString("label")))
        }
        val actions=dev.droiduse.agent.ActionCatalog.forEditorFrame(declaredActions,
            reply.getStringArray("editorActions").orEmpty().toSet(),reply.getLong("editorGeneration"))
        return TaskLoop.Frame(reply.getString("frameId") ?: "",reply.getInt("displayId"),info.outWidth,info.outHeight,
            reply.getInt("rotation"),reply.getLong("capturedAt"),reply.getString("packageName") ?: "",
            Base64.encodeToString(bytes,Base64.NO_WRAP),if(reply.containsKey("editorGeneration")) reply.getLong("editorGeneration") else null,
            supportedActions=ObservedTarget.actions(actions,targets),targets=targets)
    }
    override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome {
        if(dev.droiduse.agent.ActionCatalog.kind(action) !in frame.supportedActions) return TaskLoop.Outcome.UNSUPPORTED
        if(action is TaskLoop.Action.Target && !ObservedTarget.accepts(frame,action)) return TaskLoop.Outcome.UNSUPPORTED
        val payload=Bundle().apply {
            putString("frameId",frame.id);putInt("displayId",frame.display);putInt("width",frame.width);putInt("height",frame.height)
            putInt("rotation",frame.rotation);putLong("capturedAt",frame.capturedAt);putString("packageName",frame.app)
            when(action) {
                is TaskLoop.Action.Target -> { putString("kind",action.operation.actionName);putString("targetId",action.targetId) }
                is TaskLoop.Action.MultiTouch, is TaskLoop.Action.DoubleTap, is TaskLoop.Action.LongPress, is TaskLoop.Action.Drag -> return TaskLoop.Outcome.UNSUPPORTED
                is TaskLoop.Action.Tap -> { putString("kind","tap");putInt("x",action.x);putInt("y",action.y) }
                is TaskLoop.Action.Swipe -> { putString("kind","swipe");putInt("x1",action.x1);putInt("y1",action.y1);putInt("x2",action.x2);putInt("y2",action.y2);putInt("durationMs",action.durationMs) }
                is TaskLoop.Action.Edit -> {
                    putString("kind",action.operation.actionName);putLong("editorGeneration",action.editorGeneration)
                    when(action.operation) {
                        dev.droiduse.agent.EditorOperation.SELECT -> { putInt("start",action.start);putInt("end",action.end) }
                        dev.droiduse.agent.EditorOperation.DELETE -> { putInt("before",action.before);putInt("after",action.after) }
                        else -> Unit
                    }
                }
                is TaskLoop.Action.Text -> { putString("kind","text");putString("value",action.value);putLong("editorGeneration",action.editorGeneration) }
                TaskLoop.Action.Back -> putString("kind","back")
                TaskLoop.Action.ReadChapters -> return TaskLoop.Outcome.UNSUPPORTED
                is TaskLoop.Action.Wait -> return TaskLoop.Outcome.UNSUPPORTED
            }
        }
        return when(api.submitAction(id(),requestId,payload).getString("code")) {
            "EXECUTED" -> TaskLoop.Outcome.EXECUTED
            "STALE_OBSERVATION", "FOCUS_CHANGED" -> TaskLoop.Outcome.STALE_OBSERVATION
            "UNSUPPORTED" -> TaskLoop.Outcome.UNSUPPORTED
            "ISOLATION_NOT_READY", "ISOLATION_LOST" -> TaskLoop.Outcome.ISOLATION_LOST
            else -> TaskLoop.Outcome.UNKNOWN_OUTCOME
        }
    }
    override fun cancel() {
        stopped.set(true)
        synchronized(lock) {
            session?.let { id ->
                val reply=api.cancelSession(id)
                check(reply.getString("code") == "CANCELLED")
                session=null
            }
        }
    }
}
