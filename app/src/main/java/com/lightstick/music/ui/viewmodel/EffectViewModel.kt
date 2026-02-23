package com.lightstick.music.ui.viewmodel

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
import com.lightstick.device.Device
import com.lightstick.music.domain.ble.BleTransmissionEvent
import com.lightstick.music.domain.ble.BleTransmissionMonitor
import com.lightstick.music.domain.usecase.effect.PlayEffectListUseCase
import com.lightstick.music.domain.usecase.effect.PlayManualEffectUseCase
import com.lightstick.music.domain.usecase.effect.StopEffectUseCase
import com.lightstick.music.domain.usecase.device.SendConnectionEffectUseCase
import com.lightstick.music.domain.usecase.device.GetBondedDevicesUseCase
import com.lightstick.music.domain.usecase.device.StartScanUseCase
import com.lightstick.music.domain.usecase.device.StopScanUseCase
import com.lightstick.music.domain.usecase.device.ConnectDeviceUseCase
import com.lightstick.music.domain.device.ObserveDeviceStatesUseCase
import com.lightstick.types.Color as LightStickColor
import com.lightstick.types.Colors
import kotlinx.coroutines.Job
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
    private val getBondedDevicesUseCase = GetBondedDevicesUseCase()
    private val startScanUseCase = StartScanUseCase()
    private val stopScanUseCase = StopScanUseCase()
    private val connectDeviceUseCase = ConnectDeviceUseCase()

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
        observeDeviceConnection()
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ Step 5: observeDeviceConnection() - UseCase 사용
    // ═══════════════════════════════════════════════════════════

    private fun observeDeviceConnection() {
        Log.d(TAG, "🚀 observeDeviceConnection() started")

        viewModelScope.launch {
            // ✅ UseCase 사용
            ObserveDeviceStatesUseCase.observeFirstConnectedDevice(preferLsDevice = false).collect { connectedDevice ->
                Log.d(TAG, "🎯 Connected device: ${connectedDevice?.mac ?: "none"}")

                if (connectedDevice != null) {
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
                    if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                        _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                        scanJob?.cancel()

                        Log.d(TAG, "⚠️ State updated: NoBondedDevice")
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ Step 5: startAutoScan() - UseCase 조합으로 완전 재작성
    // ═══════════════════════════════════════════════════════════

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
                // ✅ 1단계: Bonded 디바이스 조회 (UseCase)
                val bondedResult = getBondedDevicesUseCase(context)

                bondedResult.onFailure { error ->
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.e(TAG, "❌ Failed to get bonded devices: ${error.message}")
                    return@launch
                }

                val bondedDevices = bondedResult.getOrNull() ?: emptyList()

                if (bondedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "❌ No bonded devices")
                    return@launch
                }

                Log.d(TAG, "✅ Found ${bondedDevices.size} bonded devices")

                // ✅ 2단계: 스캔 시작 (UseCase)
                _deviceConnectionState.value = DeviceConnectionState.Scanning

                val scanResult = startScanUseCase(
                    context = context,
                    durationMs = SCAN_DURATION_MS,
                    filter = { device ->
                        // Bonded 디바이스만 필터링
                        bondedDevices.any { it.mac == device.mac }
                    }
                )

                scanResult.onFailure { error ->
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.e(TAG, "❌ Scan failed: ${error.message}")
                    return@launch
                }

                val scannedDevices = scanResult.getOrNull() ?: emptyList()

                // ✅ 3단계: 최적 디바이스 선택 (가장 강한 RSSI)
                val bestDevice = scannedDevices
                    .filter { it.rssi != null }
                    .maxByOrNull { it.rssi!! }

                if (bestDevice == null) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.d(TAG, "❌ No devices found during scan")
                    return@launch
                }

                Log.d(TAG, "🎯 Best device found: ${bestDevice.mac} RSSI=${bestDevice.rssi}")

                // ✅ 4단계: 연결 (UseCase)
                val connectResult = connectDeviceUseCase(
                    context = context,
                    device = bestDevice,
                    onConnected = {
                        _deviceConnectionState.value = DeviceConnectionState.Connected(bestDevice)
                        Log.d(TAG, "✅ Auto connected: ${bestDevice.mac}")

                        // ✅ 5단계: 연결 애니메이션 (UseCase)
                        viewModelScope.launch {
                            val animResult = sendConnectionEffectUseCase(context, bestDevice)
                            animResult.onFailure { error ->
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

                connectResult.onFailure { error ->
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.e(TAG, "❌ Connect error: ${error.message}")
                }

            } catch (e: Exception) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                Log.e(TAG, "❌ Auto scan error: ${e.message}")
            }
        }
    }

    fun retryAutoScan(context: Context) {
        startAutoScan(context)
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

    private fun saveFgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { index, color ->
                putInt("fg_preset_$index", colorToRgb(color.toLightStickColor()))
            }
            apply()
        }
    }

    private fun saveBgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { index, color ->
                putInt("bg_preset_$index", colorToRgb(color.toLightStickColor()))
            }
            apply()
        }
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
                        period = 0,
                        transit = 50,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.Off -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 0,
                        transit = 100,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.Strobe -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 10,
                        transit = 0,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.Blink -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 30,
                        transit = 0,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.Breath -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 30,
                        transit = 0,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.EffectList -> EffectSettings(
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 0,
                        transit = 0,
                        randomColor = false,
                        randomDelay = 0,
                        broadcasting = true
                    )
                    is UiEffectType.Custom -> {
                        defaultFor(
                            when (effectType.baseType) {
                                UiEffectType.BaseEffectType.ON -> UiEffectType.On
                                UiEffectType.BaseEffectType.OFF -> UiEffectType.Off
                                UiEffectType.BaseEffectType.STROBE -> UiEffectType.Strobe
                                UiEffectType.BaseEffectType.BLINK -> UiEffectType.Blink
                                UiEffectType.BaseEffectType.BREATH -> UiEffectType.Breath
                            }
                        )
                    }
                }
            }

            fun fromJson(jsonString: String): EffectSettings {
                val obj = JSONObject(jsonString)
                return EffectSettings(
                    color = LightStickColor(
                        r = obj.getInt("colorR"),
                        g = obj.getInt("colorG"),
                        b = obj.getInt("colorB")
                    ),
                    backgroundColor = LightStickColor(
                        r = obj.getInt("bgColorR"),
                        g = obj.getInt("bgColorG"),
                        b = obj.getInt("bgColorB")
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
            obj.put("colorR", color.r)
            obj.put("colorG", color.g)
            obj.put("colorB", color.b)
            obj.put("bgColorR", backgroundColor.r)
            obj.put("bgColorG", backgroundColor.g)
            obj.put("bgColorB", backgroundColor.b)
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