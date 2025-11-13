package com.dongsitech.lightstickmusicdemo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun CustomProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    foregroundColor: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // 배경
        drawRect(
            color = backgroundColor,
            size = size
        )

        // 전경
        drawRect(
            color = foregroundColor,
            size = androidx.compose.ui.geometry.Size(width = width * progress.coerceIn(0f, 1f), height = height)
        )
    }
}
