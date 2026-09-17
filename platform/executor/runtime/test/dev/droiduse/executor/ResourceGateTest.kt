package dev.droiduse.executor

fun resourceGateScenarios(): Int {
    var time = 10L
    val gate = ResourceGate { time }
    val bg = ResourceGate.Principal(10200,0,9)
    val fg = ResourceGate.Principal(10300,0,0)
    check(!gate.reserve(1,bg,setOf(bg.uid)))
    check(gate.reserve(1,bg,setOf(fg.uid)))
    for (resource in ResourceGate.Resource.entries) check(gate.decide(bg,resource).route == ResourceGate.Route.DENY)
    check(!gate.ready(1,bg,Long.MAX_VALUE)); check(!gate.ready(2,bg,100)); check(gate.ready(1,bg,100))
    check(gate.decide(bg,ResourceGate.Resource.AUDIO_OUTPUT).route == ResourceGate.Route.VIRTUAL_SILENT)
    for (resource in ResourceGate.Resource.entries - ResourceGate.Resource.AUDIO_OUTPUT)
        check(gate.decide(bg,resource).route == ResourceGate.Route.DENY)
    var allocations=0
    check(runCatching { gate.admit(bg,ResourceGate.Resource.MICROPHONE) { allocations++ } }.isFailure)
    check(allocations == 0)
    gate.admit(bg,ResourceGate.Resource.AUDIO_OUTPUT) { check(it.route == ResourceGate.Route.VIRTUAL_SILENT); allocations++ }
    check(allocations == 1)
    check(gate.decide(fg,ResourceGate.Resource.MICROPHONE).route == ResourceGate.Route.SYSTEM_DEFAULT)
    check(gate.decide(bg.copy(displayId=0),ResourceGate.Resource.AUDIO_OUTPUT).route == ResourceGate.Route.DENY)
    val ticket = gate.decide(bg,ResourceGate.Resource.AUDIO_OUTPUT)
    time=100; check(!gate.isCurrent(bg,ResourceGate.Resource.AUDIO_OUTPUT,ticket))
    check(!gate.ready(1,bg,200)); gate.revoke(1); check(!gate.ready(1,bg,200))
    check(!gate.release(1,false)); check(gate.decide(bg,ResourceGate.Resource.MICROPHONE).route == ResourceGate.Route.DENY)
    check(gate.release(1,true)); check(gate.decide(bg,ResourceGate.Resource.MICROPHONE).route == ResourceGate.Route.SYSTEM_DEFAULT)
    println("PASS resource admission: quarantine, sharing refusal, routing, escape, expiry, death, drain")
    return 1
}
