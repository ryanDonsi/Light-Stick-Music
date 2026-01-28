package com.lightstick.music.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.getValue  // ✅ 필수
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.data.model.SplashState
import com.lightstick.music.ui.screen.splash.SplashScreen
import com.lightstick.music.ui.theme.LightStickMusicTheme
import com.lightstick.music.ui.viewmodel.SplashViewModel

@UnstableApi
class SplashActivity : ComponentActivity() {

    private val viewModel: SplashViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }

        // ✅ 권한 상태 로깅
        PermissionManager.logPermissionStatus(this, "SplashActivity")

        if (allGranted) {
            // ✅ 권한 획득 성공 → ViewModel에 알림 → SDK 초기화 → 앱 초기화 시작
            viewModel.onPermissionAllowed()
            initializeStartApp()
        } else {
            // 거부된 권한 확인
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
        // ✅ Splash Screen 설치 (super.onCreate() 전에 호출)
        installSplashScreen()

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContent {
            val splashState by viewModel.splashState.collectAsState()

            // ✅ 권한 안내 다이얼로그에서 백키 누르면 앱 종료
            BackHandler(enabled = splashState is SplashState.ShowPermissionGuide) {
                finish()  // 앱 종료
            }

            LightStickMusicTheme {
                Surface(
                    modifier = Modifier.Companion.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SplashScreen(
                        splashState = splashState,
                        onLogoTimeout = {
                            // 로고 표시 완료 → 권한 체크
                            checkPermissionsAndProceed()
                        },
                        onPermissionGuideConfirmed = {
                            // 권한 안내 다이얼로그에서 "확인" → 시스템 권한 요청
                            viewModel.onPermissionGuideConfirmed()
                            requestAllPermissions()
                        },
                        onInitializationComplete = {
                            // 초기화 완료 → MainActivity로 이동
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
        // ⭐ 테스트용: 항상 다이얼로그 표시
        // TODO: 최종 릴리즈 전에 아래 주석을 해제하고 테스트 코드 삭제
        viewModel.onLogoTimeout()

        /* 원래 코드 (최종 릴리즈 시 사용)
        val requiredPermissions = PermissionManager.getAllRequiredPermissions()
        val deniedPermissions = PermissionManager.getDeniedPermissions(this, requiredPermissions)

        if (deniedPermissions.isEmpty()) {
            // 모든 권한이 이미 허용됨 → 바로 초기화 시작
            viewModel.onPermissionAllowed()
            initializeSdkAndStartApp()
        } else {
            // 권한 없음 → 권한 안내 다이얼로그 표시
            viewModel.onLogoTimeout()
        }
        */
    }

    /**
     * 필요한 모든 권한 요청
     */
    private fun requestAllPermissions() {
        // 현재 권한 상태 로깅
        PermissionManager.logPermissionStatus(this, "SplashActivity")

        // 필요한 모든 권한
        val requiredPermissions = PermissionManager.getAllRequiredPermissions()

        // 거부된 권한만 필터링
        val deniedPermissions = PermissionManager.getDeniedPermissions(this, requiredPermissions)

        if (deniedPermissions.isEmpty()) {
            // 모든 권한이 이미 허용됨
            viewModel.onPermissionAllowed()
            initializeStartApp()
        } else {
            // 거부된 권한 요청
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }

    /**
     * ✅ SDK 초기화 후 앱 초기화 시작
     */
    private fun initializeStartApp() {
        try {
            // ✅ 권한 확보 후 SDK 초기화
//            LSBluetooth.initialize(applicationContext)
//            Log.d("SplashActivity", "✅ LSBluetooth initialized successfully")

            // 앱 초기화 시작
            viewModel.startInitialization()

        } catch (e: Exception) {
            Log.e("SplashActivity", "❌ Failed to initialize SDK", e)
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