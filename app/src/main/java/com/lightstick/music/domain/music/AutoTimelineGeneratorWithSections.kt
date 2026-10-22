package com.lightstick.music.domain.music

import com.lightstick.music.domain.music.sectiondetector.meta.SectionMeta

/**
 * SectionMeta(섹션 메타데이터, sectiondetector.meta 패키지)도 함께 생성해 반환하는 생성기가
 * 구현하는 확장 인터페이스. PrecomputeAutoTimelinesUseCase 가 섹션 메타데이터를 저장할 수
 * 있도록 한다.
 */
interface AutoTimelineGeneratorWithSections {
    /**
     * 타임라인 프레임과 섹션 메타데이터를 동시에 반환한다.
     * AutoTimelineGenerator.generate() 의 확장 버전.
     */
    fun generateWithSections(
        musicPath: String,
        musicId: Int,
        paletteSize: Int
    ): Pair<List<Pair<Long, ByteArray>>, List<SectionMeta>>
}
