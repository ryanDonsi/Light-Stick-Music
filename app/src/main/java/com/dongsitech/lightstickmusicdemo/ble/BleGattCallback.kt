package com.dongsitech.lightstickmusicdemo.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission

class BleGattCallback(private val context: Context) : BluetoothGattCallback() {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d("MyGattCallback", "Connected")
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d("MyGattCallback", "Disconnected")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        gatt.services.forEach { service ->
            Log.d("Gatt", "Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                Log.d("Gatt", "↳ Characteristic: ${characteristic.uuid}")
            }
        }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        Log.d("MyGattCallback", "Characteristic read: ${characteristic.uuid}")
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("MyGattCallback", "Characteristic write successful: ${characteristic.uuid}")
        } else {
            Log.e("MyGattCallback", "Characteristic write failed: $status")
        }
    }
}
