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

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showForegroundColorPicker by remember { mutableStateOf(false) }
    var showBackgroundColorPicker by remember { mutableStateOf(false) }

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
                actions = {
                    // Foreground Color 선택 버튼 (EFFECT LIST 제외)
                    if (selectedEffect != null &&
                        selectedEffect !is EffectViewModel.UiEffectType.Off &&
                        selectedEffect !is EffectViewModel.UiEffectType.EffectList) {
                        IconButton(onClick = { showForegroundColorPicker = true }) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "전경색 선택"
                                )
                                Text(
                                    text = "FG",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    // BG Color 버튼 (Strobe, Blink, Breath에서만 표시)
                    if (selectedEffect is EffectViewModel.UiEffectType.Strobe ||
                        selectedEffect is EffectViewModel.UiEffectType.Blink ||
                        selectedEffect is EffectViewModel.UiEffectType.Breath) {
                        IconButton(onClick = { showBackgroundColorPicker = true }) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "배경색 선택"
                                )
                                Text(
                                    text = "BG",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    
                    // 설정 버튼 (EFFECT LIST 제외)
                    if (selectedEffect != null &&
                        selectedEffect !is EffectViewModel.UiEffectType.EffectList) {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "설정"
                            )
                        }
                    }
                },
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

            // Broadcasting 옵션
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Broadcasting Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (currentSettings.broadcasting) "Master Mode (주변 응원봉에 신호 재전파)"
                                else "Single Mode (연결된 응원봉만 동작)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = currentSettings.broadcasting,
                            onCheckedChange = { viewModel.toggleBroadcasting(context) }
                        )
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
            items(effects) { effect ->
                // currentSettings를 key로 사용하여 설정 변경 시 리컴포지션 트리거
                key(effect, currentSettings) {
                    EffectCard(
                        effect = effect,
                        isSelected = selectedEffect == effect,
                        isPlaying = isPlaying && selectedEffect == effect,
                        settings = if (selectedEffect == effect) {
                            currentSettings  // 선택된 이펙트는 currentSettings 사용 (실시간)
                        } else {
                            viewModel.getEffectSettings(effect)  // 다른 이펙트는 저장된 설정
                        },
                        viewModel = viewModel,
                        onClick = { viewModel.selectEffect(context, effect) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Foreground Color Picker
    if (showForegroundColorPicker) {
        ColorPickerDialog(
            title = "전경색 선택",
            currentColor = currentSettings.color,
            onColorSelected = { color ->
                viewModel.updateColor(context, color)
                showForegroundColorPicker = false
            },
            onDismiss = { showForegroundColorPicker = false }
        )
    }

    // Background Color Picker
    if (showBackgroundColorPicker) {
        ColorPickerDialog(
            title = "배경색 선택",
            currentColor = currentSettings.backgroundColor,
            onColorSelected = { color ->
                viewModel.updateBackgroundColor(context, color)
                showBackgroundColorPicker = false
            },
            onDismiss = { showBackgroundColorPicker = false }
        )
    }

    // 설정 다이얼로그
    if (showSettingsDialog && selectedEffect != null) {
        EffectSettingsDialog(
            settings = currentSettings,
            onDismiss = { showSettingsDialog = false },
            onApply = { newSettings ->
                viewModel.updateSettings(context, newSettings)
                showSettingsDialog = false
            }
        )
    }
}

/**
 * 이펙트 카드
 */
@Composable
fun EffectCard(
    effect: EffectViewModel.UiEffectType,
    isSelected: Boolean,
    isPlaying: Boolean,
    settings: EffectViewModel.EffectSettings,
    viewModel: EffectViewModel,
    onClick: () -> Unit
) {
    // 해당 이펙트의 현재 설정값 (실시간 업데이트)
    val effectSettings = if (isSelected) {
        // 선택된 이펙트는 currentSettings 사용 (실시간 반영)
        settings
    } else {
        // 선택되지 않은 이펙트는 저장된 설정 사용
        remember(effect) { viewModel.getEffectSettings(effect) }
    }

    // 설정값 표시 문자열 생성
    val settingsText = buildString {
        // EFFECT LIST는 내부 프레임 사용이므로 설정값 표시 안 함
        if (effect is EffectViewModel.UiEffectType.EffectList) {
            return@buildString
        }

        // 색상 정보
        val colorHex = String.format(
            "#%02X%02X%02X",
            effectSettings.color.r,
            effectSettings.color.g,
            effectSettings.color.b
        )

        when (effect) {
            is EffectViewModel.UiEffectType.Strobe,
            is EffectViewModel.UiEffectType.Blink,
            is EffectViewModel.UiEffectType.Breath -> {
                append("🎨 $colorHex")
                append(" | Period: ${effectSettings.period}")
            }
            is EffectViewModel.UiEffectType.On -> {
                append("🎨 $colorHex")
                append(" | Transit: ${effectSettings.transit}")
            }
            is EffectViewModel.UiEffectType.Off -> {
                append("Transit: ${effectSettings.transit}")
            }
            else -> {}
        }

        // Random Color
        if (effectSettings.randomColor) {
            append(" | 🎲 Random")
        }

        // Random Delay
        if (effectSettings.randomDelay > 0) {
            append(" | ⏱ Delay: ${effectSettings.randomDelay}")
        }
    }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

                    // 색상 프리뷰 (EFFECT LIST 제외)
                    if (effect !is EffectViewModel.UiEffectType.EffectList &&
                        effect !is EffectViewModel.UiEffectType.Off) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(effectSettings.color.toComposeColor())
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        if(effect is EffectViewModel.UiEffectType.Strobe ||
                            effect is EffectViewModel.UiEffectType.Blink ||
                            effect is EffectViewModel.UiEffectType.Breath) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(effectSettings.backgroundColor.toComposeColor())
                                    .border(
                                        0.5.dp,
                                        MaterialTheme.colorScheme.outline,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
                Text(
                    text = effect.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 설정값 표시
                if (settingsText.isNotEmpty()) {
                    Text(
                        text = settingsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (isPlaying) "재생 중" else "선택됨",
                    tint = if (isPlaying)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/**
 * Color Picker Dialog (깔끔하게 개선)
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

    // 현재 선택된 색상 (실시간)
    val selectedColor = SdkColor(red, green, blue)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 색상 미리보기 (크기 축소)
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

                // RGB 슬라이더 (간결하게, 모두 회색)
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

                // 프리셋 색상 그리드 (2줄, 크기 축소)
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
 * 간결한 컬러 슬라이더 (회색 통일, 컴팩트한 크기)
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
                .height(20.dp), // 슬라이더 높이 축소
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumb = {
                // Thumb 크기 축소
                Box(
                    modifier = Modifier
                        .size(12.dp) // 기본 20dp에서 12dp로 축소
                        .background(
                            MaterialTheme.colorScheme.onSurface,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            },
            track = { sliderState ->
                // Track 두께 축소
                val fraction = sliderState.value / 255f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp) // 기본 4dp 유지하되 명시적으로 설정
                ) {
                    // Inactive track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp) // Track 두께 2dp로 축소
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(1.dp)
                            )
                    )
                    // Active track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp) // Track 두께 2dp로 축소
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
 * 이펙트 설정 다이얼로그 (간결한 UI)
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
    var broadcasting by remember { mutableStateOf(settings.broadcasting) }

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
                        // Period
                        CompactSliderRow(
                            label = "Period",
                            value = period,
                            onValueChange = { period = it },
                            valueRange = 0f..255f
                        )
                    }

                    is EffectViewModel.UiEffectType.On,
                    is EffectViewModel.UiEffectType.Off -> {
                        // Transit
                        CompactSliderRow(
                            label = "Transit",
                            value = transit,
                            onValueChange = { transit = it },
                            valueRange = 0f..255f
                        )
                    }

                    else -> {}
                }
                // Random Delay
                CompactSliderRow(
                    label = "Random Delay",
                    value = randomDelay,
                    onValueChange = { randomDelay = it },
                    valueRange = 0f..255f
                )
                if(settings.uiType !is EffectViewModel.UiEffectType.Off) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Random Color
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
                        randomDelay = randomDelay,
                        broadcasting = broadcasting
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
 * 간결한 슬라이더 행 (설정용, 컴팩트한 크기)
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
                .height(20.dp), // 슬라이더 높이 축소
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            thumb = {
                // Thumb 크기 축소
                Box(
                    modifier = Modifier
                        .size(14.dp) // 작은 thumb
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
                // Track 두께 축소
                val fraction = (sliderState.value - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    // Inactive track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp) // Track 두께 3dp
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(1.5.dp)
                            )
                    )
                    // Active track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp) // Track 두께 3dp
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