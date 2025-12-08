package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.lightstick.LSBluetooth
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.LSEffectPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Effect 화면의 ViewModel
 *
 * ✅ 개선 사항:
 * - Color 직렬화 개선 (RGB를 하나의 Int로 저장)
 * - SDK의 LSEffectPayload.Effects 사용
 * - SharedPreferences 영구 저장
 * - EffectScreen 완전 호환 (수정 불필요)
 */
class EffectViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "effect_settings"
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // UI 표시용 이펙트 타입
    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On : UiEffectType("ON", "LED를 선택한 색상으로 켭니다")
        data object Off : UiEffectType("OFF", "LED를 끕니다")
        data object Strobe : UiEffectType("STROBE", "플래시 터지는 효과 (period: 0~255)")
        data object Blink : UiEffectType("BLINK", "깜빡이는 효과 (period: 0~255)")
        data object Breath : UiEffectType("BREATH", "숨쉬듯 밝아졌다 어두워지는 효과 (period: 0~255)")
        data class EffectList(val number: Int, val subName: String = "") :
            UiEffectType("EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}",
                "내장 이펙트 리스트 재생")
    }

    // 이펙트 설정 (SDK 타입 사용)
    data class EffectSettings(
        val uiType: UiEffectType,
        var broadcasting: Boolean = false,
        var color: Color = Colors.WHITE,
        var backgroundColor: Color = Colors.BLACK,
        var period: Int = 0,
        var transit: Int = 0,
        var randomColor: Boolean = false,
        var randomDelay: Int = 0,
        var fade: Int = 100
    )

    // 현재 선택된 이펙트
    private val _selectedEffect = MutableStateFlow<UiEffectType?>(null)
    val selectedEffect: StateFlow<UiEffectType?> = _selectedEffect.asStateFlow()

    // 이펙트별 개별 설정 저장소 (내부용)
    private val effectSettingsMapInternal = mutableMapOf<String, EffectSettings>()

    // ✅ EffectScreen 호환: effectSettingsMap 이름으로 StateFlow 제공
    private val _effectSettingsMap = MutableStateFlow<Map<String, EffectSettings>>(emptyMap())
    val effectSettingsMap: StateFlow<Map<String, EffectSettings>> = _effectSettingsMap.asStateFlow()

    // 현재 이펙트 설정 (선택된 이펙트의 설정)
    private val _currentSettings = MutableStateFlow(EffectSettings(UiEffectType.On))
    val currentSettings: StateFlow<EffectSettings> = _currentSettings.asStateFlow()

    // 이펙트 재생 중 여부
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 에러 메시지
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 재생 Job
    private var playbackJob: Job? = null

    init {
        // 앱 시작 시 저장된 설정 불러오기
        loadAllSettings()
    }

    /**
     * ✅ public 메서드
     */
    fun getEffectKey(effect: UiEffectType): String {
        return when (effect) {
            is UiEffectType.On -> "ON"
            is UiEffectType.Off -> "OFF"
            is UiEffectType.Strobe -> "STROBE"
            is UiEffectType.Blink -> "BLINK"
            is UiEffectType.Breath -> "BREATH"
            is UiEffectType.EffectList -> "EFFECT_LIST_${effect.number}"
        }
    }

    /**
     * SharedPreferences에서 모든 설정 불러오기
     */
    private fun loadAllSettings() {
        try {
            val effectKeys = listOf(
                "ON", "OFF", "STROBE", "BLINK", "BREATH",
                "EFFECT_LIST_1", "EFFECT_LIST_2", "EFFECT_LIST_3",
                "EFFECT_LIST_4", "EFFECT_LIST_5", "EFFECT_LIST_6"
            )

            effectKeys.forEach { effectKey ->
                val settings = loadSettings(effectKey)
                if (settings != null) {
                    effectSettingsMapInternal[effectKey] = settings
                    Log.d("EffectViewModel", "Loaded settings for $effectKey")
                }
            }

            // ✅ Flow 업데이트
            _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Failed to load settings: ${e.message}")
        }
    }

    /**
     * ✅ Color 직렬화 개선 (RGB를 하나의 Int로)
     */
    private fun loadSettings(effectKey: String): EffectSettings? {
        return try {
            if (!prefs.contains("${effectKey}_color_rgb")) return null

            val uiType = when (effectKey) {
                "ON" -> UiEffectType.On
                "OFF" -> UiEffectType.Off
                "STROBE" -> UiEffectType.Strobe
                "BLINK" -> UiEffectType.Blink
                "BREATH" -> UiEffectType.Breath
                else -> {
                    val num = effectKey.substringAfter("EFFECT_LIST_").toIntOrNull() ?: return null
                    UiEffectType.EffectList(num)
                }
            }

            // ✅ RGB를 하나의 Int로 저장/복원
            val colorRgb = prefs.getInt("${effectKey}_color_rgb", 0xFFFFFF)
            val bgColorRgb = prefs.getInt("${effectKey}_bg_color_rgb", 0x000000)

            EffectSettings(
                uiType = uiType,
                broadcasting = prefs.getBoolean("${effectKey}_broadcasting", false),
                color = rgbToColor(colorRgb),
                backgroundColor = rgbToColor(bgColorRgb),
                period = prefs.getInt("${effectKey}_period", 0),
                transit = prefs.getInt("${effectKey}_transit", 0),
                randomColor = prefs.getBoolean("${effectKey}_randomColor", false),
                randomDelay = prefs.getInt("${effectKey}_randomDelay", 0),
                fade = prefs.getInt("${effectKey}_fade", 100)
            )
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Error loading settings for $effectKey: ${e.message}")
            null
        }
    }

    /**
     * ✅ Color 저장 (RGB를 하나의 Int로)
     */
    private fun saveSettings(effectKey: String, settings: EffectSettings) {
        try {
            prefs.edit().apply {
                putBoolean("${effectKey}_broadcasting", settings.broadcasting)

                // ✅ RGB를 하나의 Int로 변환하여 저장
                putInt("${effectKey}_color_rgb", colorToRgb(settings.color))
                putInt("${effectKey}_bg_color_rgb", colorToRgb(settings.backgroundColor))

                putInt("${effectKey}_period", settings.period)
                putInt("${effectKey}_transit", settings.transit)
                putBoolean("${effectKey}_randomColor", settings.randomColor)
                putInt("${effectKey}_randomDelay", settings.randomDelay)
                putInt("${effectKey}_fade", settings.fade)
                apply()
            }
            Log.d("EffectViewModel", "Saved settings for $effectKey")
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Error saving settings for $effectKey: ${e.message}")
        }
    }

    /**
     * ✅ Color를 Int로 변환 (0xRRGGBB)
     */
    private fun colorToRgb(color: Color): Int {
        return (color.r shl 16) or (color.g shl 8) or color.b
    }

    /**
     * ✅ Int를 Color로 변환
     */
    private fun rgbToColor(rgb: Int): Color {
        return Color(
            r = (rgb shr 16) and 0xFF,
            g = (rgb shr 8) and 0xFF,
            b = rgb and 0xFF
        )
    }

    /**
     * ✅ EffectScreen 호환: selectEffect(context, effect)
     */
    fun selectEffect(context: Context, effectType: UiEffectType) {
        _selectedEffect.value = effectType
        val key = getEffectKey(effectType)

        _currentSettings.value = effectSettingsMapInternal[key] ?: EffectSettings(effectType)
        Log.d("EffectViewModel", "Selected effect: $key")
    }

    /**
     * 기본 버전 (Context 불필요한 경우)
     */
    fun selectEffect(effectType: UiEffectType) {
        _selectedEffect.value = effectType
        val key = getEffectKey(effectType)

        _currentSettings.value = effectSettingsMapInternal[key] ?: EffectSettings(effectType)
        Log.d("EffectViewModel", "Selected effect: $key")
    }

    /**
     * ✅ EffectScreen 호환: getEffectSettings(effect)
     */
    fun getEffectSettings(effectType: UiEffectType): EffectSettings {
        val key = getEffectKey(effectType)
        return effectSettingsMapInternal[key] ?: EffectSettings(effectType)
    }

    /**
     * ✅ EffectScreen 호환: saveEffectSettings(effect, settings)
     */
    fun saveEffectSettings(effectType: UiEffectType, settings: EffectSettings) {
        val key = getEffectKey(effectType)
        effectSettingsMapInternal[key] = settings
        saveSettings(key, settings)

        // ✅ Flow 업데이트
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    /**
     * ✅ EffectScreen 호환: updateColor(context, color) - 현재 선택된 effect
     */
    fun updateColor(context: Context, color: Color) {
        val current = _selectedEffect.value ?: return
        val settings = _currentSettings.value.copy(color = color)
        _currentSettings.value = settings
        saveEffectSettings(current, settings)
    }

    /**
     * ✅ EffectScreen 호환: updateBackgroundColor(context, color) - 현재 선택된 effect
     */
    fun updateBackgroundColor(context: Context, color: Color) {
        val current = _selectedEffect.value ?: return
        val settings = _currentSettings.value.copy(backgroundColor = color)
        _currentSettings.value = settings
        saveEffectSettings(current, settings)
    }

    /**
     * ✅ EffectScreen 호환: updateSettings(context, newSettings) - 현재 선택된 effect
     */
    fun updateSettings(context: Context, newSettings: EffectSettings) {
        val current = _selectedEffect.value ?: return
        _currentSettings.value = newSettings
        saveEffectSettings(current, newSettings)
    }

    /**
     * 현재 설정 업데이트 (updater 패턴)
     */
    fun updateCurrentSettings(updater: (EffectSettings) -> EffectSettings) {
        val updated = updater(_currentSettings.value)
        _currentSettings.value = updated

        val current = _selectedEffect.value ?: return
        val key = getEffectKey(current)
        effectSettingsMapInternal[key] = updated
        saveSettings(key, updated)

        // ✅ Flow 업데이트
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    /**
     * ✅ clearError
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 이펙트 재생
     */
    @SuppressLint("MissingPermission")
    fun playEffect(context: Context) {
        playbackJob?.cancel()

        if (!hasBluetoothPermission(context)) {
            _errorMessage.value = "블루투스 권한이 필요합니다"
            return
        }

        _isPlaying.value = true
        playbackJob = viewModelScope.launch {
            try {
                while (isActive) {
                    sendEffectToDevices()
                    delay(100)
                }
            } catch (e: Exception) {
                Log.e("EffectViewModel", "Error in playEffect: ${e.message}")
                _errorMessage.value = "이펙트 전송 실패: ${e.message}"
            }
        }
    }

    /**
     * 이펙트 중지
     */
    @SuppressLint("MissingPermission")
    fun stopEffect(context: Context) {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false

        if (hasBluetoothPermission(context)) {
            sendOffToAllDevices()
        }

        _selectedEffect.value = null
    }

    /**
     * ✅ SDK의 LSEffectPayload.Effects 사용
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendEffectToDevices() {
        try {
            val devices = LSBluetooth.connectedDevices()
            if (devices.isEmpty()) {
                Log.d("EffectViewModel", "No connected devices")
                return
            }

            val settings = _currentSettings.value

            // ✅ SDK Payload 직접 생성
            val payload = when (_selectedEffect.value) {
                is UiEffectType.On -> {
                    LSEffectPayload.Effects.on(
                        color = settings.color,
                        transit = settings.transit,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Off -> {
                    LSEffectPayload.Effects.off(
                        transit = settings.transit,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Strobe -> {
                    LSEffectPayload.Effects.strobe(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Blink -> {
                    LSEffectPayload.Effects.blink(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Breath -> {
                    LSEffectPayload.Effects.breath(
                        period = settings.period,
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.EffectList -> {
                    val presetColors = listOf(
                        Colors.RED, Colors.GREEN, Colors.BLUE,
                        Colors.YELLOW, Colors.MAGENTA, Colors.CYAN
                    )
                    LSEffectPayload.Effects.on(
                        color = presetColors.random(),
                        transit = 100,
                        randomColor = 0,
                        randomDelay = 0
                    )
                }
                null -> return
            }

            // ✅ 모든 디바이스에 전송 (또는 broadcast 사용)
            if (settings.broadcasting) {
                // ✅ SDK의 broadcast 메서드 사용
                LSBluetooth.broadcastEffect(payload)
                Log.d("EffectViewModel", "Broadcasted effect to all devices")
            } else {
                // 개별 전송
                devices.forEach { device ->
                    try {
                        Log.d("EffectViewModel", "Sending Effect to ${device.mac}")
                        device.sendEffect(payload)
                    } catch (e: Exception) {
                        Log.e("EffectViewModel", "Failed to send to ${device.mac}: ${e.message}")
                    }
                }
            }

        } catch (e: IllegalArgumentException) {
            Log.e("EffectViewModel", "Invalid parameter: ${e.message}")
            _errorMessage.value = "설정값 오류: ${e.message}"
            throw e
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Error sending effect: ${e.message}")
            _errorMessage.value = "전송 오류: ${e.message}"
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendOffToAllDevices() {
        try {
            val offPayload = LSEffectPayload.Effects.off()
            LSBluetooth.broadcastEffect(offPayload)
            Log.d("EffectViewModel", "Sent OFF to all devices")
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Error sending OFF: ${e.message}")
        }
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        return PermissionUtils.hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }
}