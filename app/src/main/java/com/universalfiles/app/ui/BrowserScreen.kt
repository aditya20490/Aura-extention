package com.universalfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalfiles.app.AppViewModel
import com.universalfiles.app.Clip
import com.universalfiles.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private data class FEntry(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind,
    val children: Int
)

private sealed interface Dlg {
    object NewFile : Dlg
    object NewFolder : Dlg
    data class Rename(val file: File) : Dlg
    data class Delete(val paths: List<String>) : Dlg
    data class Zip(val paths: List<String>) : Dlg
    data class Extract(val file: File) : Dlg
    data class Props(val file: File) : Dlg
}

private enum class RowAction(val label: String, val icon: ImageVector) {
    OPEN_WITH("Open with…", Icons.Filled.OpenInNew),
    EDIT_TEXT("Edit as text", Icons.Filled.Edit),
    COPY("Copy", Icons.Filled.ContentCopy),
    CUT("Cut", Icons.Filled.ContentCut),
    RENAME("Rename", Icons.Filled.Edit),
    SHARE("Share", Icons.Filled.Share),
    ZIP("Compress", Icons.Filled.Archive),
    EXTRACT("Extract", Icons.Filled.Unarchive),
    SELECT("Select", Icons.Filled.CheckBox),
    DELETE("Delete", Icons.Filled.Delete),
    PROPS("Properties", Icons.Filled.Info)
}

private val EXTRACTABLE = setOf(
    "zip", "jar", "war", "ear", "apk", "apks", "xapk", "aab",
    "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub"
)

private fun canExtract(f: File) = f.isFile && FileKinds.ext(f) in EXTRACTABLE
private fun isGz(f: File) = f.isFile && FileKinds.ext(f) == "gz"

private fun entryOf(f: File): FEntry {
    val dir = f.isDirectory
    return FEntry(
        file = f,
        isDir = dir,
        size = if (dir) 0L else f.length(),
        modified = f.lastModified(),
        kind = FileKinds.detect(f, sniff = false),
        children = if (dir) (f.list()?.size ?: 0) else 0
    )
}

