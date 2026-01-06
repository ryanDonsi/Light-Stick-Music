package com.dongsitech.lightstickmusicdemo.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.ui.theme.customColors
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles
import com.dongsitech.lightstickmusicdemo.R

/**
 * Custom Top Bar (Figma 디자인 적용)
 *
 * @param title 타이틀 텍스트
 * @param showBackButton 뒤로가기 버튼 표시 여부
 * @param onBackClick 뒤로가기 클릭 리스너
 * @param actionText 우측 액션 텍스트 (선택적)
 * @param onActionClick 액션 텍스트 클릭 리스너
 * @param actionTextColor 액션 텍스트 색상 (기본값: Secondary from theme)
 * @param backgroundColor 배경 색상 (기본값: Surface from theme)
 */
@Composable
fun CustomTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = MaterialTheme.customColors.secondary,
    backgroundColor: Color = MaterialTheme.customColors.surface
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(backgroundColor)
            .height(60.dp)  // Figma 스펙: 60px
    ) {
        // 타이틀 (완전 중앙 정렬)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.customTextStyles.topBarLarge,  // SemiBold 17sp, 125%
                color = MaterialTheme.customColors.onSurface,
                textAlign = TextAlign.Center
            )
        }

        // 좌측 버튼 (뒤로가기)
        if (showBackButton) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
            ) {
                IconButton(
                    onClick = { onBackClick?.invoke() },
                    modifier = Modifier.size(36.dp)  // Figma 스펙: 36×36px
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "뒤로가기",
                        tint = MaterialTheme.customColors.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 우측 액션 텍스트
        if (actionText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                TextButton(
                    onClick = { onActionClick?.invoke() },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.customTextStyles.topBarSmall,  // SemiBold 14sp, 140%
                        color = actionTextColor
                    )
                }
            }
        }
    }
}

/**
 * 뒤로가기 버튼이 있는 TopBar
 */
@Composable
fun TopBarWithBack(
    title: String,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = MaterialTheme.customColors.secondary
) {
    CustomTopBar(
        title = title,
        showBackButton = true,
        onBackClick = onBackClick,
        actionText = actionText,
        onActionClick = onActionClick,
        actionTextColor = actionTextColor,
        modifier = modifier
    )
}

/**
 * 중앙 정렬 타이틀만 있는 TopBar
 */
@Composable
fun TopBarCentered(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = MaterialTheme.customColors.secondary
) {
    CustomTopBar(
        title = title,
        showBackButton = false,
        actionText = actionText,
        onActionClick = onActionClick,
        actionTextColor = actionTextColor,
        modifier = modifier
    )
}