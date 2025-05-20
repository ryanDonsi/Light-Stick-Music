package com.dongsitech.lightstickmusicdemo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    delayMillis: Int = 1000,
    speed: Float = 50f // px per second
) {
    var textWidth by remember { mutableStateOf(0) }
    var containerWidth by remember { mutableStateOf(0) }

    val infiniteTransition = rememberInfiniteTransition(label = "marquee")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -((textWidth - containerWidth).coerceAtLeast(0)).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ((textWidth - containerWidth) / speed).toInt().coerceAtLeast(1) * 1000,
                delayMillis = delayMillis,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Box(
        modifier = modifier
            .width(200.dp)
            .clipToBounds()
            .onGloballyPositioned { containerWidth = it.size.width }
    ) {
        Text(
            text = text,
            style = style,
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .onGloballyPositioned { textWidth = it.size.width }
        )
    }
}
