package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import java.util.Locale

/**
 * ✅ EffectTypeCard - Figma 디자인 + Theme 기반 완벽 구현
 *
 * ## 레이아웃
 * - 크기: fillMaxWidth × 84dp
 * - 여백: 16dp (상하좌우)
 * - 중앙 정렬
 *
 * ## 3가지 상태
 * 1. 비활성화 (Device 미연결): 전체 회색
 * 2. 활성화 (Device 연결, 대기 중): OnSurface 5%, 테두리 OnSurface 14% 1dp
 * 3. 선택/실행 중: Primary 22%, 테두리 Primary 2dp
 *
 * ## 아이콘 색상
 * - Transit: #A774FF (보라색)
 * - Period: #FFD46F (노란색)
 * - Random Delay: #84E366 (초록색)
 * - Random Color: #22D3EE (하늘색)
 *
 * ## 그림자
 * - Elevation: 20dp
 * - Color: Black 20%
 * - Offset: Y=8dp
 */
@Composable
fun EffectTypeCard(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    onEffectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onForegroundColorClick: () -> Unit,
    onBackgroundColorClick: () -> Unit
) {
    // ✅ 상태별 색상 (Theme 기반)
    val cardBackgroundColor = when {
        isSelected -> MaterialTheme.customColors.primary.copy(alpha = 0.22f)  // 선택: Primary 22%
        else -> MaterialTheme.customColors.onSurface.copy(alpha = 0.05f)      // 기본: OnSurface 5%
    }

    val borderColor = when {
        isSelected -> MaterialTheme.customColors.primary                        // 선택: Primary
        else -> MaterialTheme.customColors.onSurface.copy(alpha = 0.14f)       // 기본: OnSurface 14%
    }

    val borderWidth = when {
        isSelected -> 2.dp  // 선택: 2dp
        else -> 1.dp        // 기본: 1dp
    }

    val titleColor = when {
        !isEnabled -> MaterialTheme.customColors.disable    // 비활성화: Disable
        else -> MaterialTheme.customColors.onSurface        // 활성화: OnSurface
    }

    // ✅ Figma: fillMaxWidth × 84dp
    Card(
        onClick = { if (isEnabled) onEffectClick() },
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Transparent
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBackgroundColor
        ),
        border = BorderStroke(
            width = borderWidth,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),  // ✅ 상하좌우 16dp
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ 좌측: 타이틀 + 파라미터들
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ✅ 타이틀 (Theme Typography: titleLarge = 20sp, SemiBold)
                Text(
                    text = effect.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = titleColor
                )

                // ✅ 파라미터 아이콘들 (Effect 타입별 표시)
                EffectParameters(
                    effect = effect,
                    effectSettings = effectSettings,
                    isSelected = isSelected,
                    isEnabled = isEnabled
                )
            }

            // ✅ 우측: FG, BG 버튼 + 메뉴
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ✅ FG 버튼 (ON, STROBE, BLINK, BREATH만 표시)
                when (effect) {
                    is EffectViewModel.UiEffectType.On,
                    is EffectViewModel.UiEffectType.Strobe,
                    is EffectViewModel.UiEffectType.Blink,
                    is EffectViewModel.UiEffectType.Breath -> {
                        ColorChip(
                            label = "FG",
                            color = if (isSelected) {
                                Color(
                                    effectSettings.color.r / 255f,
                                    effectSettings.color.g / 255f,
                                    effectSettings.color.b / 255f
                                )
                            } else {
                                MaterialTheme.customColors.disable  // #4A4A4A
                            },
                            onClick = onForegroundColorClick,
                            enabled = isEnabled,
                            isSelected = isSelected
                        )
                    }
                    else -> { /* OFF, EFFECT LIST는 FG 없음 */ }
                }

                // ✅ BG 버튼 (STROBE, BLINK, BREATH만 표시)
                when (effect) {
                    is EffectViewModel.UiEffectType.Strobe,
                    is EffectViewModel.UiEffectType.Blink,
                    is EffectViewModel.UiEffectType.Breath -> {
                        ColorChip(
                            label = "BG",
                            color = if (isSelected) {
                                Color(
                                    effectSettings.backgroundColor.r / 255f,
                                    effectSettings.backgroundColor.g / 255f,
                                    effectSettings.backgroundColor.b / 255f
                                )
                            } else {
                                MaterialTheme.customColors.disable  // #4A4A4A
                            },
                            onClick = onBackgroundColorClick,
                            enabled = isEnabled,
                            isSelected = isSelected
                        )
                    }
                    else -> { /* ON, OFF, EFFECT LIST는 BG 없음 */ }
                }

                // ✅ 더보기 메뉴
                IconButton(
                    onClick = onSettingsClick,
                    enabled = isEnabled,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "설정",
                        tint = when {
                            isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                            isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                            else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * ✅ Effect 타입별 파라미터 표시
 *
 * ## 표시 규칙
 * - ON: Transit, Random Delay, Random Color
 * - OFF: Transit만
 * - STROBE/BLINK/BREATH: Period, Random Delay, Random Color
 * - EFFECT LIST: 설정값 없음
 */
@Composable
private fun EffectParameters(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isEnabled: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (effect) {
            // ✅ ON: Transit, Random Delay, Random Color
            is EffectViewModel.UiEffectType.On -> {
                // Transit (보라색 #A774FF)
                ParameterItem(
                    iconRes = R.drawable.ic_transit,
                    value = formatSeconds(effectSettings.transit),
                    iconTint = when {
                        isSelected -> Color(0xFFA774FF)  // 선택: 보라색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )

                // Random Delay (초록색 #84E366)
                ParameterItem(
                    iconRes = R.drawable.ic_random,
                    value = formatSeconds(effectSettings.randomDelay),
                    iconTint = when {
                        isSelected -> Color(0xFF84E366)  // 선택: 초록색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )

                // Random Color (하늘색 #22D3EE) - 항상 표시
                ParameterItem(
                    iconRes = R.drawable.ic_color,
                    value = if (effectSettings.randomColor) "R" else "-",
                    iconTint = when {
                        isSelected -> Color(0xFF22D3EE)  // 선택: 하늘색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )
            }

            // ✅ OFF: Transit만
            is EffectViewModel.UiEffectType.Off -> {
                // Transit (보라색 #A774FF)
                ParameterItem(
                    iconRes = R.drawable.ic_transit,
                    value = formatSeconds(effectSettings.transit),
                    iconTint = when {
                        isSelected -> Color(0xFFA774FF)  // 선택: 보라색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )
            }

            // ✅ STROBE/BLINK/BREATH: Period, Random Delay, Random Color
            is EffectViewModel.UiEffectType.Strobe,
            is EffectViewModel.UiEffectType.Blink,
            is EffectViewModel.UiEffectType.Breath -> {
                // Period (노란색 #FFD46F)
                ParameterItem(
                    iconRes = R.drawable.ic_period,
                    value = formatSeconds(effectSettings.period),
                    iconTint = when {
                        isSelected -> Color(0xFFFFD46F)  // 선택: 노란색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )

                // Random Delay (초록색 #84E366)
                ParameterItem(
                    iconRes = R.drawable.ic_random,
                    value = formatSeconds(effectSettings.randomDelay),
                    iconTint = when {
                        isSelected -> Color(0xFF84E366)  // 선택: 초록색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )

                // Random Color (하늘색 #22D3EE) - 항상 표시
                ParameterItem(
                    iconRes = R.drawable.ic_color,
                    value = if (effectSettings.randomColor) "R" else "-",
                    iconTint = when {
                        isSelected -> Color(0xFF22D3EE)  // 선택: 하늘색
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    },
                    textColor = when {
                        isSelected -> MaterialTheme.customColors.onSurface  // 선택: OnSurface
                        isEnabled -> MaterialTheme.customColors.surfaceVariant  // 활성화: SurfaceVariant
                        else -> MaterialTheme.customColors.disable  // 비활성화: Disable
                    }
                )
            }

            // ✅ EFFECT LIST: 설정값 없음
            is EffectViewModel.UiEffectType.EffectList -> {
                // 빈 공간
            }

            else -> {
                // 미지원 타입
            }
        }
    }
}

/**
 * ✅ 파라미터 아이콘 + 값 (Theme Typography: bodySmall = 12sp)
 */
@Composable
private fun ParameterItem(
    iconRes: Int,
    value: String,
    iconTint: Color,
    textColor: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,  // 12sp, Regular
            color = textColor
        )
    }
}

/**
 * ✅ FG/BG 색상 칩 (36x36dp, round8)
 */
@Composable
private fun ColorChip(
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean,
    isSelected: Boolean
) {
    // ✅ 배경색 결정
    val backgroundColor = when {
        isSelected -> color                                  // 선택: 실제 색상
        enabled -> MaterialTheme.customColors.surfaceVariant // 활성화: SurfaceVariant
        else -> MaterialTheme.customColors.disable           // 비활성화: Disable
    }

    // ✅ 텍스트 색상 결정
    val textColor = when {
        isSelected -> {
            // 선택 시: 밝기 기반 흑백
            if (color.luminance() > 0.5f) Color.Black else Color.White
        }
        else -> MaterialTheme.customColors.surface  // 활성화/비활성화: Surface (#111111)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        modifier = Modifier.size(36.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.customTextStyles.badgeMedium,  // 12sp, SemiBold, 140%
                color = textColor
            )
        }
    }
}

/**
 * UI 전용 시간 포맷 함수
 */
private fun formatSeconds(value: Int): String {
    val seconds = value * 0.1
    return String.format(Locale.US, "%.2f", seconds)
        .trimEnd('0')
        .trimEnd('.') + "s"
}


/**
 * Color 밝기 계산 (Luminance)
 */
private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}