package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload

// 장르별 이펙트 색상표(아래 <장르>_SET_<번호>_<이펙트> 상수들)가 쓰는 실측 RGB 스와치.
// 이름을 붙여두면 코드만 보고도 바로 어떤 색인지 알 수 있다 — 전부 응원봉 실기로 확인한
// S=100%·V=100% 순수 색상.
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
// (ONPULSE/CHORUS/BREATH_VERSE의 bg 후보)이다. 매번 계산하지 않고 값 그대로 이름 붙여
// 선언해둔다.
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

// ===== 이펙트별 색상표 (장르×세트×이펙트, 전부 응원봉 실기로 확인한 RGB를 그대로 역할에 맞게 배치) =====
// <장르>_SET_<번호>_<이펙트> 형태로 전부 독립된 상수다 — 어떤 장르의 몇 번째 세트가 어떤 이펙트에
// 무슨 색을 쓰는지 이름만 보고 바로 알 수 있다. 각 세트의 main[0](ONPULSE/CHORUS 등 순환 리스트의
// 첫 색)이 그 세트의 "대표색"이고, 이 배치는 실사용 비중이 큰 BALLAD/EDM/DANCE_POP/POP이 공유하는
// 계열별로 "그 계열을 쓰는 세트들의 대표색끼리 최대한 멀어지도록" 전수 탐색으로 찾은 값이다
// (최소 간격 11.3도). HIPHOP_RNB/ROCK은 실사용 비중이 낮아 이 최적화에서 제외하고 같은 계열을
// 쓰는 세트(HIPHOP_RNB→BALLAD, ROCK→EDM)의 상수를 그대로 재사용한다.
//
// 이펙트 역할: ONPULSE/CHORUS/BREATH_VERSE는 fg가 bar/beat마다 순환, BLINK는 ONPULSE와 같은 fg
// 순환에 bg만 달리해 구분, BREATH_DEFAULT/FREEZE는 fg 고정 1색, BREATH_BRIDGE_BALLAD는 발라드
// BRIDGE 전용(fg 고정 1색 + bg 검정, 조용한 느낌).

// BALLAD_SET_1 = TEAL (rolePerm=[3, 0, 1, 2])
private val BALLAD_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN_SHADE)
private val BALLAD_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT)
private val BALLAD_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN_SHADE)
private val BALLAD_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN_SHADE)
private val BALLAD_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), BLACK)
private val BALLAD_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), TEAL_CYAN_SHADE)
private val BALLAD_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT), BLACK)

// BALLAD_SET_2 = PURPLE (rolePerm=[3, 0, 1, 2])
private val BALLAD_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_NAVY_SHADE)
private val BALLAD_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_INDIGO)
private val BALLAD_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_NAVY_SHADE)
private val BALLAD_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY, PURPLE_MAGENTA, PURPLE_VIOLET), PURPLE_NAVY_SHADE)
private val BALLAD_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), BLACK)
private val BALLAD_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), PURPLE_NAVY_SHADE)
private val BALLAD_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_INDIGO), BLACK)

// BALLAD_SET_3 = BLUE (rolePerm=[3, 0, 1, 2])
private val BALLAD_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE_SHADE)
private val BALLAD_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE)
private val BALLAD_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE_SHADE)
private val BALLAD_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE_SHADE)
private val BALLAD_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLACK)
private val BALLAD_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLUE_PURE_SHADE)
private val BALLAD_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE), BLACK)

// EDM_SET_1 = RED (rolePerm=[0, 1, 2, 3])
private val EDM_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), RED_MAGENTA_SHADE)
private val EDM_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), RED_VERMILION)
private val EDM_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_MAGENTA, RED_ROSE, RED_PURE), RED_MAGENTA_SHADE)
private val EDM_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_MAGENTA, RED_ROSE, RED_PURE), RED_MAGENTA_SHADE)
private val EDM_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
private val EDM_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_MAGENTA_SHADE)
private val EDM_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

// EDM_SET_2 = ORANGE_GOLD (rolePerm=[0, 1, 2, 3])
private val EDM_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_DEEP_SHADE)
private val EDM_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME)
private val EDM_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_DEEP_SHADE)
private val EDM_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER), ORANGE_GOLD_DEEP_SHADE)
private val EDM_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)
private val EDM_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), ORANGE_GOLD_DEEP_SHADE)
private val EDM_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)

// EDM_SET_3 = PURPLE (rolePerm=[0, 1, 2, 3])
private val EDM_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_MAGENTA_SHADE)
private val EDM_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_NAVY)
private val EDM_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_MAGENTA_SHADE)
private val EDM_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO), PURPLE_MAGENTA_SHADE)
private val EDM_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)
private val EDM_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), PURPLE_MAGENTA_SHADE)
private val EDM_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)

// DANCE_POP_SET_1 = RED (rolePerm=[2, 0, 1, 3])
private val DANCE_POP_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), RED_PURE_SHADE)
private val DANCE_POP_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), RED_VERMILION)
private val DANCE_POP_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_PURE, RED_MAGENTA, RED_ROSE), RED_PURE_SHADE)
private val DANCE_POP_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_PURE, RED_MAGENTA, RED_ROSE), RED_PURE_SHADE)
private val DANCE_POP_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
private val DANCE_POP_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_PURE_SHADE)
private val DANCE_POP_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

// DANCE_POP_SET_2 = TEAL (rolePerm=[2, 0, 1, 3])
private val DANCE_POP_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT_SHADE)
private val DANCE_POP_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_CYAN)
private val DANCE_POP_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT_SHADE)
private val DANCE_POP_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_MINT, TEAL_YELLOWGREEN, TEAL_GREEN), TEAL_MINT_SHADE)
private val DANCE_POP_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)
private val DANCE_POP_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), TEAL_MINT_SHADE)
private val DANCE_POP_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)

// DANCE_POP_SET_3 = BLUE (rolePerm=[2, 0, 1, 3])
private val DANCE_POP_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE_SHADE)
private val DANCE_POP_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_PURE)
private val DANCE_POP_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE_SHADE)
private val DANCE_POP_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_AZURE, BLUE_SEAFOAM, BLUE_SKYCYAN), BLUE_AZURE_SHADE)
private val DANCE_POP_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)
private val DANCE_POP_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLUE_AZURE_SHADE)
private val DANCE_POP_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)

