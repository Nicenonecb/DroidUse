package dev.droiduse.assistant

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import dev.droiduse.agent.ReadingProgress
import kotlinx.coroutines.runBlocking

interface OcrEngine : AutoCloseable {
    fun recognize(bitmap: Bitmap): PhoneOcr.Result
}

enum class OcrKind(val label: String) {
    PADDLE_TINY("PaddleOCR tiny"), ML_KIT("ML Kit");
    companion object {
        fun selected(context: Context): OcrKind = entries.firstOrNull {
            it.name == context.getSharedPreferences("ocr", Context.MODE_PRIVATE).getString("engine", null)
        } ?: PADDLE_TINY
        fun select(context: Context, kind: OcrKind) {
            context.getSharedPreferences("ocr", Context.MODE_PRIVATE).edit().putString("engine", kind.name).apply()
        }
    }
}

/** Lazy initialization keeps model loading off the UI thread. No silent engine fallback. */
class PaddleTinyOcr(context: Context) : OcrEngine {
    private val app = context.applicationContext
    private var engine: PaddleOCR? = null
    private var closed = false

    @Synchronized override fun recognize(bitmap: Bitmap): PhoneOcr.Result = runBlocking {
        check(!closed) { "OCR_ENGINE_CLOSED" }
        val active = engine ?: run {
            check(OpenCVUtils.init(app)) { "OCR_INITIALIZATION_FAILED" }
            PaddleOCR.create(app, PaddleOCRConfig(), EngineConfig(numThreads = 2),
                "paddle/tiny/det/inference.onnx", "paddle/tiny/rec/inference.onnx",
                "paddle/tiny/rec/inference.yml").also { engine = it }
        }
        val rows = active.recognize(bitmap).results.map { item ->
            val points = item.box.points
            ReadingProgress.Row(item.text, points.map { it.x }.average().toInt(),
                points.map { it.y }.average().toInt(), item.confidence,
                points.minOf { it.x }.toInt(), points.minOf { it.y }.toInt(),
                points.maxOf { it.x }.toInt(), points.maxOf { it.y }.toInt())
        }.sortedWith(compareBy({ it.y }, { it.x }))
        PhoneOcr.Result(rows, rows)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val previous = engine
        engine = null
        if (previous != null) runBlocking { previous.release() }
    }
}
