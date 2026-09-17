package dev.droiduse.executor

/** Serialized startup/cleanup driver. Android enforcement must live outside this process.
 * install must be idempotent; a failed call may have installed policy before its reply was lost.
 * releaseAll must cover the whole epoch, including partially installed policies.
 */
class IsolationCoordinator(val session: IsolationSession, private val platform: Platform,
                           private val now: () -> Long) {
    interface Platform {
        fun install(epoch: Long, displayId: Int, protection: IsolationSession.Protection): Boolean
        fun launch(epoch: Long, displayId: Int): Boolean
        /** Must drain queued launches and terminate only this session's targets. */
        fun stopAndDrain(epoch: Long): Boolean
        fun releaseAll(epoch: Long): Boolean
    }
    fun start(expiresAt: Long): Boolean {
        if (session.state != IsolationSession.State.PREPARING) return false
        for (protection in IsolationSession.Protection.entries) {
            if (now() >= expiresAt || !attempt { platform.install(session.epoch, session.displayId, protection) }) {
                stop("protection installation failed or expired"); return false
            }
        }
        if (!session.protectionReady(session.epoch, 1, IsolationSession.Protection.entries.toSet(), now(), expiresAt)) {
            stop("protection acknowledgment invalid"); return false
        }
        if (IsolationSession.Effect.LAUNCH !in session.launch(now())) {
            cleanup(); return false
        }
        val launched = attempt { platform.launch(session.epoch, session.displayId) }
        session.launched(session.epoch, launched)
        if (!session.canAct(now())) { stop("launch failed or protection expired"); return false }
        return true
    }
    fun tick() { session.tick(now()); cleanup() }
    fun stop(reason: String) { session.stop(reason); cleanup() }
    /** Safe retry: never release isolation until stopping and draining has been confirmed. */
    fun cleanup() {
        if (session.state == IsolationSession.State.STOPPING) {
            session.targetsStopped(session.epoch, attempt { platform.stopAndDrain(session.epoch) })
        }
        if (session.state == IsolationSession.State.RELEASING) {
            session.released(session.epoch, attempt { platform.releaseAll(session.epoch) })
        }
    }
    private fun attempt(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }
}
