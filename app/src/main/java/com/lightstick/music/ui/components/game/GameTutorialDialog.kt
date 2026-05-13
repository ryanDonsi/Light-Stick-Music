package com.lightstick.music.ui.components.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lightstick.music.data.model.GameMode
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.foundation.Canvas

// ─── Wand color constants ─────────────────────────────────────────────────────

private val WandOff    = Color(0xFF444466)
private val WandWhite  = Color(0xFFFFFFFF)
private val WandGreen  = Color(0xFF4ADE80)
private val WandRed    = Color(0xFFF87171)
private val WandBlue   = Color(0xFF60A5FA)
private val WandGrip   = Color(0xFF23233A)
private val WandNeck   = Color(0xFF2A2A4A)
private val WandStripe = Color(0xFF33334A)

private val Mode1Steps = listOf("① 대기", "② LED ON", "③ 흔들기", "④ 득점", "우승")
private val Mode2Steps = listOf("① ON", "② 흔들기", "③ OFF", "④ 반복", "5연속!")
private val Mode3Steps = listOf("① 팀 배정", "② 신호", "③ 판정", "④ 라운드", "결과")

private val StageBackground = Color(0xFF0D0D1A)
private val OverlayBackground = Color(0xCC0A0A1A)

// ─── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun GameTutorialDialog(mode: GameMode, onDismiss: () -> Unit) {
    var playKey by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .wrapContentHeight()
        ) {
            // Glass glow layers — matches BaseDialog style
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.05f; scaleX = 1.020f; scaleY = 1.020f }
                    .background(Color.White, RoundedCornerShape(16.dp))
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 0.12f; scaleX = 1.015f; scaleY = 1.015f }
                    .background(Color.White, RoundedCornerShape(16.dp))
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF111111)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // ── Title bar ──────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${mode.nameKr} 튜토리얼",
                            style = MaterialTheme.customTextStyles.topBarLarge,
                            color = MaterialTheme.customColors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "닫기",
                                tint = MaterialTheme.customColors.surfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.customColors.divider,
                        thickness = 1.dp
                    )

                    // ── Tutorial content (keyed for replay restart) ────────
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        key(playKey) {
                            when (mode) {
                                GameMode.SPEED_REACTION ->
                                    SpeedReactionTutorial(onReplay = { playKey++ })
                                GameMode.TEMPO ->
                                    TempoTutorial(onReplay = { playKey++ })
                                GameMode.TEAM_BATTLE ->
                                    TeamBattleTutorial(onReplay = { playKey++ })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Step progress bar ────────────────────────────────────────────────────────

@Composable
private fun TutorialStepBar(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.customColors
    Row(
        modifier = modifier
            .height(IntrinsicSize.Max)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
    ) {
        steps.forEachIndexed { i, step ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.outline)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        when {
                            i < currentStep -> Color(0xFF1A3A1A)
                            i == currentStep -> colors.primary.copy(alpha = 0.18f)
                            else -> Color.Transparent
                        }
                    )
                    .padding(vertical = 7.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        i < currentStep -> WandGreen
                        i == currentStep -> colors.primary
                        else -> colors.textTertiary
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─── Wand shape ───────────────────────────────────────────────────────────────

@Composable
private fun TutorialWand(
    headColor: Color,
    label: String = "",
    score: Int = -1,
    rotation: Float = 0f
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8888AA)
            )
        }
        Canvas(
            modifier = Modifier
                .size(width = 24.dp, height = 76.dp)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val w = size.width
            val headR = w * 0.44f
            val cx = w / 2f
            val headCy = headR + 1f

            if (headColor != WandOff) {
                drawCircle(
                    color = headColor.copy(alpha = 0.22f),
                    radius = headR + 5f,
                    center = Offset(cx, headCy)
                )
            }
            drawCircle(headColor, radius = headR, center = Offset(cx, headCy))
            drawCircle(
                color = if (headColor == WandWhite) Color.White.copy(0.65f)
                        else Color.White.copy(0.18f),
                radius = headR * 0.28f,
                center = Offset(cx - headR * 0.28f, headCy - headR * 0.28f)
            )
            val neckTop = headCy + headR
            drawRect(
                WandNeck,
                topLeft = Offset(cx - w * 0.09f, neckTop),
                size = Size(w * 0.18f, w * 0.18f)
            )
            val gripTop = neckTop + w * 0.18f
            drawRoundRect(
                WandGrip,
                topLeft = Offset(cx - w * 0.14f, gripTop),
                size = Size(w * 0.28f, size.height - gripTop),
                cornerRadius = CornerRadius(w * 0.14f)
            )
            val stripeY = gripTop + (size.height - gripTop) * 0.38f
            drawRoundRect(
                WandStripe,
                topLeft = Offset(cx - w * 0.14f, stripeY),
                size = Size(w * 0.28f, (size.height - gripTop) * 0.10f),
                cornerRadius = CornerRadius(w * 0.10f)
            )
        }
        if (score >= 0) {
            val winner = score >= 5
            Box(
                modifier = Modifier
                    .background(
                        if (winner) Color(0xFF1A3A1A) else Color(0xFF2A2A3E),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${score}점",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (winner) WandGreen else Color(0xFF8888AA)
                )
            }
        }
    }
}

