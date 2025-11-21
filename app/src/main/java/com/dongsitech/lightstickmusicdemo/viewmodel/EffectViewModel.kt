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
 * - SharedPreferences를 사용한 설정 영구 저장 추가
 * - 앱 재시작 시 마지막 설정 자동 로드
 * - Period 값 유효성 검사 강화 (0~255 범위 엄격 적용)
 * - Background Color 설정 추가
 * - 사용자 입력 실시간 유효성 검사
 * - 에러 메시지 표시 기능
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
        var color: Color = Colors.WHITE,              // Foreground Color
        var backgroundColor: Color = Colors.BLACK,   // Background Color
        var period: Int = 0,                        // 0~255 범위
        var transit: Int = 0,                       // 0~255 범위
        var randomColor: Boolean = false,
        var randomDelay: Int = 0,                    // 0~255 범위
        var fade: Int = 100                            // 0~255 범위
    )

    // 현재 선택된 이펙트
    private val _selectedEffect = MutableStateFlow<UiEffectType?>(null)
    val selectedEffect: StateFlow<UiEffectType?> = _selectedEffect.asStateFlow()

    // 이펙트별 개별 설정 저장소
    private val effectSettingsMap = mutableMapOf<String, EffectSettings>()

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
     * 이펙트의 고유 키 생성
     */
    private fun getEffectKey(effect: UiEffectType): String {
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
            // 미리 정의된 이펙트 키 목록
            val effectKeys = listOf(
                "ON",
                "OFF",
                "STROBE",
                "BLINK",
                "BREATH",
                "EFFECT_LIST_1",
                "EFFECT_LIST_2",
                "EFFECT_LIST_3",
                "EFFECT_LIST_4",
                "EFFECT_LIST_5",
                "EFFECT_LIST_6"
            )

            effectKeys.forEach { effectKey ->
                val settings = loadSettings(effectKey)
                if (settings != null) {
                    effectSettingsMap[effectKey] = settings
                    Log.d("EffectViewModel", "Loaded settings for $effectKey")
                }
            }
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Failed to load settings: ${e.message}")
        }
    }

    /**
     * 특정 이펙트의 설정을 SharedPreferences에서 불러오기
     */
    private fun loadSettings(effectKey: String): EffectSettings? {
        try {
            val prefix = "effect_${effectKey}_"

            // 저장된 데이터가 있는지 확인
            if (!prefs.contains("${prefix}color_r")) {
                return null
            }

            // 색상 정보 불러오기
            val colorR = prefs.getInt("${prefix}color_r", 255)
            val colorG = prefs.getInt("${prefix}color_g", 255)
            val colorB = prefs.getInt("${prefix}color_b", 255)
            val color = Color(colorR, colorG, colorB)

            val bgColorR = prefs.getInt("${prefix}bgcolor_r", 0)
            val bgColorG = prefs.getInt("${prefix}bgcolor_g", 0)
            val bgColorB = prefs.getInt("${prefix}bgcolor_b", 0)
            val backgroundColor = Color(bgColorR, bgColorG, bgColorB)

            // 나머지 설정 불러오기
            val period = prefs.getInt("${prefix}period", 10)
            val transit = prefs.getInt("${prefix}transit", 0)
            val randomColor = prefs.getBoolean("${prefix}random_color", false)
            val randomDelay = prefs.getInt("${prefix}random_delay", 0)
            val broadcasting = prefs.getBoolean("${prefix}broadcasting", false)
            val fade = prefs.getInt("${prefix}fade", 0)

            // UiEffectType 복원
            val uiType = when (effectKey) {
                "ON" -> UiEffectType.On
                "OFF" -> UiEffectType.Off
                "STROBE" -> UiEffectType.Strobe
                "BLINK" -> UiEffectType.Blink
                "BREATH" -> UiEffectType.Breath
                else -> {
                    if (effectKey.startsWith("EFFECT_LIST_")) {
                        val number = effectKey.removePrefix("EFFECT_LIST_").toIntOrNull() ?: 1
                        UiEffectType.EffectList(number)
                    } else {
                        return null
                    }
                }
            }

            return EffectSettings(
                uiType = uiType,
                color = color,
                backgroundColor = backgroundColor,
                period = period,
                transit = transit,
                randomColor = randomColor,
                randomDelay = randomDelay,
                broadcasting = broadcasting,
                fade = fade
            )
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Failed to load settings for $effectKey: ${e.message}")
            return null
        }
    }

    /**
     * 이펙트의 저장된 설정 가져오기 (없으면 기본값 생성)
     */
    private fun getOrCreateSettings(effect: UiEffectType): EffectSettings {
        val key = getEffectKey(effect)

        // 이미 메모리에 로드된 설정이 있으면 반환
        effectSettingsMap[key]?.let { return it }

        // SharedPreferences에서 로드 시도
        val loadedSettings = loadSettings(key)
        if (loadedSettings != null) {
            effectSettingsMap[key] = loadedSettings
            return loadedSettings
        }

        // 저장된 설정이 없으면 기본값 생성 후 저장
        val defaultSettings = createDefaultSettings(effect)
        effectSettingsMap[key] = defaultSettings
        return defaultSettings
    }

    /**
     * 이펙트별 기본 설정 생성
     */
    private fun createDefaultSettings(effect: UiEffectType): EffectSettings {
        return when (effect) {
            is UiEffectType.On -> EffectSettings(
                uiType = effect,
                color = Colors.WHITE,
                transit = 0
            )
            is UiEffectType.Off -> EffectSettings(
                uiType = effect,
                transit = 0
            )
            is UiEffectType.Strobe -> EffectSettings(
                uiType = effect,
                color = Colors.RED,
                period = 10
            )
            is UiEffectType.Blink -> EffectSettings(
                uiType = effect,
                color = Colors.BLUE,
                period = 10
            )
            is UiEffectType.Breath -> EffectSettings(
                uiType = effect,
                color = Colors.CYAN,
                period = 40
            )
            is UiEffectType.EffectList -> EffectSettings(
                uiType = effect,
                color = Colors.MAGENTA,
                period = 10
            )
        }
    }

    /**
     * 현재 이펙트의 설정 저장
     */
    private fun saveCurrentSettings() {
        _selectedEffect.value?.let { effect ->
            val key = getEffectKey(effect)
            val settings = _currentSettings.value.copy()

            // 메모리에 저장
            effectSettingsMap[key] = settings

            // SharedPreferences에 저장
            saveSettingsToPrefs(key, settings)

            Log.d("EffectViewModel", "Settings saved for $key")
        }
    }

    /**
     * 설정을 SharedPreferences에 저장
     */
    private fun saveSettingsToPrefs(effectKey: String, settings: EffectSettings) {
        try {
            val prefix = "effect_${effectKey}_"

            prefs.edit().apply {
                // 색상 정보 저장
                putInt("${prefix}color_r", settings.color.r and 0xFF)
                putInt("${prefix}color_g", settings.color.g and 0xFF)
                putInt("${prefix}color_b", settings.color.b and 0xFF)

                putInt("${prefix}bgcolor_r", settings.backgroundColor.r and 0xFF)
                putInt("${prefix}bgcolor_g", settings.backgroundColor.g and 0xFF)
                putInt("${prefix}bgcolor_b", settings.backgroundColor.b and 0xFF)

                // 나머지 설정 저장
                putInt("${prefix}period", settings.period)
                putInt("${prefix}transit", settings.transit)
                putBoolean("${prefix}random_color", settings.randomColor)
                putInt("${prefix}random_delay", settings.randomDelay)
                putBoolean("${prefix}broadcasting", settings.broadcasting)
                putInt("${prefix}fade", settings.fade)

                apply()
            }
        } catch (e: Exception) {
            Log.e("EffectViewModel", "Failed to save settings for $effectKey: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // Effect Selection & Control
    // ------------------------------------------------------------------------

    /**
     * 이펙트 선택 및 재생
     */
    @SuppressLint("MissingPermission")
    fun selectEffect(context: Context, effect: UiEffectType) {
        if (!hasBluetoothPermission(context)) {
            _errorMessage.value = "Bluetooth 권한이 필요합니다"
            return
        }

        // 이미 선택된 이펙트를 다시 선택하면 중지
        if (_selectedEffect.value == effect && _isPlaying.value) {
            stopEffect(context)
            return
        }

        // 이전 설정 저장
        saveCurrentSettings()

        // 이전 재생 중지
        stopEffect(context)

        // 새 이펙트 선택
        _selectedEffect.value = effect

        // 해당 이펙트의 저장된 설정 로드
        _currentSettings.value = getOrCreateSettings(effect)

        // 재생 시작
        startEffect(context)
    }

    /**
     * 이펙트 재생 시작
     */
    @SuppressLint("MissingPermission")
    private fun startEffect(context: Context) {
        _isPlaying.value = true
        _errorMessage.value = null

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    sendEffectToDevices()

                    // 반복 주기 계산
                    val delayMs = when (_selectedEffect.value) {
                        is UiEffectType.On,
                        is UiEffectType.Off -> {
                            // ON/OFF는 한 번만 전송
                            _isPlaying.value = false
                            break
                        }
                        is UiEffectType.Strobe,
                        is UiEffectType.Blink,
                        is UiEffectType.Breath -> {
                            // period 값 사용 (이미 0~255 범위로 제한됨)
                            (_currentSettings.value.period * 2L).coerceAtLeast(100L)
                        }
                        is UiEffectType.EffectList -> 1000L
                        null -> break
                    }

                    delay(delayMs)
                } catch (e: IllegalArgumentException) {
                    // Period 범위 오류 등을 캐치
                    Log.e("EffectViewModel", "Effect parameter error: ${e.message}")
                    _errorMessage.value = "설정값 오류: ${e.message}"
                    _isPlaying.value = false
                    break
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Error sending effect: ${e.message}")
                    _errorMessage.value = "이펙트 전송 실패: ${e.message}"
                }
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
     * 연결된 모든 디바이스에 이펙트 전송
     * SDK의 LSEffectPayload에서 유효성 검사를 처리함
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

            // SDK Payload 직접 생성 (SDK가 유효성 검사 처리)
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
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        period = settings.period,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Blink -> {
                    LSEffectPayload.Effects.blink(
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        period = settings.period,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.Breath -> {
                    LSEffectPayload.Effects.breath(
                        color = settings.color,
                        backgroundColor = settings.backgroundColor,
                        period = settings.period,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay,
                        broadcasting = if (settings.broadcasting) 1 else 0
                    )
                }
                is UiEffectType.EffectList -> {
                    // 랜덤 프리셋 색상(추후 Effect List로 생성)
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

            // 모든 디바이스에 전송
            devices.forEach { device ->
                try {
                    Log.d("EffectViewModel", "Sending Effect to ${device.mac}: payload=0x${
                        payload.toByteArray().joinToString(" ") { "%02X".format(it) }
                    }")
                    device.sendEffect(payload)
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to send to ${device.mac}: ${e.message}")
                }
            }

        } catch (e: IllegalArgumentException) {
            // SDK에서 발생한 유효성 검사 오류
            Log.e("EffectViewModel", "Invalid parameter: ${e.message}")
            _errorMessage.value = "설정값 오류: ${e.message}"
            throw e
        } catch (e: Exception) {
            Log.e("EffectViewModel", "sendEffectToDevices error: ${e.message}")
            throw e
        }
    }

    /**
     * 모든 디바이스에 OFF 전송
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendOffToAllDevices() {
        try {
            val devices = LSBluetooth.connectedDevices()
            val offPayload = LSEffectPayload.Effects.off()

            devices.forEach { device ->
                try {
                    device.sendEffect(offPayload)
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to send OFF: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("EffectViewModel", "sendOffToAllDevices error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------------
    // Settings Update (✅ 유효성 검사 포함)
    // ------------------------------------------------------------------------

    /**
     * Foreground Color 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateColor(context: Context, color: Color) {
        _currentSettings.value = _currentSettings.value.copy(color = color)
        saveCurrentSettings() // 설정 저장

        // 재생 중이면 즉시 적용
        if (_isPlaying.value) {
            viewModelScope.launch {
                try {
                    sendEffectToDevices()
                } catch (e: IllegalArgumentException) {
                    _errorMessage.value = "색상 설정 오류: ${e.message}"
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to apply color: ${e.message}")
                }
            }
        }
    }

    /**
     * Background Color 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateBackgroundColor(context: Context, color: Color) {
        _currentSettings.value = _currentSettings.value.copy(backgroundColor = color)
        saveCurrentSettings() // 설정 저장

        // 재생 중이면 즉시 적용
        if (_isPlaying.value) {
            viewModelScope.launch {
                try {
                    sendEffectToDevices()
                } catch (e: IllegalArgumentException) {
                    _errorMessage.value = "색상 설정 오류: ${e.message}"
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to apply color: ${e.message}")
                }
            }
        }
    }

    /**
     * Broadcasting 모드 토글
     */
    @SuppressLint("MissingPermission")
    fun toggleBroadcasting(context: Context) {
        _currentSettings.value = _currentSettings.value.copy(
            broadcasting = !_currentSettings.value.broadcasting
        )
        saveCurrentSettings() // 설정 저장

        // 재생 중이면 즉시 적용
        if (_isPlaying.value) {
            viewModelScope.launch {
                try {
                    sendEffectToDevices()
                } catch (e: IllegalArgumentException) {
                    _errorMessage.value = "설정값 오류: ${e.message}"
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to apply settings: ${e.message}")
                }
            }
        }
    }

    /**
     * 전체 설정 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateSettings(context: Context, settings: EffectSettings) {
        _currentSettings.value = settings
        saveCurrentSettings() // 설정 저장

        // 재생 중이면 즉시 적용
        if (_isPlaying.value) {
            viewModelScope.launch {
                try {
                    sendEffectToDevices()
                } catch (e: IllegalArgumentException) {
                    _errorMessage.value = "설정값 오류: ${e.message}"
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to apply settings: ${e.message}")
                }
            }
        }
    }

    /**
     * 특정 이펙트의 설정 가져오기 (UI에서 사용)
     */
    fun getEffectSettings(effect: UiEffectType): EffectSettings {
        return getOrCreateSettings(effect)
    }

    /**
     * 에러 메시지 초기화
     */
    fun clearError() {
        _errorMessage.value = null
    }

    // ------------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------------

    /**
     * 권한 체크
     */
    private fun hasBluetoothPermission(context: Context): Boolean {
        return PermissionUtils.hasPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}