package dev.droiduse.agent

import org.junit.Assert.*
import org.junit.Test

class ResourcePolicyTest {
    @Test fun normalModerateAndSevereHaveDifferentActions() {
        fun decision(thermal: Int) = ResourcePolicy.evaluate(ResourcePolicy.Sample(thermal, 80, false, false))
        assertEquals(500, decision(0).captureIntervalMs)
        assertNull(decision(2).pauseReason)
        assertEquals(1500, decision(2).captureIntervalMs)
        assertEquals("THERMAL_SEVERE", decision(3).pauseReason)
    }
    @Test fun chargingDoesNotRemoveThermalOrMemoryProtection() {
        assertEquals("LOW_MEMORY", ResourcePolicy.evaluate(ResourcePolicy.Sample(0, 100, true, true)).pauseReason)
        assertEquals("THERMAL_SEVERE", ResourcePolicy.evaluate(ResourcePolicy.Sample(3, 100, true, false)).pauseReason)
        assertNull(ResourcePolicy.evaluate(ResourcePolicy.Sample(0, 5, true, false)).pauseReason)
        assertEquals("LOW_BATTERY", ResourcePolicy.evaluate(ResourcePolicy.Sample(0, 10, false, false)).pauseReason)
        assertNull(ResourcePolicy.evaluate(ResourcePolicy.Sample(0, null, false, false)).pauseReason)
    }
    @Test fun criticalTrimRequiresUserAndNoSubmit() {
        assertEquals("LOW_MEMORY", ResourcePolicy.evaluate(ResourcePolicy.Sample(0, 80, false, false, true)).pauseReason)
        var cancelled=false
        val executor=object : TaskLoop.Executor {
            override fun begin()=true
            override fun observe(): TaskLoop.Frame = throw ResourcePressureException("LOW_MEMORY")
            override fun submit(requestId: String, frame: TaskLoop.Frame, action: TaskLoop.Action): TaskLoop.Outcome = error("must not submit")
            override fun cancel() { cancelled=true }
        }
        val model=object : TaskLoop.Model {
            override fun decide(task: String, frame: TaskLoop.Frame): TaskLoop.Decision = error("must not request")
            override fun verify(task: String, claim: String, frame: TaskLoop.Frame): TaskLoop.Verification = error("must not verify")
            override fun cancel() = Unit
        }
        val loop=TaskLoop(executor,model,{0},"测试")
        assertEquals(TaskLoop.State.NEEDS_USER,loop.step());assertTrue(cancelled)
        assertTrue(loop.message.contains("LOW_MEMORY"))
    }
}
