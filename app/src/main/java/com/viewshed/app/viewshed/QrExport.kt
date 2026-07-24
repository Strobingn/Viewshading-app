package com.viewshed.app.viewshed

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale

object QrExport {
    fun payload(result: ViewshedResult): String = String.format(
        Locale.US,
        "viewshade://analysis?lat=%.7f&lon=%.7f&eye=%.2f&target=%.2f&range=%.2f&rays=%d&samples=%d&curvature=%s&refraction=%.3f",
        result.observer.lat,
        result.observer.lon,
        result.params.eyeHeightM,
        result.params.targetHeightM,
        result.params.maxDistKm,
        result.params.numRays,
        result.params.samplesPerRay,
        result.params.useCurvature,
        result.params.refraction,
    )

    fun bitmap(content: String, size: Int = 768): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
