package dev.droiduse.executor

/** Protocol core. Only a trusted platform adapter may supply events; not the model or app UI.
 * Call on the executor's serialized control lane. Effects must be acknowledged, not assumed done.
 * This class cannot itself enforce Android resource policy.
 */
class IsolationSession(val epoch: Long, val displayId: Int) {
    init { require(epoch > 0 && displayId > 0) }

    enum class State { PREPARING, READY, STARTING, RUNNING, STOPPING, RELEASING, CLOSED }
    enum class Protection { INPUT, AUDIO_OUTPUT, AUDIO_FOCUS, MICROPHONE, CAMERA, ESCAPE_PREVENTION }
    enum class Effect { LAUNCH, STOP_TARGETS, RELEASE_ISOLATION }
    var state = State.PREPARING
        private set
    var stopReason: String? = null
        private set
    private var validUntil = Long.MIN_VALUE
    private var acceptedProof = -1L

    fun protectionReady(eventEpoch: Long, proof: Long, protections: Set<Protection>,
                        now: Long, expiresAt: Long): Boolean {
        if (eventEpoch != epoch || state !in setOf(State.PREPARING, State.READY, State.RUNNING)
            || proof <= acceptedProof || expiresAt <= now
            || (state != State.PREPARING && now >= validUntil)
            || !protections.containsAll(Protection.entries)) return false
        acceptedProof = proof
        validUntil = expiresAt
        if (state == State.PREPARING) state = State.READY
        return true
    }

    fun launch(now: Long): List<Effect> {
        if (state != State.READY) return emptyList()
        if (now >= validUntil) return stop("protection expired before launch")
        state = State.STARTING
        return listOf(Effect.LAUNCH)
    }

    fun launched(eventEpoch: Long, success: Boolean): List<Effect> {
        if (eventEpoch != epoch || state != State.STARTING) return emptyList()
        if (!success) return stop("launch failed or outcome unknown")
        state = State.RUNNING
        return emptyList()
    }

    fun protectionValid(now: Long) = state in setOf(State.READY, State.STARTING, State.RUNNING) && now < validUntil

    fun canAct(now: Long) = state == State.RUNNING && protectionValid(now)

    fun tick(now: Long): List<Effect> =
        if (state in setOf(State.READY, State.STARTING, State.RUNNING) && now >= validUntil)
            stop("protection heartbeat expired") else emptyList()

    /** Policy death, display loss, client death, takeover and cancellation use the same gate. */
    fun stop(reason: String): List<Effect> {
        if (state in setOf(State.STOPPING, State.RELEASING, State.CLOSED)) return emptyList()
        stopReason = reason
        validUntil = Long.MIN_VALUE
        state = State.STOPPING
        // Keep protection installed until the platform confirms targets cannot access resources.
        return listOf(Effect.STOP_TARGETS)
    }

    fun targetsStopped(eventEpoch: Long, verified: Boolean): List<Effect> {
        // verified must include draining/cancelling pending launches, not merely no current PID.
        if (eventEpoch != epoch || state != State.STOPPING || !verified) return emptyList()
        state = State.RELEASING
        return listOf(Effect.RELEASE_ISOLATION)
    }

    fun released(eventEpoch: Long, success: Boolean) {
        if (eventEpoch == epoch && state == State.RELEASING && success) state = State.CLOSED
        // Failure deliberately retains RELEASING; operator/adapter must retry cleanup.
    }
}
