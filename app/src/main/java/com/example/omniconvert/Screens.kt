package com.example.omniconvert

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Data ----------

data class Conversion(
    val id: String,
    val title: String,
    val inputMime: String,
    val multiple: Boolean = false,
    val hasScale: Boolean = false,     // show the "scale %" chips on the convert screen
    val hasQuality: Boolean = false    // show the quality slider on the convert screen
)

data class Category(val id: String, val title: String, val conversions: List<Conversion>)

data class Outcome(
    val message: String,
    val uris: List<Uri> = emptyList(),
    val folder: String? = null
)

val categories = listOf(
    Category("image", "Image conversions", listOf(
        Conversion("img_jpg", "Images → JPG", "image/*", true),
        Conversion("img_png", "Images → PNG", "image/*", true),
        Conversion("img_webp", "Images → WEBP", "image/*", true),
        Conversion("img_pdf", "Images → PDF", "image/*", true),
        Conversion("svg_png", "SVG → PNG", "image/svg+xml"),
        Conversion("img_resize", "Resize & compress images", "image/*", true,
            hasScale = true, hasQuality = true),
    )),
    Category("pdf", "PDF tools", listOf(
        Conversion("pdf_from_img", "Images → PDF", "image/*", true),
        Conversion("pdf_merge", "Merge PDFs", "application/pdf", true),
        Conversion("pdf_split", "Split PDF into pages", "application/pdf"),
        Conversion("svg_pdf", "SVGs → PDF", "image/svg+xml", true),
        Conversion("pdf_png", "PDF → PNG images", "application/pdf"),
        Conversion("pdf_jpg", "PDF → JPG images", "application/pdf"),
    )),
    Category("video", "Video & audio", listOf(
        Conversion("extract_audio", "Extract audio from video", "video/*"),
    )),
)

// ---------- Navigation ----------

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            ListScreen("Byte", categories.map { it.title }) { index ->
                nav.navigate("category/${categories[index].id}")
            }
        }
        composable("category/{catId}") { entry ->
            val cat = categories.first { it.id == entry.arguments?.getString("catId") }
            ListScreen(cat.title, cat.conversions.map { it.title }) { index ->
                nav.navigate("convert/${cat.conversions[index].id}")
            }
        }
        composable("convert/{convId}") { entry ->
            val conv = categories.flatMap { it.conversions }
                .first { it.id == entry.arguments?.getString("convId") }
            ConvertScreen(conv)
        }
    }
}

