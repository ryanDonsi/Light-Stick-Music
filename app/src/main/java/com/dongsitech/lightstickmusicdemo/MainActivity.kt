package com.dongsitech.lightstickmusicdemo

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.dongsitech.lightstickmusicdemo.ui.EffectScreen
import com.dongsitech.lightstickmusicdemo.ui.LightStickListScreen
import com.dongsitech.lightstickmusicdemo.ui.MusicPlayerScreen
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickListViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.permissions.RequestPermissions
import com.dongsitech.lightstickmusicdemo.R
import androidx.media3.common.util.UnstableApi
import com.lightstick.LSBluetooth
import com.lightstick.device.Device

@UnstableApi
class MainActivity : ComponentActivity() {
    private val lightStickListViewModel: LightStickListViewModel by viewModels()
    private val musicPlayerViewModel: MusicPlayerViewModel by viewModels()
    private val effectViewModel: EffectViewModel by viewModels()  // ✅ EffectViewModel 추가

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK init
        LSBluetooth.initialize(applicationContext)
        lightStickListViewModel.initializeWithContext(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            val context = LocalContext.current

            val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

            RequestPermissions(
                permissions = arrayOf(storagePermission),
                onGranted = { /* 권한 허용됨 → UI 표시 */ },
                onDenied = {
                    Toast.makeText(context, "저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                }
            )

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
                    BottomNavItem("effect", "이펙트", R.drawable.ic_lightstick),
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
                        effectViewModel = effectViewModel  // ✅ EffectViewModel 전달
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    lightStickListViewModel: LightStickListViewModel,
    musicPlayerViewModel: MusicPlayerViewModel,
    effectViewModel: EffectViewModel  // ✅ EffectViewModel 파라미터 추가
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = "music",
        modifier = modifier
    ) {
        composable("music") {
            MusicPlayerScreen(viewModel = musicPlayerViewModel)
        }

        composable("effect") {
            // ✅ EffectScreen에 ViewModel 전달
            EffectScreen(viewModel = effectViewModel)
        }

        composable("deviceList") {
            LightStickListScreen(
                viewModel = lightStickListViewModel,
                navController = navController,
                onDeviceSelected = { device: Device ->
                    // 권한 체크
                    val hasPermission = PermissionUtils.hasPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )

                    if (!hasPermission) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@LightStickListScreen
                    }

                    // 주소만 넘겨 토글 (ViewModel은 주소 기반 연결/해제 처리)
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
