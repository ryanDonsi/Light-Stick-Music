package com.lightstick.music.ui.screen.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.InitializationState
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.ui.components.init.AppLogo
import com.lightstick.music.ui.components.init.CompletionSummary
import com.lightstick.music.ui.components.init.ProgressSection
import com.lightstick.music.ui.components.splash.LogoScreen
import com.lightstick.music.ui.components.splash.PermissionGuideDialog
import kotlinx.coroutines.delay

/**
 * Splash 화면
 * - 단계별 화면 전환: 로고 → (권한 체크) → 권한 안내 다이얼로그 (권한 없을 시) → 초기화
 */
@Composable
fun SplashScreen(
    splashState: SplashState,
    onLogoTimeout: () -> Unit,
    onPermissionGuideConfirmed: () -> Unit,
    onInitializationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (splashState) {
            // 1단계: 로고 화면
            is SplashState.ShowLogo -> {
                LogoScreen(
                    onTimeout = onLogoTimeout,
                    displayDuration = 2000L
                )
            }

            // 2단계: 권한 안내 다이얼로그 (배경은 로고 유지)
            is SplashState.ShowPermissionGuide -> {
                // 배경에 로고 표시
                LogoScreen(
                    onTimeout = { },
                    displayDuration = Long.MAX_VALUE  // 타임아웃 없음
                )

                // 다이얼로그 표시 (확인 버튼 1개)
                PermissionGuideDialog(
                    onConfirm = onPermissionGuideConfirmed
                )
            }

            // 3단계: 앱 초기화 진행 중
            is SplashState.Initializing -> {
                InitializationScreen(
                    initState = splashState.initState,
                    onComplete = onInitializationComplete
                )
            }
        }
    }
}

/**
 * 앱 초기화 진행 화면 (기존 로직)
 */
@Composable
private fun InitializationScreen(
    initState: InitializationState,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 완료 시 자동 전환
    LaunchedEffect(initState) {
        if (initState is InitializationState.Completed) {
            delay(2000)
            onComplete()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ✅ 로고 컴포넌트
        AppLogo()

        Spacer(modifier = Modifier.height(48.dp))

        // ✅ 진행 상태별 컴포넌트
        when (initState) {
            is InitializationState.Idle -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("초기화 준비 중...")
            }

            is InitializationState.CheckingPermissions -> {
                ProgressSection(
                    title = "권한 확인 중...",
                    progress = initState.progress / 100f
                )
            }

            is InitializationState.ScanningMusic -> {
                ProgressSection(
                    title = "음악 파일 스캔 중...",
                    current = initState.scanned,
                    total = initState.total
                )
            }

            is InitializationState.CalculatingMusicIds -> {
                ProgressSection(
                    title = "Music ID 계산 중...",
                    current = initState.calculated,
                    total = initState.total
                )
            }

            is InitializationState.ConfiguringEffectsDirectory -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Effects 폴더 설정 중...")
            }

            is InitializationState.ScanningEffects -> {
                ProgressSection(
                    title = "Effect 파일 스캔 중...",
                    current = initState.scanned,
                    total = initState.total
                )
            }

            is InitializationState.MatchingEffects -> {
                ProgressSection(
                    title = "음악-이펙트 매칭 중...",
                    current = initState.matched,
                    total = initState.total
                )
            }

            is InitializationState.Completed -> {
                // ✅ 완료 요약 컴포넌트
                CompletionSummary(
                    musicCount = initState.musicCount,
                    effectCount = initState.effectCount,
                    matchedCount = initState.matchedCount
                )
            }

            is InitializationState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "⚠️ 오류 발생",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = initState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is InitializationState.PermissionDenied -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "⚠️ 권한 필요",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "권한: ${initState.permission}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}