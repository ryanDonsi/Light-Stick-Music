package com.lightstick.music.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object PermissionManager {

    /**
     * 주어진 권한이 허용되었는지 확인합니다.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 여러 권한이 모두 허용되었는지 확인합니다.
     */
    fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    // ═══════════════════════════════════════════════════════════
    // 블루투스 권한
    // ═══════════════════════════════════════════════════════════

    /**
     * 블루투스 스캔 권한 확인
     */
    fun hasBluetoothScanPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    }

    /**
     * 블루투스 연결 권한 확인
     */
    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * 모든 블루투스 권한 확인
     */
    fun hasAllBluetoothPermissions(context: Context): Boolean {
        return hasBluetoothScanPermission(context) &&
                hasBluetoothConnectPermission(context)
    }

    /**
     * 필요한 블루투스 권한 목록 반환
     */
    fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

        return permissions.toTypedArray()
    }

    // ═══════════════════════════════════════════════════════════
    // 저장소 권한
    // ═══════════════════════════════════════════════════════════

    /**
     * 저장소 읽기 권한 확인
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 필요한 저장소 권한 목록 반환
     */
    fun getRequiredStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 알림 권한
    // ═══════════════════════════════════════════════════════════

    /**
     * 알림 권한 확인 (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Android 12 이하는 권한 불필요
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 통합 권한 체크
    // ═══════════════════════════════════════════════════════════

    /**
     * 앱에 필요한 모든 권한 확인
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasAllBluetoothPermissions(context) &&
                hasStoragePermission(context) &&
                hasNotificationPermission(context)
    }

    /**
     * 앱에 필요한 모든 권한 목록 반환
     */
    fun getAllRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // 블루투스 권한
        permissions.addAll(getRequiredBluetoothPermissions())

        // 저장소 권한
        permissions.addAll(getRequiredStoragePermissions())

        // 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * 거부된 권한 목록 반환
     */
    fun getDeniedPermissions(context: Context, permissions: Array<String>): List<String> {
        return permissions.filter { !hasPermission(context, it) }
    }

    /**
     * 디버깅: 모든 권한 상태 로깅
     */
    fun logPermissionStatus(context: Context, tag: String = "PermissionUtils") {
        Log.d(tag, "═══════════════════════════════════════")
        Log.d(tag, "Permission Status:")
        Log.d(tag, "  Bluetooth:")
        Log.d(tag, "    ├─ SCAN: ${hasBluetoothScanPermission(context)}")
        Log.d(tag, "    └─ CONNECT: ${hasBluetoothConnectPermission(context)}")
        Log.d(tag, "  Storage:")
        Log.d(tag, "    └─ ${hasStoragePermission(context)}")
        Log.d(tag, "  Notification:")
        Log.d(tag, "    └─ ${hasNotificationPermission(context)}")
        Log.d(tag, "  All Required: ${hasAllRequiredPermissions(context)}")
        Log.d(tag, "═══════════════════════════════════════")
    }
}