package com.lightstick.music.ui.activity

import android.content.Intent
import android.os.Bundle
import com.lightstick.music.core.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.permission.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.ui.screen.splash.SplashScreen
import com.lightstick.music.ui.theme.LightStickMusicTheme
import com.lightstick.music.ui.viewmodel.SplashViewModel

@AndroidEntryPoint
@UnstableApi
class SplashActivity : ComponentActivity() {

    private val viewModel: SplashViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }

        PermissionManager.logPermissionStatus(this, "SplashActivity")

        if (allGranted) {
            viewModel.onPermissionAllowed()
            initializeStartApp()
        } else {
            val deniedPermissions = results.filter { !it.value }.keys
            Toast.makeText(
                this,
                "필요한 권한이 거부되었습니다: ${deniedPermissions.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
            viewModel.onPermissionDenied()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            val splashState by viewModel.splashState.collectAsState()

            BackHandler(enabled = splashState is SplashState.ShowPermissionGuide) {
                finish()
            }

            LightStickMusicTheme {
                Surface(
                    modifier = Modifier.Companion.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SplashScreen(
                        splashState = splashState,
                        onLogoTimeout = {
                            checkPermissionsAndProceed()
                        },
                        onPermissionGuideConfirmed = {
                            viewModel.onPermissionGuideConfirmed()
                            requestAllPermissions()
                        },
                        onInitializationComplete = {
                            viewModel.saveInitializationResult()
                            startMainActivity()
                        }
                    )
                }
            }
        }
    }

    /**
     * 권한 체크 후 진행 방향 결정
     * - 권한 있음 → 바로 초기화 시작
     * - 권한 없음 → 권한 안내 다이얼로그 표시
     */
    private fun checkPermissionsAndProceed() {
        val requiredPermissions = PermissionManager.getAllRequiredPermissions()
        val deniedPermissions = PermissionManager.getDeniedPermissions(this, requiredPermissions)

        if (deniedPermissions.isEmpty()) {
            viewModel.onPermissionAllowed()
            initializeStartApp()
        } else {
            viewModel.onLogoTimeout()
        }
    }

    /**
     * 필요한 모든 권한 요청
     */
    private fun requestAllPermissions() {
        PermissionManager.logPermissionStatus(this, "SplashActivity")

        val requiredPermissions = PermissionManager.getAllRequiredPermissions()

        val deniedPermissions = PermissionManager.getDeniedPermissions(this, requiredPermissions)

        if (deniedPermissions.isEmpty()) {
            viewModel.onPermissionAllowed()
            initializeStartApp()
        } else {
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }

    /**
     *  SDK 초기화 후 앱 초기화 시작
     */
    private fun initializeStartApp() {
        try {

            viewModel.startInitialization()

        } catch (e: Exception) {
            Log.e("SplashActivity", "Failed to initialize SDK", e)
            Toast.makeText(
                this,
                "SDK 초기화 실패: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    /**
     * MainActivity로 이동
     */
    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
