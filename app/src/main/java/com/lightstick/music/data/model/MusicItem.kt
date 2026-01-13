package com.lightstick.music.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MusicItem(
    val title: String,
    val artist: String,
    val filePath: String,
    val albumArtPath: String? = null,
    val hasEffect: Boolean = false,
    val duration: Long = 0
) : Parcelable