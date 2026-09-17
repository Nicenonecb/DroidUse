package dev.droiduse.executor

/** Invoked by trusted Binder death/display/heartbeat hooks on the serialized control lane.
 * ResourceGate lives at the system resource authority; it must outlive the assistant/executor.
 */
class ProtectionSupervisor(private val coordinator: IsolationCoordinator,
                           private val gate: ResourceGate) {
    enum class Fault { CLIENT_DIED, EXECUTOR_DIED, PROTECTION_DIED, DISPLAY_REMOVED, FOREGROUND_CONFLICT, HEARTBEAT_EXPIRED }
    fun fault(epoch: Long, fault: Fault) {
        if(epoch != coordinator.session.epoch) return
        // Shut admission before attempting remote cleanup. Late requests cannot get default routes.
        gate.revoke(epoch)
        coordinator.stop(fault.name)
        releaseIfClosed()
    }
    fun tick(now: Long) {
        if(coordinator.session.state in setOf(IsolationSession.State.READY, IsolationSession.State.STARTING, IsolationSession.State.RUNNING)
            && !coordinator.session.protectionValid(now)) {
            fault(coordinator.session.epoch,Fault.HEARTBEAT_EXPIRED)
        } else if(coordinator.session.state in setOf(IsolationSession.State.STOPPING,IsolationSession.State.RELEASING)) {
            gate.revoke(coordinator.session.epoch)
            retryCleanup()
        }
    }
    fun retryCleanup() { coordinator.cleanup(); releaseIfClosed() }
    private fun releaseIfClosed() {
        if(coordinator.session.state == IsolationSession.State.CLOSED)
            gate.release(coordinator.session.epoch,targetsAndRequestsDrained=true)
    }
}
