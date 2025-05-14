package com.dongsitech.lightstickmusicdemo.permissions

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun PermissionRequester(
    permissions: Array<String>,
    onAllGranted: () -> Unit,
    onDenied: () -> Unit
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            onAllGranted()
        } else {
            Toast.makeText(context, "필수 권한이 모두 허용되지 않았습니다.", Toast.LENGTH_SHORT).show()
            onDenied()
        }
    }

    LaunchedEffect(Unit) {
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            onAllGranted()
        } else {
            permissionLauncher.launch(permissions)
        }
    }
}
