package com.lightstick.music.ui.screen.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.R
import com.lightstick.music.data.model.GameDifficulty
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.data.model.GameResultSummary
import com.lightstick.music.data.model.GameState
import com.lightstick.music.data.model.TeamWinner
import com.lightstick.music.data.model.WandResult
import com.lightstick.music.domain.game.GameBleManager
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.GameViewModel

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val selectedMode by viewModel.selectedMode.collectAsState()
    val selectedDifficulty by viewModel.selectedDifficulty.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    val bleState by viewModel.bleConnectionState.collectAsState()
    val countdownSeconds by viewModel.countdownSeconds.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.connectIfNeeded()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
    ) {
        AnimatedContent(
            targetState = gameState,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically { it / 4 })
                    .togetherWith(fadeOut(tween(200)) + slideOutVertically { -it / 4 })
            },
            label = "game_state_transition"
        ) { state ->
            when (state) {
                is GameState.Idle -> ModeSelectionContent(
                    selectedMode = selectedMode,
                    selectedDifficulty = selectedDifficulty,
                    bleState = bleState,
                    onModeSelect = viewModel::selectMode,
                    onDifficultySelect = viewModel::selectDifficulty,
                    onStart = viewModel::startGame
                )
                is GameState.Ready -> CountdownContent(countdownSeconds = countdownSeconds)
                is GameState.Playing -> PlayingContent(
                    mode = selectedMode,
                    onStop = viewModel::stopGame
                )
                is GameState.Finished -> ResultContent(
                    summary = state.summary,
                    onPlayAgain = viewModel::resetToIdle
                )
                is GameState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = viewModel::resetToIdle
                )
            }
        }
    }
}

// ─── Mode Selection ───────────────────────────────────────────────────────────

