package dev.droiduse.assistant

import android.content.ComponentCallbacks2
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ResourceGuardTest {
    @Test fun hiddenUiIsNotPressureButCriticalTrimIsLatched() {
        val guard=RuntimeResourceGuard(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertFalse(guard.sample().trimCritical)
            guard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertTrue(guard.sample().trimCritical)
        } finally { guard.close();guard.close() }
    }
}
