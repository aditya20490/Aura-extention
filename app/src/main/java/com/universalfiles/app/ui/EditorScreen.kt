package com.universalfiles.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.universalfiles.app.AppViewModel

@Composable
fun EditorScreen(vm: AppViewModel) {
    ViewerScaffold(title = "Editor", snackbar = vm.snackbar, onBack = { vm.closeOverlay() }) {
        ComingSoon("The code editor")
    }
}
