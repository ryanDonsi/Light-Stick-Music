package com.lightstick.music.ui.screen.tablet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * 태블릿에서 디바이스 설정·게임 모드를 폰 화면 그대로 띄우는 팝업 프레임.
 *
 * 메인 화면의 맨 오른쪽 패널(Effect Control) 자리에 정확히 겹쳐 놓는다:
 *  - 위: 타이틀 바 바로 아래(상태바 + 타이틀 높이) — 타이틀의 게임·설정 아이콘 아래로 내려오는 모양
 *  - 아래: 시스템 내비게이션 바 위(바를 침범하지 않음) + 패널 하단 여백
 *  - 폭: 메인 화면의 맨 오른쪽 패널 폭과 같다 (전체 폭 - 좌우 여백 - 패널 간격) × [EFFECT_WIDTH_FRACTION]
 *  - 위에서 아래로 펼쳐지며 나타난다. 바깥(어두운 영역)을 누르면 [onDismiss] 한다.
 *
 * 다이얼로그 창을 시스템 바 뒤까지 그리는 모드(decorFitsSystemWindows=false)로 띄우고,
 * 메인 화면과 같은 방식으로 시스템 바 인셋을 직접 적용해 위치를 맞춘다.
 */
@Composable
fun TabletPopupFrame(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val noRipple = remember { MutableInteractionSource() }
    // 처음 표시될 때 false -> true 로 바뀌며 펼침 애니메이션이 재생된다
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss)
    ) {
        // 메인 레이아웃: 좌우 여백 PANE_GAP, 패널 사이 간격 2개
        val popupWidth = (maxWidth - PANE_GAP * 4) * EFFECT_WIDTH_FRACTION

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(top = TITLE_BAR_HEIGHT, end = PANE_GAP, bottom = PANE_GAP)
        ) {
            AnimatedVisibility(
                visibleState = visibleState,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()
            ) {
                Box(
                    modifier = Modifier
                        .width(popupWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.customColors.surface)
                        // 카드 안쪽 클릭이 바깥 클릭(닫기)으로 전달되지 않게 한다
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        // 안쪽 TopBar가 시스템 바 여백을 또 더하지 않게 소비한다
                        .consumeWindowInsets(WindowInsets.systemBars)
                ) {
                    content()
                }
            }
        }
    }
}
