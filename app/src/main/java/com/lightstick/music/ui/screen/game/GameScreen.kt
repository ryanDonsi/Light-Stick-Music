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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.components.common.CustomChip
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.TopBarCentered
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
import com.lightstick.music.ui.viewmodel.GameViewModel

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val selectedMode     by viewModel.selectedMode.collectAsState()
    val selectedDifficulty by viewModel.selectedDifficulty.collectAsState()
    val gameState        by viewModel.gameState.collectAsState()
    val bleState         by viewModel.bleConnectionState.collectAsState()
    val countdownSeconds by viewModel.countdownSeconds.collectAsState()
    val partialResults   by viewModel.partialResults.collectAsState()

    val toastState = rememberToastState()
    var showStopDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.connectIfNeeded() }

    // 에러 상태 토스트 표시
    LaunchedEffect(gameState) {
        if (gameState is GameState.Error) {
            toastState.show((gameState as GameState.Error).message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.customColors.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopBarCentered(
                title = "Game Mode",
                actionText = if (gameState is GameState.Playing) "중지" else null,
                onActionClick = { showStopDialog = true },
                actionTextColor = MaterialTheme.colorScheme.error
            )

            AnimatedContent(
                targetState = gameState,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { it / 6 })
                        .togetherWith(fadeOut(tween(200)) + slideOutVertically { -it / 6 })
                },
                label = "game_state"
            ) { state ->
                when (state) {
                    is GameState.Idle, is GameState.Error -> ModeSelectionContent(
                        selectedMode = selectedMode,
                        selectedDifficulty = selectedDifficulty,
                        bleState = bleState,
                        onModeSelect = viewModel::selectMode,
                        onDifficultySelect = viewModel::selectDifficulty,
                        onStart = viewModel::startGame
                    )
                    is GameState.Ready  -> CountdownContent(countdownSeconds = countdownSeconds)
                    is GameState.Playing -> PlayingContent(
                        mode = selectedMode,
                        partialResults = partialResults,
                        onStopClick = { showStopDialog = true }
                    )
                    is GameState.Finished -> ResultContent(
                        summary = state.summary,
                        onPlayAgain = viewModel::resetToIdle
                    )
                }
            }
        }

        // 게임 중지 확인 다이얼로그
        if (showStopDialog) {
            BaseDialog(
                title = "게임 중지",
                subtitle = "진행 중인 게임을 중지할까요?",
                onDismiss = { showStopDialog = false },
                onConfirm = {
                    showStopDialog = false
                    viewModel.stopGame()
                },
                confirmText = "중지",
                dismissText = "취소"
            )
        }

        // 토스트
        CustomToast(
            message = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = toastState::dismiss,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // BLE 연결 상태
        BleBanner(bleState = bleState)

        // 모드 선택
        Text(
            text = "모드 선택",
            style = MaterialTheme.customTextStyles.bodyAccent,
            color = colors.surfaceVariant
        )

        GameMode.entries.forEach { mode ->
            GameModeCard(
                mode = mode,
                selected = selectedMode == mode,
                onClick = { onModeSelect(mode) }
            )
        }

        // 난이도 선택
        AnimatedVisibility(visible = selectedMode != null) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "난이도",
                    style = MaterialTheme.customTextStyles.bodyAccent,
                    color = colors.surfaceVariant
                )
                DifficultyRow(
                    selected = selectedDifficulty,
                    onSelect = onDifficultySelect
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 시작 버튼
        BaseButton(
            text = "게임 시작",
            onClick = onStart,
            enabled = selectedMode != null && bleState is GameBleManager.ConnectionState.Connected,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )

        // 게임 미지원 기기 에러 메시지만 표시
        if (bleState is GameBleManager.ConnectionState.Error) {
            Text(
                text = (bleState as GameBleManager.ConnectionState.Error).message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BleBanner(bleState: GameBleManager.ConnectionState) {
    val colors = MaterialTheme.customColors

    val bgColor = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> Color(0xFF1A3A1A)
        is GameBleManager.ConnectionState.Connecting -> Color(0xFF2A2A0A)
        is GameBleManager.ConnectionState.Error      -> Color(0xFF3A1A1A)
        else                                         -> Color(0xFF252525)
    }
    val dotColor = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> Color(0xFF4CAF50)
        is GameBleManager.ConnectionState.Connecting -> MaterialTheme.customColors.secondary
        else                                         -> colors.surfaceVariant
    }
    val labelText = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> "응원봉 연결됨"
        is GameBleManager.ConnectionState.Connecting -> "연결 중..."
        is GameBleManager.ConnectionState.Error      ->
            (bleState as GameBleManager.ConnectionState.Error).message
        else -> "응원봉 미연결"
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
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = labelText,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (bleState is GameBleManager.ConnectionState.Connecting) {
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
    val bgColor = if (selected)
        colors.primary.copy(alpha = 0.12f)
    else
        colors.onSurface.copy(alpha = 0.05f)

    val modeIcon = when (mode) {
        GameMode.SPEED_REACTION -> Icons.Filled.Bolt
        GameMode.TEMPO          -> Icons.Filled.MusicNote
        GameMode.TEAM_BATTLE    -> Icons.Filled.Groups
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
        Icon(
            imageVector = modeIcon,
            contentDescription = null,
            tint = if (selected) colors.onSurface else colors.surfaceVariant,
            modifier = Modifier.size(28.dp)
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = mode.nameKr,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                text = mode.descKr,
                style = MaterialTheme.typography.bodySmall,
                color = colors.surfaceVariant
            )
            Text(
                text = mode.winConditionKr,
                style = MaterialTheme.customTextStyles.badgeMedium,
                color = if (selected) colors.onSurface.copy(alpha = 0.7f) else colors.textTertiary
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
private fun DifficultyRow(
    selected: GameDifficulty,
    onSelect: (GameDifficulty) -> Unit
) {
    val colors = MaterialTheme.customColors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameDifficulty.entries.forEach { diff ->
            val isSelected = diff == selected
            val chipBg = when {
                isSelected && diff == GameDifficulty.EASY   -> Color(0xFF1A3A1A)
                isSelected && diff == GameDifficulty.NORMAL -> colors.primaryContainer.copy(alpha = 0.3f)
                isSelected && diff == GameDifficulty.HARD   -> Color(0xFF3A1A1A)
                else -> colors.onSurface.copy(alpha = 0.05f)
            }
            val chipContent = when {
                isSelected && diff == GameDifficulty.EASY   -> Color(0xFF4CAF50)
                isSelected && diff == GameDifficulty.NORMAL -> colors.primary
                isSelected && diff == GameDifficulty.HARD   -> MaterialTheme.colorScheme.error
                else -> colors.surfaceVariant
            }

            CustomChip(
                text = diff.nameKr,
                onClick = { onSelect(diff) },
                containerColor = chipBg,
                contentColor = chipContent,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            )
        }
    }
}

// ─── Countdown ────────────────────────────────────────────────────────────────

@Composable
private fun CountdownContent(countdownSeconds: Int) {
    val colors = MaterialTheme.customColors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "준비 중",
                style = MaterialTheme.typography.titleMedium,
                color = colors.surfaceVariant
            )
            AnimatedContent(
                targetState = countdownSeconds,
                transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(150))) },
                label = "countdown"
            ) { sec ->
                Text(
                    text = if (sec > 0) "$sec" else "시작!",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.primary
                )
            }
            Text(
                text = "응원봉이 곧 시작됩니다",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textTertiary
            )
        }
    }
}

