package com.lightstick.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lightstick.music.R

val PretendardFont = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold)
)

val Typography = Typography(

    titleLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),

    titleMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),

    titleSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),

    bodyLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),

    bodyMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    bodySmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),

    labelLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),

    labelMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    labelSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp
    ),

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

@Immutable
data class CustomTextStyles(

    val topBarLarge: TextStyle,

    val topBarSmall: TextStyle,

    val textFieldMedium: TextStyle,

    val buttonMedium: TextStyle,

    val buttonSmall: TextStyle,

    val toastMedium: TextStyle,

    val badgeMedium: TextStyle,

    val badgeSmall: TextStyle,

    val bodyAccent: TextStyle
)

val DefaultCustomTextStyles = CustomTextStyles(
    topBarLarge = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    topBarSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    textFieldMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    buttonMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    buttonSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    toastMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),

    badgeMedium = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp
    ),
    badgeSmall = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.sp
    ),

    bodyAccent = TextStyle(
        fontFamily = PretendardFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )
)

internal val LocalCustomTextStyles =
    staticCompositionLocalOf { DefaultCustomTextStyles }

/**
 * MaterialTheme에서 커스텀 텍스트 스타일에 접근
 *
 * 사용 예:
 * ```
 * // Figma 이름 그대로 사용
 * Text(
 *     text = "Button",
 *     style = MaterialTheme.customTextStyles.buttonSmall
 * )
 *
 * // 또는 Material3 방식
 * Text(
 *     text = "Button",
 *     style = MaterialTheme.typography.labelMedium  // = buttonSmall
 * )
 * ```
 */
val MaterialTheme.customTextStyles: CustomTextStyles
    @Composable
    get() = LocalCustomTextStyles.current
