package com.universalfiles.app.data

import java.io.File

class EditorTab(val path: String) {
    val file = File(path)
    val name: String get() = file.name
    var dirty = false
    var loaded = false
    suspend fun save(): Boolean = true
}