@Composable
fun ListScreen(title: String, items: List<String>, onClick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size) { i ->
                Card(Modifier.fillMaxWidth().clickable { onClick(i) }) {
                    Text(items[i], Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ---------- Convert screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(conv: Conversion) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // Settings for conversions that have options
    var scalePercent by remember { mutableStateOf(100) }
    var quality by remember { mutableStateOf(80) }

    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOpenPrompt by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            picked = uris
            outcome = null
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(conv.title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { picker.launch(arrayOf(conv.inputMime)) }) { Text("Choose file(s)") }
        Spacer(Modifier.height(8.dp))
        Text(if (picked.isEmpty()) "Nothing selected" else "${picked.size} file(s) selected")
        Spacer(Modifier.height(16.dp))

        // Options panel: only shown for conversions that ask for it
        if (conv.hasScale) {
            Text("Size: $scalePercent% of original")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 75, 50, 25).forEach { p ->
                    FilterChip(
                        selected = scalePercent == p,
                        onClick = { scalePercent = p },
                        label = { Text("$p%") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (conv.hasQuality) {
            Text("Quality: $quality  (lower = smaller file)")
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.toInt() },
                valueRange = 10f..100f
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            enabled = picked.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                outcome = null
                scope.launch {
                    val files = if (conv.multiple) picked else picked.take(1)
                    val result = runConversion(context, conv.id, files, scalePercent, quality)
                    names = withContext(Dispatchers.IO) {
                        result.uris.map { getDisplayName(context, it) ?: "file" }
                    }
                    outcome = result
                    busy = false
                    if (result.uris.isNotEmpty()) showOpenPrompt = true
                }
            }
        ) { Text(if (busy) "Working..." else "Convert & Save") }

        Spacer(Modifier.height(16.dp))

        // Result card: stays on screen after the user says "No"
        outcome?.let { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(o.message, style = MaterialTheme.typography.titleMedium)
                    if (o.uris.isNotEmpty() && o.folder != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Saved in: ${o.folder}", style = MaterialTheme.typography.bodyMedium)
                        names.take(3).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                        if (names.size > 3) {
                            Text("• …and ${names.size - 3} more", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openFile(context, o.uris.first()) }) { Text("Open") }
                            OutlinedButton(onClick = { showRename = true }) { Text("Rename") }
                        }
                        OutlinedButton(onClick = { openFolder(context, o.folder) }) { Text("Show folder") }
                    }
                }
            }
        }
    }

    // Prompt 1: open the file now?
    if (showOpenPrompt) {
        AlertDialog(
            onDismissRequest = { showOpenPrompt = false },
            title = { Text("Conversion complete") },
            text = { Text("Do you want to open the converted file now?") },
            confirmButton = {
                TextButton(onClick = {
                    showOpenPrompt = false
                    outcome?.uris?.firstOrNull()?.let { openFile(context, it) }
                }) { Text("Yes, open") }
            },
            dismissButton = {
                TextButton(onClick = { showOpenPrompt = false }) { Text("No, stay here") }
            }
        )
    }

    // Prompt 2: rename
    val current = outcome
    if (showRename && current != null) {
        var newBase by remember(names) {
            mutableStateOf(names.firstOrNull()?.substringBeforeLast('.') ?: "")
        }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename file") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newBase,
                        onValueChange = { newBase = it },
                        singleLine = true,
                        label = { Text("New name (extension is kept)") }
                    )
                    if (current.uris.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Files will be numbered: name_1, name_2, …",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = newBase.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                    if (clean.isNotEmpty()) {
                        showRename = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                current.uris.mapIndexed { i, uri ->
                                    val ext = names.getOrNull(i)?.substringAfterLast('.', "") ?: ""
                                    val suffix = if (current.uris.size > 1) "_${i + 1}" else ""
                                    val full = if (ext.isEmpty()) "$clean$suffix" else "$clean$suffix.$ext"
                                    renameFile(context, uri, full)
                                }.all { it }
                            }
                            names = withContext(Dispatchers.IO) {
                                current.uris.map { getDisplayName(context, it) ?: "file" }
                            }
                            Toast.makeText(
                                context,
                                if (ok) "Renamed" else "Rename failed for some files",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

// ---------- Conversion dispatcher ----------

suspend fun runConversion(
    context: Context,
    id: String,
    uris: List<Uri>,
    scalePercent: Int = 100,
    quality: Int = 80
): Outcome {
    return when (id) {

        // Images -> JPG / PNG / WEBP, plus SVG -> PNG
        "img_jpg", "img_png", "img_webp", "svg_png" -> {
            val fmt = when (id) {
                "img_jpg" -> TargetFormat.JPEG
                "img_webp" -> TargetFormat.WEBP
                else -> TargetFormat.PNG
            }
            val results = ImageConverter.convertMultipleImages(context, uris, fmt) { _, _ -> }
            val saved = results.mapNotNull { it.getOrNull() }
            if (saved.isEmpty())
                Outcome("Conversion failed: ${results.firstOrNull()?.exceptionOrNull()?.message}")
            else
                Outcome("Converted ${saved.size}/${uris.size} file(s)", saved, "Pictures/OmniConvert")
        }

        // Resize + compress images (uses the scale and quality settings)
        "img_resize" -> {
            val results = ImageTools.resizeAndCompress(context, uris, scalePercent, quality) { _, _ -> }
            val saved = results.mapNotNull { it.getOrNull() }
            if (saved.isEmpty())
                Outcome("Failed: ${results.firstOrNull()?.exceptionOrNull()?.message}")
            else
                Outcome("Processed ${saved.size}/${uris.size} image(s)", saved, "Pictures/OmniConvert")
        }

        // Images -> one PDF (listed in both the Image and PDF categories)
        "img_pdf", "pdf_from_img" -> {
            val r = ImageConverter.compileImagesToPdf(context, uris) { _, _ -> }
            r.getOrNull()?.let { Outcome("PDF created", listOf(it), "Download/OmniConvert") }
                ?: Outcome("Error: ${r.exceptionOrNull()?.message}")
        }

        // Several PDFs -> one PDF
        "pdf_merge" -> {
            val r = PdfTools.mergePdfs(context, uris) { _, _ -> }
            r.getOrNull()?.let { Outcome("Merged ${uris.size} PDFs", listOf(it), "Download/OmniConvert") }
                ?: Outcome("Error: ${r.exceptionOrNull()?.message}")
        }

        // One PDF -> one PDF per page
        "pdf_split" -> {
            val results = PdfTools.splitPdf(context, uris.first()) { _, _ -> }
            val saved = results.mapNotNull { it.getOrNull() }
            if (saved.isEmpty())
                Outcome("Split failed: ${results.firstOrNull()?.exceptionOrNull()?.message}")
            else
                Outcome("Split into ${saved.size} PDF(s)", saved, "Download/OmniConvert/Split")
        }

        // SVG(s) -> one PDF
        "svg_pdf" -> {
            val r = PdfTools.svgsToPdf(context, uris) { _, _ -> }
            r.getOrNull()?.let { Outcome("PDF created", listOf(it), "Download/OmniConvert") }
                ?: Outcome("Error: ${r.exceptionOrNull()?.message}")
        }

        // PDF -> PNG / JPG (one image per page)
        "pdf_png", "pdf_jpg" -> {
            val fmt = if (id == "pdf_png") TargetFormat.PNG else TargetFormat.JPEG
            val results = ImageConverter.extractPdfPagesToImages(context, uris.first(), fmt) { _, _ -> }
            val saved = results.mapNotNull { it.getOrNull() }
            if (saved.isEmpty())
                Outcome("Conversion failed: ${results.firstOrNull()?.exceptionOrNull()?.message}")
            else
                Outcome("Saved ${saved.size} page(s)", saved, "Pictures/OmniConvert")
        }

        // Video -> audio (m4a)
        "extract_audio" -> {
            val r = ImageConverter.extractAudio(context, uris.first())
            r.getOrNull()?.let { Outcome("Audio extracted", listOf(it), "Music/OmniConvert") }
                ?: Outcome("Error: ${r.exceptionOrNull()?.message}")
        }

        else -> Outcome("Not implemented yet")
    }
}

// ---------- Helpers ----------

fun getDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

fun renameFile(context: Context, uri: Uri, newName: String): Boolean = try {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
    context.contentResolver.update(uri, values, null, null) > 0
} catch (e: Exception) {
    false
}

fun openFile(context: Context, uri: Uri) {
    val mime = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

fun openFolder(context: Context, relativePath: String) {   // e.g. "Pictures/OmniConvert"
    val folderUri = android.provider.DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$relativePath"
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(folderUri, "vnd.android.document/directory")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback: open the system Downloads app
        try {
            context.startActivity(
                Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(context, "Saved to $relativePath", Toast.LENGTH_LONG).show()
        }
    }
}