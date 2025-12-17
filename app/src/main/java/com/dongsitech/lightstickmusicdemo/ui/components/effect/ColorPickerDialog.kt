package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.types.Color as SdkColor

/**
 * 색상 선택 다이얼로그
 *
 * @param currentColor 현재 색상
 * @param onColorSelected 색상 선택 시 콜백
 * @param onDismiss 닫기 시 콜백
 */
@Composable
fun ColorPickerDialog(
    currentColor: SdkColor,
    onColorSelected: (SdkColor) -> Unit,
    onDismiss: () -> Unit
) {
    var r by remember { mutableIntStateOf(currentColor.r) }
    var g by remember { mutableIntStateOf(currentColor.g) }
    var b by remember { mutableIntStateOf(currentColor.b) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 미리보기
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color(r, g, b), MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                )

                // RGB 슬라이더
                ColorSlider(
                    label = "Red",
                    value = r,
                    onValueChange = { r = it },
                    color = Color.Red
                )

                ColorSlider(
                    label = "Green",
                    value = g,
                    onValueChange = { g = it },
                    color = Color.Green
                )

                ColorSlider(
                    label = "Blue",
                    value = b,
                    onValueChange = { b = it },
                    color = Color.Blue
                )

                // 프리셋 색상
                Text(
                    text = "프리셋",
                    style = MaterialTheme.typography.titleSmall
                )

                PresetColorGrid(
                    onColorSelected = { color ->
                        r = color.r
                        g = color.g
                        b = color.b
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(SdkColor(r, g, b)) }) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}