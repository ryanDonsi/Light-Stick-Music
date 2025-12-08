package com.dongsitech.lightstickmusicdemo.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.model.InitializationState

@Composable
fun IntroScreen(
    state: InitializationState,
    onComplete: () -> Unit
) {
    // 완료 시 자동 전환
    LaunchedEffect(state) {
        if (state is InitializationState.Completed) {
            kotlinx.coroutines.delay(2000)  // 2초 대기
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
            // 앱 아이콘/로고
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "App Logo",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 앱 타이틀
            Text(
                text = "Light Stick Music",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "전시회용 응원봉 제어 앱",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 진행 상태 표시
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Complete",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "✓ 초기화 완료",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                InfoRow("음악 파일", "${state.musicCount}개")
                                Spacer(modifier = Modifier.height(8.dp))
                                InfoRow("이펙트 파일", "${state.effectCount}개")
                                Spacer(modifier = Modifier.height(8.dp))
                                InfoRow(
                                    "매칭 완료",
                                    "${state.matchedCount}개",
                                    highlight = true
                                )
                            }
                        }
                    }
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

@Composable
private fun ProgressSection(
    title: String,
    current: Int? = null,
    total: Int? = null,
    progress: Float? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (current != null && total != null && total > 0) {
            // ✅ 수정: progress를 람다로 전달
            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier
                    .width(240.dp)
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$current / $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (progress != null) {
            // ✅ 수정: progress를 람다로 전달
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(240.dp)
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}