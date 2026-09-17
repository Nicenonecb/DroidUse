package dev.droiduse.executor

/** System-side window validation and InputConnection transport are mandatory adapters.
 * No clipboard fallback or main-screen IME switching is performed here.
 */
class IndependentEditor(private val session: IsolationSession, private val platform: Platform) {
    data class Binding(val window: String, val uid: Int, val pid: Int,
                       val displayId: Int, val generation: Long)
    interface Platform {
        /** Recheck real WMS focus, process ownership and live editor generation atomically with
         * dispatch at the platform seam. The binding cannot be supplied by an untrusted caller. */
        fun commitIfCurrent(binding: Binding, text: String): Dispatch
    }
    enum class Dispatch { DISPATCHED, STALE_EDITOR, UNSUPPORTED, UNKNOWN_OUTCOME }
    enum class Result { DISPATCHED, STALE_EDITOR, UNSUPPORTED, UNKNOWN_OUTCOME,
                        ISOLATION_NOT_READY, INVALID_TEXT, DUPLICATE, REPLAY_WINDOW_FULL }
    private var binding: Binding? = null
    private var generation = 0L
    private val requests = mutableSetOf<String>()

    fun bindFromPlatform(next: Binding) {
        require(next.displayId == session.displayId && next.displayId > 0)
        require(next.uid >= 0 && next.pid > 0 && next.window.isNotEmpty())
        require(next.generation > generation)
        generation = next.generation
        binding = next
    }

    fun invalidate() { binding = null }

    fun commit(requestId: String, expectedGeneration: Long, text: String, now: Long): Result {
        if (!session.canAct(now)) return Result.ISOLATION_NOT_READY
        if (requestId.isBlank() || requestId.length > 128 || text.length > 16384
            || !validUtf16(text)) return Result.INVALID_TEXT
        if (requestId in requests) return Result.DUPLICATE
        if (requests.size >= 4096) return Result.REPLAY_WINDOW_FULL
        val target = binding ?: return Result.STALE_EDITOR
        if (expectedGeneration != target.generation) return Result.STALE_EDITOR
        // Record before IPC. A timeout may mean the target received the text; never auto-retry.
        requests.add(requestId)
        return try {
            when (platform.commitIfCurrent(target, text)) {
                Dispatch.DISPATCHED -> Result.DISPATCHED
                Dispatch.STALE_EDITOR -> { invalidate(); Result.STALE_EDITOR }
                Dispatch.UNSUPPORTED -> Result.UNSUPPORTED
                Dispatch.UNKNOWN_OUTCOME -> { invalidate(); Result.UNKNOWN_OUTCOME }
            }
        } catch (_: Exception) { invalidate(); Result.UNKNOWN_OUTCOME }
    }

    private fun validUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) {
                if (i == text.length || !text[i++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }
}
