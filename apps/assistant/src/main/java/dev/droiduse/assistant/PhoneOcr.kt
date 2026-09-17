package dev.droiduse.assistant

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.droiduse.agent.ReadingProgress
import java.util.concurrent.TimeUnit

/** Bundled Chinese model. Recognition runs locally on Android, without a model download. */
class PhoneOcr(private val scale: Int = 2) : OcrEngine {
    data class Result(val rows: List<ReadingProgress.Row>,val targets: List<ReadingProgress.Row>)
    private val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    @Synchronized override fun recognize(bitmap: Bitmap): Result {
        val full=recognizeAtScale(bitmap,scale)
        // Fanqie's bold chapter header can be confused with 韋 even at 2x. Re-read pixels;
        // never substitute characters or infer a chapter from the requested task.
        val clean=Regex("^第\\s*[一二三四1234]\\s*章")
        val suspect=full.rows.filter { it.y in 40..140 &&
            Regex("^第\\s*[一二三四1234]").containsMatchIn(it.text) && !clean.containsMatchIn(it.text) }
        if(bitmap.width!=720 || bitmap.height!=1280 || suspect.size!=1) return full
        val crop=Bitmap.createBitmap(bitmap,0,40,720,110)
        val corrected=try { recognizeAtScale(crop,3).rows.filter { clean.containsMatchIn(it.text) }
            .map { it.copy(y=it.y+40,top=it.top+40,bottom=it.bottom+40) }.singleOrNull() } finally { crop.recycle() }
        if(corrected==null) return full
        val rows=(full.rows-suspect.toSet()+corrected).sortedWith(compareBy({it.y},{it.x}))
        return Result(rows,(full.targets-suspect.toSet()+corrected).distinct())
    }
    private fun recognizeAtScale(bitmap: Bitmap,scale: Int): Result {
        val input=if(scale==1) bitmap else Bitmap.createScaledBitmap(bitmap,bitmap.width*scale,bitmap.height*scale,true)
        val text=try { Tasks.await(recognizer.process(InputImage.fromBitmap(input,0)),15,TimeUnit.SECONDS) }
            finally { if(input !== bitmap) input.recycle() }
        val lines=text.textBlocks.flatMap { it.lines }
        val rows=lines.mapNotNull { line -> line.boundingBox?.let { ReadingProgress.Row(line.text,it.centerX()/scale,it.centerY()/scale,line.confidence,it.left/scale,it.top/scale,it.right/scale,it.bottom/scale) } }.sortedWith(compareBy({it.y},{it.x}))
        val elements=lines.flatMap { it.elements }.mapNotNull { e -> e.boundingBox?.let { ReadingProgress.Row(e.text,it.centerX()/scale,it.centerY()/scale,e.confidence,it.left/scale,it.top/scale,it.right/scale,it.bottom/scale) } }
        return Result(rows,(rows+elements).distinct())
    }
    @Synchronized override fun close() { recognizer.close() }
}
