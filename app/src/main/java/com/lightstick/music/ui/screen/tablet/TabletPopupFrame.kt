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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * 태블릿에서 게임 모드를 폰 화면 그대로 띄우는 팝업 프레임.
 *
 * 별도 창(Dialog)이 아니라 메인 화면 위에 겹치는 같은 창 안의 레이어다.
 *
 *  - 위치: 메인 화면의 맨 오른쪽 패널 자리 — 위는 타이틀 바 바로 아래, 아래는 시스템 내비게이션 바 위
 *  - 폭: 메인 화면의 맨 오른쪽 패널 폭과 같다 (전체 폭 - 좌우 여백 - 패널 간격) × [EFFECT_WIDTH_FRACTION]
 *  - 위에서 아래로 펼쳐지며 나타난다
 *  - 닫기: 타이틀 바 아래의 카드 바깥을 누르면 [onDismiss]
 *  - 타이틀 바 영역은 이 레이어가 터치를 받지 않는다 → 팝업이 떠 있어도 상단 아이콘이 바로 눌린다
 *    (겹친 레이어는 터치 핸들러가 있는 영역만큼 아래 레이어의 터치를 가리므로, 핸들러를 타이틀 아래에만 둔다)
 */
@Composable
fun TabletPopupFrame(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    // 처음 표시될 때 false -> true 로 바뀌며 펼침 애니메이션이 재생된다
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val titleBottom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TITLE_BAR_HEIGHT

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 메인 레이아웃: 좌우 여백 PANE_GAP, 패널 사이 간격 2개
        val popupWidth = (maxWidth - PANE_GAP * 4) * EFFECT_WIDTH_FRACTION

        // 바깥 클릭 영역: 타이틀 바 아래만
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = titleBottom)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

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
                        // 카드 안쪽 빈 곳을 눌러도 바깥 클릭(닫기)으로 전달되지 않게 한다
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

/**
 * 태블릿 전체 화면 레이어 — 메인 3단 화면을 완전히 덮고, 아래 레이어로 터치가 새지 않게 한다.
 * 안쪽 화면이 상태바·타이틀 여백을 스스로 처리한다(각 화면의 TopBar).
 */
@Composable
fun TabletFullScreen(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
            // 터치 핸들러가 있는 레이어는 그 아래 레이어의 터치를 가린다
            .pointerInput(Unit) { }
    ) {
        content()
    }
}

/** 전체 화면 설정에서 상세가 열리지 않았을 때 오른쪽 영역에 보이는 안내 */
@Composable
fun TabletDetailPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurface.copy(alpha = 0.5f)
        )
    }
}
