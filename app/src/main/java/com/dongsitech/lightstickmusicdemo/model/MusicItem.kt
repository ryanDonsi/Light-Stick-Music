package com.dongsitech.lightstickmusicdemo.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MusicItem(
    val title: String,
    val artist: String,
    val filePath: String,
    val albumArtPath: String? = null,
    val hasEffect: Boolean = false
) : Parcelable


