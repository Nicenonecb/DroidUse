package dev.droiduse.executor

fun coordinatorScenarios(): Int {
    class Fake : IsolationCoordinator.Platform {
        val calls = mutableListOf<String>()
        var fail: IsolationSession.Protection? = null
        var drains = true
        var releases = true
        var launchThrows = false
        override fun install(epoch: Long, displayId: Int, protection: IsolationSession.Protection): Boolean {
            calls += protection.name; return protection != fail
        }
        override fun launch(epoch: Long, displayId: Int): Boolean { calls += "launch"; if (launchThrows) error("unknown"); return true }
        override fun stopAndDrain(epoch: Long): Boolean { calls += "drain"; return drains }
        override fun releaseAll(epoch: Long): Boolean { calls += "release"; return releases }
    }
    for (failure in IsolationSession.Protection.entries) {
        val p = Fake().apply { fail = failure }
        val c = IsolationCoordinator(IsolationSession(1,9),p) { 100 }
        check(!c.start(200)); check("launch" !in p.calls)
        check(p.calls.takeLast(2) == listOf("drain","release")); check(c.session.state == IsolationSession.State.CLOSED)
    }
    run {
        val p = Fake().apply { launchThrows = true; drains = false }
        val c = IsolationCoordinator(IsolationSession(1,9),p) { 100 }
        check(!c.start(200)); check("release" !in p.calls)
        check(c.session.state == IsolationSession.State.STOPPING)
        p.drains = true; p.releases = false; c.cleanup()
        check(c.session.state == IsolationSession.State.RELEASING)
        p.releases = true; c.cleanup(); check(c.session.state == IsolationSession.State.CLOSED)
    }
    run {
        var time = 100L
        val p = Fake(); val c = IsolationCoordinator(IsolationSession(1,9),p) { time }
        check(c.start(200)); check(p.calls.last() == "launch")
        check(!c.start(300)); time = 200; c.tick()
        check(c.session.state == IsolationSession.State.CLOSED)
        check(p.calls.takeLast(2) == listOf("drain","release"))
    }
    println("PASS 8 coordinator scenarios: installation failure, unknown launch, retained policy, expiry")
    return 8
}
