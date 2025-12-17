package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 이펙트 제어 버튼
 *
 * @param isPlaying 재생 중 여부
 * @param onPlayClick 재생 버튼 클릭 시
 * @param onStopClick 중지 버튼 클릭 시
 * @param enabled 활성화 여부
 */
@Composable
fun EffectControls(
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onPlayClick,
            enabled = enabled && !isPlaying,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "재생"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("재생")
        }

        Button(
            onClick = onStopClick,
            enabled = enabled && isPlaying,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "중지"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("중지")
        }
    }
}