private fun sortEntries(list: List<FEntry>, by: String): List<FEntry> {
    val cmp: Comparator<FEntry> = when (by) {
        "date" -> compareByDescending<FEntry> { it.modified }
        "size" -> compareByDescending<FEntry> { it.size }
        "type" -> compareBy<FEntry> { FileKinds.ext(it.file) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        else -> compareBy<FEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
    }
    return list.sortedWith(compareByDescending<FEntry> { it.isDir }.then(cmp))
}

private fun subtitleOf(e: FEntry, inSearch: Boolean, base: String): String {
    val meta = if (e.isDir) {
        "${e.children} item${if (e.children == 1) "" else "s"}"
    } else {
        Fmt.size(e.size)
    }
    return if (inSearch) {
        val rel = e.file.parent.orEmpty().removePrefix(base).trimStart('/')
        "${if (rel.isEmpty()) "this folder" else rel} · $meta"
    } else {
        "$meta · ${Fmt.date(e.modified)}"
    }
}

private fun buildCrumbs(path: String, rootPath: String, rootName: String): List<Pair<String, String>> {
    val out = arrayListOf(rootName to rootPath)
    if (path.length > rootPath.length && path.startsWith(rootPath)) {
        var cur = rootPath
        path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }.forEach { seg ->
            cur = cur.trimEnd('/') + "/" + seg
            out.add(seg to cur)
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, onRequestAccess: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    val path = vm.browserPath
    val dir = File(path)

    val root = vm.roots
        .filter { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }
        .maxByOrNull { it.path.length }
    val rootPath = root?.path ?: "/"
    val rootName = root?.name ?: "/"

    var entries by remember { mutableStateOf<List<FEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unreadable by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var barMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val selecting = selected.isNotEmpty()
    val showHidden = s.showHidden.value
    val sortBy = s.sortBy.value
    val inSearch = searching && query.isNotBlank()

    // ---------- loading ----------

    LaunchedEffect(path, vm.refreshTick, showHidden, sortBy, query, searching) {
        val q = if (searching) query.trim() else ""
        if (q.isNotEmpty()) delay(300)
        loading = true
        val result: List<FEntry>? = withContext(Dispatchers.IO) {
            val scope = this
            if (q.isNotEmpty()) {
                val found = ArrayList<FEntry>()
                val walk = dir.walkTopDown().onEnter {
                    it == dir || (scope.isActive && (showHidden || !it.name.startsWith(".")))
                }
                for (f in walk) {
                    if (!scope.isActive || found.size >= 300) break
                    if (f == dir) continue
                    if (!showHidden && f.name.startsWith(".")) continue
                    if (f.name.contains(q, ignoreCase = true)) found.add(entryOf(f))
                }
                found
            } else {
                val list = dir.listFiles()
                if (list == null) {
                    null
                } else {
                    list.filter { showHidden || !it.name.startsWith(".") }.map { entryOf(it) }
                }
            }
        }
        unreadable = result == null
        entries = sortEntries(result ?: emptyList(), sortBy)
        loading = false
    }

    LaunchedEffect(searching) {
        if (searching) {
            delay(120)
            try {
                focus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- actions ----------

    fun go(p: String) {
        selected.clear()
        searching = false
        query = ""
        vm.browserPath = p
    }

    fun toggle(p: String) {
        if (p in selected) selected.remove(p) else selected.add(p)
    }

    fun selectAll() {
        entries.forEach { if (it.file.absolutePath !in selected) selected.add(it.file.absolutePath) }
    }

    fun doClip(paths: List<String>, cut: Boolean) {
        vm.clipboard = Clip(paths, cut)
        selected.clear()
        vm.toast("${paths.size} item(s) ready to ${if (cut) "move" else "copy"}. Open a folder and tap Paste.")
    }

    fun doShare(files: List<File>) {
        if (files.any { it.isDirectory }) {
            vm.toast("Folders can't be shared directly. Compress them first.")
            return
        }
        FileActions.share(ctx, files)
    }

    fun askDelete(paths: List<String>) {
        if (s.confirmDelete.value) {
            dlg = Dlg.Delete(paths)
        } else {
            vm.deletePaths(paths)
            selected.clear()
        }
    }

    fun handle(a: RowAction, e: FEntry) {
        val f = e.file
        when (a) {
            RowAction.OPEN_WITH -> FileActions.openWith(ctx, f)
            RowAction.EDIT_TEXT -> vm.openInEditor(f)
            RowAction.COPY -> doClip(listOf(f.absolutePath), false)
            RowAction.CUT -> doClip(listOf(f.absolutePath), true)
            RowAction.RENAME -> dlg = Dlg.Rename(f)
            RowAction.SHARE -> doShare(listOf(f))
            RowAction.ZIP -> dlg = Dlg.Zip(listOf(f.absolutePath))
            RowAction.EXTRACT -> {
                if (isGz(f)) {
                    val base = f.name.removeSuffix(".gz").ifEmpty { f.name + ".out" }
                    vm.gunzip(f, FileOps.uniqueName(f.parentFile ?: dir, base))
                } else {
                    dlg = Dlg.Extract(f)
                }
            }
            RowAction.SELECT -> toggle(f.absolutePath)
            RowAction.DELETE -> askDelete(listOf(f.absolutePath))
            RowAction.PROPS -> dlg = Dlg.Props(f)
        }
    }

    fun click(e: FEntry) {
        when {
            selecting -> toggle(e.file.absolutePath)
            e.isDir -> go(e.file.absolutePath)
            else -> vm.openFile(e.file)
        }
    }

    BackHandler(enabled = selecting || searching || path != rootPath) {
        when {
            selecting -> selected.clear()
            searching -> {
                searching = false
                query = ""
            }
            else -> dir.parentFile?.let { go(it.absolutePath) }
        }
    }

    // ---------- UI ----------

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                when {
                    selecting -> Text("${selected.size} selected")
                    searching -> TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search in ${dir.name.ifEmpty { "/" }}…") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                    else -> Text(
                        if (path == rootPath) rootName else dir.name.ifEmpty { "/" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                when {
                    selecting -> IconButton(onClick = { selected.clear() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                    }
                    searching -> IconButton(onClick = {
                        searching = false
                        query = ""
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Close search") }
                    path != rootPath -> IconButton(onClick = {
                        dir.parentFile?.let { go(it.absolutePath) }
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Up") }
                }
            },
            actions = {
                when {
                    selecting -> IconButton(onClick = { selectAll() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    searching -> {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    "name" to "Name",
                                    "date" to "Date (newest first)",
                                    "size" to "Size (largest first)",
                                    "type" to "Type"
                                ).forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            s.sortBy.set(key)
                                            sortMenu = false
                                        },
                                        trailingIcon = {
                                            if (sortBy == key) Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Show hidden files") },
                                    onClick = {
                                        s.showHidden.set(!showHidden)
                                        moreMenu = false
                                    },
                                    trailingIcon = {
                                        if (showHidden) Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                    onClick = {
                                        vm.refreshTick++
                                        moreMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Properties of this folder") },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = {
                                        dlg = Dlg.Props(dir)
                                        moreMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )

        if (searching) {
            Text(
                if (inSearch) "Results inside ${dir.name.ifEmpty { "/" }} and its subfolders"
                else "Type to search inside this folder and all subfolders",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else {
            Breadcrumbs(buildCrumbs(path, rootPath, rootName)) { go(it) }
        }
        HorizontalDivider()

        if (!vm.hasAccess) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Limited access: some folders and files can't be shown.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRequestAccess) { Text("Grant") }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                unreadable -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Lock, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Can't open this folder", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android blocks some system folders, such as Android/data on newer versions.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    if (!vm.hasAccess) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRequestAccess) { Text("Grant access") }
                    }
                }

                entries.isEmpty() && !loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (inSearch) "No matches" else "This folder is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(entries, key = { it.file.absolutePath }) { e ->
                        val p = e.file.absolutePath
                        EntryRow(
                            e = e,
                            selecting = selecting,
                            isSelected = p in selected,
                            subtitle = subtitleOf(e, inSearch, path),
                            onClick = { click(e) },
                            onLong = { toggle(p) },
                            onAction = { handle(it, e) }
                        )
                    }
                }
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))

            if (!selecting && !searching) {
                Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    FloatingActionButton(onClick = { fabMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create")
                    }
                    DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New file") },
                            leadingIcon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                dlg = Dlg.NewFile
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                dlg = Dlg.NewFolder
                            }
                        )
                    }
                }
            }
        }

        val clip = vm.clipboard
        if (clip != null && !selecting) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${clip.paths.size} item(s) to ${if (clip.cut) "move" else "copy"}",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.clipboard = null }) { Text("Cancel") }
                    Button(onClick = { vm.paste(dir) }) { Text("Paste here") }
                }
            }
        }

        if (selecting) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BarButton(Icons.Filled.ContentCopy, "Copy") { doClip(selected.toList(), false) }
                    BarButton(Icons.Filled.ContentCut, "Cut") { doClip(selected.toList(), true) }
                    BarButton(Icons.Filled.Delete, "Delete") { askDelete(selected.toList()) }
                    BarButton(Icons.Filled.Share, "Share") { doShare(selected.map { File(it) }) }
                    BarButton(Icons.Filled.Archive, "Zip") { dlg = Dlg.Zip(selected.toList()) }
                    Box {
                        BarButton(Icons.Filled.MoreVert, "More") { barMenu = true }
                        DropdownMenu(expanded = barMenu, onDismissRequest = { barMenu = false }) {
                            if (selected.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = {
                                        barMenu = false
                                        dlg = Dlg.Rename(File(selected.first()))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Properties") },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = {
                                        barMenu = false
                                        dlg = Dlg.Props(File(selected.first()))
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                                onClick = {
                                    barMenu = false
                                    selectAll()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- dialogs ----------

    when (val d = dlg) {
        null -> Unit

        Dlg.NewFile -> TextInputDialog(
            title = "New file",
            label = "File name",
            initial = "untitled.txt",
            confirm = "Create",
            selectBaseName = true,
            hint = "Use an extension like .html, .css, .json, .csv, .xml or .sql",
            onDismiss = { dlg = null },
            onConfirm = { name ->
                dlg = null
                vm.createFile(dir, name, false)
            }
        )

        Dlg.NewFolder -> TextInputDialog(
            title = "New folder",
            label = "Folder name",
            initial = "New folder",
            confirm = "Create",
            onDismiss = { dlg = null },
            onConfirm = { name ->
                dlg = null
                vm.createFile(dir, name, true)
            }
        )

        is Dlg.Rename -> TextInputDialog(
            title = "Rename",
            label = "New name",
            initial = d.file.name,
            confirm = "Rename",
            selectBaseName = d.file.isFile,
            onDismiss = { dlg = null },
            onConfirm = { name ->
                dlg = null
                selected.clear()
                vm.rename(d.file, name)
            }
        )

        is Dlg.Delete -> ConfirmDialog(
            title = "Delete",
            text = if (d.paths.size == 1) {
                "Delete \"${File(d.paths[0]).name}\"? This can't be undone."
            } else {
                "Delete ${d.paths.size} items? This can't be undone."
            },
            confirm = "Delete",
            onDismiss = { dlg = null },
            onConfirm = {
                dlg = null
                selected.clear()
                vm.deletePaths(d.paths)
            }
        )

        is Dlg.Zip -> TextInputDialog(
            title = "Compress to zip",
            label = "Archive name",
            initial = if (d.paths.size == 1) File(d.paths[0]).nameWithoutExtension + ".zip" else "archive.zip",
            confirm = "Compress",
            selectBaseName = true,
            hint = "Created in ${dir.name.ifEmpty { "/" }}",
            onDismiss = { dlg = null },
            onConfirm = { name ->
                dlg = null
                var n = name.trim()
                if (n.isEmpty() || n.contains('/')) {
                    vm.toast("Invalid name")
                } else {
                    if (!n.endsWith(".zip", ignoreCase = true)) n += ".zip"
                    selected.clear()
                    vm.zipPaths(d.paths, FileOps.uniqueName(dir, n))
                }
            }
        )

        is Dlg.Extract -> TextInputDialog(
            title = "Extract",
            label = "Destination folder",
            initial = d.file.nameWithoutExtension,
            confirm = "Extract",
            hint = "Created next to the archive",
            onDismiss = { dlg = null },
            onConfirm = { name ->
                dlg = null
                val n = name.trim()
                if (n.isEmpty() || n.contains('/')) {
                    vm.toast("Invalid name")
                } else {
                    vm.extractZip(d.file, FileOps.uniqueName(d.file.parentFile ?: dir, n))
                }
            }
        )

        is Dlg.Props -> PropertiesDialog(d.file) { dlg = null }
    }
}

@Composable
private fun Breadcrumbs(crumbs: List<Pair<String, String>>, onGo: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.lastOrNull()?.second) {
        delay(80)
        scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { i, (name, p) ->
            if (i > 0) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val last = i == crumbs.lastIndex
            Text(
                name,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { onGo(p) }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: FEntry,
    selecting: Boolean,
    isSelected: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onAction: (RowAction) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLong),
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        ),
        leadingContent = { FileIcon(e.kind, Modifier.size(32.dp)) },
        headlineContent = { Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            if (selecting) {
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                val actions = buildList {
                    if (!e.isDir) {
                        add(RowAction.OPEN_WITH)
                        add(RowAction.EDIT_TEXT)
                    }
                    add(RowAction.COPY)
                    add(RowAction.CUT)
                    add(RowAction.RENAME)
                    if (!e.isDir) add(RowAction.SHARE)
                    add(RowAction.ZIP)
                    if (canExtract(e.file) || isGz(e.file)) add(RowAction.EXTRACT)
                    add(RowAction.SELECT)
                    add(RowAction.DELETE)
                    add(RowAction.PROPS)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        actions.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                leadingIcon = { Icon(a.icon, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onAction(a)
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
