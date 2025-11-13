package com.dongsitech.lightstickmusicdemo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.lightstick.sdk.ble.model.ScanResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightStickListScreen(
    viewModel: LightStickListViewModel,
    navController: NavController,
    onDeviceSelected: (ScanResult) -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
//    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()

    val canShowAddress =
        permissionGranted && PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

    val openSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    val pullRefreshState = rememberPullToRefreshState()

    val listState = rememberLazyListState()
    val canScrollUp = { listState.canScrollBackward }
    val pullFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    RequestPermissions(
        permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ),
        onGranted = { viewModel.setPermissionGranted(true) },
        onDenied = { viewModel.setPermissionGranted(false) }
    )

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.startScan(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("응원봉 목록") },
                actions = {
                    if (permissionGranted) {
                        AnimatedScanIconButton(
                            isScanning = isScanning,
                            onStartScan = { viewModel.startScan(context) }
                        )
                    }
                },
                // 상태바 패딩 제거 → 상태바 바로 아래 붙임
                windowInsets = WindowInsets(0)
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        StretchPullRefreshContainer(
            isRefreshing = isScanning,
            onRefresh = { viewModel.startScan(context) },
            canScrollUp = canScrollUp,
            fillColor = pullFill,
            edgeColor = Color.Black.copy(alpha = 0.04f),
            edgeWidth = 0.5.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!permissionGranted) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "권한이 필요합니다.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        openSettingsLauncher.launch(intent)
                    }) {
                        Text("권한 설정하러 가기")
                    }
                }
            } else {
                // ✅ 항상 LazyColumn 사용 → 빈 상태에서도 Pull-to-Refresh 동작 보장
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    if (devices.isEmpty() && !isScanning /*&& !isRefreshing*/) {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Filled.BluetoothDisabled,
                                        contentDescription = "No Devices Found",
                                        modifier = Modifier.height(48.dp),
                                        tint = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "주변에 블루투스 모드 동작 중인 \n 응원봉이 없습니다.",
                                        textAlign = TextAlign.Center,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    } else {
                        items(devices, key = { it.address }) { res ->
                            val address = res.address
                            val rssi = res.rssi
                            val isConnected = connectionStates[address] == true
                            val name = res.name ?: "Unnamed Device"

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$rssi dBm",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = if (canShowAddress) address else "주소 비공개",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = if (isConnected) "연결됨" else "연결 안 됨",
                                                color = if (isConnected) Color.Red else Color.Gray,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        Button(
                                            onClick = { onDeviceSelected(res) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isConnected) Color.Red else Color.Blue
                                            )
                                        ) {
                                            Text(if (isConnected) "Disconnect" else "Connect")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
        // 비활성일 때도 아이콘이 흐려지지 않도록 고정
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
