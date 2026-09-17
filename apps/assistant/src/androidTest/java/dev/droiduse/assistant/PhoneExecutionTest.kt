package dev.droiduse.assistant

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in: requires the bootstrapped phone daemon; never invokes an AI model. */
class PhoneExecutionTest {
    private fun enabled() { assumeTrue(InstrumentationRegistry.getArguments().getString("phoneRuntime")=="true") }
    @Test fun sessionsCanBeRecreatedWithoutReusingHostEvents() {
        enabled()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val frames=mutableSetOf<String>();val displays=mutableSetOf<Int>()
        repeat(2) {
            val executor=PhoneTaskExecutor(context)
            try {
                assertTrue(executor.begin());val f=executor.observe()
                assertTrue(f.display>0);assertTrue(frames.add(f.id));assertTrue(displays.add(f.display))
                assertEquals(720,f.width);assertEquals(1280,f.height);assertFalse(f.completionReady)
            } finally { executor.cancel() }
        }
    }
    @Test fun pauseBlocksObservationAndCancelInterruptsPause() {
        enabled()
        val executor=PhoneTaskExecutor(InstrumentationRegistry.getInstrumentation().targetContext)
        val worker=Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.begin());executor.setPaused(true)
            val frame=worker.submit<dev.droiduse.agent.TaskLoop.Frame> { executor.observe() }
            Thread.sleep(150);assertFalse(frame.isDone);executor.setPaused(false)
            assertTrue(frame.get(20,TimeUnit.SECONDS).display>0)
            executor.setPaused(true)
            val cancelled=worker.submit<Boolean> { runCatching { executor.observe() }.isFailure }
            Thread.sleep(150);assertFalse(cancelled.isDone);executor.cancel()
            assertTrue(cancelled.get(5,TimeUnit.SECONDS))
        } finally { executor.cancel();worker.shutdownNow() }
    }
}
