package com.dongsitech.lightstickmusicdemo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    delayMillis: Int = 1000,
    speed: Float = 50f // px/sec
) {
    var textWidth by remember { mutableStateOf(0) }
    var containerWidth by remember { mutableStateOf(0) }
    val offsetX = remember { Animatable(0f) }

    val distance = (textWidth - containerWidth).coerceAtLeast(1)
    val shouldScroll = textWidth > containerWidth

    LaunchedEffect(shouldScroll, text) {
        if (shouldScroll) {
            while (true) {
                offsetX.snapTo(0f)
                delay(delayMillis.toLong())
                offsetX.animateTo(
                    targetValue = -distance.toFloat(),
                    animationSpec = tween(
                        durationMillis = ((distance / speed) * 1000).toInt().coerceAtLeast(1000),
                        easing = LinearEasing
                    )
                )
                delay(1000)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth() // ★ 필수
            .clipToBounds()
            .onGloballyPositioned { containerWidth = it.size.width }
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .onGloballyPositioned { textWidth = it.size.width }
        )
    }
}



