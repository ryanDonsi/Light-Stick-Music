package com.lightstick.music.domain.music.effectmatchingengine.catalog

import com.lightstick.music.domain.music.genredetector.GenreDetector
import com.lightstick.music.domain.music.effectmatchingengine.EffectMatchingEngine
import com.lightstick.types.Color as LSColor

/**
 * 장르별 팔레트 세트 카탈로그 — EffectMatchingEngine이 쓰는 모든 색상 데이터를 관리한다.
 * seed(musicId)와 musicStyle(장르)로 완성된 Palette 하나를 결정론적으로 골라준다.
 */
object PaletteCatalog {

    // 장르별 이펙트 색상표(아래 <장르>_SET_<번호>_<이펙트> 상수들)가 쓰는 실측 RGB 스와치.
    private val PURPLE_MAGENTA = LSColor(212, 0, 255)  // 자주보라
    private val PURPLE_VIOLET  = LSColor(164, 0, 255)  // 보라
    private val PURPLE_INDIGO  = LSColor(116, 0, 255)  // 남보라
    private val PURPLE_NAVY    = LSColor(67, 0, 255)   // 남색

    private val RED_MAGENTA   = LSColor(255, 0, 249)  // 자홍
    private val RED_ROSE      = LSColor(255, 0, 155)  // 로즈
    private val RED_PURE      = LSColor(255, 0, 0)    // 순수 빨강
    private val RED_VERMILION = LSColor(255, 33, 0)   // 다홍

    private val ORANGE_GOLD_DEEP   = LSColor(255, 50, 0)   // 진한 다홍주황
    private val ORANGE_GOLD_ORANGE = LSColor(255, 117, 0)  // 주황
    private val ORANGE_GOLD_AMBER  = LSColor(255, 183, 0)  // 골드
    private val ORANGE_GOLD_LIME   = LSColor(255, 249, 0)  // 라임옐로우

    private val TEAL_YELLOWGREEN = LSColor(71, 255, 0)   // 연두
    private val TEAL_GREEN       = LSColor(0, 255, 36)   // 초록
    private val TEAL_MINT        = LSColor(0, 255, 146)  // 민트
    private val TEAL_CYAN        = LSColor(0, 255, 255)  // 청록(시안)

    private val BLUE_SEAFOAM = LSColor(0, 255, 161)  // 민트청록
    private val BLUE_SKYCYAN = LSColor(0, 232, 255)  // 하늘청록
    private val BLUE_AZURE   = LSColor(0, 116, 255)  // 파랑
    private val BLUE_PURE    = LSColor(0, 0, 255)    // 순수 파랑

    // 위 스와치들의 파생색 — 그 세트의 대표색과 같은 hue에서 명도만 48%로 낮춘 어두운 톤
    // (CHORUS/BREATH_VERSE/BREATH_DEFAULT의 bg — fg와 hue·명도 둘 다 달라야 또렷이 구분된다).
    private val PURPLE_MAGENTA_SHADE = LSColor(101, 0, 122)   // 자주보라 셰이드
    private val PURPLE_VIOLET_SHADE  = LSColor(78, 0, 122)    // 보라 셰이드
    private val PURPLE_INDIGO_SHADE  = LSColor(55, 0, 122)    // 남보라 셰이드
    private val PURPLE_NAVY_SHADE    = LSColor(32, 0, 122)    // 남색 셰이드

    private val RED_MAGENTA_SHADE   = LSColor(122, 0, 119)   // 자홍 셰이드
    private val RED_ROSE_SHADE      = LSColor(122, 0, 74)    // 로즈 셰이드
    private val RED_PURE_SHADE      = LSColor(122, 0, 0)     // 순수 빨강 셰이드
    private val RED_VERMILION_SHADE = LSColor(122, 15, 0)    // 다홍 셰이드

    private val ORANGE_GOLD_DEEP_SHADE   = LSColor(122, 23, 0)    // 진한 다홍주황 셰이드
    private val ORANGE_GOLD_ORANGE_SHADE = LSColor(122, 56, 0)    // 주황 셰이드
    private val ORANGE_GOLD_AMBER_SHADE  = LSColor(122, 87, 0)    // 골드 셰이드
    private val ORANGE_GOLD_LIME_SHADE   = LSColor(122, 119, 0)   // 라임옐로우 셰이드

