package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dongsitech.lightstickmusicdemo.ui.components.common.TopBarCentered
import com.dongsitech.lightstickmusicdemo.ui.components.effect.*
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * ✅ EffectScreen - ColorPickerDialog 프리셋 지원 + isEnabled 기반 disable 처리
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectScreen(
    viewModel: EffectViewModel = viewModel(),
    navController: NavController
) {
    val context = LocalContext.current
    val selectedEffect by viewModel.selectedEffect.collectAsState()
    val currentSettings by viewModel.currentSettings.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val effectSettingsMap by viewModel.effectSettingsMap.collectAsState()
    val deviceConnectionState by viewModel.deviceConnectionState.collectAsState()

    var settingsDialogEffect by remember { mutableStateOf<EffectViewModel.UiEffectType?>(null) }
    var colorPickerState by remember { mutableStateOf<Pair<EffectViewModel.UiEffectType, Boolean>?>(null) }
    var showEffectListSheet by remember { mutableStateOf(false) }

    // ===== 프리셋 관련 State 추가 =====
    var showPresetEdit by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    val fgPresetColors by viewModel.fgPresetColors.collectAsState()
    val bgPresetColors by viewModel.bgPresetColors.collectAsState()
    val selectedFgPreset by viewModel.selectedFgPreset.collectAsState()
    val selectedBgPreset by viewModel.selectedBgPreset.collectAsState()

    val listState = rememberLazyListState()
    val isScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0

    // ✅ 기기 연결 상태 확인
    val isDeviceConnected = deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected

    val currentEffectColor = remember(isPlaying, currentSettings) {
        if (isPlaying) {
            Color(
                red = currentSettings.color.r,
                green = currentSettings.color.g,
                blue = currentSettings.color.b
            )
        } else {
            Color.Red
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startAutoScan(context)
    }

    val basicEffects = remember {
        listOf(
            EffectViewModel.UiEffectType.On,
            EffectViewModel.UiEffectType.Off,
            EffectViewModel.UiEffectType.Strobe,
            EffectViewModel.UiEffectType.Blink,
            EffectViewModel.UiEffectType.Breath
        )
    }

    val effectLists = remember {
        listOf(
            EffectViewModel.UiEffectType.EffectList(1, "발라드"),
            EffectViewModel.UiEffectType.EffectList(2, "댄스"),
            EffectViewModel.UiEffectType.EffectList(3, "록"),
            EffectViewModel.UiEffectType.EffectList(4, "힙합"),
            EffectViewModel.UiEffectType.EffectList(5, "재즈"),
            EffectViewModel.UiEffectType.EffectList(6, "클래식")
        )
    }

    Scaffold(
        topBar = {
            TopBarCentered(
                title = "이펙트",
                actionText = "LIST",
                onActionClick = { showEffectListSheet = true }
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                DeviceConnectionCard(
                    connectionState = deviceConnectionState,
                    onConnectClick = {
                        navController.navigate("deviceList")
                    },
                    onRetryClick = {
                        viewModel.retryAutoScan(context)
                    },
                    currentEffectColor = currentEffectColor,
                    isScrolled = isScrolled
                )
            }

            errorMessage?.let { error ->
                item {
                    ErrorBanner(
                        message = error,
                        onDismiss = { viewModel.clearError() }
                    )
                }
            }

            // ✅ Basic Effects - isEnabled 추가
            items(basicEffects) { effect ->
                val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                    ?: EffectViewModel.EffectSettings.defaultFor(effect)

                EffectTypeCard(
                    effect = effect,
                    effectSettings = effectSettings,
                    isSelected = selectedEffect == effect,
                    isEnabled = isDeviceConnected,  // ✅ 기기 연결 상태
                    onEffectClick = {
                        viewModel.selectEffect(effect)
                        viewModel.playEffect(context, effect)
                    },
                    onSettingsClick = { settingsDialogEffect = effect },
                    onForegroundColorClick = { colorPickerState = effect to false },
                    onBackgroundColorClick = { colorPickerState = effect to true }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ✅ EffectList BottomSheet
    if (showEffectListSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEffectListSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "EFFECT LIST",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // ✅ Effect Lists - isEnabled 추가
                effectLists.forEach { effect ->
                    val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                        ?: EffectViewModel.EffectSettings.defaultFor(effect)

                    EffectTypeCard(
                        effect = effect,
                        effectSettings = effectSettings,
                        isSelected = selectedEffect == effect,
                        isEnabled = isDeviceConnected,  // ✅ 기기 연결 상태
                        onEffectClick = {
                            viewModel.selectEffect(effect)
                            viewModel.playEffect(context, effect)
                            showEffectListSheet = false
                        },
                        onSettingsClick = {
                            settingsDialogEffect = effect
                            showEffectListSheet = false
                        },
                        onForegroundColorClick = {
                            colorPickerState = effect to false
                            showEffectListSheet = false
                        },
                        onBackgroundColorClick = {
                            colorPickerState = effect to true
                            showEffectListSheet = false
                        }
                    )
                }
            }
        }
    }

    // ✅ 설정 다이얼로그
    settingsDialogEffect?.let { effect ->
        val settings = effectSettingsMap[viewModel.getEffectKey(effect)]
            ?: EffectViewModel.EffectSettings.defaultFor(effect)

        EffectSettingsDialog(
            effect = effect,
            settings = settings,
            onSettingsChange = { newSettings ->
                if (selectedEffect == effect) {
                    viewModel.updateSettings(context, newSettings)
                } else {
                    viewModel.saveEffectSettings(effect, newSettings)
                }
            },
            onDismiss = { settingsDialogEffect = null }
        )
    }

    // ✅ 색상 선택 다이얼로그 (프리셋 지원)
    colorPickerState?.let { (effect, isBackground) ->
        val settings = effectSettingsMap[viewModel.getEffectKey(effect)]
            ?: EffectViewModel.EffectSettings.defaultFor(effect)

        val currentColor = if (isBackground) settings.backgroundColor else settings.color

        ColorPickerDialog(
            title = if (isBackground) "Background Color 설정" else "Forground Color 설정",
            initialColor = currentColor.toComposeColor(),
            presetColors = if (isBackground) bgPresetColors else fgPresetColors,
            selectedPresetIndex = if (isBackground) selectedBgPreset else selectedFgPreset,
            onColorSelected = { color ->
                val lightStickColor = color.toLightStickColor()

                if (selectedEffect == effect) {
                    if (isBackground) {
                        viewModel.updateBackgroundColor(context, lightStickColor)
                    } else {
                        viewModel.updateColor(context, lightStickColor)
                    }
                } else {
                    val updated = if (isBackground) {
                        settings.copy(backgroundColor = lightStickColor)
                    } else {
                        settings.copy(color = lightStickColor)
                    }
                    viewModel.saveEffectSettings(effect, updated)
                }
            },
            onPresetSelected = { index ->
                if (isBackground) {
                    viewModel.selectBgPreset(index)
                } else {
                    viewModel.selectFgPreset(index)
                }
            },
            onPresetEdit = { index ->
                showPresetEdit = index to isBackground
            },
            onDismiss = { colorPickerState = null }
        )
    }

    // ✅ 프리셋 색상 편집 다이얼로그
    showPresetEdit?.let { (index, isBg) ->
        val colors = if (isBg) bgPresetColors else fgPresetColors

        PresetColorEditDialog(
            presetIndex = index,
            initialColor = colors[index],
            onColorSelected = { color ->
                if (isBg) {
                    viewModel.updateBgPresetColor(index, color)
                } else {
                    viewModel.updateFgPresetColor(index, color)
                }
            },
            onDismiss = { showPresetEdit = null }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 헬퍼 함수
// ═══════════════════════════════════════════════════════════

/**
 * LightStick Color -> Compose Color 변환
 */
private fun com.lightstick.types.Color.toComposeColor(): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(
        red = (this.r and 0xFF) / 255f,
        green = (this.g and 0xFF) / 255f,
        blue = (this.b and 0xFF) / 255f
    )
}

/**
 * Compose Color -> LightStick Color 변환
 */
private fun androidx.compose.ui.graphics.Color.toLightStickColor(): com.lightstick.types.Color {
    return com.lightstick.types.Color(
        r = (this.red * 255).toInt(),
        g = (this.green * 255).toInt(),
        b = (this.blue * 255).toInt()
    )
}