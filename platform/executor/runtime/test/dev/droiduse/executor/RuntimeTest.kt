package dev.droiduse.executor

private var count = 0
private fun scenario(name: String, test: () -> Unit) {
    test(); count++; println("PASS $name")
}
private fun prepared(): IsolationSession = IsolationSession(7, 21).apply {
    check(protectionReady(7, 1, IsolationSession.Protection.entries.toSet(), 100, 200))
}
private fun running(): IsolationSession = prepared().apply {
    check(launch(100) == listOf(IsolationSession.Effect.LAUNCH))
    launched(7, true)
}
fun main() {
    scenario("refuse primary display") {
        check(runCatching { IsolationSession(7, 0) }.isFailure)
    }
    scenario("missing ANY protection prevents launch") {
        for (missing in IsolationSession.Protection.entries) {
            val s = IsolationSession(7, 21)
            check(!s.protectionReady(7, 1, IsolationSession.Protection.entries.toSet() - missing, 100, 200))
            check(s.launch(100).isEmpty() && !s.canAct(100))
        }
    }
    scenario("stale session and expired proof rejected") {
        val s = IsolationSession(7, 21)
        check(!s.protectionReady(6, 1, IsolationSession.Protection.entries.toSet(), 100, 200))
        check(!s.protectionReady(7, 1, IsolationSession.Protection.entries.toSet(), 100, 100))
    }
    scenario("launch once and no action until acknowledgment") {
        val s = prepared()
        check(s.launch(100).single() == IsolationSession.Effect.LAUNCH)
        check(s.launch(101).isEmpty() && !s.canAct(101))
        s.launched(6, true); check(!s.canAct(101))
        s.launched(7, true); check(s.canAct(101))
    }
    scenario("expired proof before launch never launches") {
        check(prepared().launch(200) == listOf(IsolationSession.Effect.STOP_TARGETS))
    }
    scenario("expiry prevents text even before watchdog tick") {
        val s = running(); check(!s.canAct(200))
        check(s.tick(200) == listOf(IsolationSession.Effect.STOP_TARGETS))
    }
    scenario("renewal cannot reuse proof or resurrect cancelled session") {
        val s = running()
        check(!s.protectionReady(7, 1, IsolationSession.Protection.entries.toSet(), 101, 300))
        check(s.protectionReady(7, 2, IsolationSession.Protection.entries.toSet(), 101, 300))
        check(s.canAct(201)); s.stop("client died")
        check(!s.protectionReady(7, 3, IsolationSession.Protection.entries.toSet(), 102, 400))
    }
    scenario("late heartbeat cannot silently resume expired protection") {
        val s = running()
        check(!s.protectionReady(7, 2, IsolationSession.Protection.entries.toSet(), 201, 400))
        check(!s.canAct(201))
        check(s.tick(201) == listOf(IsolationSession.Effect.STOP_TARGETS))
    }
    scenario("cancel while launch in flight rejects late success") {
        val s = prepared(); s.launch(100); s.stop("cancel")
        s.launched(7, true); check(!s.canAct(101))
    }
    scenario("all fault types retain policy until stop verified") {
        for (reason in listOf("client died", "policy died", "display lost", "takeover", "cancel")) {
            val s = running()
            check(s.stop(reason) == listOf(IsolationSession.Effect.STOP_TARGETS))
            check(!s.canAct(101))
            check(s.targetsStopped(7, false).isEmpty())
            check(s.targetsStopped(6, true).isEmpty())
            check(s.targetsStopped(7, true) == listOf(IsolationSession.Effect.RELEASE_ISOLATION))
            s.released(7, false); check(s.state == IsolationSession.State.RELEASING)
            s.released(7, true); check(s.state == IsolationSession.State.CLOSED)
            check(s.stop(reason).isEmpty())
        }
    }
    scenario("unknown launch outcome uses stop path") {
        val s = prepared(); s.launch(100)
        check(s.launched(7, false) == listOf(IsolationSession.Effect.STOP_TARGETS))
    }
    var calls = 0
    var mode = IndependentEditor.Dispatch.DISPATCHED
    var received = ""
    val s = running()
    val editor = IndependentEditor(s, object : IndependentEditor.Platform {
        override fun commitIfCurrent(binding: IndependentEditor.Binding, text: String): IndependentEditor.Dispatch {
            calls++; received = text; return mode
        }
    })
    fun bind(generation: Long) = editor.bindFromPlatform(IndependentEditor.Binding("window", 10298, 123, 21, generation))
    scenario("reject text without editor") {
        check(editor.commit("0", 1, "你好", 101) == IndependentEditor.Result.STALE_EDITOR)
        check(calls == 0)
    }
    scenario("reject binding to primary or wrong display") {
        for (display in listOf(0, 22)) check(runCatching {
            editor.bindFromPlatform(IndependentEditor.Binding("window", 10298, 123, display, 1))
        }.isFailure)
    }
    scenario("Chinese emoji multiline and combining characters preserved") {
        bind(1)
        val text = "北京酒店\n👨‍👩‍👧‍👦 café e\u0301 ¥123"
        check(editor.commit("1", 1, text, 101) == IndependentEditor.Result.DISPATCHED)
        check(received == text && calls == 1)
    }
    scenario("stale generation never dispatched") {
        bind(2)
        check(editor.commit("2", 1, "不应写入", 101) == IndependentEditor.Result.STALE_EDITOR)
        check(calls == 1)
    }
    scenario("same request cannot duplicate after editor switch") {
        check(editor.commit("1", 2, "重复", 101) == IndependentEditor.Result.DUPLICATE)
        check(calls == 1)
    }
    scenario("malformed surrogate and excessive text rejected") {
        for (text in listOf("\uD800", "\uDC00", "x".repeat(16385)))
            check(editor.commit("bad", 2, text, 101) == IndependentEditor.Result.INVALID_TEXT)
        check(calls == 1)
    }
    scenario("platform catches focus race; invalidate old editor") {
        mode = IndependentEditor.Dispatch.STALE_EDITOR
        check(editor.commit("race", 2, "不应写入", 101) == IndependentEditor.Result.STALE_EDITOR)
        check(editor.commit("after-race", 2, "不应写入", 101) == IndependentEditor.Result.STALE_EDITOR)
        check(calls == 2)
    }
    scenario("unknown delivery never automatically resubmits") {
        bind(3); mode = IndependentEditor.Dispatch.UNKNOWN_OUTCOME
        check(editor.commit("timeout", 3, "一次", 101) == IndependentEditor.Result.UNKNOWN_OUTCOME)
        bind(4)
        check(editor.commit("timeout", 4, "一次", 101) == IndependentEditor.Result.DUPLICATE)
        check(calls == 3)
    }
    scenario("protection loss blocks valid editor") {
        s.stop("policy died")
        check(editor.commit("last", 4, "不应写入", 101) == IndependentEditor.Result.ISOLATION_NOT_READY)
        check(calls == 3)
    }
    count += coordinatorScenarios()
    count += resourceGateScenarios()
    count += supervisorScenarios()
    count += systemSessionScenarios()
    println("$count scenarios passed (host protocol tests, not Android integration tests)")
}
