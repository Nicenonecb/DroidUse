package dev.droiduse.executor.app

import android.content.Context
import android.os.IInterface
import dev.droiduse.systemclient.CapabilitySnapshot
import dev.droiduse.systemclient.IDroidUseSystem

/** Discovers the ROM service through Context after SystemServiceRegistry installs it. */
internal class RomSystemClient(context: Context) {
    data class Probe(val serviceFound: Boolean, val snapshot: CapabilitySnapshot?, val failure: String?)

    val service: IDroidUseSystem? = runCatching {
        val framework = context.getSystemService(SERVICE_NAME) as? IInterface
        framework?.asBinder()?.let { IDroidUseSystem.Stub.asInterface(it) }
    }.getOrNull()

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
