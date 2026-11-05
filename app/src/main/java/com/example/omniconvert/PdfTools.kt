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
import com.caverock.androidsvg.SVG
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object PdfTools {

    /** Creates a PDF in Downloads/OmniConvert[/subfolder] and lets [write] fill it. */
    private fun savePdf(
        context: Context,
        filename: String,
        subfolder: String,
        write: (OutputStream) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/OmniConvert" + subfolder
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val outUri = resolver.insert(collection, values)
            ?: throw Exception("Could not create PDF entry.")
        try {
            resolver.openOutputStream(outUri)?.use { write(it) }
                ?: throw Exception("Could not open PDF output stream.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(outUri, null, null)   // don't leave a broken file behind
            throw e
        }
        return outUri
    }

    /** Splits a PDF into one PDF per page. Text and vectors are preserved. */
    suspend fun splitPdf(
        context: Context,
        pdfUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<Result<Uri>> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val results = mutableListOf<Result<Uri>>()
        try {
            val input = context.contentResolver.openInputStream(pdfUri)
                ?: throw Exception("Could not open the PDF.")
            input.use { stream ->
                PDDocument.load(stream).use { source ->
                    val total = source.numberOfPages
                    val stamp = System.currentTimeMillis()
                    for (i in 0 until total) {
                        results.add(runCatching {
                            PDDocument().use { single ->
                                single.importPage(source.getPage(i))
                                val name = "split_${stamp}_page_${"%03d".format(i + 1)}.pdf"
                                savePdf(context, name, "/Split") { out -> single.save(out) }
                            }
                        })
                        onProgress(i + 1, total)
                    }
                }
            }
        } catch (e: Exception) {
            results.add(Result.failure(e))
        }
        results
    }

    /** Converts one or more SVGs into a single PDF, one page per SVG, kept as vectors. */
    suspend fun svgsToPdf(
        context: Context,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            if (uris.isEmpty()) throw IllegalArgumentException("No SVG selected.")
            val doc = PdfDocument()
            try {
                uris.forEachIndexed { index, uri ->
                    val svg = context.contentResolver.openInputStream(uri)?.use {
                        SVG.getFromInputStream(it)
                    } ?: throw Exception("Could not read SVG #${index + 1}.")

                    val box = svg.documentViewBox
                    val w = when {
                        svg.documentWidth > 0 -> svg.documentWidth
                        box != null -> box.width()
                        else -> 595f
                    }
                    val h = when {
                        svg.documentHeight > 0 -> svg.documentHeight
                        box != null -> box.height()
                        else -> 842f
                    }
                    val pageW = w.toInt().coerceAtLeast(1)
                    val pageH = h.toInt().coerceAtLeast(1)

                    val picture = svg.renderToPicture(pageW, pageH)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                    val page = doc.startPage(pageInfo)
                    page.canvas.drawPicture(picture)
                    doc.finishPage(page)
                    onProgress(index + 1, uris.size)
                }
                savePdf(context, "svg_${System.currentTimeMillis()}.pdf", "") { out ->
                    doc.writeTo(out)
                }
            } finally {
                doc.close()
            }
        }
    }

    /** Merges several PDFs, in the order given, into one PDF. */
    suspend fun mergePdfs(
        context: Context,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        runCatching {
            if (uris.size < 2) throw IllegalArgumentException("Pick at least 2 PDFs to merge.")

            // Source documents must stay open until the merged file is saved,
            // because imported pages still point at their original document.
            val sources = mutableListOf<PDDocument>()
            try {
                PDDocument().use { merged ->
                    uris.forEachIndexed { index, uri ->
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw Exception("Could not open PDF #${index + 1}.")
                        val source = stream.use { PDDocument.load(it) }
                        sources.add(source)
                        for (page in source.pages) merged.importPage(page)
                        onProgress(index + 1, uris.size)
                    }
                    savePdf(context, "merged_${System.currentTimeMillis()}.pdf", "") { out ->
                        merged.save(out)
                    }
                }
            } finally {
                sources.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Converts images into one PDF, one page each. JPEG-compressed, so files stay small. */
    suspend fun imagesToPdf(
        context: Context,
        uris: List<Uri>,
        maxSide: Int = 2000,      // longest edge in px; 1600-2000 is plenty for reading
        quality: Float = 0.8f,    // JPEG quality
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        runCatching {
            if (uris.isEmpty()) throw IllegalArgumentException("No images selected.")
            PDDocument().use { doc ->
                uris.forEachIndexed { index, uri ->
                    val bmp = decodeForPdf(context, uri, maxSide)
                        ?: throw Exception("Could not read image #${index + 1}.")
                    try {
                        // JPEG for photos; lossless only when transparency must be kept
                        val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp)
                        else JPEGFactory.createFromImage(doc, bmp, quality)

                        // Fit onto a page whose longest side is A4-ish (842 pt)
                        val scale = 842f / maxOf(bmp.width, bmp.height)
                        val w = bmp.width * scale
                        val h = bmp.height * scale
                        val page = PDPage(PDRectangle(w, h))
                        doc.addPage(page)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w, h) }
                    } finally {
                        bmp.recycle()
                    }
                    onProgress(index + 1, uris.size)
                }
                savePdf(context, "images_${System.currentTimeMillis()}.pdf", "") { out ->
                    doc.save(out)
                }
            }
        }
    }


    /** Decodes downscaled (memory-safe) and applies EXIF rotation. */
    /** Decodes downscaled (memory-safe) and applies EXIF rotation. SVGs are rasterised. */
    private fun decodeForPdf(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver

        if (resolver.getType(uri) == "image/svg+xml" || uri.toString()
                .endsWith(".svg", ignoreCase = true)
        ) {
            val svgBmp = ImageConverter.decodeAndCorrectOrientation(context, uri) ?: return null
            svgBmp.setHasAlpha(false)   // it's filled white, so JPEG is fine
            val longest = maxOf(svgBmp.width, svgBmp.height)
            if (longest <= maxSide) return svgBmp
            val s = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                svgBmp,
                (svgBmp.width * s).toInt().coerceAtLeast(1),
                (svgBmp.height * s).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== svgBmp) svgBmp.recycle()
            return scaled
        }

        // 1) read size only
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        // 2) decode with a power-of-two subsample close to the target
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // 3) exact resize + EXIF rotation in one matrix pass
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > maxSide) m.postScale(maxSide.toFloat() / longest, maxSide.toFloat() / longest)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        if (!m.isIdentity) {
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }
}