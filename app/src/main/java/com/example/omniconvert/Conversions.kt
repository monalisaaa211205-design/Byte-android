package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer


enum class TargetFormat(
    val extension: String, 
    val mimeType: String, 
    val compressFormat: Bitmap.CompressFormat?
) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    WEBP("webp", "image/webp",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    ),
    PDF("pdf", "application/pdf", null),
    M4A("m4a", "audio/mp4", null)
}

object ImageConverter {

    suspend fun compileImagesToPdf(
    context: Context,
    uris: List<Uri>,
    onProgress: (current: Int, total: Int) -> Unit
): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
        if (uris.isEmpty()) throw IllegalArgumentException("No images selected.")

        val pdfDocument = PdfDocument()
        val total = uris.size

        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = decodeAndCorrectOrientation(context, uri)
                    ?: throw Exception("Failed to decode image at index $index")

                // Fit bitmap proportionally to standard A4 page dimensions (595 x 842 pt)
                val targetWidth = 595
                val targetHeight = 842
                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val scale = minOf(
                    targetWidth.toFloat() / bitmap.width.toFloat(),
                    targetHeight.toFloat() / bitmap.height.toFloat()
                )
                val scaledWidth = bitmap.width * scale
                val scaledHeight = bitmap.height * scale
                val dx = (targetWidth - scaledWidth) / 2f
                val dy = (targetHeight - scaledHeight) / 2f

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }

                page.canvas.drawBitmap(bitmap, matrix, null)
                pdfDocument.finishPage(page)
                bitmap.recycle()

                onProgress(index + 1, total)
            }

            val filename = "compiled_${System.currentTimeMillis()}.pdf"
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniConvert")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val outputUri = context.contentResolver.insert(collectionUri, values)
                ?: throw Exception("Failed to create PDF entry in MediaStore.")

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                pdfDocument.writeTo(outputStream)
            } ?: throw Exception("Failed to open PDF output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(outputUri, values, null, null)
            }

            outputUri
        } finally {
            pdfDocument.close()
        }
    }
}
    

    suspend fun convertAndSaveImage(
    context: Context,
    inputUri: Uri,
    targetFormat: TargetFormat,
    quality: Int = 90
): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
        val contentResolver = context.contentResolver
        
        // Take persistable permission if granted by picker
        try {
            contentResolver.takePersistableUriPermission(
                inputUri, 
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val bitmap = decodeAndCorrectOrientation(context, inputUri)
            ?: throw Exception("Failed to decode image from URI: $inputUri")

        val filename = "converted_${System.currentTimeMillis()}.${targetFormat.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniConvert")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore returned null for insert")

        val compressFormat = when (targetFormat) {
            TargetFormat.PNG -> Bitmap.CompressFormat.PNG
            TargetFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }

        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            val success = bitmap.compress(compressFormat, quality, outputStream)
            if (!success) throw Exception("Bitmap compression failed.")
        } ?: throw Exception("Failed to open output stream.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(outputUri, values, null, null)
        }

        bitmap.recycle()
        outputUri
    }.onFailure { e ->
        android.util.Log.e("OmniConvert", "Conversion error for $inputUri: ${e.message}", e)
    }
}
    suspend fun convertMultipleImages(
        context: Context,
        uris: List<Uri>,
        targetFormat: TargetFormat,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result<Uri>>()
        val total = uris.size

        uris.forEachIndexed { index, uri ->
            val result = convertAndSaveImage(context, uri, targetFormat)
            results.add(result)
            onProgress(index + 1, total)
        }

        results
    }

    private fun decodeAndCorrectOrientation(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        val orientation = resolver.openInputStream(uri)?.use { stream: InputStream ->
            val exif = ExifInterface(stream)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rawBitmap = resolver.openInputStream(uri)?.use { stream: InputStream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val rotationAngle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationAngle == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotationAngle) }
        val rotatedBitmap = Bitmap.createBitmap(
            rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
        )
        rawBitmap.recycle()
        return rotatedBitmap
    
        // INTERCEPT SVG FILES HERE:
        if (resolver.getType(uri) == "image/svg+xml") {
            return decodeSvgToBitmap(context, uri)
        }
    }
    private fun decodeSvgToBitmap(context: Context, uri: Uri): Bitmap? {
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(stream)
        
        // Use intrinsic vector bounds, fallback to 1000x1000 if undefined
        val width = if (svg.documentWidth > 0) svg.documentWidth.toInt() else 1000
        val height = if (svg.documentHeight > 0) svg.documentHeight.toInt() else 1000
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // SVGs often expect a transparent background, but for JPEGs we need a white base
        canvas.drawColor(android.graphics.Color.WHITE)
        svg.renderToCanvas(canvas)
        bitmap
    }

    suspend fun extractPdfPagesToImages(
        context: Context,
        pdfUri: Uri,
        targetFormat: TargetFormat,
        quality: Int = 100,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result<Uri>>()
        val resolver = context.contentResolver

        runCatching {
            resolver.openFileDescriptor(pdfUri, "r")?.use { descriptor ->
                val pdfRenderer = PdfRenderer(descriptor)
                val totalPages = pdfRenderer.pageCount

                for (i in 0 until totalPages) {
                    val page = pdfRenderer.openPage(i)
                    
                    // Scale the PDF page up for better image fidelity (approx 2x standard density)
                    val densityScale = context.resources.displayMetrics.densityDpi / 160f * 2f
                    val width = (page.width * densityScale).toInt()
                    val height = (page.height * densityScale).toInt()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE) // PDFs render transparent by default

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Save individual page using your existing MediaStore pattern
                    val filename = "page_${i + 1}_${System.currentTimeMillis()}.${targetFormat.extension}"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniConvert")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (outputUri != null) {
                        resolver.openOutputStream(outputUri)?.use { out ->
                            val compressFormat = targetFormat.compressFormat ?: Bitmap.CompressFormat.JPEG
                            bitmap.compress(compressFormat, quality, out)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(outputUri, values, null, null)
                        }
                        results.add(Result.success(outputUri))
                    } else {
                        results.add(Result.failure(Exception("MediaStore insert failed for page $i")))
                    }

                    bitmap.recycle()
                    onProgress(i + 1, totalPages)
                }
                pdfRenderer.close()
            }
        }.onFailure { e ->
            results.add(Result.failure(e))
        }

        results
    }
    
    }
}
