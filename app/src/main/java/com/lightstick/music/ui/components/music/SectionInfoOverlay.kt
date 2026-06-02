package com.lightstick.music.ui.components.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.domain.music.MusicStyleClassifier
import com.lightstick.music.domain.music.SectionDetector
import com.lightstick.music.domain.music.SectionMeta

/**
 * 앨범 이미지 위에 겹쳐 표시되는 섹션 정보 오버레이.
 *
 * 구성:
 *  - 하단 반투명 패널: 현재 섹션 타입 + 구간 특성값 전체
 *  - 섹션 바: 전체 구간을 비율 분할한 색상 막대 + 현재 위치 포인터
 *
 * 표시 특성:
 *  type, changeStrength, beatMs, beatConfidence,
 *  energy(avg/peak), lowRatio, midRatio, onsetDensity, periodicity
 */
@Composable
fun SectionInfoOverlay(
    sections: List<SectionMeta>,
    currentPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    if (sections.isEmpty() || durationMs <= 0L) return

    val cur        = sections.firstOrNull {
        currentPositionMs >= it.startMs && currentPositionMs < it.endMs
    } ?: sections.last()
    val musicStyle = sections.firstOrNull()?.musicStyle

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // ── Music Style 배지 ──────────────────────────────
            if (musicStyle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = "STYLE",
                        color      = Color.White.copy(alpha = 0.50f),
                        fontSize   = 9.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(musicStyleColor(musicStyle).copy(alpha = 0.25f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text       = musicStyleLabel(musicStyle),
                            color      = musicStyleColor(musicStyle),
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── 섹션 타입 + 변화강도 ──────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(sectionColor(cur.type))
                )
                Text(
                    text       = sectionLabel(cur.type),
                    color      = Color.White,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text     = "∆${changeLabel(cur.changeStrength)}",
                    color    = changeColor(cur.changeStrength),
                    fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text     = "${cur.startMs / 1000}s–${cur.endMs / 1000}s",
                    color    = Color.White.copy(alpha = 0.55f),
                    fontSize = 9.sp
                )
            }

            // ── 에너지 / 피크 ────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureChip("E.avg", cur.energy)
                FeatureChip("E.peak", cur.peakEnergy)
                FeatureChip("beat", "${cur.beatMs}ms")
                FeatureChip("conf", cur.beatConfidence)
            }

            // ── 스펙트럼 밴드 비율 ────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureChip("low%", cur.lowRatio)
                FeatureChip("mid%", cur.midRatio)
                FeatureChip("high%", cur.highRatio)   // 3.6kHz↑ 여성보컬 존재감
            }

            // ── 리듬 특성 ────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureChip("onset", cur.onsetDensity)
                FeatureChip("period", cur.periodicity)
            }

            // ── 섹션 바 ──────────────────────────────────────
            SectionBar(
                sections          = sections,
                durationMs        = durationMs,
                currentPositionMs = currentPositionMs,
                modifier          = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun FeatureChip(label: String, value: Float) {
    FeatureChipImpl(label = label, display = "%.2f".format(value))
}

@Suppress("SameParameterValue")
@Composable
private fun FeatureChip(label: String, raw: String) {
    FeatureChipImpl(label = label, display = raw)
}

@Composable
private fun FeatureChipImpl(label: String, display: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
        Text(text = display, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionBar(
    sections: List<SectionMeta>,
    durationMs: Long,
    currentPositionMs: Long,
    modifier: Modifier = Modifier
) {
    val sectionColors = sections.map { sectionColor(it.type) }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        for (i in sections.indices) {
            val s = sections[i]
            val startX = w * (s.startMs.toFloat() / durationMs)
            val endX   = w * (s.endMs.toFloat()   / durationMs)
            drawRect(
                color  = sectionColors[i].copy(alpha = 0.80f),
                topLeft = androidx.compose.ui.geometry.Offset(startX, 0f),
                size   = androidx.compose.ui.geometry.Size(endX - startX, h)
            )
        }
        // 현재 위치 포인터
        val posX = w * (currentPositionMs.toFloat() / durationMs)
        drawRect(
            color   = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(posX - 1f, 0f),
            size    = androidx.compose.ui.geometry.Size(2f, h)
        )
    }
}

private fun sectionLabel(type: SectionDetector.SectionType): String = when (type) {
    SectionDetector.SectionType.INTRO  -> "INTRO"
    SectionDetector.SectionType.VERSE  -> "VERSE"
    SectionDetector.SectionType.CHORUS -> "CHORUS"
    SectionDetector.SectionType.BRIDGE -> "BRIDGE"
    SectionDetector.SectionType.END    -> "END"
}

private fun changeLabel(s: SectionDetector.ChangeStrength): String = when (s) {
    SectionDetector.ChangeStrength.NONE   -> "NONE"
    SectionDetector.ChangeStrength.MEDIUM -> "MED"
    SectionDetector.ChangeStrength.STRONG -> "STRONG"
}

private fun changeColor(s: SectionDetector.ChangeStrength): Color = when (s) {
    SectionDetector.ChangeStrength.NONE   -> Color.White.copy(alpha = 0.45f)
    SectionDetector.ChangeStrength.MEDIUM -> Color(0xFFFFD54F)
    SectionDetector.ChangeStrength.STRONG -> Color(0xFFFF7043)
}

private fun musicStyleLabel(style: MusicStyleClassifier.MusicStyle): String = when (style) {
    MusicStyleClassifier.MusicStyle.EDM        -> "EDM"
    MusicStyleClassifier.MusicStyle.DANCE_POP  -> "DANCE POP"
    MusicStyleClassifier.MusicStyle.HIPHOP_RNB -> "HIPHOP / R&B"
    MusicStyleClassifier.MusicStyle.BALLAD     -> "BALLAD"
    MusicStyleClassifier.MusicStyle.ROCK       -> "ROCK"
    MusicStyleClassifier.MusicStyle.POP        -> "POP"
}

private fun musicStyleColor(style: MusicStyleClassifier.MusicStyle): Color = when (style) {
    MusicStyleClassifier.MusicStyle.EDM        -> Color(0xFF00E5FF)  // Cyan
    MusicStyleClassifier.MusicStyle.DANCE_POP  -> Color(0xFFE040FB)  // Purple
    MusicStyleClassifier.MusicStyle.HIPHOP_RNB -> Color(0xFFFFD740)  // Amber
    MusicStyleClassifier.MusicStyle.BALLAD     -> Color(0xFF69F0AE)  // Green
    MusicStyleClassifier.MusicStyle.ROCK       -> Color(0xFFFF5252)  // Red
    MusicStyleClassifier.MusicStyle.POP        -> Color(0xFF40C4FF)  // Light Blue
}

fun sectionColor(type: SectionDetector.SectionType): Color = when (type) {
    SectionDetector.SectionType.INTRO  -> Color(0xFF9C27B0)  // Purple
    SectionDetector.SectionType.VERSE  -> Color(0xFF2196F3)  // Blue
    SectionDetector.SectionType.CHORUS -> Color(0xFFF44336)  // Red
    SectionDetector.SectionType.BRIDGE -> Color(0xFFFF9800)  // Orange
    SectionDetector.SectionType.END    -> Color(0xFF26C6DA)  // Teal
}
