package dev.droiduse.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.TaskCheckpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run in a cold target process. Seeds disk metadata; does not simulate an executed business action. */
class CheckpointRestoreTest {
    @Test fun coldServiceShowsPendingReceiptAndNeverStartsAWorker() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val state=File(context.noBackupFilesDir,"runtime-state.json")
        val original=if(state.exists()) state.readBytes() else null
        val runId=System.nanoTime().toString()
        val checkpoint=File(context.noBackupFilesDir,"task-checkpoint-$runId.json")
        val latch=CountDownLatch(1)
        var runtime: TaskRuntimeService?=null
        var bound=false
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) {
                runtime=(binder as TaskRuntimeService.LocalBinder).runtime;latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName)=Unit
        }
        try {
            checkpoint.writeText(TaskCheckpoint(step=4,executedActions=2,frameId="f4",requestId="r4",outcome="PENDING").json().toString())
            state.writeText(JSONObject().put("running",true).put("phase","RUNNING").put("runId",runId).toString())
            instrumentation.runOnMainSync {
                bound=context.bindService(Intent(context,TaskRuntimeService::class.java),connection,Context.BIND_AUTO_CREATE)
            }
            assertTrue(bound);assertTrue(latch.await(5,TimeUnit.SECONDS))
            val snapshot=requireNotNull(runtime).snapshots.value
            assertFalse(snapshot.running);assertFalse(snapshot.manual)
            assertTrue(snapshot.message.contains("第4步"));assertTrue(snapshot.message.contains("2次动作"))
            assertTrue(snapshot.message.contains("结果未知"));assertTrue(snapshot.message.contains("不能重发"))
            val saved=JSONObject(state.readText())
            assertEquals("INTERRUPTED",saved.getString("phase"));assertFalse(saved.getBoolean("running"))
            assertEquals(runId,saved.getString("runId"))
            assertEquals("PENDING",TaskCheckpoint.parse(JSONObject(checkpoint.readText())).outcome)
        } finally {
            if(bound) instrumentation.runOnMainSync { context.unbindService(connection) }
            checkpoint.delete()
            if(original!=null) state.writeBytes(original) else state.delete()
        }
    }
}
