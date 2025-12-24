package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * ✅ EffectTypeCard - Figma 디자인 완벽 구현
 *
 * 3가지 상태:
 * 1. 비활성화 (Device 미연결): 전체 회색
 * 2. 활성화 (Device 연결, 동작 전): 하얀 텍스트, 회색 버튼
 * 3. 실행 중: 보라색 테두리, 색상 활성화, 우측 슬라이더
 */
@Composable
fun EffectTypeCard(
    effect: EffectViewModel.UiEffectType,
    effectSettings: EffectViewModel.EffectSettings,
    isSelected: Boolean,
    isPlaying: Boolean,
    isEnabled: Boolean = true,
    onEffectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onForegroundColorClick: () -> Unit,
    onBackgroundColorClick: () -> Unit
) {
    // ✅ 상태별 색상
    val cardBackgroundColor = when {
        !isEnabled -> Color(0xFF2A2A2A)  // 비활성화: 어두운 회색
        isPlaying -> Color(0xFF2A2A2A)   // 실행 중: 어두운 배경
        else -> Color(0xFF2A2A2A)        // 활성화: 어두운 배경
    }

    val borderColor = when {
        isPlaying -> Color(0xFF9C27B0)   // 실행 중: 보라색 테두리
        else -> Color.Transparent         // 기본: 테두리 없음
    }

    val titleColor = when {
        !isEnabled -> Color(0xFF666666)  // 비활성화: 회색
        else -> Color.White               // 활성화/실행 중: 하얀색
    }

    Card(
        onClick = onEffectClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBackgroundColor
        ),
        border = BorderStroke(
            width = if (isPlaying) 2.dp else 0.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ 좌측: 타이틀 + 파라미터들
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 타이틀
                Text(
                    text = effect.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )

                // 파라미터 아이콘들
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ON, OFF
                    // Transit (ic_transit)
                    ParameterItem(
                        iconRes = R.drawable.ic_transit,
                        value = "${effectSettings.transit}s",
                        iconTint = if (isPlaying) MaterialTheme.customColors.primary else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666),
                        textColor = if (isPlaying) Color.White else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666)
                    )

                    // STROBE, BLINK, BREATH
                    // Period (ic_period)
                    ParameterItem(
                        iconRes = R.drawable.ic_period,
                        value = "${effectSettings.period}s",
                        iconTint = if (isPlaying) Color(0xFFFFD46F) else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666),
                        textColor = if (isPlaying) Color.White else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666)
                    )

                    // Random Delay (ic_random)
                    ParameterItem(
                        iconRes = R.drawable.ic_random,
                        value = "${effectSettings.randomDelay}s",
                        iconTint = if (isPlaying) Color(0xFF84E366) else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666),
                        textColor = if (isPlaying) Color.White else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666)
                    )

                    // Random Color (ic_color)
                    val randomColor = if (effectSettings.randomColor) "R" else "-"
                    ParameterItem(
                        iconRes = R.drawable.ic_color,
                        value = randomColor,
                        iconTint = if (isPlaying) Color(0xFF22D3EE) else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666),
                        textColor = if (isPlaying) Color.White else if (isEnabled) MaterialTheme.customColors.surfaceVariant else Color(0xFF666666)
                    )
                }
            }

            // ✅ 우측: FG, BG 버튼 + 메뉴 + 슬라이더
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // FG 버튼
                ColorChip(
                    label = "FG",
                    color = if (isPlaying) {
                        Color(
                            effectSettings.color.r / 255f,
                            effectSettings.color.g / 255f,
                            effectSettings.color.b / 255f
                        )
                    } else {
                        Color(0xFF4A4A4A)  // 회색
                    },
                    onClick = onForegroundColorClick,
                    enabled = isEnabled
                )

                // BG 버튼
                ColorChip(
                    label = "BG",
                    color = if (isPlaying) {
                        Color(
                            effectSettings.backgroundColor.r / 255f,
                            effectSettings.backgroundColor.g / 255f,
                            effectSettings.backgroundColor.b / 255f
                        )
                    } else {
                        Color(0xFF4A4A4A)  // 회색
                    },
                    onClick = onBackgroundColorClick,
                    enabled = isEnabled
                )

                // 더보기 메뉴
                IconButton(
                    onClick = onSettingsClick,
                    enabled = isEnabled,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "설정",
                        tint = if (isEnabled) Color.White else Color(0xFF666666),
                        modifier = Modifier.size(20.dp)
                    )
                }

//                // ✅ 실행 중일 때만 슬라이더 표시
//                if (isPlaying) {
//                    Box(
//                        modifier = Modifier
//                            .width(4.dp)
//                            .height(24.dp)
//                            .clip(RoundedCornerShape(2.dp))
//                            .background(Color.White)
//                    )
//                }
            }
        }
    }
}

/**
 * ✅ 파라미터 아이콘 + 값
 */
@Composable
fun ParameterItem(
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
            fontSize = 12.sp,
            color = textColor
        )
    }
}

/**
 * ✅ FG/BG 색상 칩
 */
@Composable
fun ColorChip(
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = color,
        modifier = Modifier.size(width = 36.dp, height = 24.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (label == "FG" && color.luminance() > 0.5f) {
                    Color.Black
                } else {
                    Color.White
                }
            )
        }
    }
}

/**
 * ✅ Color 밝기 계산 (Luminance)
 */
fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}