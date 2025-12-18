package com.dongsitech.lightstickmusicdemo.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.ui.theme.Secondary
import com.dongsitech.lightstickmusicdemo.ui.theme.customTextStyles

/**
 * 🎨 Custom Top Bar (Figma 디자인 적용)
 *
 * Figma 스펙:
 * - 높이: 60px
 * - 배경: Neutral color/Surface
 * - 타이틀: Top bar/large (SemiBold 17px, 125%)
 * - 액션 텍스트: Top bar/small (SemiBold 14px, 140%)
 * - 뒤로가기 아이콘: 36×36px
 *
 * @param title 타이틀 텍스트
 * @param showBackButton 뒤로가기 버튼 표시 여부
 * @param onBackClick 뒤로가기 클릭 리스너
 * @param actionText 우측 액션 텍스트 (선택적)
 * @param onActionClick 액션 텍스트 클릭 리스너
 * @param actionTextColor 액션 텍스트 색상 (기본값: Secondary)
 * @param backgroundColor 배경 색상 (기본값: 반투명 검정)
 */
@Composable
fun CustomTopBar(
    title: String,
    showBackButton: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = Secondary,
    backgroundColor: Color = Color.Black.copy(alpha = 0.8f),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(backgroundColor)
            .height(60.dp)  // Figma 스펙: 60px
    ) {
        // ✅ 타이틀 (완전 중앙 정렬)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.customTextStyles.topBarLarge,  // SemiBold 17sp, 125%
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        // ✅ 좌측 버튼 (뒤로가기)
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로가기",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // ✅ 우측 액션 텍스트
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
    onBackClick: () -> Unit,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = Secondary,
    modifier: Modifier = Modifier
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
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTextColor: Color = Secondary,
    modifier: Modifier = Modifier
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