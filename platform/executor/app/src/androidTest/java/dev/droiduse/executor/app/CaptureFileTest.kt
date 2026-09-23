package dev.droiduse.executor.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CaptureFileTest {
    private val directory get() = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "capture-test"
    ).apply { mkdirs() }

    @Test fun pipeBecomesBoundedFileWithoutLeavingScreenshotOnDisk() {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(1, 2, 3)) }
        val dir = directory
        CaptureFile.copy(pipe[0], dir).use {
            assertEquals(3L, it.statSize)
            assertArrayEquals(byteArrayOf(1, 2, 3), ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() })
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun oversizedCaptureIsRejectedAndDeleted() {
        val dir = directory
        val source = File.createTempFile("source", ".bin", dir)
        try {
            java.io.RandomAccessFile(source, "rw").use { it.setLength(3_145_729) }
            try {
                CaptureFile.copy(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), dir).close()
                fail("oversized capture accepted")
            } catch (error: IllegalArgumentException) { assertEquals("CAPTURE_TOO_LARGE", error.message) }
            assertEquals(listOf(source.name), dir.listFiles()!!.map { it.name })
        } finally { source.delete() }
    }

    @Test fun stalledPipeTimesOutAndDeletesPartialFile() {
        val pipe = ParcelFileDescriptor.createPipe()
        val dir = directory
        try {
            try {
                CaptureFile.copy(pipe[0], dir).close()
                fail("stalled pipe accepted")
            } catch (error: IllegalStateException) { assertEquals("CAPTURE_TIMEOUT", error.message) }
            assertEquals(0, dir.listFiles()!!.size)
        } finally { pipe[1].close() }
    }
}
