package com.universalfiles.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalfiles.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FileIcon(kind: FileKind, modifier: Modifier = Modifier) {
    val (icon, color) = when (kind) {
        FileKind.FOLDER -> Icons.Filled.Folder to Color(0xFFF9A825)
        FileKind.TEXT -> Icons.Filled.Description to Color(0xFF1E88E5)
        FileKind.PDF -> Icons.Filled.PictureAsPdf to Color(0xFFE53935)
        FileKind.IMAGE -> Icons.Filled.Image to Color(0xFF43A047)
        FileKind.AUDIO -> Icons.Filled.Audiotrack to Color(0xFFFB8C00)
        FileKind.VIDEO -> Icons.Filled.Movie to Color(0xFF8E24AA)
        FileKind.ARCHIVE -> Icons.Filled.Archive to Color(0xFF6D4C41)
        FileKind.OTHER -> Icons.Filled.InsertDriveFile to Color(0xFF78909C)
    }
    Icon(icon, contentDescription = null, tint = color, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScaffold(
    title: String,
    subtitle: String? = null,
    snackbar: SnackbarHostState? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content
    )
}

@Composable
fun ComingSoon(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title arrives in the next part", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String = "OK",
    singleLine: Boolean = true,
    selectBaseName: Boolean = false,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var tf by remember {
        val end = if (selectBaseName) initial.substringBeforeLast('.', initial).length else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, end)))
    }
    val fr = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = tf,
                onValueChange = { tf = it },
                label = { Text(label) },
                singleLine = singleLine,
                supportingText = if (hint != null) ({ Text(hint) }) else null,
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(tf.text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    LaunchedEffect(Unit) {
        delay(150)
        try {
            fr.requestFocus()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PropLine(k: String, v: String) {
    Column {
        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PropertiesDialog(file: File, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf<Long?>(if (file.isFile) file.length() else null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var hashing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file) {
        if (file.isDirectory) size = withContext(Dispatchers.IO) { FileOps.dirSize(file) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties", fontWeight = FontWeight.SemiBold) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PropLine("Name", file.name)
                    PropLine("Path", file.absolutePath)
                    PropLine("Type", if (file.isDirectory) "Folder" else FileKinds.mime(file))
                    PropLine("Size", size?.let { Fmt.size(it) } ?: "Calculating…")
                    PropLine("Modified", Fmt.date(file.lastModified()))
                    PropLine(
                        "Access",
                        (if (file.canRead()) "read " else "") + (if (file.canWrite()) "write" else "read-only")
                    )
                    if (file.isFile) {
                        val h = hash
                        if (h != null) {
                            PropLine("SHA-256", h)
                        } else {
                            TextButton(onClick = {
                                hashing = true
                                scope.launch {
                                    hash = withContext(Dispatchers.IO) { FileOps.hash(file, "SHA-256") }
                                    hashing = false
                                }
                            }) { Text(if (hashing) "Calculating…" else "Calculate SHA-256") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
