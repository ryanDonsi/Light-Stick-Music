package com.dongsitech.lightstickmusicdemo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongsitech.lightstickmusicdemo.ui.components.effect.*
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * ✅ 리팩토링: 컴포넌트 분리 완료
 *
 * Before: 800+ 줄
 * After: ~130 줄
 */
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
    val effectSettingsMap by viewModel.effectSettingsMap.collectAsState()

    // 다이얼로그 상태
    var settingsDialogEffect by remember { mutableStateOf<EffectViewModel.UiEffectType?>(null) }
    var colorPickerState by remember { mutableStateOf<Pair<EffectViewModel.UiEffectType, Boolean>?>(null) }

    // 이펙트 목록
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

            // ✅ 에러 배너 (컴포넌트)
            errorMessage?.let { error ->
                item {
                    ErrorBanner(
                        message = error,
                        onDismiss = { viewModel.clearError() }
                    )
                }
            }

            // ✅ 이펙트 타입 카드들 (컴포넌트)
            items(effects) { effect ->
                val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                    ?: EffectViewModel.EffectSettings.defaultFor(effect)

                EffectTypeCard(
                    effect = effect,
                    effectSettings = effectSettings,
                    isSelected = selectedEffect == effect,
                    isPlaying = isPlaying && selectedEffect == effect,
                    onEffectClick = {
                        viewModel.selectEffect(context, effect)
                    },
                    onSettingsClick = { settingsDialogEffect = effect },
                    onForegroundColorClick = { colorPickerState = effect to false },
                    onBackgroundColorClick = { colorPickerState = effect to true }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ✅ 설정 다이얼로그 (컴포넌트)
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

    // ✅ 색상 선택 다이얼로그 (컴포넌트)
    colorPickerState?.let { (effect, isBackground) ->
        val settings = effectSettingsMap[viewModel.getEffectKey(effect)]
            ?: EffectViewModel.EffectSettings.defaultFor(effect)

        val currentColor = if (isBackground) settings.backgroundColor else settings.color

        ColorPickerDialog(
            currentColor = currentColor,
            onColorSelected = { newColor ->
                if (selectedEffect == effect) {
                    if (isBackground) {
                        viewModel.updateBackgroundColor(context, newColor)
                    } else {
                        viewModel.updateColor(context, newColor)
                    }
                } else {
                    val updatedSettings = if (isBackground) {
                        settings.copy(backgroundColor = newColor)
                    } else {
                        settings.copy(color = newColor)
                    }
                    viewModel.saveEffectSettings(effect, updatedSettings)
                }
            },
            onDismiss = { colorPickerState = null }
        )
    }
}