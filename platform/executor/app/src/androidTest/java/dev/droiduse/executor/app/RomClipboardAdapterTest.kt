package dev.droiduse.executor.app

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Exercises Executor's production adapter, including its frame and editor action filtering. */
class RomClipboardAdapterTest {
    @Test fun adapterClipboardAndPasswordAndExternalFocusChange() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3Clipboard") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = RomSession(requireNotNull(RomSystemClient(context).service), context.cacheDir)
        session.open("dev.droiduse.executor.test")
        fun observe(): Bundle { SystemClock.sleep(350); return session.observe() }
        fun submit(frame: Bundle, kind: String, value: String? = null, y: Int = 290): String {
            val action = Bundle(frame).apply {
                putString("kind", kind); putString("value", value)
                putInt("x", frame.getInt("width") / 2); putInt("y", y)
            }
            return session.execute(UUID.randomUUID().toString(), action)
        }
        fun perform(kind: String, value: String? = null, y: Int = 290) {
            assertEquals(kind, "EXECUTED", submit(observe(), kind, value, y))
        }
        fun text(): String = context.contentResolver.query(
            Uri.parse("content://dev.droiduse.m2.fixture/state"), null, null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(it.getColumnIndexOrThrow("text"))
        }
        try {
            perform("tap")
            perform("text", "adapter 中文 proof")
            perform("edit_select_all")
            perform("edit_copy")
            perform("edit_cut")
            assertEquals("", text())
            perform("edit_paste")
            assertEquals("adapter 中文 proof", text())
            perform("tap", y = 650)
            val password = observe()
            assertTrue("Password editor must offer no editing", password.getStringArray("editorActions")!!.isEmpty())
            assertEquals("UNSUPPORTED", submit(password, "edit_paste"))
            perform("tap")
            val beforeFocusChange = observe()
            // Simulate another actor changing editor focus without a new ROM observation.
            instrumentation.uiAutomation.executeShellCommand("input -d ${beforeFocusChange.getInt("displayId")} tap ${beforeFocusChange.getInt("width") / 2} 830").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            SystemClock.sleep(350)
            try {
                val result = submit(beforeFocusChange, "text", "MUST_NOT_BE_INSERTED")
                assertEquals("STALE_OBSERVATION", result)
            } catch (error: RuntimeException) {
                assertEquals("STALE_OBSERVATION", error.message)
            }
            assertEquals("adapter 中文 proof", text())
        } finally { session.close() }
    }
}
