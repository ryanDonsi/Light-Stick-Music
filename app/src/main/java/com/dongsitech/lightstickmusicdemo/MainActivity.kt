package com.dongsitech.lightstickmusicdemo

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.SideEffect
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.ui.EffectScreen
import com.dongsitech.lightstickmusicdemo.ui.LightStickListScreen
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickListViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.R
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.ui.MusicControlScreen
import com.dongsitech.lightstickmusicdemo.ui.MusicListScreen
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.dongsitech.lightstickmusicdemo.ui.components.common.CustomNavigationBar  // ← 추가

@UnstableApi
class MainActivity : ComponentActivity() {
    private val lightStickListViewModel: LightStickListViewModel by viewModels()
    private val musicPlayerViewModel: MusicPlayerViewModel by viewModels()
    private val effectViewModel: EffectViewModel by viewModels()

    /**
     * ✅ SAF를 통한 Effects 디렉토리 선택 (수동 선택용)
     */
    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                EffectDirectoryManager.saveDirectoryUri(this, uri)

                // 재초기화
                MusicEffectManager.initializeFromSAF(this)

                Toast.makeText(
                    this,
                    "Effects 폴더 설정 완료 (${MusicEffectManager.getLoadedEffectCount()}개 파일)",
                    Toast.LENGTH_SHORT
                ).show()

                // 음악 목록 다시 로드하여 hasEffect 상태 업데이트
                musicPlayerViewModel.loadMusic()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // SDK init
        LSBluetooth.initialize(applicationContext)
        lightStickListViewModel.initializeWithContext(applicationContext)

        // ✅ 권한 상태 로깅
        PermissionUtils.logPermissionStatus(this, "MainActivity")

        setContent {
            val lightBackground = !isSystemInDarkTheme()
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView)
                    .isAppearanceLightStatusBars = lightBackground
            }

            LightStickMusicPlayerDemoTheme {
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

                val connectedDeviceCount by lightStickListViewModel.connectedDeviceCount.collectAsState()

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
                        lightStickListViewModel = lightStickListViewModel,
                        musicPlayerViewModel = musicPlayerViewModel,
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
            PermissionUtils.logPermissionStatus(this, "PermissionResult")

            val allGranted = results.values.all { it }
            if (allGranted) {
                lightStickListViewModel.startScan(this)  // ✅ 수정
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
        val intent = EffectDirectoryManager.createDirectoryPickerIntent()
        directoryPickerLauncher.launch(intent)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    lightStickListViewModel: LightStickListViewModel,
    musicPlayerViewModel: MusicPlayerViewModel,
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
                viewModel = musicPlayerViewModel,
                onNavigateToMusicList = {
                    navController.navigate("musicList")
                },
                onRequestEffectsDirectory = onRequestEffectsDirectory
            )
        }

// 📋 MusicListScreen (음악 목록 화면)
        composable("musicList") {
            MusicListScreen(
                viewModel = musicPlayerViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("effect") {
            EffectScreen(
                viewModel = effectViewModel,
                onNavigateToDeviceList = {
                    navController.navigate("deviceList")
                }
            )
        }

        composable("deviceList") {
            LightStickListScreen(
                viewModel = lightStickListViewModel,
                navController = navController,
                onDeviceSelected = { device: Device ->
                    if (!PermissionUtils.hasBluetoothConnectPermission(context)) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@LightStickListScreen
                    }

                    @SuppressLint("MissingPermission")
                    lightStickListViewModel.toggleConnection(context, device)
                }
            )
        }
    }
}