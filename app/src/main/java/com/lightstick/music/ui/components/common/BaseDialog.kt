package com.lightstick.music.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightstick.music.ui.theme.customColors

@Composable
fun BaseDialog(
    title: String? = null,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "확인",
    dismissText: String = "취소",
    scrollable: Boolean = true,
    confirmEnabled: Boolean = true,
    dismissible: Boolean = true,
    // ✅ [수정] content를 nullable로 변경하여 조건부 렌더링을 구현합니다.
    content: (@Composable ColumnScope.() -> Unit)? = null
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
                .padding(horizontal = 20.dp)
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        title?.let {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.customColors.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }

                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.customColors.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // ✅ [수정] content가 null이 아닐 때만 콘텐츠 영역을 렌더링합니다.
                    if (content != null) {
                        Column(
                            modifier = if (scrollable) {
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        ) {
                            content()
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BaseButton(
                            text = dismissText,
                            onClick = onDismiss,
                            style = ButtonStyle.SURFACE,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )

                        BaseButton(
                            text = confirmText,
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            style = ButtonStyle.PRIMARY,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BaseDialog(
    title: String? = null,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmText: String = "확인",
    scrollable: Boolean = true,
    dismissible: Boolean = true,
    // ✅ [수정] content를 nullable로 변경하여 조건부 렌더링을 구현합니다.
    content: (@Composable ColumnScope.() -> Unit)? = null
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        title?.let {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.customColors.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }

                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.customColors.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // ✅ [수정] content가 null이 아닐 때만 콘텐츠 영역을 렌더링합니다.
                    if (content != null) {
                        Column(
                            modifier = if (scrollable) {
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        ) {
                            content()
                        }
                    }

                    BaseButton(
                        text = confirmText,
                        onClick = onDismiss,
                        style = ButtonStyle.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )
                }
            }
        }
    }
}