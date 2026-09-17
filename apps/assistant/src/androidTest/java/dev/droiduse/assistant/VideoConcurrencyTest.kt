package dev.droiduse.assistant

import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Start VideoActivity on display 0 first. This test never launches an assistant Activity. */
class VideoConcurrencyTest {
    @Test fun repeatedBackgroundCaptureAndTinyWhileMainVideoPlays() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("videoConcurrency")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        OcrKind.select(context,OcrKind.PADDLE_TINY)
        val executor=PhoneTaskExecutor(context)
        val durations=mutableListOf<Long>()
        val power=context.getSystemService(PowerManager::class.java)
        val thermalBefore=power.currentThermalStatus
        val start=SystemClock.elapsedRealtime();var maxPss=0
        try {
            assertTrue(executor.begin())
            while(SystemClock.elapsedRealtime()-start<40000) {
                val tick=SystemClock.elapsedRealtime();val frame=executor.observe()
                assertTrue(frame.display>0)
                durations.add(SystemClock.elapsedRealtime()-tick)
                val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory);maxPss=maxOf(maxPss,memory.totalPss)
            }
        } finally { executor.cancel() }
        val end=SystemClock.elapsedRealtime()
        assertTrue(durations.size>=15)
        val result=JSONObject().put("startElapsedMs",start).put("endElapsedMs",end)
            .put("observations",durations.size).put("observeTotalMs",durations.sum())
            .put("observeMeanMs",durations.average()).put("maxSampledPssKb",maxPss)
            .put("thermalBefore",thermalBefore).put("thermalAfter",power.currentThermalStatus)
        File(context.noBackupFilesDir,"video-concurrency.json").writeText(result.toString(2))
        println("VIDEO_CONCURRENCY $result")
    }
}
