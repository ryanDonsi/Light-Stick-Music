package com.lightstick.music.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.ui.activity.MainActivity
import com.lightstick.music.R
import com.lightstick.music.data.model.MusicItem
import com.lightstick.music.core.receiver.MusicActionReceiver

/**
 * Foreground service that:
 *  - Holds a framework MediaSession (system integration: lock screen/car/headset).
 *  - Shows a MediaStyle notification with explicit action buttons (Prev/Play-Pause/Next).
 *  - Forwards both MediaSession callbacks and notification actions to a BroadcastReceiver.
 *
 * NOTE: Assumes SDK_INT >= 31 (Android 12+). Uses IntentCompat for Tiramisu-safe parcelable access.
 */
class MusicEffectService : Service() {

    companion object {
        const val CHANNEL_ID = "music_effect_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREV = "ACTION_PREV"
        const val ACTION_SEEK = "ACTION_SEEK"
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"

        /** 서비스가 살아 있는지 — 살아 있으면 알림 갱신은 startService 로 보낸다 ([ServiceController]) */
        @Volatile var isRunning: Boolean = false
            private set

        /** 알림 큰 아이콘 최대 크기(px) — 원본 앨범아트를 그대로 디코드하지 않는다 */
        private const val LARGE_ICON_MAX_PX = 256
    }

    private var isForeground = false
    private var cachedArtPath: String? = null
    private var cachedArtBitmap: Bitmap? = null

    private lateinit var mediaSession: MediaSession
    private var lastIsPlaying: Boolean = true

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaSession = MediaSession(this, "MusicSession")
        setupMediaSessionCallback()
        createNotificationChannel()
    }

    @UnstableApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val musicItem: MusicItem = IntentCompat.getParcelableExtra(
            intent,
            "musicItem",
            MusicItem::class.java
        ) ?: return START_NOT_STICKY

        lastIsPlaying = intent.getBooleanExtra("isPlaying", true)
        val position = intent.getLongExtra("position", 0L)
        val duration = intent.getLongExtra("duration", 0L)

        updateMetadata(musicItem, duration)
        updatePlaybackState(lastIsPlaying, position)

        val notification = buildNotification(musicItem, lastIsPlaying)
        if (isForeground) {
            // 이미 포그라운드면 알림만 갱신 (startForeground 재호출 불필요)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        }
        return START_STICKY
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val reqCode = when (action) {
            ACTION_PREV -> 1
            ACTION_PLAY_PAUSE -> 2
            ACTION_NEXT -> 3
            ACTION_SEEK -> 4
            else -> 0
        }
        val i = Intent(this, MusicActionReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            this,
            reqCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Build a Notification.Action (API 23+ Icon-based builder). */
    private fun buildAction(iconResId: Int, title: String, pi: PendingIntent): Notification.Action {
        val icon = Icon.createWithResource(this, iconResId)
        return Notification.Action.Builder(icon, title, pi).build()
    }

    @UnstableApi
    private fun buildNotification(musicItem: MusicItem, isPlaying: Boolean): Notification {
        val albumBitmap: Bitmap = loadAlbumArt(musicItem.albumArtPath)
            ?: getBitmapFromDrawable(this, R.drawable.ic_music_note)

        val playPauseIconRes =
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val prevAction = buildAction(
            android.R.drawable.ic_media_previous, "Prev", actionPendingIntent(ACTION_PREV)
        )
        val playPauseAction = buildAction(
            playPauseIconRes, playPauseTitle, actionPendingIntent(ACTION_PLAY_PAUSE)
        )
        val nextAction = buildAction(
            android.R.drawable.ic_media_next, "Next", actionPendingIntent(ACTION_NEXT)
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(albumBitmap)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setContentIntent(createContentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    @UnstableApi
    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("navigateTo", "music")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateMetadata(musicItem: MusicItem, duration: Long) {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, musicItem.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, musicItem.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setState(state, position, 1.0f)
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun setupMediaSessionCallback() {
        mediaSession.setCallback(object : MediaSession.Callback() {
            // 토글이 아니라 각각 재생/일시정지 — 재생 중 헤드셋이 PLAY 를 보내면 멈추던 문제 방지
            override fun onPlay() { sendActionToReceiver(ACTION_PLAY) }
            override fun onPause() { sendActionToReceiver(ACTION_PAUSE) }
            override fun onSkipToNext() { sendActionToReceiver(ACTION_NEXT) }
            override fun onSkipToPrevious() { sendActionToReceiver(ACTION_PREV) }
            override fun onSeekTo(pos: Long) {
                sendActionToReceiver(ACTION_SEEK) { putExtra("seekPosition", pos) }
            }
        })
        mediaSession.isActive = true
    }

    private fun sendActionToReceiver(action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, MusicActionReceiver::class.java).apply {
            this.action = action
            extras?.invoke(this)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "음악 및 이펙트 재생", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LightStick 음악과 LED 효과 재생용 채널"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /** 같은 곡이면 캐시를 쓰고, 새 곡이면 알림 아이콘 크기로 줄여서 디코드한다 */
    private fun loadAlbumArt(path: String?): Bitmap? {
        if (path == null) return null
        if (path == cachedArtPath) return cachedArtBitmap
        val bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= LARGE_ICON_MAX_PX &&
                bounds.outHeight / (sample * 2) >= LARGE_ICON_MAX_PX
            ) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
        cachedArtPath = path
        cachedArtBitmap = bitmap
        return bitmap
    }

    override fun onDestroy() {
        isRunning = false
        isForeground = false
        super.onDestroy()
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getBitmapFromDrawable(context: Context, drawableId: Int): Bitmap {
        val drawable: Drawable = context.getDrawable(drawableId) ?: return createBitmap(100, 100)
        if (drawable is BitmapDrawable) return drawable.bitmap

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 100
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 100

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
