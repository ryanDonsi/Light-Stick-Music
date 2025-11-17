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
 * ✅ SDK 타입 직접 사용:
 * - com.lightstick.types.Color
 * - com.lightstick.types.EffectType
 * - com.lightstick.types.LSEffectPayload
 * - 중간 변환 없이 직접 사용
 */
class EffectViewModel : ViewModel() {

    // UI 표시용 이펙트 타입
    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On : UiEffectType("ON", "LED를 선택한 색상으로 켭니다")
        data object Off : UiEffectType("OFF", "LED를 천천히 끕니다")
        data object Strobe : UiEffectType("STROBE", "빠르게 깜빡이는 효과 (50~2000ms)")
        data object Blink : UiEffectType("BLINK", "천천히 깜빡이는 효과 (200~5000ms)")
        data object Breath : UiEffectType("BREATH", "숨쉬듯 밝아졌다 어두워지는 효과 (500~10000ms)")
        data class EffectList(val number: Int, val subName: String = "") :
            UiEffectType("EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}",
                "내장 이펙트 리스트 재생")
    }

    // 이펙트 설정 (SDK 타입 사용)
    data class EffectSettings(
        val uiType: UiEffectType,
        var broadcasting: Boolean = false,
        var color: Color = Colors.RED,          // ✅ SDK Color 사용
        var backgroundColor: Color = Colors.BLACK, // ✅ SDK Color 사용
        var period: Int = 500,
        var transit: Int = 10,
        var randomColor: Boolean = false,
        var randomDelay: Int = 0
    )

    // 현재 선택된 이펙트
    private val _selectedEffect = MutableStateFlow<UiEffectType?>(null)
    val selectedEffect: StateFlow<UiEffectType?> = _selectedEffect.asStateFlow()

    // 현재 이펙트 설정
    private val _currentSettings = MutableStateFlow(EffectSettings(UiEffectType.On))
    val currentSettings: StateFlow<EffectSettings> = _currentSettings.asStateFlow()

    // 이펙트 재생 중 여부
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 재생 Job
    private var playbackJob: Job? = null

    /**
     * 이펙트 선택/해제
     */
    fun selectEffect(context: Context, effectType: UiEffectType) {
        if (!hasBluetoothPermission(context)) {
            Log.w("EffectViewModel", "BLUETOOTH_CONNECT permission not granted")
            return
        }

        if (_selectedEffect.value == effectType) {
            stopEffect(context)
        } else {
            stopEffect(context)
            _selectedEffect.value = effectType
            _currentSettings.value = EffectSettings(effectType)
            startEffect(context)
        }
    }

    /**
     * 이펙트 설정 업데이트
     */
    @SuppressLint("MissingPermission")
    fun updateSettings(context: Context, settings: EffectSettings) {
        _currentSettings.value = settings

        if (_isPlaying.value && hasBluetoothPermission(context)) {
            sendEffectToDevices()
        }
    }

    /**
     * 색상 변경
     */
    @SuppressLint("MissingPermission")
    fun updateColor(context: Context, color: Color) {
        _currentSettings.value = _currentSettings.value.copy(color = color)

        if (_isPlaying.value && hasBluetoothPermission(context)) {
            sendEffectToDevices()
        }
    }

    /**
     * Broadcasting 토글
     */
    @SuppressLint("MissingPermission")
    fun toggleBroadcasting(context: Context) {
        _currentSettings.value = _currentSettings.value.copy(
            broadcasting = !_currentSettings.value.broadcasting
        )

        if (_isPlaying.value && hasBluetoothPermission(context)) {
            sendEffectToDevices()
        }
    }

    /**
     * 이펙트 재생 시작
     */
    @SuppressLint("MissingPermission")
    private fun startEffect(context: Context) {
        if (!hasBluetoothPermission(context)) return

        _isPlaying.value = true

        playbackJob = viewModelScope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    sendEffectToDevices()

                    val delayMs = when (_selectedEffect.value) {
                        is UiEffectType.On -> 5000L
                        is UiEffectType.Off -> {
                            _isPlaying.value = false
                            break
                        }
                        is UiEffectType.Strobe,
                        is UiEffectType.Blink,
                        is UiEffectType.Breath -> {
                            (_currentSettings.value.period * 2L).coerceAtLeast(500L)
                        }
                        is UiEffectType.EffectList -> 1000L
                        null -> break
                    }

                    delay(delayMs)
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Error sending effect: ${e.message}")
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
     * ✅ SDK 타입 직접 사용 - 변환 불필요!
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
                        color = if (settings.randomColor) generateRandomColor() else settings.color,
                        period = settings.transit * 10,
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay
                    )
                }
                is UiEffectType.Off -> {
                    LSEffectPayload.Effects.off()
                }
                is UiEffectType.Strobe -> {
                    LSEffectPayload.Effects.strobe(
                        color = if (settings.randomColor) generateRandomColor() else settings.color,
                        period = settings.period.coerceIn(0, 255),
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay
                    )
                }
                is UiEffectType.Blink -> {
                    LSEffectPayload.Effects.blink(
                        color = if (settings.randomColor) generateRandomColor() else settings.color,
                        period = settings.period.coerceIn(0, 255),
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay
                    )
                }
                is UiEffectType.Breath -> {
                    LSEffectPayload.Effects.breath(
                        color = if (settings.randomColor) generateRandomColor() else settings.color,
                        period = settings.period.coerceIn(0, 255),
                        randomColor = if (settings.randomColor) 1 else 0,
                        randomDelay = settings.randomDelay
                    )
                }
                is UiEffectType.EffectList -> {
                    // 랜덤 프리셋 색상
                    val presetColors = listOf(
                        Colors.RED, Colors.GREEN, Colors.BLUE,
                        Colors.YELLOW, Colors.MAGENTA, Colors.CYAN
                    )
                    LSEffectPayload.Effects.on(
                        color = presetColors.random(),
                        period = 100,
                        randomColor = 0,
                        randomDelay = 0
                    )
                }
                null -> return
            }

            // 모든 디바이스에 전송
            devices.forEach { device ->
                try {
                    device.sendEffect(payload)
                } catch (e: Exception) {
                    Log.e("EffectViewModel", "Failed to send to ${device.mac}: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("EffectViewModel", "sendEffectToDevices error: ${e.message}")
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
    // 권한 체크
    // ------------------------------------------------------------------------

    private fun hasBluetoothPermission(context: Context): Boolean {
        return PermissionUtils.hasPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    }

    // ------------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------------

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