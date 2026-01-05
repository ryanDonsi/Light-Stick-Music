package com.dongsitech.lightstickmusicdemo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ═══════════════════════════════════════════════════════════
// Material3 ColorScheme (기본 시스템 컬러용)
// → 커스텀 컬러는 customColors 로 접근
// ═══════════════════════════════════════════════════════════

private val AppColorScheme = darkColorScheme(
    // Primary
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,

    // Secondary
    secondary = Secondary,
    onSecondary = OnSecondary,

    // Surface / Background
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    background = Background,

    // Error
    error = Error,
    onError = OnPrimary,
)

// ═══════════════════════════════════════════════════════════
// App Theme
// ═══════════════════════════════════════════════════════════

@Composable
fun LightStickMusicPlayerDemoTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current

    // 상태바 / 네비게이션바 다크 고정
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // CustomColors + CustomTextStyles 주입
    CompositionLocalProvider(
        LocalCustomColors provides DefaultCustomColors,
        LocalCustomTextStyles provides DefaultCustomTextStyles
    ) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            typography = Typography,
            content = content
        )
    }
}