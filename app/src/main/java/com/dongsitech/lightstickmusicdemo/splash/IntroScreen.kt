package com.dongsitech.lightstickmusicdemo.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.model.InitializationState
import com.dongsitech.lightstickmusicdemo.ui.components.init.AppLogo
import com.dongsitech.lightstickmusicdemo.ui.components.init.CompletionSummary
import com.dongsitech.lightstickmusicdemo.ui.components.init.ProgressSection

/**
 * ✅ 리팩토링: 컴포넌트 분리 완료
 */
@Composable
fun IntroScreen(
    state: InitializationState,
    onComplete: () -> Unit
) {
    // 완료 시 자동 전환
    LaunchedEffect(state) {
        if (state is InitializationState.Completed) {
            kotlinx.coroutines.delay(2000)
            onComplete()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // ✅ 로고 컴포넌트
            AppLogo()

            Spacer(modifier = Modifier.height(48.dp))

            // ✅ 진행 상태별 컴포넌트
            when (state) {
                is InitializationState.Idle -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("초기화 준비 중...")
                }

                is InitializationState.CheckingPermissions -> {
                    ProgressSection(
                        title = "권한 확인 중...",
                        progress = state.progress / 100f
                    )
                }

                is InitializationState.ScanningMusic -> {
                    ProgressSection(
                        title = "음악 파일 스캔 중...",
                        current = state.scanned,
                        total = state.total
                    )
                }

                is InitializationState.CalculatingMusicIds -> {
                    ProgressSection(
                        title = "Music ID 계산 중...",
                        current = state.calculated,
                        total = state.total
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
                        current = state.scanned,
                        total = state.total
                    )
                }

                is InitializationState.MatchingEffects -> {
                    ProgressSection(
                        title = "음악-이펙트 매칭 중...",
                        current = state.matched,
                        total = state.total
                    )
                }

                is InitializationState.Completed -> {
                    // ✅ 완료 요약 컴포넌트
                    CompletionSummary(
                        musicCount = state.musicCount,
                        effectCount = state.effectCount,
                        matchedCount = state.matchedCount
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
                            text = state.message,
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
                            text = "권한: ${state.permission}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}