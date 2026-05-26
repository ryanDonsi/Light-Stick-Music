package com.lightstick.music.core.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lightstick.music.core.service.EventNotificationListenerService
import com.lightstick.music.core.util.Log

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
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 알림 권한 확인 (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

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

        permissions.addAll(getRequiredBluetoothPermissions())

        permissions.addAll(getRequiredStoragePermissions())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * 메시지 알림 접근 권한 확인
     * 설정 > 앱 > 특별 앱 접근 > 알림 접근에서 사용자가 직접 허용해야 함
     */
    fun hasNotificationListenerPermission(context: Context): Boolean {
        val cn = ComponentName(context, EventNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(cn.flattenToString())
    }

    /**
     * 선택적 권한 목록 반환
     * 거부되어도 앱 핵심 기능은 동작하지만 해당 이벤트 기능은 비활성화
     */
    fun getOptionalPermissions(): Array<String> = emptyArray()

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
        Log.d(tag, "[권한 상태]")
        Log.d(tag, "  BLUETOOTH_SCAN     : ${hasBluetoothScanPermission(context)}")
        Log.d(tag, "  BLUETOOTH_CONNECT  : ${hasBluetoothConnectPermission(context)}")
        Log.d(tag, "  STORAGE            : ${hasStoragePermission(context)}")
        Log.d(tag, "  NOTIFICATION       : ${hasNotificationPermission(context)}")
        Log.d(tag, "  NOTIFICATION_LISTENER  : ${hasNotificationListenerPermission(context)}")
    }
}
