package com.dongsitech.lightstickmusicdemo.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.*

class BleManager(private val context: Context) {
    private var bluetoothGatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, callback: BluetoothGattCallback) {
        bluetoothGatt = device.connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    fun writeCharacteristic(uuidService: UUID, uuidChar: UUID, value: ByteArray): Boolean {
        val service = bluetoothGatt?.getService(uuidService)
        val characteristic = service?.getCharacteristic(uuidChar)

        if (characteristic == null) {
            Log.e("BleManager", "Characteristic not found")
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = value
                bluetoothGatt?.writeCharacteristic(characteristic) ?: false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        Log.d("BleManager", "Disconnected and GATT closed.")
    }
}
