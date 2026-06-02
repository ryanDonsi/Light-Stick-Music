package com.lightstick.music.ui.components.music

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

    val cur = sections.firstOrNull {
        currentPositionMs >= it.startMs && currentPositionMs < it.endMs
    } ?: sections.last()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
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
                FeatureChip("beat", cur.beatMs.toString() + "ms", raw = true)
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
    FeatureChip(label = label, raw = "%.2f".format(value))
}

@Composable
private fun FeatureChip(label: String, raw: String = "", value: Float = 0f) {
    val display = if (raw.isNotEmpty()) raw else "%.2f".format(value)
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
    BoxWithConstraints(modifier = modifier) {
        val totalWidth = maxWidth

        Row(modifier = Modifier.fillMaxSize()) {
            sections.forEach { section ->
                val fraction = ((section.endMs - section.startMs).toFloat() / durationMs.toFloat())
                    .coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(fraction.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(sectionColor(section.type).copy(alpha = 0.80f))
                )
            }
        }

        // 현재 위치 포인터
        if (durationMs > 0L) {
            val posX = totalWidth * (currentPositionMs.toFloat() / durationMs.toFloat())
            Box(
                modifier = Modifier
                    .offset(x = posX - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White)
            )
        }
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

fun sectionColor(type: SectionDetector.SectionType): Color = when (type) {
    SectionDetector.SectionType.INTRO  -> Color(0xFF9C27B0)  // Purple
    SectionDetector.SectionType.VERSE  -> Color(0xFF2196F3)  // Blue
    SectionDetector.SectionType.CHORUS -> Color(0xFFF44336)  // Red
    SectionDetector.SectionType.BRIDGE -> Color(0xFFFF9800)  // Orange
    SectionDetector.SectionType.END    -> Color(0xFF26C6DA)  // Teal
}
