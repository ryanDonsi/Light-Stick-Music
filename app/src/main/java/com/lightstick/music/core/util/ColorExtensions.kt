package com.lightstick.music.core.util

// Compose와 SDK의 Color 클래스 이름이 같으므로, Type Alias를 사용하여 명확히 구분합니다.
import androidx.compose.ui.graphics.Color as ComposeColor
import com.lightstick.types.Color as SdkColor

/**
 * SDK의 [SdkColor]를 Compose UI의 [ComposeColor]로 변환합니다.
 */
fun SdkColor.toComposeColor(): ComposeColor {
    return ComposeColor(
        red = (this.r and 0xFF) / 255f,
        green = (this.g and 0xFF) / 255f,
        blue = (this.b and 0xFF) / 255f
    )
}

/**
 * Compose UI의 [ComposeColor]를 SDK의 [SdkColor]로 변환합니다.
 */
fun ComposeColor.toLightStickColor(): SdkColor {
    return SdkColor(
        r = (this.red * 255).toInt(),
        g = (this.green * 255).toInt(),
        b = (this.blue * 255).toInt()
    )
}
