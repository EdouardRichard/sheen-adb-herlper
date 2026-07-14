package com.sheen.adb.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

object SheenDimensions {
    val screenPadding = 20.dp
    val itemSpacing = 12.dp
    val minimumTouchTarget = 48.dp
    val expandedPaneWidth = 280.dp
}

@Composable
fun SheenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
