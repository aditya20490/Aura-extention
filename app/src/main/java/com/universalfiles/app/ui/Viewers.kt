package com.universalfiles.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.universalfiles.app.AppViewModel
import com.universalfiles.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

@Composable
private fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, act) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    act()
                })
            }
        }
    }
}

@Composable
private fun Problem(text: String, onOpenWith: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenWith) { Text("Open with another app") }
    }
}

// ======================= PDF =======================

@Composable
fun PdfScreen(vm: AppViewModel, path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val lock = remember(path) { Any() }
    var renderer by remember(path) { mutableStateOf<PdfRenderer?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var gotoDlg by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    DisposableEffect(path) {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = p
            val pr = PdfRenderer(p)
            r = pr
            renderer = pr
        } catch (e: SecurityException) {
            error = "This PDF is password-protected. Use \"Open with\" to view it in another app."
        } catch (e: Exception) {
            error = "Can't render this PDF: " + (e.message ?: "unknown error")
        }
        onDispose {
            synchronized(lock) {
                try {
                    r?.close()
                } catch (_: Exception) {
                }
                try {
                    pfd?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    ViewerScaffold(
        title = file.name,
        subtitle = renderer?.let { "${it.pageCount} pages" },
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() },
        actions = {
            IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
            IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(3f) }) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
            MoreMenu(
                listOf(
                    "Go to page" to { gotoDlg = true },
                    "Open with…" to { FileActions.openWith(ctx, file) },
                    "Share" to { FileActions.share(ctx, listOf(file)) }
                )
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val r = renderer
            val err = error
            when {
                err != null -> Problem(err) { FileActions.openWith(ctx, file) }
                r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val pageW = maxWidth * zoom
                    val px = with(LocalDensity.current) { pageW.roundToPx() }.coerceAtMost(2800)
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(
                            state = list,
                            modifier = Modifier.width(pageW),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(r.pageCount) { i -> PdfPage(r, lock, i, px) }
                        }
                    }
                    Surface(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp
                    ) {
                        Text(
                            "${list.firstVisibleItemIndex + 1} / ${r.pageCount}",
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    if (gotoDlg) {
        TextInputDialog(
            title = "Go to page", label = "Page number", initial = "", confirm = "Go",
            onDismiss = { gotoDlg = false },
            onConfirm = {
                gotoDlg = false
                val n = it.trim().toIntOrNull()
                val c = renderer?.pageCount ?: 0
                if (n != null && c > 0) scope.launch { list.scrollToItem((n - 1).coerceIn(0, c - 1)) }
            }
        )
    }
}

@Composable
private fun PdfPage(r: PdfRenderer, lock: Any, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val page = r.openPage(index)
                    try {
                        val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    } finally {
                        page.close()
                    }
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    }
}

// ======================= IMAGE =======================

private fun decodeImage(path: String, maxDim: Int): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= maxDim || o.outHeight / (s * 2) >= maxDim) s *= 2
    val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    if (b == null) {
        null
    } else {
        val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }
} catch (e: Throwable) {
    null
}

private fun imageDetails(f: File): String {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    val sb = StringBuilder("${o.outWidth} × ${o.outHeight} px\n${Fmt.size(f.length())}\n${o.outMimeType ?: ""}")
    try {
        val e = ExifInterface(f.absolutePath)
        val tags = listOf(
            "Date" to ExifInterface.TAG_DATETIME, "Make" to ExifInterface.TAG_MAKE,
            "Model" to ExifInterface.TAG_MODEL, "Exposure" to ExifInterface.TAG_EXPOSURE_TIME,
            "Focal length" to ExifInterface.TAG_FOCAL_LENGTH
        )
        for ((label, tag) in tags) e.getAttribute(tag)?.let { sb.append("\n$label: $it") }
    } catch (_: Exception) {
    }
    return sb.toString()
}

@Composable
fun ImageScreen(vm: AppViewModel, path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    var done by remember(path) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var info by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) { decodeImage(path, 2400) }
        done = true
    }

    ViewerScaffold(
        title = file.name,
        subtitle = Fmt.size(file.length()),
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() },
        actions = {
            MoreMenu(
                listOf(
                    "Details" to { info = true },
                    "Open with…" to { FileActions.openWith(ctx, file) },
                    "Share" to { FileActions.share(ctx, listOf(file)) }
                )
            )
        }
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 8f)
                        scale = ns
                        val mx = size.width * (ns - 1f) / 2f
                        val my = size.height * (ns - 1f) / 2f
                        off = Offset((off.x + pan.x).coerceIn(-mx, mx), (off.y + pan.y).coerceIn(-my, my))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            off = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            when {
                b != null -> Image(
                    b.asImageBitmap(), file.name,
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                    contentScale = ContentScale.Fit
                )
                done -> Text("Can't display this image here.", color = Color.White)
                else -> CircularProgressIndicator()
            }
        }
    }

    if (info) {
        val text = remember(path) { imageDetails(file) }
        AlertDialog(
            onDismissRequest = { info = false },
            title = { Text("Image details") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } }
        )
    }
}

