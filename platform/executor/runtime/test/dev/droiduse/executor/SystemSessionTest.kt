package dev.droiduse.executor

fun systemSessionScenarios(): Int {
    var count = 0
    fun scenario(name: String, test: () -> Unit) {
        test(); count++; println("PASS $name")
    }
    fun session(
        mode: SystemSession.Mode,
        display: Int,
        deadline: Long = 10_000,
        maxPending: Int = 64,
    ) = SystemSession("session-1", 7, 10_298, 0, mode, display, setOf(1, 2, 3), deadline, maxPending)
    fun ready(value: SystemSession, now: Long = 100) {
        check(value.protectionReady(7, 1, value.requiredProtections(), now, now + 1_000))
    }

    scenario("session modes enforce display ownership") {
        check(runCatching { session(SystemSession.Mode.ISOLATED_DISPLAY, 0) }.isFailure)
        check(runCatching { session(SystemSession.Mode.PHYSICAL_CONTROL, 1) }.isFailure)
        session(SystemSession.Mode.ISOLATED_DISPLAY, 7)
        session(SystemSession.Mode.PHYSICAL_CONTROL, 0)
        session(SystemSession.Mode.CALL_ASSIST, -1)
        session(SystemSession.Mode.MEDIA_OBSERVE, -1)
    }
    scenario("mode-specific protections are required") {
        for (mode in SystemSession.Mode.values()) {
            val display = if (mode == SystemSession.Mode.ISOLATED_DISPLAY) 7 else if (mode == SystemSession.Mode.PHYSICAL_CONTROL) 0 else -1
            val value = session(mode, display)
            for (missing in value.requiredProtections()) {
                check(!value.protectionReady(7, 1, value.requiredProtections() - missing, 100, 1_000))
            }
            ready(value)
        }
    }
    scenario("physical session accepts bounded requests only while active") {
        val value = session(SystemSession.Mode.PHYSICAL_CONTROL, 0, maxPending = 1)
        ready(value)
        check(value.activate(100) == listOf(SystemSession.Effect.ACTIVATE_TARGET))
        check(value.admit("request-1", 101) == SystemSession.Admission.ACCEPTED)
        check(value.admit("request-2", 101) == SystemSession.Admission.LIMIT_REACHED)
        check(value.complete("request-1"))
        check(value.admit("request-1", 101) == SystemSession.Admission.DUPLICATE)
        check(value.admit("request-2", 101) == SystemSession.Admission.ACCEPTED)
    }
    scenario("pause retains protection but rejects new operations") {
        val value = session(SystemSession.Mode.PHYSICAL_CONTROL, 0)
        ready(value); value.activate(100)
        check(value.pause(101))
        check(value.admit("paused", 101) == SystemSession.Admission.NOT_READY)
        check(value.resume(102))
        check(value.admit("resumed", 102) == SystemSession.Admission.ACCEPTED)
    }
    scenario("cleanup order waits for requests streams and resources") {
        val value = session(SystemSession.Mode.CALL_ASSIST, -1)
        ready(value); value.activate(100)
        check(value.admit("audio-op", 101) == SystemSession.Admission.ACCEPTED)
        check(value.stop("client died") == listOf(SystemSession.Effect.REVOKE_ADMISSION, SystemSession.Effect.STOP_TARGETS))
        check(value.admit("late", 102) == SystemSession.Admission.NOT_READY)
        check(value.targetsStopped(7, true).isEmpty())
        check(value.requestsDrained(7, true))
        check(value.targetsStopped(7, true) == listOf(SystemSession.Effect.CLOSE_STREAMS))
        check(value.streamsClosed(7, true) == listOf(SystemSession.Effect.RELEASE_RESOURCES))
        value.resourcesReleased(7, false)
        check(value.state == SystemSession.State.RELEASING)
        value.resourcesReleased(7, true)
        check(value.state == SystemSession.State.CLOSED)
    }
    scenario("expired heartbeat cannot be revived") {
        val value = session(SystemSession.Mode.MEDIA_OBSERVE, -1)
        ready(value)
        value.activate(100)
        check(value.tick(1_100) == listOf(SystemSession.Effect.REVOKE_ADMISSION, SystemSession.Effect.STOP_TARGETS))
        check(!value.protectionReady(7, 2, value.requiredProtections(), 1_101, 2_000))
    }
    scenario("session deadline closes even with earlier valid proof") {
        val value = session(SystemSession.Mode.CALL_ASSIST, -1, deadline = 500)
        ready(value)
        value.activate(100)
        check(value.tick(500) == listOf(SystemSession.Effect.REVOKE_ADMISSION, SystemSession.Effect.STOP_TARGETS))
    }
    return count
}
