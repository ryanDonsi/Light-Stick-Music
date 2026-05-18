package com.lightstick.music.ui.activity

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.ui.screen.effect.EffectScreen
import com.lightstick.music.ui.screen.device.DeviceListScreen
import com.lightstick.music.ui.theme.LightStickMusicTheme
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.music.ui.viewmodel.MusicViewModel
import com.lightstick.music.ui.viewmodel.GameViewModel
import com.lightstick.music.core.permission.PermissionManager
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import dagger.hilt.android.AndroidEntryPoint
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
import com.lightstick.music.ui.components.device.OtaVersionInfoDialog
import com.lightstick.music.ui.components.device.ReconnectConfirmDialog
import com.lightstick.music.ui.screen.device.DeviceDetailScreen
import com.lightstick.music.ui.screen.game.GameScreen

@AndroidEntryPoint
@UnstableApi
class MainActivity : ComponentActivity() {
    private val deviceViewModel: DeviceViewModel by viewModels()
    private val musicViewModel: MusicViewModel by viewModels()
    private val effectViewModel: EffectViewModel by viewModels()
    private val gameViewModel: GameViewModel by viewModels()

    /**
     *  SAF를 통한 Effects 디렉토리 선택 (수동 선택용)
     */
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                EffectPathPreferences.saveDirectoryUri(this, uri)

                MusicEffectManager.initializeFromSAF(this)

                Toast.makeText(
                    this,
                    "Effects 폴더 설정 완료 (${MusicEffectManager.getLoadedEffectCount()}개 파일)",
                    Toast.LENGTH_SHORT
                ).show()

