package com.lightstick.music.domain.music

import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.util.Log
import com.lightstick.types.Color as LSColor
import com.lightstick.types.LSEffectPayload
import kotlin.math.max
import kotlin.math.min

// RGB->hue 변환 유틸리티(색상 정렬/비교용). PaletteFamily는 enum이라 outer 클래스
// 인스턴스에 접근 못 하므로, EffectMatchingEngineV1 본문과 공유하려면 top-level이어야
// 한다. light/deep은 더 이상 여기서 계산하지 않고 PaletteFamily에 실측값 그대로
// 상수로 들고 있는다(아래 enum 참고).
private fun wrap360(h: Float) = ((h % 360f) + 360f) % 360f

private fun hueOf(c: LSColor): Float {
    val r = c.r / 255f; val g = c.g / 255f; val b = c.b / 255f
    val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
    val d = maxC - minC
    if (d < 1e-6f) return 0f
    val h = when (maxC) {
        r    -> (((g - b) / d) % 6f + 6f) % 6f
        g    -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return wrap360(h * 60f)
}

// 팔레트 색 계열(PaletteFamily)이 쓰는 실측 RGB 스와치. 이름을 붙여두면 코드만 보고도
// 바로 어떤 색인지 알 수 있다 — 전부 응원봉 실기로 확인한 S=100%·V=100% 순수 색상.
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

// 위 스와치들의 파생색 — cMain으로 선택된 스와치와 같은 hue에서 _LIGHT는 채도만
// 80%로 낮춘 "옅지만 밝은" 포인트색(cStep4), _SHADE는 명도만 48%로 낮춘 어두운 톤
// (cDeep)이다. 매번 계산하지 않고 값 그대로 이름 붙여 선언해둔다.
private val PURPLE_MAGENTA_LIGHT = LSColor(220, 50, 255)  // 자주보라 라이트
private val PURPLE_VIOLET_LIGHT  = LSColor(182, 50, 255)  // 보라 라이트
private val PURPLE_INDIGO_LIGHT  = LSColor(143, 50, 255)  // 남보라 라이트
private val PURPLE_NAVY_LIGHT    = LSColor(104, 50, 255)  // 남색 라이트
private val PURPLE_MAGENTA_SHADE = LSColor(101, 0, 122)   // 자주보라 셰이드
private val PURPLE_VIOLET_SHADE  = LSColor(78, 0, 122)    // 보라 셰이드
private val PURPLE_INDIGO_SHADE  = LSColor(55, 0, 122)    // 남보라 셰이드
private val PURPLE_NAVY_SHADE    = LSColor(32, 0, 122)    // 남색 셰이드

private val RED_MAGENTA_LIGHT   = LSColor(255, 50, 250)  // 자홍 라이트
private val RED_ROSE_LIGHT      = LSColor(255, 50, 174)  // 로즈 라이트
private val RED_PURE_LIGHT      = LSColor(255, 50, 50)   // 순수 빨강 라이트
private val RED_VERMILION_LIGHT = LSColor(255, 77, 50)   // 다홍 라이트
private val RED_MAGENTA_SHADE   = LSColor(122, 0, 119)   // 자홍 셰이드
private val RED_ROSE_SHADE      = LSColor(122, 0, 74)    // 로즈 셰이드
private val RED_PURE_SHADE      = LSColor(122, 0, 0)     // 순수 빨강 셰이드
private val RED_VERMILION_SHADE = LSColor(122, 15, 0)    // 다홍 셰이드

private val ORANGE_GOLD_DEEP_LIGHT   = LSColor(255, 90, 50)   // 진한 다홍주황 라이트
private val ORANGE_GOLD_ORANGE_LIGHT = LSColor(255, 144, 50)  // 주황 라이트
private val ORANGE_GOLD_AMBER_LIGHT  = LSColor(255, 197, 50)  // 골드 라이트
private val ORANGE_GOLD_LIME_LIGHT   = LSColor(255, 250, 50)  // 라임옐로우 라이트
private val ORANGE_GOLD_DEEP_SHADE   = LSColor(122, 23, 0)    // 진한 다홍주황 셰이드
private val ORANGE_GOLD_ORANGE_SHADE = LSColor(122, 56, 0)    // 주황 셰이드
private val ORANGE_GOLD_AMBER_SHADE  = LSColor(122, 87, 0)    // 골드 셰이드
private val ORANGE_GOLD_LIME_SHADE   = LSColor(122, 119, 0)   // 라임옐로우 셰이드

private val TEAL_YELLOWGREEN_LIGHT = LSColor(107, 255, 50)  // 연두 라이트
private val TEAL_GREEN_LIGHT       = LSColor(50, 255, 79)   // 초록 라이트
private val TEAL_MINT_LIGHT        = LSColor(50, 255, 167)  // 민트 라이트
private val TEAL_CYAN_LIGHT        = LSColor(50, 255, 255)  // 청록(시안) 라이트
private val TEAL_YELLOWGREEN_SHADE = LSColor(34, 122, 0)    // 연두 셰이드
private val TEAL_GREEN_SHADE       = LSColor(0, 122, 17)    // 초록 셰이드
private val TEAL_MINT_SHADE        = LSColor(0, 122, 70)    // 민트 셰이드
private val TEAL_CYAN_SHADE        = LSColor(0, 122, 122)   // 청록(시안) 셰이드

private val BLUE_SEAFOAM_LIGHT = LSColor(50, 255, 179)  // 민트청록 라이트
private val BLUE_SKYCYAN_LIGHT = LSColor(50, 236, 255)  // 하늘청록 라이트
private val BLUE_AZURE_LIGHT   = LSColor(50, 143, 255)  // 파랑 라이트
private val BLUE_PURE_LIGHT    = LSColor(50, 50, 255)   // 순수 파랑 라이트
private val BLUE_SEAFOAM_SHADE = LSColor(0, 122, 77)    // 민트청록 셰이드
private val BLUE_SKYCYAN_SHADE = LSColor(0, 111, 122)   // 하늘청록 셰이드
private val BLUE_AZURE_SHADE   = LSColor(0, 55, 122)    // 파랑 셰이드
private val BLUE_PURE_SHADE    = LSColor(0, 0, 122)     // 순수 파랑 셰이드

/**
 * EffectMatchingEngineV1 - V3의 이펙트 매칭 룰
 * 섹션 타입별 이펙트 엔진 할당 (ON_PULSE / STROBE / BREATH / ON_TRANSIT_ROTATE 등)
 */
class EffectMatchingEngineV1 : EffectMatchingEngine {

    companion object {
        private const val TAG = AppConstants.Feature.AUTO_TIMELINE
        private const val ON_TRANSIT = 2
        private const val ON_PULSE_ACCENT_HOLD_MS = 200L
        private const val ON_ROTATE_BALLAD_TRANSIT = ON_TRANSIT
        // CLIMAX 엔진을 BPM 130 기준으로 양분한다(번갈아 쓰지 않고 섹션 전체에 하나만) —
        // 130 BPM = beatMs 461.5. beatMs가 이보다 작으면(=BPM이 더 높으면) STROBE,
        // 크면(BPM이 더 낮으면) ON_TRANSIT_ROTATE.
        private const val CLIMAX_STROBE_MAX_BEAT_MS = 461L
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

        // 팔레트 색 계열(세트) — 곡의 색을 "아무 색상"이 아니라 미리 정해둔 계열
        // (PaletteFamily의 rgb 4개, 전부 응원봉 실기로 확인한 값) 중 하나로 고정한다.
        // 과거엔 계열 안에서도 곡마다 ±지터로 hue를 살짝 돌렸지만, 지금은 실측값을
        // 그대로 보존하기 위해 지터 없이 rgb를 그대로 쓴다 — 곡별 다양성은 장르별
        // 계열 후보군(families)과 역할 배치(rolePermutation)만으로 충분히 나온다.

        // 장르(MusicStyle)별 팔레트 설정을 GenreTheme 하나로 묶는다 — 예전엔
        // GENRE_PALETTE_FAMILIES(계열 후보군)와 GENRE_ROLE_PERMUTATION(역할 배치)이
        // 장르별로 따로따로 Map에 떨어져 있어서, 장르를 추가/수정할 때 둘을 따로 맞춰야
        // 했고 서로 안 맞게 어긋날 위험이 있었다.
        //
        // families: 장르에 어울리는 색 계열 후보군. 후보가 여럿이면 seed(musicId)로 그중
        //   하나를 결정론적으로 고른다 — 같은 장르 곡들이 전부 똑같은 색은 아니게 하되,
        //   분위기(계열)는 장르에 맞춘다.
        // rolePermutation: 한 계열(PaletteFamily)은 hues 4개의 고정된 색 후보를 갖는다.
        //   이 4개 중 "어느 것을 cMain으로 쓸지" + 나머지를 cStep1~3에
        //   어떤 순서로 배치할지를 장르마다 다르게 둬서, 같은 계열이 걸려도 장르마다
        //   대표색(cMain)이 달라 보이는 효과를 낸다(cStep4는 역할 배치에 안 들고 항상
        //   cMain과 같은 hue의 옅은 포인트색 — buildPalette 참고).
        //   rolePermutation[0](=cMain으로 쓸 raw 인덱스)은 임의로 고른 게 아니라, 실사용
        //   비중이 큰 BALLAD/EDM/DANCE_POP/POP이 공유하는 계열별로 "그 계열을 쓰는
        //   장르들의 cMain끼리 최대한 멀어지도록" 전수 탐색(4^4=256 조합)으로 찾은 값이다
        //   (최소 간격 11.3도). 이 조건을 만족하는 조합이 여러 개라 그중 DANCE_POP의
        //   cMain이 RED의 순수 빨강(hues[2]=0도)이 되는 조합을 택했다. ROCK/HIPHOP_RNB는
        //   실사용 비중이 낮아 이 최적화에서 제외하고 같은 계열을 쓰는 장르(ROCK→EDM,
        //   HIPHOP_RNB→BALLAD)의 값을 그대로 가져다 쓴다.
        private data class GenreTheme(val families: List<PaletteFamily>, val rolePermutation: List<Int>)

        private val GENRE_THEMES: Map<MusicStyleClassifier.MusicStyle, GenreTheme> = mapOf(
            MusicStyleClassifier.MusicStyle.BALLAD to GenreTheme(
                families = listOf(PaletteFamily.TEAL, PaletteFamily.PURPLE, PaletteFamily.BLUE),
                rolePermutation = listOf(3, 0, 1, 2)
            ),
            MusicStyleClassifier.MusicStyle.EDM to GenreTheme(
                families = listOf(PaletteFamily.RED, PaletteFamily.ORANGE_GOLD, PaletteFamily.PURPLE),
                rolePermutation = listOf(0, 1, 2, 3)
            ),
            MusicStyleClassifier.MusicStyle.DANCE_POP to GenreTheme(
                families = listOf(PaletteFamily.RED, PaletteFamily.TEAL, PaletteFamily.BLUE),
                rolePermutation = listOf(2, 0, 1, 3)
            ),
            MusicStyleClassifier.MusicStyle.HIPHOP_RNB to GenreTheme(
                families = listOf(PaletteFamily.PURPLE, PaletteFamily.BLUE, PaletteFamily.TEAL),
                rolePermutation = listOf(3, 0, 1, 2)
            ),
            MusicStyleClassifier.MusicStyle.ROCK to GenreTheme(
                families = listOf(PaletteFamily.RED, PaletteFamily.ORANGE_GOLD),
                rolePermutation = listOf(0, 1, 2, 3)
            ),
            MusicStyleClassifier.MusicStyle.POP to GenreTheme(
                families = PaletteFamily.values().toList(),
                rolePermutation = listOf(1, 0, 2, 3)
            )
        )
    }

    /**
     * 팔레트 색 계열 — rgb는 cMain/cStep1~3 후보가 될 4개의 RGB(위에 이름 붙여둔 실측
     * 스와치). light/deep은 rgb[i]가 cMain으로 선택됐을 때 쓰는 cStep4(채도만 80%로
     * 낮춘 라이트 포인트색)·cDeep(명도만 48%로 낮춘 어두운 톤)을 같은 인덱스로 미리
     * 계산해 둔 값 — buildPalette에서는 rolePerm[0]으로 세 리스트 모두 바로 꺼내 쓴다.
     * 각 계열의 첫 번째·마지막 스와치가 실제 응원봉 펌웨어 색상표에서 고른 두 "끝점"
     * 색상이고, 가운데 둘은 그 사이를 hue 기준 3등분한 값이 기본이다. RED만 3등분 보간
     * 그대로 쓰면 정작 순수 RED_PURE(255,0,0)가 네 포인트 중 어디에도 걸리지 않아서
     * (실측 피드백) 세 번째 자리를 보간값 대신 RED_PURE로 고정해뒀다. RED/ORANGE_GOLD
     * 경계처럼 한두 계열은 끝점이 살짝 겹치는 것도 실측 확인 후 허용한 값이다(겹쳐도
     * 색 1개 정도라 문제 없음).
     */
    private enum class PaletteFamily(
        val rgb: List<LSColor>,
        val light: List<LSColor>,
        val deep: List<LSColor>
    ) {
        PURPLE(
            rgb   = listOf(PURPLE_MAGENTA, PURPLE_VIOLET, PURPLE_INDIGO, PURPLE_NAVY),
            light = listOf(PURPLE_MAGENTA_LIGHT, PURPLE_VIOLET_LIGHT, PURPLE_INDIGO_LIGHT, PURPLE_NAVY_LIGHT),
            deep  = listOf(PURPLE_MAGENTA_SHADE, PURPLE_VIOLET_SHADE, PURPLE_INDIGO_SHADE, PURPLE_NAVY_SHADE)
        ),
        // 0/360도 경계를 넘어간다. 세 번째 자리는 보간값이 아니라 RED_PURE로 고정.
        RED(
            rgb   = listOf(RED_MAGENTA, RED_ROSE, RED_PURE, RED_VERMILION),
            light = listOf(RED_MAGENTA_LIGHT, RED_ROSE_LIGHT, RED_PURE_LIGHT, RED_VERMILION_LIGHT),
            deep  = listOf(RED_MAGENTA_SHADE, RED_ROSE_SHADE, RED_PURE_SHADE, RED_VERMILION_SHADE)
        ),
        ORANGE_GOLD(
            rgb   = listOf(ORANGE_GOLD_DEEP, ORANGE_GOLD_ORANGE, ORANGE_GOLD_AMBER, ORANGE_GOLD_LIME),
            light = listOf(ORANGE_GOLD_DEEP_LIGHT, ORANGE_GOLD_ORANGE_LIGHT, ORANGE_GOLD_AMBER_LIGHT, ORANGE_GOLD_LIME_LIGHT),
            deep  = listOf(ORANGE_GOLD_DEEP_SHADE, ORANGE_GOLD_ORANGE_SHADE, ORANGE_GOLD_AMBER_SHADE, ORANGE_GOLD_LIME_SHADE)
        ),
        TEAL(
            rgb   = listOf(TEAL_YELLOWGREEN, TEAL_GREEN, TEAL_MINT, TEAL_CYAN),
            light = listOf(TEAL_YELLOWGREEN_LIGHT, TEAL_GREEN_LIGHT, TEAL_MINT_LIGHT, TEAL_CYAN_LIGHT),
            deep  = listOf(TEAL_YELLOWGREEN_SHADE, TEAL_GREEN_SHADE, TEAL_MINT_SHADE, TEAL_CYAN_SHADE)
        ),
        // 네 번째 자리(BLUE_PURE)가 이미 순수 파랑(0,0,255).
        BLUE(
            rgb   = listOf(BLUE_SEAFOAM, BLUE_SKYCYAN, BLUE_AZURE, BLUE_PURE),
            light = listOf(BLUE_SEAFOAM_LIGHT, BLUE_SKYCYAN_LIGHT, BLUE_AZURE_LIGHT, BLUE_PURE_LIGHT),
            deep  = listOf(BLUE_SEAFOAM_SHADE, BLUE_SKYCYAN_SHADE, BLUE_AZURE_SHADE, BLUE_PURE_SHADE)
        )
    }

    data class V8Section(
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
        // 장르에 어울리는 색 계열 후보군 중 하나를 seed로 결정론적으로 고른다 — 같은
        // 장르 곡들이 전부 똑같은 색은 아니게 하되, 계열 자체(=실측 RGB)는 그대로 쓴다.
        val theme = GENRE_THEMES[musicStyle]
        val candidates = theme?.families ?: PaletteFamily.values().toList()
        val family = candidates[Math.floorMod(rawHue, candidates.size)]
        Log.d(TAG, "[Palette] style=$musicStyle family=$family")
        // 장르별 역할 배치(GenreTheme.rolePermutation)에 따라 family.rgb(4개 고정 RGB)를
        // cMain/cStep1~3으로 재배정한다 — 실측값을 그대로 쓰므로 변환이 필요 없다.
        val rolePerm = theme?.rolePermutation ?: listOf(0, 1, 2, 3)
        val cMain  = family.rgb[rolePerm[0]]
        val cStep1 = family.rgb[rolePerm[1]]
        val cStep2 = family.rgb[rolePerm[2]]
        val cStep3 = family.rgb[rolePerm[3]]
        // cStep4: 계열 역할 배치에 안 들고, cMain과 같은 hue를 채도만 낮춰(80%) 밝기는
        // 그대로(100%) 유지한 "옅지만 밝은" 포인트색이다(기존 응원봉 펌웨어의
        // COLOR_LIGHTPURPLE(153,51,255)가 COLOR_PURPLE(70,0,255)보다 채도만 낮고
        // 명도는 그대로인 것과 같은 방식 — 밝기는 최대한 유지해야 한다는 요구사항에 맞춰
        // 명도가 아닌 채도로만 "옅은" 느낌을 낸다). family.light/deep에 미리 계산해둔
        // 값을 cMain과 같은 인덱스(rolePerm[0])로 바로 꺼내 쓴다.
        val cStep4 = family.light[rolePerm[0]]
        val cDeep  = family.deep[rolePerm[0]]
        val black  = LSColor(0, 0, 0)
        val white = LSColor(255, 255, 255)
        // colorGroup(VERSE 순환용, cStep 빈도 균등화): family.rgb 4개는 hue 기준 3등분
        // 보간이라 간격이 이미 고르게 벌어져 있어 hue 정렬만 하면 되고(zigzag 재배치
        // 불필요), cStep4(포인트색)는 맨 뒤에 추가한다.
        val mainHue = hueOf(cMain)
        fun signedOffsetFromBase(h: Float) = wrap360(h - mainHue + 180f) - 180f
        val sortedArc = listOf(
            mainHue to cMain, hueOf(cStep1) to cStep1, hueOf(cStep2) to cStep2, hueOf(cStep3) to cStep3
        ).sortedBy { signedOffsetFromBase(it.first) }
        val colorGroup = sortedArc.map { it.second } + cStep4
        // CHORUS는 "탁탁 튀는" 전환이 핵심이라 family.rgb 4개 중 서로 hue 차이가 가장 크게
        // 벌어지는 3개만 골라 white와 함께 4개로 순환시킨다. 가운데 두 지점(index 1,2)
        // 중 하나를 빼는 두 가지 경우만 비교하면 되는데(양끝은 항상 남겨야 스펙트럼이
        // 최대로 벌어짐), 뺐을 때 생기는 두 구간 중 더 좁은 쪽이 큰 경우를 택한다.
        val (h0, c0) = sortedArc[0]; val (h1, c1) = sortedArc[1]
        val (h2, c2) = sortedArc[2]; val (h3, c3) = sortedArc[3]
        val gap01 = signedOffsetFromBase(h1) - signedOffsetFromBase(h0)
        val gap12 = signedOffsetFromBase(h2) - signedOffsetFromBase(h1)
        val gap23 = signedOffsetFromBase(h3) - signedOffsetFromBase(h2)
        // index1 제외: 남는 간격 (gap01+gap12), gap23 중 최소값
        val minDropMid1 = min(gap01 + gap12, gap23)
        // index2 제외: 남는 간격 gap01, (gap12+gap23) 중 최소값
        val minDropMid2 = min(gap01, gap12 + gap23)
        val chorusColors = if (minDropMid1 >= minDropMid2)
            listOf(white, c0, c2, c3)
        else
            listOf(white, c0, c1, c3)
        val cMainLuma  = 0.299f * cMain.r + 0.587f * cMain.g + 0.114f * cMain.b
        val patternABg = if (cMainLuma >= 128f) cDeep else cMain
        // VERSE BLINK: BG는 빨강에 가장 가까운 색(closestToRed)으로 고정하고, FG는 BG와
        // 겹치지 않는 나머지 색 중 4개를 bar마다 순환시킨다. cMain/cStep1~4 5개 중 BG로
        // 쓰인 1개를 뺀 나머지 4개가 그대로 FG 후보가 된다.
        val verseBlinkBg = closestToRed(colorGroup)
        val verseBlinkFgCycle = listOf(cStep1, cStep2, cStep3, cStep4, cMain)
            .filterNot { it == verseBlinkBg }.take(4)
        return EffectMatchingEngine.Palette(
            black       = black, white = white,
            // 패턴B(onPulseBg[1])의 BG를 black에서 cDeep으로 바꾼다 — BRIDGE의 BREATH가
            // BG를 black으로 뒀을 때 "꺼진 것처럼 보임" 피드백이 있었는데, ON_PULSE도 같은
            // 문제를 겪을 수 있어서 같은 색 계열의 어두운 톤(cDeep)으로 바꿔 테스트해본다.
            onPulseBg = listOf(patternABg, cDeep),
            blinkSets   = listOf(EffectMatchingEngine.ColorSet(cStep2, black), EffectMatchingEngine.ColorSet(cStep3, black)),
            strokeSets  = listOf(EffectMatchingEngine.ColorSet(white, black)),
            // BG는 patternABg가 아니라 항상 cDeep으로 고정한다 — PURPLE/RED 계열은
            // cMain 밝기가 낮아 patternABg가 거의 항상 cMain 자신으로 떨어지는데, 그러면
            // FG(cStep4, 채도 80%)와 BG(cMain, 채도 100%)가 같은 hue에 채도 차이뿐이라
            // 숨쉬기(breath) 명암 대비가 거의 안 느껴졌다. cDeep(명도 48%)으로 고정하면
            // 계열과 무관하게 명도 차이로 또렷한 대비가 난다.
            breathSet   = EffectMatchingEngine.ColorSet(cStep4, cDeep),
            bridgeSets  = listOf(EffectMatchingEngine.ColorSet(cStep2, black), EffectMatchingEngine.ColorSet(cMain, black)),
            chorusBg    = cDeep, colorGroup = colorGroup,
            verseBlinkBg = verseBlinkBg,
            verseBlinkFgCycle = verseBlinkFgCycle,
            chorusColors = chorusColors
        )
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
        hopMs: Long,
        isDancePopMode: Boolean
    ): List<Pair<Long, ByteArray>> {
        if (sectionGroups.isEmpty()) return emptyList()

        // 섹션을 V8 섹션으로 변환 (에너지 기반 엔진 할당)
        val globalBeatMs = if (beatTimesMs.size > 1)
            beatTimesMs[1] - beatTimesMs[0] else 500L

        val effectiveHopMs = if (hopMs > 0L) hopMs else 10L
        val v8Sections = convertToV8Sections(sectionGroups, globalBeatMs, isBalladMode, fullEnv, durationMs, effectiveHopMs, isDancePopMode)

        // V8 섹션으로부터 프레임 빌드
        return buildFramesFromSections(palette, v8Sections, beatTimesMs, durationMs, isBalladMode, finalOffMs,
            downbeatMs, beatsPerBar, fullEnv, effectiveHopMs, isDancePopMode)
    }

    private fun convertToV8Sections(
        groups: List<EffectMatchingEngine.SectionGroup>,
        beatMs: Long,
        isBalladMode: Boolean,
        fullEnv: List<Float>,
        durationMs: Long,
        hopMs: Long,
        isDancePopMode: Boolean
    ): List<V8Section> {
        if (groups.isEmpty()) return emptyList()

        val energies = groups.map { g -> computeGroupEnergy(g.startMs, g.endMs, fullEnv, durationMs, hopMs) }
        val lowTh    = percentile(energies, 0.35f)
        val highTh   = percentile(energies, 0.70f)
        val range    = (highTh - lowTh).coerceAtLeast(1e-6f)

        return groups.mapIndexed { i, g ->
            val energy   = energies[i]
            val beats    = g.annotatedBeats.size
            val relScore = ((energy - lowTh) / range).coerceIn(0f, 1f)

            // 예전엔 6비트 미만 BRIDGE를 V8Section.type 단계에서 VERSE로 재분류했는데,
            // 이게 EffectMatchingEngineV1 내부에서만 일어나는 변환이라 SectionDetectorV3가
            // 내보내는 실제 섹션 타입(Beat Accuracy Checker 등에 표시되는 값)과 어긋나는
            // 문제가 있었다 — 체커에는 BRIDGE로 보이는데 실제 이펙트는 VERSE의 BLINK로
            // 나가서 혼동을 줌(실측 피드백). 타입은 항상 g.type 그대로 두고, 짧은 BRIDGE도
            // bridgePhaseEngine을 그대로 거치게 한다.
            val engine = assignFgEngine(g.type, relScore, beats, globalBeatMs = beatMs,
                isBalladMode = isBalladMode, isDancePopMode = isDancePopMode)

            val source = buildSourceName(g.type, engine, beats)

            val change = when {
                g.type == SectionDetector.SectionType.BRIDGE && beats < 20 ->
                    EffectMatchingEngine.ChangeLevel.STRONG
                beats < 8 -> EffectMatchingEngine.ChangeLevel.MEDIUM
                else      -> EffectMatchingEngine.ChangeLevel.STRONG
            }

            V8Section(
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
        sections: List<V8Section>,
        beatTimesMs: List<Long>,
        durationMs: Long,
        isBalladMode: Boolean,
        finalOffMs: Long,
        downbeatMs: Long,
        beatsPerBar: Int,
        fullEnv: List<Float>,
        hopMs: Long,
        isDancePopMode: Boolean
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

        val sameTypeCountMap = mutableMapOf<SectionDetector.SectionType, Int>()

        for ((index, section) in sections.withIndex()) {
            val sameTypeIdx = sameTypeCountMap.getOrDefault(section.type, 0)
            sameTypeCountMap[section.type] = sameTypeIdx + 1
            lastRepeatKey = null

            val effectiveBeats = section.beatTimesMs

            if (section.engine == EffectMatchingEngine.FgEngine.OFF_TRANSIT) continue

            val enginePool  = sectionEnginePool(section.type, section.relScore, section.beatMs, isBalladMode, isDancePopMode)
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
                        bridgePhaseEngine(beatIndex, effectiveBeats.size, section.beatMs, section.relScore, isBalladMode)
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

                val (fg, bg) = colorsForEngine(palette, effectiveEngine, sameTypeIdx, beatIndex, section.type, barIdx, isBalladMode)
                val bgNonNull = bg ?: LSColor(0, 0, 0)
                lastFg = fg

                val beatPeriod = when (effectiveEngine) {
                    EffectMatchingEngine.FgEngine.STROBE -> 1
                    EffectMatchingEngine.FgEngine.BREATH -> msToBreathPeriod(section.beatMs)
                    else            -> null
                }
                val beatRandomDelay = when {
                    effectiveEngine == EffectMatchingEngine.FgEngine.STROBE             -> 1
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
                    || effectiveEngine == EffectMatchingEngine.FgEngine.STROBE
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
        type: SectionDetector.SectionType,
        rel: Float, beats: Int, globalBeatMs: Long,
        isBalladMode: Boolean, isDancePopMode: Boolean = false
    ): EffectMatchingEngine.FgEngine = when (type) {
        SectionDetector.SectionType.INTRO  -> EffectMatchingEngine.FgEngine.BREATH
        SectionDetector.SectionType.OUTRO  -> EffectMatchingEngine.FgEngine.OFF_TRANSIT
        // BREAK는 이제 "완전 무음" 구간만 가리키므로(SectionDetectorV2 참고), 숨쉬듯
        // 깜빡이는 BREATH 대신 특정 색을 켠 채 그대로 고정(freeze)해 정적을 표현한다.
        SectionDetector.SectionType.BREAK  -> EffectMatchingEngine.FgEngine.FREEZE

        // CLIMAX 전용 연출(STROBE/BPM-DANCE_POP 분기)이 실기로는 너무 안 어울린다는
        // 피드백으로 당분간 막아둔다 — CHORUS와 동일하게 ON_TRANSIT_ROTATE만 쓴다.
        // (이후 SectionDetectorV3 쪽에서 CLIMAX 검출 자체도 막아서, 지금은 이 분기가
        // 사실상 도달하지 않는다 — 두 레벨 다 복원 시 각자의 주석을 해제하면 된다.)
        SectionDetector.SectionType.CLIMAX -> EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
        // if (isDancePopMode || globalBeatMs in 1..CLIMAX_STROBE_MAX_BEAT_MS)
        //     EffectMatchingEngine.FgEngine.STROBE
        // else
        //     EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE

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
        type: SectionDetector.SectionType, rel: Float, globalBeatMs: Long, isBalladMode: Boolean,
        isDancePopMode: Boolean = false
    ): List<EffectMatchingEngine.FgEngine> = when (type) {
        SectionDetector.SectionType.INTRO  -> listOf(EffectMatchingEngine.FgEngine.BREATH)
        SectionDetector.SectionType.OUTRO  -> listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT)
        SectionDetector.SectionType.BREAK  -> listOf(EffectMatchingEngine.FgEngine.FREEZE)
        SectionDetector.SectionType.END    -> listOf(EffectMatchingEngine.FgEngine.OFF_TRANSIT)
        SectionDetector.SectionType.BRIDGE -> listOf(EffectMatchingEngine.FgEngine.BREATH)

        // assignFgEngine과 동일하게 당분간 막아둠 — CHORUS와 같은 ON_TRANSIT_ROTATE만.
        // 복원 시 아래 주석을 해제(BPM 130 기준 STROBE/ON_TRANSIT_ROTATE, DANCE_POP은 항상 STROBE).
        SectionDetector.SectionType.CLIMAX -> listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)
        // if (isDancePopMode || globalBeatMs in 1..CLIMAX_STROBE_MAX_BEAT_MS)
        //     listOf(EffectMatchingEngine.FgEngine.STROBE)
        // else
        //     listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)

        SectionDetector.SectionType.VERSE ->
            if (isBalladMode) listOf(EffectMatchingEngine.FgEngine.BREATH)
            else listOf(EffectMatchingEngine.FgEngine.ON_PULSE, EffectMatchingEngine.FgEngine.BLINK)

        // STROBE는 CLIMAX 전용 연출로 남긴다 — CHORUS 풀에 섞으면 CLIMAX 여부와 무관하게
        // CHORUS 전체에서 bar마다 절반씩 STROBE가 나와, CLIMAX가 좁은 한 구간인데도
        // STROBE는 훨씬 넓게 반복되는 결과가 됐었다.
        SectionDetector.SectionType.CHORUS ->
            listOf(EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE)
    }

    /** 섹션마다 결정론적으로 2~5 사이의 bar 블록 길이를 고른다 (같은 곡이면 항상 같은 결과). */
    private fun sectionBarBlockLen(section: V8Section): Int {
        val h = section.startMs * 2654435761L + section.type.ordinal * 97L
        return 2 + Math.floorMod(h, 4L).toInt()
    }

    /** downbeatMs를 기준으로 한 절대 bar 인덱스 (beatInBar와 달리 마디 내 위치가 아닌 마디 자체의 순번). */
    private fun barIndexAt(tMs: Long, downbeatMs: Long, globalBeatMs: Long, beatsPerBar: Int): Int {
        if (globalBeatMs <= 0L || beatsPerBar <= 0) return 0
        val steps = Math.round((tMs - downbeatMs).toDouble() / globalBeatMs.toDouble())
        return Math.floorDiv(steps, beatsPerBar.toLong()).toInt()
    }

    private fun buildSourceName(type: SectionDetector.SectionType, engine: EffectMatchingEngine.FgEngine, beats: Int): String =
        when (type) {
            SectionDetector.SectionType.INTRO  -> "intro-breath"
            SectionDetector.SectionType.OUTRO  -> "outro-off"
            SectionDetector.SectionType.BREAK  -> "break-freeze"
            SectionDetector.SectionType.CLIMAX -> if (engine == EffectMatchingEngine.FgEngine.STROBE) "climax-strobe" else "climax-rotate"
            SectionDetector.SectionType.VERSE  -> "verse-on-pulse"
            SectionDetector.SectionType.CHORUS -> "chorus-rotate"
            SectionDetector.SectionType.BRIDGE -> "bridge-breath"
            SectionDetector.SectionType.END    -> "end-off"
        }

    // 테스트용: BRIDGE는 임시로 BREATH만 쓰도록 단순화했다(원래는 phase/relScore에 따라
    // STROBE/ON_TRANSIT_ROTATE도 섞였음 — 아래 원본 로직 주석 참고).
    private fun bridgePhaseEngine(
        beatIndex: Int, totalBeats: Int, beatMs: Long, relScore: Float, isBalladMode: Boolean
    ): EffectMatchingEngine.FgEngine = EffectMatchingEngine.FgEngine.BREATH
    // 원본 로직(복원 시 위 한 줄을 지우고 이 블록 주석 해제):
    // if (isBalladMode || relScore < 0.1f) return EffectMatchingEngine.FgEngine.BREATH
    // if (totalBeats <= 0) return EffectMatchingEngine.FgEngine.STROBE
    // val strobeEntry = (0.80f - relScore * 0.55f).coerceIn(0.20f, 0.85f)
    // return when {
    //     totalBeats < 8  -> EffectMatchingEngine.FgEngine.STROBE
    //     totalBeats < 16 -> {
    //         val phase = beatIndex.toFloat() / totalBeats
    //         if (phase < strobeEntry) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
    //     }
    //     else -> {
    //         val phase      = beatIndex.toFloat() / totalBeats
    //         val rotateEntry = (strobeEntry - 0.25f - relScore * 0.10f).coerceIn(0.10f, strobeEntry - 0.10f)
    //         if (phase < rotateEntry) EffectMatchingEngine.FgEngine.BREATH else EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE
    //     }
    // }

    private fun colorsForEngine(
        palette: EffectMatchingEngine.Palette, engine: EffectMatchingEngine.FgEngine, sectionIndex: Int,
        beatIndex: Int = 0, sectionType: SectionDetector.SectionType = SectionDetector.SectionType.VERSE,
        barIdx: Int = 0, isBalladMode: Boolean = false
    ): Pair<LSColor, LSColor?> {
        val isPatternA = (sectionIndex % 2 == 0)
        // 예전엔 "CHORUS면 chorusColors, 아니면 colorGroup"을 effectiveColors 하나로
        // 묶어서 ON_PULSE/BREATH/ON_TRANSIT_ROTATE가 전부 같이 참조했는데, 실제로는
        // ON_PULSE·BREATH(VERSE 분기)는 CHORUS에서 절대 안 쓰인다(assignFgEngine 참고 —
        // VERSE→ON_PULSE/BREATH, CHORUS→ON_TRANSIT_ROTATE로 섹션별 엔진이 고정이라 겹칠
        // 일이 없다). 그래서 effectiveColors는 사실상 매번 똑같은 값을 가리키면서도
        // "어디서 쓰는 색인지"가 변수명만 봐선 안 보였다. 엔진별로 쓰는 색 순환을 각자
        // 이름 붙여서(onPulseFgCycle/verseBreathFgCycle/chorusOnRotateFgCycle 등)
        // 분기 안에서 바로 선언하도록 바꿨다.
        return when (engine) {
            EffectMatchingEngine.FgEngine.ON_PULSE -> {
                // ON_PULSE는 VERSE(비발라드)에서만 쓰이므로 항상 colorGroup을 순환한다.
                // FG를 bar 단위로 순환시킨다 — 기존엔 섹션 전체가 sectionIndex 기반 고정
                // 한 색이라 같은 VERSE 안에서는 ON_PULSE FG가 안 바뀌었다. BG는 기존처럼
                // 섹션 단위 패턴(A/B)을 그대로 유지.
                val onPulseFgCycle = palette.colorGroup
                val bg = if (isPatternA) palette.onPulseBg[0] else palette.onPulseBg[1]
                val fg = onPulseFgCycle[Math.floorMod(barIdx, onPulseFgCycle.size)]
                fg to bg
            }
            EffectMatchingEngine.FgEngine.BLINK -> {
                // VERSE의 BLINK는 "그 bar가 섹션 내에서 에너지가 높은 구간"일 때만 쓰인다
                // (buildFramesFromSections의 verseBarEnergy 참고). BG는 verseBlinkBg(고정,
                // 팔레트에서 빨강에 가장 가까운 색)로 ON_PULSE(낮은 에너지)와 또렷이
                // 구분하고, FG는 ON_PULSE처럼 bar마다 verseBlinkFgCycle(BG와 겹치지 않는
                // 4색)을 순환시켜 bar가 바뀔 때마다 다른 색으로 연출한다.
                if (sectionType == SectionDetector.SectionType.VERSE) {
                    val fg = palette.verseBlinkFgCycle[Math.floorMod(barIdx, palette.verseBlinkFgCycle.size)]
                    fg to palette.verseBlinkBg
                } else {
                    val set = palette.blinkSets[beatIndex % palette.blinkSets.size]
                    set.fg to set.bg
                }
            }
            EffectMatchingEngine.FgEngine.ON_TRANSIT_ROTATE ->
                if (sectionType == SectionDetector.SectionType.BRIDGE) {
                    val set = palette.bridgeSets[beatIndex % palette.bridgeSets.size]
                    set.fg to set.bg
                } else if (sectionType == SectionDetector.SectionType.CHORUS) {
                    // CHORUS는 간격이 큰 색만 골라둔 chorusColors를 순환시켜 전환마다
                    // 또렷이 달라지도록 한다("탁탁 튀는" 전환).
                    val chorusOnRotateFgCycle = palette.chorusColors
                    val fg = chorusOnRotateFgCycle[beatIndex % chorusOnRotateFgCycle.size]
                    fg to palette.chorusBg
                } else {
                    // CLIMAX 등 BRIDGE/CHORUS가 아닌 ON_TRANSIT_ROTATE 구간은 빈도
                    // 균등화된 colorGroup을 순환한다.
                    val onRotateFgCycle = palette.colorGroup
                    val fg = onRotateFgCycle[beatIndex % onRotateFgCycle.size]
                    fg to palette.black
                }
            EffectMatchingEngine.FgEngine.STROBE -> {
                val set = palette.strokeSets[beatIndex % palette.strokeSets.size]
                set.fg to set.bg
            }
            // BRIDGE의 BREATH는 (비발라드 포함) VERSE의 BREATH와 같은 breathSet 색을 그대로
            // 썼었다(sectionType 구분이 없었음) — 발라드에서만 BG를 검정으로 바꿔 더 조용한
            // 느낌을 주려 했는데, FG까지 검정으로 하면 breath가 검정↔검정이 돼서 사실상
            // 완전히 꺼진 것처럼 보이는 문제가 있었다(실측: "BRIDGE가 OFF 상태로만 나옴").
            // FG는 breathSet.fg(색상 있는 값)를 유지하고 BG만 검정으로 바꿔서, FREEZE와
            // 같은 패턴(색상 있는 FG + 검정 BG)으로 맞췄다.
            // 발라드 VERSE의 BREATH는 breathSet 고정 1색 대신 컬러셋을 bar마다 순환시킨다
            // — ON_PULSE가 FG를 bar 단위로 순환시키는 것과 같은 방식. RANDOM DELAY=0
            // (buildFramesFromSections)이라 모든 기기가 같은 타이밍으로 숨쉬므로, 색만
            // bar마다 바뀌어도 기기 간 어긋남 없이 또렷하게 보인다.
            EffectMatchingEngine.FgEngine.BREATH -> when {
                sectionType == SectionDetector.SectionType.BRIDGE && isBalladMode -> palette.breathSet.fg to palette.black
                sectionType == SectionDetector.SectionType.VERSE -> {
                    // BREATH가 VERSE에서 쓰이는 건 발라드뿐이라 항상 colorGroup을 순환한다.
                    val verseBreathFgCycle = palette.colorGroup
                    verseBreathFgCycle[Math.floorMod(barIdx, verseBreathFgCycle.size)] to palette.breathSet.bg
                }
                else -> palette.breathSet.fg to palette.breathSet.bg
            }
            // FREEZE(BREAK): 깜빡임/회전 없이 한 가지 색을 고정해서 보여준다. BREATH와 같은
            // 색을 쓰되 배경은 완전히 꺼서(black) 정적인 느낌을 준다.
            EffectMatchingEngine.FgEngine.FREEZE -> palette.breathSet.fg to palette.black
            // OFF_TRANSIT 섹션은 buildFramesFromSections에서 이미 continue로 걸러져 여기 도달하지 않음
            EffectMatchingEngine.FgEngine.OFF_TRANSIT -> palette.black to palette.black
        }
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
            EffectMatchingEngine.FgEngine.STROBE ->
                LSEffectPayload.Effects.strobe(period = period ?: msToStrobePeriod(beatMs),
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
    private fun msToStrobePeriod(beatMs: Long)       = (beatMs / 10L).toInt().coerceIn(1, 255)
    private fun msToBreathPeriod(beatMs: Long)       = (beatMs / 20L).toInt().coerceIn(1, 255)
    private fun msToBreathRandomDelay(beatMs: Long)  = (msToBreathPeriod(beatMs) / 10).coerceIn(1, 10)

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    /** 색상 목록 중 색상환에서 빨강(hue 0)에 가장 가까운 것을 고른다. */
    private fun closestToRed(colors: List<LSColor>): LSColor {
        if (colors.isEmpty()) return LSColor(255, 0, 0)
        return colors.minBy { c ->
            val h = hueOf(c)
            min(h, 360f - h)
        }
    }
}
