package com.lightstick.music.ui.screen.tablet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lightstick.music.R
import com.lightstick.music.ui.screen.effect.EffectScreen
import com.lightstick.music.ui.screen.music.MusicControlScreen
import com.lightstick.music.ui.screen.music.MusicListScreen
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.music.ui.viewmodel.MusicViewModel

/**
 * 태블릿(가로) 메인 화면.
 *
 * ┌──────────────────────────────────────────────┐
 * │ GLOWSYNC 로고                        [🎮] [⚙] │  ← 타이틀 (우측 상단: 게임 모드 / Device Setting)
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
    musicViewModel: MusicViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.surface)
    ) {
        TabletTitleBar(
            onGameClick = {
                navController.navigate("game") { launchSingleTop = true }
            },
            onSettingsClick = {
                navController.navigate("deviceList") { launchSingleTop = true }
            }
        )

        // 타이틀이 상태바 영역을 이미 처리했으므로, 각 패널 안쪽 TopBar가 상태바 여백을 또 더하지 않게 소비한다.
        // 하단 내비게이션 바 여백은 이 Row가 한 번만 적용한다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .consumeWindowInsets(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Pane(modifier = Modifier.weight(1f)) {
                EffectScreen(
                    viewModel = effectViewModel,
                    navController = navController
                )
            }
            Pane(modifier = Modifier.weight(1f)) {
                MusicListScreen(
                    viewModel = musicViewModel,
                    onNavigateBack = {},
                    embedded = true
                )
            }
            Pane(modifier = Modifier.weight(1.1f)) {
                MusicControlScreen(
                    viewModel = musicViewModel,
                    onNavigateToMusicList = {},
                    embedded = true
                )
            }
        }
    }
}

/** 상단 타이틀: 좌측 GLOWSYNC 로고, 우측 게임·설정 아이콘 */
@Composable
private fun TabletTitleBar(onGameClick: () -> Unit, onSettingsClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 24.dp)
    ) {
        // logo_glowsync 는 앱 아이콘과 같은 GLOWSYNC 워드마크(앱 이름 포함)
        Image(
            painter = painterResource(id = R.drawable.logo_glowsync),
            contentDescription = stringResourceAppName(),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .height(24.dp)
                .aspectRatio(LOGO_ASPECT_RATIO)
        )

        // 우측 상단: 게임 아이콘 + 설정 아이콘 (설정이 가장 오른쪽)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onGameClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_navi_game),
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

/** logo_glowsync.png 가로:세로 = 225:32 */
private const val LOGO_ASPECT_RATIO = 225f / 32f

@Composable
private fun stringResourceAppName(): String =
    androidx.compose.ui.res.stringResource(R.string.app_name)
