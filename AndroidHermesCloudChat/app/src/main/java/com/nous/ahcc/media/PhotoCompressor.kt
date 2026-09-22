package com.nous.ahcc.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

object PhotoCompressor {
    const val MIME = "image/jpeg"
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_SIDE = 1600
    private const val QUALITY = 82

    fun compressFile(input: File): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SIDE * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(input.absolutePath, opts)
            ?: error("Cannot decode image")
        val scaled = scaleDown(bitmap, MAX_SIDE)
        if (scaled !== bitmap) bitmap.recycle()

        var quality = QUALITY
        var bytes: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            quality -= 8
        } while (bytes.size > MAX_BYTES && quality >= 40)

        scaled.recycle()
        if (bytes.size > MAX_BYTES) error("Photo too large after compress (${bytes.size})")
        val name = "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return bytes to name
    }

    fun newAttachmentId(): String = UUID.randomUUID().toString()

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = max(w, h)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
