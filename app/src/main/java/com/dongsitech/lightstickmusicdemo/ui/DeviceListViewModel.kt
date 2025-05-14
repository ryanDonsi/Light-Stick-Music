package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceListViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<Pair<BluetoothDevice, Int>>>(emptyList())
    val devices: StateFlow<List<Pair<BluetoothDevice, Int>>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    fun setPermissionGranted(granted: Boolean) {
        _permissionGranted.value = granted
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(context: Context) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("DeviceListViewModel", "BLUETOOTH_SCAN permission not granted")
            return
        }

        if (_isScanning.value) {
            Log.d("DeviceListViewModel", "Scan already in progress")
            return
        }

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        if (bluetoothAdapter.isEnabled) {
            _isScanning.value = true
            _devices.value = emptyList()

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    result?.device?.let { device ->
                        val rssi = result.rssi
                        val hasConnectPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                        val name = if (hasConnectPermission) device.name else null
                        if (name?.endsWith("LS") == true) {
                            if (!_devices.value.any { it.first == device }) {
                                _devices.value = _devices.value + Pair(device, rssi)
                                Log.d("DeviceListViewModel", "Added device: $name, RSSI: $rssi")
                            } else {
                                _devices.value = _devices.value.map {
                                    if (it.first == device) Pair(device, rssi) else it
                                }
                                Log.d("DeviceListViewModel", "Updated device: $name, RSSI: $rssi")
                            }
                        } else {
                            Log.d("DeviceListViewModel", "Skipped device: $name (does not end with LS)")
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    Log.e("DeviceListViewModel", "Scan failed with error code: $errorCode")
                    _isScanning.value = false
                    _isRefreshing.value = false
                }
            }

            bluetoothLeScanner?.startScan(scanCallback)
            viewModelScope.launch {
                delay(10000)
                stopScan()
            }
        } else {
            Log.e("DeviceListViewModel", "Bluetooth is disabled")
            _isScanning.value = false
            _isRefreshing.value = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (_isScanning.value) {
            bluetoothLeScanner?.stopScan(object : ScanCallback() {})
            _isScanning.value = false
            _isRefreshing.value = false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun refreshScan(context: Context) {
        viewModelScope.launch {
            _isRefreshing.value = true
            stopScan()
            startScan(context)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}