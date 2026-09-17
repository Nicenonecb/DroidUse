package dev.droiduse.agent

/** Android thermal levels: 0 none, 1 light, 2 moderate, 3 severe. */
object ResourcePolicy {
    data class Sample(val thermal: Int, val batteryPercent: Int?, val charging: Boolean,
        val lowMemory: Boolean, val trimCritical: Boolean = false)
    data class Decision(val pauseReason: String? = null, val captureIntervalMs: Int = 500)
    fun evaluate(sample: Sample): Decision = when {
        sample.lowMemory || sample.trimCritical -> Decision("LOW_MEMORY")
        sample.thermal >= 3 -> Decision("THERMAL_SEVERE")
        !sample.charging && sample.batteryPercent != null && sample.batteryPercent <= 10 -> Decision("LOW_BATTERY")
        sample.thermal >= 2 || (!sample.charging && sample.batteryPercent != null && sample.batteryPercent <= 20) -> Decision(captureIntervalMs = 1500)
        else -> Decision()
    }
}

class ResourcePressureException(val reason: String) : IllegalStateException(reason)