// POP_SET_1 = PURPLE (rolePerm=[1, 0, 2, 3])
private val POP_SET_1_ONPULSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_VIOLET_SHADE)
private val POP_SET_1_BLINK = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_NAVY)
private val POP_SET_1_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_VIOLET_SHADE)
private val POP_SET_1_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(PURPLE_VIOLET, PURPLE_MAGENTA, PURPLE_INDIGO), PURPLE_VIOLET_SHADE)
private val POP_SET_1_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)
private val POP_SET_1_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), PURPLE_VIOLET_SHADE)
private val POP_SET_1_FREEZE = EffectMatchingEngine.EffectColors(listOf(PURPLE_NAVY), BLACK)

// POP_SET_2 = RED (rolePerm=[1, 0, 2, 3])
private val POP_SET_2_ONPULSE = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), RED_ROSE_SHADE)
private val POP_SET_2_BLINK = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), RED_VERMILION)
private val POP_SET_2_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, RED_ROSE, RED_MAGENTA, RED_PURE), RED_ROSE_SHADE)
private val POP_SET_2_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(RED_ROSE, RED_MAGENTA, RED_PURE), RED_ROSE_SHADE)
private val POP_SET_2_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)
private val POP_SET_2_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), RED_ROSE_SHADE)
private val POP_SET_2_FREEZE = EffectMatchingEngine.EffectColors(listOf(RED_VERMILION), BLACK)

// POP_SET_3 = ORANGE_GOLD (rolePerm=[1, 0, 2, 3])
private val POP_SET_3_ONPULSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_ORANGE_SHADE)
private val POP_SET_3_BLINK = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_LIME)
private val POP_SET_3_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_ORANGE_SHADE)
private val POP_SET_3_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_ORANGE, ORANGE_GOLD_DEEP, ORANGE_GOLD_AMBER), ORANGE_GOLD_ORANGE_SHADE)
private val POP_SET_3_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)
private val POP_SET_3_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), ORANGE_GOLD_ORANGE_SHADE)
private val POP_SET_3_FREEZE = EffectMatchingEngine.EffectColors(listOf(ORANGE_GOLD_LIME), BLACK)

// POP_SET_4 = TEAL (rolePerm=[1, 0, 2, 3])
private val POP_SET_4_ONPULSE = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_GREEN_SHADE)
private val POP_SET_4_BLINK = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_CYAN)
private val POP_SET_4_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_GREEN_SHADE)
private val POP_SET_4_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(TEAL_GREEN, TEAL_YELLOWGREEN, TEAL_MINT), TEAL_GREEN_SHADE)
private val POP_SET_4_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)
private val POP_SET_4_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), TEAL_GREEN_SHADE)
private val POP_SET_4_FREEZE = EffectMatchingEngine.EffectColors(listOf(TEAL_CYAN), BLACK)

// POP_SET_5 = BLUE (rolePerm=[1, 0, 2, 3])
private val POP_SET_5_ONPULSE = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_SKYCYAN_SHADE)
private val POP_SET_5_BLINK = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_PURE)
private val POP_SET_5_CHORUS = EffectMatchingEngine.EffectColors(listOf(WHITE, BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_SKYCYAN_SHADE)
private val POP_SET_5_BREATH_VERSE = EffectMatchingEngine.EffectColors(listOf(BLUE_SKYCYAN, BLUE_SEAFOAM, BLUE_AZURE), BLUE_SKYCYAN_SHADE)
private val POP_SET_5_BREATH_BRIDGE_BALLAD = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)
private val POP_SET_5_BREATH_DEFAULT = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLUE_SKYCYAN_SHADE)
private val POP_SET_5_FREEZE = EffectMatchingEngine.EffectColors(listOf(BLUE_PURE), BLACK)

// HIPHOP_RNB_SET_1 = BALLAD_SET_2와 동일 계열/배치(PURPLE) 재사용 — 실사용 비중이 낮아 별도 최적화 없이 그대로 가져다 쓴다.
private val HIPHOP_RNB_SET_1_ONPULSE = BALLAD_SET_2_ONPULSE
private val HIPHOP_RNB_SET_1_BLINK = BALLAD_SET_2_BLINK
private val HIPHOP_RNB_SET_1_CHORUS = BALLAD_SET_2_CHORUS
private val HIPHOP_RNB_SET_1_BREATH_VERSE = BALLAD_SET_2_BREATH_VERSE
private val HIPHOP_RNB_SET_1_BREATH_BRIDGE_BALLAD = BALLAD_SET_2_BREATH_BRIDGE_BALLAD
private val HIPHOP_RNB_SET_1_BREATH_DEFAULT = BALLAD_SET_2_BREATH_DEFAULT
private val HIPHOP_RNB_SET_1_FREEZE = BALLAD_SET_2_FREEZE

// HIPHOP_RNB_SET_2 = BALLAD_SET_3와 동일 계열/배치(BLUE) 재사용 — 실사용 비중이 낮아 별도 최적화 없이 그대로 가져다 쓴다.
private val HIPHOP_RNB_SET_2_ONPULSE = BALLAD_SET_3_ONPULSE
private val HIPHOP_RNB_SET_2_BLINK = BALLAD_SET_3_BLINK
private val HIPHOP_RNB_SET_2_CHORUS = BALLAD_SET_3_CHORUS
private val HIPHOP_RNB_SET_2_BREATH_VERSE = BALLAD_SET_3_BREATH_VERSE
private val HIPHOP_RNB_SET_2_BREATH_BRIDGE_BALLAD = BALLAD_SET_3_BREATH_BRIDGE_BALLAD
private val HIPHOP_RNB_SET_2_BREATH_DEFAULT = BALLAD_SET_3_BREATH_DEFAULT
private val HIPHOP_RNB_SET_2_FREEZE = BALLAD_SET_3_FREEZE

// HIPHOP_RNB_SET_3 = BALLAD_SET_1와 동일 계열/배치(TEAL) 재사용 — 실사용 비중이 낮아 별도 최적화 없이 그대로 가져다 쓴다.
private val HIPHOP_RNB_SET_3_ONPULSE = BALLAD_SET_1_ONPULSE
private val HIPHOP_RNB_SET_3_BLINK = BALLAD_SET_1_BLINK
private val HIPHOP_RNB_SET_3_CHORUS = BALLAD_SET_1_CHORUS
private val HIPHOP_RNB_SET_3_BREATH_VERSE = BALLAD_SET_1_BREATH_VERSE
private val HIPHOP_RNB_SET_3_BREATH_BRIDGE_BALLAD = BALLAD_SET_1_BREATH_BRIDGE_BALLAD
private val HIPHOP_RNB_SET_3_BREATH_DEFAULT = BALLAD_SET_1_BREATH_DEFAULT
private val HIPHOP_RNB_SET_3_FREEZE = BALLAD_SET_1_FREEZE

