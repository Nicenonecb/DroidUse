package dev.droiduse.assistant

import android.content.*
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.*
import dev.droiduse.ipc.IExecutor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in: sends calculator screenshots to the user's configured model. */
class CalculatorModelTest {
    @Test fun calculateWithConfiguredModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineCalculator") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = ProfileStore(context).load()
        val profile = profiles.profiles.single { it.id == profiles.activeId }
        val ready = CountDownLatch(1)
        var api: IExecutor? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                api = IExecutor.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName("dev.droiduse.executor",
            "dev.droiduse.executor.app.ExecutorService")), connection, Context.BIND_AUTO_CREATE))
        val evidence = File(context.noBackupFilesDir, "calculator-model-test").apply { mkdirs() }
        val log = File(evidence, "events.jsonl").apply { writeText("") }
        var executor: BinderTaskExecutor? = null
        var loop: TaskLoop? = null
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val backend = BinderTaskExecutor(requireNotNull(api), "com.android.calculator2",
                when (OcrKind.selected(context)) {
                    OcrKind.PADDLE_TINY -> PaddleTinyOcr(context)
                    OcrKind.ML_KIT -> PhoneOcr(1)
                })
            executor = backend
            val observed = object : TaskLoop.Executor by backend {
                override fun observe(): TaskLoop.Frame = try { backend.observe().also {
                    File(evidence, "latest.png").writeBytes(android.util.Base64.decode(it.pngBase64, android.util.Base64.DEFAULT))
                    log.appendText(org.json.JSONObject().put("capture",it.id).put("ageMs",SystemClock.elapsedRealtime()-it.capturedAt).toString()+"\n")
                } } catch (error: Exception) {
                    log.appendText(org.json.JSONObject().put("captureError",error.javaClass.simpleName)
                        .put("message",TracePrivacy.redactKnownSecrets(error.message ?: "",listOf(profile.apiKey))).toString()+"\n")
                    throw error
                }
            }
            val delegate = VisionTaskModel(profile) { raw ->
                log.appendText(TracePrivacy.redactKnownSecrets(raw, listOf(profile.apiKey)) + "\n")
            }
            val model = object : TaskLoop.Model by delegate {
                override fun decide(task: String, frame: TaskLoop.Frame): TaskLoop.Decision = try {
                    delegate.decide(task, frame)
                } catch (error: Exception) {
                    log.appendText(org.json.JSONObject().put("modelError",error.javaClass.simpleName)
                        .put("message",TracePrivacy.redactKnownSecrets(error.message ?: "",listOf(profile.apiKey)).take(200)).toString()+"\n")
                    throw error
                }
            }
            val current = TaskLoop(observed, model, SystemClock::elapsedRealtime,
                "Clear previous input. Calculate 17*23 using the calculator buttons, then verify the displayed result.",
                maxSteps = 20, deadline = SystemClock.elapsedRealtime() + 180000,
                audit = { log.appendText(it.toString() + "\n") })
            loop = current
            do { val state = current.step() } while (state == TaskLoop.State.RUNNING)
            assertEquals(current.message, TaskLoop.State.COMPLETED, current.state)
        } finally {
            loop?.stop(); executor?.cancel(); context.unbindService(connection)
        }
    }
}
