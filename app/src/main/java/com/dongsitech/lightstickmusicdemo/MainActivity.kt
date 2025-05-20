package com.dongsitech.lightstickmusicdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.dongsitech.lightstickmusicdemo.ui.LightStickListScreen
import com.dongsitech.lightstickmusicdemo.ui.MusicPlayerScreen
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickListViewModel
import com.dongsitech.lightstickmusicdemo.viewmodel.MusicPlayerViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.ble.BleGattManager

@UnstableApi
class MainActivity : ComponentActivity() {
    private val lightStickListViewModel: LightStickListViewModel by viewModels()
    private val musicPlayerViewModel: MusicPlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermissionIfNeeded()

        BleGattManager.initialize(applicationContext)

        setContent {
            LightStickMusicPlayerDemoTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "music"

                val bottomNavItems = listOf(
                    BottomNavItem("music", "음악", Icons.Default.MusicNote),
                    BottomNavItem("deviceList", "응원방", Icons.Default.Devices)
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
                        lightStickListViewModel = lightStickListViewModel,
                        musicPlayerViewModel = musicPlayerViewModel
                    )
                }
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    lightStickListViewModel: LightStickListViewModel,
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
            LightStickListScreen(
                viewModel = lightStickListViewModel,
                navController = navController,
                onDeviceSelected = { device ->
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        Toast.makeText(context, "BLUETOOTH_CONNECT 권한 없음", Toast.LENGTH_LONG).show()
                        return@LightStickListScreen
                    }

                    val isConnected = BleGattManager.isConnected(device.address)
                    if (isConnected) {
                        BleGattManager.disconnectDevice(device.address)
                        Toast.makeText(context, "연결 해제됨", Toast.LENGTH_SHORT).show()
                    } else {
                        BleGattManager.connectToDevice(device)
                        Toast.makeText(context, "연결됨", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)
