package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors

/**
 * ✅ 이펙트 토글 아이템 (아이콘 + 레이블 + Switch)
 *
 * ## 수정사항
 * - EffectSettingsDialog.kt 내부 private 함수에서 별도 컴포넌트로 분리
 * - theme color, font 적용 (MaterialTheme.customColors 사용)
 *
 * ## 사용 예시
 * ```kotlin
 * EffectToggleItem(
 *     icon = R.drawable.ic_color,
 *     iconTint = Color(0xFF2196F3),
 *     label = "RANDOM COLOR",
 *     checked = true,
 *     onCheckedChange = { }
 * )
 * ```
 */
@Composable
fun EffectToggleItem(
    icon: Int,
    iconTint: Color,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ========== Left: 아이콘 + 레이블 ==========
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.customColors.onSurfaceVariant
            )
        }

        // ========== Right: Switch ==========
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.customColors.onSurface,
                checkedTrackColor = MaterialTheme.customColors.primaryContainer,
                uncheckedThumbColor = MaterialTheme.customColors.onSurface,
                uncheckedTrackColor = MaterialTheme.customColors.onSurface.copy(0.14f)
            )
        )
    }
}