// ─── Legend row item ──────────────────────────────────────────────────────────

@Composable
private fun LegendItem(color: Color, label: String, bordered: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
                .then(
                    if (bordered)
                        Modifier.border(1.dp, Color(0xFF666666), CircleShape)
                    else Modifier
                )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.customColors.textTertiary
        )
    }
}

// ─── Replay button ────────────────────────────────────────────────────────────

@Composable
private fun ReplayButton(onClick: () -> Unit) {
    val colors = MaterialTheme.customColors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.primaryContainer)
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 11.dp)
    ) {
        Text(
            text = "↺  다시 보기",
            style = MaterialTheme.customTextStyles.buttonSmall,
            color = colors.onPrimary
        )
    }
}

// ─── Mode 1: Speed Reaction ──────────────────────────────────────────────────

@Composable
private fun SpeedReactionTutorial(onReplay: () -> Unit) {
    val colors = MaterialTheme.customColors
    val labels = listOf("A", "B", "C")

    val headColors = remember { mutableStateListOf(WandOff, WandOff, WandOff) }
    val scores     = remember { mutableStateListOf(0, 0, 0) }
    val rotations  = remember { mutableStateListOf(0f, 0f, 0f) }
    var step       by remember { mutableIntStateOf(0) }
    var message    by remember { mutableStateOf("준비 중...") }
    var subMsg     by remember { mutableStateOf("") }
    var winnerText by remember { mutableStateOf("") }
    var finished   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(715)
        val sc = mutableListOf(0, 0, 0)
        while (true) {
            step = 0
            headColors[0] = WandOff; headColors[1] = WandOff; headColors[2] = WandOff
            message = "대기 중..."; subMsg = ""
            delay(930 + Random.nextLong(1360))

            step = 1
            headColors[0] = WandWhite; headColors[1] = WandWhite; headColors[2] = WandWhite
            message = "LED ON — 흔드세요!"; subMsg = ""
            val wi = Random.nextInt(3)
            delay(430 + Random.nextLong(715))

            step = 2
            launch {
                listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f).forEach { r ->
                    rotations[wi] = r; delay(80)
                }
            }
            delay(545)

            step = 3
            sc[wi]++; scores[wi] = sc[wi]; headColors[wi] = WandGreen
            message = "응원봉 ${labels[wi]} +1점 (${sc[wi]}/5)"; subMsg = ""

            if (sc[wi] >= 5) {
                step = 4
                winnerText = "응원봉 ${labels[wi]} 우승! 🏆"
                message = winnerText; subMsg = "5회 먼저 달성!"
                finished = true; break
            }
            delay(1575)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "LED가 켜지는 순간 응원봉을 흔드세요. 먼저 5회 성공한 응원봉이 우승!",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        TutorialStepBar(Mode1Steps, step, Modifier.fillMaxWidth())

        // Stage + overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(StageBackground)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    labels.indices.forEach { i ->
                        TutorialWand(headColors[i], labels[i], scores[i], rotations[i])
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
            Column(modifier = Modifier.matchParentSize()) {
                AnimatedVisibility(
                    visible = finished,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(285)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OverlayBackground, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = winnerText,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                textAlign = TextAlign.Center
                            )
                            ReplayButton(onReplay)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            LegendItem(WandOff, "대기")
            LegendItem(WandWhite, "LED ON", bordered = true)
            LegendItem(WandGreen, "성공")
        }
    }
}

// ─── Mode 2: Tempo ────────────────────────────────────────────────────────────

@Composable
private fun TempoTutorial(onReplay: () -> Unit) {
    val colors = MaterialTheme.customColors

    var headColor  by remember { mutableStateOf(WandOff) }
    var rotation   by remember { mutableFloatStateOf(0f) }
    var streak     by remember { mutableIntStateOf(0) }
    var step       by remember { mutableIntStateOf(0) }
    var message    by remember { mutableStateOf("리듬을 맞춰보세요") }
    var finished   by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(715)
        var stk = 0
        while (true) {
            step = 0; headColor = WandWhite; message = "LED ON — 흔드세요!"
            delay(970)

            val hit = Random.nextFloat() > 0.22f
            step = 1
            launch {
                listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f).forEach { r ->
                    rotation = r; delay(80)
                }
            }
            delay(455)

            if (hit) {
                stk++; streak = stk; headColor = WandGreen
                if (stk >= 5) {
                    step = 4; message = "5연속 성공! 우승! 🎉"
                    finished = true; break
                }
                step = 3; message = "성공! ($stk/5)"
                delay(715)
                step = 2; headColor = WandOff; message = "LED OFF"
                delay(1000)
            } else {
                stk = 0; streak = 0; headColor = WandRed; message = "MISS — 연속 초기화!"
                delay(970)
                step = 2; headColor = WandOff
                delay(1000)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "LED 리듬에 맞춰 흔드세요. 5회 연속 성공하면 우승! MISS 시 횟수 초기화.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        TutorialStepBar(Mode2Steps, step, Modifier.fillMaxWidth())

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(StageBackground)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TutorialWand(headColor, "W", rotation = rotation)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "연속 성공: $streak / 5",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
            Column(modifier = Modifier.matchParentSize()) {
                AnimatedVisibility(
                    visible = finished,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(285)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OverlayBackground, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "5연속 성공! 🎉",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                textAlign = TextAlign.Center
                            )
                            ReplayButton(onReplay)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            LegendItem(WandOff, "OFF")
            LegendItem(WandWhite, "ON → 흔들기", bordered = true)
            LegendItem(WandRed, "MISS")
            LegendItem(WandGreen, "성공")
        }
    }
}

