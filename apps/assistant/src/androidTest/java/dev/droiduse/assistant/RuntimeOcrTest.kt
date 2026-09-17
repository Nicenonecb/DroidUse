package dev.droiduse.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RuntimeOcrTest {
    @Test fun productionAssetsCoordinatesConfidenceAndRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("都市脑洞", 100f, 180f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f })
        }
        try {
            // Load from the installed main APK, not instrumentation benchmark assets.
            repeat(2) {
                val engine = PaddleTinyOcr(context)
                try {
                    val row = engine.recognize(bitmap).rows.single { it.text.contains("都市脑洞") }
                    assertTrue(row.x in 100..300)
                    assertTrue(row.y in 130..185)
                    assertTrue(requireNotNull(row.confidence) in 0f..1f)
                    assertTrue(row.left < row.right && row.top < row.bottom)
                } finally { engine.close(); engine.close() }
                assertThrows(IllegalStateException::class.java) { engine.recognize(bitmap) }
            }
        } finally { bitmap.recycle() }
    }
}
