package com.dongsitech.lightstickmusicdemo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ✅ 가이드 기반 다크 테마 (강제 적용)
/*
// ═══════════════════════════════════════════════════════════
// Primary Colors
// ═══════════════════════════════════════════════════════════

val Primary = Color(0xFFA774FF)              // #A774FF
val OnPrimary = Color(0xFFFCF4FF)            // #FCF4FF
val PrimaryContainer = Color(0xFF843DFF)     // #843DFF
val OnPrimaryContainer = Color(0xFFFCF9FF)   // #FCF9FF

// ═══════════════════════════════════════════════════════════
// Secondary Colors
// ═══════════════════════════════════════════════════════════

val Secondary = Color(0xFFFFD46F)            // #FFD46F
val OnSecondary = Color(0xFF21201D)          // #21201D

// ═══════════════════════════════════════════════════════════
// Neutral Colors
// ═══════════════════════════════════════════════════════════

val Surface = Color(0xFF111111)              // #111111
val OnSurface = Color(0xFFFFFFFF)            // #FFFFFF
val SurfaceVariant = Color(0xFF6F7074)       // #6F7074
val OnSurfaceVariant = Color(0xFFE1E3E6)     // #E1E3E6
val Outline = Color(0xFF424242)              // #424242 (border)
val Divider = Color(0xFF323232)              // #323232
val Background = Color(0xFF1F1F1F)           // #1F1F1F
val Error = Color(0xFFFF404E)                // #FF404E

// ═══════════════════════════════════════════════════════════
// Effect Colors (알파값 포함)
// ═══════════════════════════════════════════════════════════

val Dimmed = Color(0x80000000)               // #000000 / 50% (0x80 = 128)
val ToastBg = Color(0xCC81B1B1)              // #81B1B1 / 80% (0xCC = 204)
val Disable = Color(0xFF4A4A4A)              // #4A4A4A
val OnDisable = Color(0x4DACACAC)            // #ACACAC / 30% (0x4D = 77)
val Ripple = Color(0x26DDE8FF)               // #DDE8FF / 15% (0x26 = 38)

// ═══════════════════════════════════════════════════════════
// Shadow Colors (알파값 포함)
// ═══════════════════════════════════════════════════════════

val ShadowDialog = Color(0x33FFFFFF)         // #FFFFFF / 20% (0x33 = 51)
val ShadowCard = Color(0x33000000)           // #000000 / 20% (0x33 = 51)

// ═══════════════════════════════════════════════════════════
// Level Colors - Primary (알파값 포함)
// ═══════════════════════════════════════════════════════════

val PrimaryLevel1 = Color(0xBA3B48FF)        // #3B48FF / 73% (0xBA = 186)
val PrimaryLevel2 = Color(0xB83B48FF)        // #3B48FF / 72% (0xB8 = 184)
val PrimaryLevel3 = Color(0x7A3B48FF)        // #3B48FF / 48% (0x7A = 122)
val PrimaryLevel4 = Color(0xBF9B48FF)        // #9B48FF / 75% (0xBF = 191)

// ═══════════════════════════════════════════════════════════
// Level Colors - OnSurface (알파값 포함)
// ═══════════════════════════════════════════════════════════

val OnSurfaceLevel1 = Color(0x0F111111)      // #111111 / 6% (0x0F = 15)
val OnSurfaceLevel2 = Color(0x24111111)      // #111111 / 14% (0x24 = 36)
val OnSurfaceLevel3 = Color(0x61111111)      // #111111 / 38% (0x61 = 97)
val OnSurfaceLevel4 = Color(0x85111111)      // #111111 / 52% (0x85 = 133)

// ═══════════════════════════════════════════════════════════
// Special Color - Gradient
// ═══════════════════════════════════════════════════════════

val GradientStart = Color(0xFF3617CE)        // #3617CE
val GradientEnd = Color(0xFF758AFF)          // #758AFF
 */
private val AppColorScheme = darkColorScheme(
    // Primary Colors
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,

    // Secondary Colors
    secondary = Secondary,
    onSecondary = OnSecondary,

    // Neutral Colors
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    background = Background,
    error = Error,
    onError = OnPrimary,
)

@Composable
fun LightStickMusicPlayerDemoTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}