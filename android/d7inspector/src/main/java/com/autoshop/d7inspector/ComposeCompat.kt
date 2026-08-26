package com.autoshop.d7inspector

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView

internal fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    setContentView(ComposeView(this).apply { setContent(content) })
}
