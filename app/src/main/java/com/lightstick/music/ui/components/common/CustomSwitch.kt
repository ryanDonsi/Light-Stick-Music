package com.lightstick.music.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * Figma 디자인에 맞춘 커스텀 토글 스위치 (38x22dp)
 *
 * @param checked 토글 상태
 * @param onCheckedChange 토글 변경 콜백
 */
@Composable
fun CustomSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        modifier = modifier
            .width(38.dp)
            .height(22.dp),
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.customColors.onSurface,
            checkedTrackColor = MaterialTheme.customColors.primaryContainer,
            uncheckedThumbColor = MaterialTheme.customColors.onSurface,
            uncheckedTrackColor = MaterialTheme.customColors.onSurface.copy(0.14f)
        ),
        thumbContent = {
            Box(
                modifier = Modifier
                    .size(16.dp) // 트랙 높이(22dp)에 맞는 작은 크기
                    .background(
                        color = MaterialTheme.customColors.onSurface,
                        shape = CircleShape
                    )
            )
        }
    )
}
