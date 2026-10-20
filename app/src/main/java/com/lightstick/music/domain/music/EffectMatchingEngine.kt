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
    // 공통 데이터 클래스 및 enum
    // =========================================================================

    enum class FgEngine { ON_PULSE, BLINK, BREATH, ON_TRANSIT_ROTATE, OFF_TRANSIT, FREEZE }
    enum class ChangeLevel { MEDIUM, STRONG }

    data class Palette(
        val black: LSColor, val white: LSColor,
        // 이 팔레트가 선택한 색 계열의 이름(V1: PaletteSet.label, 예 "PURPLE"/"RED"/
        // "ORANGE_GOLD"/"TEAL"/"BLUE". V0처럼 계열 개념이 없는 구현체는 "NONE") — UI의
        // Effect Info 패널에서 장르 옆에 어떤 색상 테이블이 선택됐는지 보여주는 용도.
        val colorFamily: String,
        // 순환형 이펙트(ON_PULSE, VERSE의 BREATH/BLINK, CHORUS)가 bar/beat마다 번갈아
        // 쓰는 메인 색 3개.
        val mainColors: List<LSColor>,
        // 고정 포인트 색 — 숨쉬듯 한 색을 유지하는 BREATH(비VERSE)·FREEZE의 메인색이자,
        // BLINK에서 ON_PULSE와 구분되는 배경 강조색으로 쓰인다.
        val pointColor: LSColor,
        // 전 이펙트가 공통으로 쓰는 배경색.
        val bgColor: LSColor
    )

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
     * @param musicStyle 음악 스타일(장르) — 장르에 어울리는 색 계열(팔레트 세트) 후보군을
     *                   고르는 데 쓰인다. 같은 장르 후보군 안에서는 seed로 결정론적으로 하나를
     *                   고르므로, 같은 곡은 항상 같은 팔레트가 나오지만 장르가 다르면 분위기도
     *                   달라진다. 미사용 구현체(V0)는 무시해도 된다.
     * @return 메인 3색 + 포인트 1색 + 배경 1색(+흑백)으로 구성된 팔레트
     */
    fun buildPalette(seed: Int, musicStyle: MusicStyleClassifier.MusicStyle = MusicStyleClassifier.MusicStyle.POP): Palette

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
     * @param fullEnv 원본 전체 에너지 envelope (섹션 내부 bar 단위 에너지 변화를 보는 용도, 미사용 구현체는 무시해도 됨)
     * @param hopMs fullEnv 한 칸이 나타내는 시간(ms)
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
        beatsPerBar: Int = 4,
        fullEnv: List<Float> = emptyList(),
        hopMs: Long = 10L
    ): List<Pair<Long, ByteArray>>
}

