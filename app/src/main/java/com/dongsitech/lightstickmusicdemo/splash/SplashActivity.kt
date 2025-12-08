package com.dongsitech.lightstickmusicdemo.splash

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.dongsitech.lightstickmusicdemo.MainActivity
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.ui.theme.LightStickMusicPlayerDemoTheme
import com.lightstick.LSBluetooth

@UnstableApi
class SplashActivity : ComponentActivity() {

    private val viewModel: InitializationViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }

        // ✅ 권한 상태 로깅
        PermissionUtils.logPermissionStatus(this, "SplashActivity")

        if (allGranted) {
            // ✅ 권한 획득 성공 → SDK 초기화 → 앱 초기화 시작
            initializeSdkAndStartApp()
        } else {
            // 거부된 권한 확인
            val deniedPermissions = results.filter { !it.value }.keys
            Toast.makeText(
                this,
                "필요한 권한이 거부되었습니다: ${deniedPermissions.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            val state by viewModel.state.collectAsState()

            LightStickMusicPlayerDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IntroScreen(
                        state = state,
                        onComplete = {
                            // 초기화 완료 → MainActivity로 이동
                            viewModel.saveInitializationResult()
                            startMainActivity()
                        }
                    )
                }
            }
        }

        // ✅ 권한 요청 먼저 (SDK 초기화는 권한 획득 후)
        requestAllPermissions()
    }

    /**
     * ✅ 리팩토링: PermissionUtils 사용
     */
    private fun requestAllPermissions() {
        // 현재 권한 상태 로깅
        PermissionUtils.logPermissionStatus(this, "SplashActivity")

        // 필요한 모든 권한
        val requiredPermissions = PermissionUtils.getAllRequiredPermissions()

        // 거부된 권한만 필터링
        val deniedPermissions = PermissionUtils.getDeniedPermissions(this, requiredPermissions)

        if (deniedPermissions.isEmpty()) {
            // ✅ 모든 권한 이미 승인됨 → SDK 초기화 → 앱 초기화
            initializeSdkAndStartApp()
        } else {
            // 거부된 권한 요청
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }

    /**
     * ✅ SDK 초기화 후 앱 초기화 시작
     */
    private fun initializeSdkAndStartApp() {
        try {
            // ✅ 권한 확보 후 SDK 초기화
            LSBluetooth.initialize(applicationContext)
            android.util.Log.d("SplashActivity", "✅ LSBluetooth initialized successfully")

            // 앱 초기화 시작
            viewModel.startInitialization()

        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "❌ Failed to initialize SDK", e)
            Toast.makeText(
                this,
                "SDK 초기화 실패: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}