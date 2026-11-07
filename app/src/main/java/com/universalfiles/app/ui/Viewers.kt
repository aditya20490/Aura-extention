package com.universalfiles.app.ui

import androidx.compose.runtime.Composable
import com.universalfiles.app.AppViewModel
import java.io.File

@Composable
fun PdfScreen(vm: AppViewModel, path: String) = StubViewer(vm, path, "The PDF viewer")

@Composable
fun ImageScreen(vm: AppViewModel, path: String) = StubViewer(vm, path, "The image viewer")

@Composable
fun MediaScreen(vm: AppViewModel, path: String, video: Boolean) = StubViewer(vm, path, "The media player")

@Composable
fun ArchiveScreen(vm: AppViewModel, path: String) = StubViewer(vm, path, "The archive tools")

@Composable
fun InfoScreen(vm: AppViewModel, path: String) = StubViewer(vm, path, "The file info viewer")

@Composable
private fun StubViewer(vm: AppViewModel, path: String, what: String) {
    ViewerScaffold(
        title = File(path).name,
        snackbar = vm.snackbar,
        onBack = { vm.closeOverlay() }
    ) { ComingSoon(what) }
}
