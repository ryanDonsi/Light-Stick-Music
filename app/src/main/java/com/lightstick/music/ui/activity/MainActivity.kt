package com.lightstick.music.ui.activity

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.SideEffect
import com.lightstick.music.domain.effect.MusicEffectManager
import com.lightstick.music.ui.screen.effect.EffectScreen
import com.lightstick.music.ui.screen.device.DeviceListScreen
import com.lightstick.music.ui.theme.LightStickMusicTheme
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.music.ui.viewmodel.MusicViewModel
import com.lightstick.music.core.permission.PermissionManager
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.lightstick.music.ui.screen.music.MusicControlScreen
import com.lightstick.music.ui.screen.music.MusicListScreen
import com.lightstick.device.Device
import com.lightstick.music.ui.components.common.CustomNavigationBar
import com.lightstick.music.ui.components.device.ConnectConfirmDialog
import com.lightstick.music.ui.components.device.DeviceInfoDialog
import com.lightstick.music.ui.components.device.DisconnectConfirmDialog
import com.lightstick.music.ui.components.device.FindEffectConfirmDialog
import com.lightstick.music.ui.components.device.OtaUpdateConfirmDialog
import com.lightstick.music.ui.components.device.ReconnectConfirmDialog
import com.lightstick.music.ui.screen.device.DeviceDetailScreen

@UnstableApi
class MainActivity : ComponentActivity() {
    private val deviceViewModel: DeviceViewModel by viewModels()
    private val musicViewModel: MusicViewModel by viewModels()
    private val effectViewModel: EffectViewModel by viewModels()

    /**
     * ✅ SAF를 통한 Effects 디렉토리 선택 (수동 선택용)
     */
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                EffectPathPreferences.saveDirectoryUri(this, uri)

                // 재초기화
                MusicEffectManager.initializeFromSAF(this)

                Toast.makeText(
                    this,
                    "Effects 폴더 설정 완료 (${MusicEffectManager.getLoadedEffectCount()}개 파일)",
                    Toast.LENGTH_SHORT
                ).show()

                // 음악 목록 다시 로드하여 hasEffect 상태 업데이트
                musicViewModel.loadMusic()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        deviceViewModel.initializeWithContext(applicationContext)

        // ✅ 권한 상태 로깅
        PermissionManager.logPermissionStatus(this, "MainActivity")

        // MainActivity.kt의 onCreate 함수 내 Scaffold 부분 수정

        setContent {
            val lightBackground = !isSystemInDarkTheme()
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightStatusBars = lightBackground
            }

            LightStickMusicTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "effect"

                val navigateTo = intent?.getStringExtra("navigateTo")

