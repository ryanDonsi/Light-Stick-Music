package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.unit.dp
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

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

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
                    // 색상 선택 버튼
                    if (selectedEffect != null && selectedEffect !is EffectViewModel.UiEffectType.Off) {
                        IconButton(onClick = { showColorPicker = true }) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "색상 선택"
                            )
                        }
                    }

                    // 설정 버튼
                    if (selectedEffect != null && selectedEffect !is EffectViewModel.UiEffectType.Off) {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "설정"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                                text = "Broadcasting",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (currentSettings.broadcasting)
                                    "Master Mode (주변 재전파)"
                                else
                                    "Single Mode (단일 동작)",
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

            // 현재 색상 표시
            if (selectedEffect != null && selectedEffect !is EffectViewModel.UiEffectType.Off) {
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
                            Text(
                                text = "현재 색상",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(currentSettings.color.toComposeColor())
                            )
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
            items(effects) { effect ->
                EffectCard(
                    effect = effect,
                    isSelected = selectedEffect == effect,
                    isPlaying = isPlaying && selectedEffect == effect,
                    onClick = { viewModel.selectEffect(context, effect) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 색상 선택 다이얼로그
    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = currentSettings.color,
            onColorSelected = { color ->
                viewModel.updateColor(context, color)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
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

@Composable
fun EffectCard(
    effect: EffectViewModel.UiEffectType,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
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
                Text(
                    text = effect.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = effect.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(ComposeColor.Red)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 색상 선택 다이얼로그 (SDK 프리셋 색상 사용)
 */
@Composable
fun ColorPickerDialog(
    currentColor: SdkColor,
    onColorSelected: (SdkColor) -> Unit,
    onDismiss: () -> Unit
) {
    val presetColors = remember {
        listOf(
            "Red" to Colors.RED,
            "Green" to Colors.GREEN,
            "Blue" to Colors.BLUE,
            "Yellow" to Colors.YELLOW,
            "Cyan" to Colors.CYAN,
            "Magenta" to Colors.MAGENTA,
            "Orange" to Colors.ORANGE,
            "Purple" to Colors.PURPLE,
            "Pink" to Colors.PINK,
            "White" to Colors.WHITE
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("색상 선택") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetColors.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (name, color) ->
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color.toComposeColor())
                                    .clickable { onColorSelected(color) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentColor == color) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = ComposeColor.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
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
    var period by remember { mutableStateOf(settings.period) }
    var transit by remember { mutableStateOf(settings.transit) }
    var randomColor by remember { mutableStateOf(settings.randomColor) }
    var randomDelay by remember { mutableStateOf(settings.randomDelay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이펙트 설정") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "반복 주기: ${period}ms",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = period.toFloat(),
                        onValueChange = { period = it.toInt() },
                        valueRange = 50f..5000f,
                        steps = 49
                    )
                }

                Column {
                    Text(
                        text = "전환 속도: $transit",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = transit.toFloat(),
                        onValueChange = { transit = it.toInt() },
                        valueRange = 1f..50f,
                        steps = 48
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("랜덤 색상")
                    Switch(
                        checked = randomColor,
                        onCheckedChange = { randomColor = it }
                    )
                }

                if (randomColor) {
                    Column {
                        Text(
                            text = "랜덤 지연: ${randomDelay}ms",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = randomDelay.toFloat(),
                            onValueChange = { randomDelay = it.toInt() },
                            valueRange = 0f..1000f,
                            steps = 19
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(settings.copy(
                    period = period,
                    transit = transit,
                    randomColor = randomColor,
                    randomDelay = randomDelay
                ))
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
 * SDK Color를 Compose Color로 변환
 */
fun SdkColor.toComposeColor(): ComposeColor {
    return ComposeColor(
        red = this.r / 255f,
        green = this.g / 255f,
        blue = this.b / 255f
    )
}