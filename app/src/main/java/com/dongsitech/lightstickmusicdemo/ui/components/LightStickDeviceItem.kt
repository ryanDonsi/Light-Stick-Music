package com.dongsitech.lightstickmusicdemo.ui.components

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun LightStickDeviceItem(
    device: BluetoothDevice,
    rssi: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Text(text = device.name ?: "Unnamed Device", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Address: ${device.address}", style = MaterialTheme.typography.bodyMedium)
        Text(text = "RSSI: $rssi dBm", style = MaterialTheme.typography.bodySmall)
    }
}
