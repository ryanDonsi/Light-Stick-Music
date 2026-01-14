package com.lightstick.music.ui.screen.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.ui.components.common.StretchPullRefreshContainer
import com.lightstick.music.ui.components.device.*
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.device.Device

/**
 * ✅ 리팩토링: 컴포넌트 분리 완료
 *
 * Before: 600+ 줄
 * After: ~120 줄
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceViewModel,
    navController: NavController,
    onDeviceSelected: (Device) -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val deviceDetails by viewModel.deviceDetails.collectAsState()

    val hasAllBluetoothPermissions = PermissionManager.hasAllBluetoothPermissions(context)
    val canShowAddress = hasAllBluetoothPermissions &&
            PermissionManager.hasBluetoothConnectPermission(context)

    val listState = rememberLazyListState()
    val canScrollUp = { listState.canScrollBackward }

    val pullFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val edgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    // 권한 있을 때만 스캔 시작 (최초 1회)
    var initialScanDone by remember { mutableStateOf(false) }

    LaunchedEffect(hasAllBluetoothPermissions) {
        if (hasAllBluetoothPermissions && !initialScanDone) {
            viewModel.startScan(context)
            initialScanDone = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("응원봉 목록") },
                actions = {
                    if (hasAllBluetoothPermissions) {
                        AnimatedScanButton(
                            isScanning = isScanning,
                            onStartScan = { viewModel.startScan(context) }
                        )
                    }
                },
                windowInsets = WindowInsets(0)
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        if (!hasAllBluetoothPermissions) {
            // ✅ 권한 없음 화면 (컴포넌트)
            PermissionBanner(
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            // ✅ 정상 화면
            StretchPullRefreshContainer(
                isRefreshing = isScanning,
                onRefresh = { viewModel.startScan(context) },
                canScrollUp = canScrollUp,
                fillColor = pullFill,
                edgeColor = edgeColor,
                edgeWidth = 1.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (devices.isEmpty()) {
                    // ✅ 빈 목록 화면 (컴포넌트)
                    EmptyDeviceList(
                        isScanning = isScanning
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val sortedDevices = devices.sortedByDescending { device ->
                            connectionStates[device.mac] ?: false
                        }

                        items(sortedDevices, key = { it.mac }) { device ->
                            // ✅ 디바이스 카드 (컴포넌트)
                            DeviceCard(
                                device = device,
                                isConnected = connectionStates[device.mac] ?: false,
                                deviceDetail = deviceDetails[device.mac],
                                canShowAddress = canShowAddress,
                                onToggleConnection = { onDeviceSelected(device) },
                                onStartOta = { uri -> viewModel.startOta(context, device, uri) },
                                onAbortOta = { viewModel.abortOta(device) },
                                onToggleCallEvent = { enabled -> viewModel.toggleCallEvent(device, enabled) },
                                onToggleSmsEvent = { enabled -> viewModel.toggleSmsEvent(device, enabled) },
                                onToggleBroadcasting = { enabled -> viewModel.toggleBroadcasting(device, enabled) }
                            )
                        }
                    }
                }
            }
        }
    }
}