                LaunchedEffect(navigateTo) {
                    if (navigateTo == "music") {
                        navController.navigate("music") {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    }
                }

                val connectedDeviceCount by deviceViewModel.connectedDeviceCount.collectAsState()

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        // ✅ CustomNavigationBar 사용
                        // musicList 화면에서는 Navigation Bar 숨김
                        if (currentRoute != "musicList" && !currentRoute.startsWith("deviceDetail")) {
                            CustomNavigationBar(
                                modifier = Modifier.navigationBarsPadding(),
                                selectedRoute = currentRoute,
                                onNavigate = { route ->
                                    // ✅ 수정: 백스택 제대로 관리
                                    navController.navigate(route) {
                                        // 시작 화면(effect)까지 모든 화면 제거
                                        popUpTo("effect") {
                                            inclusive = false  // effect는 유지
                                        }

                                        // 같은 화면 중복 방지
                                        launchSingleTop = true
                                    }
                                },
                                connectedDeviceCount = connectedDeviceCount
                            )
                        }
                    }
                ) { padding ->
                    AppNavigation(
                        navController = navController,
                        modifier = Modifier.padding(padding),
                        deviceViewModel = deviceViewModel,
                        musicViewModel = musicViewModel,
                        effectViewModel = effectViewModel,
                        onRequestEffectsDirectory = { requestEffectsDirectory() }
                    )
                }
            }
        }

        // ✅ Permissions
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            PermissionManager.logPermissionStatus(this, "PermissionResult")

            val allGranted = results.values.all { it }
            if (allGranted) {
                deviceViewModel.startScan(this)
            } else {
                Toast.makeText(
                    this,
                    "일부 권한이 거부되었습니다. BLE 기능이 제한될 수 있습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * ✅ Effects 디렉토리 수동 선택 요청
     */
    private fun requestEffectsDirectory() {
        val intent = EffectPathPreferences.createDirectoryPickerIntent()
        directoryPickerLauncher.launch(intent)
    }
}

// MainActivity.kt의 AppNavigation 함수 수정본

@OptIn(UnstableApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    deviceViewModel: DeviceViewModel,
    musicViewModel: MusicViewModel,
    effectViewModel: EffectViewModel,
    onRequestEffectsDirectory: () -> Unit
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = "effect",
        modifier = modifier
    ) {
        // 🎵 MusicControlScreen (메인 음악 화면)
        composable("music") {
            MusicControlScreen(
                viewModel = musicViewModel,
                onNavigateToMusicList = {
                    navController.navigate("musicList")
                },
                onRequestEffectsDirectory = onRequestEffectsDirectory
            )
        }

        // 📋 MusicListScreen (음악 목록 화면)
        composable("musicList") {
            MusicListScreen(
                viewModel = musicViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // 🎨 EffectScreen (효과 제어 화면)
        composable("effect") {
            EffectScreen(
                viewModel = effectViewModel,
                navController = navController
            )
        }

        // 📱 DeviceListScreen (디바이스 목록 화면)
        composable("deviceList") {
            // ✅ 다이얼로그 상태 관리
            var showConnectDialog by remember { mutableStateOf(false) }
            var showReconnectDialog by remember { mutableStateOf(false) }
            var selectedDevice by remember { mutableStateOf<Device?>(null) }

            val connectedDevices by deviceViewModel.connectedDeviceCount.collectAsState()
            val connectionStates by deviceViewModel.connectionStates.collectAsState()

            DeviceListScreen(
                viewModel = deviceViewModel,
                navController = navController,
                onNavigateToDetail = { device ->
                    navController.navigate("deviceDetail/${device.mac}")
                },
                onDeviceSelected = { device: Device ->
                    if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@DeviceListScreen
                    }

                    selectedDevice = device
                    val isConnected = connectionStates[device.mac] == true

                    if (isConnected) {
                        // 이미 연결된 기기 → 바로 연결 해제 (다이얼로그 없음)
                        @SuppressLint("MissingPermission")
                        deviceViewModel.toggleConnection(context, device)
                    } else {
                        // 미연결 기기 → 연결 시도
                        if (connectedDevices > 0) {
                            // 이미 연결된 다른 기기 있음 → ReconnectConfirmDialog
                            showReconnectDialog = true
                        } else {
                            // 연결된 기기 없음 → ConnectConfirmDialog
                            showConnectDialog = true
                        }
                    }
                }
            )

            // ===== 연결 확인 다이얼로그 =====
            if (showConnectDialog && selectedDevice != null) {
                ConnectConfirmDialog(
                    deviceName = selectedDevice!!.name ?: "Unknown Device",
                    onDismiss = {
                        showConnectDialog = false
                        selectedDevice = null
                    },
                    onConfirm = {
                        showConnectDialog = false
                        @SuppressLint("MissingPermission")
                        deviceViewModel.toggleConnection(context, selectedDevice!!)
                        selectedDevice = null
                    }
                )
            }

            // ===== 재연결 확인 다이얼로그 =====
            if (showReconnectDialog && selectedDevice != null) {
                val devices by deviceViewModel.devices.collectAsState()
                val currentConnectedDevice = devices.find { connectionStates[it.mac] == true }

                ReconnectConfirmDialog(
                    currentDeviceName = currentConnectedDevice?.name ?: "Unknown Device",
                    onDismiss = {
                        showReconnectDialog = false
                        selectedDevice = null
                    },
                    onConfirm = {
                        showReconnectDialog = false
                        @SuppressLint("MissingPermission")
                        deviceViewModel.toggleConnection(context, selectedDevice!!)
                        selectedDevice = null
                    }
                )
            }
        }

        // ✅ DeviceDetailScreen (디바이스 상세 화면)
        composable(
            route = "deviceDetail/{deviceMac}",
            arguments = listOf(
                navArgument("deviceMac") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceMac = backStackEntry.arguments?.getString("deviceMac") ?: return@composable

            // ViewModel에서 디바이스 정보 가져오기
            val devices by deviceViewModel.devices.collectAsState()
            val connectionStates by deviceViewModel.connectionStates.collectAsState()
            val deviceDetails by deviceViewModel.deviceDetails.collectAsState()

            val device = devices.find { it.mac == deviceMac }
            val deviceDetail = deviceDetails[deviceMac]

            if (device == null) {
                // 디바이스를 찾을 수 없는 경우 뒤로가기
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                return@composable
            }

            // ✅ 다이얼로그 상태
            var showDisconnectDialog by remember { mutableStateOf(false) }
            var showDeviceInfoDialog by remember { mutableStateOf(false) }
            var showOtaUpdateDialog by remember { mutableStateOf(false) }
            var showFindDialog by remember { mutableStateOf(false) }

            DeviceDetailScreen(
                deviceName = device.name ?: "Unknown Device",
                macAddress = device.mac,
                rssi = device.rssi ?: 0,
                batteryLevel = deviceDetail?.batteryLevel,
                deviceInfo = deviceDetail,
                callEventEnabled = deviceDetail?.callEventEnabled ?: false,
                smsEventEnabled = deviceDetail?.smsEventEnabled ?: false,
                broadcastingEnabled = deviceDetail?.broadcasting ?: false,
                onBackClick = {
                    navController.popBackStack()
                },
                onDisconnectClick = {
                    showDisconnectDialog = true
                },
                onDeviceInfoClick = {
                    showDeviceInfoDialog = true
                },
                onCallEventToggle = { enabled ->
                    deviceViewModel.toggleCallEvent(device, enabled)
                },
                onSmsEventToggle = { enabled ->
                    deviceViewModel.toggleSmsEvent(device, enabled)
                },
                onBroadcastingToggle = { enabled ->
                    deviceViewModel.toggleBroadcasting(device, enabled)
                },
                onFindClick = {
                    showFindDialog = true
                },
                onOtaUpdateClick = {
                    showOtaUpdateDialog = true
                }
            )

            // ===== 다이얼로그들 =====

            // 연결 해제 확인 다이얼로그
            if (showDisconnectDialog) {
                DisconnectConfirmDialog(
                    deviceName = device.name ?: "Unknown Device",
                    onDismiss = { showDisconnectDialog = false },
                    onConfirm = {
                        showDisconnectDialog = false
                        @SuppressLint("MissingPermission")
                        deviceViewModel.toggleConnection(context, device)
                        navController.popBackStack()
                    }
                )
            }

            // 디바이스 정보 다이얼로그
            if (showDeviceInfoDialog) {
                DeviceInfoDialog(
                    model = deviceDetail?.deviceInfo?.modelNumber ?: "Unknown",
                    firmware = deviceDetail?.deviceInfo?.firmwareRevision ?: "Unknown",
                    manufacturer = deviceDetail?.deviceInfo?.manufacturer ?: "Unknown",
                    onDismiss = { showDeviceInfoDialog = false }
                )
            }

            // ✅ FIND 확인 다이얼로그
            if (showFindDialog) {
                FindEffectConfirmDialog(
                    onDismiss = { showFindDialog = false },
                    onConfirm = {
                        showFindDialog = false
                        // TODO: FIND 이펙트 전송
                        Toast.makeText(context, "FIND 이펙트 전송", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // OTA 업데이트 확인 다이얼로그
            if (showOtaUpdateDialog) {
                OtaUpdateConfirmDialog(
                    deviceName = device.name ?: "Unknown Device",
                    newversion = deviceDetail?.deviceInfo?.firmwareRevision ?: "Unknown",
                    onDismiss = { showOtaUpdateDialog = false },
                    onConfirm = {
                        showOtaUpdateDialog = false
                        // TODO: OTA 업데이트 시작
                        Toast.makeText(context, "OTA 업데이트 시작", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}