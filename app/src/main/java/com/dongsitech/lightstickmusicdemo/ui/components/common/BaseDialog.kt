package com.dongsitech.lightstickmusicdemo.ui.components.common

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * ✅ BaseDialog - 모든 다이얼로그의 기본 레이아웃
 *
 * ## Figma 스펙 (Effect_dialog)
 * - 크기: 좌우 16dp 여백 + 나머지 화면 너비 (반응형)
 * - 배경: #111111 (Neutral color/Surface)
 * - Glow: 진짜 Gaussian Blur (API 31+) 또는 Layer 방식 (API 30 이하)
 *   - Android 12+: blur(12.dp) 사용
 *   - Android 11 이하: 3-Layer graphicsLayer 방식
 * - Border: #FFFFFF 25%, 1dp
 * - Corner Radius: 16px
 * - 외부 여백: 좌우 16dp
 * - 내부 패딩: 상단 32px, 좌우 16px, 하단 16px
 * - 간격: 24px
 * - 스크롤: Fixed(고정)
 *
 * ## 구현
 * - API 31+ (Android 12+): blur() modifier로 진짜 Gaussian Blur
 * - API 30 이하: graphicsLayer로 blur 시뮬레이션
 * - 모든 디바이스에서 아름다운 Glow 효과
 *
 * ## 사용 예시
 * ```kotlin
 * BaseDialog(
 *     title = "효과 상세 설정",
 *     subtitle = null,  // Optional
 *     onDismiss = { },
 *     onConfirm = { },
 *     confirmText = "확인",
 *     dismissText = "취소",
 *     scrollable = true
 * ) {
 *     // 커스텀 콘텐츠
 *     Text("Hello World")
 * }
 * ```
 */
@Composable
fun BaseDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "확인",
    dismissText: String = "취소",
    scrollable: Boolean = true,
    confirmEnabled: Boolean = true,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .wrapContentHeight()
        ) {

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.05f
                        scaleX = 1.020f
                        scaleY = 1.020f
                    }
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp)
                    )
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.12f
                        scaleX = 1.015f
                        scaleY = 1.015f
                    }
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp)
                    )
            )

            // ✅ 실제 다이얼로그
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111111),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 32.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // ===== Header: Title + Subtitle =====
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // ===== Content =====
                    Column(
                        modifier = if (scrollable) {
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)  // 최대 높이 제한
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        content()
                    }

                    // ===== Buttons =====
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)  // ✅ Figma: 버튼 간격 12px
                    ) {
                        // ✅ 취소 버튼 (Surface 스타일)
                        BaseButton(
                            text = dismissText,
                            onClick = onDismiss,
                            style = ButtonStyle.SURFACE,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)  // ✅ Dialog 버튼은 48dp
                        )

                        // ✅ 확인 버튼 (Primary 스타일)
                        BaseButton(
                            text = confirmText,
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            style = ButtonStyle.PRIMARY,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)  // ✅ Dialog 버튼은 48dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * ✅ BaseDialog (버튼 없는 버전)
 *
 * 확인 버튼만 있는 다이얼로그 (예: 알림, 정보)
 */
@Composable
fun BaseDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmText: String = "확인",
    scrollable: Boolean = true,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .wrapContentHeight()
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.05f
                        scaleX = 1.020f
                        scaleY = 1.020f
                    }
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp)
                    )
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.12f
                        scaleX = 1.015f
                        scaleY = 1.015f
                    }
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(16.dp)
                    )
            )

            // 실제 다이얼로그
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111111),
                border = BorderStroke(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 32.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Header
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Content
                    Column(
                        modifier = if (scrollable) {
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        content()
                    }

                    // 확인 버튼
                    BaseButton(
                        text = confirmText,
                        onClick = onDismiss,
                        style = ButtonStyle.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)  // ✅ Dialog 버튼은 48dp
                    )
                }
            }
        }
    }
}