package com.lightstick.music.ui.components.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.types.EffectType

// ──────────────────────────────────────────────────────────────────────────────
// 앨범 아트 오버레이 이펙트 뱃지 — 공통 컴포넌트
//
// 구조: [ 이펙트명(Text) | 원형(Dot) ]
//   - 원형을 항상 오른쪽 끝에 고정 → 이펙트명이 바뀌어도 원형 위치 불변
//   - Alignment.BottomEnd 에 배치해 사용
// ──────────────────────────────────────────────────────────────────────────────

private fun effectTypeName(effectType: EffectType?): String = when (effectType) {
    EffectType.ON     -> "ON"
    EffectType.OFF    -> "OFF"
    EffectType.BLINK  -> "BLINK"
    EffectType.STROBE -> "STROBE"
    EffectType.BREATH -> "BREATH"
    null              -> ""
}

/**
 * 앨범 아트 오버레이 이펙트 뱃지
 *
 * - 이펙트명(Text) 왼쪽 + LightStick 색상 원형 오른쪽 고정
 * - 원형이 항상 우측 끝에 위치하여 이펙트명이 바뀌어도 원형이 흔들리지 않음
 *
 * @param transmission 현재 TIMELINE_EFFECT BleTransmissionEvent
 * @param modifier 위치 지정 (보통 Alignment.BottomEnd + padding)
 */
@Composable
fun EffectOverlayBadge(
    transmission: BleTransmissionEvent,
    modifier: Modifier = Modifier,
    maxLabelLength: Int? = null  // null=전체 표시, 정수=최대 글자 수 (예: 2 → "BLINK"→"BL")
) {
    val dotColor  = transmission.color?.toComposeColor() ?: Color.White
    val labelText = effectTypeName(transmission.effectType).let { name ->
        if (maxLabelLength != null && name.length > maxLabelLength)
            name.take(maxLabelLength)
        else name
    }

    // maxLabelLength=null: 내용에 맞게 자동 크기
    // maxLabelLength=정수:  고정폭 (너비 = 14*length + 24 dp)
    val widthModifier = if (maxLabelLength != null)
        Modifier.width((14 * maxLabelLength + 24).dp)
    else
        Modifier

    Row(
        modifier = modifier
            .then(widthModifier)
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 이펙트명 — 왼쪽 (텍스트 길이가 바뀌어도 원형은 우측 고정)
        if (labelText.isNotEmpty()) {
            Text(
                text      = labelText,
                style     = MaterialTheme.typography.labelSmall,
                color     = Color.White,
                fontSize  = 10.sp,
                textAlign = TextAlign.End,
                maxLines  = 1,
                softWrap  = false,
                // maxLabelLength=null: weight 없이 자연 크기 → 배지가 내용에 맞게 축소
                // maxLabelLength=정수: weight(1f)로 고정폭 내 오른쪽 정렬
                modifier  = if (maxLabelLength != null) Modifier.weight(1f) else Modifier
            )
        }

        // LightStick 색상 원형 — 오른쪽 고정
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor.copy(alpha = 1f), CircleShape)
        )
    }
}