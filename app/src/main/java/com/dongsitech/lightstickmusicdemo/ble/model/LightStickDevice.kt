package com.dongsitech.lightstickmusicdemo.ble.model

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

data class LightStickDevice(
    val device: BluetoothDevice,
    var gatt: BluetoothGatt? = null
)
