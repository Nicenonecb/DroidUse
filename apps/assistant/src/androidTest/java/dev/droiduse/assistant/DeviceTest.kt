package dev.droiduse.assistant

import android.content.*
import android.os.*
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.ModelProfile
import dev.droiduse.agent.TaskLoop
import dev.droiduse.ipc.IExecutor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceTest {
    @Test fun privateBuiltInIsInstalledWithoutOverwritingSelection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.assets.list("")!!.contains("builtin-model.json"))
        val store=ProfileStore(context);val state=store.installBuiltInIfNeeded(context,store.load())
        val builtin=state.profiles.single { it.id=="builtin-qwen-max" };builtin.validate()
        assertEquals("qwen3.8-max-0902",builtin.model)
        assertFalse(String(File(context.noBackupFilesDir,"models.enc").readBytes(),Charsets.ISO_8859_1).contains(builtin.apiKey))
        val chosen=ModelProfile(name="custom",baseUrl="https://example.com/v1",model="fake",apiKey="synthetic")
        val custom=state.copy(profiles=state.profiles+chosen,activeId=chosen.id)
        assertEquals(chosen.id,store.installBuiltInIfNeeded(context,custom).activeId)
    }
    @Test fun modelPageProtectsWindowAndSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun click(label: String) {
                val until = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < until) {
                    val nodes = mutableListOf<AccessibilityNodeInfo>()
                    fun walk(node: AccessibilityNodeInfo?) {
                        if (node == null) return
                        nodes.add(node)
                        for (i in 0 until node.childCount) walk(node.getChild(i))
                    }
                    walk(instrumentation.uiAutomation.rootInActiveWindow)
                    for (node in nodes) {
                        if (node.text?.toString() != label) continue
                        var candidate: AccessibilityNodeInfo? = node
                        while (candidate != null) {
                            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                instrumentation.waitForIdleSync(); SystemClock.sleep(250); return
                            }
                            candidate = candidate.parent
                        }
                    }
                    SystemClock.sleep(100)
                }
                error("Page button not found: $label")
            }
            click("模型")
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            click("任务")
            scenario.onActivity { assertEquals(0,it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun encryptedProfilesRoundTripAndTamperDetection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileStore(context)
        val before = store.load()
        val file = File(context.noBackupFilesDir,"models.enc")
        val p = ModelProfile(name="instrumentation-only",baseUrl="https://example.com/v1",model="fake-model",apiKey="ONLY-SYNTHETIC-TEST-SECRET")
        try {
            store.save(ProfileStore.State(listOf(p),p.id))
            assertEquals(p,store.load().profiles.single()); assertEquals(p.id,store.load().activeId)
            assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains(p.apiKey))
            val encrypted = file.readBytes(); encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte(); file.writeBytes(encrypted)
            assertTrue(runCatching { store.load() }.isFailure)
            store.save(ProfileStore.State(emptyList(),null)); assertTrue(store.load().profiles.isEmpty())
        } finally { store.save(before) }
    }
    @Test fun binderSessionRequiresExplicitTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var service: IExecutor? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) { service=IExecutor.Stub.asInterface(b); latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName) { service=null }
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName("dev.droiduse.executor","dev.droiduse.executor.app.ExecutorService")),connection,Context.BIND_AUTO_CREATE))
        try {
            assertTrue(latch.await(10,TimeUnit.SECONDS)); val api = service!!
            assertEquals(3,api.capabilities.getInt("protocolVersion"))
            var modelCalls=0
            val model=object: TaskLoop.Model {
                override fun decide(task: String,frame: TaskLoop.Frame): TaskLoop.Decision { modelCalls++; error("Must not call model") }
                override fun verify(task: String,claim: String,frame: TaskLoop.Frame): TaskLoop.Verification { modelCalls++; error("Must not call model") }
                override fun cancel() {}
            }
            val loop=TaskLoop(BinderTaskExecutor(api, ""),model,{SystemClock.elapsedRealtime()},"搜索测试")
            assertEquals(TaskLoop.State.FAILED,loop.step()); assertEquals(0,modelCalls)
            assertEquals("TARGET_REQUIRED",api.beginSession(Binder()).getString("code"))
            assertEquals("INVALID_TARGET",api.beginTargetSession(Binder(),"dev.droiduse.assistant").getString("code"))
            assertTrue(runCatching { api.getStatus("wrong-id") }.isFailure)
        } finally { context.unbindService(connection) }
    }
}
