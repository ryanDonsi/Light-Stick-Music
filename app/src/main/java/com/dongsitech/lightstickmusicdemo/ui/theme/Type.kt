package com.dongsitech.lightstickmusicdemo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.R

// ═══════════════════════════════════════════════════════════
// Font Family - Pretendard
// ═══════════════════════════════════════════════════════════

// TODO: Pretendard 폰트 파일을 res/font/ 에 추가 후 아래 주석 해제
 val PretendardFont = FontFamily(
     Font(R.font.pretendard_regular, FontWeight.Normal),
     Font(R.font.pretendard_semibold, FontWeight.SemiBold)
 )

// Pretendard 폰트가 없는 경우 기본 폰트 사용
//val PretendardFont = FontFamily.Default

// ═══════════════════════════════════════════════════════════
// Material3 Typography (Figma 명세 기반)
// ═══════════════════════════════════════════════════════════

val Typography = Typography(
    // ─────────────────────────────────────────────────────
    // Title Styles
    // ─────────────────────────────────────────────────────

    // Title/large: SemiBold (600), 20sp, line height 140%
    titleLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 20.sp,
        lineHeight = 28.sp,  // 20 × 1.4 = 28
        letterSpacing = 0.sp
    ),

    // Title/medium: SemiBold (600), 18sp, line height 140%
    titleMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 18.sp,
        lineHeight = 25.sp,  // 18 × 1.4 = 25.2 ≈ 25
        letterSpacing = 0.sp
    ),

    // Title/small: SemiBold (600), 16sp, line height 140%
    titleSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 16.sp,
        lineHeight = 22.sp,  // 16 × 1.4 = 22.4 ≈ 22
        letterSpacing = 0.sp
    ),

    // ─────────────────────────────────────────────────────
    // Body Styles
    // ─────────────────────────────────────────────────────

    // Body/medium: Regular (400), 16sp, line height 140%
    bodyLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,  // 400
        fontSize = 16.sp,
        lineHeight = 22.sp,  // 16 × 1.4 = 22.4 ≈ 22
        letterSpacing = 0.sp
    ),

    // Body/small: Regular (400), 14sp, line height 140%
    bodyMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,  // 400
        fontSize = 14.sp,
        lineHeight = 20.sp,  // 14 × 1.4 = 19.6 ≈ 20
        letterSpacing = 0.sp
    ),

    // Body/extra_small: Regular (400), 12sp, line height 160%
    bodySmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,  // 400
        fontSize = 12.sp,
        lineHeight = 19.sp,  // 12 × 1.6 = 19.2 ≈ 19
        letterSpacing = 0.sp
    ),

    // ─────────────────────────────────────────────────────
    // Label Styles (Button)
    // ─────────────────────────────────────────────────────

    // Button/medium: SemiBold (600), 16sp, line height 140%
    labelLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 16.sp,
        lineHeight = 22.sp,  // 16 × 1.4 = 22.4 ≈ 22
        letterSpacing = 0.sp
    ),

    // Button/small: SemiBold (600), 14sp, line height 140%
    labelMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 14.sp,
        lineHeight = 20.sp,  // 14 × 1.4 = 19.6 ≈ 20
        letterSpacing = 0.sp
    ),

    // Badge/small: SemiBold (600), 10sp, line height 125%
    labelSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,  // 600
        fontSize = 10.sp,
        lineHeight = 13.sp,  // 10 × 1.25 = 12.5 ≈ 13
        letterSpacing = 0.sp
    ),

    // ─────────────────────────────────────────────────────
    // Headline & Display (사용하지 않음, 기본값 유지)
    // ─────────────────────────────────────────────────────

    headlineLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),

    headlineMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),

    headlineSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    displayLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),

    displayMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),

    displaySmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    )
)

// ═══════════════════════════════════════════════════════════
// Custom Text Styles (Material3에 없는 커스텀 스타일)
// ═══════════════════════════════════════════════════════════

@Immutable
data class CustomTextStyles(
    // Top bar/large: Semibold (600), 17sp, line height 140%
    val topBarLarge: TextStyle,

    // Top bar/small: Semibold (600), 14sp, line height 140%
    val topBarSmall: TextStyle,

    // Text fields/medium: SemiBold (600), 14sp, line height 140%
    val textFieldMedium: TextStyle,

    // Toast/medium: SemiBold (600), 14sp, line height 125%
    val toastMedium: TextStyle,

    // Badge/medium: SemiBold (600), 12sp, line height 140%
    val badgeMedium: TextStyle,

    // Special/body_accent: SemiBold (600), 14sp, line height 140%
    val bodyAccent: TextStyle
)

val LocalCustomTextStyles = staticCompositionLocalOf {
    CustomTextStyles(
        topBarLarge = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 17.sp,
            lineHeight = 24.sp,  // 17 × 1.4 = 23.8 ≈ 24
            letterSpacing = 0.sp
        ),
        topBarSmall = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 14.sp,
            lineHeight = 20.sp,  // 14 × 1.4 = 19.6 ≈ 20
            letterSpacing = 0.sp
        ),
        textFieldMedium = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 14.sp,
            lineHeight = 20.sp,  // 14 × 1.4 = 19.6 ≈ 20
            letterSpacing = 0.sp
        ),
        toastMedium = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 14.sp,
            lineHeight = 18.sp,  // 14 × 1.25 = 17.5 ≈ 18
            letterSpacing = 0.sp
        ),
        badgeMedium = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 12.sp,
            lineHeight = 17.sp,  // 12 × 1.4 = 16.8 ≈ 17
            letterSpacing = 0.sp
        ),
        bodyAccent = TextStyle(
            fontFamily = PretendardFont,
            fontWeight = FontWeight.SemiBold,  // 600
            fontSize = 14.sp,
            lineHeight = 20.sp,  // 14 × 1.4 = 19.6 ≈ 20
            letterSpacing = 0.sp
        )
    )
}

// ═══════════════════════════════════════════════════════════
// Extension Property - 쉽게 접근하기
// ═══════════════════════════════════════════════════════════

/**
 * MaterialTheme에서 커스텀 텍스트 스타일에 접근
 *
 * 사용 예:
 * ```
 * Text(
 *     text = "Top Bar Title",
 *     style = MaterialTheme.customTextStyles.topBarLarge
 * )
 * ```
 */
val androidx.compose.material3.MaterialTheme.customTextStyles: CustomTextStyles
    @Composable
    get() = LocalCustomTextStyles.current