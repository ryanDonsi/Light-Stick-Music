package com.dongsitech.lightstickmusicdemo.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dongsitech.lightstickmusicdemo.R
import com.dongsitech.lightstickmusicdemo.ui.components.common.TopBarCentered
import com.dongsitech.lightstickmusicdemo.ui.components.effect.*
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectScreen(
    viewModel: EffectViewModel = viewModel(),
    onNavigateToDeviceList: () -> Unit = {}
) {
    val context = LocalContext.current
    val selectedEffect by viewModel.selectedEffect.collectAsState()
    val playingEffect by viewModel.playingEffect.collectAsState()
    val effectSettingsMap by viewModel.effectSettingsMap.collectAsState()
    val deviceConnectionState by viewModel.deviceConnectionState.collectAsState()
    val customEffects by viewModel.customEffects.collectAsState()

    // ✅ 스크롤 상태 감지
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemScrollOffset > 50
        }
    }

    // ✅ Device 연결 상태 확인
    val isDeviceConnected = deviceConnectionState is EffectViewModel.DeviceConnectionState.Connected

    // ✅ Effect List 팝업 상태
    var showEffectListDialog by remember { mutableStateOf(false) }

    // ✅ 기본 이펙트 목록
    val basicEffects = remember {
        listOf(
            EffectViewModel.UiEffectType.On,
            EffectViewModel.UiEffectType.Off,
            EffectViewModel.UiEffectType.Strobe,
            EffectViewModel.UiEffectType.Blink,
            EffectViewModel.UiEffectType.Breath
        )
    }

    // ✅ Effect List 목록
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

    // ✅ Effect 진입 시 BLE Scan & 자동 연결
    LaunchedEffect(Unit) {
        if (deviceConnectionState is EffectViewModel.DeviceConnectionState.NoBondedDevice) {
            viewModel.startAutoReconnect()
        }
    }

    // ✅ 현재 재생 중인 Effect의 색상
    val currentEffectColor = remember(playingEffect, effectSettingsMap) {
        playingEffect?.let { effect ->
            val settings = effectSettingsMap[viewModel.getEffectKey(effect)]
                ?: EffectViewModel.EffectSettings.defaultFor(effect)
            androidx.compose.ui.graphics.Color(
                settings.color.r / 255f,
                settings.color.g / 255f,
                settings.color.b / 255f
            )
        } ?: androidx.compose.ui.graphics.Color.Red
    }

    Scaffold(
        topBar = {
            TopBarCentered(
                title = "이펙트"
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ✅ 배경 이미지
            Image(
                painter = painterResource(id = R.drawable.background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )

            // ✅ 콘텐츠
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // ✅ 디바이스 연결 상태 카드
                item {
                    DeviceConnectionCard(
                        connectionState = deviceConnectionState,
                        onConnectClick = onNavigateToDeviceList,
                        onRetryClick = { viewModel.startAutoReconnect() },
                        currentEffectColor = currentEffectColor,
                        isScrolled = isScrolled
                    )
                }

                // ✅ 기본 이펙트 카드들
                items(basicEffects) { effect ->
                    val effectSettings = effectSettingsMap[viewModel.getEffectKey(effect)]
                        ?: EffectViewModel.EffectSettings.defaultFor(effect)

                    EffectTypeCard(
                        effect = effect,
                        effectSettings = effectSettings,
                        isSelected = selectedEffect == effect,
                        isPlaying = playingEffect == effect,
                        isEnabled = isDeviceConnected,
                        onEffectClick = {
                            if (isDeviceConnected) {
                                viewModel.selectEffect(effect)
                                viewModel.playEffect(context, effect)
                            }
                        },
                        onSettingsClick = {
                            // TODO: Settings Dialog 구현
                        },
                        onForegroundColorClick = {
                            // TODO: Color Picker Dialog 구현
                        },
                        onBackgroundColorClick = {
                            // TODO: Color Picker Dialog 구현
                        }
                    )
                }

                // ✅ Custom Effect 카드들
                items(customEffects) { customEffect ->
                    val effectSettings = effectSettingsMap[viewModel.getEffectKey(customEffect)]
                        ?: EffectViewModel.EffectSettings.defaultFor(customEffect)

                    EffectTypeCard(
                        effect = customEffect,
                        effectSettings = effectSettings,
                        isSelected = selectedEffect == customEffect,
                        isPlaying = playingEffect == customEffect,
                        isEnabled = isDeviceConnected,
                        onEffectClick = {
                            if (isDeviceConnected) {
                                viewModel.selectEffect(customEffect)
                                viewModel.playEffect(context, customEffect)
                            }
                        },
                        onSettingsClick = {
                            // TODO: Settings Dialog 구현
                        },
                        onForegroundColorClick = {
                            // TODO: Color Picker Dialog 구현
                        },
                        onBackgroundColorClick = {
                            // TODO: Color Picker Dialog 구현
                        }
                    )
                }

                // ✅ Effect List 버튼
                item {
                    Button(
                        onClick = { showEffectListDialog = true },
                        enabled = isDeviceConnected,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Effect List")
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // ✅ Effect List 하단 팝업
    if (showEffectListDialog) {
        ModalBottomSheet(
            onDismissRequest = { showEffectListDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Effect List",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                effectLists.forEach { effect ->
                    Card(
                        onClick = {
                            viewModel.selectEffect(effect)
                            viewModel.playEffect(context, effect)
                            showEffectListDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = effect.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = effect.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (playingEffect == effect) {
                                Text(
                                    text = "재생 중",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}