// ROCK_SET_1 = EDM_SET_1와 동일 계열/배치(RED) 재사용 — 실사용 비중이 낮아 별도 최적화 없이 그대로 가져다 쓴다.
private val ROCK_SET_1_ONPULSE = EDM_SET_1_ONPULSE
private val ROCK_SET_1_BLINK = EDM_SET_1_BLINK
private val ROCK_SET_1_CHORUS = EDM_SET_1_CHORUS
private val ROCK_SET_1_BREATH_VERSE = EDM_SET_1_BREATH_VERSE
private val ROCK_SET_1_BREATH_BRIDGE_BALLAD = EDM_SET_1_BREATH_BRIDGE_BALLAD
private val ROCK_SET_1_BREATH_DEFAULT = EDM_SET_1_BREATH_DEFAULT
private val ROCK_SET_1_FREEZE = EDM_SET_1_FREEZE

// ROCK_SET_2 = EDM_SET_2와 동일 계열/배치(ORANGE_GOLD) 재사용 — 실사용 비중이 낮아 별도 최적화 없이 그대로 가져다 쓴다.
private val ROCK_SET_2_ONPULSE = EDM_SET_2_ONPULSE
private val ROCK_SET_2_BLINK = EDM_SET_2_BLINK
private val ROCK_SET_2_CHORUS = EDM_SET_2_CHORUS
private val ROCK_SET_2_BREATH_VERSE = EDM_SET_2_BREATH_VERSE
private val ROCK_SET_2_BREATH_BRIDGE_BALLAD = EDM_SET_2_BREATH_BRIDGE_BALLAD
private val ROCK_SET_2_BREATH_DEFAULT = EDM_SET_2_BREATH_DEFAULT
private val ROCK_SET_2_FREEZE = EDM_SET_2_FREEZE

// ===== 장르별 팔레트 세트 (위 색상표를 Palette로 묶은 것 — 더 이상의 조합/계산 없음) =====

private val BALLAD_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "TEAL",
    onPulse = BALLAD_SET_1_ONPULSE, blink = BALLAD_SET_1_BLINK, chorus = BALLAD_SET_1_CHORUS,
    breathVerse = BALLAD_SET_1_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = BALLAD_SET_1_BREATH_DEFAULT, freeze = BALLAD_SET_1_FREEZE
)

private val BALLAD_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "PURPLE",
    onPulse = BALLAD_SET_2_ONPULSE, blink = BALLAD_SET_2_BLINK, chorus = BALLAD_SET_2_CHORUS,
    breathVerse = BALLAD_SET_2_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = BALLAD_SET_2_BREATH_DEFAULT, freeze = BALLAD_SET_2_FREEZE
)

private val BALLAD_SET_3 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "BLUE",
    onPulse = BALLAD_SET_3_ONPULSE, blink = BALLAD_SET_3_BLINK, chorus = BALLAD_SET_3_CHORUS,
    breathVerse = BALLAD_SET_3_BREATH_VERSE, breathBridgeBallad = BALLAD_SET_3_BREATH_BRIDGE_BALLAD,
    breathDefault = BALLAD_SET_3_BREATH_DEFAULT, freeze = BALLAD_SET_3_FREEZE
)

private val EDM_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "RED",
    onPulse = EDM_SET_1_ONPULSE, blink = EDM_SET_1_BLINK, chorus = EDM_SET_1_CHORUS,
    breathVerse = EDM_SET_1_BREATH_VERSE, breathBridgeBallad = EDM_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = EDM_SET_1_BREATH_DEFAULT, freeze = EDM_SET_1_FREEZE
)

private val EDM_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD",
    onPulse = EDM_SET_2_ONPULSE, blink = EDM_SET_2_BLINK, chorus = EDM_SET_2_CHORUS,
    breathVerse = EDM_SET_2_BREATH_VERSE, breathBridgeBallad = EDM_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = EDM_SET_2_BREATH_DEFAULT, freeze = EDM_SET_2_FREEZE
)

private val EDM_SET_3 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "PURPLE",
    onPulse = EDM_SET_3_ONPULSE, blink = EDM_SET_3_BLINK, chorus = EDM_SET_3_CHORUS,
    breathVerse = EDM_SET_3_BREATH_VERSE, breathBridgeBallad = EDM_SET_3_BREATH_BRIDGE_BALLAD,
    breathDefault = EDM_SET_3_BREATH_DEFAULT, freeze = EDM_SET_3_FREEZE
)

private val DANCE_POP_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "RED",
    onPulse = DANCE_POP_SET_1_ONPULSE, blink = DANCE_POP_SET_1_BLINK, chorus = DANCE_POP_SET_1_CHORUS,
    breathVerse = DANCE_POP_SET_1_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = DANCE_POP_SET_1_BREATH_DEFAULT, freeze = DANCE_POP_SET_1_FREEZE
)

private val DANCE_POP_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "TEAL",
    onPulse = DANCE_POP_SET_2_ONPULSE, blink = DANCE_POP_SET_2_BLINK, chorus = DANCE_POP_SET_2_CHORUS,
    breathVerse = DANCE_POP_SET_2_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = DANCE_POP_SET_2_BREATH_DEFAULT, freeze = DANCE_POP_SET_2_FREEZE
)

private val DANCE_POP_SET_3 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "BLUE",
    onPulse = DANCE_POP_SET_3_ONPULSE, blink = DANCE_POP_SET_3_BLINK, chorus = DANCE_POP_SET_3_CHORUS,
    breathVerse = DANCE_POP_SET_3_BREATH_VERSE, breathBridgeBallad = DANCE_POP_SET_3_BREATH_BRIDGE_BALLAD,
    breathDefault = DANCE_POP_SET_3_BREATH_DEFAULT, freeze = DANCE_POP_SET_3_FREEZE
)

private val POP_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "PURPLE",
    onPulse = POP_SET_1_ONPULSE, blink = POP_SET_1_BLINK, chorus = POP_SET_1_CHORUS,
    breathVerse = POP_SET_1_BREATH_VERSE, breathBridgeBallad = POP_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = POP_SET_1_BREATH_DEFAULT, freeze = POP_SET_1_FREEZE
)

