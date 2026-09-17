package dev.droiduse.executor

/** System-side admission policy, called BEFORE allocating a device or changing audio focus.
 * The Android hook must construct Principal from Binder/process attribution, never app extras.
 * Synchronization serializes policy transitions; allocation must execute through admit() in the same policy critical section.
 * Resource-service lock ordering must remain consistent; a detached ticket is not authority.
 */
class ResourceGate(private val now: () -> Long) {
    enum class Resource { AUDIO_OUTPUT, AUDIO_FOCUS, MICROPHONE, CAMERA }
    enum class Route { SYSTEM_DEFAULT, VIRTUAL_SILENT, DENY }
    data class Principal(val uid: Int, val userId: Int, val displayId: Int) {
        init { require(uid >= 0 && userId >= 0 && uid / 100000 == userId) }
    }
    data class Decision(val route: Route, val revision: Long, val epoch: Long?, val reason: String)
    private data class Lease(val epoch: Long, val display: Int, val until: Long, val ready: Boolean, val revoked: Boolean = false)
    private val leases = mutableMapOf<Pair<Int, Int>, Lease>()
    private var revision = 0L

    /** UID is quarantined before target launch. Shared main/background UID is unsupported. */
    @Synchronized fun reserve(epoch: Long, principal: Principal, foregroundUids: Set<Int>): Boolean {
        require(epoch > 0 && principal.uid % 100000 >= 10000 && principal.userId >= 0 && principal.displayId > 0)
        if (principal.uid in foregroundUids || key(principal) in leases) return false
        leases[key(principal)] = Lease(epoch, principal.displayId, Long.MIN_VALUE, false)
        revision++
        return true
    }
    /** Only the trusted platform acknowledges all routes/denials and their finite lifetime. */
    @Synchronized fun ready(epoch: Long, principal: Principal, expiresAt: Long): Boolean {
        val previous = leases[key(principal)] ?: return false
        if (previous.revoked || previous.epoch != epoch || previous.display != principal.displayId || expiresAt - now() !in 1..30_000
            || (previous.ready && now() >= previous.until)) return false
        leases[key(principal)] = previous.copy(until = expiresAt, ready = true)
        revision++
        return true
    }
    @Synchronized fun decide(principal: Principal, resource: Resource): Decision {
        val lease = leases[key(principal)] ?: return Decision(Route.SYSTEM_DEFAULT, revision, null, "not an isolated UID")
        if (!lease.ready || now() >= lease.until || principal.displayId != lease.display)
            return Decision(Route.DENY, revision, lease.epoch, "unready, expired or escaped display")
        return when (resource) {
            Resource.AUDIO_OUTPUT -> Decision(Route.VIRTUAL_SILENT, revision, lease.epoch, "verified virtual sink only")
            Resource.AUDIO_FOCUS, Resource.MICROPHONE, Resource.CAMERA -> Decision(Route.DENY, revision, lease.epoch, "main-screen resource reserved")
        }
    }
    /** Execute the short allocation transaction under the same policy lock as revoke.
     * The callback must preserve normal Android permission checks and honor VIRTUAL_SILENT.
     * Never perform Binder calls or blocking work in this transaction.
     */
    @Synchronized fun <T> admit(principal: Principal, resource: Resource, allocate: (Decision) -> T): T {
        val decision = decide(principal,resource)
        if(decision.route == Route.DENY) throw SecurityException("Isolated resource denied")
        return allocate(decision)
    }

    /** Diagnostic ticket validation only; use admit() for the actual allocation transaction. */
    @Synchronized fun isCurrent(principal: Principal, resource: Resource, decision: Decision): Boolean =
        decision == decide(principal, resource)

    /** Death/expiry retains UID quarantine; no fallback to speaker/camera/microphone. */
    @Synchronized fun revoke(epoch: Long) {
        var changed = false
        leases.replaceAll { _, lease -> if (lease.epoch == epoch) {
            changed = true; lease.copy(ready = false, until = Long.MIN_VALUE, revoked = true)
        } else lease }
        if (changed) revision++
    }
    /** Stop all targets AND drain pending allocations/launches before releasing UID quarantine. */
    @Synchronized fun release(epoch: Long, targetsAndRequestsDrained: Boolean): Boolean {
        if (!targetsAndRequestsDrained) return false
        val changed = leases.entries.removeAll { it.value.epoch == epoch }
        if (changed) revision++
        return changed
    }
    private fun key(p: Principal) = p.userId to p.uid
}
