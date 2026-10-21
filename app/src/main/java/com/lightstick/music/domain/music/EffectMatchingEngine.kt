package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor

/**
 * EffectMatchingEngine — 이펙트 매칭 및 팔레트 추출 엔진.
 * V0: 비트마다 ON 매칭(단순). V1: 완전 구현(복잡/고급).
 * AutoTimelineGenerator가 EffectMatchingEngineRouter를 통해 버전을 선택해 호출한다.
 */
interface EffectMatchingEngine {

    enum class FgEngine { ON_PULSE, BLINK, BREATH, ON_TRANSIT_ROTATE, OFF_TRANSIT, FREEZE }
    enum class ChangeLevel { MEDIUM, STRONG }

    // 한 이펙트가 쓰는 색 — fgCycle: bar/beat 인덱스로 순환시킬 FG 목록, bg: 고정 배경색.
    data class EffectColors(val fgCycle: List<LSColor>, val bg: LSColor)

    data class Palette(
        val black: LSColor, val white: LSColor,
        // 선택된 색 계열 이름(예 "PURPLE"/"RED"/"ORANGE_GOLD"/"TEAL"/"BLUE", 없으면 "NONE").
        val colorFamily: String,
        // 이 팔레트를 고를 때 쓴 장르 — GenreEngineCatalog가 섹션별 엔진 매칭을 장르별로
        // 고르는 데도 그대로 재사용한다.
        val musicStyle: MusicStyleClassifier.MusicStyle,
        val onPulse: EffectColors,          // ON_PULSE(VERSE, 비발라드)
        val blink: EffectColors,            // BLINK(VERSE 고에너지 bar, 비발라드)
        val chorus: EffectColors,           // ON_TRANSIT_ROTATE(CHORUS)
        val breathVerse: EffectColors,      // BREATH(VERSE, 발라드/HIPHOP_RNB)
        val breathBridgeBallad: EffectColors, // BREATH(BRIDGE, 발라드)
        val breathDefault: EffectColors,    // BREATH(그 외 — INTRO, BRIDGE 비발라드)
        val freeze: EffectColors            // FREEZE(BREAK)
    )

    data class SectionGroup(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val annotatedBeats: List<SectionDetector.AnnotatedBeat>
    )

    /** 팔레트 생성. seed(musicId)와 musicStyle(장르)로 결정론적으로 색 계열을 고른다. */
    fun buildPalette(seed: Int, musicStyle: MusicStyleClassifier.MusicStyle = MusicStyleClassifier.MusicStyle.POP): Palette

    /** 섹션 그룹으로부터 이펙트 프레임(타임스탬프→페이로드) 생성. */
    fun buildFrames(
        palette: Palette,
        sectionGroups: List<SectionGroup>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4,
        fullEnv: List<Float> = emptyList(),
        hopMs: Long = 10L
    ): List<Pair<Long, ByteArray>>
}

