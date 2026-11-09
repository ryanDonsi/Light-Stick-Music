package com.lightstick.music.ui.screen.tablet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * 태블릿에서 디바이스 설정·게임 모드를 폰 화면 그대로 띄우는 팝업 프레임.
 *
 * 별도 창(Dialog)이 아니라 메인 화면 위에 겹치는 같은 창 안의 레이어다.
 * 그래서 팝업이 떠 있는 동안에도 상단 타이틀의 아이콘이 바로 눌린다 (팝업이 터치를 가로채지 않는다).
 *
 *  - 위치: 메인 화면의 맨 오른쪽 패널 자리 — 위는 타이틀 바 바로 아래, 아래는 시스템 내비게이션 바 위
 *  - 폭: 메인 화면의 맨 오른쪽 패널 폭과 같다 (전체 폭 - 좌우 여백 - 패널 간격) × [EFFECT_WIDTH_FRACTION]
 *  - [detail] 이 있으면 카드를 두 장으로 — 오른쪽 [content], 그 왼쪽 옆에 [detail]
 *  - 위에서 아래로 펼쳐지며 나타난다
 *  - 닫기: 카드 바깥을 누르면 [onDismiss]. 단, 타이틀 바 영역은 제외한다(그 영역의 아이콘이 알아서 동작하도록).
 *    누름을 소비하지 않고 관찰만 하므로, 바깥의 버튼은 그대로 눌린다.
 */
@Composable
fun TabletPopupFrame(
    onDismiss: () -> Unit,
    /** 있으면 [content] 카드 왼쪽 옆에 같은 크기의 카드로 나란히 표시한다 (예: 기기 목록 옆의 상세 화면) */
    detail: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 타이틀 바 하단 경계(px): 상태바 + 타이틀 높이
    val titleBottomPx = with(density) { WindowInsets.statusBars.getTop(density) + TITLE_BAR_HEIGHT.toPx() }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 처음 표시될 때 false -> true 로 바뀌며 펼침 애니메이션이 재생된다
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial 패스에서 보기만 하고 소비하지 않는다 → 아래층(타이틀 아이콘 등)도 같은 터치를 받는다
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            val pos = event.changes.first().position
                            val card = Rect(
                                cardBounds.left - origin.x, cardBounds.top - origin.y,
                                cardBounds.right - origin.x, cardBounds.bottom - origin.y
                            )
                            if (!card.contains(pos) && pos.y > titleBottomPx) currentOnDismiss()
                        }
                    }
                }
            }
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
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .onGloballyPositioned { cardBounds = it.boundsInRoot() },
                    horizontalArrangement = Arrangement.spacedBy(PANE_GAP)
                ) {
                    if (detail != null) {
                        PopupCard(popupWidth) { detail() }
                    }
                    PopupCard(popupWidth) { content() }
                }
            }
        }
    }
}

/** 팝업 카드 한 장: 둥근 모서리 패널, 시스템 바 인셋은 바깥에서 이미 적용했으므로 소비한다 */
@Composable
private fun PopupCard(width: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.customColors.surface)
            // 안쪽 TopBar가 시스템 바 여백을 또 더하지 않게 소비한다
            .consumeWindowInsets(WindowInsets.systemBars)
    ) {
        content()
    }
}
