package com.lightstick.music.ui.components.init

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 진행률 섹션 컴포넌트
 */
@Composable
fun ProgressSection(
    title: String,
    current: Int? = null,
    total: Int? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )

        if (current != null && total != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$current / $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val calculatedProgress = progress ?: run {
            if (current != null && total != null && total > 0) {
                current.toFloat() / total
            } else {
                0f
            }
        }

        LinearProgressIndicator(
            progress = { calculatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )
    }
}