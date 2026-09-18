package dev.droiduse.assistant

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.graphics.BitmapFactory
import dev.droiduse.agent.TaskLoop
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only loopback transport. ROM readiness stays false; this is explicitly an ADB lab. */
class AdbTaskExecutor(context: Context): TaskLoop.Executor {
    private val token=context.assets.open("bridge-token").bufferedReader().use { it.readText().trim() }
    private val stopped=AtomicBoolean(false)
    private val lock=Any()
    private var session: String?=null
    private val controls=java.util.concurrent.Executors.newSingleThreadExecutor()
    init { check(BuildConfig.DEBUG) }
    private fun call(path: String,body: JSONObject=JSONObject()): JSONObject {
        val http=URI("http://127.0.0.1:8765$path").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.connectTimeout=5000;http.readTimeout=if(path=="/action") 180000 else 30000
            http.instanceFollowRedirects=false;http.doOutput=true
            http.setRequestProperty("Authorization","Bearer $token");http.setRequestProperty("Content-Type","application/json")
            http.outputStream.use { it.write(body.toString().toByteArray()) }
            check(http.responseCode==200) { "ADB bridge rejected request" }
            val data=http.inputStream.use { it.readNBytes(8_388_609) };require(data.size<=8_388_608)
            return JSONObject(String(data,Charsets.UTF_8))
        } finally { http.disconnect() }
    }
    override fun begin(): Boolean = synchronized(lock) {
        if(stopped.get()) return false
        val reply=call("/begin");session=reply.getString("sessionId")
        if(stopped.get()) { cancel(); return false }
        reply.getBoolean("ready") && reply.getString("backend")=="ADB_EXPERIMENT"
    }
    private fun body(): JSONObject=synchronized(lock) { JSONObject().put("sessionId",requireNotNull(session)) }
    override fun observe(): TaskLoop.Frame {
        check(!stopped.get());val started=SystemClock.elapsedRealtime();val f=call("/observe",body())
        val encoded=f.getString("pngBase64");require(encoded.length<=4_194_304)
        val bytes=Base64.decode(encoded,Base64.DEFAULT)
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        require(options.outWidth==f.getInt("width") && options.outHeight==f.getInt("height") && options.outMimeType=="image/png")
        val context=JSONObject().put("backend","ADB_EXPERIMENT").put("ocr",f.getJSONArray("targets")).put("reading",f.getString("context")).toString()
        val complete=JSONObject(f.getString("context")).optJSONObject("reading")?.optBoolean("complete",false) == true
        return TaskLoop.Frame(f.getString("id"),f.getInt("display"),f.getInt("width"),f.getInt("height"),f.getInt("rotation"),started,f.getString("app"),encoded,contextText=context,completionReady=complete,supportedActions=setOf("tap","swipe","back","wait","read_chapters"))
    }
    override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome {
        check(!stopped.get())
        val json=JSONObject()
        when(action) {
            is TaskLoop.Action.Device, is TaskLoop.Action.PickFile -> return TaskLoop.Outcome.UNSUPPORTED
            is TaskLoop.Action.Target -> return TaskLoop.Outcome.UNSUPPORTED
            is TaskLoop.Action.Edit, is TaskLoop.Action.MultiTouch, is TaskLoop.Action.DoubleTap, is TaskLoop.Action.LongPress, is TaskLoop.Action.Drag -> return TaskLoop.Outcome.UNSUPPORTED
            is TaskLoop.Action.Tap -> json.put("kind","tap").put("x",action.x).put("y",action.y).put("target",action.target ?: "")
            is TaskLoop.Action.Swipe -> json.put("kind","swipe").put("x1",action.x1).put("y1",action.y1).put("x2",action.x2).put("y2",action.y2).put("durationMs",action.durationMs)
            TaskLoop.Action.Back -> json.put("kind","back")
            is TaskLoop.Action.Wait -> json.put("kind","wait").put("durationMs",action.durationMs)
            TaskLoop.Action.ReadChapters -> json.put("kind","read_chapters")
            is TaskLoop.Action.Text -> return TaskLoop.Outcome.UNSUPPORTED
        }
        return when(call("/action",body().put("requestId",requestId).put("frameId",frame.id).put("action",json)).getString("code")) {
            "EXECUTED" -> TaskLoop.Outcome.EXECUTED
            "STALE_OBSERVATION" -> TaskLoop.Outcome.STALE_OBSERVATION
            "UNSUPPORTED" -> TaskLoop.Outcome.UNSUPPORTED
            "ISOLATION_LOST" -> TaskLoop.Outcome.ISOLATION_LOST
            else -> TaskLoop.Outcome.UNKNOWN_OUTCOME
        }
    }
    override fun cancel() {
        stopped.set(true)
        controls.shutdownNow()
        synchronized(lock) { if(session!=null) { call("/cancel",body());session=null } }
    }
    override fun setPaused(paused: Boolean) {
        if(stopped.get()) return
        try { controls.execute {
            try { if(!stopped.get() && synchronized(lock){session!=null}) call(if(paused) "/pause" else "/resume",body()) }
            catch (_: Exception) { cancel() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
