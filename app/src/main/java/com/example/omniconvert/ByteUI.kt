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

// ---------- Root: bottom nav, one tab per category in Screens.kt ----------

@Composable
fun ByteApp(sharedUris: List<Uri> = emptyList()) {
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(sharedUris) { if (sharedUris.isNotEmpty()) tab = 0 }

    val imageCat = categories.first { it.id == "image" }
    val pdfCat = categories.first { it.id == "pdf" }
    val videoCat = categories.first { it.id == "video" }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Images") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("PDF Tools") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("Audio") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ToolsScreen(imageCat, "Byte", "Batch Image Processor • 100% Offline", sharedUris)
                1 -> ToolsScreen(pdfCat, "PDF Quick Tools", "PdfBox-Android Powered")
                else -> ToolsScreen(videoCat, "Audio Tools", "Extract audio from video")
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

    val mimes: Array<String> = selected?.let { arrayOf(it.inputMime) }
        ?: category.conversions.map { it.inputMime }.distinct().toTypedArray()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))

        FileBox(
            if (picked.isEmpty()) "No files selected" else "${picked.size} file(s) selected",
            Modifier.fillMaxWidth().height(110.dp)
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { picker.launch(mimes) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (category.id == "image") "Select Photos (Single or Batch)" else "Select File(s)")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("Choose Tool", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
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

        // Extra options for tools that need them (resize & compress)
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
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        )
    }
}

@Composable
fun FileBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.border(
            1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}