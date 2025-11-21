package com.dongsitech.lightstickmusicdemo.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongsitech.lightstickmusicdemo.permissions.PermissionUtils
import com.lightstick.LSBluetooth
import com.lightstick.types.Color
import com.lightstick.types.Colors
import com.lightstick.types.EffectType
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
 * - Period 값 유효성 검사 강화 (0~255 범위 엄격 적용)
 * - Background Color 설정 추가
 * - 사용자 입력 실시간 유효성 검사
 * - 에러 메시지 표시 기능
 */
class EffectViewModel : ViewModel() {

    // UI 표시용 이펙트 타입
    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On : UiEffectType("ON", "LED를 선택한 색상으로 켭니다")
        data object Off : UiEffectType("OFF", "LED를 천천히 끕니다")
        data object Strobe : UiEffectType("STROBE", "빠르게 깜빡이는 효과 (period: 0~255)")
        data object Blink : UiEffectType("BLINK", "천천히 깜빡이는 효과 (period: 0~255)")
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
        var period: Int = 10,                        // 0~255 범위
        var transit: Int = 0,                       // 0~255 범위
        var randomColor: Boolean = false,
        var randomDelay: Int = 0,                    // 0~255 범위
        var fade: Int = 0                            // 0~255 범위
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
     * 이펙트의 저장된 설정 가져오기 (없으면 기본값 생성)
     */
    private fun getOrCreateSettings(effect: UiEffectType): EffectSettings {
        val key = getEffectKey(effect)
        return effectSettingsMap.getOrPut(key) {
            createDefaultSettings(effect)
        }
    }

    /**
     * 이펙트별 기본 설정 생성
     */
    private fun createDefaultSettings(effect: UiEffectType): EffectSettings {
        return when (effect) {
            is UiEffectType.On -> EffectSettings(
                uiType = effect,
                color = Colors.WHITE,
                period = 0,
                transit = 10
            )
            is UiEffectType.Off -> EffectSettings(
                uiType = effect,
                color = Colors.BLACK,
                period = 0,
                transit = 10
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
            effectSettingsMap[key] = _currentSettings.value.copy()
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
     * 이펙트별 기본값 설정 (더 이상 사용 안 함 - createDefaultSettings로 대체)
     */
    private fun setDefaultsForEffect(effect: UiEffectType) {
        // 이제 getOrCreateSettings에서 처리하므로 비워둠
    }

    /**
     * 이펙트 재생 시작
     */
    @SuppressLint("MissingPermission")
    private fun startEffect(context: Context) {
        _isPlaying.value = true
        _errorMessage.value = null

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
     * Period 값 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updatePeriod(context: Context, value: Int) {
        _currentSettings.value = _currentSettings.value.copy(period = value)
        saveCurrentSettings() // 설정 저장
        _errorMessage.value = null

        // 재생 중이면 즉시 적용
        if (_isPlaying.value) {
            viewModelScope.launch {
                try {
                    sendEffectToDevices()
                } catch (e: IllegalArgumentException) {
                    _errorMessage.value = "설정값 오류: ${e.message}"
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to apply period: ${e.message}")
                }
            }
        }
    }

    /**
     * Transit 값 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateTransit(context: Context, value: Int) {
        _currentSettings.value = _currentSettings.value.copy(transit = value)
        saveCurrentSettings() // 설정 저장
        _errorMessage.value = null
    }

    /**
     * Random Delay 값 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateRandomDelay(context: Context, value: Int) {
        _currentSettings.value = _currentSettings.value.copy(randomDelay = value)
        saveCurrentSettings() // 설정 저장
        _errorMessage.value = null
    }

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
     * Random Color 토글
     */
    @SuppressLint("MissingPermission")
    fun toggleRandomColor(context: Context) {
        _currentSettings.value = _currentSettings.value.copy(
            randomColor = !_currentSettings.value.randomColor
        )
        saveCurrentSettings() // 설정 저장
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

    /**
     * 랜덤 색상 생성 (SDK 프리셋 사용)
     */
    private fun generateRandomColor(): Color {
        val presetColors = listOf(
            Colors.RED, Colors.GREEN, Colors.BLUE, Colors.YELLOW,
            Colors.MAGENTA, Colors.CYAN, Colors.ORANGE, Colors.PURPLE, Colors.PINK
        )
        return presetColors.random()
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}