// ======================= AUDIO / VIDEO =======================

@Composable
fun MediaScreen(vm: AppViewModel, path: String, video: Boolean) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val exo = remember(path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    var err by remember(path) { mutableStateOf<String?>(null) }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                err = error.message ?: "Playback error"
            }
        }
        exo.addListener(l)
        onDispose {
            exo.removeListener(l)
            exo.release()
        }
    }

    ViewerScaffold(
        title = file.name,
        subtitle = Fmt.size(file.length()),
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() },
        actions = {
            MoreMenu(
                listOf(
                    "Open with…" to { FileActions.openWith(ctx, file) },
                    "Share" to { FileActions.share(ctx, listOf(file)) }
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!video) {
                Icon(Icons.Filled.Audiotrack, null, Modifier.size(96.dp), tint = Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text(file.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(24.dp))
            }
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        player = exo
                        useController = true
                        controllerShowTimeoutMs = if (video) 3000 else 0
                        controllerHideOnTouch = video
                    }
                },
                modifier = if (video) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(160.dp)
            )
            err?.let {
                Text("Can't play this file: $it", color = Color.White, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

// ======================= ARCHIVES =======================

private class ARow(val name: String, val dir: Boolean, val size: Long, val full: String)

@Composable
fun ArchiveScreen(vm: AppViewModel, path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    var entries by remember(path) { mutableStateOf<List<Pair<String, ArcEntry>>?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var prefix by remember(path) { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var extractDlg by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) {
            try {
                Result.success(Archives.list(file))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        r.onSuccess { list ->
            entries = list.map { e -> e.name.replace('\\', '/').removePrefix("./").trimStart('/') to e }
        }.onFailure { error = it.message ?: "Can't read this archive" }
    }

    BackHandler(enabled = prefix.isNotEmpty()) {
        val up = prefix.dropLast(1).substringBeforeLast('/', "")
        prefix = if (up.isEmpty()) "" else "$up/"
    }

    val rows = remember(entries, prefix) {
        val map = LinkedHashMap<String, ARow>()
        entries?.forEach { (norm, e) ->
            if (norm.startsWith(prefix) && norm.length > prefix.length) {
                val rest = norm.substring(prefix.length)
                val seg = rest.substringBefore('/')
                val isDir = rest.contains('/') || e.dir
                if (seg.isNotEmpty() && seg != "..") {
                    val old = map[seg]
                    if (old == null || (!old.dir && isDir)) map[seg] = ARow(seg, isDir, if (isDir) 0L else e.size, e.name)
                }
            }
        }
        map.values.sortedWith(compareBy<ARow>({ !it.dir }, { it.name.lowercase() }))
    }

    fun openEntry(r: ARow) {
        scope.launch {
            busy = "Opening…"
            val out = File(ctx.cacheDir, "arc/" + file.name + "/" + r.name)
            val err = withContext(Dispatchers.IO) {
                try {
                    Archives.extractEntry(file, r.full, out)
                    null
                } catch (e: Throwable) {
                    e.message ?: "error"
                }
            }
            busy = null
            if (err == null) vm.openFile(out) else vm.toast("Couldn't open: $err")
        }
    }

    val baseName = remember(path) {
        val n = file.name
        listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz")
            .firstOrNull { n.endsWith(it, true) }?.let { n.dropLast(it.length) } ?: n.substringBeforeLast('.', n)
    }

    ViewerScaffold(
        title = file.name,
        subtitle = if (prefix.isEmpty()) "${entries?.size ?: 0} entries" else prefix,
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() },
        actions = {
            if (error == null) {
                IconButton(onClick = { extractDlg = true }) { Icon(Icons.Filled.Unarchive, "Extract all") }
            }
            MoreMenu(
                listOf(
                    "Open with…" to { FileActions.openWith(ctx, file) },
                    "Share" to { FileActions.share(ctx, listOf(file)) }
                )
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val err = error
            when {
                err != null -> Problem(err) { FileActions.openWith(ctx, file) }
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                rows.isEmpty() -> Text("Empty", Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.name }) { r ->
                        ListItem(
                            modifier = Modifier.clickable {
                                if (r.dir) prefix = prefix + r.name + "/" else openEntry(r)
                            },
                            leadingContent = {
                                FileIcon(
                                    if (r.dir) FileKind.FOLDER else FileKinds.detect(File(r.name), sniff = false),
                                    Modifier.size(28.dp)
                                )
                            },
                            headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                if (!r.dir) Text(if (r.size >= 0) Fmt.size(r.size) else "compressed file")
                            }
                        )
                    }
                }
            }
            val b = busy
            if (b != null) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(b, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (extractDlg) {
        TextInputDialog(
            title = "Extract all", label = "Destination folder", initial = baseName, confirm = "Extract",
            hint = "Created next to the archive",
            onDismiss = { extractDlg = false },
            onConfirm = { name ->
                extractDlg = false
                val n = name.trim()
                if (n.isEmpty() || n.contains('/')) {
                    vm.toast("Invalid name")
                } else {
                    scope.launch {
                        busy = "Extracting…"
                        val dest = FileOps.uniqueName(file.parentFile ?: ctx.filesDir, n)
                        val msg = withContext(Dispatchers.IO) {
                            try {
                                "Extracted ${Archives.extractAll(file, dest)} file(s) to ${dest.name}"
                            } catch (e: Throwable) {
                                "Failed: " + (e.message ?: "unknown error")
                            }
                        }
                        busy = null
                        vm.refreshTick++
                        vm.toast(msg)
                    }
                }
            }
        )
    }
}

// ======================= INFO + HEX =======================

private fun sniff(f: File): String? {
    val b = ByteArray(16)
    val n = try {
        f.inputStream().use { it.read(b) }
    } catch (e: Exception) {
        -1
    }
    if (n < 0) return null
    if (n == 0) return "Empty file"
    fun st(s: String) = n >= s.length && s.indices.all { b[it] == s[it].code.toByte() }
    return when {
        st("%PDF") -> "PDF document"
        st("PK\u0003\u0004") -> "ZIP-based archive"
        st("\u0089PNG") -> "PNG image"
        n >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> "JPEG image"
        st("GIF8") -> "GIF image"
        st("7z\u00BC\u00AF") -> "7-Zip archive"
        st("Rar!") -> "RAR archive"
        st("\u007FELF") -> "ELF executable"
        st("MZ") -> "Windows executable"
        st("SQLite format 3") -> "SQLite database"
        st("ID3") -> "MP3 audio"
        st("fLaC") -> "FLAC audio"
        st("OggS") -> "Ogg media"
        st("RIFF") -> "RIFF container (WAV, AVI or WebP)"
        st("\u001F\u008B") -> "Gzip data"
        st("BZh") -> "Bzip2 data"
        st("dex\n") -> "Android DEX"
        n >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "MPEG-4 container"
        else -> null
    }
}

@Composable
fun InfoScreen(vm: AppViewModel, path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    var tab by remember { mutableIntStateOf(0) }
    val sig = remember(path) { sniff(file) }

    ViewerScaffold(
        title = file.name,
        subtitle = sig ?: "Unknown format",
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() },
        actions = {
            MoreMenu(
                listOf(
                    "Open with…" to { FileActions.openWith(ctx, file) },
                    "Share" to { FileActions.share(ctx, listOf(file)) }
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Info") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Hex") })
            }
            if (tab == 0) InfoTab(vm, file, sig) else HexTab(file)
        }
    }
}

@Composable
private fun InfoTab(vm: AppViewModel, file: File, sig: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hashes by remember { mutableStateOf(mapOf<String, String>()) }
    var working by remember { mutableStateOf<String?>(null) }
    val lines = listOf(
        "Name" to file.name,
        "Path" to file.absolutePath,
        "Detected type" to (sig ?: "Unknown"),
        "Size" to "${Fmt.size(file.length())} (${file.length()} bytes)",
        "Modified" to Fmt.date(file.lastModified())
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        lines.forEach { (k, v) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        for (algo in listOf("MD5", "SHA-1", "SHA-256")) {
            val h = hashes[algo]
            if (h != null) {
                Column {
                    Text(algo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(h, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = {
                    working = algo
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try {
                                FileOps.hash(file, algo)
                            } catch (e: Exception) {
                                "error"
                            }
                        }
                        hashes = hashes + (algo to r)
                        working = null
                    }
                }) { Text(if (working == algo) "Calculating…" else "Calculate $algo") }
            }
        }
        if (file.length() <= MAX_EDIT_BYTES) {
            Button(onClick = { vm.openInEditor(file) }) { Text("Edit as text (may corrupt binary files)") }
        }
        OutlinedButton(onClick = { FileActions.openWith(ctx, file) }) { Text("Open with another app") }
    }
}

@Composable
private fun HexTab(file: File) {
    val raf = remember(file) {
        try {
            RandomAccessFile(file, "r")
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(raf) {
        onDispose {
            try {
                raf?.close()
            } catch (_: Exception) {
            }
        }
    }
    val len = file.length()
    val per = 8
    val rowsN = ((len + per - 1) / per).toInt().coerceAtLeast(0)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jump by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$len bytes", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { jump = true }) { Text("Go to offset") }
        }
        if (raf == null || rowsN == 0) {
            Text("Nothing to show", Modifier.padding(16.dp))
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(rowsN) { r ->
                    val off = r.toLong() * per
                    val line = remember(off) {
                        val n = minOf(per.toLong(), len - off).toInt()
                        val b = ByteArray(n)
                        try {
                            raf.seek(off)
                            raf.readFully(b)
                        } catch (_: Exception) {
                        }
                        val hex = StringBuilder()
                        val asc = StringBuilder()
                        for (i in 0 until per) {
                            if (i < n) {
                                hex.append("%02X ".format(b[i]))
                                val c = b[i].toInt() and 0xFF
                                asc.append(if (c in 32..126) c.toChar() else '.')
                            } else {
                                hex.append("   ")
                            }
                        }
                        "%08X  %s %s".format(off, hex, asc)
                    }
                    Text(
                        line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    if (jump) {
        TextInputDialog(
            title = "Go to offset", label = "Offset (hex)", initial = "0", confirm = "Go",
            onDismiss = { jump = false },
            onConfirm = {
                jump = false
                val v = it.trim().removePrefix("0x").toLongOrNull(16)
                if (v != null && rowsN > 0) scope.launch { list.scrollToItem((v / per).toInt().coerceIn(0, rowsN - 1)) }
            }
        )
    }
}
