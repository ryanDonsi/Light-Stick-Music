package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 권한 항목 컴포넌트
 * - 아이콘 + 타이틀을 같은 Row에 배치
 * - 설명은 아이콘 너비(24dp) + 간격(8dp) = 32dp 만큼 왼쪽 패딩
 * - Figma 디자인 기준
 */
@Composable
fun PermissionItem(
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                tint = MaterialTheme.customColors.onSurface,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = title,
                style = MaterialTheme.customTextStyles.bodyAccent,
                color = MaterialTheme.customColors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.5,
            modifier = Modifier.padding(start = 32.dp, end = 16.dp)
        )
    }
}
