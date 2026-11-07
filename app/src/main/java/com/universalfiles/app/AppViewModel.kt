package com.universalfiles.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universalfiles.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MainTab { HOME, FILES, SETTINGS }

sealed interface Overlay {
    object Editor : Overlay
    data class Pdf(val path: String) : Overlay
    data class Image(val path: String) : Overlay
    data class Media(val path: String, val video: Boolean) : Overlay
    data class Archive(val path: String) : Overlay
    data class Info(val path: String) : Overlay
}

class Clip(val paths: List<String>, val cut: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    val snackbar = SnackbarHostState()

    var mainTab by mutableStateOf(MainTab.HOME)
    val overlays = mutableStateListOf<Overlay>()

    var hasAccess by mutableStateOf(false)
        private set
    var roots by mutableStateOf<List<Place>>(emptyList())
        private set
    var quick by mutableStateOf<List<Place>>(emptyList())
        private set

    var browserPath by mutableStateOf(Storage.primaryRoot.absolutePath)
    val tabs = mutableStateListOf<EditorTab>()
    var activeTab by mutableIntStateOf(0)
    var clipboard by mutableStateOf<Clip?>(null)
    val recents = mutableStateListOf<String>()
    var busy by mutableStateOf<String?>(null)
        private set
    var refreshTick by mutableIntStateOf(0)

    private val prefs get() = settings.prefs
    private val ctx get() = getApplication<Application>()

    init {
        prefs.getString("recents", "")!!.split('\n').filter { it.isNotBlank() }.forEach { recents.add(it) }
        prefs.getString("tabs", "")!!.split('\n')
            .filter { it.isNotBlank() && File(it).isFile }
            .forEach { tabs.add(EditorTab(it)) }
        activeTab = prefs.getInt("active_tab", 0).coerceIn(0, maxOf(0, tabs.size - 1))
        refreshAccess()
    }

    fun refreshAccess() {
        hasAccess = Storage.hasAccess(ctx)
        roots = Storage.places(ctx)
        quick = Storage.quick(ctx)
    }

    fun toast(msg: String) {
        viewModelScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    // ---------- navigation ----------

    fun closeOverlay() {
        if (overlays.isNotEmpty()) overlays.removeAt(overlays.lastIndex)
    }

    fun openFolder(path: String) {
        File(path).mkdirs()
        browserPath = path
        mainTab = MainTab.FILES
    }

    fun openFile(f: File) {
        if (!f.exists()) {
            toast("File not found")
            return
        }
        val p = f.absolutePath
        when (FileKinds.detect(f)) {
            FileKind.FOLDER -> openFolder(p)
            FileKind.TEXT -> { addRecent(p); openInEditor(f) }
            FileKind.PDF -> { addRecent(p); overlays.add(Overlay.Pdf(p)) }
            FileKind.IMAGE -> { addRecent(p); overlays.add(Overlay.Image(p)) }
            FileKind.AUDIO -> { addRecent(p); overlays.add(Overlay.Media(p, false)) }
            FileKind.VIDEO -> { addRecent(p); overlays.add(Overlay.Media(p, true)) }
            FileKind.ARCHIVE -> { addRecent(p); overlays.add(Overlay.Archive(p)) }
            FileKind.OTHER -> { addRecent(p); overlays.add(Overlay.Info(p)) }
        }
    }

    fun openInEditor(f: File) {
        if (f.length() > MAX_EDIT_BYTES) {
            toast("Too large for the editor (max 2 MB)")
            overlays.add(Overlay.Info(f.absolutePath))
            return
        }
        val idx = tabs.indexOfFirst { it.path == f.absolutePath }
        if (idx >= 0) {
            activeTab = idx
        } else {
            tabs.add(EditorTab(f.absolutePath))
            activeTab = tabs.lastIndex
        }
        if (overlays.lastOrNull() != Overlay.Editor) overlays.add(Overlay.Editor)
        persistTabs()
    }

    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        tabs.removeAt(i)
        if (i < activeTab) activeTab--
        activeTab = activeTab.coerceIn(0, maxOf(0, tabs.lastIndex))
        persistTabs()
    }

    fun persistTabs() {
        prefs.edit()
            .putString("tabs", tabs.joinToString("\n") { it.path })
            .putInt("active_tab", activeTab)
            .apply()
    }

    private fun addRecent(path: String) {
        recents.remove(path)
        recents.add(0, path)
        while (recents.size > 30) recents.removeAt(recents.lastIndex)
        prefs.edit().putString("recents", recents.joinToString("\n")).apply()
    }

    fun autoSave() {
        if (!settings.autoSave.value) return
        val dirty = tabs.filter { it.dirty && it.loaded }
        if (dirty.isEmpty()) return
        viewModelScope.launch { dirty.forEach { it.save() } }
        persistTabs()
    }

    // ---------- file operations ----------

    private fun runBusy(label: String, work: () -> String) {
        viewModelScope.launch {
            busy = label
            val msg = withContext(Dispatchers.IO) {
                try {
                    work()
                } catch (e: Exception) {
                    "Failed: " + (e.message ?: "unknown error")
                }
            }
            busy = null
            refreshTick++
            toast(msg)
        }
    }

    fun createFile(dir: File, name: String, isFolder: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean == "." || clean == "..") {
            toast("Invalid name")
            return
        }
        viewModelScope.launch {
            val f = File(dir, clean)
            val err = withContext(Dispatchers.IO) {
                try {
                    dir.mkdirs()
                    when {
                        f.exists() -> "exists"
                        isFolder -> { f.mkdirs(); null }
                        else -> { f.writeText(Templates.content(clean)); null }
                    }
                } catch (e: Exception) {
                    e.message ?: "error"
                }
            }
            when (err) {
                null -> {
                    refreshTick++
                    if (isFolder) toast("Folder created") else openFile(f)
                }
                "exists" -> toast("\"$clean\" already exists")
                else -> toast("Couldn't create: $err")
            }
        }
    }

    fun deletePaths(paths: List<String>) = runBusy("Deleting…") {
        paths.forEach { File(it).deleteRecursively() }
        tabs.removeAll { t -> paths.any { t.path == it || t.path.startsWith(it + "/") } }
        activeTab = activeTab.coerceIn(0, maxOf(0, tabs.lastIndex))
        "Deleted ${paths.size} item(s)"
    }

    fun paste(dir: File) {
        val c = clipboard ?: return
        clipboard = null
        runBusy(if (c.cut) "Moving…" else "Copying…") {
            c.paths.forEach { p ->
                val src = File(p)
                if (c.cut) FileOps.move(src, dir) else FileOps.copyInto(src, dir)
            }
            if (c.cut) "Moved ${c.paths.size} item(s)" else "Copied ${c.paths.size} item(s)"
        }
    }

    fun zipPaths(paths: List<String>, out: File) = runBusy("Compressing…") {
        FileOps.zip(paths.map { File(it) }, out)
        "Created ${out.name}"
    }

    fun extractZip(zip: File, dest: File) = runBusy("Extracting…") {
        val n = FileOps.unzip(zip, dest)
        "Extracted $n file(s) to ${dest.name}"
    }

    fun gunzip(src: File, out: File) = runBusy("Decompressing…") {
        FileOps.gunzip(src, out)
        "Created ${out.name}"
    }

    fun rename(file: File, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) {
            toast("Invalid name")
            return
        }
        val dest = File(file.parentFile, n)
        if (dest.exists()) {
            toast("That name already exists")
            return
        }
        if (file.renameTo(dest)) {
            tabs.removeAll { it.path == file.absolutePath }
            activeTab = activeTab.coerceIn(0, maxOf(0, tabs.lastIndex))
            refreshTick++
            toast("Renamed")
        } else {
            toast("Rename failed")
        }
    }

    fun openZipEntry(zip: File, entryName: String) {
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    val base = File(ctx.cacheDir, "zip/" + zip.name)
                    FileOps.openZip(zip).use { zf ->
                        val e = zf.getEntry(entryName) ?: return@use null
                        val dest = File(base, entryName)
                        if (!dest.canonicalPath.startsWith(base.canonicalPath)) return@use null
                        dest.parentFile?.mkdirs()
                        zf.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                        dest
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (out != null) openFile(out) else toast("Couldn't open that entry")
        }
    }

    /** Files opened from other apps (content:// or file://). */
    fun openUri(uri: Uri) {
        viewModelScope.launch {
            val file: File? = withContext(Dispatchers.IO) {
                try {
                    if (uri.scheme == "file") {
                        uri.path?.let { File(it) }
                    } else {
                        var name = "file"
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (i >= 0) name = c.getString(i) ?: name
                            }
                        }
                        name = name.replace('/', '_')
                        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Opened")
                        dir.mkdirs()
                        val dest = FileOps.uniqueName(dir, name)
                        ctx.contentResolver.openInputStream(uri)?.use { i ->
                            dest.outputStream().use { o -> i.copyTo(o) }
                        }
                        dest
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (file != null && file.exists()) openFile(file) else toast("Couldn't open that file")
        }
    }
}
