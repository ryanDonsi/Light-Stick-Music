package com.dongsitech.lightstickmusicdemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import com.dongsitech.lightstickmusicdemo.R
import java.io.File

class MusicPlayerService : Service() {

    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager

    private var currentFile: File? = null
    private var isPrepared = false

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val filePath = intent?.getStringExtra("file_path") ?: return START_NOT_STICKY
        currentFile = File(filePath)

        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
        }
        mediaPlayer.reset()

        mediaPlayer.setDataSource(filePath)
        mediaPlayer.prepare()
        isPrepared = true
        mediaPlayer.start()

        updateMetadata()
        updateNotification()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaPlayer.release()
        mediaSession.release()
        super.onDestroy()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MediaPlayerService")
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                if (!mediaPlayer.isPlaying && isPrepared) {
                    mediaPlayer.start()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification()
                }
            }

            override fun onPause() {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification()
                }
            }

            override fun onStop() {
                stopSelf()
            }
        })

        mediaPlayer = MediaPlayer()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun updateMetadata() {
        val fileName = currentFile?.name ?: "알 수 없음"
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, fileName)
                .build()
        )
    }

    private fun updateNotification() {
        val isPlaying = mediaPlayer.isPlaying

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause, "일시정지",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play, "재생",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY
                )
            )
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("음악 재생 중")
            .setContentText(currentFile?.name ?: "알 수 없음")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(mediaSession.controller.sessionActivity)
            .addAction(playPauseAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val name = "Music Playback"
        val description = "Notification for background music playback"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            this.description = description
        }

        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
