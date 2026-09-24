package dev.droiduse.assistant

import android.content.Context

/** Capture this explicit opt-in at task creation; never upgrade a recovered task's authority. */
internal object GlobalSettingsPreference {
    fun enabled(context: Context) = context.getSharedPreferences("execution_scope", Context.MODE_PRIVATE)
        .getBoolean("allow_global_settings", false)
    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences("execution_scope", Context.MODE_PRIVATE).edit()
            .putBoolean("allow_global_settings", enabled).apply()
    }
}
