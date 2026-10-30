package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

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
import java.io.File


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
    ): Result<Uri> = PdfTools.imagesToPdf(context, uris, onProgress = onProgress)

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
            } catch (_: Exception) {
            }

            val bitmap = decodeAndCorrectOrientation(context, inputUri)
                ?: throw Exception("Failed to decode image from URI: $inputUri")

            try {
                val filename = "converted_${System.currentTimeMillis()}.${targetFormat.extension}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, targetFormat.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/OmniConvert"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val outputUri =
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("MediaStore returned null for insert")

                try {
                    val compressFormat = when (targetFormat) {
                        TargetFormat.PNG -> Bitmap.CompressFormat.PNG
                        TargetFormat.WEBP ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                            else Bitmap.CompressFormat.WEBP

                        else -> Bitmap.CompressFormat.JPEG
                    }

                    contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                        if (!bitmap.compress(compressFormat, quality, outputStream)) {
                            throw Exception("Bitmap compression failed.")
                        }
                    } ?: throw Exception("Failed to open output stream.")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(outputUri, values, null, null)
                    }
                } catch (e: Throwable) {
                    contentResolver.delete(
                        outputUri,
                        null,
                        null
                    )   // don't leave a broken file behind
                    throw e
                }
                outputUri
            } finally {
                bitmap.recycle()
            }
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

    internal fun decodeAndCorrectOrientation(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // SVG check goes FIRST, before any EXIF/bitmap decoding
        if (resolver.getType(uri) == "image/svg+xml" || uri.toString()
                .endsWith(".svg", ignoreCase = true)
        ) {
            return decodeSvgToBitmap(context, uri)
        }

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
        val rotated =
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        rawBitmap.recycle()
        return rotated
    }

    private fun decodeSvgToBitmap(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(stream)
            val width = if (svg.documentWidth > 0) svg.documentWidth.toInt() else 1000
            val height = if (svg.documentHeight > 0) svg.documentHeight.toInt() else 1000
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            svg.renderToCanvas(canvas)
            bitmap
        }
    }   // <-- this brace was missing

    suspend fun extractPdfPagesToImages(
        context: Context,
        pdfUri: Uri,
        targetFormat: TargetFormat,
        quality: Int = 100,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Result<Uri>>()
        try {
            val descriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: throw Exception("Could not open the PDF.")
            descriptor.use { fd ->
                val renderer = PdfRenderer(fd)
                try {
                    val totalPages = renderer.pageCount
                    val stamp = System.currentTimeMillis()
                    for (i in 0 until totalPages) {
                        results.add(runCatching {
                            renderPdfPage(
                                context.contentResolver,
                                renderer,
                                i,
                                stamp,
                                targetFormat,
                                quality
                            )
                        })
                        onProgress(i + 1, totalPages)
                    }
                } finally {
                    renderer.close()
                }
            }
        } catch (e: Exception) {
            results.add(Result.failure(e))
        }
        results
    }

    private fun renderPdfPage(
        resolver: android.content.ContentResolver,
        renderer: PdfRenderer,
        index: Int,
        stamp: Long,
        targetFormat: TargetFormat,
        quality: Int
    ): Uri {
        // Render
        val page = renderer.openPage(index)
        val bitmap = try {
            val scale = 2f   // fixed scale; the density-based one can create huge bitmaps
            Bitmap.createBitmap(
                (page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888
            ).also { bmp ->
                android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }

        // Save
        try {
            val format = targetFormat.compressFormat ?: Bitmap.CompressFormat.JPEG
            val ext = if (targetFormat.compressFormat != null) targetFormat.extension else "jpg"
            val mime =
                if (targetFormat.compressFormat != null) targetFormat.mimeType else "image/jpeg"

            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "page_${stamp}_${"%03d".format(index + 1)}.$ext"
                )
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/OmniConvert"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("MediaStore insert failed for page ${index + 1}")
            try {
                resolver.openOutputStream(outputUri)?.use { out ->
                    if (!bitmap.compress(
                            format,
                            quality,
                            out
                        )
                    ) throw Exception("Compression failed.")
                } ?: throw Exception("Could not open output stream for page ${index + 1}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(outputUri, values, null, null)
                }
            } catch (e: Throwable) {
                resolver.delete(outputUri, null, null)
                throw e
            }
            return outputUri
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun extractAudio(context: Context, videoUri: Uri): Result<Uri> =
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            try {
                runCatching {
                    val extractor = MediaExtractor()
                    var muxer: MediaMuxer? = null
                    var muxerStarted = false
                    try {
                        extractor.setDataSource(context, videoUri, null)
                        val track = (0 until extractor.trackCount).firstOrNull {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                                ?.startsWith("audio/") == true
                        } ?: throw Exception("This video has no audio track.")

                        extractor.selectTrack(track)
                        muxer =
                            MediaMuxer(tempFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        val outTrack = muxer.addTrack(extractor.getTrackFormat(track))
                        muxer.start()
                        muxerStarted = true

                        val buffer = ByteBuffer.allocate(1 shl 20)
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            info.size = extractor.readSampleData(buffer, 0)
                            if (info.size < 0) break
                            info.presentationTimeUs = extractor.sampleTime
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(outTrack, buffer, info)
                            extractor.advance()
                        }
                        muxer.stop()
                        muxerStarted = false
                    } finally {
                        runCatching { if (muxerStarted) muxer?.stop() }
                        runCatching { muxer?.release() }
                        extractor.release()
                    }

                    // Copy from cache into Music/OmniConvert
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Audio.Media.DISPLAY_NAME,
                            "audio_${System.currentTimeMillis()}.m4a"
                        )
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Audio.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_MUSIC + "/OmniConvert"
                            )
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val outUri =
                        resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("Could not create audio file entry.")
                    try {
                        resolver.openOutputStream(outUri)?.use { out ->
                            tempFile.inputStream().use { it.copyTo(out) }
                        } ?: throw Exception("Could not open audio output stream.")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            resolver.update(outUri, values, null, null)
                        }
                    } catch (e: Throwable) {
                        resolver.delete(outUri, null, null)
                        throw e
                    }
                    outUri
                }
            } finally {
                tempFile.delete()
            }
        }
}
