package com.dongsitech.lightstickmusicdemo.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {

    /**
     * 주어진 권한이 허용되었는지 확인합니다.
     *
     * @param context 호출 컨텍스트
     * @param permission 확인할 권한 문자열 (예: Manifest.permission.BLUETOOTH_CONNECT)
     * @return 권한이 허용되었으면 true, 아니면 false
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 여러 권한이 모두 허용되었는지 확인합니다.
     *
     * @param context 호출 컨텍스트
     * @param permissions 확인할 권한 목록
     * @return 모든 권한이 허용되었으면 true
     */
    fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }
}
