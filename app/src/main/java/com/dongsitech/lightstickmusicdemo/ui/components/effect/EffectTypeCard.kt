package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * 이펙트 타입 카드
 */
@Composable
fun EffectTypeCard(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isPlaying: Boolean,
    onEffectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onForegroundColorClick: () -> Unit,
    onBackgroundColorClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEffectClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.medium
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 왼쪽: 이펙트 정보
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = effect.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = effect.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 오른쪽: 액션 버튼들
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Foreground Color 버튼 (OFF, EFFECT LIST 제외)
                if (effect !is EffectViewModel.UiEffectType.Off &&
                    effect !is EffectViewModel.UiEffectType.EffectList
                ) {
                    ColorPreviewButton(
                        color = effectSettings.color,
                        label = "FG",
                        onClick = onForegroundColorClick
                    )
                }

                // Background Color 버튼 (Strobe, Blink, Breath만)
                if (effect is EffectViewModel.UiEffectType.Strobe ||
                    effect is EffectViewModel.UiEffectType.Blink ||
                    effect is EffectViewModel.UiEffectType.Breath
                ) {
                    ColorPreviewButton(
                        color = effectSettings.backgroundColor,
                        label = "BG",
                        onClick = onBackgroundColorClick
                    )
                }

                // 설정 버튼 (EFFECT LIST 제외)
                if (effect !is EffectViewModel.UiEffectType.EffectList) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "설정",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 선택/재생 아이콘
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = if (isPlaying) "재생 중" else "선택됨",
                        tint = if (isPlaying)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 색상 미리보기 버튼
 */
@Composable
private fun ColorPreviewButton(
    color: com.lightstick.types.Color,
    label: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    androidx.compose.ui.graphics.Color(color.r, color.g, color.b),
                    RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (color.r + color.g + color.b > 384)
                    androidx.compose.ui.graphics.Color.Black
                else
                    androidx.compose.ui.graphics.Color.White
            )
        }
    }
}