package com.universalfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalfiles.app.AppViewModel
import com.universalfiles.app.MainTab
import com.universalfiles.app.Overlay

@Composable
fun AppRoot(vm: AppViewModel, onRequestAccess: () -> Unit) {
    val top = vm.overlays.lastOrNull()
    BackHandler(enabled = top != null) { vm.closeOverlay() }
    when (top) {
        null -> MainScaffold(vm, onRequestAccess)
        is Overlay.Editor -> EditorScreen(vm)
        is Overlay.Pdf -> PdfScreen(vm, top.path)
        is Overlay.Image -> ImageScreen(vm, top.path)
        is Overlay.Media -> MediaScreen(vm, top.path, top.video)
        is Overlay.Archive -> ArchiveScreen(vm, top.path)
        is Overlay.Info -> InfoScreen(vm, top.path)
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel, onRequestAccess: () -> Unit) {
    BackHandler(enabled = vm.mainTab != MainTab.HOME) { vm.mainTab = MainTab.HOME }

    val items = listOf(
        Triple(MainTab.HOME, "Home", Icons.Filled.Home),
        Triple(MainTab.FILES, "Files", Icons.Filled.Folder),
        Triple(MainTab.SETTINGS, "Settings", Icons.Filled.Settings)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(vm.snackbar) },
        bottomBar = {
            NavigationBar {
                items.forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = vm.mainTab == tab,
                        onClick = { vm.mainTab = tab },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.mainTab) {
                MainTab.HOME -> HomeScreen(vm, onRequestAccess)
                MainTab.FILES -> BrowserScreen(vm, onRequestAccess)
                MainTab.SETTINGS -> SettingsScreen(vm, onRequestAccess)
            }
            val busy = vm.busy
            if (busy != null) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        busy,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