@Composable
private fun ModeSelectionContent(
    selectedMode: GameMode?,
    selectedDifficulty: GameDifficulty,
    bleState: GameBleManager.ConnectionState,
    onModeSelect: (GameMode) -> Unit,
    onDifficultySelect: (GameDifficulty) -> Unit,
    onStart: () -> Unit
) {
    val colors = MaterialTheme.customColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Game Mode",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
        )

        // BLE 연결 상태 배너
        BleBanner(bleState = bleState)

        Spacer(modifier = Modifier.height(4.dp))

        // 게임 모드 카드 선택
        Text(
            text = "모드 선택",
            style = MaterialTheme.typography.labelLarge.copy(color = colors.surfaceVariant)
        )

        GameMode.entries.forEach { mode ->
            GameModeCard(
                mode = mode,
                selected = selectedMode == mode,
                onClick = { onModeSelect(mode) }
            )
        }

        // 난이도 선택 (모드가 선택된 경우)
        AnimatedVisibility(visible = selectedMode != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "난이도",
                    style = MaterialTheme.typography.labelLarge.copy(color = colors.surfaceVariant)
                )
                DifficultySelector(
                    selected = selectedDifficulty,
                    onSelect = onDifficultySelect
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 시작 버튼
        val canStart = selectedMode != null &&
            bleState is GameBleManager.ConnectionState.Connected

        StartButton(
            enabled = canStart,
            onClick = onStart
        )

        if (bleState !is GameBleManager.ConnectionState.Connected) {
            Text(
                text = when (bleState) {
                    is GameBleManager.ConnectionState.Connecting -> "기기에 연결 중..."
                    is GameBleManager.ConnectionState.Error -> (bleState as GameBleManager.ConnectionState.Error).message
                    else -> "게임을 시작하려면 응원봉을 연결하세요"
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.surfaceVariant,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BleBanner(bleState: GameBleManager.ConnectionState) {
    val colors = MaterialTheme.customColors
    val (bgColor, text) = when (bleState) {
        is GameBleManager.ConnectionState.Connected ->
            Color(0xFF1A3A1A) to "응원봉 연결됨"
        is GameBleManager.ConnectionState.Connecting ->
            Color(0xFF2A2A0A) to "연결 중..."
        is GameBleManager.ConnectionState.Error ->
            Color(0xFF3A1A1A) to (bleState as GameBleManager.ConnectionState.Error).message
        else ->
            Color(0xFF252525) to "응원봉 미연결"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val dotColor = when (bleState) {
            is GameBleManager.ConnectionState.Connected -> Color(0xFF4CAF50)
            is GameBleManager.ConnectionState.Connecting -> Color(0xFFFFD46F)
            else -> Color(0xFF757575)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface)
        )
        if (bleState is GameBleManager.ConnectionState.Connecting) {
            Spacer(modifier = Modifier.width(4.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.primary
            )
        }
    }
}

@Composable
private fun GameModeCard(
    mode: GameMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.customColors
    val borderColor = if (selected) colors.primary else Color.Transparent
    val bgColor = if (selected) colors.primary.copy(alpha = 0.12f) else colors.onSurface.copy(alpha = 0.05f)

    val modeEmoji = when (mode) {
        GameMode.SPEED_REACTION -> "⚡"
        GameMode.TEMPO -> "🎵"
        GameMode.TEAM_BATTLE -> "⚔️"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = modeEmoji, fontSize = 28.sp)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = mode.nameKr,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) colors.primary else colors.onSurface
                )
            )
            Text(
                text = mode.descKr,
                style = MaterialTheme.typography.bodySmall.copy(color = colors.surfaceVariant)
            )
            Text(
                text = mode.winConditionKr,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (selected) colors.primary.copy(alpha = 0.8f) else colors.textTertiary
                )
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun DifficultySelector(
    selected: GameDifficulty,
    onSelect: (GameDifficulty) -> Unit
) {
    val colors = MaterialTheme.customColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GameDifficulty.entries.forEach { diff ->
            val isSelected = diff == selected
            val bgColor = when {
                isSelected && diff == GameDifficulty.EASY -> Color(0xFF1A3A1A)
                isSelected && diff == GameDifficulty.NORMAL -> Color(0xFF1A2A3A)
                isSelected && diff == GameDifficulty.HARD -> Color(0xFF3A1A1A)
                else -> colors.onSurface.copy(alpha = 0.05f)
            }
            val textColor = when {
                isSelected && diff == GameDifficulty.EASY -> Color(0xFF4CAF50)
                isSelected && diff == GameDifficulty.NORMAL -> colors.primary
                isSelected && diff == GameDifficulty.HARD -> Color(0xFFFF5252)
                else -> colors.surfaceVariant
            }
            val borderColor = if (isSelected) textColor else Color.Transparent

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(diff) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = diff.nameKr,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun StartButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.customColors
    val bgBrush = if (enabled) {
        Brush.horizontalGradient(listOf(colors.gradientStart, colors.gradientEnd))
    } else {
        Brush.horizontalGradient(listOf(colors.disable, colors.disable))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "게임 시작",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = if (enabled) colors.onPrimary else colors.onDisable
            )
        )
    }
}

// ─── Countdown / Ready ────────────────────────────────────────────────────────

@Composable
private fun CountdownContent(countdownSeconds: Int) {
    val colors = MaterialTheme.customColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "준비 중",
                style = MaterialTheme.typography.headlineSmall.copy(color = colors.surfaceVariant)
            )
            Spacer(modifier = Modifier.height(16.dp))
            AnimatedContent(
                targetState = countdownSeconds,
                transitionSpec = {
                    fadeIn(tween(200)).togetherWith(fadeOut(tween(150)))
                },
                label = "countdown"
            ) { sec ->
                Text(
                    text = if (sec > 0) "$sec" else "시작!",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.primary,
                        fontSize = 80.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "응원봉이 곧 시작됩니다",
                style = MaterialTheme.typography.bodyMedium.copy(color = colors.textTertiary)
            )
        }
    }
}

// ─── Playing ─────────────────────────────────────────────────────────────────

@Composable
private fun PlayingContent(mode: GameMode?, onStop: () -> Unit) {
    val colors = MaterialTheme.customColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = colors.primary,
                strokeWidth = 4.dp
            )
            Text(
                text = "게임 진행 중",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
            )
            if (mode != null) {
                Text(
                    text = mode.nameKr,
                    style = MaterialTheme.typography.bodyLarge.copy(color = colors.surfaceVariant)
                )
            }
            Text(
                text = "결과를 기다리는 중...",
                style = MaterialTheme.typography.bodyMedium.copy(color = colors.textTertiary)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3A1A1A))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStop
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "게임 중지",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

