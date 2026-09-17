package dev.droiduse.assistant

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import dev.droiduse.agent.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/** Opt-in real-image benchmark. Accuracy mismatches are recorded, never hidden by a passing assertion. */
class PaddleOcrBenchmarkTest {
    @Test fun capturedScreensOnPhone() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val kind=args.getString("ocrModel","")
        assumeTrue(kind in listOf("tiny","small","mlkit"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val context=object : ContextWrapper(instrumentation.context) {
            override fun getApplicationContext(): Context=this
        }
        val out=File(target.noBackupFilesDir,"ocr-benchmark-$kind").apply { mkdirs() }
        val results=File(out,"results.jsonl").apply { writeText("") }
        fun pss(): Int { val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);return info.totalPss }
        val power=target.getSystemService(PowerManager::class.java)
        val before=pss();val thermalBefore=power.currentThermalStatus
        val start=SystemClock.elapsedRealtime()
        if(kind!="mlkit") check(com.paddle.ocr.util.OpenCVUtils.init(context)) { "OpenCV initialization failed" }
        val paddle=if(kind!="mlkit") PaddleOCR.create(context,PaddleOCRConfig(),EngineConfig(numThreads=2),
            "paddle/$kind/det/inference.onnx","paddle/$kind/rec/inference.onnx","paddle/$kind/rec/inference.yml") else null
        val mlkit=if(kind=="mlkit") PhoneOcr() else null
        val loadMs=SystemClock.elapsedRealtime()-start
        val samples=JSONArray(context.assets.open("ocr-samples.json").bufferedReader().use { it.readText() })
        val progress=ReadingProgress();var coverage=true;var completed=false
        var expectedCount=0;var matchedCount=0;var pageCount=0;var pageMatches=0;var maxPss=pss()
        val durations=mutableListOf<Long>()
        fun normalized(s: String)=Normalizer.normalize(s,Normalizer.Form.NFKC).filter { it.isLetterOrDigit() }
        try {
            for(i in 0 until samples.length()) {
                val sample=samples.getJSONObject(i)
                val bitmap=context.assets.open(sample.getString("asset")).use { BitmapFactory.decodeStream(it) }!!
                val tick=SystemClock.elapsedRealtime()
                val raw=paddle?.recognize(bitmap)
                val rows=raw?.results?.map { item ->
                    ReadingProgress.Row(item.text,item.box.points.map { it.x }.average().toInt(),item.box.points.map { it.y }.average().toInt())
                } ?: mlkit!!.recognize(bitmap).rows
                val ms=SystemClock.elapsedRealtime()-tick;durations.add(ms);bitmap.recycle()
                val text=rows.joinToString("\n") { it.text };val flat=normalized(text)
                val expected=sample.getJSONArray("expected");val misses=JSONArray()
                for(j in 0 until expected.length()) { expectedCount++;val value=expected.getString(j)
                    if(flat.contains(normalized(value))) matchedCount++ else misses.put(value)
                }
                var pageOk=true
                if(!sample.isNull("page")) {
                    pageCount++
                    val found=rows.filter { it.y>1050 }.mapNotNull { Regex("(\\d+)\\s*/\\s*\\d+").matchEntire(it.text.trim())?.groupValues?.get(1)?.toIntOrNull() }.toSet()
                    pageOk=found==setOf(sample.getInt("page"));if(pageOk) pageMatches++
                }
                var continuityError: String?=null
                if(sample.getBoolean("reading") && coverage) {
                    try { completed=progress.accept(rows) } catch(e: IllegalArgumentException) { coverage=false;continuityError=e.message }
                }
                val memory=pss();maxPss=maxOf(maxPss,memory)
                val record=JSONObject().put("id",sample.getString("id")).put("elapsedMs",ms).put("pssKb",memory)
                    .put("pageCorrect",pageOk).put("misses",misses).put("continuityError",continuityError)
                    .put("text",text).put("rows",JSONArray(rows.map { JSONObject().put("text",it.text).put("x",it.x).put("y",it.y) }))
                if(raw!=null) record.put("detMs",raw.detectionTimeMs).put("recMs",raw.recognitionTimeMs)
                    .put("confidence",JSONArray(raw.results.map { it.confidence }))
                results.appendText(record.toString()+"\n")
                println("OCR_BENCH $kind ${sample.getString("id")} ms=$ms page=$pageOk misses=${misses.length()}")
            }
        } finally { paddle?.release();mlkit?.close() }
        val sorted=durations.sorted()
        val summary=JSONObject().put("model",kind).put("threads",if(paddle!=null) 2 else JSONObject.NULL)
            .put("samples",durations.size).put("loadMs",loadMs).put("totalOcrMs",durations.sum())
            .put("medianMs",sorted[sorted.size/2]).put("p95Ms",sorted[(sorted.size*0.95).toInt().coerceAtMost(sorted.lastIndex)])
            .put("firstInferenceMs",durations.first()).put("expectedTextCount",expectedCount).put("matchedTextCount",matchedCount)
            .put("pageCount",pageCount).put("pageMatches",pageMatches).put("readingComplete",coverage && completed)
            .put("baselinePssKb",before).put("maxSampledPssKb",maxPss).put("thermalBefore",thermalBefore).put("thermalAfter",power.currentThermalStatus)
        File(out,"summary.json").writeText(summary.toString(2));println("OCR_SUMMARY $summary")
    }
}
