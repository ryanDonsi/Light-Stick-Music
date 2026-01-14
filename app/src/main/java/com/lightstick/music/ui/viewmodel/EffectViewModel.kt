package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.layout.add
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
import com.lightstick.types.Color as LightStickColor
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
        // ✅ 추가
        private const val MAX_CUSTOM_EFFECTS = 7
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // 상태 (State)
    // ═══════════════════════════════════════════════════════════

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _selectedEffectListNumber = MutableStateFlow<Int?>(null)
    val selectedEffectListNumber: StateFlow<Int?> = _selectedEffectListNumber.asStateFlow()

    // ✅ 수정: Custom 타입과 BaseEffectType 추가
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

    data class EffectSettings(
        val uiType: UiEffectType,
        var broadcasting: Boolean = false,
        var color: LightStickColor = Colors.WHITE,
        var backgroundColor: LightStickColor = Colors.BLACK,
        var period: Int = 0,
        var transit: Int = 0,
        var randomColor: Boolean = false,
        var randomDelay: Int = 0,
        var fade: Int = 100
    ) {
        companion object {
            fun defaultFor(effectType: UiEffectType): EffectSettings {
                return when (effectType) {
                    is UiEffectType.On -> EffectSettings(uiType = effectType, color = Colors.WHITE, transit = 0, broadcasting = false)
                    is UiEffectType.Off -> EffectSettings(uiType = effectType, transit = 0, broadcasting = false)
                    is UiEffectType.Strobe -> EffectSettings(uiType = effectType, color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 2, broadcasting = false)
                    is UiEffectType.Blink -> EffectSettings(uiType = effectType, color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 5, broadcasting = false)
                    is UiEffectType.Breath -> EffectSettings(uiType = effectType, color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 40, broadcasting = false)
                    is UiEffectType.EffectList -> EffectSettings(uiType = effectType, color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 30, broadcasting = false)
                    // ✅ 수정: Custom 타입의 기본 설정 추가
                    is UiEffectType.Custom -> {
                        when (effectType.baseType) {
                            UiEffectType.BaseEffectType.ON -> defaultFor(UiEffectType.On).copy(uiType = effectType)
                            UiEffectType.BaseEffectType.OFF -> defaultFor(UiEffectType.Off).copy(uiType = effectType)
                            UiEffectType.BaseEffectType.STROBE -> defaultFor(UiEffectType.Strobe).copy(uiType = effectType)
                            UiEffectType.BaseEffectType.BLINK -> defaultFor(UiEffectType.Blink).copy(uiType = effectType)
                            UiEffectType.BaseEffectType.BREATH -> defaultFor(UiEffectType.Breath).copy(uiType = effectType)
                        }
                    }
                }
            }
        }
    }

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

    // ✅ 추가
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

    private var effectListJob: Job? = null
    private var scanJob: Job? = null

    init {
        loadCustomEffects() // ✅ 추가
        loadAllSettings()

        viewModelScope.launch {
            delay(300)
            checkExistingConnection()
        }

        observeDeviceStates()
    }

    // (기존의 프리셋, 연결, 스캔 관련 함수들은 그대로 유지)
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
                // ✅ 수정: 확장 함수를 통해 rgbToColor 재사용
                rgbToColor(rgb).toComposeColor()
            } else {
                PresetColors.defaultBackgroundPresets[index]
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkExistingConnection() {
        try {
            val connected = LSBluetooth.connectedDevices()
            val lsDevice = connected.firstOrNull { it.name?.endsWith("LS") == true }

            if (lsDevice != null) {
                _deviceConnectionState.value = DeviceConnectionState.Connected(lsDevice)
                Log.d(TAG, "✅ Found existing connection: ${lsDevice.mac}")
            } else {
                Log.d(TAG, "⚠️ No existing connection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking existing connection: ${e.message}")
        }
    }

    private fun observeDeviceStates() {
        viewModelScope.launch {
            LSBluetooth.observeDeviceStates().collect { states ->
                if (_deviceConnectionState.value is DeviceConnectionState.Scanning) {
                    return@collect
                }

                val connectedState = states.values.firstOrNull { state ->
                    state.connectionState is ConnectionState.Connected
                }

                if (connectedState != null) {
                    val device = Device(
                        mac = connectedState.macAddress,
                        name = connectedState.deviceInfo?.deviceName ?: "Unknown",
                        rssi = connectedState.deviceInfo?.rssi
                    )

                    if (_deviceConnectionState.value !is DeviceConnectionState.Connected) {
                        _deviceConnectionState.value = DeviceConnectionState.Connected(device)
                        Log.d(TAG, "✅ Device connected via observeDeviceStates: ${device.mac}")
                    }
                } else {
                    if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                        _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                        Log.d(TAG, "⚠️ Device disconnected via observeDeviceStates")
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
                    .filter { it.name?.endsWith("LS") == true }
                if (bondedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "❌ No bonded LS devices")
                    return@launch
                }

                Log.d(TAG, "✅ Found ${bondedDevices.size} bonded LS devices")

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

                Log.d(TAG, "✅ Best device: ${bestDevice.mac} RSSI=${bestDevice.rssi}")

                connectToDevice(bestDevice)

            } catch (e: Exception) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                Log.e(TAG, "❌ Auto scan failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: Device) {
        device.connect(
            onConnected = {
                _deviceConnectionState.value = DeviceConnectionState.Connected(device)
                Log.d(TAG, "✅ Auto connected: ${device.mac}")
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

    private fun createEffectListSequence(number: Int): List<Pair<Long, ByteArray>> {
        return when (number) {
            1 -> listOf(
                0L to LSEffectPayload.Effects.breath(40, Colors.RED, Colors.BLACK).toByteArray(),
                2000L to LSEffectPayload.Effects.breath(50, LightStickColor(255, 192, 203), Colors.BLACK).toByteArray(),
                4000L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 30).toByteArray(),
                6000L to LSEffectPayload.Effects.breath(45, LightStickColor(255, 165, 0), Colors.BLACK).toByteArray()
            )
            2 -> listOf(
                0L to LSEffectPayload.Effects.strobe(5, Colors.MAGENTA, Colors.BLACK).toByteArray(),
                1000L to LSEffectPayload.Effects.blink(8, Colors.CYAN, Colors.BLACK).toByteArray(),
                2000L to LSEffectPayload.Effects.strobe(3, Colors.YELLOW, Colors.BLACK).toByteArray(),
                3000L to LSEffectPayload.Effects.strobe(6, Colors.GREEN, Colors.BLACK).toByteArray()
            )
            3 -> listOf(
                0L to LSEffectPayload.Effects.strobe(10, Colors.RED, Colors.BLACK).toByteArray(),
                1500L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 10).toByteArray(),
                2500L to LSEffectPayload.Effects.strobe(8, LightStickColor(255, 165, 0), Colors.BLACK).toByteArray(),
                4000L to LSEffectPayload.Effects.blink(15, Colors.YELLOW, Colors.BLACK).toByteArray()
            )
            4 -> listOf(
                0L to LSEffectPayload.Effects.blink(12, Colors.YELLOW, Colors.BLACK).toByteArray(),
                1200L to LSEffectPayload.Effects.strobe(7, LightStickColor(128, 0, 128), Colors.BLACK).toByteArray(),
                2400L to LSEffectPayload.Effects.blink(10, Colors.CYAN, Colors.BLACK).toByteArray(),
                3600L to LSEffectPayload.Effects.on(Colors.GREEN, transit = 15).toByteArray()
            )
            5 -> listOf(
                0L to LSEffectPayload.Effects.breath(50, Colors.CYAN, Colors.BLUE).toByteArray(),
                2500L to LSEffectPayload.Effects.on(LightStickColor(135, 206, 235), transit = 40).toByteArray(),
                5000L to LSEffectPayload.Effects.breath(60, Colors.BLUE, Colors.BLACK).toByteArray(),
                7500L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 50).toByteArray()
            )
            6 -> listOf(
                0L to LSEffectPayload.Effects.breath(60, Colors.WHITE, Colors.BLACK).toByteArray(),
                3000L to LSEffectPayload.Effects.on(LightStickColor(255, 192, 203), transit = 50).toByteArray(),
                6000L to LSEffectPayload.Effects.breath(70, LightStickColor(135, 206, 235), Colors.BLACK).toByteArray(),
                9000L to LSEffectPayload.Effects.on(Colors.WHITE, transit = 60).toByteArray()
            )
            else -> listOf(
                0L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ 추가: Custom Effect CRUD
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
                remove("${key}_broadcasting")
                remove("${key}_color_rgb")
                remove("${key}_bg_color_rgb")
                remove("${key}_period")
                remove("${key}_transit")
                remove("${key}_randomColor")
                remove("${key}_randomDelay")
                remove("${key}_fade")
                apply()
            }
            saveCustomEffects()
            if (_selectedEffect.value == custom) {
                _selectedEffect.value = null
                _playingEffect.value = null
                _isPlaying.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete custom effect: ${e.message}")
        }
    }

    fun renameCustomEffect(customId: String, newName: String) {
        try {
            val current = _customEffects.value.toMutableList()
            val index = current.indexOfFirst { it.id == customId }
            if (index < 0) return
            val old = current[index]
            val renamed = UiEffectType.Custom(id = old.id, baseType = old.baseType, name = newName)
            current[index] = renamed
            _customEffects.value = current
            if (_selectedEffect.value == old) {
                _selectedEffect.value = renamed
            }
            if (_playingEffect.value == old) {
                _playingEffect.value = renamed
            }
            saveCustomEffects()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to rename custom effect: ${e.message}")
        }
    }

    private fun saveCustomEffects() {
        try {
            val jsonArray = JSONArray()
            _customEffects.value.forEach { custom ->
                val jsonObject = JSONObject()
                jsonObject.put("id", custom.id)
                jsonObject.put("name", custom.name)
                jsonObject.put("baseType", custom.baseType.name)
                jsonArray.put(jsonObject)
            }
            prefs.edit().putString(KEY_CUSTOM_EFFECTS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom effects: ${e.message}")
        }
    }

    private fun loadCustomEffects() {
        try {
            val json = prefs.getString(KEY_CUSTOM_EFFECTS, null) ?: return
            val jsonArray = JSONArray(json)
            val customs = mutableListOf<UiEffectType.Custom>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val custom = UiEffectType.Custom(
                    id = jsonObject.getString("id"),
                    name = jsonObject.getString("name"),
                    baseType = UiEffectType.BaseEffectType.valueOf(jsonObject.getString("baseType"))
                )
                customs.add(custom)
            }
            _customEffects.value = customs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom effects: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Effect 제어 (기존 코드 + Custom 타입 처리 추가)
    // ═══════════════════════════════════════════════════════════

    // ✅ 수정: getEffectKey에 Custom 타입 처리 추가
    fun getEffectKey(effect: UiEffectType): String {
        return when (effect) {
            is UiEffectType.On -> "ON"
            is UiEffectType.Off -> "OFF"
            is UiEffectType.Strobe -> "STROBE"
            is UiEffectType.Blink -> "BLINK"
            is UiEffectType.Breath -> "BREATH"
            is UiEffectType.EffectList -> "EFFECT_LIST_${effect.number}"
            is UiEffectType.Custom -> "CUSTOM_${effect.id}"
        }
    }

    // (기존의 loadAllSettings, loadSettings, saveSettings 등은 그대로 유지)
    private fun loadAllSettings() {
        try {
            val effectKeys = mutableListOf(
                "ON", "OFF", "STROBE", "BLINK", "BREATH",
                "EFFECT_LIST_1", "EFFECT_LIST_2", "EFFECT_LIST_3",
                "EFFECT_LIST_4", "EFFECT_LIST_5", "EFFECT_LIST_6"
            )

            // ✅ 추가: 로드된 커스텀 이펙트의 키들을 목록에 추가합니다.
            _customEffects.value.forEach { customEffect ->
                effectKeys.add(getEffectKey(customEffect))
            }

            effectKeys.forEach { effectKey ->
                val settings = loadSettings(effectKey)
                if (settings != null) {
                    effectSettingsMapInternal[effectKey] = settings
                }
            }
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings: ${e.message}")
        }
    }

    private fun loadSettings(effectKey: String): EffectSettings? {
        return try {
            val uiType: UiEffectType? = when {
                effectKey == "ON" -> UiEffectType.On
                effectKey == "OFF" -> UiEffectType.Off
                effectKey == "STROBE" -> UiEffectType.Strobe
                effectKey == "BLINK" -> UiEffectType.Blink
                effectKey == "BREATH" -> UiEffectType.Breath
                effectKey.startsWith("EFFECT_LIST_") -> {
                    val num = effectKey.substringAfter("EFFECT_LIST_").toIntOrNull() ?: 1
                    UiEffectType.EffectList(num)
                }
                // ✅ 추가: 커스텀 이펙트의 경우, 미리 로드된 목록에서 ID로 찾습니다.
                effectKey.startsWith("CUSTOM_") -> {
                    val customId = effectKey.substringAfter("CUSTOM_")
                    _customEffects.value.find { it.id == customId }
                }
                else -> null
            }

            if (uiType == null) {
                Log.w(TAG, "Could not find matching UiEffectType for key: $effectKey")
                return null
            }

            if (!prefs.contains("${effectKey}_color_rgb")) {
                return EffectSettings.defaultFor(uiType)
            }

            val colorRgb = prefs.getInt("${effectKey}_color_rgb", 0xFFFFFF)
            val bgColorRgb = prefs.getInt("${effectKey}_bg_color_rgb", 0x000000)

            EffectSettings(
                uiType = uiType,
                broadcasting = prefs.getBoolean("${effectKey}_broadcasting", false),
                color = rgbToColor(colorRgb),
                backgroundColor = rgbToColor(bgColorRgb),
                period = prefs.getInt("${effectKey}_period", EffectSettings.defaultFor(uiType).period),
                transit = prefs.getInt("${effectKey}_transit", EffectSettings.defaultFor(uiType).transit),
                randomColor = prefs.getBoolean("${effectKey}_randomColor", false),
                randomDelay = prefs.getInt("${effectKey}_randomDelay", 0),
                fade = prefs.getInt("${effectKey}_fade", 100)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading settings for $effectKey: ${e.message}")
            null
        }
    }

    private fun saveSettings(effectKey: String, settings: EffectSettings) {
        try {
            prefs.edit().apply {
                putBoolean("${effectKey}_broadcasting", settings.broadcasting)
                putInt("${effectKey}_color_rgb", colorToRgb(settings.color))
                putInt("${effectKey}_bg_color_rgb", colorToRgb(settings.backgroundColor))
                putInt("${effectKey}_period", settings.period)
                putInt("${effectKey}_transit", settings.transit)
                putBoolean("${effectKey}_randomColor", settings.randomColor)
                putInt("${effectKey}_randomDelay", settings.randomDelay)
                putInt("${effectKey}_fade", settings.fade)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings for $effectKey: ${e.message}")
        }
    }

    fun selectEffect(effectType: UiEffectType) {
        val key = getEffectKey(effectType)

        if (_selectedEffect.value == effectType) {
            _selectedEffect.value = null
            return
        }

        _selectedEffect.value = effectType
        _currentSettings.value = effectSettingsMapInternal[key]
            ?: EffectSettings.defaultFor(effectType)
    }

    @SuppressLint("MissingPermission")
    fun playEffect(context: Context, effectType: UiEffectType) {
        if (!PermissionManager.hasAllBluetoothPermissions(context)) {
            _errorMessage.value = "Bluetooth 권한이 필요합니다"
            return
        }

        try {
            val connected = LSBluetooth.connectedDevices()
            val lsDevice = connected.firstOrNull { it.name?.endsWith("LS") == true }

            if (lsDevice == null) {
                _errorMessage.value = "연결된 디바이스가 없습니다"
                return
            }

            val settings = effectSettingsMapInternal[getEffectKey(effectType)]
                ?: EffectSettings.defaultFor(effectType)

            if (effectType is UiEffectType.EffectList) {
                effectListJob?.cancel()

                val frames = createEffectListSequence(effectType.number)

                effectListJob = viewModelScope.launch {
                    while (isActive) {
                        lsDevice.play(frames)

                        val maxTimestamp = frames.maxOfOrNull { it.first } ?: 0L
                        delay(maxTimestamp + 500)
                    }
                }

                _playingEffect.value = effectType
                _isPlaying.value = true
                _errorMessage.value = null

                Log.d(TAG, "✅ EffectList play started: ${effectType.displayName}")
                return
            }

            val payload = createPayload(effectType, settings) ?: return

            lsDevice.sendEffect(payload)

            _playingEffect.value = effectType
            _isPlaying.value = true
            _errorMessage.value = null

            Log.d(TAG, "✅ Effect sent: ${effectType.displayName}")

        } catch (e: Exception) {
            _errorMessage.value = "Effect 전송 실패: ${e.message}"
            Log.e(TAG, "❌ playEffect error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopEffect(context: Context) {
        try {
            effectListJob?.cancel()
            effectListJob = null

            val connected = LSBluetooth.connectedDevices()
            val lsDevice = connected.firstOrNull { it.name?.endsWith("LS") == true }

            lsDevice?.sendEffect(LSEffectPayload.Effects.off())

            _playingEffect.value = null
            _isPlaying.value = false

            Log.d(TAG, "✅ Effect stopped")

        } catch (e: Exception) {
            Log.e(TAG, "❌ stopEffect error: ${e.message}")
        }
    }

    // ✅ 수정: createPayload에 Custom 타입 처리 추가
    private fun createPayload(effectType: UiEffectType, settings: EffectSettings): LSEffectPayload? {
        return try {
            when (effectType) {
                is UiEffectType.On -> LSEffectPayload.Effects.on(
                    color = settings.color,
                    transit = settings.transit,
                    randomColor = if (settings.randomColor) 1 else 0,
                    randomDelay = settings.randomDelay,
                    broadcasting = if (settings.broadcasting) 1 else 0
                )
                is UiEffectType.Off -> LSEffectPayload.Effects.off(
                    transit = settings.transit,
                    randomDelay = settings.randomDelay,
                    broadcasting = if (settings.broadcasting) 1 else 0
                )
                is UiEffectType.Strobe -> LSEffectPayload.Effects.strobe(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = if (settings.randomColor) 1 else 0,
                    randomDelay = settings.randomDelay,
                    broadcasting = if (settings.broadcasting) 1 else 0
                )
                is UiEffectType.Blink -> LSEffectPayload.Effects.blink(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = if (settings.randomColor) 1 else 0,
                    randomDelay = settings.randomDelay,
                    broadcasting = if (settings.broadcasting) 1 else 0
                )
                is UiEffectType.Breath -> LSEffectPayload.Effects.breath(
                    period = settings.period,
                    color = settings.color,
                    backgroundColor = settings.backgroundColor,
                    randomColor = if (settings.randomColor) 1 else 0,
                    randomDelay = settings.randomDelay,
                    broadcasting = if (settings.broadcasting) 1 else 0
                )
                is UiEffectType.EffectList -> null
                is UiEffectType.Custom -> {
                    when (effectType.baseType) {
                        UiEffectType.BaseEffectType.ON -> createPayload(UiEffectType.On, settings)
                        UiEffectType.BaseEffectType.OFF -> createPayload(UiEffectType.Off, settings)
                        UiEffectType.BaseEffectType.STROBE -> createPayload(UiEffectType.Strobe, settings)
                        UiEffectType.BaseEffectType.BLINK -> createPayload(UiEffectType.Blink, settings)
                        UiEffectType.BaseEffectType.BREATH -> createPayload(UiEffectType.Breath, settings)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPayload error: ${e.message}")
            null
        }
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

    fun clearToastMessage() {
        _toastMessage.value = null
    }

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

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        effectListJob?.cancel()
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
}