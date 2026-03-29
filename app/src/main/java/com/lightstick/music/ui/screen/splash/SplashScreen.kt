package com.lightstick.music.ui.screen.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.InitializationState
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.ui.components.init.TextSection
import com.lightstick.music.ui.components.splash.ProgressSection
import com.lightstick.music.ui.components.splash.LogoScreen
import com.lightstick.music.ui.components.splash.PermissionGuideDialog
import com.lightstick.music.ui.theme.customColors
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
            is SplashState.ShowLogo -> {
                LogoScreen(
                    onTimeout = onLogoTimeout,
                    displayDuration = 2000L
                )
            }

            is SplashState.ShowPermissionGuide -> {
                LogoScreen(
                    onTimeout = { },
                    displayDuration = Long.MAX_VALUE
                )
                PermissionGuideDialog(
                    onConfirm = onPermissionGuideConfirmed
                )
            }

            is SplashState.Initializing -> {
                InitializationScreen(
                    initState = splashState.initState,
                    onComplete = onInitializationComplete
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// [신규] AnimatedDotsLabel
// - text: 기본 라벨 텍스트 ("파일명 이펙트 생성 중" 등)
// - dots가 . → .. → ... → . 순서로 500ms마다 순환
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnimatedDotsLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    var dotStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500L)
            dotStep = (dotStep + 1) % 3
        }
    }

    // dots를 고정폭 Box에 분리 → 텍스트 좌우 흔들림 방지
    val dots = when (dotStep) {
        0 -> "."
        1 -> ".."
        else -> "..."
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 고정폭 Box: "..." 3글자 너비를 항상 확보
        Box(modifier = Modifier.width(14.dp)) {
            Text(
                text = dots,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.customColors.onSurface.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// InitializationScreen
// ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun InitializationScreen(
    initState: InitializationState,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(initState) {
        if (initState is InitializationState.Completed) {
            onComplete()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LogoScreen(
                onTimeout = { },
                displayDuration = Long.MAX_VALUE,
                modifier = Modifier.height(200.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            when (initState) {
                is InitializationState.Idle -> {
                    AnimatedDotsLabel(text = "초기화 준비 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                }

                is InitializationState.CheckingPermissions -> {
                    AnimatedDotsLabel(text = "권한 확인 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

                    ProgressSection(
                        title = "",
                        progress = initState.progress / 100f
                    )
                }

                is InitializationState.ScanningMusic -> {
                    AnimatedDotsLabel(text = "음악 파일 스캔 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

                    ProgressSection(
                        title = "",
                        current = initState.scanned,
                        total = initState.total
                    )
                }

                is InitializationState.CalculatingMusicIds -> {
                    AnimatedDotsLabel(text = "Music ID 계산 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

                    ProgressSection(
                        title = "",
                        current = initState.calculated,
                        total = initState.total
                    )
                }

                is InitializationState.ConfiguringEffectsDirectory -> {
                    AnimatedDotsLabel(text = "Effects 폴더 설정 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                }

                is InitializationState.ScanningEffects -> {
                    AnimatedDotsLabel(text = "Effect 파일 스캔 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

                    ProgressSection(
                        title = "",
                        current = initState.scanned,
                        total = initState.total
                    )
                }

                is InitializationState.MatchingEffects -> {
                    AnimatedDotsLabel(text = "음악-이펙트 매칭 중", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))

                    ProgressSection(
                        title = "",
                        current = initState.matched,
                        total = initState.total
                    )
                }

                // [수정] PrecomputingTimelines: 파일명을 타이틀 자리에 직접 표시
                // - 파일명 있음: "[파일명] 이펙트 생성 중..." 애니메이션 dots
                // - 파일명 없음(초기): "자동 타임라인 생성 중..." 애니메이션 dots
                is InitializationState.PrecomputingTimelines -> {
                    val labelText = if (initState.currentFileName.isNotEmpty())
                        "${initState.currentFileName} 이펙트 생성 중"
                    else
                        "자동 타임라인 생성 중"

                    // AnimatedDotsLabel이 타이틀 역할 수행 (ProgressSection 고정 텍스트 대체)
                    AnimatedDotsLabel(
                        text = labelText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                    )

                    ProgressSection(
                        title = "",   // 타이틀 없음 — AnimatedDotsLabel이 대신함
                        current = initState.processed,
                        total = initState.total
                    )
                }

                is InitializationState.Completed -> {
                    TextSection(title = "초기화 완료")
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
}