// ─── Results ─────────────────────────────────────────────────────────────────

@Composable
private fun ResultContent(summary: GameResultSummary, onPlayAgain: () -> Unit) {
    val colors = MaterialTheme.customColors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "게임 결과",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
        )

        when (summary.mode) {
            GameMode.SPEED_REACTION, GameMode.TEMPO -> SoloResultContent(summary)
            GameMode.TEAM_BATTLE -> TeamResultContent(summary)
        }

        Spacer(modifier = Modifier.height(8.dp))
        StartButton(enabled = true, onClick = onPlayAgain)
    }
}

@Composable
private fun SoloResultContent(summary: GameResultSummary) {
    val colors = MaterialTheme.customColors

    // 우승자 배너
    val winner = summary.soloWinner
    if (winner != null) {
        WinnerBanner(label = "우승!", subtitle = "응원봉 #${winner.wandId.toString(16).uppercase()} | ${winner.redScore}점")
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "순위표",
        style = MaterialTheme.typography.labelLarge.copy(color = colors.surfaceVariant)
    )

    summary.rankedResults.forEachIndexed { index, result ->
        RankRow(rank = index + 1, result = result, isTeamBattle = false)
    }
}

@Composable
private fun TeamResultContent(summary: GameResultSummary) {
    val colors = MaterialTheme.customColors

    // 팀 결과
    val winner = summary.teamWinner
    val winnerLabel = when (winner) {
        TeamWinner.RED -> "Red팀 승리!"
        TeamWinner.BLUE -> "Blue팀 승리!"
        TeamWinner.DRAW -> "무승부!"
    }
    WinnerBanner(
        label = winnerLabel,
        subtitle = "Red ${summary.totalRedScore}점  |  Blue ${summary.totalBlueScore}점"
    )

    Spacer(modifier = Modifier.height(4.dp))

    // 팀 점수 비교 바
    TeamScoreBar(
        redScore = summary.totalRedScore,
        blueScore = summary.totalBlueScore
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "개인 점수",
        style = MaterialTheme.typography.labelLarge.copy(color = colors.surfaceVariant)
    )

    summary.wandResults
        .filter { it.wandId != 0 && it.wandId != 0xFFFF }
        .sortedByDescending { it.redScore + it.blueScore }
        .forEachIndexed { index, result ->
            RankRow(rank = index + 1, result = result, isTeamBattle = true)
        }

    Text(
        text = "참여 응원봉: ${summary.totalWandCount}개",
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.customColors.textTertiary),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End
    )
}

@Composable
private fun WinnerBanner(label: String, subtitle: String) {
    val colors = MaterialTheme.customColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(listOf(colors.gradientStart, colors.gradientEnd))
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "🏆",
                fontSize = 36.sp
            )
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.onPrimary
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onPrimary.copy(alpha = 0.8f)
                )
            )
        }
    }
}

@Composable
private fun TeamScoreBar(redScore: Int, blueScore: Int) {
    val total = (redScore + blueScore).coerceAtLeast(1)
    val redRatio = redScore.toFloat() / total
    val animatedRatio by animateFloatAsState(
        targetValue = redRatio,
        animationSpec = tween(800),
        label = "team_score_bar"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Red  $redScore점",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "$blueScore점  Blue",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color(0xFF448AFF),
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF448AFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedRatio)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFF5252))
            )
        }
    }
}

@Composable
private fun RankRow(rank: Int, result: WandResult, isTeamBattle: Boolean) {
    val colors = MaterialTheme.customColors
    val medalColor = when (rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFCD7F32)
        else -> colors.surfaceVariant
    }
    val score = if (isTeamBattle) result.redScore + result.blueScore else result.redScore

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "${rank}위",
            style = MaterialTheme.typography.labelLarge.copy(
                color = medalColor,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = "응원봉 #${result.wandId.toString(16).uppercase()}",
            style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${score}점",
            style = MaterialTheme.typography.titleMedium.copy(
                color = colors.primary,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.customColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "⚠️", fontSize = 48.sp)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primary.copy(alpha = 0.15f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRetry
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "돌아가기",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}
