package com.lightstick.music.ui.screen.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.AppIcon
import com.lightstick.music.ui.screen.effect.EffectScreen
import com.lightstick.music.ui.screen.music.MusicControlScreen
import com.lightstick.music.ui.screen.music.MusicListScreen
import com.lightstick.music.ui.theme.Secondary
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.music.ui.viewmodel.MusicViewModel

/**
 * 태블릿(가로) 메인 화면.
 *
 * ┌──────────────────────────────────────────────┐
 * │ [아이콘] GLOWSYNC   EFFECT LIST  AUTO EFFECT  [🎮] [⚙] │  ← 타이틀 (패널별 타이틀은 없음)
 * ├────────────────┬─────────────┬───────────────┤
 * │ Effect Control │ Music List  │ Music Player  │
 * └────────────────┴─────────────┴───────────────┘
 *
 * 세 패널은 폰 화면(EffectScreen / MusicListScreen / MusicControlScreen)을 그대로 재사용하고,
 * ViewModel을 공유하므로 상태와 동작은 폰과 같다. 하단 탭은 없으며, 디바이스 설정은 설정 아이콘으로 진입한다.
 */
@OptIn(UnstableApi::class)
@Composable
fun TabletMainScreen(
    navController: NavController,
    effectViewModel: EffectViewModel,
    musicViewModel: MusicViewModel,
    onShowToast: (String) -> Unit
) {
    // 상단 타이틀의 EFFECT LIST / AUTO EFFECT 버튼 상태 (폰의 LIST / AUTO 와 같은 규칙, 라벨만 태블릿용)
    val isEffectBlocked by effectViewModel.isEffectBlocked.collectAsState()
    val deviceConnectionState by effectViewModel.deviceConnectionState.collectAsState()
    val isAutoModeEnabled by musicViewModel.isAutoModeEnabled.collectAsState()
    val isDeviceConnected =
        deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected
    var listRequestToken by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.surface)
    ) {
        TabletTitleBar(
            effectListColor = when {
                isEffectBlocked -> Color.Gray
                isDeviceConnected -> MaterialTheme.customColors.secondary
                else -> Color.Gray
            },
            onEffectListClick = { listRequestToken++ },
            autoEffectColor = if (isAutoModeEnabled) Secondary else Color.Gray,
            onAutoEffectClick = {
                val enabled = musicViewModel.toggleAutoMode()
                onShowToast(
                    if (enabled) "자동 연출 기능을 실행합니다."
                    else "자동 연출 기능을 중지합니다."
                )
            },
            onGameClick = {
                navController.navigate("game") { launchSingleTop = true }
            },
            onSettingsClick = {
                navController.navigate("deviceList") { launchSingleTop = true }
            }
        )

        // 타이틀이 상태바 영역을 이미 처리했으므로, 각 패널 안쪽 TopBar가 상태바 여백을 또 더하지 않게 소비한다.
        // 하단 내비게이션 바 여백은 이 컨테이너가 한 번만 적용한다.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .consumeWindowInsets(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(start = PANE_GAP, end = PANE_GAP, bottom = PANE_GAP)
        ) {
            // 패널 폭 배분:
            //  1) Effect Control = 전체의 30% (고정 비율)
            //  2) Music Player   = 높이에 맞춘 폭 — 앨범 아트가 폭만큼 정사각형으로 커지므로,
            //                      폭을 (패널 높이 - 아트 외 세로 요소 합) 으로 잡아 하단이 잘리지 않고 딱 맞게 한다
            //  3) Music List     = 나머지 전부
            val totalWidth = maxWidth - PANE_GAP * 2
            val effectWidth = totalWidth * EFFECT_WIDTH_FRACTION
            val playerWidthMax = (totalWidth - effectWidth - LIST_MIN_WIDTH).coerceAtLeast(PLAYER_MIN_WIDTH)
            val playerWidth = (maxHeight - PLAYER_HEIGHT_OVERHEAD).coerceIn(PLAYER_MIN_WIDTH, playerWidthMax)

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(PANE_GAP)
            ) {
                Pane(modifier = Modifier.width(effectWidth)) {
                    EffectScreen(
                        viewModel = effectViewModel,
                        navController = navController,
                        embedded = true,
                        listRequestToken = listRequestToken
                    )
                }
                Pane(modifier = Modifier.weight(1f)) {
                    MusicListScreen(
                        viewModel = musicViewModel,
                        onNavigateBack = {},
                        embedded = true
                    )
                }
                Pane(modifier = Modifier.width(playerWidth)) {
                    MusicControlScreen(
                        viewModel = musicViewModel,
                        onNavigateToMusicList = {},
                        embedded = true
                    )
                }
            }
        }
    }
}

