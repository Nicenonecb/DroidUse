package dev.droiduse.executor

fun supervisorScenarios(): Int {
    for(fault in ProtectionSupervisor.Fault.entries) {
        val gate=ResourceGate { 10 }
        val target=ResourceGate.Principal(10200,0,9)
        check(gate.reserve(1,target,emptySet())); check(gate.ready(1,target,100))
        var drained=false
        val platform=object: IsolationCoordinator.Platform {
            override fun install(epoch: Long,displayId: Int,protection: IsolationSession.Protection)=true
            override fun launch(epoch: Long,displayId: Int)=true
            override fun stopAndDrain(epoch: Long): Boolean {
                check(gate.decide(target,ResourceGate.Resource.AUDIO_OUTPUT).route == ResourceGate.Route.DENY)
                return drained
            }
            override fun releaseAll(epoch: Long)=true
        }
        val coordinator=IsolationCoordinator(IsolationSession(1,9),platform) {10}
        check(coordinator.start(100))
        val supervisor=ProtectionSupervisor(coordinator,gate)
        supervisor.fault(2,fault);check(coordinator.session.canAct(10))
        if(fault == ProtectionSupervisor.Fault.HEARTBEAT_EXPIRED) supervisor.tick(100) else supervisor.fault(1,fault)
        check(!coordinator.session.canAct(10));check(!gate.ready(1,target,200))
        check(gate.decide(target,ResourceGate.Resource.AUDIO_OUTPUT).route == ResourceGate.Route.DENY)
        drained=true;supervisor.retryCleanup()
        check(coordinator.session.state == IsolationSession.State.CLOSED)
        check(gate.decide(target,ResourceGate.Resource.AUDIO_OUTPUT).route == ResourceGate.Route.SYSTEM_DEFAULT)
    }
    println("PASS six supervisor faults deny before drain and retain quarantine on uncertain stop")
    return 6
}
