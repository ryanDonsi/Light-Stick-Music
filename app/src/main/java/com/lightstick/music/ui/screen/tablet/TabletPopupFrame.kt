package com.lightstick.music.ui.screen.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/** 팝업 폭: 폰 화면 폭에 가깝게 맞춰 폰 레이아웃을 그대로 쓴다 */
private val POPUP_WIDTH = 420.dp

/** 팝업 높이 비율 (화면 높이 대비) */
private const val POPUP_HEIGHT_FRACTION = 0.92f

/**
 * 태블릿에서 디바이스 설정·게임 모드를 폰 화면 그대로 띄우는 팝업 프레임.
 *
 * - 가운데에 폰 폭(420dp)의 둥근 카드를 놓고, 바깥(어두운 영역)을 누르면 [onDismiss] 한다.
 * - 안쪽 TopBar가 상태바·내비게이션 바 여백을 또 더하지 않도록 시스템 바 인셋을 소비한다.
 */
@Composable
fun TabletPopupFrame(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(POPUP_WIDTH)
                .fillMaxHeight(POPUP_HEIGHT_FRACTION)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.surface)
                // 카드 안쪽 클릭이 바깥 클릭(닫기)으로 전달되지 않게 한다
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .consumeWindowInsets(WindowInsets.systemBars)
        ) {
            content()
        }
    }
}
