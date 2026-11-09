package com.lightstick.music.ui.activity

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import android.provider.DocumentsContract
import android.os.Bundle
import android.provider.Settings
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.SideEffect
import com.lightstick.music.core.manager.DeviceStatusNotificationManager
import com.lightstick.music.ui.components.splash.NotificationListenerGuideDialog
import com.lightstick.music.domain.music.MusicEffectManager
import com.lightstick.music.ui.screen.effect.EffectScreen
import com.lightstick.music.ui.screen.device.DeviceListScreen
import com.lightstick.music.ui.theme.LightStickMusicTheme
import com.lightstick.music.core.util.SafHelper
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.data.local.preferences.DevicePreferences
import com.lightstick.music.ui.viewmodel.EffectViewModel
import com.lightstick.music.ui.viewmodel.DeviceViewModel
import com.lightstick.music.ui.viewmodel.MusicViewModel
import com.lightstick.music.ui.viewmodel.GameViewModel
import com.lightstick.music.core.permission.PermissionManager
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavType
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.navArgument
import com.lightstick.music.ui.screen.music.MusicControlScreen
import com.lightstick.music.ui.screen.music.MusicListScreen
import com.lightstick.device.Device
import com.lightstick.music.ui.components.common.CustomNavigationBar
import com.lightstick.music.ui.layout.applyDeviceOrientation
import com.lightstick.music.ui.layout.isTabletLandscape
import com.lightstick.music.ui.screen.tablet.TabletDetailPlaceholder
import com.lightstick.music.ui.screen.tablet.TabletDeviceSettingsTitleBar
import com.lightstick.music.ui.screen.tablet.TabletFullScreen
import com.lightstick.music.ui.screen.tablet.TabletMainScreen
import com.lightstick.music.ui.screen.tablet.TabletPopupFrame
import com.lightstick.music.ui.components.common.CustomToast
import com.lightstick.music.ui.components.common.ToastState
import com.lightstick.music.ui.components.common.rememberToastState
import com.lightstick.music.ui.components.device.ConnectConfirmDialog
import com.lightstick.music.ui.components.device.DeviceInfoDialog
import com.lightstick.music.ui.components.device.DisconnectConfirmDialog
import com.lightstick.music.ui.components.device.EffectFolderGuideDialog
import com.lightstick.music.ui.components.device.FindEffectConfirmDialog
import com.lightstick.music.ui.components.device.GroupAssignDialog
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

    /** setContent{} 안에서 rememberToastState()로 채워짐. Compose 밖(런처 콜백)에서 토스트를 띄우기 위한 참조. */
    private var toastState: ToastState? = null

    /**
     *  SAF를 통한 Effects 디렉토리 선택 (수동 선택용)
     */
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        SafHelper.logPickerResult(result.resultCode, result.data?.data)
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                EffectPathPreferences.saveDirectoryUri(this, uri)

                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        MusicEffectManager.initializeFromSAF(this@MainActivity)
                    }

                    toastState?.show("Effects 폴더 설정 완료 (${MusicEffectManager.getLoadedEffectCount()}개 파일)")

                    musicViewModel.loadMusic()
                }
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
        applyDeviceOrientation()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        DeviceStatusNotificationManager.start(this)
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

                var showEffectFolderGuide by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (EffectPathPreferences.getSavedDirectoryUri(this@MainActivity) == null) {
                        showEffectFolderGuide = true
                    }
                }

                if (showEffectFolderGuide) {
                    EffectFolderGuideDialog(
                        onDismiss = { showEffectFolderGuide = false },
                        onConfirm = {
                            showEffectFolderGuide = false
                            requestEffectsDirectory()
                        }
                    )
                }

                var showNotificationListenerGuide by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            showNotificationListenerGuide =
                                !PermissionManager.hasNotificationListenerPermission(this@MainActivity)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (showNotificationListenerGuide) {
                    NotificationListenerGuideDialog(
                        onDismiss = { showNotificationListenerGuide = false },
                        onGoToSettings = {
                            showNotificationListenerGuide = false
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                }

                val navigateTo = intent?.getStringExtra("navigateTo")

                LaunchedEffect(navigateTo) {
                    if (navigateTo == "music") {
                        navController.navigate("music") {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    }
                }

                val otaInProgressMap by deviceViewModel.otaInProgress.collectAsState()
                val isAnyOtaInProgress = otaInProgressMap.values.any { it }

                val toast = rememberToastState()
                toastState = toast
                val tablet = isTabletLandscape()

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0),
                        bottomBar = {
                            if (!tablet && currentRoute != "musicList" && !currentRoute.startsWith("deviceDetail")) {
                                CustomNavigationBar(
                                    modifier = Modifier.navigationBarsPadding(),
                                    selectedRoute = currentRoute,
                                    onNavigate = { route ->
                                        if (!isAnyOtaInProgress) {
                                            navController.navigate(route) {
                                                popUpTo("effect") {
                                                    inclusive = false
                                                }
                                                launchSingleTop = true
                                            }
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
                            onRequestEffectsDirectory = { requestEffectsDirectory() },
                            onShowToast = { message -> toast.show(message) }
                        )
                    }

                    CustomToast(
                        message   = toast.message,
                        isVisible = toast.isVisible,
                        onDismiss = { toast.dismiss() },
                        modifier  = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        val deniedPermissions = PermissionManager.getDeniedPermissions(
            this, PermissionManager.getAllRequiredPermissions()
        )

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            PermissionManager.logPermissionStatus(this, "PermissionResult")

            if (PermissionManager.hasStoragePermission(this)) {
                musicViewModel.loadMusic()
            }

            val allGranted = results.values.all { it }
            if (!allGranted) {
                toastState?.show("일부 권한이 거부되었습니다. BLE 기능이 제한될 수 있습니다.")
            }
        }

        if (deniedPermissions.isNotEmpty()) {
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }

    /**
     *  Effects 디렉토리 수동 선택 요청
     */
    private fun requestEffectsDirectory() {
        val intent = EffectPathPreferences.createDirectoryPickerIntent()
        directoryPickerLauncher.launch(intent)
    }
}

/**
 * 디바이스 목록(Device Setting) 화면 본문 — 폰의 화면 이동 경로와 태블릿 팝업 양쪽에서 같이 쓴다.
 *
 * @param tablet 태블릿 스타일(타이틀 "디바이스 설정" 왼쪽 정렬)
 * @param onOpenDetail 지정하면 기기 상세를 화면 이동 대신 이 콜백으로 연다 (태블릿: 오른쪽 상세 영역에 표시)
 * @param onBack 지정하면 타이틀 왼쪽에 돌아가기 버튼을 보여준다 (태블릿: 메인 화면으로)
 */
@Composable
private fun DeviceListPane(
    navController: NavHostController,
    deviceViewModel: DeviceViewModel,
    onRequestEffectsDirectory: () -> Unit,
    onShowToast: (String) -> Unit,
    tablet: Boolean,
    onOpenDetail: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    showTopBar: Boolean = true,
    folderDialogRequest: Int = 0
) {
    val context = LocalContext.current
    var showConnectDialog by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }

    val connectedDevices by deviceViewModel.connectedDeviceCount.collectAsState()
    val connectionStates by deviceViewModel.connectionStates.collectAsState()

    DeviceListScreen(
        viewModel = deviceViewModel,
        navController = navController,
        onNavigateToDetail = { device ->
            if (onOpenDetail != null) onOpenDetail(device.mac)
            else navController.navigate("deviceDetail/${device.mac}")
        },
        onRequestEffectsDirectory = onRequestEffectsDirectory,
        tabletStyle = tablet,
        onBack = onBack,
        showTopBar = showTopBar,
        folderDialogRequest = folderDialogRequest,
        onDeviceSelected = { device: Device ->
            if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                onShowToast("BLUETOOTH_CONNECT 권한 없음")
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

/** 디바이스 상세 화면 본문 — 폰의 화면 이동 경로와 태블릿의 옆 패널 양쪽에서 같이 쓴다. */
@OptIn(UnstableApi::class)
@Composable
private fun DeviceDetailPane(
    deviceMac: String,
    navController: NavHostController,
    deviceViewModel: DeviceViewModel,
    /** 상세 화면을 닫는 동작 — 폰: 이전 화면으로, 태블릿: 옆 패널만 닫기 */
    onClose: () -> Unit = { navController.popBackStack() },
    showTopBar: Boolean = true
) {
    val context = LocalContext.current

    // 상세화면에 머무는 동안 스캔을 멈춰 RSSI 갱신에 따른 불필요한 DIS 재처리를 방지
    DisposableEffect(Unit) {
        deviceViewModel.stopScan()
        onDispose {
            deviceViewModel.startScan(context)
        }
    }

    val devices by deviceViewModel.devices.collectAsState()
    val connectionStates by deviceViewModel.connectionStates.collectAsState()
    val deviceDetails by deviceViewModel.deviceDetails.collectAsState()

    val device = devices.find { it.mac == deviceMac }
    val deviceDetail = deviceDetails[deviceMac]

    if (device == null) {
        LaunchedEffect(Unit) {
            onClose()
        }
        return
    }

    val isConnected = connectionStates[deviceMac] == true
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            onClose()
        }
    }

    val otaVersionCheck by deviceViewModel.otaVersionCheck.collectAsState()

    // TODO: 최종 구현 시 서버 API로 최신 버전 조회 + 다운로드 URL 수신으로 대체
    val otaFilePicker = rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
                    )
                }
            }
        }
    ) { uri ->
        uri?.let { deviceViewModel.checkFirmwareVersion(context, device, it) }
    }

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showDeviceInfoDialog by remember { mutableStateOf(false) }
    var showOtaUpdateDialog by remember { mutableStateOf(false) }
    var showOtaLatestDialog by remember { mutableStateOf(false) }
    var showFindDialog by remember { mutableStateOf(false) }
    var showGroupAssignDialog by remember { mutableStateOf(false) }

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
    val isGroupEffectAllowed by deviceViewModel.isGroupEffectAllowed.collectAsState()
    val isOtaInProgress  = otaInProgressMap[deviceMac] == true
    val otaProgress      = otaProgressMap[deviceMac] ?: 0

    BackHandler(enabled = isOtaInProgress) { /* OTA 진행 중 뒤로가기 차단 */ }

    DeviceDetailScreen(
        deviceName = device.name ?: "Unknown Device",
        macAddress = device.mac,
        rssi = device.rssi ?: 0,
        batteryLevel = deviceDetail?.batteryLevel,
        deviceInfo = deviceDetail,
        callEventEnabled = callEventEnabled,
        smsEventEnabled = smsEventEnabled,
        broadcastingEnabled = broadcastingEnabled,
        broadcastingSupported = deviceDetail?.supportsBroadcasting == true,
        isOtaInProgress = isOtaInProgress,
        otaProgress = otaProgress,
        showTopBar = showTopBar,
        onBackClick = {
            if (!isOtaInProgress) onClose()
        },
        onDisconnectClick = {
            showDisconnectDialog = true
        },
        onDeviceInfoClick = {
            deviceViewModel.refreshDeviceInfo(deviceMac)
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
        isGroupEffectAllowed = isGroupEffectAllowed,
        onGroupAssignClick = {
            showGroupAssignDialog = true
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

    if (showGroupAssignDialog) {
        val groupCount by deviceViewModel.groupCount.collectAsState()
        GroupAssignDialog(
            groupCount = groupCount,
            onGroupCountChange = { deviceViewModel.setGroupCount(it) },
            onDismiss = { showGroupAssignDialog = false },
            onConfirm = { groupId ->
                deviceViewModel.sendGroupSetting(device, groupId)
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

/**
 * 태블릿에서는 메인 화면 위에 겹치는 팝업(맨 오른쪽 패널 자리의 카드)으로, 폰에서는 일반 화면으로 등록한다.
 * 내용은 같은 Composable을 쓰므로 폰과 동일하게 구성된다.
 *
 * 팝업은 별도 창(Dialog)이 아니라 같은 창 안의 겹침이라, 팝업이 떠 있는 동안에도 상단 타이틀의
 * 게임·설정 아이콘 등이 바로 눌린다.
 */
private fun NavGraphBuilder.popupOrScreen(
    route: String,
    popup: Boolean,
    onDismiss: () -> Unit,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit
) {
    composable(route = route, arguments = arguments) { entry ->
        if (popup) {
            TabletPopupFrame(onDismiss = onDismiss) { content(entry) }
        } else {
            content(entry)
        }
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
    onRequestEffectsDirectory: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val tablet = isTabletLandscape()

    // 팝업 바깥 클릭으로 닫기 — 펌웨어 업데이트(OTA) 중에는 닫지 않는다
    val otaInProgressMap by deviceViewModel.otaInProgress.collectAsState()
    val isAnyOtaInProgress = otaInProgressMap.values.any { it }
    val dismissPopup: () -> Unit = {
        if (!isAnyOtaInProgress) navController.popBackStack()
    }

    val navHost: @Composable (Modifier) -> Unit = { navModifier ->
    NavHost(
        navController = navController,
        startDestination = "effect",
        modifier = navModifier,
        // 기본 전환(페이드 700ms)을 폰에서는 그대로 두고, 태블릿 팝업은 자체 펼침 애니메이션을 쓰므로 끈다
        enterTransition = if (tablet) ({ EnterTransition.None }) else ({ fadeIn(animationSpec = tween(700)) }),
        exitTransition = if (tablet) ({ ExitTransition.None }) else ({ fadeOut(animationSpec = tween(700)) }),
        popEnterTransition = if (tablet) ({ EnterTransition.None }) else ({ fadeIn(animationSpec = tween(700)) }),
        popExitTransition = if (tablet) ({ ExitTransition.None }) else ({ fadeOut(animationSpec = tween(700)) })
    ) {
        composable("music") {
            if (tablet) {
                // 태블릿 메인 화면은 NavHost 밖(아래층)에 항상 깔려 있다 — 여기는 비워 둔다
            } else {
                MusicControlScreen(
                    viewModel = musicViewModel,
                    onNavigateToMusicList = {
                        navController.navigate("musicList")
                    }
                )
            }
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
            if (tablet) {
                // 태블릿 메인 화면은 NavHost 밖(아래층)에 항상 깔려 있다 — 팝업이 없을 때는 비워 둔다
            } else {
                EffectScreen(
                    viewModel = effectViewModel,
                    navController = navController
                )
            }
        }

        popupOrScreen("game", popup = tablet, onDismiss = dismissPopup) {
            GameScreen(
                viewModel = gameViewModel,
                // 태블릿 팝업은 타이틀 없이 내용만 보여준다 (닫기: 팝업 바깥 또는 게임 아이콘 다시 누르기)
                showTitleBar = !tablet
            )
        }

        composable("deviceList") {
            if (tablet) {
                // 태블릿: 메인 화면을 덮는 전체 화면. 왼쪽 기기 목록 / 오른쪽 상세(상세 보기를 누르면 표시).
                // 어느 기기의 상세인지는 이 화면의 상태로 들고 있어서 목록의 스크롤·대화상자가 유지된다.
                var detailMac by rememberSaveable { mutableStateOf<String?>(null) }
                var folderRequest by remember { mutableIntStateOf(0) }
                TabletFullScreen {
                    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                        // 전체 폭 타이틀 바 하나: [←] 디바이스 설정 ……… [⋮]  (목록·상세는 각자의 타이틀 바를 그리지 않는다)
                        TabletDeviceSettingsTitleBar(
                            // 돌아가기 → 메인 화면. 펌웨어 업데이트 중에는 이동하지 않는다
                            onBack = { if (!isAnyOtaInProgress) navController.popBackStack() },
                            onEffectFolderSetting = { folderRequest++ }
                        )
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                                DeviceListPane(
                                    navController = navController,
                                    deviceViewModel = deviceViewModel,
                                    onRequestEffectsDirectory = onRequestEffectsDirectory,
                                    onShowToast = onShowToast,
                                    tablet = true,
                                    onOpenDetail = { detailMac = it },
                                    showTopBar = false,
                                    folderDialogRequest = folderRequest
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.customColors.divider)
                            )
                            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                                val openedMac = detailMac
                                if (openedMac != null) {
                                    DeviceDetailPane(
                                        deviceMac = openedMac,
                                        navController = navController,
                                        deviceViewModel = deviceViewModel,
                                        onClose = { detailMac = null },
                                        showTopBar = false
                                    )
                                } else {
                                    TabletDetailPlaceholder("기기의 상세 보기를 누르면 이곳에 표시됩니다")
                                }
                            }
                        }
                    }
                }
            } else {
                DeviceListPane(
                    navController = navController,
                    deviceViewModel = deviceViewModel,
                    onRequestEffectsDirectory = onRequestEffectsDirectory,
                    onShowToast = onShowToast,
                    tablet = false
                )
            }
        }

        // 폰 전용: 기기 상세는 별도 화면으로 이동한다 (태블릿은 위 목록 카드 옆 패널을 쓴다)
        composable(
            route = "deviceDetail/{deviceMac}",
            arguments = listOf(
                navArgument("deviceMac") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceMac = backStackEntry.arguments?.getString("deviceMac") ?: return@composable
            DeviceDetailPane(deviceMac, navController, deviceViewModel)
        }
    }
    }

    if (tablet) {
        // 아래층: 3단 메인 화면(항상 표시) / 위층: 팝업이 있을 때만 그려지는 NavHost
        Box(modifier = modifier) {
            TabletMainScreen(
                navController, effectViewModel, musicViewModel, onShowToast,
                navigationLocked = isAnyOtaInProgress
            )
            navHost(Modifier.fillMaxSize())
        }
    } else {
        navHost(modifier)
    }
}
