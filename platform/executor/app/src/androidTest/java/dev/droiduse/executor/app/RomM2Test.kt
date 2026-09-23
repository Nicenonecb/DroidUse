package dev.droiduse.executor.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.systemclient.*
import dev.droiduse.systemclient.DroidUseContract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RomM2Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val api get() = requireNotNull(RomSystemClient(context).service)
    private val results = LinkedBlockingQueue<OperationResult>()
    private val callback = object : IDroidUseCallback.Stub() {
        override fun onOperationResult(result: OperationResult) { results.add(result) }
        override fun onSessionEvent(event: SessionEvent) {}
        override fun onCapabilitiesChanged(snapshot: CapabilitySnapshot) {}
    }
    private fun open(target: String = "dev.droiduse.executor.test") = api.openSession(SessionSpec().apply {
        userId = 0; mode = MODE_ISOLATED_DISPLAY
        targetPackage = target; targetTaskId = -1
        requestedDisplayId = -1; requestedCapabilities = intArrayOf(7, 9)
        requestedScreenshotFps = 5; timeoutMs = 60_000
    }, Binder(), callback)
    private fun capture(handle: SessionHandle, name: String): Observation {
        SystemClock.sleep(350)
        val spec = ObservationSpec().apply {
            sessionId = handle.sessionId; epoch = handle.epoch; afterFrameId = -1; includeCapture = true
        }
        val deadline = SystemClock.elapsedRealtime() + 3_000
        var captured: Observation? = null
        while (captured == null) {
            try { captured = api.observe(handle, spec) }
            catch (error: RuntimeException) {
                if (error.message != "SCREENSHOT_NOT_READY" || SystemClock.elapsedRealtime() >= deadline) throw error
                SystemClock.sleep(200)
            }
        }
        val frame = captured
        assertEquals(handle.displayId, frame.displayId)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(frame.capture)).use { it.readBytes() }
        frame.capture = null
        val image = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
        assertEquals(frame.width,image.width); assertEquals(frame.height,image.height);image.recycle()
        File(context.getExternalFilesDir(null), "m2-$name.png").writeBytes(bytes)
        return frame
    }
    private fun execute(h: SessionHandle, frame: Observation, input: InputOperation): OperationRequest {
        val request = OperationRequest().apply {
            sessionId = h.sessionId; epoch = h.epoch; requestId = UUID.randomUUID().toString()
            expectedFrameId = frame.frameId; expectedWindowGeneration = frame.windowGeneration
            domain = DOMAIN_INPUT; this.input = input
        }
        assertTrue(api.execute(h,request).accepted)
        val result = requireNotNull(results.poll(5,TimeUnit.SECONDS))
        assertEquals(request.requestId,result.requestId);assertEquals(OK,result.code)
        return request
    }
    private fun proof(name: String): String = context.contentResolver.query(
        Uri.parse("content://dev.droiduse.m2.fixture/state"),null,null,null,null)!!.use {
        assertTrue(it.moveToFirst());it.getString(it.getColumnIndexOrThrow(name))
    }
    private fun rejected(code: String, block: () -> Unit) {
        try { block();fail("Expected $code") } catch (error: RuntimeException) {
            assertTrue(error.toString(),error.message?.contains(code)==true)
        }
    }
    private fun assertReleased(display: Int) {
        val displays = context.getSystemService(DisplayManager::class.java)
        val deadline = SystemClock.elapsedRealtime()+5000
        while(displays.getDisplay(display)!=null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertNull("virtual display leaked",displays.getDisplay(display))
    }
    @Test fun openCaptureInputAndClose() {
        assertTrue(api.capabilities.coreReady)
        val h = open()
        try {
            assertTrue(h.displayId > 0)
            SystemClock.sleep(1500)
            var frame = capture(h,"initial")
            assertEquals(h.displayId.toString(),proof("display"))
            val request = execute(h,frame,InputOperation().apply { kind=INPUT_TAP;x1=frame.width/2;y1=110 })
            assertFalse("duplicate action admitted",api.execute(h,request).accepted)
            SystemClock.sleep(300);assertEquals("1",proof("clicks"))
            frame=capture(h,"tap")
            execute(h,frame,InputOperation().apply { kind=INPUT_TAP;x1=frame.width/2;y1=290 })
            frame=capture(h,"focus")
            execute(h,frame,InputOperation().apply { kind=INPUT_TEXT;text="DroidUse 中文输入 123";editorGeneration=frame.editorGeneration })
            SystemClock.sleep(500);assertEquals("DroidUse 中文输入 123",proof("text"))
            frame=capture(h,"text")
            execute(h,frame,InputOperation().apply { kind=INPUT_SWIPE;x1=frame.width/2;y1=frame.height*3/4;x2=x1;y2=frame.height/2 })
            SystemClock.sleep(500);assertTrue("swipe had no effect",proof("scroll").toInt()>0)
            capture(h,"swipe")
            val stale = request.apply { requestId=UUID.randomUUID().toString() }
            rejected(STALE_OBSERVATION) { api.execute(h,stale) }
            rejected(STALE_TARGET) { api.execute(h,OperationRequest().apply {
                sessionId=h.sessionId;epoch=h.epoch;requestId=UUID.randomUUID().toString()
                expectedFrameId=-1;expectedWindowGeneration=-1;domain=DOMAIN_APP_TASK
                app=AppOperation().apply { kind=APP_LAUNCH;packageName="com.android.settings" }
            }) }
            val savedEpoch=h.epoch;h.epoch++
            try { rejected(STALE_SESSION) { capture(h,"invalid") } } finally { h.epoch=savedEpoch }
        } finally { api.closeSession(h,0) }
        assertReleased(h.displayId)
    }
    @Test fun staticDisplaySupportsRepeatedFreshCaptures() {
        val h = open()
        try {
            SystemClock.sleep(1500)
            var previous = -1L
            repeat(6) { index ->
                val frame = capture(h, "static-$index")
                assertTrue("capture must be fresh without injecting input", frame.frameId > previous)
                previous = frame.frameId
            }
        } finally { api.closeSession(h, 0) }
        assertReleased(h.displayId)
    }
    @Test fun calculatorIsVisibleAfterLaunchSettles() {
        val h=open("com.android.calculator2")
        try {
            SystemClock.sleep(2000)
            capture(h,"calculator-settled")
            val file=File(context.getExternalFilesDir(null),"m2-calculator-settled.png")
            val bitmap=requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
            try {
                var visible=0
                for(y in 0 until bitmap.height step 20) for(x in 0 until bitmap.width step 20) {
                    val c=bitmap.getPixel(x,y)
                    if(android.graphics.Color.red(c)>30 || android.graphics.Color.green(c)>30 || android.graphics.Color.blue(c)>30) visible++
                }
                assertTrue("calculator remained black after launch settled",visible>100)
            } finally { bitmap.recycle() }
        } finally { api.closeSession(h,0) }
        assertReleased(h.displayId)
    }
    @Test fun secureWindowPixelsAreNotCaptured() {
        val h = open()
        fun show(secure: Boolean) {
            val command = "am start --display ${h.displayId} -n dev.droiduse.executor.test/dev.droiduse.executor.app.SecureFixtureActivity --ez secure $secure"
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
            SystemClock.sleep(700)
            assertEquals(h.displayId.toString(), proof("display"))
            assertEquals(if (secure) "1" else "0", proof("secure"))
        }
        fun magentaPixels(): Int {
            val frame = api.observe(h, ObservationSpec().apply {
                sessionId=h.sessionId; epoch=h.epoch; afterFrameId=-1; includeCapture=true
            })
            val bytes=ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(frame.capture)).use { it.readBytes() }
            val bitmap=requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try {
                var count=0
                for (y in bitmap.height/4 until bitmap.height*3/4 step 10)
                    for (x in bitmap.width/4 until bitmap.width*3/4 step 10) {
                        val color=bitmap.getPixel(x,y)
                        if (android.graphics.Color.red(color)>200 && android.graphics.Color.blue(color)>200 && android.graphics.Color.green(color)<60) count++
                    }
                return count
            } finally { bitmap.recycle() }
        }
        try {
            show(false)
            assertTrue("unprotected control must be visible", magentaPixels()>100)
            show(true)
            repeat(3) {
                SystemClock.sleep(350)
                try { assertEquals("secure pixels leaked",0,magentaPixels()) }
                catch (error: RuntimeException) {
                    assertEquals("android.os.ServiceSpecificException",error.javaClass.name)
                    assertTrue(error.toString(),error.message in setOf("SCREENSHOT_NOT_READY", "PROTECTED_CONTENT"))
                }
            }
        } finally { api.closeSession(h,0) }
        assertReleased(h.displayId)
    }
    @Test fun repeatedSessionsReleaseDisplays() {
        repeat(3) { val h=open();api.closeSession(h,0);assertReleased(h.displayId) }
    }
    @Test fun pauseRejectsInputAndResumeRestoresObservation() {
        val h=open()
        try {
            api.updateSession(h,SessionUpdate().apply { sessionId=h.sessionId;epoch=h.epoch;kind=SESSION_UPDATE_PAUSE })
            rejected(STALE_SESSION) { capture(h,"paused") }
            api.updateSession(h,SessionUpdate().apply { sessionId=h.sessionId;epoch=h.epoch;kind=SESSION_UPDATE_RESUME })
            SystemClock.sleep(1000);capture(h,"resumed")
        } finally { api.closeSession(h,0) }
        assertReleased(h.displayId)
    }
    @Test fun lockRevokesSession() {
        val h=open()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            SystemClock.sleep(1000)
            automation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            val keyguard=context.getSystemService(android.app.KeyguardManager::class.java)
            val deadline=SystemClock.elapsedRealtime()+20_000
            while(!keyguard.isDeviceLocked && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
            assertTrue("phone did not lock",keyguard.isDeviceLocked)
            try { capture(h,"locked");fail("locked capture succeeded") }
            catch (error: RuntimeException) {
                assertTrue(error.toString(), error.message in setOf(USER_NOT_UNLOCKED,STALE_SESSION))
            }
            val displays=context.getSystemService(DisplayManager::class.java)
            val cleanupDeadline=SystemClock.elapsedRealtime()+15_000
            while(displays.getDisplay(h.displayId)!=null && SystemClock.elapsedRealtime()<cleanupDeadline) SystemClock.sleep(100)
            assertNull("lock did not release display",displays.getDisplay(h.displayId))
        } finally {
            try { api.closeSession(h,0) } catch (error: RuntimeException) {
                if(error.message!=STALE_SESSION) throw error
            }
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }
    @Test fun lockedDeviceRejectsOpen() {
        assertTrue(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        rejected(USER_NOT_UNLOCKED) { open() }
    }
}
