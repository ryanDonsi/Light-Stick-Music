package com.lightstick.music.app

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
import com.lightstick.music.ui.screen.music.MusicControlScreen
import com.lightstick.music.ui.screen.music.MusicListScreen
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.music.ui.components.common.CustomNavigationBar

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

        // SDK init
        LSBluetooth.initialize(applicationContext)
        deviceViewModel.initializeWithContext(applicationContext)

        // ✅ 권한 상태 로깅
        PermissionManager.logPermissionStatus(this, "MainActivity")

        setContent {
            val lightBackground = !isSystemInDarkTheme()
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightStatusBars = lightBackground
            }

            LightStickMusicTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "music"

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
                        if (currentRoute != "musicList") {
                            CustomNavigationBar(
                                modifier = Modifier.navigationBarsPadding(),
                                selectedRoute = currentRoute,
                                onNavigate = { route ->
                                    if (!currentRoute.startsWith(route)) {
                                        navController.navigate(route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
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
            DeviceListScreen(
                viewModel = deviceViewModel,
                navController = navController,
                onDeviceSelected = { device: Device ->
                    if (!PermissionManager.hasBluetoothConnectPermission(context)) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@DeviceListScreen
                    }

                    @SuppressLint("MissingPermission")
                    deviceViewModel.toggleConnection(context, device)
                }
            )
        }
    }
}