package com.lightstick.music.ui.screen.effect

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lightstick.music.R
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.core.util.toLightStickColor
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.TopBarCentered
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.components.effect.*
import com.lightstick.music.ui.theme.Secondary
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.EffectViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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

    // ✅ BLE 전송 모니터링 (음악 재생 시 실시간 이펙트)
    val latestTransmission by viewModel.latestTransmission.collectAsState()

    var settingsDialogEffect by remember { mutableStateOf<EffectViewModel.UiEffectType?>(null) }
    var colorPickerState by remember { mutableStateOf<Pair<EffectViewModel.UiEffectType, Boolean>?>(null) }
    var showEffectListSheet by remember { mutableStateOf(false) }
    var showPresetEdit by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val fgPresetColors by viewModel.fgPresetColors.collectAsState()
    val bgPresetColors by viewModel.bgPresetColors.collectAsState()
    val selectedFgPreset by viewModel.selectedFgPreset.collectAsState()
    val selectedBgPreset by viewModel.selectedBgPreset.collectAsState()
    var overrideSettings by remember { mutableStateOf<EffectViewModel.EffectSettings?>(null) }

    val customEffects by viewModel.customEffects.collectAsState()
    var showAddEffectDialog by remember { mutableStateOf(false) }
    var pendingCustomEffect by remember { mutableStateOf<Pair<String, EffectViewModel.UiEffectType.BaseEffectType>?>(null) }

    // ✅ 추가: 새로운 다이얼로그 상태
    var showRenameDialogFor by remember { mutableStateOf<EffectViewModel.UiEffectType.Custom?>(null) }
    var showDeleteDialogFor by remember { mutableStateOf<EffectViewModel.UiEffectType.Custom?>(null) }

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
    val maxOffsetPx = with(LocalDensity.current) { collapseRange.toPx() }

    var isCorrectingScroll by remember { mutableStateOf(false) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isCorrectingScroll) return Offset.Zero

                if (available.y < 0) {
                    val newOffset = (collapsedOffset + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val consumed = newOffset - collapsedOffset
                    collapsedOffset = newOffset
                    return if (consumed != 0f) Offset(0f, -consumed) else Offset.Zero
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (isCorrectingScroll) return Offset.Zero

                if (available.y > 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    val newOffset = (collapsedOffset + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val consumedY = collapsedOffset - newOffset
                    collapsedOffset = newOffset
                    return if (consumedY != 0f) Offset(0f, consumedY) else Offset.Zero
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isCorrectingScroll) return Velocity.Zero

                if (collapsedOffset > 0 && collapsedOffset < maxOffsetPx) {
                    coroutineScope.launch {
                        // 민감도 조정 : 50%를 기준으로 카드를 크거나 작게 변하도록 기준을 변경합니다.
                        val targetOffset = if (collapsedOffset > maxOffsetPx * 0.5f) {
                            maxOffsetPx
                        } else {
                            0f
                        }

                        animate(
                            initialValue = collapsedOffset,
                            targetValue = targetOffset,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { value, _ ->
                            collapsedOffset = value
                        }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 50% 이상 접혔을 때만 Scrolled 상태로 간주하여 민감도를 조절합니다.
    val isScrolled = remember { derivedStateOf { collapsedOffset > maxOffsetPx * 0.5f } }.value
    val isDeviceConnected = deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected

    LaunchedEffect(isScrolled) {
        // DeviceConnectionCard가 완전히 축소되었을 때,
        if (isScrolled) {
            // 스크롤 보정은 리스트 최상단에 있을 때만 필요합니다.
            if (listState.firstVisibleItemIndex == 0) {
                // 카드 높이 변경 후 레이아웃이 다시 계산되도록 한 프레임 대기합니다.
                yield()
                // 만약 첫 아이템이 조금이라도 잘려 보인다면, 스크롤 위치를 보정합니다.
                if (listState.firstVisibleItemScrollOffset > 0) {
                    try {
                        isCorrectingScroll = true
                        listState.animateScrollToItem(0)
                    } finally {
                        isCorrectingScroll = false
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startAutoScan(context)
    }

    val basicEffects = remember { listOf(EffectViewModel.UiEffectType.On, EffectViewModel.UiEffectType.Off, EffectViewModel.UiEffectType.Strobe, EffectViewModel.UiEffectType.Blink, EffectViewModel.UiEffectType.Breath) }
    val effectLists = remember { listOf(EffectViewModel.UiEffectType.EffectList(1, "발라드"), EffectViewModel.UiEffectType.EffectList(2, "댄스"), EffectViewModel.UiEffectType.EffectList(3, "록"), EffectViewModel.UiEffectType.EffectList(4, "힙합"), EffectViewModel.UiEffectType.EffectList(5, "재즈"), EffectViewModel.UiEffectType.EffectList(6, "클래식")) }
    val previewSettings = overrideSettings ?: currentSettings

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBarCentered(
                title = "Effect Control",
                actionText = "LIST",
                actionTextColor = if (isDeviceConnected) {
                    MaterialTheme.customColors.secondary
                } else {
                    Color.Gray
                },
                onActionClick = {
                    if (isDeviceConnected) {
                        showEffectListSheet = true
                    }
                }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .nestedScroll(nestedScrollConnection)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    DeviceConnectionCard(
                        connectionState = deviceConnectionState,
                        onConnectClick = {
                            navController.navigate("deviceList") {
                                popUpTo("effect") {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        onRetryClick = { viewModel.retryAutoScan(context) },
                        isScrolled = isScrolled,
                        selectedEffect = selectedEffect,
                        effectSettings = previewSettings,
                        isPlaying = isPlaying || overrideSettings != null,
                        latestTransmission = latestTransmission
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    errorMessage?.let { error ->
                        ErrorBanner(message = error, onDismiss = { viewModel.clearError() })
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    LazyColumn(state = listState, modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                onBackgroundColorClick = { colorPickerState = effect to true },
                                onRenameClick = { /* Basic effects can't be renamed */ },
                                onDeleteClick = { /* Basic effects can't be deleted */ }
                            )
                        }

                        items(customEffects) { effect ->
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
                                onBackgroundColorClick = { colorPickerState = effect to true },
                                // ✅ 추가: Custom Effect 메뉴 콜백 연결
                                onRenameClick = { showRenameDialogFor = effect },
                                onDeleteClick = { showDeleteDialogFor = effect }
                            )
                        }

                        if (viewModel.canAddCustomEffect()) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                AddEffectButton(
                                    onClick = { showAddEffectDialog = true },
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                            }
                        } else {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }

        CustomToast(
            message = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

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

    if (showAddEffectDialog) {
        AddCustomEffectDialog(
            onDismiss = { showAddEffectDialog = false },
            onConfirm = { name, baseType ->
                showAddEffectDialog = false
                pendingCustomEffect = name to baseType
            }
        )
    }

    pendingCustomEffect?.let { (name, baseType) ->
        ConfirmAddEffectDialog(
            effectName = name,
            onDismiss = { pendingCustomEffect = null },
            onConfirm = {
                viewModel.addCustomEffect(name, baseType)
                pendingCustomEffect = null
            }
        )
    }

    // ✅ 추가: 이름 변경 다이얼로그 호출
    showRenameDialogFor?.let { effectToRename ->
        RenameEffectDialog(
            initialName = effectToRename.name,
            onDismiss = { showRenameDialogFor = null },
            onConfirm = { newName ->
                viewModel.renameCustomEffect(effectToRename.id, newName)
                showRenameDialogFor = null
            }
        )
    }

    // ✅ 추가: 삭제 확인 다이얼로그 호출
    showDeleteDialogFor?.let { effectToDelete ->
        ConfirmDeleteEffectDialog(
            effectName = effectToDelete.name,
            onDismiss = { showDeleteDialogFor = null },
            onConfirm = {
                viewModel.deleteCustomEffect(effectToDelete)
                showDeleteDialogFor = null
            }
        )
    }

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
                val sdkColor = color.toLightStickColor()
                if (selectedEffect == effect) {
                    if (isBackground) viewModel.updateBackgroundColor(context, sdkColor) else viewModel.updateColor(context, sdkColor)
                } else {
                    val updated = if (isBackground) settings.copy(backgroundColor = sdkColor) else settings.copy(color = sdkColor)
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