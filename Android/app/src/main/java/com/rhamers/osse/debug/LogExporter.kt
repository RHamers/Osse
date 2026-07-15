package com.rhamers.osse.debug

import android.content.Context
import android.os.Process
import java.io.File

// Dumps this process's own logcat buffer to a file. Reading your own app's log lines via
// `logcat` needs no permission on API 24+ (logd restricts cross-UID reads); --pid filters
// out noise from every other running app/system process.
object LogExporter {
    fun export(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "osse-log-${System.currentTimeMillis()}.txt")
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        process.waitFor()
        return file
    }
}
