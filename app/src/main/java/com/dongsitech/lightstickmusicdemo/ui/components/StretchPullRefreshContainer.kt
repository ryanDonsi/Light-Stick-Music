package com.dongsitech.lightstickmusicdemo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/**
 * Stretchy pull-to-refresh container.
 * - 상단에 “풍선형 면”을 채워서 그리며,
 * - 콘텐츠도 드래그 양에 비례해 아래로 **함께 이동**시킵니다.
 */
@Composable
fun StretchPullRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    canScrollUp: () -> Boolean,
    modifier: Modifier = Modifier,

    // Appearance
    fillColor: Color,
    edgeColor: Color = Color.Black.copy(alpha = 0.06f),
    edgeWidth: Dp = 0.5.dp,

    // Feel
    maxVisual: Dp = 50.dp,
    trigger: Dp = 40.dp,
    springBackDurationMs: Int = 260,

    // 콘텐츠 이동량 비율 (1.0f면 표면과 동일 이동)
    contentOffsetFactor: Float = 0.85f,

    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val maxVisualPx = with(density) { maxVisual.toPx() }
    val triggerPx   = with(density) { trigger.toPx() }
    val edgeWidthPx = with(density) { edgeWidth.toPx() }

    // 풀 거리(픽셀). 단일 소스.
    val pull = remember { Animatable(0f) }

    // 새로고침 중에는 표면/오프셋 숨김
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) pull.snapTo(0f)
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && !canScrollUp()) {
                    val consumedY = available.y * 0.5f
                    scope.launch {
                        val newValue = (pull.value + consumedY).coerceAtMost(maxVisualPx)
                        pull.snapTo(newValue)
                    }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && !canScrollUp()) {
                    val consumedY = available.y * 0.5f
                    scope.launch {
                        val newValue = (pull.value + consumedY).coerceAtMost(maxVisualPx)
                        pull.snapTo(newValue)
                    }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value >= triggerPx && !isRefreshing) onRefresh()
                pull.animateTo(0f, animationSpec = tween(springBackDurationMs))
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(connection)
            .fillMaxSize()
    ) {
        // 1) 윗부분 “면” 먼저 그림 (배경처럼)
        val h = pull.value
        if (h > 0.5f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width

                val path = Path().apply {
                    moveTo(0f, 0f)
                    quadraticTo(0f, h * 0.55f, w * 0.25f, h * 0.85f)
                    quadraticTo(w * 0.5f, h * 1.05f, w * 0.75f, h * 0.85f)
                    quadraticTo(w, h * 0.55f, w, 0f)
                    close()
                }

                // 채움
                drawPath(path, color = fillColor)
                // 매우 얇은 테두리
                if (edgeWidthPx > 0f) {
                    drawPath(path, color = edgeColor, style = Stroke(width = edgeWidthPx))
                }
            }
        }

        // 2) 콘텐츠를 드래그 양에 비례하여 아래로 이동
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = h * contentOffsetFactor
            }
        ) {
            content()
        }
    }
}
