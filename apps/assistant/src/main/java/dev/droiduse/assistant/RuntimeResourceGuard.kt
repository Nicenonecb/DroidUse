package dev.droiduse.assistant

import android.app.ActivityManager
import android.content.*
import android.content.res.Configuration
import android.os.*
import dev.droiduse.agent.ResourcePolicy
import java.util.concurrent.atomic.AtomicBoolean

/** Per-task monitor; no global volume, focus, performance mode or foreground app changes. */
class RuntimeResourceGuard(context: Context) : AutoCloseable, ComponentCallbacks2 {
    private val app = context.applicationContext
    private val critical = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    init { app.registerComponentCallbacks(this) }
    fun sample(): ResourcePolicy.Sample {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return ResourcePolicy.Sample(app.getSystemService(PowerManager::class.java).currentThermalStatus,
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            memory.lowMemory, critical.get())
    }
    override fun onTrimMemory(level: Int) {
        // UI_HIDDEN is normal while the user uses another app, not memory pressure.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) critical.set(true)
    }
    override fun onLowMemory() { critical.set(true) }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun close() { if (closed.compareAndSet(false, true)) app.unregisterComponentCallbacks(this) }
}
