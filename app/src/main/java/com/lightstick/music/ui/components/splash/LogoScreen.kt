package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors
import kotlinx.coroutines.delay

/**
 * 로고 화면 컴포넌트
 * - GLOWSYNC 로고를 중앙에 표시
 * - 일정 시간 후 자동으로 다음 화면으로 전환
 */
@Composable
fun LogoScreen(
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    displayDuration: Long = 2000L
) {
    // 자동 전환 타이머
    LaunchedEffect(Unit) {
        delay(displayDuration)
        onTimeout()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background),
        contentAlignment = Alignment.Center
    ) {
        // GLOWSYNC 로고 이미지
        Image(
            painter = painterResource(id = R.drawable.logo_glowsync),
            contentDescription = "GLOWSYNC Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(240.dp)
                .height(80.dp)
        )
    }
}