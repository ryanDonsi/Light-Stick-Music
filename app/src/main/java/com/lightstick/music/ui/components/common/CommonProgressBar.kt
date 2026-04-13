package com.lightstick.music.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * 공통 프로그레스 바 (순수 UI — 라벨·버튼 없음)
 *
 * 트랙(배경)과 채움(fill) 두 레이어로만 구성.
 * 레이아웃·텍스트·인터랙션은 각 기능 컴포넌트에서 담당:
 *  - Splash     → [com.lightstick.music.ui.components.splash.ProgressSection]
 *  - Music 재생  → [com.lightstick.music.ui.components.music.MusicSeekBar]
 *  - OTA 업데이트 → [com.lightstick.music.ui.components.device.OtaProgressSection]
 *
 * @param progress      채움 비율 0f..1f
 * @param modifier      외부 레이아웃 제어
 * @param trackColor    배경 트랙 색상 (기본: customColors.outline)
 * @param progressBrush 채움 브러시 — null 이면 [progressColor] 사용
 * @param progressColor 채움 단색 (progressBrush 가 null 일 때 사용, 기본: primary)
 * @param height        바 두께 (기본: 4.dp)
 * @param shape         모서리 모양 (기본: 완전 둥근 pill)
 */
@Composable
fun CommonProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.customColors.outline,
    progressBrush: Brush? = null,
    progressColor: Color = MaterialTheme.customColors.primary,
    height: Dp = 4.dp,
    shape: Shape = RoundedCornerShape(9999.dp)
) {
    val fillBrush = progressBrush ?: SolidColor(progressColor)

    Box(modifier = modifier.height(height)) {
        // 배경 트랙
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(trackColor)
        )
        // 채움 (fill)
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(brush = fillBrush)
        )
    }
}
