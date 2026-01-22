package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 권한 항목 컴포넌트
 * - 아이콘, 권한 이름, 설명을 표시
 */
@Composable
fun PermissionItem(
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // 아이콘
        Icon(
            imageVector = ImageVector.vectorResource(id = iconRes),
            contentDescription = title,
            tint = MaterialTheme.customColors.onSurface,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // 텍스트 영역
        Column(modifier = Modifier.weight(1f)) {
            // 권한 이름
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.customColors.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 설명
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.customColors.textTertiary,
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.4
            )
        }
    }
}