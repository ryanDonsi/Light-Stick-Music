package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.ble.BleGattManager
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

class LightStickListViewModel : ViewModel() {

    private val _devices = MutableStateFlow<List<Pair<BluetoothDevice, Int>>>(emptyList())
    val devices: StateFlow<List<Pair<BluetoothDevice, Int>>> = _devices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private val _connectionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val connectionStates: StateFlow<Map<String, Boolean>> = _connectionStates

    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: return
            if (!name.endsWith("LS")) return

            _devices.value = _devices.value
                .filterNot { it.first.address == device.address } + (device to result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("LightStickVM", "Scan failed: $errorCode")
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(context: Context) {
        if (!PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)) return
        if (_isScanning.value) return

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e("LightStickVM", "Bluetooth not supported or not enabled")
            return
        }

        scanner = adapter.bluetoothLeScanner ?: return
        _devices.value = emptyList()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)

        // Stop scan after 1 seconds
        viewModelScope.launch {
            delay(1000)
            stopScan()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun refreshScan(context: Context) {
        _isRefreshing.value = true
        startScan(context)
        viewModelScope.launch {
            delay(5000)
            _isRefreshing.value = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun toggleConnection(device: BluetoothDevice) {
        val address = device.address
        val connected = BleGattManager.isConnected(address)
        if (connected) {
            BleGattManager.disconnectDevice(address)
        } else {
            BleGattManager.connectToDevice(device)
        }

        _connectionStates.value = _connectionStates.value.toMutableMap().apply {
            this[address] = !connected
        }
    }
}
