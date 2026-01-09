package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dongsitech.lightstickmusicdemo.ui.components.common.CustomToast
import com.dongsitech.lightstickmusicdemo.ui.components.common.TopBarCentered
import com.dongsitech.lightstickmusicdemo.ui.components.common.rememberToastState
import com.dongsitech.lightstickmusicdemo.ui.components.effect.*
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import kotlinx.coroutines.launch

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
    var showPresetEdit by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val fgPresetColors by viewModel.fgPresetColors.collectAsState()
    val bgPresetColors by viewModel.bgPresetColors.collectAsState()
    val selectedFgPreset by viewModel.selectedFgPreset.collectAsState()
    val selectedBgPreset by viewModel.selectedBgPreset.collectAsState()
    var overrideSettings by remember { mutableStateOf<EffectViewModel.EffectSettings?>(null) }

    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastState = rememberToastState()
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            toastState.show(it)
            viewModel.clearToastMessage()
        }
    }

    LaunchedEffect(deviceConnectionState) {
        if (deviceConnectionState is EffectViewModel.DeviceConnectionState.ScanFailed) {
            toastState.show("연결 가능한 기기가 없습니다")
        }
    }

    val selectedEffectListNumber by viewModel.selectedEffectListNumber.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val maxCardHeight = 180.dp
    val minCardHeight = 124.dp
    val collapseRange = maxCardHeight - minCardHeight
    var collapsedOffset by remember { mutableFloatStateOf(0f) }
    val maxOffsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { collapseRange.toPx() }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) {
                    val newOffset = (collapsedOffset + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val consumed = newOffset - collapsedOffset
                    collapsedOffset = newOffset
                    return if (consumed != 0f) Offset(0f, -consumed) else Offset.Zero
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    val newOffset = (collapsedOffset + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val consumedY = collapsedOffset - newOffset
                    collapsedOffset = newOffset
                    return if (consumedY != 0f) Offset(0f, consumedY) else Offset.Zero
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (collapsedOffset > 0 && collapsedOffset < maxOffsetPx) {
                    coroutineScope.launch {
                        val targetOffset = if (collapsedOffset > maxOffsetPx / 2) maxOffsetPx else 0f
                        animate(initialValue = collapsedOffset, targetValue = targetOffset, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { value, _ -> collapsedOffset = value }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val isScrolled = remember { derivedStateOf { collapsedOffset >= maxOffsetPx } }.value
    val isDeviceConnected = deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected

    LaunchedEffect(Unit) {
        viewModel.startAutoScan(context)
    }

    val basicEffects = remember { listOf(EffectViewModel.UiEffectType.On, EffectViewModel.UiEffectType.Off, EffectViewModel.UiEffectType.Strobe, EffectViewModel.UiEffectType.Blink, EffectViewModel.UiEffectType.Breath) }
    val effectLists = remember { listOf(EffectViewModel.UiEffectType.EffectList(1, "발라드"), EffectViewModel.UiEffectType.EffectList(2, "댄스"), EffectViewModel.UiEffectType.EffectList(3, "록"), EffectViewModel.UiEffectType.EffectList(4, "힙합"), EffectViewModel.UiEffectType.EffectList(5, "재즈"), EffectViewModel.UiEffectType.EffectList(6, "클래식")) }
    val previewSettings = overrideSettings ?: currentSettings

    // ✅ 수정: Toast 위치를 하단으로 고정하기 위해 Box로 감쌈
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopBarCentered(
                    title = "Effect Control",
                    actionText = "LIST",
                    // ✅ 수정: 기기 연결 상태에 따라 텍스트 색상을 동적으로 변경
                    actionTextColor = if (isDeviceConnected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) // 비활성화 색상
                    },
                    // ✅ 수정: 클릭 이벤트 내부에서 기기 연결 상태를 한 번 더 확인
                    onActionClick = {
                        if (isDeviceConnected) {
                            showEffectListSheet = true
                        }
                    }
                )
            },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .nestedScroll(nestedScrollConnection)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                DeviceConnectionCard(
                    connectionState = deviceConnectionState,
                    onConnectClick = { navController.navigate("deviceList") },
                    onRetryClick = { viewModel.retryAutoScan(context) },
                    isScrolled = isScrolled,
                    selectedEffect = selectedEffect,
                    effectSettings = previewSettings,
                    isPlaying = isPlaying || overrideSettings != null
                )
                Spacer(modifier = Modifier.height(12.dp))
                errorMessage?.let { error ->
                    ErrorBanner(message = error, onDismiss = { viewModel.clearError() })
                    Spacer(modifier = Modifier.height(12.dp))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(basicEffects) { effect ->
                        val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)] ?: EffectViewModel.EffectSettings.defaultFor(effect)
                        EffectTypeCard(
                            effect = effect,
                            effectSettings = effectSettings,
                            isSelected = selectedEffect == effect,
                            isEnabled = isDeviceConnected,
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
        }

        // ✅ 수정: Modifier.align 추가하여 Toast 위치 하단으로 고정
        CustomToast(
            message = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ✅ 수정: BottomSheet가 항상 전체 화면으로 뜨도록 sheetState 추가
    if (showEffectListSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEffectListSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            EffectListSheetContent(
                effectLists = effectLists,
                selectedEffectListNumber = selectedEffectListNumber,
                onEffectClick = { effectNumber ->
                    viewModel.selectEffectList(context, effectNumber)
                    showEffectListSheet = false
                },
                onClearClick = {
                    viewModel.clearEffectListSelection(context)
                    showEffectListSheet = false
                }
            )
        }
    }

    // 설정 다이얼로그 (이하 코드는 기존과 동일)
    settingsDialogEffect?.let { effect ->
        val initialSettings = effectSettingsMap[viewModel.getEffectKey(effect)] ?: EffectViewModel.EffectSettings.defaultFor(effect)
        LaunchedEffect(effect) { overrideSettings = initialSettings }
        EffectSettingsDialog(
            effect = effect,
            settings = overrideSettings ?: initialSettings,
            onSettingsChange = { newSettings -> overrideSettings = newSettings },
            onDismiss = {
                overrideSettings?.let { finalSettings ->
                    viewModel.saveEffectSettings(effect, finalSettings)
                    if (selectedEffect == effect) {
                        viewModel.updateSettings(context, finalSettings)
                    }
                }
                settingsDialogEffect = null
                overrideSettings = null
            }
        )
    }

    colorPickerState?.let { (effect, isBackground) ->
        val settings = effectSettingsMap[viewModel.getEffectKey(effect)] ?: EffectViewModel.EffectSettings.defaultFor(effect)
        val currentColor = if (isBackground) settings.backgroundColor else settings.color
        ColorPickerDialog(
            title = if (isBackground) "Background Color 설정" else "Forground Color 설정",
            initialColor = currentColor.toComposeColor(),
            presetColors = if (isBackground) bgPresetColors else fgPresetColors,
            selectedPresetIndex = if (isBackground) selectedBgPreset else selectedFgPreset,
            onColorSelected = { color ->
                val lightStickColor = color.toLightStickColor()
                if (selectedEffect == effect) {
                    if (isBackground) viewModel.updateBackgroundColor(context, lightStickColor) else viewModel.updateColor(context, lightStickColor)
                } else {
                    val updated = if (isBackground) settings.copy(backgroundColor = lightStickColor) else settings.copy(color = lightStickColor)
                    viewModel.saveEffectSettings(effect, updated)
                }
            },
            onPresetSelected = { index -> if (isBackground) viewModel.selectBgPreset(index) else viewModel.selectFgPreset(index) },
            onPresetEdit = { index -> showPresetEdit = index to isBackground },
            onDismiss = { colorPickerState = null }
        )
    }

    showPresetEdit?.let { (index, isBg) ->
        val colors = if (isBg) bgPresetColors else fgPresetColors
        PresetColorEditDialog(
            presetIndex = index,
            initialColor = colors[index],
            onColorSelected = { color -> if (isBg) viewModel.updateBgPresetColor(index, color) else viewModel.updateFgPresetColor(index, color) },
            onDismiss = { showPresetEdit = null }
        )
    }
}

// 헬퍼 함수 (기존과 동일)
private fun com.lightstick.types.Color.toComposeColor(): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(red = (this.r and 0xFF) / 255f, green = (this.g and 0xFF) / 255f, blue = (this.b and 0xFF) / 255f)
}
private fun androidx.compose.ui.graphics.Color.toLightStickColor(): com.lightstick.types.Color {
    return com.lightstick.types.Color(r = (this.red * 255).toInt(), g = (this.green * 255).toInt(), b = (this.blue * 255).toInt())
}