// ─── Mode 3: Team Battle ──────────────────────────────────────────────────────

@Composable
private fun TeamBattleTutorial(onReplay: () -> Unit) {
    val colors = MaterialTheme.customColors

    val redColors    = remember { mutableStateListOf(WandOff, WandOff) }
    val blueColors   = remember { mutableStateListOf(WandOff, WandOff) }
    val redRots      = remember { mutableStateListOf(0f, 0f) }
    val blueRots     = remember { mutableStateListOf(0f, 0f) }
    var redScore     by remember { mutableIntStateOf(0) }
    var blueScore    by remember { mutableIntStateOf(0) }
    var step         by remember { mutableIntStateOf(0) }
    var message      by remember { mutableStateOf("준비 중...") }
    var subMsg       by remember { mutableStateOf("라운드 0 / 5") }
    var winnerText   by remember { mutableStateOf("") }
    var finished     by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(715)
        step = 0
        redColors[0] = WandRed;  redColors[1] = WandRed
        blueColors[0] = WandBlue; blueColors[1] = WandBlue
        message = "팀 배정 완료!"
        delay(1575)

        var rs = 0; var bs = 0
        for (rnd in 1..5) {
            step = 1; subMsg = "라운드 $rnd / 5"
            redColors[0] = WandOff; redColors[1] = WandOff
            blueColors[0] = WandOff; blueColors[1] = WandOff
            delay(1145)

            val sigRed = Random.nextBoolean()
            if (sigRed) {
                redColors[0] = WandRed; redColors[1] = WandRed
                message = "홍팀 신호!"
            } else {
                blueColors[0] = WandBlue; blueColors[1] = WandBlue
                message = "청팀 신호!"
            }
            delay(1070)

            step = 2
            val hit = Random.nextFloat() > 0.15f
            if (sigRed) {
                launch {
                    listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f).forEach { r ->
                        redRots[0] = r; redRots[1] = r; delay(80)
                    }
                }
                delay(645)
                if (hit) {
                    rs++; redScore = rs
                    redColors[0] = WandGreen; redColors[1] = WandGreen
                    message = "홍팀 성공! +1"
                } else {
                    redColors[0] = WandOff; redColors[1] = WandOff
                    message = "홍팀 MISS"
                }
                blueColors[0] = WandOff; blueColors[1] = WandOff
            } else {
                launch {
                    listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f).forEach { r ->
                        blueRots[0] = r; blueRots[1] = r; delay(80)
                    }
                }
                delay(645)
                if (hit) {
                    bs++; blueScore = bs
                    blueColors[0] = WandGreen; blueColors[1] = WandGreen
                    message = "청팀 성공! +1"
                } else {
                    blueColors[0] = WandOff; blueColors[1] = WandOff
                    message = "청팀 MISS"
                }
                redColors[0] = WandOff; redColors[1] = WandOff
            }
            step = 3
            delay(1285)
        }

        step = 4
        winnerText = when {
            rs > bs -> "홍팀 승리! 🔴"
            bs > rs -> "청팀 승리! 🔵"
            else    -> "무승부! 🤝"
        }
        message = winnerText
        subMsg = "홍팀 ${rs}점  vs  청팀 ${bs}점"
        finished = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "내 팀 색 신호에만 흔드세요. 5라운드 후 득점이 많은 팀이 우승!",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        TutorialStepBar(Mode3Steps, step, Modifier.fillMaxWidth())

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(StageBackground)
                .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "홍팀",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = WandRed
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TutorialWand(redColors[0], "R1", rotation = redRots[0])
                            TutorialWand(redColors[1], "R2", rotation = redRots[1])
                        }
                        Text(
                            "${redScore}점",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WandRed
                        )
                    }
                    Text(
                        "vs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF444466),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "청팀",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = WandBlue
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TutorialWand(blueColors[0], "B1", rotation = blueRots[0])
                            TutorialWand(blueColors[1], "B2", rotation = blueRots[1])
                        }
                        Text(
                            "${blueScore}점",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WandBlue
                        )
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
            Column(modifier = Modifier.matchParentSize()) {
                AnimatedVisibility(
                    visible = finished,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(285)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OverlayBackground, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = winnerText,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                textAlign = TextAlign.Center
                            )
                            ReplayButton(onReplay)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            LegendItem(WandOff, "대기")
            LegendItem(WandRed, "홍팀 신호")
            LegendItem(WandBlue, "청팀 신호")
            LegendItem(WandGreen, "성공")
        }
    }
}
