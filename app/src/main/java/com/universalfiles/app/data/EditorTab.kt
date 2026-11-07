package com.universalfiles.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EditorTab(val path: String) {
    val file = File(path)
    val name: String get() = file.name
    var value by mutableStateOf(TextFieldValue(""))
    var dirty by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    private var crlf = false
    private val undoStack = ArrayList<TextFieldValue>()
    private val redoStack = ArrayList<TextFieldValue>()
    private var lastEdit = 0L

    suspend fun load(force: Boolean = false) {
        if (loaded && !force) return
        val r = withContext(Dispatchers.IO) {
            try {
                var s = String(file.readBytes(), Charsets.UTF_8)
                if (s.startsWith("\uFEFF")) s = s.substring(1)
                val c = s.contains("\r\n")
                Triple(if (c) s.replace("\r\n", "\n") else s, c, null as String?)
            } catch (e: Exception) {
                Triple("", false, e.message ?: "Can't read file")
            }
        }
        crlf = r.second
        error = r.third
        value = TextFieldValue(r.first)
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
        dirty = false
        loaded = r.third == null
    }

    fun edit(new: TextFieldValue) {
        val old = value
        if (new.text != old.text) {
            val now = System.currentTimeMillis()
            if (undoStack.isEmpty() || now - lastEdit > 700) {
                undoStack.add(old)
                val max = if (old.text.length > 200_000) 15 else 100
                while (undoStack.size > max) undoStack.removeAt(0)
            }
            lastEdit = now
            redoStack.clear()
            dirty = true
            canUndo = true
            canRedo = false
        }
        value = new
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(value)
        value = undoStack.removeAt(undoStack.lastIndex)
        lastEdit = 0L
        dirty = true
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(value)
        value = redoStack.removeAt(redoStack.lastIndex)
        lastEdit = 0L
        dirty = true
        canUndo = true
        canRedo = redoStack.isNotEmpty()
    }

    fun select(start: Int, end: Int) {
        value = TextFieldValue(value.text, TextRange(start, end))
    }

    suspend fun save(): Boolean {
        if (!loaded) return false
        val text = value.text
        val out = if (crlf) text.replace("\n", "\r\n") else text
        val ok = withContext(Dispatchers.IO) {
            try {
                FileOps.writeAtomic(file, out.toByteArray(Charsets.UTF_8))
                true
            } catch (e: Exception) {
                false
            }
        }
        if (ok && value.text == text) dirty = false
        return ok
    }
}
