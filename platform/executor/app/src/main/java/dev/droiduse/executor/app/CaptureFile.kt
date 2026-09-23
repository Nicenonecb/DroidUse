package dev.droiduse.executor.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File

/** Converts the trusted ROM stream into a bounded, unlinked, read-only file for IPC. */
internal object CaptureFile {
    private const val MAX_BYTES = 3_145_728
    fun copy(source: ParcelFileDescriptor, directory: File): ParcelFileDescriptor = source.use {
        val file = File.createTempFile("capture-", ".png", directory)
        var result: ParcelFileDescriptor? = null
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var total = 0
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                file.outputStream().use { output ->
                    result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    check(file.delete()) { "CAPTURE_FILE_CLEANUP_FAILED" }
                    val poll = StructPollfd().apply {
                        fd = source.fileDescriptor
                        events = OsConstants.POLLIN.toShort()
                    }
                    val buffer = ByteArray(8192)
                    while (true) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        check(remaining > 0 && Os.poll(arrayOf(poll), remaining.toInt()) > 0) {
                            "CAPTURE_TIMEOUT"
                        }
                        check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) == 0) {
                            "CAPTURE_FAILED"
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "CAPTURE_TOO_LARGE" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(total > 0) { "CAPTURE_EMPTY" }
            requireNotNull(result)
        } catch (error: Exception) {
            result?.close()
            throw error
        } finally {
            // The open descriptor owns the inode; no screenshot survives a process crash.
            file.delete()
        }
    }
}
