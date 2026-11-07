package com.universalfiles.app.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universalfiles.app.AppViewModel

@Composable
private fun Header(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) ({ Text(subtitle) }) else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = selected == key,
                    onClick = { onSelect(key) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onRequestAccess: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header("Appearance") }
            item {
                ChoiceRow(
                    "Theme",
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                    s.theme.value
                ) { s.theme.set(it) }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                item {
                    SwitchRow("Dynamic colors", "Use colors from your wallpaper", s.dynamicColor.value) {
                        s.dynamicColor.set(it)
                    }
                }
            }

            item { Header("Editor") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Font size: ${s.fontSize.value} sp")
                    Slider(
                        value = s.fontSize.value.toFloat(),
                        onValueChange = { s.fontSize.set(it.toInt()) },
                        valueRange = 10f..24f,
                        steps = 13
                    )
                }
            }
            item {
                ChoiceRow(
                    "Indent size",
                    listOf("2" to "2 spaces", "4" to "4 spaces", "8" to "8 spaces"),
                    s.tabSize.value.toString()
                ) { s.tabSize.set(it.toInt()) }
            }
            item { SwitchRow("Word wrap", "Hides line numbers while on", s.wordWrap.value) { s.wordWrap.set(it) } }
            item { SwitchRow("Line numbers", null, s.lineNumbers.value) { s.lineNumbers.set(it) } }
            item { SwitchRow("Syntax highlighting", "Disabled automatically for very large files", s.highlight.value) { s.highlight.set(it) } }
            item { SwitchRow("Auto-indent", "Keep indentation on new lines", s.autoIndent.value) { s.autoIndent.set(it) } }
            item { SwitchRow("Auto-close brackets and tags", null, s.autoClose.value) { s.autoClose.set(it) } }
            item { SwitchRow("Auto-save", "Save changes when you leave the app", s.autoSave.value) { s.autoSave.set(it) } }

            item { Header("Files") }
            item { SwitchRow("Show hidden files", null, s.showHidden.value) { s.showHidden.set(it) } }
            item {
                SwitchRow("Confirm before deleting", "Deleting is permanent", s.confirmDelete.value) {
                    s.confirmDelete.set(it)
                }
            }
            item {
                ChoiceRow(
                    "Sort by",
                    listOf("name" to "Name", "date" to "Date", "size" to "Size", "type" to "Type"),
                    s.sortBy.value
                ) { s.sortBy.set(it) }
            }

            item { Header("Storage access") }
            item {
                ListItem(
                    headlineContent = {
                        Text(if (vm.hasAccess) "All files access granted" else "Access not granted")
                    },
                    supportingContent = {
                        Text(
                            if (vm.hasAccess) "You can browse and edit files anywhere."
                            else "Needed to browse and edit files outside this app."
                        )
                    },
                    trailingContent = {
                        if (!vm.hasAccess) Button(onClick = onRequestAccess) { Text("Grant") }
                    }
                )
            }

            item { Header("About") }
            item {
                ListItem(
                    headlineContent = { Text("Universal Files") },
                    supportingContent = { Text("Version $version") }
                )
            }
        }
    }
}
