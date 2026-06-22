package com.lightstick.music.domain.music

import com.lightstick.types.Color as LSColor

/**
 * EffectMatchingEngine — 이펙트 매칭 및 팔레트 추출 엔진
 *
 * 역할:
 * - 비트/섹션 정보 → LED 이펙트 프레임 변환
 * - 팔레트 생성 (seed 기반 색상 생성)
 * - 섹션별 이펙트 엔진 할당 (FgEngine)
 * - 프레임 빌딩 (색상, 이펙트 타입, period, randomDelay 등)
 *
 * 버전:
 * - V0: 비트마다 ON 매칭 (단순)
 * - V1: V8 기반 이펙트 매칭 (복잡, 고급)
 *
 * 사용:
 * - AutoTimelineGenerator에서 각 버전을 선택해 호출
 * - EffectMatchingEngineRouter를 통해 버전 분기
 */
interface EffectMatchingEngine {

    // =========================================================================
    // 공통 데이터 클래스
    // =========================================================================

    data class Palette(
        val black: LSColor, val white: LSColor,
        val onPulseSets: List<ColorSet>,
        val blinkSets: List<ColorSet>,
        val strokeSets: List<ColorSet>,
        val breathSet: ColorSet,
        val bridgeSets: List<ColorSet>,
        val chorusBg: LSColor,
        val colorGroup: List<LSColor>
    )

    data class ColorSet(val fg: LSColor, val bg: LSColor)

    data class SectionGroup(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val annotatedBeats: List<SectionDetector.AnnotatedBeat>
    )

    // =========================================================================
    // 핵심 메서드
    // =========================================================================

    /**
     * 팔레트 생성
     * @param seed musicId 또는 임의의 시드값
     * @return 4색 팔레트 (흰색 포함)
     */
    fun buildPalette(seed: Int): Palette

    /**
     * 섹션 그룹으로부터 이펙트 프레임 생성
     *
     * @param palette 색상 팔레트
     * @param sectionGroups 섹션 정보 (감지기 출력)
     * @param beatTimesMs 비트 타임스탐프
     * @param durationMs 음악 길이
     * @param isBalladMode 발라드 모드 여부 (음악 스타일)
     * @param finalOffMs 마지막 음악 위치 (종료 후 묵음 제거)
     * @param downbeatMs 강박 위치
     * @param beatsPerBar 마디당 비트 수 (예: 4/4박자 → 4)
     * @return 타임스탐프→페이로드 쌍 리스트
     */
    fun buildFrames(
        palette: Palette,
        sectionGroups: List<SectionGroup>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long = 0L,
        beatsPerBar: Int = 4
    ): List<Pair<Long, ByteArray>>
}

