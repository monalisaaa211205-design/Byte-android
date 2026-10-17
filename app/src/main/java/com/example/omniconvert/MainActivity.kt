package com.example.omniconvert

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sharedUrisState = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val sharedUris by sharedUrisState
                    converterScreen(initialUris = sharedUris)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                sharedUrisState.value = listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                sharedUrisState.value = uris ?: emptyList()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun converterScreen(initialUris: List<Uri> = emptyList()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mimeTypes = arrayOf("image/*", "video/*")

    var selectedUris by remember { mutableStateOf<List<Uri>>(initialUris) }
    var selectedFormat by remember { mutableStateOf(TargetFormat.JPEG) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty()) {
            selectedUris = initialUris
        }
    }

    // Allows picking up to 50 images at once
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "OmniConvert",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Batch Image Processor • 100% Offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Image Preview Strip
        if (selectedUris.isNotEmpty()) {
            Text(
                "${selectedUris.size} item(s) queued",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(140.dp)
            ) {
                items(selectedUris) { uri ->
                    OutlinedCard {
                        AsyncImage(
                            model = uri,
                            contentDescription = "Selected Image",
                            modifier = Modifier.size(130.dp)
                        )
                    }
                }
            }
        } else {
            OutlinedCard(
                modifier = Modifier
                    .size(width = 240.dp, height = 140.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("No files selected", color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }) {
            Text("Select Photos (Single or Batch)")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Format selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetFormat.entries.filter { it != TargetFormat.M4A }.forEach { format ->
                FilterChip(
                    selected = (selectedFormat == format),
                    onClick = { selectedFormat = format },
                    label = { Text(format.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
        enabled = selectedUris.isNotEmpty() && !isProcessing,
        onClick = {
            isProcessing = true
            progressText = "Starting..."
            coroutineScope.launch {
                if (selectedFormat == TargetFormat.PDF) {
                    val result = ImageConverter.compileImagesToPdf(
                        context = context,
                        uris = selectedUris
                    ) { current, total ->
                        progressText = "Rendering Page $current of $total"
                    }
                    isProcessing = false
                    if (result.isSuccess) {
                        Toast.makeText(context, "Saved PDF to Downloads/OmniConvert!", Toast.LENGTH_LONG).show()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Unknown error"
                        Toast.makeText(context, "PDF Error: $err", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val results = ImageConverter.convertMultipleImages(
                        context = context,
                        uris = selectedUris,
                        targetFormat = selectedFormat
                    ) { current, total ->
                        progressText = "Processing: $current of $total"
                    }
                    isProcessing = false
                    val successCount = results.count { it.isSuccess }
                    val failure = results.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
                    
                    val msg = if (successCount == selectedUris.size) {
                        "Saved $successCount/${selectedUris.size} to Pictures/OmniConvert"
                    } else {
                        "Saved $successCount/${selectedUris.size}. Error: ${failure ?: "Decode/Write failed"}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
        ) {
            if (isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(progressText)
                }
            } else {
                Text(if (selectedUris.size > 1) "Convert All (${selectedUris.size} Images)" else "Convert & Save")
            }
        }
    }
}