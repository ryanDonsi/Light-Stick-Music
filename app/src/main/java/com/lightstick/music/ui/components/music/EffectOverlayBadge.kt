package com.lightstick.music.ui.components.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.types.EffectType

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
    maxLabelLength: Int? = null
) {
    EffectOverlayBadge(
        effectType     = transmission.effectType,
        color          = transmission.color,
        modifier       = modifier,
        maxLabelLength = maxLabelLength
    )
}

/** 배지가 실제로 보여 주는 값. 전송 이벤트는 매번 새 객체(시각 포함)라 이 값으로 바뀜 여부를 판단한다. */
private data class BadgeContent(val effectType: EffectType?, val color: com.lightstick.types.Color?)

/**
 * 최근 전송을 람다로 받아 이 안에서만 읽는다. EFX/타임라인 이펙트일 때만 그리고,
 * 이펙트 종류나 색이 바뀔 때만 다시 그린다(전송이 잦아도 상위 화면은 재구성되지 않음).
 */
@Composable
fun EffectOverlayBadgeSlot(
    transmission: () -> BleTransmissionEvent?,
    modifier: Modifier = Modifier,
    maxLabelLength: Int? = null
) {
    val content by remember(transmission) {
        derivedStateOf {
            transmission()
                ?.takeIf {
                    it.source == TransmissionSource.EFX_EFFECT ||
                        it.source == TransmissionSource.TIMELINE_EFFECT
                }
                ?.let { BadgeContent(it.effectType, it.color) }
        }
    }
    content?.let {
        EffectOverlayBadge(
            effectType     = it.effectType,
            color          = it.color,
            modifier       = modifier,
            maxLabelLength = maxLabelLength
        )
    }
}

@Composable
fun EffectOverlayBadge(
    effectType: EffectType?,
    color: com.lightstick.types.Color?,
    modifier: Modifier = Modifier,
    maxLabelLength: Int? = null
) {
    val dotColor  = color?.toComposeColor() ?: Color.White
    val labelText = effectTypeName(effectType).let { name ->
        if (maxLabelLength != null && name.length > maxLabelLength)
            name.take(maxLabelLength)
        else name
    }

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
        if (labelText.isNotEmpty()) {
            Text(
                text      = labelText,
                style     = MaterialTheme.typography.labelSmall,
                color     = Color.White,
                fontSize  = 10.sp,
                textAlign = TextAlign.End,
                maxLines  = 1,
                softWrap  = false,
                modifier  = if (maxLabelLength != null) Modifier.weight(1f) else Modifier
            )
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor.copy(alpha = 1f), CircleShape)
        )
    }
}
