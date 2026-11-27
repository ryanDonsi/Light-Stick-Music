package com.lightstick.music.core.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lightstick.music.domain.music.PlaybackEngine
import com.lightstick.music.ui.activity.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 음악 재생 서비스 (Media3 MediaSessionService).
 *
 * [PlaybackEngine] 의 플레이어로 MediaSession 을 만든다. 알림, 잠금화면, 블루투스 헤드셋,
 * 자동차 조작은 Media3 가 처리하고, 재생 중이면 서비스를 포그라운드로 유지해 Activity 가
 * 끝나도 음악과 이펙트가 계속된다(B1). 예전의 MusicEffectService + 브로드캐스트 리시버 +
 * 명령 버스는 이 서비스로 대체됐다.
 *
 * 세션 조작(재생/일시정지/시크/다음/이전)은 [EngineForwardingPlayer] 가 엔진 함수로 넘겨,
 * 화면에서 누른 것과 똑같이 이펙트 일시정지·재동기화가 함께 일어나게 한다.
 */
@AndroidEntryPoint
@UnstableApi
class PlaybackService : MediaSessionService() {

    @Inject lateinit var engine: PlaybackEngine

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, EngineForwardingPlayer(engine))
            .setSessionActivity(createContentIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // 플레이어는 엔진(앱 전역)이 계속 갖고 있으므로 세션만 해제한다
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "music")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 세션에서 들어온 조작을 엔진으로 보낸다. 엔진은 내부의 실제 플레이어를 직접 쓰므로 재귀하지 않는다.
     * 엔진이 곡 목록을 관리하므로 다음/이전 명령을 항상 열어 둔다.
     */
    private class EngineForwardingPlayer(
        private val engine: PlaybackEngine
    ) : ForwardingPlayer(engine.player) {

        override fun play() = engine.play()

        override fun pause() = engine.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) engine.play() else engine.pause()
        }

        override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = engine.seekTo(positionMs)

        override fun seekToNext() = engine.playNext()

        override fun seekToNextMediaItem() = engine.playNext()

        override fun seekToPrevious() = engine.playPrevious()

        override fun seekToPreviousMediaItem() = engine.playPrevious()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
    }
}
