package com.lightstick.music.ui.components.music

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lightstick.music.core.util.TimeFormatter
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 음악 재생 Seek Bar
 *
 * 현재/전체 재생 시간 텍스트와 [CommonProgressBar]를 함께 제공하며,
 * 탭/드래그 제스처로 재생 위치를 변경할 수 있는 16dp 터치 영역을 포함.
 * 드래그 중에는 프로그레스 바와 현재 시간 텍스트가 실시간으로 갱신되고,
 * 포인터 업 시점에 [onSeekTo]가 호출되어 실제 재생 위치가 확정된다.
 *
 * @param currentPosition 현재 재생 위치 (ms)
 * @param duration        전체 재생 시간 (ms)
 * @param onSeekTo        탭/드래그 완료 위치에 해당하는 재생 위치(ms)를 전달하는 콜백
 */
@Composable
fun MusicSeekBar(
    currentPosition: Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val baseProgress = if (duration > 0)
        (currentPosition / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val displayProgress = dragProgress ?: baseProgress
    val displayMs = if (dragProgress != null) (duration * dragProgress!!).toLong() else currentPosition

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.customColors.gradientStart,
            MaterialTheme.customColors.gradientEnd
        )
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // 현재 재생 시간 / 전체 시간
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = TimeFormatter.formatTime(displayMs),
                style = MaterialTheme.customTextStyles.badgeMedium,
                color = MaterialTheme.customColors.textTertiary
            )
            Text(
                text  = TimeFormatter.formatTime(duration),
                style = MaterialTheme.customTextStyles.badgeMedium,
                color = MaterialTheme.customColors.textTertiary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 16dp 터치 영역 (시각적 바는 4dp로 중앙 정렬)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Main)
                        val startPercent = (down.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = startPercent

                        drag(down.id) { change ->
                            change.consume()
                            dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        }

                        // 포인터 업 — seek 확정
                        if (duration > 0) {
                            onSeekTo((duration * (dragProgress ?: startPercent)).toLong())
                        }
                        dragProgress = null
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            CommonProgressBar(
                progress      = displayProgress,
                progressBrush = gradientBrush,
                modifier      = Modifier.fillMaxWidth()
            )
        }
    }
}
