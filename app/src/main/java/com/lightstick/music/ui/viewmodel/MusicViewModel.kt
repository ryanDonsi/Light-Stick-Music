package com.lightstick.music.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lightstick.music.core.service.PlaybackService
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.domain.music.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 음악 화면용 ViewModel. 재생 상태와 동작은 앱 전역 [PlaybackEngine] 이 갖고 있고,
 * 여기서는 화면에 그대로 노출한다(B1). 그래서 Activity 가 끝나도 재생·이펙트는 계속된다.
 *
 * 화면이 떠 있는 동안 [PlaybackService] 에 MediaController 로 연결해 서비스를 띄워 둔다.
 * 재생 중에 화면이 닫히면 Media3 가 서비스를 포그라운드로 유지하고, 재생 중이 아니면 서비스가 정리된다.
 */
@HiltViewModel
@UnstableApi
class MusicViewModel @Inject constructor(
    application: Application,
    private val engine: PlaybackEngine
) : AndroidViewModel(application) {

    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(
            application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java))
        ).buildAsync()

    val isAutoModeEnabled       = engine.isAutoModeEnabled
    val musicList               = engine.musicList
    val isMusicLoading          = engine.isMusicLoading
    val nowPlaying              = engine.nowPlaying
    val isPlaying               = engine.isPlaying
    val currentPosition         = engine.currentPosition
    val duration                = engine.duration
    val autoTimelineProgress    = engine.autoTimelineProgress
    val latestTransmission      = engine.latestTransmission
    val currentSections         = engine.currentSections
    val currentColorFamily      = engine.currentColorFamily
    val isSectionOverlayEnabled = engine.isSectionOverlayEnabled

    fun loadMusic(force: Boolean = false) = engine.loadMusic(force)
    fun playMusic(item: MusicItem) = engine.playMusic(item)
    fun togglePlayPause() = engine.togglePlayPause()
    fun playNext() = engine.playNext()
    fun playPrevious() = engine.playPrevious()
    fun seekTo(position: Long) = engine.seekTo(position)
    fun toggleAutoMode(): Boolean = engine.toggleAutoMode()
    fun toggleSectionOverlay(): Boolean = engine.toggleSectionOverlay()

    override fun onCleared() {
        // 연결만 끊는다. 플레이어·서비스는 재생 중이면 계속 돈다.
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
