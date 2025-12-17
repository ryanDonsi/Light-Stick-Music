package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.types.Colors
import com.lightstick.types.Color as SdkColor

/**
 * 프리셋 색상 그리드
 *
 * @param onColorSelected 색상 선택 시 콜백
 */
@Composable
fun PresetColorGrid(
    onColorSelected: (SdkColor) -> Unit,
    modifier: Modifier = Modifier
) {
    val presetColors = listOf(
        Colors.RED,
        Colors.GREEN,
        Colors.BLUE,
        Colors.YELLOW,
        Colors.MAGENTA,
        Colors.CYAN,
        Colors.ORANGE,
        Colors.PURPLE,
        Colors.PINK,
        Colors.WHITE,
        Colors.BLACK
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 첫 번째 줄: 6개
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.take(6).forEach { sdkColor ->
                PresetColorButton(
                    color = sdkColor,
                    onClick = { onColorSelected(sdkColor) }
                )
            }
        }

        // 두 번째 줄: 5개
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.drop(6).forEach { sdkColor ->
                PresetColorButton(
                    color = sdkColor,
                    onClick = { onColorSelected(sdkColor) }
                )
            }
        }
    }
}

@Composable
private fun PresetColorButton(
    color: SdkColor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(color.r, color.g, color.b))
            .border(1.dp, Color.Gray, CircleShape)
            .clickable(onClick = onClick)
    )
}