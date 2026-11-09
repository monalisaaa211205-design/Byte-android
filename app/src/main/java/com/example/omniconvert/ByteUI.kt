package com.example.omniconvert

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

// ---------- Root: bottom nav, one tab per category in Screens.kt ----------
val redaction10 = FontFamily(Font(R.font.redaction10_bold))
val redaction10_reg= FontFamily(Font(R.font.redaction10_regular))
@Composable
fun ByteApp(sharedUris: List<Uri> = emptyList()) {
    // 0 = PDF Tools, 1 = Images, 2 = Audio
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(sharedUris) { if (sharedUris.isNotEmpty()) tab = 1 }

    val imageCat = categories.first { it.id == "image" }
    val pdfCat = categories.first { it.id == "pdf" }
    val videoCat = categories.first { it.id == "video" }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("PDF Tools", fontFamily= Redaction35) }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Image Tools", fontFamily= Redaction35) }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("Audio Tools", fontFamily= Redaction35) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ToolsScreen(pdfCat, "Byte", "PdfBox•Android Powered•100% Offline")
                1 -> ToolsScreen(imageCat, "Byte", "Batch Image Processor•100% Offline", sharedUris)
                else -> ToolsScreen(videoCat, "Byte", "Audio Extraction•100% Offline")
            }
        }
    }
}
// ---------- One screen layout used by every tab ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    category: Category,
    title: String,
    subtitle: String,
    initialUris: List<Uri> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf(initialUris) }
    var selected by remember { mutableStateOf<Conversion?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scalePercent by remember { mutableStateOf(100) }
    var quality by remember { mutableStateOf(80) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOpenPrompt by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty()) { picked = initialUris; outcome = null }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) { picked = uris; outcome = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = Redaction35,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, fontFamily = redaction10_reg)
        Spacer(Modifier.height(24.dp))

        // ---- Step 1: choose the tool ----
        Text("Choose Tool", style = MaterialTheme.typography.titleLarge, fontFamily = Redaction35, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        category.conversions.forEach { conv ->
            OptionCard(conv.title, selected = selected?.id == conv.id) {
                val prev = selected
                if (prev != null && prev.inputMime != conv.inputMime) picked = emptyList()
                selected = conv
                outcome = null
            }
            Spacer(Modifier.height(12.dp))
        }

        // Options for the chosen tool (resize & compress), right under the tools
        selected?.let { conv ->
            if (conv.hasScale) {
                Spacer(Modifier.height(8.dp))
                Text("Size: $scalePercent% of original", modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(100, 75, 50, 25).forEach { p ->
                        FilterChip(
                            selected = scalePercent == p,
                            onClick = { scalePercent = p },
                            label = { Text("$p%") }
                        )
                    }
                }
            }
            if (conv.hasQuality) {
                Spacer(Modifier.height(8.dp))
                Text("Quality: $quality  (lower = smaller file)", modifier = Modifier.fillMaxWidth())
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt() },
                    valueRange = 10f..100f
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        // ---- Step 2: pick the files ----
        Text("Select Files", style = MaterialTheme.typography.titleLarge, fontFamily = Redaction35,modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        FileBox(
            when {
                picked.isNotEmpty() -> "${picked.size} file(s) selected"
                selected == null -> "Choose a tool first"
                else -> "No files selected"
            },
            Modifier.fillMaxWidth().height(110.dp)
        )
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = selected != null,
            onClick = { selected?.let { picker.launch(arrayOf(it.inputMime)) } },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (selected?.multiple == true) "Select Files (Single or Batch)" else "Select File")
        }

        Spacer(Modifier.height(24.dp))

        // ---- Step 3: convert ----
        Button(
            enabled = picked.isNotEmpty() && selected != null && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                val conv = selected
                if (conv != null) {
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
            }
        ) { Text(if (busy) "Working..." else "Convert & Save") }

        Spacer(Modifier.height(16.dp))

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

    if (showOpenPrompt) {
        AlertDialog(
            onDismissRequest = { showOpenPrompt = false },
            title = { Text("Conversion complete", fontFamily = Redaction10) },
            text = { Text("Do you want to open the converted file now?", fontFamily = Redaction10) },
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
                        Text("Files will be numbered: name_1, name_2, …", style = MaterialTheme.typography.bodySmall)
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

// ---------- Reusable pieces ----------

@Composable
fun OptionCard(text: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.background,
        border = BorderStroke(7.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            fontSize= 16.sp,
            fontFamily = redaction10_reg,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        )
    }
}

@Composable
fun FileBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.border(
            7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 20.sp,
            fontFamily = redaction10_reg,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}