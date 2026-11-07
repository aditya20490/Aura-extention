package com.universalfiles.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class BoolPref(private val p: SharedPreferences, private val key: String, default: Boolean) {
    var value by mutableStateOf(p.getBoolean(key, default))
        private set

    fun set(v: Boolean) {
        value = v
        p.edit().putBoolean(key, v).apply()
    }
}

class IntPref(private val p: SharedPreferences, private val key: String, default: Int) {
    var value by mutableStateOf(p.getInt(key, default))
        private set

    fun set(v: Int) {
        value = v
        p.edit().putInt(key, v).apply()
    }
}

class StrPref(private val p: SharedPreferences, private val key: String, default: String) {
    var value by mutableStateOf(p.getString(key, default) ?: default)
        private set

    fun set(v: String) {
        value = v
        p.edit().putString(key, v).apply()
    }
}

class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val theme = StrPref(prefs, "theme", "system")
    val dynamicColor = BoolPref(prefs, "dynamic", true)
    val fontSize = IntPref(prefs, "font_size", 14)
    val tabSize = IntPref(prefs, "tab_size", 2)
    val wordWrap = BoolPref(prefs, "wrap", false)
    val lineNumbers = BoolPref(prefs, "line_numbers", true)
    val highlight = BoolPref(prefs, "highlight", true)
    val autoIndent = BoolPref(prefs, "auto_indent", true)
    val autoClose = BoolPref(prefs, "auto_close", true)
    val autoSave = BoolPref(prefs, "auto_save", true)
    val showHidden = BoolPref(prefs, "hidden", false)
    val sortBy = StrPref(prefs, "sort", "name")
    val confirmDelete = BoolPref(prefs, "confirm_delete", true)
}
