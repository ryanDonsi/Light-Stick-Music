package com.lightstick.music.domain.music

interface AutoTimelineGenerator {
    fun generate(musicPath: String, musicId: Int, paletteSize: Int): List<Pair<Long, ByteArray>>
}
