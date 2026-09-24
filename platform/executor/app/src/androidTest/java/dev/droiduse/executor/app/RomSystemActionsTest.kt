package dev.droiduse.executor.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.systemclient.DroidUseContract.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in, synthetic app only. Global settings tests save and restore the original values. */
class RomSystemActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = "dev.droiduse.executor.test"
    private val fixture = Uri.parse("content://${M3SystemFixture.AUTHORITY}")
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private fun fixture(method: String, content: String? = null) =
        requireNotNull(context.contentResolver.call(fixture, method, content, null))
    private fun granted(permission: String) = context.packageManager.checkPermission(permission, root) == PackageManager.PERMISSION_GRANTED
    private fun restore(permission: String, before: Boolean) { shell("pm ${if (before) "grant" else "revoke"} $root $permission") }
    private fun session(global: Boolean = false, target: String = root): RomSession {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3System") == "true")
        val api = requireNotNull(RomSystemClient(context).service)
        assertTrue("Requires new M3 system-actions ROM", api.capabilities.capabilities.any { it.reason == "TASK_APP_NOTIFICATIONS" })
        return RomSession(api, context.cacheDir).also { it.open(target, global) }
    }
    private fun frame(session: RomSession): Bundle {
        SystemClock.sleep(300)
        return session.observe().also { it.getParcelable("captureFile", ParcelFileDescriptor::class.java)?.close() }
    }
    private fun targets(frame: Bundle) = frame.getParcelableArrayList("targets", Bundle::class.java).orEmpty()
    private fun action(session: RomSession, frame: Bundle, kind: String, value: String? = null, label: String = ""): String {
        val target = targets(frame).first { it.getString("kind") == kind && it.getString("label").orEmpty().contains(label) }
        return session.execute(UUID.randomUUID().toString(), Bundle(frame).apply {
            putString("kind",kind); putString("targetId",target.getString("targetId")); value?.let { putString("value",it) }
        })
    }
    private fun awaitProof(predicate: (Bundle) -> Boolean): Bundle {
        val end = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val proof = fixture("status")
            if (predicate(proof)) return proof
            check(SystemClock.elapsedRealtime() < end) { "Synthetic proof did not arrive" }
            SystemClock.sleep(100)
        }
    }

    @Test fun syntheticNotificationReplyOpenClearAndStaleReplacement() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3System") == "true")
        val before = granted(Manifest.permission.POST_NOTIFICATIONS)
        shell("pm grant $root android.permission.POST_NOTIFICATIONS")
        val session = session()
        try {
            fixture("post")
            var f = frame(session)
            assertTrue(f.getString("systemContext").orEmpty().contains("M3 SYNTHETIC"))
            assertFalse(targets(f).any { it.getString("kind") in setOf("set_wifi","set_volume","set_brightness","connect_wifi") })
            assertEquals("EXECUTED",action(session,f,"notification_reply","M3 synthetic reply"))
            awaitProof { it.getString("reply") == "M3 synthetic reply" }
            f = frame(session)
            assertEquals("EXECUTED",action(session,f,"notification_open"))
            assertEquals(f.getInt("displayId"),awaitProof { it.getInt("openedDisplay") > 0 }.getInt("openedDisplay"))
            f = frame(session)
            fixture("post","replacement content")
            SystemClock.sleep(200)
            try {
                assertEquals(STALE_OBSERVATION, action(session,f,"notification_clear"))
            } catch (error: RuntimeException) { assertEquals(STALE_OBSERVATION,error.message) }
            assertEquals(1,fixture("status").getInt("count"))
            assertEquals("EXECUTED",action(session,frame(session),"notification_clear"))
            awaitProof { it.getInt("count") == 0 }
        } finally { session.close(); fixture("clear"); restore(Manifest.permission.POST_NOTIFICATIONS,before) }
    }

    @Test fun otherTaskCannotObserveFixtureNotificationsOrPermissions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3System") == "true")
        val before = granted(Manifest.permission.POST_NOTIFICATIONS)
        shell("pm grant $root android.permission.POST_NOTIFICATIONS")
        fixture("post")
        val session = session(target = "org.lineageos.twelve")
        try {
            val f = frame(session)
            assertFalse(f.getString("systemContext").orEmpty().contains("M3 SYNTHETIC"))
            assertFalse(targets(f).any { it.getString("label").orEmpty().contains(root) &&
                it.getString("kind") in setOf("notification_open","notification_reply","notification_clear","permission_grant","permission_revoke") })
        } finally { session.close(); fixture("clear"); restore(Manifest.permission.POST_NOTIFICATIONS,before) }
    }

    @Test fun runtimePermissionRoundTripAndStaleHandleRejected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3System") == "true")
        val permission = Manifest.permission.READ_MEDIA_AUDIO
        val before = granted(permission)
        shell("pm revoke $root $permission")
        val session = session()
        try {
            val stale = frame(session)
            val current = frame(session)
            assertFalse(targets(current).any { it.getString("label").orEmpty().contains("android.permission.CAMERA") })
            assertEquals(STALE_OBSERVATION,action(session,stale,"permission_grant",label=permission))
            assertEquals("EXECUTED",action(session,current,"permission_grant",label=permission))
            assertTrue(granted(permission))
            assertEquals("EXECUTED",action(session,frame(session),"permission_revoke",label=permission))
            assertFalse(granted(permission))
        } finally { session.close(); restore(permission,before) }
    }

    @Test fun optedInVolumeAndBrightnessRoundTrip() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m3System") == "true")
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val oldVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val oldBrightness = shell("settings get system screen_brightness").toInt()
        val oldMode = shell("settings get system screen_brightness_mode").toInt()
        val session = session(global=true)
        try {
            val f = frame(session)
            assertTrue(targets(f).any { it.getString("kind") == "set_wifi" })
            val desired = if (oldVolume == 0) "25%" else "0%"
            assertEquals("EXECUTED",action(session,f,"set_volume",label=desired))
            assertNotEquals(oldVolume,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            val percent = if (oldBrightness == 128) 25 else 50
            assertEquals("EXECUTED",action(session,frame(session),"set_brightness",label="$percent%"))
            assertEquals(0,shell("settings get system screen_brightness_mode").toInt())
            assertEquals(Math.round(255 * percent / 100f),shell("settings get system screen_brightness").toInt())
        } finally {
            session.close()
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,oldVolume,0)
            shell("settings put system screen_brightness $oldBrightness")
            shell("settings put system screen_brightness_mode $oldMode")
        }
    }

    @Test fun wifiToggleAndSavedNetworkReconnect() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("m3System") == "true" && args.getString("m3Wifi") == "true")
        val ssid = requireNotNull(args.getString("m3WifiSsid"))
        require(ssid.length in 1..32 && ssid.none { it == '\n' || it == '\r' })
        val originallyEnabled = shell("cmd wifi status").contains("Wifi is enabled")
        assumeTrue("Reconnect test starts with Wi-Fi enabled", originallyEnabled)
        val session = session(global=true)
        fun awaitWifi(enabled: Boolean) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (shell("cmd wifi status").contains("Wifi is enabled") != enabled) {
                check(SystemClock.elapsedRealtime() < end) { "Wi-Fi transition timed out" }
                SystemClock.sleep(300)
            }
        }
        try {
            val f = frame(session)
            assertTrue("Saved network must exist before disconnecting",targets(f).any {
                it.getString("kind") == "connect_wifi" && it.getString("label").orEmpty().contains(ssid)
            })
            assertEquals("EXECUTED",action(session,f,"set_wifi"))
            awaitWifi(false)
            assertEquals("EXECUTED",action(session,frame(session),"set_wifi"))
            awaitWifi(true)
            assertEquals("EXECUTED",action(session,frame(session),"connect_wifi",label=ssid))
            val end = SystemClock.elapsedRealtime() + 30000
            while (true) {
                val state = frame(session).getString("systemContext").orEmpty()
                if (state.contains(ssid) && state.contains("默认网络使用 Wi-Fi=true") &&
                    state.contains("默认网络通过系统联网验证=true")) break
                check(SystemClock.elapsedRealtime() < end) { "Wi-Fi network not validated" }
            }
        } finally { session.close(); shell("svc wifi enable") }
    }
}
