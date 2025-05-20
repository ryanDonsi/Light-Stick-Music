package com.dongsitech.lightstickmusicdemo.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.dongsitech.lightstickmusicdemo.util.UuidConstants
import java.lang.ref.WeakReference
import java.util.*

object BleGattManager {

    private val gattMap: MutableMap<String, BluetoothGatt> = mutableMapOf()
    private var contextRef: WeakReference<Context>? = null

    fun initialize(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(device: BluetoothDevice) {
        val context = contextRef?.get() ?: return
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return

        if (!gattMap.containsKey(device.address)) {
            try {
                val gatt = device.connectGatt(context, false, BleGattCallback(context))
                gattMap[device.address] = gatt
            } catch (e: SecurityException) {
                Log.e("BleGattManager", "권한 오류로 BLE 연결 실패: ${e.message}")
            } catch (e: Exception) {
                Log.e("BleGattManager", "BLE 연결 예외: ${e.message}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectDevice(address: String) {
        val context = contextRef?.get() ?: return
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return

        try {
            gattMap[address]?.let {
                it.disconnect()
                it.close()
            }
        } catch (e: Exception) {
            Log.e("BleGattManager", "BLE 연결 해제 중 예외: ${e.message}")
        } finally {
            gattMap.remove(address)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendLedColorToAll(red: Byte, green: Byte, blue: Byte, transit: Byte) {
        val context = contextRef?.get() ?: return
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) return

        for ((_, gatt) in gattMap) {
            try {
                sendLedColor(gatt, red, green, blue, transit)
            } catch (e: SecurityException) {
                Log.e("BleGattManager", "권한 오류로 LED 전송 실패: ${e.message}")
            } catch (e: Exception) {
                Log.e("BleGattManager", "LED 전송 중 예외: ${e.message}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendLedColor(gatt: BluetoothGatt, red: Byte, green: Byte, blue: Byte, transit: Byte) {
        val service = gatt.getService(UuidConstants.LED_CONTROL_SERVICE) ?: return
        val characteristic = service.getCharacteristic(UuidConstants.LED_CONTROL_CHAR) ?: return

        val value = byteArrayOf(red, green, blue, transit)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // API 33 이상: 새로운 writeCharacteristic 방식 사용
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            // API 32 이하: 기존 방식 유지
            characteristic.setValue(value) // deprecated이지만 호환성 위해 사용
            gatt.writeCharacteristic(characteristic)
        }
    }


    fun isConnected(address: String): Boolean {
        return gattMap.containsKey(address)
    }

    fun getConnectedAddresses(): List<String> {
        return gattMap.keys.toList()
    }
}
