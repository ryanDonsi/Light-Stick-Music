package com.lightstick.music.ui.components.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lightstick.music.R
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.TransmissionSource
import com.lightstick.music.domain.music.sectiondetector.meta.SectionMeta
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 *  Music Player Card (글라스모피즘 + Figma 디자인)
 *
 *  수정 사항: **색상만 theme로 교체, UI는 그대로 유지**
 *
 * [추가] latestTransmission 파라미터
 *   → TIMELINE_EFFECT 수신 시 앨범 이미지 우측 하단에 오버레이 뱃지 표시
 *      (LightStick 색상 원형 + 이펙트명)
 */
@Composable
fun MusicPlayerCard(
    musicItem: MusicItem?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    latestTransmission: BleTransmissionEvent? = null,
    sections: List<SectionMeta>? = null,
    isSectionOverlayEnabled: Boolean = false,
    colorFamily: String? = null,
    /**
     * true면 카드가 주어진 높이를 꽉 채우고, 앨범 이미지는 남는 공간에 맞춘 정사각형으로 그린다
     * (태블릿 패널용 — 하단이 잘리지 않고 이미지·뱃지·오버레이가 같은 크기로 맞는다).
     * 호출 쪽에서 [modifier]로 높이(weight 등)를 정해 줘야 한다.
     */
    fitHeight: Boolean = false
) {
    if (musicItem == null) {
        EmptyMusicCard(modifier = modifier)
        return
    }

    val hasMusic = musicItem.filePath.isNotEmpty()

    if (fitHeight) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .shadow(
                    elevation    = 20.dp,
                    shape        = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.customColors.shadowCard,
                    spotColor    = MaterialTheme.customColors.shadowCard
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.onSurface.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 아래 제목·컨트롤이 필요한 높이를 먼저 차지하고, 남는 높이 안에서 가로·세로 중 작은 쪽에 맞춘 정사각형
                BoxWithConstraints(
                    modifier         = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val side = minOf(maxWidth, maxHeight)
                    AlbumArt(
                        musicItem               = musicItem,
                        currentPosition         = currentPosition,
                        duration                = duration,
                        latestTransmission      = latestTransmission,
                        sections                = sections,
                        isSectionOverlayEnabled = isSectionOverlayEnabled,
                        colorFamily             = colorFamily,
                        modifier                = Modifier.size(side)
                    )
                }
                TrackInfoAndControls(
                    musicItem        = musicItem,
                    hasMusic         = hasMusic,
                    isPlaying        = isPlaying,
                    currentPosition  = currentPosition,
                    duration         = duration,
                    onPrevClick      = onPrevClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick      = onNextClick,
                    onSeekTo         = onSeekTo
                )
            }
        }
        return
    }

    Column(
        modifier              = modifier.fillMaxWidth(),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation    = 20.dp,
                    shape        = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.customColors.shadowCard,
                    spotColor    = MaterialTheme.customColors.shadowCard
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.onSurface.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 32.dp, vertical = 32.dp)
        ) {
            Column(
                modifier            = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlbumArt(
                    musicItem               = musicItem,
                    currentPosition         = currentPosition,
                    duration                = duration,
                    latestTransmission      = latestTransmission,
                    sections                = sections,
                    isSectionOverlayEnabled = isSectionOverlayEnabled,
                    colorFamily             = colorFamily,
                    modifier                = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .heightIn(max = 280.dp)
                )

                TrackInfoAndControls(
                    musicItem        = musicItem,
                    hasMusic         = hasMusic,
                    isPlaying        = isPlaying,
                    currentPosition  = currentPosition,
                    duration         = duration,
                    onPrevClick      = onPrevClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick      = onNextClick,
                    onSeekTo         = onSeekTo
                )
            }
        }
    }
}

/**
 * 앨범 이미지 + 이펙트 뱃지 + 섹션 정보 오버레이.
 * 크기는 [modifier]로 받는다 — 이미지·뱃지·오버레이가 모두 이 상자 안에서 같은 크기로 그려진다.
 */
