package com.universalfiles.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.universalfiles.app.AppViewModel
import com.universalfiles.app.data.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

private class LayoutHolder {
    var layout: TextLayoutResult? = null
}

private class JNode(val depth: Int, val key: String, val path: String, val container: Boolean, val summary: String, val open: Boolean)

private val SYMBOLS = listOf(
    "Tab" to "\t", "{}" to "{}", "()" to "()", "[]" to "[]", "<>" to "<>", "\"\"" to "\"\"", "''" to "''",
    "/" to "/", "\\" to "\\", "=" to "=", ";" to ";", ":" to ":", "#" to "#", "$" to "$", "&" to "&",
    "|" to "|", "_" to "_", "-" to "-", "+" to "+", "*" to "*", "!" to "!", "?" to "?", "," to ",", "." to "."
)

private fun specialMode(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm", "xhtml", "md", "markdown", "svg" -> "Preview"
    "csv", "tsv" -> "Table"
    "json", "jsonc", "geojson" -> "Tree"
    else -> null
}

@Composable
private fun MenuRow(label: String, checked: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val tabs = vm.tabs
    LaunchedEffect(tabs.size) { if (tabs.isEmpty()) vm.closeOverlay() }
    if (tabs.isEmpty()) return

    val ctx = LocalContext.current
    val s = vm.settings
    val scope = rememberCoroutineScope()
    val idx = vm.activeTab.coerceIn(0, tabs.lastIndex)
    val tab = tabs[idx]
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val lang = remember(tab.name) { FileKinds.language(tab.name) }
    val special = specialMode(tab.name)

    var find by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var gotoDlg by remember { mutableStateOf(false) }
    var closeDlg by remember { mutableStateOf<Int?>(null) }
    var mode by remember(tab.path) { mutableIntStateOf(0) }
    val holder = remember { LayoutHolder() }
    val vScroll = remember(tab.path) { ScrollState(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(tab.path) { tab.load() }

    fun refocus() {
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }

    fun leave() {
        vm.autoSave()
        vm.closeOverlay()
    }

    fun reveal(offset: Int) {
        val l = holder.layout ?: return
        val o = offset.coerceIn(0, l.layoutInput.text.length)
        val y = l.getLineTop(l.getLineForOffset(o)).toInt()
        scope.launch { vScroll.animateScrollTo((y - 120).coerceAtLeast(0)) }
    }

    fun edit(v: TextFieldValue) {
        tab.edit(
            Smart.transform(tab.value, v, lang, " ".repeat(s.tabSize.value), s.autoIndent.value, s.autoClose.value)
        )
    }

    fun insert(sym: String) {
        val ins = if (sym == "\t") " ".repeat(s.tabSize.value) else sym
        val v = tab.value
        val a = v.selection.min
        val b = v.selection.max
        val caret = if (ins.length == 2 && sym != "\t") a + 1 else a + ins.length
        tab.edit(TextFieldValue(v.text.replaceRange(a, b, ins), TextRange(caret)))
        refocus()
    }

    fun findNext(forward: Boolean) {
        val t = tab.value.text
        if (query.isEmpty() || t.isEmpty()) return
        val sel = tab.value.selection
        var i = if (forward) t.indexOf(query, sel.max, !matchCase) else t.lastIndexOf(query, sel.min - 1, !matchCase)
        if (i < 0) i = if (forward) t.indexOf(query, 0, !matchCase) else t.lastIndexOf(query, t.length, !matchCase)
        if (i < 0) {
            vm.toast("No matches")
            return
        }
        tab.select(i, i + query.length)
        reveal(i)
        refocus()
    }

    fun replaceOne() {
        val v = tab.value
        val sel = v.selection
        if (query.isNotEmpty() && v.text.substring(sel.min, sel.max).equals(query, ignoreCase = !matchCase)) {
            tab.edit(TextFieldValue(v.text.replaceRange(sel.min, sel.max, repl), TextRange(sel.min + repl.length)))
        }
        findNext(true)
    }

    fun replaceAll() {
        if (query.isEmpty()) return
        val v = tab.value
        val nt = v.text.replace(query, repl, ignoreCase = !matchCase)
        if (nt != v.text) {
            tab.edit(TextFieldValue(nt, TextRange(0)))
            vm.toast("Replaced all")
        } else {
            vm.toast("No matches")
        }
    }

    fun goTo(line: Int) {
        val t = tab.value.text
        var off = 0
        var n = 1
        while (n < line) {
            val j = t.indexOf('\n', off)
            if (j < 0) break
            off = j + 1
            n++
        }
        tab.select(off, off)
        reveal(off)
        refocus()
    }

    fun reformat(minify: Boolean) {
        val t = tab.value.text
        try {
            JSONTokener(t).nextValue()
        } catch (e: Exception) {
            vm.toast("Invalid JSON: " + (e.message ?: ""))
            return
        }
        val out = if (minify) JsonFmt.minify(t) else JsonFmt.pretty(t, " ".repeat(s.tabSize.value))
        tab.edit(TextFieldValue(out, TextRange(0)))
    }

    val count = remember(tab.value.text, query, matchCase, find) {
        if (!find || query.isEmpty()) {
            0
        } else {
            val t = tab.value.text
            var c = 0
            var i = t.indexOf(query, 0, !matchCase)
            while (i >= 0 && c < 99999) {
                c++
                i = t.indexOf(query, i + query.length, !matchCase)
            }
            c
        }
    }

    BackHandler { if (find) find = false else leave() }

    ViewerScaffold(
        title = tab.name + if (tab.dirty) " •" else "",
        subtitle = File(tab.path).parent,
        snackbar = vm.snackbar,
        onBack = { leave() },
        actions = {
            IconButton(onClick = { tab.undo() }, enabled = tab.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
            IconButton(onClick = { tab.redo() }, enabled = tab.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
            IconButton(onClick = {
                scope.launch {
                    vm.toast(if (tab.save()) "Saved" else "Save failed")
                    vm.persistTabs()
                }
            }) { Icon(Icons.Filled.Save, "Save") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("Find & replace") { menu = false; find = true }
                    MenuRow("Go to line") { menu = false; gotoDlg = true }
                    MenuRow("Word wrap", s.wordWrap.value) { menu = false; s.wordWrap.set(!s.wordWrap.value) }
                    if (lang == Lang.JSON) {
                        MenuRow("Format JSON") { menu = false; reformat(false) }
                        MenuRow("Minify JSON") { menu = false; reformat(true) }
                    }
                    MenuRow("Reload from disk") { menu = false; scope.launch { tab.load(true) } }
                    MenuRow("Share") { menu = false; FileActions.share(ctx, listOf(tab.file)) }
                    MenuRow("Open with…") { menu = false; FileActions.openWith(ctx, tab.file) }
                }
            }
        },
        bottomBar = {
            if (mode == 0 && tab.loaded) EditorBottom(tab, lang) { insert(it) }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = idx, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = i == idx,
                        onClick = {
                            vm.activeTab = i
                            vm.persistTabs()
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    t.name + if (t.dirty) " •" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp)
                                )
                                IconButton(
                                    onClick = { if (t.dirty) closeDlg = i else vm.closeTab(i) },
                                    modifier = Modifier.size(28.dp)
                                ) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(16.dp)) }
                            }
                        }
                    )
                }
            }

            if (special != null) {
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Code") })
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text(special) })
                }
            }

            if (find && mode == 0) {
                FindBar(
                    query, { query = it }, repl, { repl = it }, matchCase, { matchCase = !matchCase }, count,
                    { findNext(true) }, { findNext(false) }, { replaceOne() }, { replaceAll() }, { find = false }
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val err = tab.error
                when {
                    err != null -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Can't open this file: $err")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scope.launch { tab.load(true) } }) { Text("Retry") }
                    }
                    !tab.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    mode == 1 && special == "Preview" -> WebPreview(tab)
                    mode == 1 && special == "Table" -> CsvTable(tab, if (tab.name.endsWith(".tsv", true)) '\t' else ',')
                    mode == 1 && special == "Tree" -> JsonTree(tab)
                    else -> CodeField(tab, lang, s, dark, vScroll, focus, holder) { edit(it) }
                }
            }
        }
    }

    if (gotoDlg) {
        TextInputDialog(
            title = "Go to line", label = "Line number", initial = "", confirm = "Go",
            onDismiss = { gotoDlg = false },
            onConfirm = {
                gotoDlg = false
                it.trim().toIntOrNull()?.let { n -> goTo(n) }
            }
        )
    }

    closeDlg?.let { i ->
        val t = tabs.getOrNull(i)
        if (t == null) {
            closeDlg = null
        } else {
            AlertDialog(
                onDismissRequest = { closeDlg = null },
                title = { Text("Unsaved changes") },
                text = { Text("Save changes to ${t.name} before closing?") },
                confirmButton = {
                    TextButton(onClick = {
                        closeDlg = null
                        scope.launch {
                            t.save()
                            vm.closeTab(i)
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            closeDlg = null
                            vm.closeTab(i)
                        }) { Text("Discard") }
                        TextButton(onClick = { closeDlg = null }) { Text("Cancel") }
                    }
                }
            )
        }
    }
}

