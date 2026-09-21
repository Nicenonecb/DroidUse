package dev.droiduse.executor.app

import android.content.Context
import dev.droiduse.system.CapabilitySnapshot
import dev.droiduse.system.IDroidUseSystem

/** Discovers the ROM service through Context after SystemServiceRegistry installs it. */
internal class RomSystemClient(context: Context) {
    data class Probe(val serviceFound: Boolean, val snapshot: CapabilitySnapshot?, val failure: String?)

    private val service: IDroidUseSystem? =
        runCatching { context.getSystemService(SERVICE_NAME) as? IDroidUseSystem }.getOrNull()

    fun probe(): Probe {
        val current = service ?: return Probe(false, null, null)
        return try {
            Probe(true, current.capabilities, null)
        } catch (error: Exception) {
            Probe(true, null, error.javaClass.simpleName)
        }
    }

    companion object { const val SERVICE_NAME = "droiduse" }
}
