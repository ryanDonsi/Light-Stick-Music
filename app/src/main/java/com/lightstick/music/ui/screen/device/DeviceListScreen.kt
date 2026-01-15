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
import com.lightstick.music.ui.components.common.CustomTopBar
import com.lightstick.music.ui.components.device.*
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.device.Device

/**
 * 디바이스 목록 화면 (Figma 디자인 100% 적용)
 *
 * ## 화면 구조:
 * 1. CustomTopBar ("Device Setting")
 * 2. 연결된 기기 섹션
 *    - DeviceSectionHeader("연결된 기기")
 *    - 연결된 기기 카드들 OR EmptyDeviceCard
 * 3. 검색된 기기 섹션
 *    - DeviceSectionHeader("검색된 기기")
 *    - 검색된 기기 카드들 OR EmptyDeviceCard
 *
 * ## 권한:
 * - 권한 없음: PermissionBanner 표시
 * - 권한 있음: 정상 화면 표시
 *
 * @param viewModel DeviceViewModel
 * @param navController NavController
 * @param onNavigateToDetail 디바이스 상세 화면으로 이동
 * @param onDeviceSelected 디바이스 연결/해제 토글
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceViewModel,
    navController: NavController,
    onNavigateToDetail: (Device) -> Unit = {},
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

    // 연결된 기기와 검색된 기기 분리
    val connectedDevices = devices.filter { device ->
        connectionStates[device.mac] == true
    }
    val scannedDevices = devices.filter { device ->
        connectionStates[device.mac] != true
    }

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
            CustomTopBar(
                title = "Device Setting",
                showBackButton = false
            )
        },
        containerColor = MaterialTheme.customColors.background
    ) { paddingValues ->
        if (!hasAllBluetoothPermissions) {
            // ===== 권한 없음 화면 =====
            PermissionBanner(
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            // ===== 정상 화면 =====
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                // ===== 연결된 기기 섹션 =====
                item {
                    DeviceSectionHeader(title = "연결된 기기")
                }

                if (connectedDevices.isEmpty()) {
                    // 연결된 기기 없음
                    item {
                        EmptyDeviceCard(
                            isScanning = false,
                            isConnectedSection = true
                        )
                    }
                } else {
                    // 연결된 기기 목록
                    items(connectedDevices, key = { it.mac }) { device ->
                        DeviceCard(
                            device = device,
                            isConnected = true,
                            deviceDetail = deviceDetails[device.mac],
                            canShowAddress = canShowAddress,
                            onToggleConnection = { onDeviceSelected(device) },
                            onNavigateToDetail = { onNavigateToDetail(device) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                // ===== 검색된 기기 섹션 =====
                item {
                    DeviceSectionHeader(title = "검색된 기기")
                }

                if (scannedDevices.isEmpty()) {
                    // 검색된 기기 없음
                    item {
                        EmptyDeviceCard(
                            isScanning = isScanning,
                            isConnectedSection = false
                        )
                    }
                } else {
                    // 검색된 기기 목록
                    items(scannedDevices, key = { it.mac }) { device ->
                        DeviceCard(
                            device = device,
                            isConnected = false,
                            deviceDetail = deviceDetails[device.mac],
                            canShowAddress = canShowAddress,
                            onToggleConnection = { onDeviceSelected(device) },
                            onNavigateToDetail = {}
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}