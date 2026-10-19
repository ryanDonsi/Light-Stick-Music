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
        // 이 팔레트가 선택한 색 계열의 이름(V1: PaletteFamily.name, 예 "PURPLE"/"RED"/
        // "ORANGE_GOLD"/"TEAL"/"BLUE". V0처럼 계열 개념이 없는 구현체는 "NONE") — UI의
        // Effect Info 패널에서 장르 옆에 어떤 색상 테이블이 선택됐는지 보여주는 용도.
        val colorFamily: String,
        // ON_PULSE의 BG만 패턴 A/B(섹션 인덱스 홀짝)로 구분해서 담는다 — FG는 고정색이
        // 아니라 colorGroup을 bar마다 순환시켜 쓰므로(colorsForEngine 참고) 여기엔
        // 들어있지 않다.
        val onPulseBg: List<LSColor>,
        val breathSet: ColorSet,
        val chorusBg: LSColor,
        val colorGroup: List<LSColor>,
        // VERSE의 BLINK(고에너지 bar) 전용 색상 — BG는 고정(cStep6을 cDeep처럼 밝기만
        // 낮춘 값), FG는 bar마다 verseBlinkFgCycle을 순환한다.
        val verseBlinkBg: LSColor,
        val verseBlinkFgCycle: List<LSColor>,
        // CHORUS 전용 순환색. colorGroup(5개, 빈도 균등화용)을 그대로 쓰면 사이사이 간격이
        // 좁은 색도 섞여서 "탁탁 튀는" 느낌이 약해진다 — CHORUS는 간격이 가장 큰 3개만
        // 골라 전환마다 또렷이 달라지도록 white 포함 4개로 미리 계산해 둔다.
        val chorusColors: List<LSColor>
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
     * @param musicStyle 음악 스타일(장르) — 장르에 어울리는 색 계열(팔레트 세트) 후보군을
     *                   고르는 데 쓰인다. 같은 장르 후보군 안에서는 seed로 결정론적으로 하나를
     *                   고르므로, 같은 곡은 항상 같은 팔레트가 나오지만 장르가 다르면 분위기도
     *                   달라진다. 미사용 구현체(V0)는 무시해도 된다.
     * @return 4색 팔레트 (흰색 포함)
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

