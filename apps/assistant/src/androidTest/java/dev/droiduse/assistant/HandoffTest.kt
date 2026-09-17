package dev.droiduse.assistant

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.TaskLoop
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class HandoffTest {
    @Test fun lostReplyAfterRealInputRetainsSessionAndRequiresNewObservation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneBridge")=="true")
        val phone=PhoneTaskExecutor(InstrumentationRegistry.getInstrumentation().targetContext)
        var submissions=0
        val executor=object : TaskLoop.Executor by phone {
            override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome {
                submissions++
                assertEquals(TaskLoop.Outcome.EXECUTED,phone.submit(requestId,frame,action))
                // The device has acknowledged input; simulate losing that reply at the task boundary.
                throw java.net.SocketTimeoutException("injected lost reply")
            }
        }
        val frames=mutableListOf<String>()
        val model=object : TaskLoop.Model {
            override fun decide(task: String,frame: TaskLoop.Frame): TaskLoop.Decision {
                frames.add(frame.id)
                return if(frames.size==1) TaskLoop.Decision.Act(TaskLoop.Action.Tap(350,600))
                    else TaskLoop.Decision.AskUser("检查新的画面")
            }
            override fun verify(task: String,claim: String,frame: TaskLoop.Frame)=error("not expected")
            override fun cancel()=Unit
        }
        val records=mutableListOf<JSONObject>()
        val loop=TaskLoop(executor,model,SystemClock::elapsedRealtime,"测试回执丢失",maxFrameAgeMs=30000,
            retainForUser=true,audit={records.add(it)})
        try {
            assertEquals(TaskLoop.State.NEEDS_USER,loop.step());assertTrue(loop.awaitingUser)
            assertEquals(TaskLoop.Failure.EXECUTOR_TIMEOUT,loop.failure)
            loop.step();assertEquals(1,submissions);assertEquals(1,frames.size)
            phone.requestHandoff()
            val manual=phone.manualObserve() // Actual helper session is still available.
            assertNotEquals(frames.first(),manual.id)
            phone.leaveHandoff();loop.resume();assertEquals(TaskLoop.State.NEEDS_USER,loop.step())
            assertEquals(1,submissions);assertEquals(2,frames.size);assertNotEquals(frames[0],frames[1])
            val request=records.single { it.getString("event")=="ACTION_PENDING" }.getString("requestId")
            assertEquals(request,records.last { it.getString("event")=="OBSERVED" }.getString("afterRequestId"))
            assertEquals("UNKNOWN_OUTCOME",records.single { it.getString("event")=="ACTION_RESULT" }.getString("outcome"))
        } finally { loop.stop() }
    }

    @Test fun criticalMemoryWhileHandedOffClosesSessionWithoutModelCall() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneBridge")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val executor=PhoneTaskExecutor(instrumentation.targetContext)
        var calls=0
        val model=object : TaskLoop.Model {
            override fun decide(task: String,frame: TaskLoop.Frame): TaskLoop.Decision {
                calls++;return TaskLoop.Decision.Act(TaskLoop.Action.Wait(200))
            }
            override fun verify(task: String,claim: String,frame: TaskLoop.Frame)=error("not expected")
            override fun cancel()=Unit
        }
        val loop=TaskLoop(executor,model,SystemClock::elapsedRealtime,"测试",maxFrameAgeMs=30000,retainForUser=true)
        try {
            assertEquals(TaskLoop.State.RUNNING,loop.step());loop.pause();executor.requestHandoff()
            assertEquals(TaskLoop.State.PAUSED,loop.step())
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am send-trim-memory dev.droiduse.assistant RUNNING_CRITICAL")).use { it.readBytes() }
            val end=SystemClock.elapsedRealtime()+3000
            while(loop.state==TaskLoop.State.PAUSED && SystemClock.elapsedRealtime()<end) { Thread.sleep(100);loop.step() }
            assertEquals(TaskLoop.State.NEEDS_USER,loop.state);assertFalse(loop.awaitingUser)
            assertEquals(1,calls);assertThrows(IllegalStateException::class.java) { loop.resume() }
        } finally { loop.stop() }
    }

    @Test fun manualFramesAreNotLoggedAndAiResumesFromNewObservation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneBridge")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val executor=PhoneTaskExecutor(context)
        var calls=0
        val frames=mutableListOf<String>()
        val model=object : TaskLoop.Model {
            override fun decide(task: String,frame: TaskLoop.Frame): TaskLoop.Decision {
                calls++;frames.add(frame.id);return TaskLoop.Decision.Act(TaskLoop.Action.Wait(200))
            }
            override fun verify(task: String,claim: String,frame: TaskLoop.Frame)=error("not expected")
            override fun cancel()=Unit
        }
        val loop=TaskLoop(executor,model,SystemClock::elapsedRealtime,"测试",maxFrameAgeMs=30000,retainForUser=true)
        try {
            assertEquals(TaskLoop.State.RUNNING,loop.step())
            val directory=context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("phone-run-") }.maxBy { it.lastModified() }
            val before=directory.listFiles()!!.associate { it.name to it.length() }
            loop.pause();executor.requestHandoff()
            assertEquals(TaskLoop.State.PAUSED,loop.step())
            val first=executor.manualObserve()
            assertTrue(executor.manualInput(first,JSONObject().put("kind","tap").put("x",350).put("y",600)))
            val second=executor.manualObserve()
            assertNotEquals(first.id,second.id)
            assertEquals(before,directory.listFiles()!!.associate { it.name to it.length() })
            assertEquals(1,calls)
            executor.leaveHandoff();loop.resume()
            assertEquals(TaskLoop.State.RUNNING,loop.step())
            assertEquals(2,calls);assertNotEquals(frames[0],frames[1]);assertNotEquals(second.id,frames[1])
        } finally { loop.stop() }
    }
}
