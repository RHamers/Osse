package com.rhamers.osse.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrCodeGeneratorTest {

    @Test
    fun generateReturnsNonEmptySquareBitmapForReleaseUrl() {
        val image = QrCodeGenerator.generate(RELEASE_URL)

        assertTrue(image.width > 0)
        assertEquals(image.width, image.height)
    }

    @Test
    fun generateProducesLargerBitmapForLongerContent() {
        val short = QrCodeGenerator.generate("a")
        val long = QrCodeGenerator.generate("a".repeat(500))

        assertTrue(long.width > short.width)
    }
}
