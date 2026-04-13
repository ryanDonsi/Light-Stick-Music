package com.lightstick.music.ui.components.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.theme.customColors

/**
 * 음악 재생 Seek Bar
 *
 * [CommonProgressBar]를 그라디언트 브러시로 감싸고,
 * 탭/드래그 제스처로 재생 위치를 변경할 수 있는 16dp 터치 영역을 제공.
 *
 * @param currentPosition 현재 재생 위치 (ms)
 * @param duration        전체 재생 시간 (ms)
 * @param onSeekTo        탭/드래그 완료 위치에 해당하는 재생 위치(ms)를 전달하는 콜백
 * @param onDragPreview   드래그 중 미리보기 위치(ms)를 전달하는 콜백; 드래그 종료 시 null 전달
 */
@Composable
fun MusicSeekBar(
    currentPosition: Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onDragPreview: ((Long?) -> Unit)? = null
) {
    val baseProgress = if (duration > 0)
        (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayProgress = dragProgress ?: baseProgress

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.customColors.gradientStart,
            MaterialTheme.customColors.gradientEnd
        )
    )

    // 16dp 터치 영역 (시각적 바는 4dp로 중앙 정렬)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Main)
                    val startPercent = (down.position.x / size.width).coerceIn(0f, 1f)
                    dragProgress = startPercent
                    onDragPreview?.invoke((duration * startPercent).toLong())

                    drag(down.id) { change ->
                        change.consume()
                        val percent = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = percent
                        onDragPreview?.invoke((duration * percent).toLong())
                    }

                    // 포인터 업 — seek 확정
                    val finalPercent = dragProgress ?: startPercent
                    if (duration > 0) {
                        onSeekTo((duration * finalPercent).toLong())
                    }
                    dragProgress = null
                    onDragPreview?.invoke(null)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        CommonProgressBar(
            progress = displayProgress,
            progressBrush = gradientBrush,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
