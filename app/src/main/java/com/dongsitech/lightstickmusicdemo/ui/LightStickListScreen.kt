package com.dongsitech.lightstickmusicdemo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.permissions.RequestPermissions
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickListViewModel
import com.dongsitech.lightstickmusicdemo.ui.components.StretchPullRefreshContainer
import com.lightstick.device.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightStickListScreen(
    viewModel: LightStickListViewModel,
    navController: NavController,
    onDeviceSelected: (Device) -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val deviceDetails by viewModel.deviceDetails.collectAsState()

    // ✅ 매번 실시간으로 권한 체크 (캐싱 없음)
    val hasAllBluetoothPermissions = PermissionUtils.hasAllBluetoothPermissions(context)
    val canShowAddress = hasAllBluetoothPermissions &&
            PermissionUtils.hasBluetoothConnectPermission(context)

    val openSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val canScrollUp = { listState.canScrollBackward }

    val pullFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val edgeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    // ✅ RequestPermissions는 유지 (초기 권한 요청용)
    RequestPermissions(
        permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ),
        onGranted = {
            viewModel.startScan(context)
        },
        onDenied = {
            android.util.Log.d("LightStickListScreen", "Permissions denied")
        }
    )

    // ✅ 권한 있을 때만 스캔 시작 (최초 1회)
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
                        AnimatedScanIconButton(
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
            // ✅ 권한 없음 화면
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "블루투스 권한이 필요합니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "설정에서 권한을 허용해주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        openSettingsLauncher.launch(intent)
                    }
                ) {
                    Text("설정으로 이동")
                }
            }
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
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("디바이스를 검색 중입니다...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "검색된 디바이스가 없습니다.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "아래로 당겨서 새로고침하세요",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
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
                            DeviceCard(
                                device = device,
                                isConnected = connectionStates[device.mac] ?: false,
                                deviceDetail = deviceDetails[device.mac],
                                canShowAddress = canShowAddress,
                                onToggleConnection = { onDeviceSelected(device) },
                                onStartOta = { uri -> viewModel.startOta(context, device, uri) },
                                onAbortOta = { viewModel.abortOta(device) },
                                onToggleCallEvent = { enabled -> viewModel.toggleCallEvent(device, enabled) },
                                onToggleSmsEvent = { enabled -> viewModel.toggleSmsEvent(device, enabled) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: Device,
    isConnected: Boolean,
    deviceDetail: com.dongsitech.lightstickmusicdemo.model.DeviceDetailInfo?,
    canShowAddress: Boolean,
    onToggleConnection: () -> Unit,
    onStartOta: (Uri) -> Unit,
    onAbortOta: () -> Unit,
    onToggleCallEvent: (Boolean) -> Unit,
    onToggleSmsEvent: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showOtaDialog by remember { mutableStateOf(false) }

    val otaFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onStartOta(it) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Device Name & Connection Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                            contentDescription = if (isConnected) "연결됨" else "미연결",
                            tint = if (isConnected) Color(0xFF2196F3) else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )

                        Text(
                            text = device.name ?: "Unknown Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (canShowAddress) {
                        Text(
                            text = "MAC: ${device.mac}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    device.rssi?.let { rssi ->
                        Text(
                            text = "RSSI: $rssi dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Expand/Collapse Button
                if (isConnected && deviceDetail != null) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "접기" else "펼치기"
                        )
                    }
                }
            }

            // Expanded Device Info
            if (isConnected && deviceDetail != null && expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Device Information
                deviceDetail.deviceInfo?.let { info ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Device Information",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        info.modelNumber?.let {
                            Text(
                                text = "Model: $it",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        info.firmwareRevision?.let {
                            Text(
                                text = "Firmware: $it",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        info.manufacturer?.let {
                            Text(
                                text = "Manufacturer: $it",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Battery Level
                deviceDetail.batteryLevel?.let { level ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                level >= 80 -> Icons.Default.BatteryFull
                                level >= 50 -> Icons.Default.Battery6Bar
                                level >= 20 -> Icons.Default.Battery3Bar
                                else -> Icons.Default.Battery1Bar
                            },
                            contentDescription = "Battery",
                            tint = when {
                                level >= 50 -> Color.Green
                                level >= 20 -> Color(0xFFFF9800)
                                else -> Color.Red
                            }
                        )
                        Text(
                            text = "배터리: $level%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // OTA Update Section
                Column {
                    Text(
                        text = "Firmware Update",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (deviceDetail.isOtaInProgress) {
                        // OTA 진행 중
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "업데이트 중...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${deviceDetail.otaProgress ?: 0}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (deviceDetail.otaProgress ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = onAbortOta,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("중단")
                            }
                        }
                    } else {
                        // OTA 시작 버튼
                        Button(
                            onClick = { otaFileLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("펌웨어 업데이트")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Event Settings
                Column {
                    Text(
                        text = "이벤트 설정",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // CALL Event Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "전화 이벤트",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = deviceDetail.callEventEnabled,
                            onCheckedChange = onToggleCallEvent
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // SMS Event Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Message,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "문자 이벤트",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = deviceDetail.smsEventEnabled,
                            onCheckedChange = onToggleSmsEvent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Connection Button
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onToggleConnection,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "연결 해제" else "연결",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AnimatedScanIconButton(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotationAnimation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAnimation"
    )

    IconButton(
        onClick = onStartScan,
        modifier = modifier,
        enabled = !isScanning,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = if (isScanning) "스캔 중" else "스캔 시작",
            tint = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(24.dp)
                .then(if (isScanning) Modifier.graphicsLayer { rotationZ = rotation } else Modifier)
        )
    }
}