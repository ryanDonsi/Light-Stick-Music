package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.lightstick.types.Color as SdkColor
import com.lightstick.types.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectScreen(
    viewModel: EffectViewModel = viewModel()
) {
    val context = LocalContext.current
    val selectedEffect by viewModel.selectedEffect.collectAsState()
    val currentSettings by viewModel.currentSettings.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // ✅ effectSettingsMap 구독 - 색상 변경 시 UI 업데이트를 위해 필수!
    val effectSettingsMap by viewModel.effectSettingsMap.collectAsState()

    // 각 Effect별 다이얼로그 상태
    var settingsDialogEffect by remember { mutableStateOf<EffectViewModel.UiEffectType?>(null) }
    var colorPickerEffect by remember { mutableStateOf<Pair<EffectViewModel.UiEffectType, Boolean>?>(null) }

    // 사용 가능한 이펙트 목록
    val effects = remember {
        
        listOf(
            EffectViewModel.UiEffectType.On,
            EffectViewModel.UiEffectType.Off,
            EffectViewModel.UiEffectType.Strobe,
            EffectViewModel.UiEffectType.Blink,
            EffectViewModel.UiEffectType.Breath,
            EffectViewModel.UiEffectType.EffectList(1, "발라드"),
            EffectViewModel.UiEffectType.EffectList(2, "댄스"),
            EffectViewModel.UiEffectType.EffectList(3),
            EffectViewModel.UiEffectType.EffectList(4),
            EffectViewModel.UiEffectType.EffectList(5),
            EffectViewModel.UiEffectType.EffectList(6)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("이펙트") },
                windowInsets = WindowInsets(0)
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 에러 메시지 표시
            errorMessage?.let { error ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearError() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "닫기",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // 이펙트 목록 헤더
            item {
                Text(
                    text = "이펙트 선택",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // 이펙트 목록
            items(
                items = effects,
                key = { effect -> viewModel.getEffectKey(effect) }
            ) { effect ->
                val effectKey = viewModel.getEffectKey(effect)
                val effectSettings = if (selectedEffect == effect) {
                    currentSettings
                } else {
                    effectSettingsMap[effectKey] ?: viewModel.getEffectSettings(effect)
                }

                EffectCard(
                    effect = effect,
                    isSelected = selectedEffect == effect,
                    isPlaying = isPlaying && selectedEffect == effect,
                    settings = effectSettings,
                    viewModel = viewModel,
                    onClick = { viewModel.selectEffect(context, effect) },
                    onShowColorPicker = { isBackground ->
                        colorPickerEffect = effect to isBackground
                    },
                    onShowSettings = {
                        settingsDialogEffect = effect
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Color Picker Dialog
    colorPickerEffect?.let { (effect, isBackground) ->
        val effectKey = viewModel.getEffectKey(effect)
        val settings = if (selectedEffect == effect) {
            currentSettings
        } else {
            effectSettingsMap[effectKey] ?: viewModel.getEffectSettings(effect)
        }

        ColorPickerDialog(
            title = if (isBackground) "배경색 선택" else "전경색 선택",
            currentColor = if (isBackground) settings.backgroundColor else settings.color,
            onColorSelected = { color ->
                if (selectedEffect == effect) {
                    // 현재 선택된 Effect
                    if (isBackground) {
                        viewModel.updateBackgroundColor(context, color)
                    } else {
                        viewModel.updateColor(context, color)
                    }
                } else {
                    // 다른 Effect - 설정만 업데이트
                    val updatedSettings = settings.copy(
                        color = if (isBackground) settings.color else color,
                        backgroundColor = if (isBackground) color else settings.backgroundColor
                    )
                    viewModel.saveEffectSettings(effect, updatedSettings)
                }
                colorPickerEffect = null
            },
            onDismiss = { colorPickerEffect = null }
        )
    }

    // Settings Dialog
    settingsDialogEffect?.let { effect ->
        val effectKey = viewModel.getEffectKey(effect)
        val settings = if (selectedEffect == effect) {
            currentSettings
        } else {
            effectSettingsMap[effectKey] ?: viewModel.getEffectSettings(effect)
        }

        EffectSettingsDialog(
            settings = settings,
            onDismiss = { settingsDialogEffect = null },
            onApply = { newSettings ->
                if (selectedEffect == effect) {
                    viewModel.updateSettings(context, newSettings)
                } else {
                    viewModel.saveEffectSettings(effect, newSettings)
                }
                settingsDialogEffect = null
            }
        )
    }
}

/**
 * 이펙트 카드 (개별 색상/설정 버튼 포함)
 */
@Composable
fun EffectCard(
    effect: EffectViewModel.UiEffectType,
    isSelected: Boolean,
    isPlaying: Boolean,
    settings: EffectViewModel.EffectSettings,
    viewModel: EffectViewModel,
    onClick: () -> Unit,
    onShowColorPicker: (isBackground: Boolean) -> Unit,
    onShowSettings: () -> Unit
) {
    // ✅ settings를 직접 사용 (실시간 업데이트)
    val effectSettings = settings

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected && isPlaying -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 헤더 (이펙트 이름 + 버튼들)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 이펙트 이름 및 설명
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = effect.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )

//                        // 색상 미리보기 (EFFECT LIST, OFF 제외)
//                        if (effect !is EffectViewModel.UiEffectType.EffectList &&
//                            effect !is EffectViewModel.UiEffectType.Off) {
//                            Box(
//                                modifier = Modifier
//                                    .size(16.dp)
//                                    .clip(androidx.compose.foundation.shape.CircleShape)
//                                    .background(effectSettings.color.toComposeColor())
//                                    .border(
//                                        0.5.dp,
//                                        MaterialTheme.colorScheme.outline,
//                                        androidx.compose.foundation.shape.CircleShape
//                                    )
//                            )
//
//                            // BG 색상 미리보기 (Strobe, Blink, Breath만)
//                            if (effect is EffectViewModel.UiEffectType.Strobe ||
//                                effect is EffectViewModel.UiEffectType.Blink ||
//                                effect is EffectViewModel.UiEffectType.Breath) {
//                                Box(
//                                    modifier = Modifier
//                                        .size(16.dp)
//                                        .clip(androidx.compose.foundation.shape.CircleShape)
//                                        .background(effectSettings.backgroundColor.toComposeColor())
//                                        .border(
//                                            0.5.dp,
//                                            MaterialTheme.colorScheme.outline,
//                                            androidx.compose.foundation.shape.CircleShape
//                                        )
//                                )
//                            }
//                        }

                        // 선택/재생 아이콘
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = if (isPlaying) "재생 중" else "선택됨",
                                tint = if (isPlaying)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = effect.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 액션 버튼들 (컴팩트하게)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Foreground Color 버튼 (OFF, EFFECT LIST 제외)
                    if (effect !is EffectViewModel.UiEffectType.Off &&
                        effect !is EffectViewModel.UiEffectType.EffectList) {
                        IconButton(
                            onClick = { onShowColorPicker(false) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        ComposeColor(
                                            effectSettings.color.r,
                                            effectSettings.color.g,
                                            effectSettings.color.b
                                        ),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "FG",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (effectSettings.color.r + effectSettings.color.g + effectSettings.color.b > 384)
                                        ComposeColor.Black else ComposeColor.White
                                )
                            }
                        }
                    }

                    // Background Color 버튼 (Strobe, Blink, Breath만)
                    if (effect is EffectViewModel.UiEffectType.Strobe ||
                        effect is EffectViewModel.UiEffectType.Blink ||
                        effect is EffectViewModel.UiEffectType.Breath) {
                        IconButton(
                            onClick = { onShowColorPicker(true) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        ComposeColor(
                                            effectSettings.backgroundColor.r,
                                            effectSettings.backgroundColor.g,
                                            effectSettings.backgroundColor.b
                                        ),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "BG",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (effectSettings.backgroundColor.r + effectSettings.backgroundColor.g + effectSettings.backgroundColor.b > 384)
                                        ComposeColor.Black else ComposeColor.White
                                )
                            }
                        }
                    }

                    // 설정 버튼 (EFFECT LIST 제외)
                    if (effect !is EffectViewModel.UiEffectType.EffectList) {
                        IconButton(
                            onClick = { onShowSettings() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "설정",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 설정 정보 표시 (EFFECT LIST 제외)
            if (effect !is EffectViewModel.UiEffectType.EffectList) {
                Spacer(modifier = Modifier.height(8.dp))

                // 설정 요약 텍스트
                val settingsText = buildString {
                    when (effect) {
                        is EffectViewModel.UiEffectType.Strobe,
                        is EffectViewModel.UiEffectType.Blink,
                        is EffectViewModel.UiEffectType.Breath -> {
                            append("Period: ${effectSettings.period}")
                        }
                        is EffectViewModel.UiEffectType.On -> {
                            append("Transit: ${effectSettings.transit}")
                        }
                        is EffectViewModel.UiEffectType.Off -> {
                            append("Transit: ${effectSettings.transit}")
                        }
                        else -> {}
                    }

                    if (effectSettings.randomColor) {
                        if (isNotEmpty()) append(" | ")
                        append("🎲 Random")
                    }

                    if (effectSettings.randomDelay > 0) {
                        if (isNotEmpty()) append(" | ")
                        append("⏱ ${effectSettings.randomDelay}")
                    }
                }

                if (settingsText.isNotEmpty()) {
                    Text(
                        text = settingsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Color Picker Dialog
 */
@Composable
fun ColorPickerDialog(
    title: String = "색상 선택",
    currentColor: SdkColor,
    onColorSelected: (SdkColor) -> Unit,
    onDismiss: () -> Unit
) {
    var red by remember { mutableIntStateOf(currentColor.r) }
    var green by remember { mutableIntStateOf(currentColor.g) }
    var blue by remember { mutableIntStateOf(currentColor.b) }

    val selectedColor = SdkColor(red, green, blue)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 색상 미리보기
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ComposeColor(red, green, blue))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                )

                // RGB 슬라이더
                CompactColorSlider(
                    label = "R",
                    value = red,
                    onValueChange = { red = it }
                )

                CompactColorSlider(
                    label = "G",
                    value = green,
                    onValueChange = { green = it }
                )

                CompactColorSlider(
                    label = "B",
                    value = blue,
                    onValueChange = { blue = it }
                )

                // 프리셋 색상
                Text(
                    text = "프리셋",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )

                val presetColors = listOf(
                    Colors.RED, Colors.GREEN, Colors.BLUE, Colors.YELLOW, Colors.MAGENTA,
                    Colors.CYAN, Colors.ORANGE, Colors.PURPLE, Colors.PINK, Colors.WHITE
                )

                // 프리셋 색상 그리드 (2줄)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presetColors.take(5).forEach { color ->
                        val isSelected = selectedColor.r == color.r &&
                                selectedColor.g == color.g &&
                                selectedColor.b == color.b
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(color.toComposeColor())
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    red = color.r
                                    green = color.g
                                    blue = color.b
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = ComposeColor.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presetColors.drop(5).forEach { color ->
                        val isSelected = selectedColor.r == color.r &&
                                selectedColor.g == color.g &&
                                selectedColor.b == color.b
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(color.toComposeColor())
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    red = color.r
                                    green = color.g
                                    blue = color.b
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = ComposeColor.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onColorSelected(SdkColor(red, green, blue))
            }) {
                Text("선택")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/**
 * 간결한 컬러 슬라이더
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactColorSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(24.dp),
            fontWeight = FontWeight.Medium
        )

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value / 255f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(1.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .align(Alignment.CenterStart)
                            .background(
                                MaterialTheme.colorScheme.onSurface,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        )

        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp)
        )
    }
}

/**
 * 이펙트 설정 다이얼로그
 */
@Composable
fun EffectSettingsDialog(
    settings: EffectViewModel.EffectSettings,
    onDismiss: () -> Unit,
    onApply: (EffectViewModel.EffectSettings) -> Unit
) {
    var period by remember { mutableIntStateOf(settings.period) }
    var transit by remember { mutableIntStateOf(settings.transit) }
    var randomColor by remember { mutableStateOf(settings.randomColor) }
    var randomDelay by remember { mutableIntStateOf(settings.randomDelay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이펙트 설정") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when(settings.uiType) {
                    is EffectViewModel.UiEffectType.Strobe,
                    is EffectViewModel.UiEffectType.Blink,
                    is EffectViewModel.UiEffectType.Breath -> {
                        CompactSliderRow(
                            label = "Period",
                            value = period,
                            onValueChange = { period = it },
                            valueRange = 0f..255f
                        )
                    }

                    is EffectViewModel.UiEffectType.On,
                    is EffectViewModel.UiEffectType.Off -> {
                        CompactSliderRow(
                            label = "Transit",
                            value = transit,
                            onValueChange = { transit = it },
                            valueRange = 0f..255f
                        )
                    }

                    else -> {}
                }

                CompactSliderRow(
                    label = "Random Delay",
                    value = randomDelay,
                    onValueChange = { randomDelay = it },
                    valueRange = 0f..255f
                )

                if(settings.uiType !is EffectViewModel.UiEffectType.Off) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    CompactSwitchRow(
                        label = "Random Color",
                        checked = randomColor,
                        onCheckedChange = { randomColor = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    settings.copy(
                        period = period,
                        transit = transit,
                        randomColor = randomColor,
                        randomDelay = randomDelay
                    )
                )
            }) {
                Text("적용")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/**
 * 간결한 슬라이더 행
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..255f
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(100.dp)
        )

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.surface,
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
            },
            track = { sliderState ->
                val fraction = (sliderState.value - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(1.5.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .align(Alignment.CenterStart)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(1.5.dp)
                            )
                    )
                }
            }
        )

        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

/**
 * 간결한 스위치 행
 */
@Composable
fun CompactSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * SDK Color를 Compose Color로 변환
 */
fun SdkColor.toComposeColor(): ComposeColor {
    return ComposeColor(this.r, this.g, this.b)
}