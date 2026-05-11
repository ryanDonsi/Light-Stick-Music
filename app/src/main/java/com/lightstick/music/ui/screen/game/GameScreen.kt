package com.lightstick.music.ui.screen.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.TopBarCentered
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
import com.lightstick.music.ui.viewmodel.GameViewModel

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val selectedMode          by viewModel.selectedMode.collectAsState()
    val selectedDifficulty    by viewModel.selectedDifficulty.collectAsState()
    val gameState             by viewModel.gameState.collectAsState()
    val bleState              by viewModel.bleConnectionState.collectAsState()
    val isGameModeSupported   by viewModel.isGameModeSupported.collectAsState()
    val countdownSeconds      by viewModel.countdownSeconds.collectAsState()
    val partialResults        by viewModel.partialResults.collectAsState()
    val playingElapsedSeconds by viewModel.playingElapsedSeconds.collectAsState()
    val playingMaxSeconds     by viewModel.playingMaxSeconds.collectAsState()

    val toastState = rememberToastState()
    var showStopDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.connectIfNeeded() }

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
                actionText = null,
                onActionClick = { showStopDialog = true },
                actionTextColor = MaterialTheme.colorScheme.error
            )

            GameStatusBanner(
                bleState = bleState,
                gameState = gameState,
                elapsedSeconds = playingElapsedSeconds,
                maxSeconds = playingMaxSeconds,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 4.dp)
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
                        onModeSelect = viewModel::selectMode,
                        onDifficultySelect = viewModel::selectDifficulty,
                        onStart = viewModel::startGame,
                        bleConnected = bleState is GameBleManager.ConnectionState.Connected,
                        isGameModeSupported = isGameModeSupported
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

        CustomToast(
            message = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = toastState::dismiss,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ModeSelectionContent(
    selectedMode: GameMode?,
    selectedDifficulty: GameDifficulty,
    bleConnected: Boolean,
    isGameModeSupported: Boolean,
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
        Text(
            text = "모드 선택",
            style = MaterialTheme.customTextStyles.bodyAccent,
            color = colors.surfaceVariant
        )

        GameMode.entries.forEach { mode ->
            Column {
                GameModeCard(
                    mode = mode,
                    selected = selectedMode == mode,
                    onClick = { onModeSelect(mode) }
                )
                AnimatedVisibility(
                    visible = selectedMode == mode,
                    enter = fadeIn(tween(200)) + expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(tween(150)) + shrinkVertically(animationSpec = tween(150))
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (bleConnected && !isGameModeSupported) {
            Text(
                text = "연결된 응원봉이 게임 모드를 지원하지 않습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        BaseButton(
            text = "게임 시작",
            onClick = onStart,
            enabled = selectedMode != null && bleConnected && isGameModeSupported,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )
    }
}

@Composable
private fun GameStatusBanner(
    bleState: GameBleManager.ConnectionState,
    gameState: GameState,
    elapsedSeconds: Int,
    maxSeconds: Int,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.customColors
    val isPlaying = gameState is GameState.Playing

    val bgColor = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> Color(0xFF1A3A1A)
        is GameBleManager.ConnectionState.Connecting -> Color(0xFF2A2A0A)
        is GameBleManager.ConnectionState.Error      -> Color(0xFF3A1A1A)
        else                                         -> Color(0xFF252525)
    }
    val dotColor = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> Color(0xFF4CAF50)
        is GameBleManager.ConnectionState.Connecting -> colors.secondary
        else                                         -> colors.surfaceVariant
    }
    val labelText = when (bleState) {
        is GameBleManager.ConnectionState.Connected  -> "응원봉 연결됨"
        is GameBleManager.ConnectionState.Connecting -> "연결 중..."
        is GameBleManager.ConnectionState.Error      -> bleState.message
        else                                         -> "응원봉 미연결"
    }

    val remaining = if (maxSeconds > 0) (maxSeconds - elapsedSeconds).coerceAtLeast(0) else 0
    val progress = if (maxSeconds > 0) remaining.toFloat() / maxSeconds else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "timer_progress"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
            if (isPlaying) {
                val m = remaining / 60
                val s = remaining % 60
                val timeText = if (m > 0) "${m}분 ${s}초" else "${s}초"
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (remaining <= 10) MaterialTheme.colorScheme.error else colors.primary
                )
            }
        }
        if (isPlaying && maxSeconds > 0) {
            CommonProgressBar(
                progress = animatedProgress,
                progressColor = colors.primary,
                trackColor = colors.onSurface.copy(alpha = 0.15f),
                height = 4.dp,
                modifier = Modifier.fillMaxWidth()
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
    val bgColor = if (selected) colors.primary.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.05f)
    val borderColor = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.14f)
    val borderWidth = if (selected) 2.dp else 1.dp

    val modeIcon = when (mode) {
        GameMode.SPEED_REACTION -> Icons.Filled.Bolt
        GameMode.TEMPO          -> Icons.Filled.MusicNote
        GameMode.TEAM_BATTLE    -> Icons.Filled.Groups
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Transparent
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(borderWidth, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
            val isSelected  = diff == selected
            val bgColor     = if (isSelected) colors.primary.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.05f)
            val borderColor = if (isSelected) colors.primary else colors.onSurface.copy(alpha = 0.14f)
            val borderWidth = if (isSelected) 2.dp else 1.dp
            val textColor   = if (isSelected) colors.onSurface else colors.surfaceVariant

            Card(
                onClick = { onSelect(diff) },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor),
                border = BorderStroke(borderWidth, borderColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = diff.nameKr,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

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

@Composable
private fun PlayingContent(
    mode: GameMode?,
    partialResults: List<WandResult>,
    onStopClick: () -> Unit
) {
    val colors = MaterialTheme.customColors
    val showRanking = (mode == GameMode.SPEED_REACTION || mode == GameMode.TEMPO) &&
            partialResults.isNotEmpty()

    if (showRanking) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = colors.primary,
                    strokeWidth = 3.dp
                )
                Text(
                    text = "게임 진행 중",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.primary
                )
                if (mode != null) {
                    Text(
                        text = mode.nameKr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.surfaceVariant
                    )
                }
            }

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
                    RankRow(rank = index + 1, result = result)
                }
            }

            BaseButton(
                text = "게임 중지",
                onClick = onStopClick,
                style = ButtonStyle.ERROR,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = colors.primary,
                    strokeWidth = 4.dp
                )
                Text(
                    text = "게임 진행 중",
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.primary
                )
                if (mode != null) {
                    Text(
                        text = mode.nameKr,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.surfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                BaseButton(
                    text = "게임 중지",
                    onClick = onStopClick,
                    style = ButtonStyle.ERROR,
                    modifier = Modifier.width(200.dp).height(48.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultContent(summary: GameResultSummary, onPlayAgain: () -> Unit) {
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
    var showAllResults by remember { mutableStateOf(false) }
    val colors = MaterialTheme.customColors
    val winner = summary.soloWinner

    if (winner != null) {
        WinnerBanner(
            label = "우승!",
            wandId = "응원봉 ${winner.wandId.toString(16).uppercase().padStart(4, '0')}",
            score = "${winner.redScore}점"
        )
    }

    Text(
        text = "참여 응원봉: ${summary.totalWandCount}개",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textTertiary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End
    )

    Text(
        text = "순위표",
        style = MaterialTheme.customTextStyles.bodyAccent,
        color = colors.surfaceVariant
    )

    val displayedResults = if (showAllResults) summary.rankedResults
                           else summary.rankedResults.take(3)
    displayedResults.forEachIndexed { index, result ->
        RankRow(rank = index + 1, result = result)
    }

    if (!showAllResults && summary.rankedResults.size > 3) {
        TextButton(
            onClick = { showAllResults = true },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)
        ) {
            Text(
                text = "모든 결과 보기",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TeamResultContent(summary: GameResultSummary) {
    val colors = MaterialTheme.customColors

    val winnerLabel = when (summary.teamWinner) {
        TeamWinner.RED  -> "Red팀 승리!"
        TeamWinner.BLUE -> "Blue팀 승리!"
        TeamWinner.DRAW -> "무승부!"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.85f), colors.primaryContainer))
            )
            .padding(vertical = 28.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(text = "🏆", fontSize = 44.sp)
            Text(
                text = winnerLabel,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Red",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onPrimary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${summary.totalRedScore}",
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.onPrimary
                    )
                    Text(
                        text = "점",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimary.copy(alpha = 0.7f)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(72.dp)
                        .background(colors.onPrimary.copy(alpha = 0.3f))
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Blue",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onPrimary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${summary.totalBlueScore}",
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.onPrimary
                    )
                    Text(
                        text = "점",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    Text(
        text = "참여 응원봉: ${summary.totalWandCount}개",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textTertiary,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TeamScoreBar(
            redScore = summary.totalRedScore,
            blueScore = summary.totalBlueScore
        )
    }
}

@Composable
private fun WinnerBanner(label: String, wandId: String, score: String) {
    val colors = MaterialTheme.customColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.85f), colors.primaryContainer))
            )
            .padding(vertical = 24.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "🏆", fontSize = 40.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onPrimary
            )
            Text(
                text = wandId,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onPrimary.copy(alpha = 0.9f)
            )
            Text(
                text = score,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimary
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
private fun RankRow(rank: Int, result: WandResult) {
    val colors = MaterialTheme.customColors
    val isWinner = rank == 1
    val medalColor = when (rank) {
        1    -> Color(0xFFFFD700)
        2    -> Color(0xFFC0C0C0)
        3    -> Color(0xFFCD7F32)
        else -> colors.surfaceVariant
    }
    val bgColor = if (isWinner) colors.primary.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.05f)
    val borderColor = if (isWinner) colors.primary else colors.onSurface.copy(alpha = 0.14f)
    val borderWidth = if (isWinner) 2.dp else 1.dp
    val wandHex = result.wandId.toString(16).uppercase().padStart(4, '0')

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Transparent
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(borderWidth, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${rank}위",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = medalColor,
                modifier = Modifier.width(48.dp)
            )
            Text(
                text = "응원봉 $wandHex",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${result.redScore}점",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
        }
    }
}
