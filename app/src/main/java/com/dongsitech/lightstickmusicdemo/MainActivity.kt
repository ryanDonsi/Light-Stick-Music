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
import androidx.compose.runtime.SideEffect
import com.dongsitech.lightstickmusicdemo.effect.MusicEffectManager
import com.dongsitech.lightstickmusicdemo.ui.EffectScreen
import com.dongsitech.lightstickmusicdemo.ui.LightStickListScreen
import com.dongsitech.lightstickmusicdemo.ui.MusicPlayerScreen
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.util.EffectDirectoryManager
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickListViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.R
import androidx.media3.common.util.UnstableApi
import com.lightstick.LSBluetooth
import com.lightstick.device.Device

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

        // SDK init
        LSBluetooth.initialize(applicationContext)
        lightStickListViewModel.initializeWithContext(applicationContext)

        // ✅ 권한 상태 로깅
        PermissionUtils.logPermissionStatus(this, "MainActivity")

        WindowCompat.setDecorFitsSystemWindows(window, true)

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

                val bottomNavItems = listOf(
                    BottomNavItem("music", "음악", R.drawable.ic_music_note),
                    BottomNavItem("effect", "이펙트", R.drawable.ic_bolt),
                    BottomNavItem("deviceList", "응원봉", R.drawable.ic_lightstick)
                )

                val connectedDeviceCount by lightStickListViewModel.connectedDeviceCount.collectAsState()

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            bottomNavItems.forEach { item ->
                                NavigationBarItem(
                                    selected = currentRoute.startsWith(item.route),
                                    onClick = {
                                        if (!currentRoute.startsWith(item.route)) {
                                            navController.navigate(item.route) {
                                                popUpTo(navController.graph.startDestinationId) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        if (item.route == "deviceList" && connectedDeviceCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge {
                                                        Text("$connectedDeviceCount")
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = item.iconRes),
                                                    contentDescription = item.label
                                                )
                                            }
                                        } else {
                                            Icon(
                                                painter = painterResource(id = item.iconRes),
                                                contentDescription = item.label
                                            )
                                        }
                                    },
                                    label = { Text(item.label) }
                                )
                            }
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
        startDestination = "music",
        modifier = modifier
    ) {
        composable("music") {
            MusicPlayerScreen(
                viewModel = musicPlayerViewModel,
                onRequestEffectsDirectory = onRequestEffectsDirectory
            )
        }

        composable("effect") {
            EffectScreen(viewModel = effectViewModel)
        }

        composable("deviceList") {
            LightStickListScreen(
                viewModel = lightStickListViewModel,
                navController = navController,
                onDeviceSelected = { device: Device ->
                    // ✅ PermissionUtils 사용
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

data class BottomNavItem(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int
)