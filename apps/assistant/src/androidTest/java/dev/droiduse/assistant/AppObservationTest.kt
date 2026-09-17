package dev.droiduse.assistant

import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppObservationTest {
    @Test fun hiddenDisplaySwitchReportsActualAppAndRecordsTransition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneBridge")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val executor=PhoneTaskExecutor(context)
        try {
            assertTrue(executor.begin())
            val first=executor.observe()
            assertEquals("com.dragon.read",first.app)
            assertTrue("read_chapters" in first.supportedActions)
            val directory=context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("phone-run-") }.maxBy { it.lastModified() }
            val command="am start -W --display ${first.display} -n dev.droiduse.probe/.LabActivity"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            Thread.sleep(500)
            val second=executor.observe()
            assertEquals(first.display,second.display)
            assertEquals("dev.droiduse.probe",second.app)
            assertFalse("read_chapters" in second.supportedActions)
            val events=java.io.File(directory,"scene-events.jsonl").readLines().map(::JSONObject)
            val change=events.single { it.getString("event")=="APP_CHANGED" }
            assertEquals(first.app,change.getString("fromApp"))
            assertEquals(second.app,change.getString("toApp"))
            assertEquals(second.id,change.getString("frameId"))
            val metadata=directory.listFiles()!!.filter { it.extension=="json" }
                .map { JSONObject(it.readText()) }.single { it.optString("frameId")==second.id }
            assertEquals(second.app,metadata.getString("app"))
        } finally { executor.cancel() }
    }
}