@Composable
private fun CodeField(
    tab: EditorTab,
    lang: Lang,
    s: AppSettings,
    dark: Boolean,
    vScroll: ScrollState,
    focus: FocusRequester,
    holder: LayoutHolder,
    onEdit: (TextFieldValue) -> Unit
) {
    val v = tab.value
    val wrap = s.wordWrap.value
    val nums = s.lineNumbers.value && !wrap
    val size = s.fontSize.value.sp
    val colors = MaterialTheme.colorScheme
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        lineHeight = size * 1.4f,
        color = colors.onSurface
    )
    val lineCount = remember(v.text) {
        var c = 1
        for (ch in v.text) if (ch == '\n') c++
        c
    }
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val highlight = s.highlight.value
    val transform: VisualTransformation = remember(lang, dark, highlight) {
        if (highlight) HighlightTransformation(lang, dark) else VisualTransformation.None
    }
    val hScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewW = maxWidth
        val viewH = maxHeight
        Row(Modifier.fillMaxWidth().verticalScroll(vScroll).heightIn(min = viewH)) {
            if (nums) {
                Text(
                    gutter,
                    style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
                    modifier = Modifier.heightIn(min = viewH).padding(horizontal = 8.dp)
                )
            }
            val field: @Composable (Modifier) -> Unit = { m ->
                BasicTextField(
                    value = v,
                    onValueChange = onEdit,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    visualTransformation = transform,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
                    onTextLayout = { holder.layout = it },
                    modifier = m.focusRequester(focus)
                )
            }
            if (wrap) {
                field(Modifier.weight(1f).heightIn(min = viewH).padding(start = if (nums) 0.dp else 12.dp, end = 12.dp))
            } else {
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    field(Modifier.widthIn(min = viewW).heightIn(min = viewH).padding(start = if (nums) 0.dp else 12.dp, end = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun EditorBottom(tab: EditorTab, lang: Lang, onInsert: (String) -> Unit) {
    val v = tab.value
    val pos = remember(v.text, v.selection.start) {
        val st = v.selection.start.coerceIn(0, v.text.length)
        var l = 1
        var last = -1
        for (i in 0 until st) if (v.text[i] == '\n') {
            l++
            last = i
        }
        l to (st - last)
    }
    Surface(tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                SYMBOLS.forEach { (label, ins) ->
                    Text(
                        label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        modifier = Modifier.clickable { onInsert(ins) }.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
            Text(
                "Ln ${pos.first}, Col ${pos.second} · UTF-8 · ${lang.name.lowercase()} · ${v.text.length} chars",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun FindBar(
    query: String, onQuery: (String) -> Unit,
    repl: String, onRepl: (String) -> Unit,
    matchCase: Boolean, onCase: () -> Unit, count: Int,
    onNext: () -> Unit, onPrev: () -> Unit,
    onReplace: () -> Unit, onReplaceAll: () -> Unit, onClose: () -> Unit
) {
    var showRepl by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text("Find") }, modifier = Modifier.weight(1f)
                )
                Text("$count", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
                IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = matchCase, onClick = onCase, label = { Text("Aa") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = showRepl, onClick = { showRepl = !showRepl }, label = { Text("Replace") })
            }
            if (showRepl) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = repl, onValueChange = onRepl, singleLine = true,
                        placeholder = { Text("Replace with") }, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onReplace) { Text("One") }
                    TextButton(onClick = onReplaceAll) { Text("All") }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
private fun WebPreview(tab: EditorTab) {
    val ext = tab.name.substringAfterLast('.', "").lowercase()
    val text = tab.value.text
    val html = remember(text) { if (ext == "md" || ext == "markdown") Markdown.toHtml(text) else text }
    val base = "file://" + (tab.file.parent ?: "") + "/"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = true
                settings.allowFileAccessFromFileURLs = true
                webViewClient = WebViewClient()
            }
        },
        update = { w ->
            if (w.tag != html) {
                w.tag = html
                val mime = if (ext == "svg") "image/svg+xml" else "text/html"
                w.loadDataWithBaseURL(base, html, mime, "utf-8", null)
            }
        }
    )
}

@Composable
private fun CsvTable(tab: EditorTab, delim: Char) {
    var rows by remember { mutableStateOf(Csv.parse(tab.value.text, delim)) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val cols = rows.maxOfOrNull { it.size } ?: 0
    fun commit(r: List<List<String>>) {
        rows = r
        tab.edit(TextFieldValue(Csv.write(r, delim)))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = {
                commit(if (rows.isEmpty()) listOf(listOf("")) else rows + listOf(List(cols) { "" }))
            }) { Text("Add row") }
            OutlinedButton(onClick = {
                commit(if (rows.isEmpty()) listOf(listOf("")) else rows.map { it + "" })
            }) { Text("Add column") }
            Text("${rows.size} × $cols", style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width((cols * 140 + 44).dp)) {
                itemsIndexed(rows) { r, row ->
                    Row(Modifier.height(44.dp)) {
                        Text(
                            "${r + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(44.dp).padding(8.dp)
                        )
                        for (c in 0 until cols) {
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .fillMaxHeight()
                                    .background(if (r == 0) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .clickable { editing = r to c }
                                    .padding(8.dp)
                            ) {
                                Text(
                                    row.getOrElse(c) { "" },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { (r, c) ->
        var text by remember(r, c) { mutableStateOf(rows.getOrNull(r)?.getOrNull(c) ?: "") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Row ${r + 1}, column ${c + 1}") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    commit(rows.mapIndexed { i, row ->
                        if (i != r) row else List(maxOf(cols, row.size)) { j -> if (j == c) text else row.getOrElse(j) { "" } }
                    })
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        commit(rows.filterIndexed { i, _ -> i != r })
                        editing = null
                    }) { Text("Delete row") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun JsonTree(tab: EditorTab) {
    val parsed = remember(tab.value.text) {
        try {
            JSONTokener(tab.value.text).nextValue() to (null as String?)
        } catch (e: Exception) {
            null to (e.message ?: "Invalid JSON")
        }
    }
    val open = remember(tab.path) { mutableStateListOf("$") }
    val err = parsed.second
    if (err != null) {
        Column(Modifier.padding(16.dp)) {
            Text("Invalid JSON", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(err, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Switch to Code to fix it.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val flat = remember(parsed, open.toList()) {
        val out = ArrayList<JNode>()
        fun walk(v: Any?, key: String, path: String, depth: Int) {
            when (v) {
                is JSONObject -> {
                    val isOpen = path in open
                    out.add(JNode(depth, key, path, true, "{" + v.length() + "}", isOpen))
                    if (isOpen) {
                        val ks = v.keys()
                        var n = 0
                        while (ks.hasNext() && n < 500) {
                            val k = ks.next()
                            walk(v.opt(k), k, "$path/$k", depth + 1)
                            n++
                        }
                    }
                }
                is JSONArray -> {
                    val isOpen = path in open
                    out.add(JNode(depth, key, path, true, "[" + v.length() + "]", isOpen))
                    if (isOpen) {
                        for (i in 0 until minOf(v.length(), 500)) walk(v.opt(i), "[$i]", "$path/$i", depth + 1)
                    }
                }
                else -> {
                    val sum = when {
                        v == null || v == JSONObject.NULL -> "null"
                        v is String -> "\"" + v.take(200) + "\""
                        else -> v.toString()
                    }
                    out.add(JNode(depth, key, path, false, sum, false))
                }
            }
        }
        walk(parsed.first, "root", "$", 0)
        out
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(flat) { n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = n.container) {
                        if (n.path in open) open.remove(n.path) else open.add(n.path)
                    }
                    .padding(start = (8 + n.depth * 14).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (n.container) {
                    Icon(
                        if (n.open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null, modifier = Modifier.size(18.dp)
                    )
                } else {
                    Spacer(Modifier.width(18.dp))
                }
                Text(n.key + ": ", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(n.summary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
