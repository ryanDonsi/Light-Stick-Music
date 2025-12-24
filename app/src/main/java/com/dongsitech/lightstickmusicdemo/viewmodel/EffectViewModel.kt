package com.dongsitech.lightstickmusicdemo.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.util.DeviceSettings
import com.lightstick.LSBluetooth
import com.lightstick.device.Device
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
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
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // Effect Type 정의
    // ═══════════════════════════════════════════════════════════

    enum class BaseEffectType(val displayName: String) {
        ON("ON"),
        OFF("OFF"),
        STROBE("STROBE"),
        BLINK("BLINK"),
        BREATH("BREATH")
    }

    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On : UiEffectType("ON", "켜기")
        data object Off : UiEffectType("OFF", "끄기")
        data object Strobe : UiEffectType("STROBE", "스트로브")
        data object Blink : UiEffectType("BLINK", "깜빡임")
        data object Breath : UiEffectType("BREATH", "숨쉬기")
        data class Custom(val id: String, val baseType: BaseEffectType, val name: String) :
            UiEffectType(name, "")
        data class EffectList(val number: Int, val name: String) :
            UiEffectType(name, "EffectList$number")
    }

    data class EffectSettings(
        val uiType: UiEffectType,
        val broadcasting: Boolean = false,
        val color: Color = Colors.WHITE,
        val backgroundColor: Color = Colors.BLACK,
        val period: Int = 5,
        val transit: Int = 2,
        val randomColor: Boolean = false,
        val randomDelay: Int = 0,
        val fade: Int = 100
    ) {
        companion object {
            fun defaultFor(effectType: UiEffectType): EffectSettings {
                return when (effectType) {
                    is UiEffectType.On -> EffectSettings(
                        uiType = effectType,
                        color = Colors.WHITE,
                        transit = 0,
                        broadcasting = false
                    )
                    is UiEffectType.Off -> EffectSettings(
                        uiType = effectType,
                        transit = 0,
                        broadcasting = false
                    )
                    is UiEffectType.Strobe -> EffectSettings(
                        uiType = effectType,
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 2,
                        broadcasting = false
                    )
                    is UiEffectType.Blink -> EffectSettings(
                        uiType = effectType,
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 5,
                        broadcasting = false
                    )
                    is UiEffectType.Breath -> EffectSettings(
                        uiType = effectType,
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 40,
                        broadcasting = false
                    )
                    is UiEffectType.Custom -> {
                        when (effectType.baseType) {
                            BaseEffectType.ON -> EffectSettings(
                                uiType = effectType,
                                color = Colors.WHITE,
                                transit = 0,
                                broadcasting = false
                            )
                            BaseEffectType.OFF -> EffectSettings(
                                uiType = effectType,
                                transit = 0,
                                broadcasting = false
                            )
                            BaseEffectType.STROBE -> EffectSettings(
                                uiType = effectType,
                                color = Colors.WHITE,
                                backgroundColor = Colors.BLACK,
                                period = 2,
                                broadcasting = false
                            )
                            BaseEffectType.BLINK -> EffectSettings(
                                uiType = effectType,
                                color = Colors.WHITE,
                                backgroundColor = Colors.BLACK,
                                period = 5,
                                broadcasting = false
                            )
                            BaseEffectType.BREATH -> EffectSettings(
                                uiType = effectType,
                                color = Colors.WHITE,
                                backgroundColor = Colors.BLACK,
                                period = 40,
                                broadcasting = false
                            )
                        }
                    }
                    is UiEffectType.EffectList -> EffectSettings(
                        uiType = effectType,
                        color = Colors.WHITE,
                        backgroundColor = Colors.BLACK,
                        period = 5,
                        broadcasting = false
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Device Connection State
    // ═══════════════════════════════════════════════════════════

    sealed class DeviceConnectionState {
        data object NoBondedDevice : DeviceConnectionState()
        data object Scanning : DeviceConnectionState()
        data class ScanFailed(val message: String) : DeviceConnectionState()
        data class Connected(val device: Device) : DeviceConnectionState()
    }

    private val _deviceConnectionState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.NoBondedDevice)
    val deviceConnectionState: StateFlow<DeviceConnectionState> = _deviceConnectionState.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    // State 관리
    // ═══════════════════════════════════════════════════════════

    private val _selectedEffect = MutableStateFlow<UiEffectType?>(null)
    val selectedEffect: StateFlow<UiEffectType?> = _selectedEffect.asStateFlow()

    private val _playingEffect = MutableStateFlow<UiEffectType?>(null)
    val playingEffect: StateFlow<UiEffectType?> = _playingEffect.asStateFlow()

    private val effectSettingsMapInternal = mutableMapOf<String, EffectSettings>()
    private val _effectSettingsMap = MutableStateFlow<Map<String, EffectSettings>>(emptyMap())
    val effectSettingsMap: StateFlow<Map<String, EffectSettings>> = _effectSettingsMap.asStateFlow()

    private val _customEffects = MutableStateFlow<List<UiEffectType.Custom>>(emptyList())
    val customEffects: StateFlow<List<UiEffectType.Custom>> = _customEffects.asStateFlow()

    init {
        loadAllSettings()
        loadCustomEffects()

        viewModelScope.launch {
            delay(500)
            startAutoReconnect()
        }
    }

    fun selectEffect(effect: UiEffectType) {
        _selectedEffect.value = effect
    }

    @SuppressLint("MissingPermission")
    fun playEffect(context: Context, effect: UiEffectType) {
        val device = (_deviceConnectionState.value as? DeviceConnectionState.Connected)?.device
        if (device == null) {
            Log.e(TAG, "No device connected")
            return
        }

        val settings = effectSettingsMapInternal[getEffectKey(effect)] ?: EffectSettings.defaultFor(effect)

        try {
            val payload = when (effect) {
                is UiEffectType.On -> {
                    LSEffectPayload.Effects.on(
                        color = settings.color,
                        transit = settings.transit
                    )
                }
                is UiEffectType.Off -> {
                    LSEffectPayload.Effects.off(transit = settings.transit)
                }
                is UiEffectType.Strobe -> {
                    LSEffectPayload.Effects.strobe(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor
                    )
                }
                is UiEffectType.Blink -> {
                    LSEffectPayload.Effects.blink(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor
                    )
                }
                is UiEffectType.Breath -> {
                    LSEffectPayload.Effects.breath(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor
                    )
                }
                is UiEffectType.Custom -> {
                    when (effect.baseType) {
                        BaseEffectType.ON -> LSEffectPayload.Effects.on(
                            color = settings.color,
                            transit = settings.transit
                        )
                        BaseEffectType.OFF -> LSEffectPayload.Effects.off(transit = settings.transit)
                        BaseEffectType.STROBE -> LSEffectPayload.Effects.strobe(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor
                        )
                        BaseEffectType.BLINK -> LSEffectPayload.Effects.blink(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor
                        )
                        BaseEffectType.BREATH -> LSEffectPayload.Effects.breath(
                            period = settings.period,
                            color = settings.color,
                            backgroundColor = settings.backgroundColor
                        )
                    }
                }
                is UiEffectType.EffectList -> {
                    LSEffectPayload.Effects.on(
                        color = settings.color,
                        transit = settings.transit
                    )
                }
            }

            device.sendEffect(payload)
            _playingEffect.value = effect
            Log.d(TAG, "Effect played: ${effect.displayName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to play effect: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopEffect(context: Context) {
        val device = (_deviceConnectionState.value as? DeviceConnectionState.Connected)?.device
        if (device == null) {
            Log.e(TAG, "No device connected")
            return
        }

        try {
            val payload = LSEffectPayload.Effects.off(transit = 0)
            device.sendEffect(payload)
            _playingEffect.value = null
            Log.d(TAG, "Effect stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop effect: ${e.message}")
        }
    }

    fun updateSettings(effect: UiEffectType, newSettings: EffectSettings) {
        val key = getEffectKey(effect)
        effectSettingsMapInternal[key] = newSettings
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        saveSettings(key, newSettings)
    }

    fun getSettings(effect: UiEffectType): EffectSettings {
        val key = getEffectKey(effect)
        return effectSettingsMapInternal[key] ?: EffectSettings.defaultFor(effect)
    }

    fun addCustomEffect(name: String, baseType: BaseEffectType) {
        try {
            val id = UUID.randomUUID().toString()
            val custom = UiEffectType.Custom(id, baseType, name)

            val current = _customEffects.value.toMutableList()
            current.add(custom)
            _customEffects.value = current

            saveCustomEffects()

            val defaultSettings = EffectSettings.defaultFor(custom)
            effectSettingsMapInternal[getEffectKey(custom)] = defaultSettings
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()

            Log.d(TAG, "Custom effect added: $name")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add custom effect: ${e.message}")
        }
    }

    fun deleteCustomEffect(custom: UiEffectType.Custom) {
        try {
            val current = _customEffects.value.toMutableList()
            current.remove(custom)
            _customEffects.value = current

            saveCustomEffects()

            effectSettingsMapInternal.remove(getEffectKey(custom))
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete custom effect: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Auto-Reconnect
    // ═══════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startAutoReconnect() {
        viewModelScope.launch {
            if (!DeviceSettings.getAutoReconnectEnabled()) {
                _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                Log.d(TAG, "❌ Auto-reconnect disabled")
                return@launch
            }

            try {
                val bondedDevices = LSBluetooth.bondedDevices()
                if (bondedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "📱 No bonded devices")
                    return@launch
                }

                val bondedLsDevices = bondedDevices.filter { it.name?.endsWith("LS") == true }
                if (bondedLsDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "📱 No bonded LS devices")
                    return@launch
                }

                val bondedMacAddresses = bondedLsDevices.map { it.mac }.toSet()
                Log.d(TAG, "📱 Found ${bondedLsDevices.size} bonded LS devices")

                _deviceConnectionState.value = DeviceConnectionState.Scanning
                Log.d(TAG, "🔍 Scanning for bonded LS devices...")

                val scannedDevices = mutableListOf<Device>()

                LSBluetooth.startScan { device ->
                    if (device.name?.endsWith("LS") == true && device.mac in bondedMacAddresses) {
                        Log.d(TAG, "📱 Found bonded LS device: ${device.mac} | ${device.name} | RSSI: ${device.rssi}")

                        val existingIndex = scannedDevices.indexOfFirst { it.mac == device.mac }
                        if (existingIndex >= 0) {
                            scannedDevices[existingIndex] = device
                        } else {
                            scannedDevices.add(device)
                        }
                    }
                }

                delay(3000)

                LSBluetooth.stopScan()
                Log.d(TAG, "🛑 Scan stopped. Found ${scannedDevices.size} bonded LS devices")

                if (scannedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed("등록된 LS 기기를 찾을 수 없습니다")
                    Log.d(TAG, "❌ No bonded LS devices found in scan")
                    return@launch
                }

                val sortedDevices = scannedDevices.sortedByDescending { it.rssi ?: -100 }

                val targetDevice = sortedDevices.first()
                Log.d(TAG, "🎯 Target device: ${targetDevice.mac} (${targetDevice.name}) RSSI: ${targetDevice.rssi}")

                Log.d(TAG, "🔗 Connecting to ${targetDevice.mac}...")

                var connectionSuccess = false

                targetDevice.connect(
                    onConnected = {
                        connectionSuccess = true
                        _deviceConnectionState.value = DeviceConnectionState.Connected(targetDevice)
                        Log.d(TAG, "✅ Connected successfully!")

                        viewModelScope.launch {
                            playConnectionAnimation(targetDevice)
                        }
                    },
                    onFailed = { error ->
                        _deviceConnectionState.value = DeviceConnectionState.ScanFailed("연결 실패: ${error.message}")
                        Log.e(TAG, "❌ Connection failed: ${error.message}")
                    }
                )

                delay(5000)

                if (!connectionSuccess) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed("연결 타임아웃")
                    Log.d(TAG, "⏱️ Connection timeout")
                }

            } catch (e: SecurityException) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed("권한 오류: ${e.message}")
                Log.e(TAG, "❌ Permission error: ${e.message}")
            } catch (e: Exception) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed("오류: ${e.message}")
                Log.e(TAG, "❌ Auto-reconnect error: ${e.message}")
            }
        }
    }

//    @SuppressLint("MissingPermission")
//    private suspend fun playConnectionAnimation(device: Device) {
//        try {
//            Log.d(TAG, "🎬 Playing connection animation...")
//
//            repeat(3) {
//                val payload = LSEffectPayload.Effects.blink(
//                    period = 5,
//                    color = Colors.WHITE,
//                    backgroundColor = Colors.BLACK
//                )
//                device.sendEffect(payload)
//
//            }
//            delay(1000)
//            device.sendEffect(LSEffectPayload.Effects.off(transit = 0))
//
//            Log.d(TAG, "✅ Connection animation completed")
//        } catch (e: Exception) {
//            Log.e(TAG, "Animation error: ${e.message}")
//        }
//    }

    @SuppressLint("MissingPermission")
    private suspend fun playConnectionAnimation(device: Device) {
        try {
            Log.d(TAG, "🎬 Playing connection animation (Timeline-based)...")
            val frames = listOf(
                // 0ms: Blink 시작
                0L to LSEffectPayload.Effects.off().toByteArray(),
                250L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray(),
                500L to LSEffectPayload.Effects.off().toByteArray(),
                750L to LSEffectPayload.Effects.on(Colors.WHITE).toByteArray()
            )
            device.play(frames)
            delay(2500)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Animation error: ${e.message}")
        }
    }


    // ═══════════════════════════════════════════════════════════
    // Settings Helper
    // ═══════════════════════════════════════════════════════════

    fun getEffectKey(effect: UiEffectType): String {
        return when (effect) {
            is UiEffectType.On -> "ON"
            is UiEffectType.Off -> "OFF"
            is UiEffectType.Strobe -> "STROBE"
            is UiEffectType.Blink -> "BLINK"
            is UiEffectType.Breath -> "BREATH"
            is UiEffectType.Custom -> "CUSTOM_${effect.id}"
            is UiEffectType.EffectList -> "EFFECT_LIST_${effect.number}"
        }
    }

    private fun loadAllSettings() {
        try {
            val effectKeys = listOf("ON", "OFF", "STROBE", "BLINK", "BREATH")

            effectKeys.forEach { effectKey ->
                val settings = loadSettings(effectKey)
                if (settings != null) {
                    effectSettingsMapInternal[effectKey] = settings
                    Log.d(TAG, "Loaded settings for $effectKey")
                }
            }

            _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings: ${e.message}")
        }
    }

    private fun loadSettings(effectKey: String): EffectSettings? {
        return try {
            val uiType = when (effectKey) {
                "ON" -> UiEffectType.On
                "OFF" -> UiEffectType.Off
                "STROBE" -> UiEffectType.Strobe
                "BLINK" -> UiEffectType.Blink
                "BREATH" -> UiEffectType.Breath
                else -> return null
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
            Log.d(TAG, "Saved settings for $effectKey")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings for $effectKey: ${e.message}")
        }
    }

    private fun colorToRgb(color: Color): Int {
        return (color.r shl 16) or (color.g shl 8) or color.b
    }

    private fun rgbToColor(rgb: Int): Color {
        return Color(
            r = (rgb shr 16) and 0xFF,
            g = (rgb shr 8) and 0xFF,
            b = rgb and 0xFF
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Custom Effects
    // ═══════════════════════════════════════════════════════════

    private fun loadCustomEffects() {
        try {
            val json = prefs.getString(KEY_CUSTOM_EFFECTS, null) ?: return
            val jsonArray = JSONArray(json)
            val effects = mutableListOf<UiEffectType.Custom>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val baseType = BaseEffectType.valueOf(obj.getString("baseType"))

                effects.add(UiEffectType.Custom(id, baseType, name))
            }

            _customEffects.value = effects
            Log.d(TAG, "Loaded ${effects.size} custom effects")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom effects: ${e.message}")
        }
    }

    private fun saveCustomEffects() {
        try {
            val jsonArray = JSONArray()
            _customEffects.value.forEach { custom ->
                val obj = JSONObject().apply {
                    put("id", custom.id)
                    put("name", custom.name)
                    put("baseType", custom.baseType.name)
                }
                jsonArray.put(obj)
            }

            prefs.edit().putString(KEY_CUSTOM_EFFECTS, jsonArray.toString()).apply()
            Log.d(TAG, "Saved ${_customEffects.value.size} custom effects")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom effects: ${e.message}")
        }
    }
}