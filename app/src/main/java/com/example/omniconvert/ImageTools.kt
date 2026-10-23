package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageTools {

    /**
     * Shrinks images to [scalePercent]% of their size and saves them as JPEG at [quality] (10-100).
     * Returns one Result per input image, so one bad file doesn't stop the rest.
     */
    suspend fun resizeAndCompress(
        context: Context,
        uris: List<Uri>,
        scalePercent: Int,
        quality: Int,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result<Uri>>()
        val stamp = System.currentTimeMillis()
        uris.forEachIndexed { index, uri ->
            results.add(runCatching {
                processOne(context, uri, scalePercent, quality, "resized_${stamp}_${index + 1}.jpg")
            })
            onProgress(index + 1, uris.size)
        }
        results
    }

    private fun processOne(
        context: Context,
        uri: Uri,
        scalePercent: Int,
        quality: Int,
        filename: String
    ): Uri {
        // Reuse the decoder from Conversions.kt (handles EXIF rotation and SVG)
        val original = ImageConverter.decodeAndCorrectOrientation(context, uri)
            ?: throw Exception("Could not decode image.")

        val w = (original.width * scalePercent / 100).coerceAtLeast(1)
        val h = (original.height * scalePercent / 100).coerceAtLeast(1)

        // Draw onto a white canvas: JPEG has no transparency, and transparent
        // pixels would otherwise turn black.
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(original, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        original.recycle()

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniConvert")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run { flat.recycle(); throw Exception("Could not create image entry.") }

        try {
            resolver.openOutputStream(outUri)?.use { out ->
                if (!flat.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)) {
                    throw Exception("Compression failed.")
                }
            } ?: throw Exception("Could not open output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(outUri, null, null)   // don't leave a broken file behind
            throw e
        } finally {
            flat.recycle()
        }
        return outUri
    }
}