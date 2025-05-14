package com.dongsitech.lightstickmusicdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.dongsitech.lightstickmusicdemo.ui.DeviceListScreen
import com.dongsitech.lightstickmusicdemo.ui.LightStickScreen
import com.dongsitech.lightstickmusicdemo.ui.MusicPlayerScreen
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.viewmodel.DeviceListViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    private val deviceListViewModel: DeviceListViewModel by viewModels()
    private val lightStickViewModel: LightStickViewModel by viewModels()
    private val musicPlayerViewModel: MusicPlayerViewModel by viewModels()

    @androidx.annotation.RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermissionIfNeeded()

        setContent {
            LightStickMusicPlayerDemoTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "music"

                val bottomNavItems = listOf(
                    BottomNavItem("music", "음악", Icons.Default.MusicNote),
                    BottomNavItem("deviceList", "디바이스", Icons.Default.Devices)
                )

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
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    AppNavigation(
                        navController = navController,
                        modifier = Modifier.padding(padding),
                        deviceListViewModel = deviceListViewModel,
                        lightStickViewModel = lightStickViewModel,
                        musicPlayerViewModel = musicPlayerViewModel
                    )
                }
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                Manifest.permission.READ_MEDIA_AUDIO
            }
            else -> {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        }
    }
}

@Composable
@androidx.annotation.RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    deviceListViewModel: DeviceListViewModel,
    lightStickViewModel: LightStickViewModel,
    musicPlayerViewModel: MusicPlayerViewModel
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

        composable("deviceList") {
            DeviceListScreen(
                viewModel = deviceListViewModel,
                navController = navController,
                onDeviceSelected = { device ->
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasPermission) {
                        try {
                            lightStickViewModel.connectToDevice(device, context)

                            val deviceWrapper = DeviceWrapper(
                                name = device.name ?: "Unnamed",
                                address = device.address
                            )
                            val deviceJson = Json.encodeToString(deviceWrapper)
                            val encodedDeviceJson = Base64.encodeToString(
                                deviceJson.toByteArray(),
                                Base64.URL_SAFE or Base64.NO_WRAP
                            )

                            navController.navigate("lightStick/$encodedDeviceJson")
                        } catch (e: Exception) {
                            Toast.makeText(context, "블루투스 연결 중 오류 발생", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        composable(
            route = "lightStick/{device}",
            arguments = listOf(navArgument("device") { type = NavType.StringType }),
            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) }
        ) { backStackEntry ->
            val encodedJson = backStackEntry.arguments?.getString("device") ?: ""
            val deviceWrapper = try {
                val json = String(Base64.decode(encodedJson, Base64.URL_SAFE or Base64.NO_WRAP))
                Json.decodeFromString<DeviceWrapper>(json)
            } catch (e: Exception) {
                null
            }

            if (deviceWrapper != null) {
                LightStickScreen(
                    viewModel = lightStickViewModel,
                    deviceName = deviceWrapper.name,
                    deviceAddress = deviceWrapper.address,
                    navController = navController
                )
            } else {
                Toast.makeText(context, "기기 정보를 불러오는 데 실패했습니다.", Toast.LENGTH_LONG).show()
                navController.popBackStack()
            }
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@kotlinx.serialization.Serializable
data class DeviceWrapper(
    val name: String,
    val address: String
)
