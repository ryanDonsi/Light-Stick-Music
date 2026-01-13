package com.lightstick.music.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
// Raw Color Definitions (기본 팔레트)
// ═══════════════════════════════════════════════════════════

// Primary
val Primary = Color(0xFFA774FF)
val OnPrimary = Color(0xFFFCF4FF)
val PrimaryContainer = Color(0xFF843DFF)
val OnPrimaryContainer = Color(0xFFFCF9FF)

// Secondary
val Secondary = Color(0xFFFFD46F)
val OnSecondary = Color(0xFF21201D)

// Neutral
val Surface = Color(0xFF111111)
val OnSurface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFF6F7074)
val OnSurfaceVariant = Color(0xFFE1E3E6)
val Outline = Color(0xFF424242)
val Divider = Color(0xFF323232)
val Background = Color(0xFF1F1F1F)
val Error = Color(0xFFFF404E)
val TextTertiary = Color(0xFF9CA3AF)  // 재생시간, 부가정보 텍스트용

// Effect
val Dimmed = Color(0x80000000)
val ToastBg = Color(0xCC81B1B1)
val Disable = Color(0xFF4A4A4A)
val OnDisable = Color(0x4DACACAC)
val Ripple = Color(0x26DDE8FF)

// Shadow
val ShadowDialog = Color(0x33FFFFFF)
val ShadowCard = Color(0x33000000)

// Primary Level
val PrimaryLevel1 = Color(0xBAA774FF)
val PrimaryLevel2 = Color(0xB8A774FF)
val PrimaryLevel3 = Color(0x7AA774FF)
val PrimaryLevel4 = Color(0xBFA774FF)

// OnSurface Level
val OnSurfaceLevel1 = Color(0x0F111111)
val OnSurfaceLevel2 = Color(0x24111111)
val OnSurfaceLevel3 = Color(0x61111111)
val OnSurfaceLevel4 = Color(0x85111111)

// Gradient (진행바용 - Figma 명세)
val GradientStart = Color(0xFF9D79BC)  // ✅ 수정: #9D79BC
val GradientEnd = Color(0xFF8A40C4)    // ✅ 수정: #8A40C4


// ═══════════════════════════════════════════════════════════
// CustomColors (MaterialTheme 확장용)
// ═══════════════════════════════════════════════════════════

@Immutable
data class CustomColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,

    val secondary: Color,
    val onSecondary: Color,

    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val divider: Color,
    val background: Color,
    val error: Color,
    val textTertiary: Color,  // ✅ 추가

    val dimmed: Color,
    val toastBg: Color,
    val disable: Color,
    val onDisable: Color,
    val ripple: Color,

    val shadowDialog: Color,
    val shadowCard: Color,

    val primaryLevel1: Color,
    val primaryLevel2: Color,
    val primaryLevel3: Color,
    val primaryLevel4: Color,

    val onSurfaceLevel1: Color,
    val onSurfaceLevel2: Color,
    val onSurfaceLevel3: Color,
    val onSurfaceLevel4: Color,

    val gradientStart: Color,
    val gradientEnd: Color
)

// ═══════════════════════════════════════════════════════════
// Default CustomColors Instance
// ═══════════════════════════════════════════════════════════

val DefaultCustomColors = CustomColors(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,

    secondary = Secondary,
    onSecondary = OnSecondary,

    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    divider = Divider,
    background = Background,
    error = Error,
    textTertiary = TextTertiary,  // ✅ 추가

    dimmed = Dimmed,
    toastBg = ToastBg,
    disable = Disable,
    onDisable = OnDisable,
    ripple = Ripple,

    shadowDialog = ShadowDialog,
    shadowCard = ShadowCard,

    primaryLevel1 = PrimaryLevel1,
    primaryLevel2 = PrimaryLevel2,
    primaryLevel3 = PrimaryLevel3,
    primaryLevel4 = PrimaryLevel4,

    onSurfaceLevel1 = OnSurfaceLevel1,
    onSurfaceLevel2 = OnSurfaceLevel2,
    onSurfaceLevel3 = OnSurfaceLevel3,
    onSurfaceLevel4 = OnSurfaceLevel4,

    gradientStart = GradientStart,
    gradientEnd = GradientEnd
)

// ═══════════════════════════════════════════════════════════
// CompositionLocal + MaterialTheme Extension
// ═══════════════════════════════════════════════════════════

internal val LocalCustomColors =
    staticCompositionLocalOf { DefaultCustomColors }

/**
 * MaterialTheme에서 커스텀 컬러에 접근
 *
 * 사용 예:
 * ```
 * Text(
 *     text = "Title",
 *     color = MaterialTheme.customColors.onSurface
 * )
 * ```
 */
val MaterialTheme.customColors: CustomColors
    @Composable
    get() = LocalCustomColors.current

// ═══════════════════════════════════════════════════════════
// 확장 속성 (자주 사용되는 투명도 변형)
// ═══════════════════════════════════════════════════════════

/**
 * Glass morphism 카드 배경 (OnSurface 5%)
 */
val CustomColors.surfaceGlass: Color
    get() = onSurface.copy(alpha = 0.05f)

/**
 * Glass morphism 카드 테두리 (OnSurface 16%)
 */
val CustomColors.surfaceGlassBorder: Color
    get() = onSurface.copy(alpha = 0.16f)

/**
 * 재생 중 강조 배경 (Primary 22%)
 */
val CustomColors.primaryGlass: Color
    get() = primary.copy(alpha = 0.22f)