private val POP_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "RED",
    onPulse = POP_SET_2_ONPULSE, blink = POP_SET_2_BLINK, chorus = POP_SET_2_CHORUS,
    breathVerse = POP_SET_2_BREATH_VERSE, breathBridgeBallad = POP_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = POP_SET_2_BREATH_DEFAULT, freeze = POP_SET_2_FREEZE
)

private val POP_SET_3 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD",
    onPulse = POP_SET_3_ONPULSE, blink = POP_SET_3_BLINK, chorus = POP_SET_3_CHORUS,
    breathVerse = POP_SET_3_BREATH_VERSE, breathBridgeBallad = POP_SET_3_BREATH_BRIDGE_BALLAD,
    breathDefault = POP_SET_3_BREATH_DEFAULT, freeze = POP_SET_3_FREEZE
)

private val POP_SET_4 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "TEAL",
    onPulse = POP_SET_4_ONPULSE, blink = POP_SET_4_BLINK, chorus = POP_SET_4_CHORUS,
    breathVerse = POP_SET_4_BREATH_VERSE, breathBridgeBallad = POP_SET_4_BREATH_BRIDGE_BALLAD,
    breathDefault = POP_SET_4_BREATH_DEFAULT, freeze = POP_SET_4_FREEZE
)

private val POP_SET_5 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "BLUE",
    onPulse = POP_SET_5_ONPULSE, blink = POP_SET_5_BLINK, chorus = POP_SET_5_CHORUS,
    breathVerse = POP_SET_5_BREATH_VERSE, breathBridgeBallad = POP_SET_5_BREATH_BRIDGE_BALLAD,
    breathDefault = POP_SET_5_BREATH_DEFAULT, freeze = POP_SET_5_FREEZE
)

private val HIPHOP_RNB_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "PURPLE",
    onPulse = HIPHOP_RNB_SET_1_ONPULSE, blink = HIPHOP_RNB_SET_1_BLINK, chorus = HIPHOP_RNB_SET_1_CHORUS,
    breathVerse = HIPHOP_RNB_SET_1_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = HIPHOP_RNB_SET_1_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_1_FREEZE
)

private val HIPHOP_RNB_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "BLUE",
    onPulse = HIPHOP_RNB_SET_2_ONPULSE, blink = HIPHOP_RNB_SET_2_BLINK, chorus = HIPHOP_RNB_SET_2_CHORUS,
    breathVerse = HIPHOP_RNB_SET_2_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = HIPHOP_RNB_SET_2_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_2_FREEZE
)

private val HIPHOP_RNB_SET_3 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "TEAL",
    onPulse = HIPHOP_RNB_SET_3_ONPULSE, blink = HIPHOP_RNB_SET_3_BLINK, chorus = HIPHOP_RNB_SET_3_CHORUS,
    breathVerse = HIPHOP_RNB_SET_3_BREATH_VERSE, breathBridgeBallad = HIPHOP_RNB_SET_3_BREATH_BRIDGE_BALLAD,
    breathDefault = HIPHOP_RNB_SET_3_BREATH_DEFAULT, freeze = HIPHOP_RNB_SET_3_FREEZE
)

private val ROCK_SET_1 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "RED",
    onPulse = ROCK_SET_1_ONPULSE, blink = ROCK_SET_1_BLINK, chorus = ROCK_SET_1_CHORUS,
    breathVerse = ROCK_SET_1_BREATH_VERSE, breathBridgeBallad = ROCK_SET_1_BREATH_BRIDGE_BALLAD,
    breathDefault = ROCK_SET_1_BREATH_DEFAULT, freeze = ROCK_SET_1_FREEZE
)

private val ROCK_SET_2 = EffectMatchingEngine.Palette(
    black = BLACK, white = WHITE, colorFamily = "ORANGE_GOLD",
    onPulse = ROCK_SET_2_ONPULSE, blink = ROCK_SET_2_BLINK, chorus = ROCK_SET_2_CHORUS,
    breathVerse = ROCK_SET_2_BREATH_VERSE, breathBridgeBallad = ROCK_SET_2_BREATH_BRIDGE_BALLAD,
    breathDefault = ROCK_SET_2_BREATH_DEFAULT, freeze = ROCK_SET_2_FREEZE
)

// 장르에 어울리는 팔레트 세트 후보군 — 후보가 여럿이면 seed(musicId)로 그중 하나를 결정론적으로
// 고른다(buildPalette 참고). POP은 전 계열을 후보로 두므로 그대로 기본값으로도 재사용한다.
private val POP_PALETTE_SETS = listOf(POP_SET_1, POP_SET_2, POP_SET_3, POP_SET_4, POP_SET_5)

private val GENRE_PALETTE_SETS: Map<MusicStyleClassifier.MusicStyle, List<EffectMatchingEngine.Palette>> = mapOf(
    MusicStyleClassifier.MusicStyle.BALLAD     to listOf(BALLAD_SET_1, BALLAD_SET_2, BALLAD_SET_3),
    MusicStyleClassifier.MusicStyle.EDM        to listOf(EDM_SET_1, EDM_SET_2, EDM_SET_3),
    MusicStyleClassifier.MusicStyle.DANCE_POP  to listOf(DANCE_POP_SET_1, DANCE_POP_SET_2, DANCE_POP_SET_3),
    MusicStyleClassifier.MusicStyle.HIPHOP_RNB to listOf(HIPHOP_RNB_SET_1, HIPHOP_RNB_SET_2, HIPHOP_RNB_SET_3),
    MusicStyleClassifier.MusicStyle.ROCK       to listOf(ROCK_SET_1, ROCK_SET_2),
    MusicStyleClassifier.MusicStyle.POP        to POP_PALETTE_SETS
)

/**
 * EffectMatchingEngineV1 - 섹션 타입 기반 이펙트 매칭 엔진
 * 섹션 타입별 이펙트 엔진 할당 (ON_PULSE / BREATH / ON_TRANSIT_ROTATE 등)
 */
