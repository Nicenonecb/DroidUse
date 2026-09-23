package dev.droiduse.assistant

import android.content.*
import android.net.Uri
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.TaskLoop
import dev.droiduse.ipc.IExecutor
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Assistant UID -> Executor APK -> ROM -> synthetic target. No model/network. */
class RomExecutorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withApi(block: (IExecutor) -> Unit) {
        val connected=CountDownLatch(1)
        var api: IExecutor?=null
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) { api=IExecutor.Stub.asInterface(binder);connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName("dev.droiduse.executor",
            "dev.droiduse.executor.app.ExecutorService")),connection,Context.BIND_AUTO_CREATE))
        try { assertTrue(connected.await(10,TimeUnit.SECONDS));block(requireNotNull(api)) }
        finally { context.unbindService(connection) }
    }
    private fun proof(name: String): String = context.contentResolver.query(
        Uri.parse("content://dev.droiduse.m2.fixture/state"),null,null,null,null)!!.use {
        assertTrue(it.moveToFirst());it.getString(it.getColumnIndexOrThrow(name))
    }
    @Test fun assistantExecutesTapTextSwipeAndCancels() = withApi { api ->
        assertTrue(api.capabilities.toString(),api.capabilities.getBoolean("ready"))
        val executor=BinderTaskExecutor(api,"dev.droiduse.executor.test")
        assertTrue(executor.begin())
        try {
            SystemClock.sleep(1500)
            var frame=executor.observe()
            assertEquals(setOf("tap","swipe","back","text"),frame.supportedActions)
            assertEquals(TaskLoop.Outcome.EXECUTED,executor.submit("tap1",frame,TaskLoop.Action.Tap(frame.width/2,110)))
            SystemClock.sleep(350);assertEquals("1",proof("clicks"))
            assertEquals(TaskLoop.Outcome.STALE_OBSERVATION,executor.submit("stale",frame,TaskLoop.Action.Tap(frame.width/2,110)))
            frame=executor.observe()
            assertEquals(TaskLoop.Outcome.EXECUTED,executor.submit("focus",frame,TaskLoop.Action.Tap(frame.width/2,290)))
            SystemClock.sleep(350);frame=executor.observe()
            assertEquals(TaskLoop.Outcome.EXECUTED,executor.submit("text",frame,TaskLoop.Action.Text("助手闭环测试",requireNotNull(frame.editorGeneration))))
            SystemClock.sleep(350);assertEquals("助手闭环测试",proof("text"))
            frame=executor.observe()
            assertEquals(TaskLoop.Outcome.EXECUTED,executor.submit("swipe",frame,
                TaskLoop.Action.Swipe(frame.width/2,frame.height*3/4,frame.width/2,frame.height/2,300)))
            SystemClock.sleep(350);assertTrue(proof("scroll").toInt()>0)
        } finally { executor.cancel() }
    }
    @Test fun metadataAndLifecycleAreEnforced() = withApi { api ->
        val opened=api.beginTargetSession(Binder(),"dev.droiduse.executor.test")
        assertEquals("READY",opened.getString("code"))
        val id=requireNotNull(opened.getString("sessionId"))
        try {
            assertEquals("BUSY",api.beginTargetSession(Binder(),"dev.droiduse.executor.test").getString("code"))
            SystemClock.sleep(1200)
            val observation=api.observe(id)
            observation.getParcelable("captureFile",ParcelFileDescriptor::class.java)?.close()
            val wrong=Bundle(observation).apply { remove("captureFile");putString("kind","tap");putInt("x",20);putInt("y",20);putInt("displayId",0) }
            assertEquals("STALE_OBSERVATION",api.submitAction(id,"wrong-display",wrong).getString("code"))
            assertEquals("PAUSED",api.pauseSession(id).getString("code"))
            assertEquals("PAUSED",api.submitAction(id,"paused",wrong).getString("code"))
            assertEquals("READY",api.resumeSession(id).getString("code"))
        } finally { assertEquals("CANCELLED",api.cancelSession(id).getString("code")) }
    }
    @Test fun assistantCannotCallRomDirectly() {
        val binder=(context.getSystemService("droiduse") as IInterface).asBinder()
        val request=Parcel.obtain();val reply=Parcel.obtain()
        try {
            request.writeInterfaceToken("dev.droiduse.system.IDroidUseSystem")
            binder.transact(IBinder.FIRST_CALL_TRANSACTION,request,reply,0)
            try { reply.readException();fail("ROM accepted Assistant uid") }
            catch (_: SecurityException) {}
        } finally { request.recycle();reply.recycle() }
    }
    @Test fun holdSessionForProcessDeath() = withApi { api ->
        val opened=api.beginTargetSession(Binder(),"dev.droiduse.executor.test")
        assertEquals("READY",opened.getString("code"))
        val id=requireNotNull(opened.getString("sessionId"))
        try { SystemClock.sleep(30_000) } finally { api.cancelSession(id) }
    }
}