// ─── Playing ─────────────────────────────────────────────────────────────────

@Composable
private fun PlayingContent(
    mode: GameMode?,
    partialResults: List<WandResult>,
    onStopClick: () -> Unit
) {
    val colors = MaterialTheme.customColors
    val showRanking = (mode == GameMode.SPEED_REACTION || mode == GameMode.TEMPO) &&
            partialResults.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 상단 헤더
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = colors.primary,
                strokeWidth = 2.5.dp
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "게임 진행 중",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface
                )
                if (mode != null) {
                    Text(
                        text = mode.nameKr,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.surfaceVariant
                    )
                }
            }
        }

        if (showRanking) {
            Text(
                text = "실시간 순위",
                style = MaterialTheme.customTextStyles.bodyAccent,
                color = colors.surfaceVariant
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                partialResults.forEachIndexed { index, result ->
                    RankRow(rank = index + 1, result = result, isTeamBattle = false)
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "결과를 기다리는 중...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary
                )
            }
        }

        BaseButton(
            text = "게임 중지",
            onClick = onStopClick,
            style = ButtonStyle.ERROR,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (summary.mode) {
            GameMode.SPEED_REACTION, GameMode.TEMPO -> SoloResultContent(summary)
            GameMode.TEAM_BATTLE                    -> TeamResultContent(summary)
        }

        Text(
            text = "참여 응원봉: ${summary.totalWandCount}개",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.height(4.dp))

        BaseButton(
            text = "다시 하기",
            onClick = onPlayAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )
    }
}