class EffectMatchingEngineV1 : EffectMatchingEngine {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
        // OUTRO 전체에 걸쳐 서서히 꺼지도록, outroStartMs~finalOffMs 구간을 여러 스텝으로
        // 나눠 각 스텝마다 밝기를 낮춘 fg색을 on(transit=...)으로 보낸다(마지막 스텝만
        // 실제 off). 한 스텝의 transit(페이드 길이)이 실제 몇 ms인지는 SDK/문서 어디에도
        // 안 나와 있어(실기로만 확인 가능) 초기 근사치로 스텝 길이(ms)/100을 쓰고,
        // [MIN,MAX]로 눌러둔다 — 실기 테스트 후 배율 재조정 필요.
        private const val OUTRO_TRANSIT_DIVISOR = 100L
        private const val OUTRO_TRANSIT_MIN = 2
        private const val OUTRO_TRANSIT_MAX = 60
        // 대략 1초에 한 스텝 꼴로 밝기를 낮춘다 — 너무 잦으면 BLE 트래픽이 커지고, 너무
        // 뜸하면 "서서히"가 아니라 계단식으로 뚝뚝 떨어지는 느낌이 된다.
        private const val OUTRO_FADE_STEP_MS = 1_000L
        private const val OUTRO_FADE_MAX_STEPS = 8
    }

    data class ResolvedSection(
        val startMs: Long, val endMs: Long,
        val type: SectionDetector.SectionType,
        val engine: EffectMatchingEngine.FgEngine,
        val beatMs: Long, val beats: Int,
        val source: String, val change: EffectMatchingEngine.ChangeLevel,
        val energyScore: Float = 0f, val relScore: Float = 0f,
        val beatTimesMs: List<Long> = emptyList()
    )

    override fun buildPalette(
        seed: Int, musicStyle: MusicStyleClassifier.MusicStyle
    ): EffectMatchingEngine.Palette {
        val rawHue  = (((seed.toLong() * 2654435761L) ushr 8) and 0x7FFFFFFFL).toInt()
        // 장르에 어울리는 팔레트 세트 후보군(~3개) 중 하나를 seed로 결정론적으로 고른다 —
        // 같은 장르 곡들이 전부 똑같은 색은 아니게 하되, 세트 자체(이펙트별로 이미 다 구워진
        // Palette)는 그대로 쓴다. 더 이상 조합/계산할 게 없다.
        val sets = GENRE_PALETTE_SETS[musicStyle] ?: POP_PALETTE_SETS
        val palette = sets[Math.floorMod(rawHue, sets.size)]
        Log.d(TAG, "[Palette] style=$musicStyle set=${palette.colorFamily}")
        return palette
    }

    override fun buildFrames(
        palette: EffectMatchingEngine.Palette,
        sectionGroups: List<EffectMatchingEngine.SectionGroup>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int,
        fullEnv: List<Float>,
        hopMs: Long
    ): List<Pair<Long, ByteArray>> {
        if (sectionGroups.isEmpty()) return emptyList()

        // 섹션에 이펙트 엔진을 할당해 ResolvedSection으로 변환 (에너지 기반 엔진 할당)
        val globalBeatMs = if (beatTimesMs.size > 1)
            beatTimesMs[1] - beatTimesMs[0] else 500L

        val effectiveHopMs = if (hopMs > 0L) hopMs else 10L
        val resolvedSections = convertToResolvedSections(sectionGroups, globalBeatMs, isBalladMode, fullEnv, durationMs, effectiveHopMs)

        // 엔진 할당된 섹션으로부터 프레임 빌드
        return buildFramesFromSections(palette, resolvedSections, beatTimesMs, durationMs, isBalladMode, finalOffMs,
            downbeatMs, beatsPerBar, fullEnv, effectiveHopMs)
    }

    private fun convertToResolvedSections(
        groups: List<EffectMatchingEngine.SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long
    ): List<ResolvedSection> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh    = percentile(energies, 0.35f)
        val highTh   = percentile(energies, 0.70f)
        val range    = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy   = energies[i]
            val beats    = g.annotatedBeats.size
            val relScore = ((energy - lowTh) / range).coerceIn(0f, 1f)

            // 예전엔 6비트 미만 BRIDGE를 ResolvedSection.type 단계에서 VERSE로 재분류했는데,
            // 이게 EffectMatchingEngineV1 내부에서만 일어나는 변환이라 SectionDetectorV3가
            // 내보내는 실제 섹션 타입(Beat Accuracy Checker 등에 표시되는 값)과 어긋나는
            // 문제가 있었다 — 체커에는 BRIDGE로 보이는데 실제 이펙트는 VERSE의 BLINK로
            // 나가서 혼동을 줌(실측 피드백). 타입은 항상 g.type 그대로 두고, 짧은 BRIDGE도
            // bridgePhaseEngine을 그대로 거치게 한다.
            val engine = assignFgEngine(g.type, isBalladMode = isBalladMode)

            val source = buildSourceName(g.type, beats)

            val change = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 20 ->
                    EffectMatchingEngine.ChangeLevel.STRONG
                beats < 8 -> EffectMatchingEngine.ChangeLevel.MEDIUM
                else      -> EffectMatchingEngine.ChangeLevel.STRONG
            }

            ResolvedSection(
                startMs     = g.startMs,      endMs       = g.endMs,
                type        = g.type,          engine      = engine,
                beatMs      = beatMs,          beats       = beats,
                source      = source,          change      = change,
                energyScore = energy,          relScore    = relScore,
                beatTimesMs = g.annotatedBeats.map { it.timeMs }
            )
        }
    }

    private fun buildFramesFromSections(
        palette: EffectMatchingEngine.Palette,
        sections: List<ResolvedSection>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int,
        fullEnv: List<Float>,
        hopMs: Long
    ): List<Pair<Long, ByteArray>> {
        val frameMap = LinkedHashMap<Long, ByteArray>(beatTimesMs.size * 4 + sections.size + 8)

        fun put(t: Long, payload: ByteArray) {
            if (t >= 0L) frameMap[t] = payload
        }

        data class RepeatKey(
            val engine: EffectMatchingEngine.FgEngine,
            val fgR: Int, val fgG: Int, val fgB: Int,
            val bgR: Int, val bgG: Int, val bgB: Int,
            val period: Int, val randomDelay: Int
        )
        var lastRepeatKey: RepeatKey? = null

        // OUTRO 페이드(아래 tail 블록)가 "지금 기기에 실제로 떠 있는 색"에서부터 서서히
        // 꺼지도록, 마지막으로 선택된 fg를 추적해 둔다 — OUTRO 자신은 engine==OFF_TRANSIT
        // 이라 아래 루프에서 continue로 건너뛰므로, 이 값은 항상 OUTRO 바로 이전 섹션의
        // 마지막 fg로 남는다.
        var lastFg: LSColor = palette.white

        for (section in sections) {
            lastRepeatKey = null

            val effectiveBeats = section.beatTimesMs

            if (section.engine == EffectMatchingEngine.FgEngine.OFF_TRANSIT) continue

            val enginePool  = sectionEnginePool(section.type, isBalladMode)
            val barBlockLen = sectionBarBlockLen(section)
            val barMs = if (section.beatMs > 0L && beatsPerBar > 0) section.beatMs * beatsPerBar else 0L

            // VERSE(비발라드, enginePool=[ON_PULSE, BLINK])는 해시 기반 bar 블록 순환 대신,
            // 그 bar의 실제 에너지가 섹션 내 중앙값보다 높은지로 ON_PULSE/BLINK를 고른다.
            // 그루브처럼 리듬은 이어지는데 내부 에너지 기복이 큰 곡은, 해시 순환으로는 그 기복과
            // 무관하게 아무 때나 전환돼서 긴 VERSE 구간이 단조롭게 느껴졌다(실측: 사용자가 긴
            // VERSE 블록이 이어지는 그루브 곡에서 연출이 안 맞는다고 보고).
            val verseBarEnergy: Map<Int, Float> =
                if (section.type == SectionDetector.SectionType.VERSE && enginePool.size > 1 && barMs > 0L) {
                    effectiveBeats.map { barIndexAt(it, downbeatMs, section.beatMs, beatsPerBar) }.distinct()
                        .associateWith { barIdx ->
                            val barStartMs = downbeatMs + barIdx.toLong() * barMs
                            computeGroupEnergy(barStartMs, barStartMs + barMs, fullEnv, durationMs, hopMs)
                        }
                } else emptyMap()
            // 중앙값(0.5)을 기준으로 쓰면 "energy >= 기준값"일 때 BLINK라서 기준값과 같은
            // bar까지 BLINK로 들어가 ON_PULSE가 구조적으로 덜 나왔다(특히 distinct bar 수가
            // 적은 짧은 VERSE일수록 쏠림이 커짐). ON_PULSE가 너무 안 보인다는 실기 피드백으로
            // 기준을 상위 20%(0.80 percentile)로 올려 ON_PULSE 쪽 비중을 늘렸다.
            val verseEnergyBlinkCutoff =
                if (verseBarEnergy.isNotEmpty()) percentile(verseBarEnergy.values.toList(), 0.80f) else 0f

            for ((beatIndex, t) in effectiveBeats.withIndex()) {
                val barIdx = barIndexAt(t, downbeatMs, section.beatMs, beatsPerBar)
                val beatEngine = when {
                    section.type == SectionDetector.SectionType.BRIDGE ->
                        bridgePhaseEngine()
                    section.type == SectionDetector.SectionType.VERSE && verseBarEnergy.isNotEmpty() -> {
                        val energy = verseBarEnergy[barIdx] ?: 0f
                        if (energy >= verseEnergyBlinkCutoff) EffectMatchingEngine.FgEngine.BLINK
                        else EffectMatchingEngine.FgEngine.ON_PULSE
                    }
                    enginePool.size > 1 -> {
                        val blockIdx = Math.floorDiv(barIdx, barBlockLen)
                        enginePool[Math.floorMod(blockIdx, enginePool.size)]
                    }
                    else -> section.engine
                }

                val effectiveEngine = beatEngine

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, beatIndex, section.type, barIdx, isBalladMode)
                val bgNonNull = bg ?: LSColor(0, 0, 0)
                lastFg = fg

                val beatPeriod = when (effectiveEngine) {
                    EffectMatchingEngine.FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else            -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE  -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.ON_PULSE           -> null
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.VERSE -> 0
                    // 발라드 BRIDGE는 실제 SDK 랜덤딜레이를 줘서 기기마다 호흡 타이밍이
                    // 어긋나는 파도 효과를 낸다(INTRO 등 기존 BREATH와 동일 공식).
                    // 비발라드 BRIDGE는 기존처럼 beatPeriod(결정적, 파도 없음)를 유지한다.
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.BRIDGE && isBalladMode ->
                        msToBreathRandomDelay(section.beatMs)
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                        section.type == SectionDetector.SectionType.BRIDGE -> beatPeriod
                    effectiveEngine == EffectMatchingEngine.FgEngine.BREATH             -> msToBreathRandomDelay(section.beatMs)
                    else                                           -> null
                }
                // 발라드 BRIDGE는 SDK의 randomColor 플래그를 켜서 기기가 직접 색을 무작위로
                // 고르게 한다 — VERSE는 앱이 고른 컬러셋을 그대로 쓰므로 여기선 끈다.
                val beatRandomColor = if (effectiveEngine == EffectMatchingEngine.FgEngine.BREATH &&
                    section.type == SectionDetector.SectionType.BRIDGE && isBalladMode) 1 else 0
                val beatRotateTransit = if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE && isBalladMode)
                    ON_ROTATE_BALLAD_TRANSIT else 0

                val skipOnPulseOdd = (beatEngine == EffectMatchingEngine.FgEngine.ON_PULSE && beatIndex % 2 != 0)

                val skipRepeat = if (skipOnPulseOdd) {
                    true
                } else if (effectiveEngine == EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
                    || effectiveEngine == EffectMatchingEngine.FgEngine.BREATH
                    || effectiveEngine == EffectMatchingEngine.FgEngine.FREEZE) {
                    val key = RepeatKey(effectiveEngine,
                        fg.r, fg.g, fg.b, bgNonNull.r, bgNonNull.g, bgNonNull.b,
                        beatPeriod ?: 0, beatRandomDelay ?: 0)
                    val dup = (key == lastRepeatKey); lastRepeatKey = key; dup
                } else {
                    lastRepeatKey = null; false
                }

                if (!skipRepeat) {
                    put(t, buildPayload(effectiveEngine, fg, bg, section.beatMs, beatPeriod,
                        beatRandomDelay, rotateTransit = beatRotateTransit, randomColor = beatRandomColor))
                }

                if (beatEngine == EffectMatchingEngine.FgEngine.ON_PULSE && !skipOnPulseOdd) {
                    val holdMs = minOf(ON_PULSE_ACCENT_HOLD_MS * 2L, section.beatMs * 44L / 100L).coerceAtLeast(60L)
                    val offT   = minOf(section.endMs - 1L, t + holdMs)
                    if (offT > t)
                        put(offT, LSEffectPayload.Effects.off(transit = 3).toByteArray())
                }
            }
        }

        frameMap.keys.filter { it >= finalOffMs }.forEach { frameMap.remove(it) }
        val outroStartMs = sections.firstOrNull { it.type == SectionDetector.SectionType.OUTRO }?.startMs
        val outroLenMs = outroStartMs?.let { (finalOffMs - it).coerceAtLeast(0L) } ?: 0L

        // OUTRO 전체 구간을 여러 스텝으로 나눠 lastFg(OUTRO 직전까지 실제로 떠 있던 색)를
        // 점점 어둡게 만들어 보낸다 — 예전엔 finalOffMs 한 지점에서만 off(transit)이 한 번
        // 발생해서, OUTRO 시작부터 그 순간까지는 화면이 그대로 멈춰 있다가 끝에만 훅 꺼지는
        // 느낌이었다. 마지막 스텝은 on(검정)이 아니라 실제 off 명령을 쓴다.
        if (outroStartMs != null && outroLenMs > 0L) {
            val stepCount = (outroLenMs / OUTRO_FADE_STEP_MS).toInt().coerceIn(1, OUTRO_FADE_MAX_STEPS)
            val stepLenMs = outroLenMs / stepCount
            val stepTransit = (stepLenMs / OUTRO_TRANSIT_DIVISOR).toInt().coerceIn(OUTRO_TRANSIT_MIN, OUTRO_TRANSIT_MAX)
            Log.d(TAG, "EffectMatchingEngineV1 outro fade: outroStartMs=$outroStartMs finalOffMs=$finalOffMs " +
                "outroLenMs=$outroLenMs stepCount=$stepCount stepTransit=$stepTransit")
            for (i in 1..stepCount) {
                val tStep = outroStartMs + outroLenMs * i / stepCount
                if (i == stepCount) {
                    frameMap[tStep] = buildOffPayload(stepTransit)
                } else {
                    val fraction = 1f - i.toFloat() / stepCount
                    frameMap[tStep] = LSEffectPayload.Effects.on(color = scaleColor(lastFg, fraction), transit = stepTransit).toByteArray()
                }
            }
        } else {
            frameMap[finalOffMs] = buildOffPayload(OUTRO_TRANSIT_MIN)
        }

        return frameMap.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun scaleColor(c: LSColor, factor: Float): LSColor {
        val f = factor.coerceIn(0f, 1f)
        return LSColor((c.r * f).toInt().coerceIn(0, 255), (c.g * f).toInt().coerceIn(0, 255), (c.b * f).toInt().coerceIn(0, 255))
    }

    private fun computeGroupEnergy(startMs: Long, endMs: Long, fullEnv: List<Float>, durationMs: Long, hopMs: Long): Float {
        if (fullEnv.isEmpty()) return 0f
        val startIdx = (startMs / hopMs).toInt().coerceIn(0, fullEnv.lastIndex)
        val endIdx   = (endMs   / hopMs).toInt().coerceAtMost(fullEnv.lastIndex)
        if (endIdx <= startIdx) return fullEnv.getOrElse(startIdx) { 0f }
        return fullEnv.subList(startIdx, endIdx + 1).average().toFloat()
    }

    private fun assignFgEngine(
        type: SectionDetector.SectionType, isBalladMode: Boolean
    ): EffectMatchingEngine.FgEngine = when (type) {
        SectionDetector.SectionType.INTRO  -> EffectMatchingEngine.FgEngine.BREATH
        SectionDetector.SectionType.OUTRO  -> EffectMatchingEngine.FgEngine.OFF_TRANSIT
        // BREAK는 이제 "완전 무음" 구간만 가리키므로(SectionDetectorV2 참고), 숨쉬듯
        // 깜빡이는 BREATH 대신 특정 색을 켠 채 그대로 고정(freeze)해 정적을 표현한다.
        SectionDetector.SectionType.BREAK  -> EffectMatchingEngine.FgEngine.FREEZE
        // CLIMAX는 SectionDetectorV3 단계에서 검출 자체를 막아둬서 실제로 도달하지 않는다
        // (CHORUS와 동일하게 ON_TRANSIT_ROTATE로 매핑만 해 둠).
        SectionDetector.SectionType.CLIMAX -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
        SectionDetector.SectionType.VERSE  -> if (isBalladMode) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_PULSE
        SectionDetector.SectionType.CHORUS -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
        SectionDetector.SectionType.BRIDGE -> EffectMatchingEngine.FgEngine.BREATH
        SectionDetector.SectionType.END    -> EffectMatchingEngine.FgEngine.OFF_TRANSIT
    }

    /**
     * 섹션 성격에 맞는 이펙트 후보군.
     * assignFgEngine이 고르는 엔진을 pool[0]으로 유지하고, 같은 분위기에서 자연스러운
     * 두 번째 엔진을 pool[1]에 더해 buildFramesFromSections에서 bar 단위로 순환시킨다.
     * BRIDGE는 bridgePhaseEngine이 별도로 처리하므로 여기서는 사용되지 않는다.
     */
    private fun sectionEnginePool(
        type: SectionDetector.SectionType, isBalladMode: Boolean
    ): List<EffectMatchingEngine.FgEngine> = when (type) {
        SectionDetector.SectionType.INTRO  -> listOf(EffectMatchingEngine.FgEngine.BREATH)
        SectionDetector.SectionType.OUTRO  -> listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT)
        SectionDetector.SectionType.BREAK  -> listOf(EffectMatchingEngine.FgEngine.FREEZE)
        SectionDetector.SectionType.END    -> listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT)
        SectionDetector.SectionType.BRIDGE -> listOf(EffectMatchingEngine.FgEngine.BREATH)
        // CLIMAX는 SectionDetectorV3 단계에서 검출 자체를 막아둬서 실제로 도달하지 않는다.
        SectionDetector.SectionType.CLIMAX -> listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)
        SectionDetector.SectionType.VERSE ->
            if (isBalladMode) listOf(EffectMatchingEngine.FgEngine.BREATH)
            else listOf(EffectMatchingEngine.FgEngine.ON_PULSE, EffectMatchingEngine.FgEngine.BLINK)
        SectionDetector.SectionType.CHORUS ->
            listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)
    }

    /** 섹션마다 결정론적으로 2~5 사이의 bar 블록 길이를 고른다 (같은 곡이면 항상 같은 결과). */
    private fun sectionBarBlockLen(section: ResolvedSection): Int {
        val h = section.startMs * 2654435761L + section.type.ordinal * 97L
        return 2 + Math.floorMod(h, 4L).toInt()
    }

    /** downbeatMs를 기준으로 한 절대 bar 인덱스 (beatInBar와 달리 마디 내 위치가 아닌 마디 자체의 순번). */
    private fun barIndexAt(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return Math.floorDiv(steps, beatsPerBar.toLong()).toInt()
    }

    private fun buildSourceName(type: SectionDetector.SectionType, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO  -> "intro-breath"
            SectionDetector.SectionType.OUTRO  -> "outro-off"
            SectionDetector.SectionType.BREAK  -> "break-freeze"
            SectionDetector.SectionType.CLIMAX -> "climax-rotate"
            SectionDetector.SectionType.VERSE  -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END    -> "end-off"
        }

    // BRIDGE는 항상 BREATH만 쓴다(phase/relScore 기반으로 STROBE/ON_TRANSIT_ROTATE까지
    // 섞던 원본 로직은 실기 테스트 결과가 안 좋아 제거했다 — 필요하면 git 이력 참고).
    private fun bridgePhaseEngine(): EffectMatchingEngine.FgEngine = EffectMatchingEngine.FgEngine.BREATH

    // EffectColors.fgCycle을 인덱스로 순환시켜 (fg, bg) 쌍을 뽑는다 — 색상 자체에 대한
    // 판단은 전혀 없고, 팔레트에 이미 구워진 값을 인덱스로 집어오기만 한다.
    private fun pick(ec: EffectMatchingEngine.EffectColors, idx: Int): Pair<LSColor, LSColor?> =
        ec.fgCycle[Math.floorMod(idx, ec.fgCycle.size)] to ec.bg

    private fun colorsForEngine(
        palette: EffectMatchingEngine.Palette, engine: EffectMatchingEngine.FgEngine,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE,
        barIdx: Int = 0, isBalladMode: Boolean = false
    ): Pair<LSColor, LSColor?> = when (engine) {
        // ON_PULSE는 VERSE(비발라드)에서만 쓰인다 — palette.onPulse가 fg 순환/bg를 이미 담고 있다.
        EffectMatchingEngine.FgEngine.ON_PULSE -> pick(palette.onPulse, barIdx)
        // BLINK는 VERSE(비발라드)의 "그 bar가 섹션 내에서 에너지가 높은 구간"일 때만 쓰인다
        // (buildFramesFromSections의 verseBarEnergy 참고). palette.blink는 ON_PULSE와 같은 fg
        // 순환에 bg만 달리 구워둬서 ON_PULSE(낮은 에너지)와 또렷이 구분된다.
        EffectMatchingEngine.FgEngine.BLINK -> pick(palette.blink, barIdx)
        // ON_TRANSIT_ROTATE는 CHORUS에서만 배정된다(assignFgEngine/sectionEnginePool —
        // BRIDGE는 bridgePhaseEngine이 항상 BREATH만 반환하고, CLIMAX는 SectionDetectorV3
        // 단계에서 검출 자체를 막아둬서 도달하지 않는다). palette.chorus는 white로 시작해
        // 전환마다 또렷이 달라지도록 이미 구워져 있다.
        EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE -> pick(palette.chorus, beatIndex)
        // 발라드 BRIDGE만 palette.breathBridgeBallad(bg 검정, 조용한 느낌)를 쓴다 — FG까지
        // 검정이면 breath가 검정↔검정이 돼서 완전히 꺼진 것처럼 보이는 문제가 있었다(실측:
        // "BRIDGE가 OFF 상태로만 나옴"). 발라드 VERSE의 BREATH(palette.breathVerse)는 fg를
        // bar마다 순환시킨다 — ON_PULSE와 같은 방식. RANDOM DELAY=0(buildFramesFromSections)
        // 이라 모든 기기가 같은 타이밍으로 숨쉬므로, 색만 bar마다 바뀌어도 기기 간 어긋남
        // 없이 또렷하게 보인다. 그 외(INTRO, 비발라드 BRIDGE)는 palette.breathDefault(fg 고정
        // 1색).
        EffectMatchingEngine.FgEngine.BREATH -> when {
            sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> pick(palette.breathBridgeBallad, 0)
            sectionType == SectionDetector.SectionType.VERSE -> pick(palette.breathVerse, barIdx)
            else -> pick(palette.breathDefault, 0)
        }
        // FREEZE(BREAK): 깜빡임/회전 없이 palette.freeze(fg 고정 1색 + bg 검정)를 그대로 보여준다.
        EffectMatchingEngine.FgEngine.FREEZE -> pick(palette.freeze, 0)
        // OFF_TRANSIT 섹션은 buildFramesFromSections에서 이미 continue로 걸러져 여기 도달하지 않음
        EffectMatchingEngine.FgEngine.OFF_TRANSIT -> palette.black to palette.black
    }

    private fun buildPayload(
        engine: EffectMatchingEngine.FgEngine, fg: LSColor, bg: LSColor?, beatMs: Long,
        period: Int? = null, randomDelay: Int? = null, rotateTransit: Int = 0, randomColor: Int = 0
    ): ByteArray {
        val bgColor = bg ?: LSColor(0, 0, 0)
        return when (engine) {
            EffectMatchingEngine.FgEngine.ON_PULSE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            EffectMatchingEngine.FgEngine.BLINK ->
                LSEffectPayload.Effects.blink(period = period ?: msToBlinkPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomDelay = randomDelay ?: 0).toByteArray()
            EffectMatchingEngine.FgEngine.BREATH ->
                LSEffectPayload.Effects.breath(period = period ?: msToBreathPeriod(beatMs),
                    color = fg, backgroundColor = bgColor, randomColor = randomColor,
                    randomDelay = randomDelay ?: msToBreathRandomDelay(beatMs)).toByteArray()
            EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE ->
                LSEffectPayload.Effects.on(color = fg, transit = rotateTransit).toByteArray()
            // FREEZE: 애니메이션 없이 한 번 켜서 그대로 유지한다(transit=0, 이후 재전송 없음).
            EffectMatchingEngine.FgEngine.FREEZE ->
                LSEffectPayload.Effects.on(color = fg, transit = 0).toByteArray()
            EffectMatchingEngine.FgEngine.OFF_TRANSIT -> buildOffPayload()
        }
    }

    private fun buildOffPayload(transit: Int = ON_TRANSIT): ByteArray = LSEffectPayload.Effects.off(transit = transit).toByteArray()

    // period는 10ms 단위고 FG+BG를 합친 한 사이클 전체 길이다(예: period=50 → 250ms FG +
    // 250ms BG = 500ms). beatMs/10("1비트=1사이클")은 느리고, beatMs/40도 실기 테스트로는
    // 박자가 안 맞았다 — beatMs/100이 체감상 박자에 얼추 맞는다는 실기 피드백으로 조정.
    private fun msToBlinkPeriod(beatMs: Long)        = (beatMs / 100L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }
}
