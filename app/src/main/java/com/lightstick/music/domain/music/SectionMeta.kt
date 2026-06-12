package com.lightstick.music.domain.music

/**
 * 섹션 메타데이터 — UI 오버레이 표시 및 섹션별 이펙트 생성에 사용.
 * SectionDetectorV1 이 계산한 구간 특성값을 모두 포함한다.
 */
data class SectionMeta(
    val startMs: Long,
    val endMs: Long,
    val type: SectionDetector.SectionType,
    val changeStrength: SectionDetector.ChangeStrength,
    val beatMs: Long,
    val beatConfidence: Float,
    // 구간 특성값 (SectionDetectorV1.FeatureWindow → SectionDetector.Section 에서 전달)
    val energy: Float       = 0f,  // 평균 전체 에너지 (0~1)
    val peakEnergy: Float   = 0f,  // 구간 최대 에너지 (0~1)
    val lowRatio: Float     = 0f,  // 저역(~897Hz) / 전체 에너지 비율
    val midRatio: Float     = 0f,  // 중역(585~3020Hz) / 전체 에너지 비율
    val highRatio: Float    = 0f,  // 고역(3.6kHz↑) / 전체 에너지 비율 (여성보컬 존재감)
    val onsetDensity: Float = 0f,  // 비트 밀집도 (0~1, novelty > 0.12 비율)
    val periodicity: Float  = 0f,  // 주기성 강도 (0~1)
    // 곡 단위 스타일 — 첫 번째 섹션에만 채워지며 나머지는 null
    val musicStyle: MusicStyleClassifier.MusicStyle? = null
)
