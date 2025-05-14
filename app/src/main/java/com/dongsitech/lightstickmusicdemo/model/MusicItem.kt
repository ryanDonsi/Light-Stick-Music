package com.dongsitech.lightstickmusicdemo.model

data class MusicItem(
    val title: String,
    val artist: String,
    val filePath: String,
    val albumArtPath: String? = null
)
