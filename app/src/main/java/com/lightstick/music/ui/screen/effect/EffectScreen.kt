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
import com.lightstick.music.ui.components.effect.AddCustomEffectDialog
import com.lightstick.music.ui.components.effect.AddEffectButton
import com.lightstick.music.ui.components.effect.ColorPickerDialog
import com.lightstick.music.ui.components.effect.ConfirmAddEffectDialog
import com.lightstick.music.ui.components.effect.ConfirmDeleteEffectDialog
import com.lightstick.music.ui.components.effect.DeviceConnectionCard
import com.lightstick.music.ui.components.effect.EffectListSheetContent
import com.lightstick.music.ui.components.effect.EffectSettingsDialog
import com.lightstick.music.ui.components.effect.EffectTypeCard
import com.lightstick.music.ui.components.effect.ErrorBanner
import com.lightstick.music.ui.components.effect.PresetColorEditDialog
import com.lightstick.music.ui.components.effect.RenameEffectDialog
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

    // ── ViewModel State ───────────────────────────────────────
    val selectedEffect           by viewModel.selectedEffect.collectAsState()
    val currentSettings          by viewModel.currentSettings.collectAsState()
    val isPlaying                by viewModel.isPlaying.collectAsState()
    val errorMessage             by viewModel.errorMessage.collectAsState()
    val effectSettingsMap        by viewModel.effectSettingsMap.collectAsState()
    val deviceConnectionState    by viewModel.deviceConnectionState.collectAsState()
    val latestTransmission       by viewModel.latestTransmission.collectAsState()
    val customEffects            by viewModel.customEffects.collectAsState()
    val selectedEffectListNumber by viewModel.selectedEffectListNumber.collectAsState()
    val fgPresetColors           by viewModel.fgPresetColors.collectAsState()
    val bgPresetColors           by viewModel.bgPresetColors.collectAsState()
    val selectedFgPreset         by viewModel.selectedFgPreset.collectAsState()
    val selectedBgPreset         by viewModel.selectedBgPreset.collectAsState()

    // [추가] EXCLUSIVE 모드 음악 재생 중 Effect 잠금 상태
    val isEffectBlocked by viewModel.isEffectBlocked.collectAsState()

    // ── UI State ──────────────────────────────────────────────
    var settingsDialogEffect by remember { mutableStateOf<EffectViewModel.UiEffectType?>(null) }
    var colorPickerState     by remember { mutableStateOf<Pair<EffectViewModel.UiEffectType, Boolean>?>(null) }
    var showEffectListSheet  by remember { mutableStateOf(false) }
    var showPresetEdit       by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var overrideSettings     by remember { mutableStateOf<EffectViewModel.EffectSettings?>(null) }
    var showAddEffectDialog  by remember { mutableStateOf(false) }
    var pendingCustomEffect  by remember { mutableStateOf<Pair<String, EffectViewModel.UiEffectType.BaseEffectType>?>(null) }
    var showRenameDialogFor  by remember { mutableStateOf<EffectViewModel.UiEffectType.Custom?>(null) }
    var showDeleteDialogFor  by remember { mutableStateOf<EffectViewModel.UiEffectType.Custom?>(null) }

    // ── Toast ─────────────────────────────────────────────────
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastState   = rememberToastState()

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
    // [추가] Effect 잠금 시 토스트 표시
    LaunchedEffect(isEffectBlocked) {
        if (isEffectBlocked) {
            toastState.show("음악 자동 재생 중 Effect 조작이 비활성화됩니다")
        }
    }

    // ── Scroll / Collapse ─────────────────────────────────────
    val listState           = rememberLazyListState()
    val coroutineScope      = rememberCoroutineScope()
    val maxCardHeight       = 180.dp
    val minCardHeight       = 124.dp
    val collapseRange       = maxCardHeight - minCardHeight
    val maxOffsetPx         = with(LocalDensity.current) { collapseRange.toPx() }
    val collapsedOffsetState = remember { mutableFloatStateOf(0f) }
    var isCorrectingScroll   by remember { mutableStateOf(false) }

    @Suppress("NAME_SHADOWING")
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isCorrectingScroll) return Offset.Zero
                if (available.y < 0) {
                    val current  = collapsedOffsetState.floatValue
                    val newValue = (current + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val consumed = newValue - current
                    collapsedOffsetState.floatValue = newValue
                    return if (consumed != 0f) Offset(0f, -consumed) else Offset.Zero
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (isCorrectingScroll) return Offset.Zero
                if (available.y > 0 &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    val current  = collapsedOffsetState.floatValue
                    val newValue = (current + (-available.y)).coerceIn(0f, maxOffsetPx)
                    val delta    = current - newValue
                    collapsedOffsetState.floatValue = newValue
                    return if (delta != 0f) Offset(0f, delta) else Offset.Zero
                }
                return Offset.Zero
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (isCorrectingScroll) return Velocity.Zero
                val current = collapsedOffsetState.floatValue
                if (current > 0 && current < maxOffsetPx) {
                    coroutineScope.launch {
                        val target = if (current > maxOffsetPx * 0.5f) maxOffsetPx else 0f
                        animate(
                            initialValue = current,
                            targetValue  = target,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessLow
                            )
                        ) { value, _ -> collapsedOffsetState.floatValue = value }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val isScrolled by remember { derivedStateOf { collapsedOffsetState.floatValue > maxOffsetPx * 0.5f } }

    LaunchedEffect(isScrolled) {
        if (isScrolled && listState.firstVisibleItemIndex == 0) {
            yield()
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

    LaunchedEffect(Unit) { viewModel.startAutoScan(context) }

    // ── Static Data ───────────────────────────────────────────
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

    val isDeviceConnected = deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected

    // [수정] Effect 카드 활성화 여부 = 디바이스 연결 AND Effect 미잠금
    val isEffectEnabled = isDeviceConnected && !isEffectBlocked

    val previewSettings = overrideSettings ?: currentSettings

    // ─────────────────────────────────────────────────────────
    // Main Layout
    // ─────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {

            TopBarCentered(
                title       = "Effect Control",
                actionText  = "LIST",
                // [수정] Effect 잠금 시 LIST 버튼도 비활성화 색상으로 표시
                actionTextColor = when {
                    isEffectBlocked   -> Color.Gray
                    isDeviceConnected -> MaterialTheme.customColors.secondary
                    else              -> Color.Gray
                },
                onActionClick = {
                    if (isEffectBlocked) {
                        // 잠금 상태 피드백은 ViewModel 에서 toast 로 처리
                        viewModel.selectEffectList(context, -1) // 차단 로직 트리거
                    } else if (isDeviceConnected) {
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
                    painter      = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier     = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment    = Alignment.TopCenter
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .nestedScroll(nestedScrollConnection)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    DeviceConnectionCard(
                        connectionState    = deviceConnectionState,
                        onConnectClick     = {
                            navController.navigate("deviceList") {
                                popUpTo("effect") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onRetryClick       = { viewModel.retryAutoScan(context) },
                        isScrolled         = isScrolled,
                        selectedEffect     = selectedEffect,
                        effectSettings     = previewSettings,
                        isPlaying          = isPlaying || overrideSettings != null,
                        latestTransmission = latestTransmission
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    errorMessage?.let { error ->
                        ErrorBanner(
                            message   = error,
                            onDismiss = { viewModel.clearError() }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    LazyColumn(
                        state   = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── Basic Effects ─────────────────────────────────
                        items(basicEffects) { effect ->
                            val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                                ?: EffectViewModel.EffectSettings.defaultFor(effect)
                            EffectTypeCard(
                                effect         = effect,
                                effectSettings = effectSettings,
                                isSelected     = selectedEffect == effect,
                                // [수정] isEffectBlocked 시 카드 비활성화
                                isEnabled      = isEffectEnabled,
                                onEffectClick  = {
                                    // [수정] toggleEffect 로 교체 (선택 해제 + EXCLUSIVE 처리)
                                    viewModel.toggleEffect(context, effect)
                                },
                                onSettingsClick        = { settingsDialogEffect = effect },
                                onForegroundColorClick = { colorPickerState = effect to false },
                                onBackgroundColorClick = { colorPickerState = effect to true },
                                onRenameClick          = { /* 기본 이펙트 이름 변경 불가 */ },
                                onDeleteClick          = { /* 기본 이펙트 삭제 불가 */ }
                            )
                        }

                        // ── Custom Effects ────────────────────────────────
                        items(customEffects) { effect ->
                            val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                                ?: EffectViewModel.EffectSettings.defaultFor(effect)
                            EffectTypeCard(
                                effect         = effect,
                                effectSettings = effectSettings,
                                isSelected     = selectedEffect == effect,
                                isEnabled      = isEffectEnabled,
                                onEffectClick  = {
                                    viewModel.toggleEffect(context, effect)
                                },
                                onSettingsClick        = { settingsDialogEffect = effect },
                                onForegroundColorClick = { colorPickerState = effect to false },
                                onBackgroundColorClick = { colorPickerState = effect to true },
                                onRenameClick          = { showRenameDialogFor = effect },
                                onDeleteClick          = { showDeleteDialogFor = effect }
                            )
                        }

                        // ── Add Button ────────────────────────────────────
                        if (viewModel.canAddCustomEffect()) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                AddEffectButton(
                                    onClick  = { showAddEffectDialog = true },
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                            }
                        } else {
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }

        CustomToast(
            message   = toastState.message,
            isVisible = toastState.isVisible,
            onDismiss = { toastState.dismiss() },
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ─────────────────────────────────────────────────────────
    // Dialogs / BottomSheets
    // ─────────────────────────────────────────────────────────

    // Effect List BottomSheet
    if (showEffectListSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEffectListSheet = false },
            sheetState       = sheetState,
            containerColor   = MaterialTheme.colorScheme.surface
        ) {
            EffectListSheetContent(
                effectLists              = effectLists,
                selectedEffectListNumber = selectedEffectListNumber,
                onEffectClick            = { effectNumber ->
                    // [수정] EXCLUSIVE 로직은 ViewModel 내부에서 처리
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

    // Add Custom Effect Dialog
    if (showAddEffectDialog) {
        AddCustomEffectDialog(
            onDismiss = { showAddEffectDialog = false },
            onConfirm = { name, baseType ->
                showAddEffectDialog = false
                pendingCustomEffect = name to baseType
            }
        )
    }

    // Confirm Add Effect Dialog
    pendingCustomEffect?.let { (name, baseType) ->
        ConfirmAddEffectDialog(
            effectName = name,
            onDismiss  = { pendingCustomEffect = null },
            onConfirm  = {
                viewModel.addCustomEffect(name, baseType)
                pendingCustomEffect = null
            }
        )
    }

    // Rename Effect Dialog
    showRenameDialogFor?.let { effectToRename ->
        RenameEffectDialog(
            initialName = effectToRename.name,
            onDismiss   = { showRenameDialogFor = null },
            onConfirm   = { newName ->
                viewModel.renameCustomEffect(effectToRename, newName)
                showRenameDialogFor = null
            }
        )
    }

    // Delete Confirm Dialog
    showDeleteDialogFor?.let { effectToDelete ->
        ConfirmDeleteEffectDialog(
            effectName = effectToDelete.name,
            onDismiss  = { showDeleteDialogFor = null },
            onConfirm  = {
                viewModel.deleteCustomEffect(effectToDelete)
                showDeleteDialogFor = null
            }
        )
    }

    // Effect Settings Dialog
    settingsDialogEffect?.let { effect ->
        val initialSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
            ?: EffectViewModel.EffectSettings.defaultFor(effect)
        LaunchedEffect(effect) { overrideSettings = initialSettings }
        EffectSettingsDialog(
            effect           = effect,
            settings         = overrideSettings ?: initialSettings,
            onSettingsChange = { newSettings -> overrideSettings = newSettings },
            onDismiss        = {
                overrideSettings?.let { finalSettings ->
                    viewModel.saveEffectSettings(effect, finalSettings)
                    if (selectedEffect == effect) {
                        viewModel.updateSettings(context, finalSettings)
                    }
                }
                settingsDialogEffect = null
                overrideSettings     = null
            }
        )
    }

    // Color Picker Dialog
    colorPickerState?.let { (effect, isBackground) ->
        val settings     = effectSettingsMap[viewModel.getEffectKey(effect)]
            ?: EffectViewModel.EffectSettings.defaultFor(effect)
        val currentColor = if (isBackground) settings.backgroundColor else settings.color
        ColorPickerDialog(
            title               = if (isBackground) "Background Color 설정" else "Foreground Color 설정",
            initialColor        = currentColor.toComposeColor(),
            presetColors        = if (isBackground) bgPresetColors else fgPresetColors,
            selectedPresetIndex = if (isBackground) selectedBgPreset else selectedFgPreset,
            onColorSelected     = { color ->
                val sdkColor = color.toLightStickColor()
                if (selectedEffect == effect) {
                    if (isBackground) viewModel.updateBackgroundColor(context, sdkColor)
                    else              viewModel.updateColor(context, sdkColor)
                } else {
                    val updated = if (isBackground) settings.copy(backgroundColor = sdkColor)
                    else              settings.copy(color = sdkColor)
                    viewModel.saveEffectSettings(effect, updated)
                }
            },
            onPresetSelected = { index ->
                if (isBackground) viewModel.selectBgPreset(index) else viewModel.selectFgPreset(index)
            },
            onPresetEdit = { index -> showPresetEdit = index to isBackground },
            onDismiss    = { colorPickerState = null }
        )
    }

    // Preset Color Edit Dialog
    showPresetEdit?.let { (index, isBg) ->
        val colors = if (isBg) bgPresetColors else fgPresetColors
        PresetColorEditDialog(
            presetIndex     = index,
            initialColor    = colors[index],
            onColorSelected = { color ->
                if (isBg) viewModel.updateBgPresetColor(index, color)
                else      viewModel.updateFgPresetColor(index, color)
            },
            onDismiss = { showPresetEdit = null }
        )
    }
}