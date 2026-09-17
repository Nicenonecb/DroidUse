package dev.droiduse.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import dev.droiduse.agent.ReadingProgress
import org.junit.Assert.*
import org.junit.Test

class PhoneOcrTest {
    @Test fun croppedChapterHeading() {
        val context=InstrumentationRegistry.getInstrumentation().context
        org.junit.Assume.assumeTrue(context.assets.list("")!!.contains("chapter-heading.png"))
        val bitmap=context.assets.open("chapter-heading.png").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        PhoneOcr().use { ocr ->
            val result=ocr.recognize(bitmap)
            val progress=ReadingProgress()
            assertFalse(progress.accept(result.rows))
            assertEquals(1,progress.chapter)
            assertEquals(1,progress.page)
        }
        bitmap.recycle()
    }
    @Test fun compareScalesOnCapturedFirstPage() {
        val context=InstrumentationRegistry.getInstrumentation().context
        org.junit.Assume.assumeTrue(context.assets.list("")!!.contains("first-chapter.png"))
        val bitmap=context.assets.open("first-chapter.png").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        for(scale in listOf(1,2,3)) PhoneOcr(scale).use { ocr ->
            val start=android.os.SystemClock.elapsedRealtime();val result=ocr.recognize(bitmap)
            if(scale==2) { val p=ReadingProgress();assertFalse(p.accept(result.rows));assertEquals(1,p.page) }
            println("OCR_SCALE=$scale ms=${android.os.SystemClock.elapsedRealtime()-start} headers=${result.rows.filter { it.y<350 }.map { it.text }}")
        }
        bitmap.recycle()
    }

    @Test fun bundledChineseOcrRecognizesControlsAndPageNumberOnPhone() {
        val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=32f }
        canvas.drawText("都市脑洞",100f,180f,paint)
        canvas.drawText("第1章 开始",100f,260f,paint)
        canvas.drawText("小说正文，这是手机本地识别。",50f,500f,paint)
        canvas.drawText("1/100",570f,1190f,paint)
        PhoneOcr().use { ocr ->
            val result=ocr.recognize(bitmap)
            assertTrue(result.rows.any { it.text.replace(" ","").contains("都市脑洞") })
            assertFalse(ReadingProgress().accept(result.rows))
        }
        bitmap.recycle()
        assertEquals("dev.droiduse.assistant",InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
