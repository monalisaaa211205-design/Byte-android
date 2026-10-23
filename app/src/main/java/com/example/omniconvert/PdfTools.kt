package com.example.omniconvert

import android.content.ContentValues
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.caverock.androidsvg.SVG
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
}