    private val TEAL_YELLOWGREEN_SHADE = LSColor(34, 122, 0)    // 연두 셰이드
    private val TEAL_GREEN_SHADE       = LSColor(0, 122, 17)    // 초록 셰이드
    private val TEAL_MINT_SHADE        = LSColor(0, 122, 70)    // 민트 셰이드
    private val TEAL_CYAN_SHADE        = LSColor(0, 122, 122)   // 청록(시안) 셰이드

    private val BLUE_SEAFOAM_SHADE = LSColor(0, 122, 77)    // 민트청록 셰이드
    private val BLUE_SKYCYAN_SHADE = LSColor(0, 111, 122)   // 하늘청록 셰이드
    private val BLUE_AZURE_SHADE   = LSColor(0, 55, 122)    // 파랑 셰이드
    private val BLUE_PURE_SHADE    = LSColor(0, 0, 122)     // 순수 파랑 셰이드

    private val WHITE = LSColor(255, 255, 255)
    private val BLACK = LSColor(0, 0, 0)

    // ===== 이펙트별 색상표 (장르×세트×이펙트) =====
    // <장르>_SET_<번호>_<이펙트> 형태의 독립 상수. ONPULSE/CHORUS/BREATH_VERSE: fg가 bar/beat마다
    // 순환. BLINK: ONPULSE와 같은 fg 순환에 bg만 달리함. BREATH_DEFAULT/FREEZE: fg 고정 1색.
    // BREATH_BRIDGE_BALLAD: 발라드 BRIDGE 전용(fg 고정 1색 + bg 검정).