                musicViewModel.loadMusic()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasStoragePermission(this)) {
            musicViewModel.loadMusic()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        PermissionManager.logPermissionStatus(this, "MainActivity")

        deviceViewModel

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

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (currentRoute != "musicList" && !currentRoute.startsWith("deviceDetail")) {
                            CustomNavigationBar(
                                modifier = Modifier.navigationBarsPadding(),
                                selectedRoute = currentRoute,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        popUpTo("effect") {
                                            inclusive = false
                                        }

                                        launchSingleTop = true
                                    }
                                }
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
                        gameViewModel = gameViewModel,
                        onRequestEffectsDirectory = { requestEffectsDirectory() }
                    )
                }
            }
        }

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
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            PermissionManager.logPermissionStatus(this, "PermissionResult")

            val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                results[Manifest.permission.READ_MEDIA_AUDIO] == true
            } else {
                results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            }
            if (storageGranted) {
                musicViewModel.loadMusic()
            }

            val allGranted = results.values.all { it }
            if (!allGranted) {
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
     *  Effects 디렉토리 수동 선택 요청
     */
    private fun requestEffectsDirectory() {
        val intent = EffectPathPreferences.createDirectoryPickerIntent()
        directoryPickerLauncher.launch(intent)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    deviceViewModel: DeviceViewModel,
    musicViewModel: MusicViewModel,
    effectViewModel: EffectViewModel,
    gameViewModel: GameViewModel,
    onRequestEffectsDirectory: () -> Unit
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = "effect",
        modifier = modifier
    ) {
        composable("music") {
            MusicControlScreen(
                viewModel = musicViewModel,
                onNavigateToMusicList = {
                    navController.navigate("musicList")
                }
            )
        }

        composable("musicList") {
            MusicListScreen(
                viewModel = musicViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("effect") {
            EffectScreen(
                viewModel = effectViewModel,
                navController = navController
            )
        }

        composable("game") {
            GameScreen(viewModel = gameViewModel)
        }

        composable("deviceList") {
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
                onRequestEffectsDirectory = onRequestEffectsDirectory,
                onDeviceSelected = { device: Device ->
                    if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@DeviceListScreen
                    }

                    selectedDevice = device
                    val isConnected = connectionStates[device.mac] == true

                    if (isConnected) {
                        @SuppressLint("MissingPermission")
                        deviceViewModel.toggleConnection(context, device)
                    } else {
                        if (connectedDevices > 0) {
                            showReconnectDialog = true
                        } else {
                            showConnectDialog = true
                        }
                    }
                }
            )

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

        composable(
            route = "deviceDetail/{deviceMac}",
            arguments = listOf(
                navArgument("deviceMac") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceMac = backStackEntry.arguments?.getString("deviceMac") ?: return@composable

            val devices by deviceViewModel.devices.collectAsState()
            val connectionStates by deviceViewModel.connectionStates.collectAsState()
            val deviceDetails by deviceViewModel.deviceDetails.collectAsState()

            val device = devices.find { it.mac == deviceMac }
            val deviceDetail = deviceDetails[deviceMac]

            if (device == null) {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                return@composable
            }

            val isConnected = connectionStates[deviceMac] == true
            LaunchedEffect(isConnected) {
                if (!isConnected) {
                    navController.popBackStack()
                }
            }

            val otaVersionCheck by deviceViewModel.otaVersionCheck.collectAsState()

            // TODO: 최종 구현 시 서버 API로 최신 버전 조회 + 다운로드 URL 수신으로 대체
            val otaFilePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let { deviceViewModel.checkFirmwareVersion(context, device, it) }
            }

            var showDisconnectDialog by remember { mutableStateOf(false) }
            var showDeviceInfoDialog by remember { mutableStateOf(false) }
            var showOtaUpdateDialog by remember { mutableStateOf(false) }
            var showOtaLatestDialog by remember { mutableStateOf(false) }
            var showFindDialog by remember { mutableStateOf(false) }

            var otaDialogCurrentVersion by remember { mutableStateOf("") }
            var otaDialogNewVersion by remember { mutableStateOf("") }

            LaunchedEffect(otaVersionCheck) {
                otaVersionCheck?.let { check ->
                    otaDialogCurrentVersion = check.deviceVersion
                    otaDialogNewVersion     = check.fileVersion
                    if (check.isUpdateAvailable) {
                        showOtaUpdateDialog = true
                    } else {
                        showOtaLatestDialog = true
                    }
                    deviceViewModel.clearOtaVersionCheck()
                }
            }

            val callEventEnabled = deviceDetail?.callEventEnabled
                ?: DevicePreferences.getCallEventEnabled(deviceMac)
            val smsEventEnabled = deviceDetail?.smsEventEnabled
                ?: DevicePreferences.getSmsEventEnabled(deviceMac)
            val broadcastingEnabled = deviceDetail?.broadcasting
                ?: DevicePreferences.getBroadcasting(deviceMac)

            val otaInProgressMap by deviceViewModel.otaInProgress.collectAsState()
            val otaProgressMap   by deviceViewModel.otaProgress.collectAsState()
            val isOtaInProgress  = otaInProgressMap[deviceMac] == true
            val otaProgress      = otaProgressMap[deviceMac] ?: 0

            DeviceDetailScreen(
                deviceName = device.name ?: "Unknown Device",
                macAddress = device.mac,
                rssi = device.rssi ?: 0,
                batteryLevel = deviceDetail?.batteryLevel,
                deviceInfo = deviceDetail,
                callEventEnabled = callEventEnabled,
                smsEventEnabled = smsEventEnabled,
                broadcastingEnabled = broadcastingEnabled,
                isOtaInProgress = isOtaInProgress,
                otaProgress = otaProgress,
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
                    otaFilePicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onAbortOta = {
                    deviceViewModel.abortOta(device)
                }
            )

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

            if (showDeviceInfoDialog) {
                DeviceInfoDialog(
                    name = deviceDetail?.deviceInfo?.modelName ?: "Unknown",
                    model = deviceDetail?.deviceInfo?.modelNumber ?: "Unknown",
                    firmware = deviceDetail?.deviceInfo?.firmwareRevision ?: "Unknown",
                    manufacturer = deviceDetail?.deviceInfo?.manufacturer ?: "Unknown",
                    onDismiss = { showDeviceInfoDialog = false }
                )
            }

            if (showFindDialog) {
                FindEffectConfirmDialog(
                    onDismiss = { showFindDialog = false },
                    onConfirm = {
                        showFindDialog = false
                        deviceViewModel.sendFindEffect(device)
                    }
                )
            }

            // TODO: 최종 구현 — 서버 API에서 버전 + 다운로드 URL 조회, 확인 시 파일 다운로드 후 startPendingOta()
            if (showOtaUpdateDialog) {
                OtaUpdateConfirmDialog(
                    deviceName     = device.name ?: "Unknown Device",
                    currentVersion = otaDialogCurrentVersion,
                    newVersion     = otaDialogNewVersion,
                    onDismiss = {
                        showOtaUpdateDialog = false
                        deviceViewModel.clearOtaVersionCheck()
                    },
                    onConfirm = {
                        showOtaUpdateDialog = false
                        deviceViewModel.startPendingOta(device)
                    }
                )
            }

            if (showOtaLatestDialog) {
                OtaVersionInfoDialog(
                    deviceName = device.name ?: "Unknown Device",
                    version    = deviceDetail?.deviceInfo?.firmwareRevision ?: "Unknown",
                    onDismiss  = { showOtaLatestDialog = false }
                )
            }
        }
    }
}