/** 상단 타이틀: 좌측 앱 아이콘 + 앱 이름, 우측 EFFECT LIST · AUTO EFFECT · 게임 · 설정 */
@Composable
private fun TabletTitleBar(
    effectListColor: Color,
    onEffectListClick: () -> Unit,
    autoEffectColor: Color,
    onAutoEffectClick: () -> Unit,
    onGameClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    // Light-Stick-EfxCreator 의 타이틀(Material TopAppBar)과 같은 규격:
    // 높이 64dp, 시작 여백 16dp·끝 여백 4dp, 런처 아이콘 36dp + 간격 12dp + 앱 이름(titleLarge)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TITLE_BAR_HEIGHT)
            .padding(start = 16.dp, end = 4.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(size = 36.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.customColors.onSurface
            )
        }

        // 우측 상단: EFFECT LIST · AUTO EFFECT 텍스트 버튼 + 게임 · 설정 아이콘 (설정이 가장 오른쪽)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TitleTextButton(text = "EFFECT LIST", color = effectListColor, onClick = onEffectListClick)
            TitleTextButton(text = "AUTO EFFECT", color = autoEffectColor, onClick = onAutoEffectClick)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onGameClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_game_controller),
                    contentDescription = "게임 모드",
                    tint = MaterialTheme.customColors.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_setting),
                    contentDescription = "디바이스 설정",
                    tint = MaterialTheme.customColors.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/** 타이틀 바의 텍스트 버튼 (폰 TopBar 의 액션 텍스트와 같은 스타일) */
@Composable
private fun TitleTextButton(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.customTextStyles.topBarSmall,
            color = color
        )
    }
}

/** 둥근 모서리 패널 */
@Composable
private fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.customColors.surface)
    ) {
        content()
    }
}

/** 타이틀 바 높이 (EfxCreator 의 TopAppBar 와 같은 64dp) */
private val TITLE_BAR_HEIGHT = 64.dp

private val PANE_GAP = 12.dp

/** Effect Control 패널이 차지하는 가로 비율 */
private const val EFFECT_WIDTH_FRACTION = 0.3f

/** Music List 패널 최소 폭 — 이보다 좁아지지 않게 Music Player 폭을 줄인다 */
private val LIST_MIN_WIDTH = 280.dp

/** Music Player 패널 최소 폭 */
private val PLAYER_MIN_WIDTH = 240.dp

/**
 * Music Player 패널 폭 = 패널 높이 - 이 값.
 * 플레이어 카드는 앨범 이미지를 "가로·남는 높이 중 작은 쪽"의 정사각형으로 그리므로 높이가 모자라도 잘리지는 않는다.
 * 이 값은 이미지가 패널 폭과 높이에 동시에 딱 맞는(양옆 빈 공간이 없는) 폭을 구하기 위한 것이다.
 *  - 이미지 한 변(가로) = 폭 - 72dp  (화면 좌우 16×2 + 카드 안쪽 20×2)
 *  - 이미지 한 변(세로) = 높이 - 316dp (위아래 여백 12×2 + 카드 안쪽 20×2 + 간격 24·8·24 + 제목 28 + 아티스트 24 +
 *                         탐색 바 40 + 버튼 64 + EFFECT INFO 줄 40)
 *  두 값이 같아지는 폭 = 높이 - 244dp.
 */
private val PLAYER_HEIGHT_OVERHEAD = 244.dp
