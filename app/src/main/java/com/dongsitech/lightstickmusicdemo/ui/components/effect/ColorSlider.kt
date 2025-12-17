package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * RGB 색상 슬라이더
 *
 * @param label 라벨 (Red/Green/Blue)
 * @param value 현재 값 (0-255)
 * @param onValueChange 값 변경 시 콜백
 * @param color 슬라이더 색상
 */
@Composable
fun ColorSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            )
        )
    }
}