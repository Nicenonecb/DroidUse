package dev.droiduse.assistant

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.SensitiveSceneException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires the matching, bootstrapped debug PhoneBridge; no cloud model is invoked. */
class PrivateSceneTest {
    @Test fun sensitiveFixtureNeverReachesPersistentCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneBridge")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val executor=PhoneTaskExecutor(context)
        try {
            assertTrue(executor.begin())
            val first=executor.observe()
            val directory=context.noBackupFilesDir.listFiles()!!.filter { it.name.startsWith("phone-run-") }.maxBy { it.lastModified() }
            val count=directory.listFiles()!!.count { it.extension=="png" }
            shell("am start --display ${first.display} -n dev.droiduse.probe/.LabActivity")
            Thread.sleep(500)
            shell("am broadcast -a dev.droiduse.lab.COMMAND -p dev.droiduse.probe --es op sensitive_test")
            Thread.sleep(500)
            assertThrows(SensitiveSceneException::class.java) { executor.observe() }
            assertEquals(count,directory.listFiles()!!.count { it.extension=="png" })
            for(file in directory.listFiles()!!.filter { it.extension in setOf("json","jsonl") })
                assertFalse(file.readText().contains("123456"))
            assertTrue(directory.resolve("scene-events.jsonl").readText().contains("SENSITIVE_SCENE"))
            assertFalse(shell("ls /data/local/tmp").lineSequence().any { it.trim()=="droiduse-probe.png" })
        } finally { executor.cancel() }
    }
}
