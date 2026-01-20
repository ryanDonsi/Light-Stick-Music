package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.CustomSlider
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * ✅ 이펙트 슬라이더 아이템
 *
 * ## 수정사항
 * - EffectSettingsDialog.kt 내부 private 함수에서 별도 컴포넌트로 분리
 * - labels 파라미터 추가 (하단 타임라인 라벨)
 * - theme color, font 적용 (MaterialTheme.customColors, typography 사용)
 *
 * ## 사용 예시
 * ```kotlin
 * EffectSliderItem(
 *     icon = R.drawable.ic_period,
 *     iconTint = Color(0xFFFFC107),
 *     label = "PERIOD",
 *     value = 100,
 *     onValueChange = { },
 *     labels = listOf("0s", "5s", "10s")
 * )
 * ```
 */
@Composable
fun EffectSliderItem(
    icon: Int,
    iconTint: Color,
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange = 0..100,
    steps: Int = 99,
    labels: List<String> = emptyList(), // ✅ 추가: 하단 라벨
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ========== Header: 아이콘 + 레이블 ==========
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

        // ========== CustomSlider (common 패키지 사용) ==========
        CustomSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enableHaptic = true,
            trackColor = Color(0xFFD9D9D9),
            thumbColor = MaterialTheme.customColors.onSurface, // ✅ theme color 사용
            trackHeight = 8.dp,
            thumbSize = 24.dp
        )

        // ========== 하단 타임라인 라벨 (추가 부분) ==========
        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 5.dp, end = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                labels.forEach { labelText ->
                    Text(
                        text = labelText,
                        style = MaterialTheme.customTextStyles.badgeSmall,
                        color = MaterialTheme.customColors.onSurfaceVariant
                    )
                }
            }
        }
    }
}