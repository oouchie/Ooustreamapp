package com.ooustream.iptv.multiview

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utility for generating QR code bitmaps using ZXing.
 * Used for monetization upgrade flow in MultiView feature.
 */
object QrCodeGenerator {
    /**
     * Generates a QR code bitmap from the given content string.
     *
     * @param content The text/URL to encode in the QR code
     * @param size The width and height of the output bitmap in pixels (default 512)
     * @return A square bitmap with black QR code on white background
     */
    fun generate(content: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }
}