@Composable
private fun AlbumArt(
    musicItem: MusicItem,
    currentPosition: Long,
    duration: Long,
    latestTransmission: BleTransmissionEvent?,
    sections: List<SectionMeta>?,
    isSectionOverlayEnabled: Boolean,
    colorFamily: String?,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.background)
    ) {
        AsyncImage(
            model              = musicItem.albumArtPath,
            contentDescription = "Album Art",
            contentScale       = ContentScale.Crop,
            error              = painterResource(id = R.drawable.ic_music_note),
            fallback           = painterResource(id = R.drawable.ic_music_note),
            modifier           = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
        )

        val isEffectActive = latestTransmission?.source == TransmissionSource.EFX_EFFECT
                || latestTransmission?.source == TransmissionSource.TIMELINE_EFFECT
        if (isEffectActive) {
            EffectOverlayBadge(
                transmission = latestTransmission,
                modifier     = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }

        if (isSectionOverlayEnabled && !sections.isNullOrEmpty()) {
            SectionInfoOverlay(
                sections          = sections,
                currentPositionMs = currentPosition,
                durationMs        = duration,
                modifier          = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
                colorFamily       = colorFamily
            )
        }
    }
}

/** 제목·아티스트·탐색 바·재생 버튼 (앨범 이미지 아래 영역) */
@Composable
private fun TrackInfoAndControls(
    musicItem: MusicItem,
    hasMusic: Boolean,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text      = musicItem.title,
                style     = MaterialTheme.typography.titleLarge,
                color     = MaterialTheme.colorScheme.onSurface,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.weight(1f, fill = false)
            )

            if (musicItem.hasEffect) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape    = RoundedCornerShape(4.dp),
                    color    = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .height(18.dp)
                        .widthIn(min = 40.dp)
                ) {
                    Box(
                        modifier         = Modifier,
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text  = "EFX",
                            style = MaterialTheme.customTextStyles.badgeMedium,
                            color = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text      = musicItem.artist,
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis
        )

        MusicSeekBar(
            currentPosition = currentPosition,
            duration        = duration,
            onSeekTo        = onSeekTo
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .height(64.dp)
                .wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            PressableIconButton(
                onClick            = onPrevClick,
                enabled            = hasMusic,
                normalIcon         = R.drawable.ic_player_back_n,
                pressedIcon        = R.drawable.ic_player_back_p,
                contentDescription = "Previous",
                size               = 48.dp
            )
            PressableIconButton(
                onClick            = onPlayPauseClick,
                enabled            = hasMusic,
                normalIcon         = if (isPlaying) R.drawable.ic_player_pause_n
                else R.drawable.ic_player_play_n,
                pressedIcon        = if (isPlaying) R.drawable.ic_player_pause_p
                else R.drawable.ic_player_play_p,
                contentDescription = if (isPlaying) "Pause" else "Play",
                size               = 64.dp
            )
            PressableIconButton(
                onClick            = onNextClick,
                enabled            = hasMusic,
                normalIcon         = R.drawable.ic_player_forward_n,
                pressedIcon        = R.drawable.ic_player_forward_p,
                contentDescription = "Next",
                size               = 48.dp
            )
        }
    }
}

@Composable
private fun EmptyMusicCard(modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation    = 20.dp,
                    shape        = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.customColors.shadowCard,
                    spotColor    = MaterialTheme.customColors.shadowCard
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.customColors.onSurface.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.customColors.onSurface.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text      = "재생중인 음악이 없습니다",
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.surfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PressableIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    normalIcon: Int,
    pressedIcon: Int,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(999.dp))
            .clickable(
                enabled           = enabled,
                interactionSource = interactionSource,
                indication        = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter            = painterResource(
                id = if (isPressed && enabled) pressedIcon else normalIcon
            ),
            contentDescription = contentDescription,
            modifier           = Modifier.fillMaxSize(),
            contentScale       = ContentScale.Fit
        )
    }
}
