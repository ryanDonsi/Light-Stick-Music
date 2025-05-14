package com.dongsitech.lightstickmusicdemo.viewmodel

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import com.dongsitech.lightstickmusicdemo.ble.BleManager
import com.dongsitech.lightstickmusicdemo.ble.MyGattCallback
import com.dongsitech.lightstickmusicdemo.util.UuidConstants

class LightStickViewModel : ViewModel() {
    private lateinit var bleManager: BleManager

    fun connectToDevice(device: BluetoothDevice, context: Context) {
        bleManager = BleManager(context)
        bleManager.connect(device, MyGattCallback(context))
    }

    fun sendLedColor(r: Int, g: Int, b: Int, w: Int) {
        val data = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), w.toByte())
        bleManager.writeCharacteristic(UuidConstants.LED_CONTROL_SERVICE, UuidConstants.LED_CONTROL_CHAR, data)
    }

    fun changeMode(mode: Byte) {
        bleManager.writeCharacteristic(UuidConstants.LED_CONTROL_SERVICE, UuidConstants.MODE_CHANGE_CHAR, byteArrayOf(mode))
    }

    fun disconnectToDevice() {
        if (::bleManager.isInitialized) {
            bleManager.disconnect()
        }
    }
}