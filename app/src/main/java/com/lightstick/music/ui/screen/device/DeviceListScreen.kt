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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.ui.components.common.CustomTopBar
import com.lightstick.music.ui.components.common.StretchPullRefreshContainer
import com.lightstick.music.ui.components.device.*
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.device.Device

/**
 * 디바이스 목록 화면 (Figma 디자인 100% 적용 + Pull-to-Refresh)
 *
 * ## 화면 구조:
 * 1. CustomTopBar ("Device Setting")
 * 2. StretchPullRefreshContainer (Pull-to-Refresh)
 *    - 연결된 기기 섹션
 *      - DeviceSectionHeader("연결된 기기")
 *      - 연결된 기기 카드들 OR EmptyDeviceCard
 *    - 검색된 기기 섹션
 *      - DeviceSectionHeader("검색된 기기")
 *      - 검색된 기기 카드들 OR EmptyDeviceCard
 *
 * ## 권한:
 * - 권한 없음: PermissionBanner 표시
 * - 권한 있음: 정상 화면 표시 (Pull-to-Refresh 포함)
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

    // ✅ LazyListState 추가 (Pull-to-Refresh용)
    val listState = rememberLazyListState()
    val canScrollUp = { listState.canScrollBackward }

    // ✅ Pull-to-Refresh 색상 설정
    val pullFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val edgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    // 권한 있을 때만 스캔 시작 (최초 1회)
    var initialScanDone by remember { mutableStateOf(false) }
    LaunchedEffect(hasAllBluetoothPermissions) {
        if (hasAllBluetoothPermissions && !initialScanDone) {
            viewModel.stopScan()
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
            // ===== 정상 화면 (Pull-to-Refresh 적용) =====
            StretchPullRefreshContainer(
                isRefreshing = isScanning,
                onRefresh = {
                    viewModel.stopScan()
                    viewModel.startScan(context)
                },
                canScrollUp = canScrollUp,
                fillColor = pullFill,
                edgeColor = edgeColor,
                edgeWidth = 1.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    state = listState,  // ✅ LazyListState 연결
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
//                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Top
                ) {
//                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    // ===== 연결된 기기 섹션 =====
                    item {
                        DeviceSectionHeader(title = "연결된 기기")
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }

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

                    item { Spacer(modifier = Modifier.height(32.dp)) }

                    // ===== 검색된 기기 섹션 =====
                    item {
                        DeviceSectionHeader(title = "검색된 기기")
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    if (scannedDevices.isEmpty()) {
                        // 검색된 기기 없음
                        item {
                            EmptyDeviceCard(
                                isScanning = isScanning,
                                isConnectedSection = false,
                                onRefresh = { viewModel.startScan(context) }
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
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        item { Spacer(modifier = Modifier.height(4.dp)) }

                        item {
                            DeviceInfoFooter()
                        }
                    }
                }
            }
        }
    }
}

/**
 * ✅ [추가] 화면 하단 설명 텍스트
 */
@Composable
private fun DeviceInfoFooter(modifier: Modifier = Modifier) {Column(
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp), // 상/하단 여백 추가
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.surfaceVariant

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("・", style = style, color = color)
        Text(
            text = "연결된 기기가 있을 경우 검색된 기기로 연결하면 새롭게 연결한 기기로 대체됩니다.",
            style = style,
            color = color
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("・", style = style, color = color)
        Text(
            text = "어플 실행 시 연결된 기기로 자동 연결을 진행합니다.",
            style = style,
            color = color
        )
    }
}
}