package com.lightstick.music.ui.components.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * Figma 디자인에 맞춘 커스텀 칩 (버튼)
 * - 높이: 30dp
 * - 패딩: 가로 10dp, 세로 6dp
 * - 모서리: 8dp
 *
 * @param text 칩에 표시될 텍스트
 * @param onClick 클릭 이벤트
 * @param modifier Modifier
 * @param enabled 활성화 여부
 * @param containerColor 배경 색상
 * @param contentColor 콘텐츠(텍스트) 색상
 */
@Composable
fun CustomChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.customColors.primaryContainer,
    contentColor: Color = MaterialTheme.customColors.onSurface
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .width(62.dp)
            .height(36.dp), // Figma: 30px height
        enabled = enabled,
        shape = RoundedCornerShape(8.dp), // Figma: 8px corner radius
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            // Figma 디자인에 명시된 비활성화 스타일이 없으므로 기본 비활성화 스타일을 따름
            disabledContainerColor = containerColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp) // Figma: padding
    ) {
        Text(
            text = text,
            style = MaterialTheme.customTextStyles.badgeMedium
        )
    }
}