@Composable
private fun SoloResultContent(summary: GameResultSummary) {
    val winner = summary.soloWinner
    if (winner != null) {
        WinnerBanner(
            label = "우승!",
            subtitle = "응원봉 #${winner.wandId.toString(16).uppercase()} · ${winner.redScore}점"
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "순위표",
        style = MaterialTheme.customTextStyles.bodyAccent,
        color = MaterialTheme.customColors.surfaceVariant
    )

    summary.rankedResults.forEachIndexed { index, result ->
        RankRow(rank = index + 1, result = result, isTeamBattle = false)
    }
}

@Composable
private fun TeamResultContent(summary: GameResultSummary) {
    val winnerLabel = when (summary.teamWinner) {
        TeamWinner.RED  -> "Red팀 승리!"
        TeamWinner.BLUE -> "Blue팀 승리!"
        TeamWinner.DRAW -> "무승부!"
    }
    WinnerBanner(
        label = winnerLabel,
        subtitle = "Red ${summary.totalRedScore}점  |  Blue ${summary.totalBlueScore}점"
    )

    // 팀 점수 비교 바
    TeamScoreBar(
        redScore = summary.totalRedScore,
        blueScore = summary.totalBlueScore
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "🏆", fontSize = 36.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimary.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun TeamScoreBar(redScore: Int, blueScore: Int) {
    val colors = MaterialTheme.customColors
    val total = (redScore + blueScore).coerceAtLeast(1)
    val redRatio by animateFloatAsState(
        targetValue = redScore.toFloat() / total,
        animationSpec = tween(800),
        label = "team_score_ratio"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Red  ${redScore}점",
                style = MaterialTheme.customTextStyles.buttonSmall,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "${blueScore}점  Blue",
                style = MaterialTheme.customTextStyles.buttonSmall,
                color = Color(0xFF448AFF)
            )
        }

        // 배경 = Blue, 전경 = Red
        Box(modifier = Modifier.fillMaxWidth()) {
            CommonProgressBar(
                progress = 1f,
                progressColor = Color(0xFF448AFF),
                trackColor = Color(0xFF448AFF),
                height = 12.dp,
                modifier = Modifier.fillMaxWidth()
            )
            CommonProgressBar(
                progress = redRatio,
                progressColor = MaterialTheme.colorScheme.error,
                trackColor = Color.Transparent,
                height = 12.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RankRow(rank: Int, result: WandResult, isTeamBattle: Boolean) {
    val colors = MaterialTheme.customColors
    val medalColor = when (rank) {
        1    -> Color(0xFFFFD700)
        2    -> Color(0xFFC0C0C0)
        3    -> Color(0xFFCD7F32)
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
            style = MaterialTheme.customTextStyles.buttonSmall,
            color = medalColor,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = "응원봉 #${result.wandId.toString(16).uppercase()}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${score}점",
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary
        )
    }
}
