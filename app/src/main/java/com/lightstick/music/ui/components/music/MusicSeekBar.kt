package com.lightstick.music.ui.components.music

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.theme.customColors

/**
 * 음악 재생 Seek Bar
 *
 * [CommonProgressBar]를 그라디언트 브러시로 감싸고,
 * 탭 제스처로 재생 위치를 변경할 수 있는 16dp 탭 영역을 제공.
 *
 * @param currentPosition 현재 재생 위치 (ms)
 * @param duration        전체 재생 시간 (ms)
 * @param onSeekTo        탭 위치에 해당하는 재생 위치(ms)를 전달하는 콜백
 */
@Composable
fun MusicSeekBar(
    currentPosition: Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0)
        (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.customColors.gradientStart,
            MaterialTheme.customColors.gradientEnd
        )
    )

    // 16dp 탭 영역 (시각적 바는 4dp로 중앙 정렬)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) {
                        val percent = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeekTo((duration * percent).toLong())
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        CommonProgressBar(
            progress = progress,
            progressBrush = gradientBrush,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