    // BALLAD_SET_1 = TEAL (rolePerm=[3, 0, 1, 2])
    private val BALLAD_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), BLACK)
    private val BALLAD_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT)
    private val BALLAD_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_YELLOWGREEN, TEAL_CYAN, TEAL_GREEN), TEAL_CYAN_SHADE)
    private val BALLAD_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT_SHADE)
    private val BALLAD_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), BLACK)
    private val BALLAD_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), TEAL_CYAN_SHADE)
    private val BALLAD_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), BLACK)

    // BALLAD_SET_2 = PURPLE (rolePerm=[3, 0, 1, 2])
    private val BALLAD_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), BLACK)
    private val BALLAD_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_INDIGO)
    private val BALLAD_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_MAGENTA, PURPLE_NAVY, PURPLE_VIOLET), PURPLE_NAVY_SHADE)
    private val BALLAD_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_INDIGO_SHADE)
    private val BALLAD_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), BLACK)
    private val BALLAD_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), PURPLE_NAVY_SHADE)
    private val BALLAD_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), BLACK)

    // BALLAD_SET_3 = BLUE (rolePerm=[3, 0, 1, 2])
    private val BALLAD_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLACK)
    private val BALLAD_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE)
    private val BALLAD_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_SEAFOAM, BLUE_PURE, BLUE_SKYCYAN), BLUE_PURE_SHADE)
    private val BALLAD_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE_SHADE)
    private val BALLAD_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLACK)
    private val BALLAD_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLUE_PURE_SHADE)
    private val BALLAD_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLACK)

    // EDM_SET_1 = RED (rolePerm=[0, 1, 2, 3])
    private val EDM_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), BLACK)
    private val EDM_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), RED_VERMILION)
    private val EDM_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_ROSE, RED_PURE, RED_MAGENTA), RED_MAGENTA_SHADE)
    private val EDM_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), RED_VERMILION_SHADE)
    private val EDM_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
    private val EDM_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_MAGENTA_SHADE)
    private val EDM_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

    // EDM_SET_2 = ORANGE_GOLD (rolePerm=[0, 1, 2, 3])
    private val EDM_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), BLACK)
    private val EDM_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME)
    private val EDM_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, ORANGE_GOLD_ORANGE, ORANGE_GOLD_LIME, ORANGE_GOLD_DEEP), ORANGE_GOLD_DEEP_SHADE)
    private val EDM_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME_SHADE)
    private val EDM_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)
    private val EDM_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), ORANGE_GOLD_DEEP_SHADE)
    private val EDM_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)

    // EDM_SET_3 = PURPLE (rolePerm=[0, 1, 2, 3])
    private val EDM_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), BLACK)
    private val EDM_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_NAVY)
    private val EDM_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_INDIGO, PURPLE_MAGENTA, PURPLE_NAVY), PURPLE_MAGENTA_SHADE)
    private val EDM_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_NAVY_SHADE)
    private val EDM_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)
    private val EDM_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), PURPLE_MAGENTA_SHADE)
    private val EDM_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)

    // DANCE_POP_SET_1 = RED (rolePerm=[2, 0, 1, 3])
    private val DANCE_POP_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), BLACK)
    private val DANCE_POP_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), RED_VERMILION)
    private val DANCE_POP_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_MAGENTA, RED_PURE, RED_ROSE), RED_PURE_SHADE)
    private val DANCE_POP_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), RED_VERMILION_SHADE)
    private val DANCE_POP_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
    private val DANCE_POP_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_PURE_SHADE)
    private val DANCE_POP_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

    // DANCE_POP_SET_2 = TEAL (rolePerm=[2, 0, 1, 3])
    private val DANCE_POP_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), BLACK)
    private val DANCE_POP_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN)
    private val DANCE_POP_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_YELLOWGREEN, TEAL_MINT, TEAL_GREEN), TEAL_MINT_SHADE)
    private val DANCE_POP_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN_SHADE)
    private val DANCE_POP_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)
    private val DANCE_POP_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), TEAL_MINT_SHADE)
    private val DANCE_POP_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)

    // DANCE_POP_SET_3 = BLUE (rolePerm=[2, 0, 1, 3])
    private val DANCE_POP_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLACK)
    private val DANCE_POP_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE)
    private val DANCE_POP_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_SEAFOAM, BLUE_AZURE, BLUE_SKYCYAN), BLUE_AZURE_SHADE)
    private val DANCE_POP_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE_SHADE)
    private val DANCE_POP_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)
    private val DANCE_POP_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLUE_AZURE_SHADE)
    private val DANCE_POP_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)

    // POP_SET_1 = PURPLE (rolePerm=[1, 0, 2, 3])
    private val POP_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), BLACK)
    private val POP_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_NAVY)
    private val POP_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_VIOLET_SHADE)
    private val POP_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_NAVY_SHADE)
    private val POP_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)
    private val POP_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), PURPLE_VIOLET_SHADE)
    private val POP_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)

    // POP_SET_2 = RED (rolePerm=[1, 0, 2, 3])
    private val POP_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), BLACK)
    private val POP_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), RED_VERMILION)
    private val POP_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_MAGENTA, RED_PURE, RED_ROSE), RED_ROSE_SHADE)
    private val POP_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), RED_VERMILION_SHADE)
    private val POP_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
    private val POP_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_ROSE_SHADE)
    private val POP_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

    // POP_SET_3 = ORANGE_GOLD (rolePerm=[1, 0, 2, 3])
    private val POP_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), BLACK)
    private val POP_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME)
    private val POP_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, ORANGE_GOLD_DEEP, ORANGE_GOLD_LIME, ORANGE_GOLD_ORANGE), ORANGE_GOLD_ORANGE_SHADE)
    private val POP_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME_SHADE)
    private val POP_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)
    private val POP_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), ORANGE_GOLD_ORANGE_SHADE)
    private val POP_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)

    // POP_SET_4 = TEAL (rolePerm=[1, 0, 2, 3])
    private val POP_SET_4_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), BLACK)
    private val POP_SET_4_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_CYAN)
    private val POP_SET_4_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_YELLOWGREEN, TEAL_MINT, TEAL_GREEN), TEAL_GREEN_SHADE)
    private val POP_SET_4_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_CYAN_SHADE)
    private val POP_SET_4_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)
    private val POP_SET_4_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), TEAL_GREEN_SHADE)
    private val POP_SET_4_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)

    // POP_SET_5 = BLUE (rolePerm=[1, 0, 2, 3])
    private val POP_SET_5_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLACK)
    private val POP_SET_5_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_PURE)
    private val POP_SET_5_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_SEAFOAM, BLUE_AZURE, BLUE_SKYCYAN), BLUE_SKYCYAN_SHADE)
    private val POP_SET_5_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_PURE_SHADE)
    private val POP_SET_5_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)
    private val POP_SET_5_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLUE_SKYCYAN_SHADE)
    private val POP_SET_5_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)

    // HIPHOP_RNB_SET_1 = BALLAD_SET_2(PURPLE) 재사용.
    private val HIPHOP_RNB_SET_1_ONPULSE = BALLAD_SET_2_ONPULSE
    private val HIPHOP_RNB_SET_1_BLINK = BALLAD_SET_2_BLINK
    private val HIPHOP_RNB_SET_1_CHORUS = BALLAD_SET_2_CHORUS
    private val HIPHOP_RNB_SET_1_BREATH_VERSE = BALLAD_SET_2_BREATH_VERSE
    private val HIPHOP_RNB_SET_1_BREATH_BRIDGE_BALLAD = BALLAD_SET_2_BREATH_BRIDGE_BALLAD
    private val HIPHOP_RNB_SET_1_BREATH_DEFAULT = BALLAD_SET_2_BREATH_DEFAULT
    private val HIPHOP_RNB_SET_1_FREEZE = BALLAD_SET_2_FREEZE

    // HIPHOP_RNB_SET_2 = BALLAD_SET_3(BLUE) 재사용.
    private val HIPHOP_RNB_SET_2_ONPULSE = BALLAD_SET_3_ONPULSE
    private val HIPHOP_RNB_SET_2_BLINK = BALLAD_SET_3_BLINK
    private val HIPHOP_RNB_SET_2_CHORUS = BALLAD_SET_3_CHORUS
    private val HIPHOP_RNB_SET_2_BREATH_VERSE = BALLAD_SET_3_BREATH_VERSE
    private val HIPHOP_RNB_SET_2_BREATH_BRIDGE_BALLAD = BALLAD_SET_3_BREATH_BRIDGE_BALLAD
    private val HIPHOP_RNB_SET_2_BREATH_DEFAULT = BALLAD_SET_3_BREATH_DEFAULT
    private val HIPHOP_RNB_SET_2_FREEZE = BALLAD_SET_3_FREEZE

    // HIPHOP_RNB_SET_3 = BALLAD_SET_1(TEAL) 재사용.
    private val HIPHOP_RNB_SET_3_ONPULSE = BALLAD_SET_1_ONPULSE
    private val HIPHOP_RNB_SET_3_BLINK = BALLAD_SET_1_BLINK
    private val HIPHOP_RNB_SET_3_CHORUS = BALLAD_SET_1_CHORUS
    private val HIPHOP_RNB_SET_3_BREATH_VERSE = BALLAD_SET_1_BREATH_VERSE
    private val HIPHOP_RNB_SET_3_BREATH_BRIDGE_BALLAD = BALLAD_SET_1_BREATH_BRIDGE_BALLAD
    private val HIPHOP_RNB_SET_3_BREATH_DEFAULT = BALLAD_SET_1_BREATH_DEFAULT
    private val HIPHOP_RNB_SET_3_FREEZE = BALLAD_SET_1_FREEZE

    // ROCK_SET_1 = EDM_SET_1(RED) 재사용.
    private val ROCK_SET_1_ONPULSE = EDM_SET_1_ONPULSE
    private val ROCK_SET_1_BLINK = EDM_SET_1_BLINK
    private val ROCK_SET_1_CHORUS = EDM_SET_1_CHORUS
    private val ROCK_SET_1_BREATH_VERSE = EDM_SET_1_BREATH_VERSE
    private val ROCK_SET_1_BREATH_BRIDGE_BALLAD = EDM_SET_1_BREATH_BRIDGE_BALLAD
    private val ROCK_SET_1_BREATH_DEFAULT = EDM_SET_1_BREATH_DEFAULT
    private val ROCK_SET_1_FREEZE = EDM_SET_1_FREEZE

    // ROCK_SET_2 = EDM_SET_2(ORANGE_GOLD) 재사용.
    private val ROCK_SET_2_ONPULSE = EDM_SET_2_ONPULSE
    private val ROCK_SET_2_BLINK = EDM_SET_2_BLINK
    private val ROCK_SET_2_CHORUS = EDM_SET_2_CHORUS
    private val ROCK_SET_2_BREATH_VERSE = EDM_SET_2_BREATH_VERSE
    private val ROCK_SET_2_BREATH_BRIDGE_BALLAD = EDM_SET_2_BREATH_BRIDGE_BALLAD
    private val ROCK_SET_2_BREATH_DEFAULT = EDM_SET_2_BREATH_DEFAULT
    private val ROCK_SET_2_FREEZE = EDM_SET_2_FREEZE

    // ===== 장르별 팔레트 세트 (위 색상표를 Palette로 묶은 것 — 더 이상의 조합/계산 없음) =====

    private val BALLAD_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "TEAL", musicStyle = GenreDetector.MusicStyle.BALLAD,
        onPulse = BALLAD_SET_1_ONPULSE, blink = BALLAD_SET_1_BLINK, chorus = BALLAD_SET_1_CHORUS,
        breathVerse = BALLAD_SET_1_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = BALLAD_SET_1_BREATH_DEFAULT, freeze = BALLAD_SET_1_FREEZE
    )

    private val BALLAD_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "PURPLE", musicStyle = GenreDetector.MusicStyle.BALLAD,
        onPulse = BALLAD_SET_2_ONPULSE, blink = BALLAD_SET_2_BLINK, chorus = BALLAD_SET_2_CHORUS,
        breathVerse = BALLAD_SET_2_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = BALLAD_SET_2_BREATH_DEFAULT, freeze = BALLAD_SET_2_FREEZE
    )

    private val BALLAD_SET_3 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "BLUE", musicStyle = GenreDetector.MusicStyle.BALLAD,
        onPulse = BALLAD_SET_3_ONPULSE, blink = BALLAD_SET_3_BLINK, chorus = BALLAD_SET_3_CHORUS,
        breathVerse = BALLAD_SET_3_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_3_BREATH_BRIDGE_BALLAD,
        breathDefault = BALLAD_SET_3_BREATH_DEFAULT, freeze = BALLAD_SET_3_FREEZE
    )

    private val EDM_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "RED", musicStyle = GenreDetector.MusicStyle.EDM,
        onPulse = EDM_SET_1_ONPULSE, blink = EDM_SET_1_BLINK, chorus = EDM_SET_1_CHORUS,
        breathVerse = EDM_SET_1_BREATH_VERSE, breathBridgeBallad = EDM_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = EDM_SET_1_BREATH_DEFAULT, freeze = EDM_SET_1_FREEZE
    )

    private val EDM_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD", musicStyle = GenreDetector.MusicStyle.EDM,
        onPulse = EDM_SET_2_ONPULSE, blink = EDM_SET_2_BLINK, chorus = EDM_SET_2_CHORUS,
        breathVerse = EDM_SET_2_BREATH_VERSE, breathBridgeBallad = EDM_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = EDM_SET_2_BREATH_DEFAULT, freeze = EDM_SET_2_FREEZE
    )

    private val EDM_SET_3 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "PURPLE", musicStyle = GenreDetector.MusicStyle.EDM,
        onPulse = EDM_SET_3_ONPULSE, blink = EDM_SET_3_BLINK, chorus = EDM_SET_3_CHORUS,
        breathVerse = EDM_SET_3_BREATH_VERSE, breathBridgeBallad = EDM_SET_3_BREATH_BRIDGE_BALLAD,
        breathDefault = EDM_SET_3_BREATH_DEFAULT, freeze = EDM_SET_3_FREEZE
    )

    private val DANCE_POP_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "RED", musicStyle = GenreDetector.MusicStyle.DANCE_POP,
        onPulse = DANCE_POP_SET_1_ONPULSE, blink = DANCE_POP_SET_1_BLINK, chorus = DANCE_POP_SET_1_CHORUS,
        breathVerse = DANCE_POP_SET_1_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = DANCE_POP_SET_1_BREATH_DEFAULT, freeze = DANCE_POP_SET_1_FREEZE
    )

    private val DANCE_POP_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "TEAL", musicStyle = GenreDetector.MusicStyle.DANCE_POP,
        onPulse = DANCE_POP_SET_2_ONPULSE, blink = DANCE_POP_SET_2_BLINK, chorus = DANCE_POP_SET_2_CHORUS,
        breathVerse = DANCE_POP_SET_2_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = DANCE_POP_SET_2_BREATH_DEFAULT, freeze = DANCE_POP_SET_2_FREEZE
    )

    private val DANCE_POP_SET_3 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "BLUE", musicStyle = GenreDetector.MusicStyle.DANCE_POP,
        onPulse = DANCE_POP_SET_3_ONPULSE, blink = DANCE_POP_SET_3_BLINK, chorus = DANCE_POP_SET_3_CHORUS,
        breathVerse = DANCE_POP_SET_3_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_3_BREATH_BRIDGE_BALLAD,
        breathDefault = DANCE_POP_SET_3_BREATH_DEFAULT, freeze = DANCE_POP_SET_3_FREEZE
    )

    private val POP_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "PURPLE", musicStyle = GenreDetector.MusicStyle.POP,
        onPulse = POP_SET_1_ONPULSE, blink = POP_SET_1_BLINK, chorus = POP_SET_1_CHORUS,
        breathVerse = POP_SET_1_BREATH_VERSE, breathBridgeBallad = POP_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = POP_SET_1_BREATH_DEFAULT, freeze = POP_SET_1_FREEZE
    )

    private val POP_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "RED", musicStyle = GenreDetector.MusicStyle.POP,
        onPulse = POP_SET_2_ONPULSE, blink = POP_SET_2_BLINK, chorus = POP_SET_2_CHORUS,
        breathVerse = POP_SET_2_BREATH_VERSE, breathBridgeBallad = POP_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = POP_SET_2_BREATH_DEFAULT, freeze = POP_SET_2_FREEZE
    )

    private val POP_SET_3 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD", musicStyle = GenreDetector.MusicStyle.POP,
        onPulse = POP_SET_3_ONPULSE, blink = POP_SET_3_BLINK, chorus = POP_SET_3_CHORUS,
        breathVerse = POP_SET_3_BREATH_VERSE, breathBridgeBallad = POP_SET_3_BREATH_BRIDGE_BALLAD,
        breathDefault = POP_SET_3_BREATH_DEFAULT, freeze = POP_SET_3_FREEZE
    )

    private val POP_SET_4 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "TEAL", musicStyle = GenreDetector.MusicStyle.POP,
        onPulse = POP_SET_4_ONPULSE, blink = POP_SET_4_BLINK, chorus = POP_SET_4_CHORUS,
        breathVerse = POP_SET_4_BREATH_VERSE, breathBridgeBallad = POP_SET_4_BREATH_BRIDGE_BALLAD,
        breathDefault = POP_SET_4_BREATH_DEFAULT, freeze = POP_SET_4_FREEZE
    )

    private val POP_SET_5 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "BLUE", musicStyle = GenreDetector.MusicStyle.POP,
        onPulse = POP_SET_5_ONPULSE, blink = POP_SET_5_BLINK, chorus = POP_SET_5_CHORUS,
        breathVerse = POP_SET_5_BREATH_VERSE, breathBridgeBallad = POP_SET_5_BREATH_BRIDGE_BALLAD,
        breathDefault = POP_SET_5_BREATH_DEFAULT, freeze = POP_SET_5_FREEZE
    )

    private val HIPHOP_RNB_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "PURPLE", musicStyle = GenreDetector.MusicStyle.HIPHOP_RNB,
        onPulse = HIPHOP_RNB_SET_1_ONPULSE, blink = HIPHOP_RNB_SET_1_BLINK, chorus = HIPHOP_RNB_SET_1_CHORUS,
        breathVerse = HIPHOP_RNB_SET_1_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = HIPHOP_RNB_SET_1_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_1_FREEZE
    )

    private val HIPHOP_RNB_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "BLUE", musicStyle = GenreDetector.MusicStyle.HIPHOP_RNB,
        onPulse = HIPHOP_RNB_SET_2_ONPULSE, blink = HIPHOP_RNB_SET_2_BLINK, chorus = HIPHOP_RNB_SET_2_CHORUS,
        breathVerse = HIPHOP_RNB_SET_2_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = HIPHOP_RNB_SET_2_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_2_FREEZE
    )

    private val HIPHOP_RNB_SET_3 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "TEAL", musicStyle = GenreDetector.MusicStyle.HIPHOP_RNB,
        onPulse = HIPHOP_RNB_SET_3_ONPULSE, blink = HIPHOP_RNB_SET_3_BLINK, chorus = HIPHOP_RNB_SET_3_CHORUS,
        breathVerse = HIPHOP_RNB_SET_3_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_3_BREATH_BRIDGE_BALLAD,
        breathDefault = HIPHOP_RNB_SET_3_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_3_FREEZE
    )

    private val ROCK_SET_1 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "RED", musicStyle = GenreDetector.MusicStyle.ROCK,
        onPulse = ROCK_SET_1_ONPULSE, blink = ROCK_SET_1_BLINK, chorus = ROCK_SET_1_CHORUS,
        breathVerse = ROCK_SET_1_BREATH_VERSE, breathBridgeBallad = ROCK_SET_1_BREATH_BRIDGE_BALLAD,
        breathDefault = ROCK_SET_1_BREATH_DEFAULT, freeze = ROCK_SET_1_FREEZE
    )

    private val ROCK_SET_2 = EffectMatchingEngine.Palette(
        black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD", musicStyle = GenreDetector.MusicStyle.ROCK,
        onPulse = ROCK_SET_2_ONPULSE, blink = ROCK_SET_2_BLINK, chorus = ROCK_SET_2_CHORUS,
        breathVerse = ROCK_SET_2_BREATH_VERSE, breathBridgeBallad = ROCK_SET_2_BREATH_BRIDGE_BALLAD,
        breathDefault = ROCK_SET_2_BREATH_DEFAULT, freeze = ROCK_SET_2_FREEZE
    )

    // 장르에 어울리는 팔레트 세트 후보군 — 후보가 여럿이면 seed(musicId)로 그중 하나를 결정론적으로
    // 고른다(select() 참고). POP은 전 계열을 후보로 두므로 그대로 기본값으로도 재사용한다.
    private val POP_PALETTE_SETS = listOf(POP_SET_1, POP_SET_2, POP_SET_3, POP_SET_4, POP_SET_5)

    private val GENRE_PALETTE_SETS: Map<GenreDetector.MusicStyle, List<EffectMatchingEngine.Palette>> = mapOf(
        GenreDetector.MusicStyle.BALLAD     to listOf(BALLAD_SET_1, BALLAD_SET_2, BALLAD_SET_3),
        GenreDetector.MusicStyle.EDM        to listOf(EDM_SET_1, EDM_SET_2, EDM_SET_3),
        GenreDetector.MusicStyle.DANCE_POP  to listOf(DANCE_POP_SET_1, DANCE_POP_SET_2, DANCE_POP_SET_3),
        GenreDetector.MusicStyle.HIPHOP_RNB to listOf(HIPHOP_RNB_SET_1, HIPHOP_RNB_SET_2, HIPHOP_RNB_SET_3),
        GenreDetector.MusicStyle.ROCK       to listOf(ROCK_SET_1, ROCK_SET_2),
        GenreDetector.MusicStyle.POP        to POP_PALETTE_SETS
    )

    /** 장르별 팔레트 세트 후보군 중 seed로 하나를 결정론적으로 고른다. */
    fun select(seed: Int, musicStyle: GenreDetector.MusicStyle): EffectMatchingEngine.Palette {
        val rawHue = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        val sets = GENRE_PALETTE_SETS[musicStyle] ?: POP_PALETTE_SETS
        return sets[Math.floorMod(rawHue, sets.size)]
    }
}
