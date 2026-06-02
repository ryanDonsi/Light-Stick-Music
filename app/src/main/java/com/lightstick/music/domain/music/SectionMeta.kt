package com.lightstick.music.domain.music

/**
 * 섹션 메타데이터 — UI 오버레이 표시 및 섹션별 이펙트 생성에 사용.
 * SectionDetector.Section 의 직렬화 가능한 경량 버전.
 */
data class SectionMeta(
    val startMs: Long,
    val endMs: Long,
    val type: SectionDetector.SectionType,
    val changeStrength: SectionDetector.ChangeStrength,
    val beatMs: Long,
    val beatConfidence: Float
)
