package com.lightstick.music.ui.components.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import com.lightstick.music.R

/**
 * 앱 런처 아이콘 (Light-Stick-EfxCreator의 AppIcon과 같은 방식).
 * 적응형 아이콘이라 painterResource 대신 Drawable 을 비트맵으로 그린다.
 */
@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    val bitmap = remember(px) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?.let { DrawableCompat.wrap(it) }
            ?.toBitmap(px, px)
            ?.asImageBitmap()
    }
    Box(modifier = modifier.size(size).clip(MaterialTheme.shapes.small)) {
        bitmap?.let { Image(it, contentDescription = "GLOWSYNC", modifier = Modifier.size(size)) }
    }
}
