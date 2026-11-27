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
 * 탭/드래그 제스처로 재생 위치를 변경할 수 있는 32dp 터치 영역을 포함.
 * 드래그 중에는 프로그레스 바와 현재 시간 텍스트가 실시간으로 갱신되고,
 * 포인터 업 시점에 [onSeekTo]가 호출되어 실제 재생 위치가 확정된다.
 *
 * @param currentPosition 현재 재생 위치 (ms)
 * @param duration        전체 재생 시간 (ms)
 * @param onSeekTo        탭/드래그 완료 위치에 해당하는 재생 위치(ms)를 전달하는 콜백
 */
/**
 * 재생 위치를 람다로 받는 형태. 위치는 이 안에서만 읽어, 위치가 바뀌어도 시크바만 다시 그린다.
 */
@Composable
fun MusicSeekBar(
    currentPosition: () -> Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    MusicSeekBar(
        currentPosition = currentPosition(),
        duration        = duration,
        onSeekTo        = onSeekTo,
        modifier        = modifier
    )
}

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
    // 제스처 블록은 한 번만 만들고 최신 값은 여기서 읽는다.
    // 예전에는 pointerInput(duration) 이라 길이가 바뀌면(곡 준비 완료 등) 드래그 중 제스처가 재시작됐다.
    val latestDuration by rememberUpdatedState(duration)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val displayProgress = dragProgress ?: baseProgress
    val displayMs = if (dragProgress != null) (duration * dragProgress!!).toLong() else currentPosition

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.customColors.gradientStart,
            MaterialTheme.customColors.gradientEnd
        )
    )

    Column(modifier = modifier.fillMaxWidth()) {
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

        // 터치 영역 32dp(예전 16dp). 위 여백 8dp 를 터치 영역 안으로 흡수해 화면 높이 증가는 8dp 로 줄였다.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Main)
                        val startPercent = (down.position.x / size.width).coerceIn(0f, 1f)
                        dragProgress = startPercent

                        drag(down.id) { change ->
                            change.consume()
                            dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        }

                        val d = latestDuration
                        if (d > 0) {
                            latestOnSeekTo((d * (dragProgress ?: startPercent)).toLong())
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
