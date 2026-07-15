package com.rhamers.osse.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Exercises the real `logcat` binary, so this must run as an instrumented test
// (Robolectric's host JVM has no logcat to shell out to).
@RunWith(AndroidJUnit4::class)
class LogExporterTest {

    @Test
    fun exportWritesNonEmptyFileNamedForThisProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        android.util.Log.i("LogExporterTest", "marker line for export test")

        val file = LogExporter.export(context)

        try {
            assertTrue(file.exists())
            assertTrue(file.name.startsWith("osse-log-"))
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(file.length() > 0)
        } finally {
            file.delete()
        }
    }
}
