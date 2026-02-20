package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.core.util.toLightStickColor
import com.lightstick.music.ui.components.effect.PresetColors
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.device.Device
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.usecase.effect.PlayEffectListUseCase
import com.lightstick.music.domain.usecase.effect.PlayManualEffectUseCase
import com.lightstick.music.domain.usecase.effect.StopEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.types.Color as LightStickColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class EffectViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "effect_settings"
        private const val TAG = "EffectViewModel"
        private const val KEY_CUSTOM_EFFECTS = "custom_effects"
        private const val SCAN_DURATION_MS = 3000L
        private const val MAX_CUSTOM_EFFECTS = 7
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // ✅ UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val playManualEffectUseCase = PlayManualEffectUseCase()
    private val playEffectListUseCase = PlayEffectListUseCase()
    private val stopEffectUseCase = StopEffectUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()

    // ═══════════════════════════════════════════════════════════
    // 상태 (State)
    // ═══════════════════════════════════════════════════════════

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _selectedEffectListNumber = MutableStateFlow<Int?>(null)
    val selectedEffectListNumber: StateFlow<Int?> = _selectedEffectListNumber.asStateFlow()

    private val _selectedEffect = MutableStateFlow<UiEffectType?>(null)
    val selectedEffect: StateFlow<UiEffectType?> = _selectedEffect.asStateFlow()

    private val effectSettingsMapInternal = mutableMapOf<String, EffectSettings>()
    private val _effectSettingsMap = MutableStateFlow<Map<String, EffectSettings>>(emptyMap())
    val effectSettingsMap: StateFlow<Map<String, EffectSettings>> = _effectSettingsMap.asStateFlow()

    private val _currentSettings = MutableStateFlow(EffectSettings.defaultFor(UiEffectType.On))
    val currentSettings: StateFlow<EffectSettings> = _currentSettings.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _playingEffect = MutableStateFlow<UiEffectType?>(null)
    val playingEffect: StateFlow<UiEffectType?> = _playingEffect.asStateFlow()

    private val _customEffects = MutableStateFlow<List<UiEffectType.Custom>>(emptyList())
    val customEffects: StateFlow<List<UiEffectType.Custom>> = _customEffects.asStateFlow()

    sealed class DeviceConnectionState {
        data object NoBondedDevice : DeviceConnectionState()
        data object Scanning : DeviceConnectionState()
        data object ScanFailed : DeviceConnectionState()
        data class Connected(val device: Device) : DeviceConnectionState()
    }

    private val _deviceConnectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.NoBondedDevice)
    val deviceConnectionState: StateFlow<DeviceConnectionState> = _deviceConnectionState.asStateFlow()

    private val _fgPresetColors = MutableStateFlow(loadFgPresetColors().toMutableList())
    val fgPresetColors: StateFlow<List<Color>> = _fgPresetColors.asStateFlow()

    private val _bgPresetColors = MutableStateFlow(loadBgPresetColors().toMutableList())
    val bgPresetColors: StateFlow<List<Color>> = _bgPresetColors.asStateFlow()

    private val _selectedFgPreset = MutableStateFlow<Int?>(null)
    val selectedFgPreset: StateFlow<Int?> = _selectedFgPreset.asStateFlow()

    private val _selectedBgPreset = MutableStateFlow<Int?>(null)
    val selectedBgPreset: StateFlow<Int?> = _selectedBgPreset.asStateFlow()

    val latestTransmission: StateFlow<BleTransmissionEvent?> = BleTransmissionMonitor.latestTransmission

    private var effectListJob: Job? = null
    private var scanJob: Job? = null

    init {
        loadCustomEffects()
        loadAllSettings()

        // ✅ 수정: delay 제거, 즉시 실행
        checkExistingConnection()

        // ✅ 연결 상태 관찰 시작
        observeDeviceConnection()
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ Effect 재생/중지 (UseCase 사용)
    // ═══════════════════════════════════════════════════════════

    fun playEffect(context: Context, effectType: UiEffectType) {
        if (!PermissionManager.hasAllBluetoothPermissions(context)) {
            _errorMessage.value = "Bluetooth 권한이 필요합니다"
            return
        }

        viewModelScope.launch {
            try {
                val settings = effectSettingsMapInternal[getEffectKey(effectType)]
                    ?: EffectSettings.defaultFor(effectType)

                // ✅ EffectList 처리
                if (effectType is UiEffectType.EffectList) {
                    val result = playEffectListUseCase(
                        context = context,
                        effectListNumber = effectType.number,
                        coroutineScope = viewModelScope
                    )

                    result.onSuccess { job ->
                        effectListJob?.cancel()
                        effectListJob = job

                        _playingEffect.value = effectType
                        _isPlaying.value = true
                        _errorMessage.value = null

                        Log.d(TAG, "✅ EffectList play started: ${effectType.displayName}")
                    }.onFailure { error ->
                        _errorMessage.value = "EffectList 재생 실패: ${error.message}"
                        Log.e(TAG, "❌ playEffect error: ${error.message}")
                    }

                    return@launch
                }

                // ✅ Manual Effect 처리
                val result = playManualEffectUseCase(
                    context = context,
                    effectType = effectType,
                    settings = settings
                )

                result.onSuccess {
                    _playingEffect.value = effectType
                    _isPlaying.value = true
                    _errorMessage.value = null

                    Log.d(TAG, "✅ Effect sent: ${effectType.displayName}")
                }.onFailure { error ->
                    _errorMessage.value = "Effect 전송 실패: ${error.message}"
                    Log.e(TAG, "❌ playEffect error: ${error.message}")
                }

            } catch (e: Exception) {
                _errorMessage.value = "Effect 전송 실패: ${e.message}"
                Log.e(TAG, "❌ playEffect error: ${e.message}")
            }
        }
    }

    fun stopEffect(context: Context) {
        viewModelScope.launch {
            try {
                // ✅ UseCase 호출
                val result = stopEffectUseCase(
                    context = context,
                    effectListJob = effectListJob
                )

                result.onSuccess {
                    effectListJob = null
                    _playingEffect.value = null
                    _isPlaying.value = false

                    Log.d(TAG, "✅ Effect stopped")
                }.onFailure { error ->
                    Log.e(TAG, "❌ stopEffect error: ${error.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ stopEffect error: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Effect 설정
    // ═══════════════════════════════════════════════════════════

    fun selectEffect(effect: UiEffectType) {
        _selectedEffect.value = effect
        _currentSettings.value = getEffectSettings(effect)
    }

    fun updateColor(context: Context, color: LightStickColor) {
        val current = _selectedEffect.value ?: return
        val settings = _currentSettings.value.copy(color = color)
        _currentSettings.value = settings
        saveEffectSettings(current, settings)
        if (_isPlaying.value) playEffect(context, current)
    }

    fun updateBackgroundColor(context: Context, color: LightStickColor) {
        val current = _selectedEffect.value ?: return
        val settings = _currentSettings.value.copy(backgroundColor = color)
        _currentSettings.value = settings
        saveEffectSettings(current, settings)
        if (_isPlaying.value) playEffect(context, current)
    }

    fun updateSettings(context: Context, newSettings: EffectSettings) {
        val current = _selectedEffect.value ?: return
        _currentSettings.value = newSettings
        saveEffectSettings(current, newSettings)
        if (_isPlaying.value) playEffect(context, current)
    }

    fun saveEffectSettings(effectType: UiEffectType, settings: EffectSettings) {
        val key = getEffectKey(effectType)
        effectSettingsMapInternal[key] = settings
        saveSettings(key, settings)
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    fun getEffectSettings(effectType: UiEffectType): EffectSettings {
        val key = getEffectKey(effectType)
        return effectSettingsMapInternal[key] ?: EffectSettings.defaultFor(effectType)
    }

    fun getEffectKey(effectType: UiEffectType): String {
        return when (effectType) {
            is UiEffectType.On -> "ON"
            is UiEffectType.Off -> "OFF"
            is UiEffectType.Strobe -> "STROBE"
            is UiEffectType.Blink -> "BLINK"
            is UiEffectType.Breath -> "BREATH"
            is UiEffectType.EffectList -> "EFFECT_LIST_${effectType.number}"
            is UiEffectType.Custom -> "CUSTOM_${effectType.id}"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // EffectList 관리
    // ═══════════════════════════════════════════════════════════

    fun selectEffectList(context: Context, effectNumber: Int) {
        if (_selectedEffectListNumber.value == effectNumber) {
            stopEffect(context)
            _selectedEffectListNumber.value = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다."
        } else {
            val effect = UiEffectType.EffectList(effectNumber)
            selectEffect(effect)
            playEffect(context, effect)
            _selectedEffectListNumber.value = effectNumber
            _toastMessage.value = "저장된 리스트를 반복 재생합니다."
        }
    }

    fun clearEffectListSelection(context: Context) {
        if (_selectedEffectListNumber.value != null) {
            _selectedEffect.value = null
            stopEffect(context)
            _selectedEffectListNumber.value = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다."
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Custom Effect CRUD
    // ═══════════════════════════════════════════════════════════

    fun canAddCustomEffect(): Boolean = _customEffects.value.size < MAX_CUSTOM_EFFECTS

    fun addCustomEffect(name: String, baseType: UiEffectType.BaseEffectType) {
        if (!canAddCustomEffect()) {
            _toastMessage.value = "커스텀 이펙트는 최대 ${MAX_CUSTOM_EFFECTS}개까지 추가할 수 있습니다."
            return
        }
        try {
            val custom = UiEffectType.Custom(id = UUID.randomUUID().toString(), baseType = baseType, name = name)
            val current = _customEffects.value.toMutableList()
            current.add(custom)
            _customEffects.value = current
            val key = getEffectKey(custom)
            val settings = EffectSettings.defaultFor(custom)
            effectSettingsMapInternal[key] = settings
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()
            saveSettings(key, settings)
            saveCustomEffects()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add custom effect: ${e.message}")
        }
    }

    fun deleteCustomEffect(custom: UiEffectType.Custom) {
        try {
            val current = _customEffects.value.toMutableList()
            current.remove(custom)
            _customEffects.value = current
            val key = getEffectKey(custom)
            effectSettingsMapInternal.remove(key)
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()
            prefs.edit().apply {
                remove(key)
                apply()
            }
            saveCustomEffects()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete custom effect: ${e.message}")
        }
    }

    fun renameCustomEffect(custom: UiEffectType.Custom, newName: String) {
        try {
            val current = _customEffects.value.toMutableList()
            val index = current.indexOf(custom)
            if (index >= 0) {
                val renamed = custom.copy(name = newName)
                current[index] = renamed
                _customEffects.value = current
                saveCustomEffects()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to rename custom effect: ${e.message}")
        }
    }

    private fun loadCustomEffects() {
        try {
            val json = prefs.getString(KEY_CUSTOM_EFFECTS, null) ?: return
            val jsonArray = JSONArray(json)
            val effects = mutableListOf<UiEffectType.Custom>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val custom = UiEffectType.Custom(
                    id = obj.getString("id"),
                    baseType = UiEffectType.BaseEffectType.valueOf(obj.getString("baseType")),
                    name = obj.getString("name")
                )
                effects.add(custom)
            }
            _customEffects.value = effects
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load custom effects: ${e.message}")
        }
    }

    private fun saveCustomEffects() {
        try {
            val jsonArray = JSONArray()
            _customEffects.value.forEach { custom ->
                val obj = JSONObject().apply {
                    put("id", custom.id)
                    put("baseType", custom.baseType.name)
                    put("name", custom.name)
                }
                jsonArray.put(obj)
            }
            prefs.edit().apply {
                putString(KEY_CUSTOM_EFFECTS, jsonArray.toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save custom effects: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Preset Colors
    // ═══════════════════════════════════════════════════════════

    fun selectFgPreset(index: Int) {
        _selectedFgPreset.value = index
    }

    fun selectBgPreset(index: Int) {
        _selectedBgPreset.value = index
    }

    fun updateFgPresetColor(index: Int, color: Color) {
        val colors = _fgPresetColors.value.toMutableList()
        colors[index] = color
        _fgPresetColors.value = colors
        saveFgPresetColors(colors)
    }

    fun updateBgPresetColor(index: Int, color: Color) {
        val colors = _bgPresetColors.value.toMutableList()
        colors[index] = color
        _bgPresetColors.value = colors
        saveBgPresetColors(colors)
    }

    private fun saveFgPresetColors(colors: List<Color>) {
        colors.forEachIndexed { index, color ->
            prefs.edit().apply {
                putInt("fg_preset_$index", colorToRgb(color.toLightStickColor()))
                apply()
            }
        }
    }

    private fun saveBgPresetColors(colors: List<Color>) {
        colors.forEachIndexed { index, color ->
            prefs.edit().apply {
                putInt("bg_preset_$index", colorToRgb(color.toLightStickColor()))
                apply()
            }
        }
    }

    private fun loadFgPresetColors(): List<Color> {
        return (0..9).map { index ->
            val rgb = prefs.getInt("fg_preset_$index", -1)
            if (rgb != -1) {
                rgbToColor(rgb).toComposeColor()
            } else {
                PresetColors.defaultForegroundPresets[index]
            }
        }
    }

    private fun loadBgPresetColors(): List<Color> {
        return (0..9).map { index ->
            val rgb = prefs.getInt("bg_preset_$index", -1)
            if (rgb != -1) {
                rgbToColor(rgb).toComposeColor()
            } else {
                PresetColors.defaultBackgroundPresets[index]
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Device Connection
    // ═══════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun checkExistingConnection() {
        try {
            if (!PermissionManager.hasBluetoothConnectPermission(getApplication())) {
                Log.w(TAG, "⚠️ No BLUETOOTH_CONNECT permission")
                return
            }

            val connected = LSBluetooth.connectedDevices()
            Log.d(TAG, "📱 Checking existing connections: ${connected.size} devices")

            // ✅ 초기화: 연결된 디바이스 중에 있는지 확인
            if (connected.isNotEmpty()) {
                // 첫 번째 연결된 디바이스 사용 (어떤 디바이스든 OK)
                val firstDevice = connected.first()
                _deviceConnectionState.value = DeviceConnectionState.Connected(firstDevice)
                Log.d(TAG, "✅ Found existing connection: ${firstDevice.mac}")
            } else {
                Log.d(TAG, "⚠️ No existing connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking existing connection: ${e.message}")
        }
    }

    private fun observeDeviceConnection() {
        Log.d(TAG, "🚀 observeDeviceConnection() started")

        viewModelScope.launch {
            LSBluetooth.observeDeviceStates().collect { states ->
                Log.d(TAG, "🔄 Device states changed: ${states.size} devices")

                // ✅ 단순히 연결된 디바이스가 있는지만 확인 (LS 필터링 X)
                val connectedDevice = states
                    .filter { (_, state) ->
                        val isConnected = state.connectionState is ConnectionState.Connected
                        isConnected
                    }
                    .map { (mac, state) ->
                        Device(
                            mac = mac,
                            name = state.deviceInfo?.deviceName,
                            rssi = state.deviceInfo?.rssi
                        )
                    }
                    .firstOrNull()

                Log.d(TAG, "🎯 Connected device: ${connectedDevice?.mac ?: "none"}")

                if (connectedDevice != null) {
                    // 연결된 디바이스 있음
                    val currentState = _deviceConnectionState.value
                    Log.d(TAG, "📊 Current state: ${currentState::class.simpleName}")

                    if (currentState !is DeviceConnectionState.Connected ||
                        (currentState as? DeviceConnectionState.Connected)?.device?.mac != connectedDevice.mac) {

                        _deviceConnectionState.value = DeviceConnectionState.Connected(connectedDevice)
                        scanJob?.cancel()

                        Log.d(TAG, "✅ State updated: Connected(${connectedDevice.mac})")
                    } else {
                        Log.d(TAG, "⏭️ Already in correct state")
                    }
                } else {
                    // 연결된 디바이스 없음
                    if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                        _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                        scanJob?.cancel()

                        Log.d(TAG, "⚠️ State updated: NoBondedDevice")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAutoScan(context: Context) {
        if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
            Log.d(TAG, "✅ Already connected, skipping auto scan")
            return
        }

        if (!PermissionManager.hasAllBluetoothPermissions(context)) {
            _errorMessage.value = "Bluetooth 권한이 필요합니다"
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                val bondedDevices = LSBluetooth.bondedDevices()
                if (bondedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "❌ No bonded devices")
                    return@launch
                }

                Log.d(TAG, "✅ Found ${bondedDevices.size} bonded devices")

                _deviceConnectionState.value = DeviceConnectionState.Scanning

                val scannedDevices = mutableMapOf<String, Device>()

                LSBluetooth.startScan { device ->
                    if (bondedDevices.any { it.mac == device.mac }) {
                        scannedDevices[device.mac] = device
                        Log.d(TAG, "📡 Scanned: ${device.mac} RSSI=${device.rssi}")
                    }
                }

                delay(SCAN_DURATION_MS)

                LSBluetooth.stopScan()

                val bestDevice = scannedDevices.values
                    .filter { it.rssi != null }
                    .maxByOrNull { it.rssi!! }

                if (bestDevice == null) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.d(TAG, "❌ No devices found during scan")
                    return@launch
                }

                Log.d(TAG, "🎯 Best device found: ${bestDevice.mac} RSSI=${bestDevice.rssi}")

                // ✅ 수정: connectToDevice() 호출
                connectToDevice(context, bestDevice)

            } catch (e: Exception) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                Log.e(TAG, "❌ Auto scan error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(context: Context, device: Device) {
        device.connect(
            onConnected = {
                // ✅ 즉시 상태 업데이트
                _deviceConnectionState.value = DeviceConnectionState.Connected(device)
                Log.d(TAG, "✅ Auto connected: ${device.mac}")
                Log.d(TAG, "✅ State updated to: Connected(${device.mac})")

                // ✅ UseCase 사용: 연결 애니메이션
                viewModelScope.launch {
                    val result = sendConnectionEffectUseCase(context, device)
                    result.onFailure { error ->
                        Log.e(TAG, "❌ Connection animation failed: ${error.message}")
                    }
                }
            },
            onFailed = { error ->
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                _errorMessage.value = "연결 실패: ${error.message}"
                Log.e(TAG, "❌ Connection failed: ${error.message}")
            }
        )
    }

    fun retryAutoScan(context: Context) {
        startAutoScan(context)
    }

    // ═══════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun loadAllSettings() {
        val allKeys = prefs.all.keys.filter { it != KEY_CUSTOM_EFFECTS && !it.startsWith("fg_preset_") && !it.startsWith("bg_preset_") }
        allKeys.forEach { key ->
            try {
                val json = prefs.getString(key, null) ?: return@forEach
                val settings = EffectSettings.fromJson(json)
                effectSettingsMapInternal[key] = settings
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings for $key: ${e.message}")
            }
        }
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    private fun saveSettings(key: String, settings: EffectSettings) {
        prefs.edit().apply {
            putString(key, settings.toJson())
            apply()
        }
    }

    private fun colorToRgb(color: LightStickColor): Int {
        return (color.r shl 16) or (color.g shl 8) or color.b
    }

    private fun rgbToColor(rgb: Int): LightStickColor {
        return LightStickColor(
            r = (rgb shr 16) and 0xFF,
            g = (rgb shr 8) and 0xFF,
            b = rgb and 0xFF
        )
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        effectListJob?.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    // Data Classes
    // ═══════════════════════════════════════════════════════════

    data class EffectSettings(
        val color: LightStickColor,
        val backgroundColor: LightStickColor,
        val period: Int,
        val transit: Int,
        val randomColor: Boolean,
        val randomDelay: Int,
        val broadcasting: Boolean
    ) {
        companion object {
            fun defaultFor(effectType: UiEffectType): EffectSettings {
                return when (effectType) {
                    is UiEffectType.On -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 10,
                        transit = 10,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = false
                    )
                    is UiEffectType.Off -> EffectSettings(
                        color = Colors.BLACK,
                        backgroundColor = Colors.BLACK,
                        period = 10,
                        transit = 10,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = false
                    )
                    is UiEffectType.Strobe, is UiEffectType.Blink, is UiEffectType.Breath -> EffectSettings(
                        color = Colors.RED,
                        backgroundColor = Colors.BLACK,
                        period = 10,
                        transit = 10,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = false
                    )
                    is UiEffectType.EffectList -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 10,
                        transit = 10,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = false
                    )
                    is UiEffectType.Custom -> {
                        when (effectType.baseType) {
                            UiEffectType.BaseEffectType.ON -> defaultFor(UiEffectType.On)
                            UiEffectType.BaseEffectType.OFF -> defaultFor(UiEffectType.Off)
                            UiEffectType.BaseEffectType.STROBE -> defaultFor(UiEffectType.Strobe)
                            UiEffectType.BaseEffectType.BLINK -> defaultFor(UiEffectType.Blink)
                            UiEffectType.BaseEffectType.BREATH -> defaultFor(UiEffectType.Breath)
                        }
                    }
                }
            }

            fun fromJson(json: String): EffectSettings {
                val obj = JSONObject(json)
                return EffectSettings(
                    color = LightStickColor(
                        r = obj.getInt("color_r"),
                        g = obj.getInt("color_g"),
                        b = obj.getInt("color_b")
                    ),
                    backgroundColor = LightStickColor(
                        r = obj.getInt("backgroundColor_r"),
                        g = obj.getInt("backgroundColor_g"),
                        b = obj.getInt("backgroundColor_b")
                    ),
                    period = obj.getInt("period"),
                    transit = obj.getInt("transit"),
                    randomColor = obj.getBoolean("randomColor"),
                    randomDelay = obj.getInt("randomDelay"),
                    broadcasting = obj.getBoolean("broadcasting")
                )
            }
        }

        fun toJson(): String {
            val obj = JSONObject()
            obj.put("color_r", color.r)
            obj.put("color_g", color.g)
            obj.put("color_b", color.b)
            obj.put("backgroundColor_r", backgroundColor.r)
            obj.put("backgroundColor_g", backgroundColor.g)
            obj.put("backgroundColor_b", backgroundColor.b)
            obj.put("period", period)
            obj.put("transit", transit)
            obj.put("randomColor", randomColor)
            obj.put("randomDelay", randomDelay)
            obj.put("broadcasting", broadcasting)
            return obj.toString()
        }
    }

    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On : UiEffectType("ON", "LED를 선택한 색상으로 켭니다")
        data object Off : UiEffectType("OFF", "LED를 끕니다")
        data object Strobe : UiEffectType("STROBE", "플래시 터지는 효과 (period: 0~255)")
        data object Blink : UiEffectType("BLINK", "깜빡이는 효과 (period: 0~255)")
        data object Breath : UiEffectType("BREATH", "숨쉬듯 밝아졌다 어두워지는 효과 (period: 0~255)")

        data class EffectList(val number: Int, val subName: String = "") :
            UiEffectType("EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}", "내장 이펙트 리스트 재생")

        data class Custom(
            val id: String,
            val baseType: BaseEffectType,
            val name: String
        ) : UiEffectType(name, getDescriptionForBase(baseType))

        enum class BaseEffectType(val displayName: String) {
            ON("ON"), OFF("OFF"), STROBE("STROBE"), BLINK("BLINK"), BREATH("BREATH")
        }

        companion object {
            private fun getDescriptionForBase(baseType: BaseEffectType): String {
                return when (baseType) {
                    BaseEffectType.ON -> "커스텀 ON 효과"
                    BaseEffectType.OFF -> "커스텀 OFF 효과"
                    BaseEffectType.STROBE -> "커스텀 STROBE 효과"
                    BaseEffectType.BLINK -> "커스텀 BLINK 효과"
                    BaseEffectType.BREATH -> "커스텀 BREATH 효과"
                }
            }
        }
    }
}