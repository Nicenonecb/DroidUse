package dev.droiduse.assistant

import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.TaskLoop
import dev.droiduse.agent.TargetOperation
import dev.droiduse.ipc.IExecutor
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Adapter contract test with a fake backend; does not claim system execution support. */
class TargetAdapterTest {
    @Test fun declarationsAndFrameTargetsControlAdmissionAndSerializedPayload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val image=File.createTempFile("target-adapter-",".png",context.cacheDir)
        val bitmap=Bitmap.createBitmap(10,20,Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val api=Backend(image)
            val executor=BinderTaskExecutor(api,"synthetic.test")
            try {
                assertTrue(executor.begin())
                assertTrue(executor.observe().supportedActions.isEmpty())
                for(operation in TargetOperation.entries) {
                    api.targets=arrayListOf(Bundle().apply {
                        putString("kind",operation.actionName);putString("targetId","target_1");putString("label","合成测试目标")
                    })
                    val frame=executor.observe()
                    assertEquals(setOf(operation.actionName),frame.supportedActions)
                    assertEquals(TaskLoop.Outcome.UNSUPPORTED,executor.submit("bad",frame,
                        TaskLoop.Action.Target(operation,"stale_target")))
                    val action=TaskLoop.Action.Target(operation,"target_1")
                    assertEquals(TaskLoop.Outcome.EXECUTED,executor.submit("request",frame,action))
                    val sent=requireNotNull(api.sent)
                    assertEquals(frame.id,sent.getString("frameId"));assertEquals(frame.display,sent.getInt("displayId"))
                    assertEquals(operation.actionName,sent.getString("kind"));assertEquals("target_1",sent.getString("targetId"))
                    assertFalse(sent.containsKey("uri"));assertFalse(sent.containsKey("path"));assertFalse(sent.containsKey("label"))
                    api.targets.clear()
                    val next=executor.observe()
                    assertEquals(TaskLoop.Outcome.UNSUPPORTED,executor.submit("old_target",next,action))
                }
                assertEquals(3,api.submissions)
            } finally { executor.cancel() }
            assertTrue(api.cancelled)
            api.declared=emptyArray()
            api.targets=arrayListOf(Bundle().apply {
                putString("kind","open_app");putString("targetId","a1");putString("label","未声明能力")
            })
            val undeclared=BinderTaskExecutor(api,"synthetic.test")
            try { assertTrue(undeclared.begin());assertTrue(undeclared.observe().supportedActions.isEmpty()) }
            finally { undeclared.cancel() }
        } finally { bitmap.recycle();image.delete() }
    }

    private class Backend(private val image: File) : IExecutor.Stub() {
        var declared=TargetOperation.actionNames.toTypedArray()
        var targets=arrayListOf<Bundle>()
        var sent: Bundle?=null
        var submissions=0
        var cancelled=false
        var sequence=0
        override fun getCapabilities()=Bundle().apply {
            putBoolean("ready",true);putInt("protocolVersion",3);putStringArray("actions",declared)
        }
        override fun beginSession(clientToken: IBinder)=Bundle().apply { putString("code","READY");putString("sessionId","test") }
        override fun beginTargetSession(clientToken: IBinder, targetPackage: String)=beginSession(clientToken)
        override fun getStatus(sessionId: String)=Bundle()
        override fun pauseSession(sessionId: String)=Bundle()
        override fun resumeSession(sessionId: String)=Bundle()
        override fun cancelSession(sessionId: String)=Bundle().apply { cancelled=true;putString("code","CANCELLED") }
        override fun submitAction(sessionId: String,requestId: String,action: Bundle)=Bundle().apply {
            submissions++;sent=Bundle(action);putString("code","EXECUTED")
        }
        override fun observe(sessionId: String)=Bundle().apply {
            putString("code","OBSERVED");putString("frameId","frame_${++sequence}")
            putInt("displayId",2);putInt("width",10);putInt("height",20);putInt("rotation",0)
            putLong("capturedAt",SystemClock.elapsedRealtime());putString("packageName","synthetic.test")
            putParcelable("captureFile",ParcelFileDescriptor.open(image,ParcelFileDescriptor.MODE_READ_ONLY))
            putParcelableArrayList("targets",targets)
        }
    }
}
