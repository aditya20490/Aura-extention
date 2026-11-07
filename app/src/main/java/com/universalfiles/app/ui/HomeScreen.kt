package com.universalfiles.app.ui

import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalfiles.app.AppViewModel
import com.universalfiles.app.data.*
import java.io.File

private fun placeIcon(kind: String): ImageVector = when (kind) {
    "storage" -> Icons.Filled.Storage
    "sd" -> Icons.Filled.SdStorage
    "download" -> Icons.Filled.Download
    "documents" -> Icons.Filled.Description
    "pictures" -> Icons.Filled.Image
    "camera" -> Icons.Filled.CameraAlt
    "music" -> Icons.Filled.MusicNote
    "movies" -> Icons.Filled.Movie
    "projects" -> Icons.Filled.Code
    "app" -> Icons.Filled.Android
    else -> Icons.Filled.Folder
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageCard(place: Place, onClick: () -> Unit) {
    val (used, total) = remember(place.path) {
        try {
            val s = StatFs(place.path)
            val t = s.blockCountLong * s.blockSizeLong
            val f = s.availableBlocksLong * s.blockSizeLong
            (t - f) to t
        } catch (e: Exception) {
            0L to 0L
        }
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(placeIcon(place.kind), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(place.name, style = MaterialTheme.typography.titleMedium)
            }
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { used.toFloat() / total },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${Fmt.size(used)} used of ${Fmt.size(total)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, onRequestAccess: () -> Unit) {
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf<String?>(null) }
    val recent = remember(vm.recents.toList(), vm.refreshTick) {
        vm.recents.take(8).map { File(it) }.filter { it.exists() }
    }
    val templates = listOf(
        "index.html", "style.css", "script.js", "data.json", "data.csv",
        "query.sql", "config.xml", "README.md", "main.py", "notes.txt"
    )

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Universal Files") })
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!vm.hasAccess) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Allow file access", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "To browse and edit files anywhere on your phone, allow " +
                                    "access to all files. Until then, only the app's own folder works.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = onRequestAccess) { Text("Grant access") }
                        }
                    }
                }
            }

            item { SectionTitle("Storage") }
            items(vm.roots) { p -> StorageCard(p) { vm.openFolder(p.path) } }

            if (vm.tabs.isNotEmpty()) {
                item {
                    Card(
                        onClick = { vm.openInEditor(File(vm.tabs[vm.activeTab.coerceIn(0, vm.tabs.lastIndex)].path)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Continue editing", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${vm.tabs.size} open file(s)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Create a file")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        templates.forEach { t ->
                            AssistChip(
                                onClick = { newName = t },
                                label = { Text(t) },
                                leadingIcon = { Icon(Icons.Filled.NoteAdd, null, Modifier.size(18.dp)) }
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Quick access")
                    vm.quick.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { p ->
                                OutlinedCard(
                                    onClick = { vm.openFolder(p.path) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(placeIcon(p.kind), null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionTitle("Recent") }
                items(recent, key = { it.absolutePath }) { f ->
                    ListItem(
                        modifier = Modifier.clickable { vm.openFile(f) },
                        leadingContent = { FileIcon(FileKinds.detect(f, sniff = false)) },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                f.parent ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    }

    newName?.let { n ->
        val dir = Storage.workspace(ctx)
        TextInputDialog(
            title = "New file",
            label = "File name",
            initial = n,
            confirm = "Create",
            selectBaseName = true,
            hint = "Created in ${dir.absolutePath}",
            onDismiss = { newName = null },
            onConfirm = { name ->
                newName = null
                vm.createFile(dir, name, false)
            }
        )
    }
}
