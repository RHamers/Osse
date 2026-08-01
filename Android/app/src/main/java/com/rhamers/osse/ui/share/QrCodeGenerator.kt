package com.rhamers.osse.ui.share

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import qrcode.QRCode

object QrCodeGenerator {

    fun generate(content: String): ImageBitmap {
        val bitmap = QRCode.ofSquares()
            .build(content)
            .render()
            .nativeImage() as Bitmap
        return bitmap.asImageBitmap()
    }
}
