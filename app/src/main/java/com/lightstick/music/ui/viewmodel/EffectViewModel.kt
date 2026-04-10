package com.lightstick.music.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightstick.device.ConnectionState
import com.lightstick.music.core.ble.ControlMode
import com.lightstick.music.core.constants.AppConstants
import com.lightstick.music.core.constants.PrefsKeys
import com.lightstick.music.core.constants.EffectKeys
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.state.MusicPlaybackState
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
import com.lightstick.music.domain.usecase.device.ConnectDeviceUseCase
import com.lightstick.music.domain.usecase.device.ObserveDeviceStatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.lightstick.types.Color as LightStickColor
import com.lightstick.types.Colors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.core.content.edit

// ── JSON 직렬화 공유 설정 (kotlinx.serialization)
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class EffectSettingsDto(
    val colorR: Int = 255, val colorG: Int = 255, val colorB: Int = 255,
    val bgColorR: Int = 0, val bgColorG: Int = 0, val bgColorB: Int = 0,
    val period: Int = 10, val transit: Int = 0,
    val randomColor: Boolean = false, val randomDelay: Int = 0,
    val broadcasting: Boolean = true
)

@Serializable
private data class CustomEffectDto(
    val id: String,
    val baseType: String,
    val name: String
)

@HiltViewModel
class EffectViewModel @Inject constructor(
    application: Application,
    private val playManualEffectUseCase:     PlayManualEffectUseCase,
    private val playEffectListUseCase:       PlayEffectListUseCase,
    private val stopEffectUseCase:           StopEffectUseCase,
    private val sendConnectionEffectUseCase: SendConnectionEffectUseCase,
    private val getBondedDevicesUseCase:     GetBondedDevicesUseCase,
    private val startScanUseCase:            StartScanUseCase,
    private val connectDeviceUseCase:        ConnectDeviceUseCase
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = AppConstants.Feature.VM_EFFECT
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PrefsKeys.PREFS_EFFECT_SETTINGS, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // Control Mode
    // ═══════════════════════════════════════════════════════════

    private val controlMode: ControlMode = AppConstants.EFFECT_CONTROL_MODE

    // ═══════════════════════════════════════════════════════════
    // DeviceConnectionState
    //
    // 상태 전환 규칙:
    //   NoBondedDevice : 페어링된 기기 없음      → "기기 연결하기" 버튼
    //   Scanning       : 등록 기기 스캔 중        → 스피너
    //   ScanFailed     : 스캔/연결 실패           → Retry 아이콘
    //   Disconnected   : Connected 후 연결 해제   → Retry 아이콘 (ScanFailed 동일 UI)
    //   Connected      : 연결 완료
    //
    // ScanFailed vs Disconnected:
    //   ScanFailed   → 아직 Connected 된 적 없거나 연결 시도 자체 실패
    //   Disconnected → Connected 상태에서 해제됨 (reason 은 로그로만 확인)
    // ═══════════════════════════════════════════════════════════

    sealed class DeviceConnectionState {
        /** 페어링된 기기가 전혀 없음 → "기기 연결하기" 버튼 표시 */
        data object NoBondedDevice : DeviceConnectionState()

        /** 페어링된 기기 스캔 중 → 스피너 표시 */
        data object Scanning       : DeviceConnectionState()

        /** 스캔 실패 또는 연결 불가 (기기 범위 밖, 연결 시도 실패) → Retry 아이콘 */
        data object ScanFailed     : DeviceConnectionState()

        /** Connected 상태에서 연결 해제됨 → Retry 아이콘 (reason은 로그로만 확인) */
        data object Disconnected   : DeviceConnectionState()

        /** 연결 완료 */
        data class  Connected(val device: Device) : DeviceConnectionState()
    }

    // ═══════════════════════════════════════════════════════════
    // State Flows
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

    private val _customEffects = MutableStateFlow<List<UiEffectType.Custom>>(emptyList())
    val customEffects: StateFlow<List<UiEffectType.Custom>> = _customEffects.asStateFlow()

    private val _deviceConnectionState =
        MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.NoBondedDevice)
    val deviceConnectionState: StateFlow<DeviceConnectionState> =
        _deviceConnectionState.asStateFlow()

    private val _fgPresetColors = MutableStateFlow(loadFgPresetColors().toMutableList())
    val fgPresetColors: StateFlow<List<Color>> = _fgPresetColors.asStateFlow()

    private val _bgPresetColors = MutableStateFlow(loadBgPresetColors().toMutableList())
    val bgPresetColors: StateFlow<List<Color>> = _bgPresetColors.asStateFlow()

    private val _selectedFgPreset = MutableStateFlow<Int?>(null)
    val selectedFgPreset: StateFlow<Int?> = _selectedFgPreset.asStateFlow()

    private val _selectedBgPreset = MutableStateFlow<Int?>(null)
    val selectedBgPreset: StateFlow<Int?> = _selectedBgPreset.asStateFlow()

    val latestTransmission: StateFlow<BleTransmissionEvent?> =
        BleTransmissionMonitor.latestTransmission

    private val _isEffectBlocked = MutableStateFlow(false)
    val isEffectBlocked: StateFlow<Boolean> = _isEffectBlocked.asStateFlow()

    private var effectListJob: Job? = null
    private var scanJob: Job? = null

    // ═══════════════════════════════════════════════════════════
    // Init
    // ═══════════════════════════════════════════════════════════

    init {
        loadCustomEffects()
        loadAllSettings()
        observeDeviceStateEvents()
        observeMusicPlaybackState()
    }

    // ═══════════════════════════════════════════════════════════
// Device State Event Observer
// ═══════════════════════════════════════════════════════════

    /**
     * SDK SharedFlow 기반 디바이스 상태 이벤트 관찰
     *
     * Connected / Disconnected 전환을 단일 옵저버에서 처리합니다.
     * 기존 observeDeviceConnection() + observeDeviceDisconnect() 2개 통합.
     */
    private fun observeDeviceStateEvents() {
        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeDeviceStateEvents()
                .collect { event ->
                    when (val state = event.state) {
                        is ConnectionState.Connected -> {
                            val current = _deviceConnectionState.value
                            val alreadyConnected =
                                current is DeviceConnectionState.Connected &&
                                        current.device.mac == event.mac
                            if (!alreadyConnected) {
                                val device = Device(mac = event.mac, name = null, rssi = null)
                                _deviceConnectionState.value =
                                    DeviceConnectionState.Connected(device)
                                Log.d(TAG, "Device state → Connected: ${event.mac}")
                            }
                        }
                        is ConnectionState.Disconnected -> {
                            Log.d(TAG, "Disconnect: mac=${event.mac} reason=${state.reason}")
                            if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                                _deviceConnectionState.value = DeviceConnectionState.Disconnected
                                Log.d(TAG, "Device state → Disconnected")
                            }
                        }
                        else -> Unit  // Connecting / Disconnecting → UI 변경 없음
                    }
                }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 음악 재생 상태 관찰 → Effect 잠금 처리
    // ═══════════════════════════════════════════════════════════

    private fun observeMusicPlaybackState() {
        viewModelScope.launch {
            MusicPlaybackState.isPlayingWithAutoMode.collect { playingWithAutoMode ->
                val blocked = playingWithAutoMode && controlMode == ControlMode.EXCLUSIVE
                _isEffectBlocked.value = blocked
                Log.d(TAG, "Effect blocked: $blocked (${controlMode.getDescription()})")

                if (blocked) {
                    effectListJob?.cancel()
                    effectListJob                   = null
                    _selectedEffectListNumber.value = null
                    _selectedEffect.value           = null
                    _isPlaying.value                = false
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Auto Scan & Connect
    // ═══════════════════════════════════════════════════════════

    fun startAutoScan(context: Context) {
        if (!PermissionManager.hasAllBluetoothPermissions(context)) {
            _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            try {
                // 1순위: ViewModel 상태 확인
                if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                    Log.d(TAG, "Already connected (state)")
                    return@launch
                }

                // 2순위: SDK 레벨에서 이미 연결된 기기 확인 (DeviceViewModel이 먼저 연결했을 수 있음)
                @SuppressLint("MissingPermission")
                val sdkConnected = try {
                    com.lightstick.LSBluetooth.connectedDevices()
                } catch (_: Exception) { emptyList() }
                if (sdkConnected.isNotEmpty()) {
                    val device = sdkConnected.first()
                    _deviceConnectionState.value = DeviceConnectionState.Connected(device)
                    Log.d(TAG, "Already connected via SDK: ${device.mac}")
                    return@launch
                }

                val bondedDevices = getBondedDevicesUseCase(context).getOrNull() ?: emptyList()
                if (bondedDevices.isEmpty()) {
                    _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                    Log.d(TAG, "No bonded devices → NoBondedDevice")
                    return@launch
                }

                _deviceConnectionState.value = DeviceConnectionState.Scanning
                val scanResult = startScanUseCase(
                    context    = context,
                    durationMs = AppConstants.EFFECT_SCAN_DURATION_MS,
                    filter     = { device -> bondedDevices.any { it.mac == device.mac } }
                )

                scanResult.onFailure { error ->
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.e(TAG, "Scan failed: ${error.message}")
                    return@launch
                }

                val bestDevice = scanResult.getOrNull()
                    ?.filter { it.rssi != null }
                    ?.maxByOrNull { it.rssi!! }

                if (bestDevice == null) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.d(TAG, "No device found → ScanFailed")
                    return@launch
                }

                // 연결 성공 시 공통 처리 (1차·2차 재시도 공유)
                val onConnectedCallback: () -> Unit = {
                    _deviceConnectionState.value = DeviceConnectionState.Connected(bestDevice)
                    Log.d(TAG, "Auto connected: ${bestDevice.mac}")
                    viewModelScope.launch {
                        sendConnectionEffectUseCase(context, bestDevice)
                            .onFailure { Log.e(TAG, "Connection animation failed: ${it.message}") }
                    }
                }

                // 1차 연결 시도 — onFailed 콜백에서 상태 변경하지 않음 (재시도 후 판단)
                val connectResult = connectDeviceUseCase(
                    context     = context,
                    device      = bestDevice,
                    onConnected = onConnectedCallback
                )

//                // Android BLE GATT 첫 연결 실패는 매우 흔한 현상 (GATT 캐시 미정리).
//                // ScanFailed 노출 없이 500ms 대기 후 자동 재시도.
//                if (connectResult.isFailure) {
//                    Log.w(TAG, "1차 연결 실패, 500ms 후 재시도: ${connectResult.exceptionOrNull()?.message}")
//                    delay(500L)
//
//                    // SDK가 500ms 사이에 자체 연결했을 수 있으므로 재확인
//                    val sdkRecheck = try {
//                        com.lightstick.LSBluetooth.connectedDevices()
//                    } catch (e: Exception) { emptyList() }
//                    if (sdkRecheck.any { it.mac == bestDevice.mac }) {
//                        _deviceConnectionState.value = DeviceConnectionState.Connected(bestDevice)
//                        Log.d(TAG, "SDK 자체 연결 감지: ${bestDevice.mac}")
//                        return@launch
//                    }
//
//                    // 2차 연결 시도
//                    val connectResult = connectDeviceUseCase(
//                        context     = context,
//                        device      = bestDevice,
//                        onConnected = onConnectedCallback
//                    )
//                }

                if (connectResult.isFailure) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.e(TAG, "연결 최종 실패 : ${connectResult.exceptionOrNull()?.message}")
                }

            } catch (e: Exception) {
                _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                Log.e(TAG, "Auto scan error: ${e.message}")
            }
        }
    }

    fun retryAutoScan(context: Context) {
        startAutoScan(context)
    }

    // ═══════════════════════════════════════════════════════════
    // Effect 재생 / 중지
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

                if (effectType is UiEffectType.EffectList) {
                    playEffectListUseCase(
                        context          = context,
                        effectListNumber = effectType.number,
                        coroutineScope   = viewModelScope
                    ).onSuccess { job ->
                        effectListJob?.cancel()
                        effectListJob        = job
                        _isPlaying.value     = true
                    }.onFailure { error ->
                        _errorMessage.value = "EffectList 재생 실패: ${error.message}"
                    }
                    return@launch
                }

                playManualEffectUseCase(
                    context    = context,
                    effectType = effectType,
                    settings   = settings
                ).onSuccess {
                    _isPlaying.value                = true
                    _selectedEffectListNumber.value = null
                }.onFailure { error ->
                    _errorMessage.value = "Effect 전송 실패: ${error.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Effect 전송 실패: ${e.message}"
            }
        }
    }

    fun stopEffect(context: Context) {
        viewModelScope.launch {
            stopEffectUseCase(context = context, effectListJob = effectListJob)
                .onSuccess {
                    effectListJob                   = null
                    _isPlaying.value                = false
                    _selectedEffectListNumber.value = null
                }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // toggleEffect
    // ═══════════════════════════════════════════════════════════

    fun toggleEffect(context: Context, effect: UiEffectType) {
        if (_isEffectBlocked.value) {
            _toastMessage.value = "음악 자동 재생 중에는 Effect를 선택할 수 없습니다"
            return
        }
        if (_selectedEffect.value == effect && _isPlaying.value) {
            stopEffect(context)
            _selectedEffect.value = null
            return
        }
        if (controlMode == ControlMode.EXCLUSIVE && _selectedEffectListNumber.value != null) {
            effectListJob?.cancel()
            effectListJob                   = null
            _selectedEffectListNumber.value = null
            _isPlaying.value                = false
        }
        selectEffect(effect)
        playEffect(context, effect)
    }

    // ═══════════════════════════════════════════════════════════
    // Effect 설정
    // ═══════════════════════════════════════════════════════════

    fun selectEffect(effect: UiEffectType) {
        _selectedEffect.value  = effect
        _currentSettings.value = getEffectSettings(effect)
    }

    fun updateColor(context: Context, color: LightStickColor) {
        val current  = _selectedEffect.value ?: return
        val settings = _currentSettings.value.copy(color = color)
        _currentSettings.value = settings
        saveEffectSettings(current, settings)
        if (_isPlaying.value) playEffect(context, current)
    }

    fun updateBackgroundColor(context: Context, color: LightStickColor) {
        val current  = _selectedEffect.value ?: return
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
        val key = EffectKeys.of(effectType)
        effectSettingsMapInternal[key] = settings
        saveSettings(key, settings)
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    fun getEffectSettings(effectType: UiEffectType): EffectSettings {
        val key = EffectKeys.of(effectType)
        return effectSettingsMapInternal[key] ?: EffectSettings.defaultFor(effectType)
    }

    fun getEffectKey(effectType: UiEffectType): String =
        EffectKeys.of(effectType)

    // ═══════════════════════════════════════════════════════════
    // EffectList 선택
    // ═══════════════════════════════════════════════════════════

    fun selectEffectList(context: Context, effectNumber: Int) {
        if (_isEffectBlocked.value) {
            _toastMessage.value = "음악 자동 재생 중에는 Effect List를 선택할 수 없습니다"
            return
        }
        if (_selectedEffectListNumber.value == effectNumber) {
            stopEffect(context)
            _selectedEffectListNumber.value = null
            _selectedEffect.value           = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다"
            return
        }
        if (controlMode == ControlMode.EXCLUSIVE &&
            _isPlaying.value && _selectedEffectListNumber.value == null) {
            viewModelScope.launch { stopEffectUseCase(context = context, effectListJob = null) }
            _isPlaying.value      = false
            _selectedEffect.value = null
        }
        val effect = UiEffectType.EffectList(effectNumber)
        selectEffect(effect)
        playEffect(context, effect)
        _selectedEffectListNumber.value = effectNumber
        _toastMessage.value = "저장된 리스트를 반복 재생합니다"
    }

    fun clearEffectListSelection(context: Context) {
        if (_selectedEffectListNumber.value != null) {
            stopEffect(context)
            _selectedEffect.value           = null
            _selectedEffectListNumber.value = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Custom Effect CRUD
    // ═══════════════════════════════════════════════════════════

    fun canAddCustomEffect(): Boolean =
        _customEffects.value.size < AppConstants.MAX_CUSTOM_EFFECTS

    fun addCustomEffect(name: String, baseType: UiEffectType.BaseEffectType) {
        if (_customEffects.value.size >= AppConstants.MAX_CUSTOM_EFFECTS) {
            _toastMessage.value = "커스텀 이펙트는 최대 ${AppConstants.MAX_CUSTOM_EFFECTS}개까지 추가 가능합니다"
            return
        }
        _customEffects.value += UiEffectType.Custom(
            id = UUID.randomUUID().toString(), baseType = baseType, name = name
        )
        saveCustomEffects()
    }

    fun renameCustomEffect(effect: UiEffectType.Custom, newName: String) {
        _customEffects.value = _customEffects.value.map {
            if (it.id == effect.id) it.copy(name = newName) else it
        }
        saveCustomEffects()
    }

    fun deleteCustomEffect(effect: UiEffectType.Custom) {
        _customEffects.value = _customEffects.value.filter { it.id != effect.id }
        effectSettingsMapInternal.remove(EffectKeys.of(effect))
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        saveCustomEffects()
    }

    private fun loadCustomEffects() {
        val jsonStr = prefs.getString(PrefsKeys.KEY_CUSTOM_EFFECTS, null) ?: return
        try {
            _customEffects.value = json.decodeFromString<List<CustomEffectDto>>(jsonStr).map { dto ->
                UiEffectType.Custom(
                    id       = dto.id,
                    baseType = UiEffectType.BaseEffectType.valueOf(dto.baseType),
                    name     = dto.name
                )
            }
        } catch (e: Exception) { Log.e(TAG, "loadCustomEffects: ${e.message}") }
    }

    private fun saveCustomEffects() {
        try {
            val list = _customEffects.value.map { c ->
                CustomEffectDto(id = c.id, baseType = c.baseType.name, name = c.name)
            }
            prefs.edit { putString(PrefsKeys.KEY_CUSTOM_EFFECTS, json.encodeToString(list)) }
        } catch (e: Exception) {
            Log.e(TAG, "saveCustomEffects: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Preset Colors
    // ═══════════════════════════════════════════════════════════

    fun selectFgPreset(index: Int) { _selectedFgPreset.value = index }
    fun selectBgPreset(index: Int) { _selectedBgPreset.value = index }

    fun updateFgPresetColor(index: Int, color: Color) {
        val c = _fgPresetColors.value.toMutableList().also { it[index] = color }
        _fgPresetColors.value = c; saveFgPresetColors(c)
    }

    fun updateBgPresetColor(index: Int, color: Color) {
        val c = _bgPresetColors.value.toMutableList().also { it[index] = color }
        _bgPresetColors.value = c; saveBgPresetColors(c)
    }

    private fun loadFgPresetColors(): List<Color> = (0..9).map { i ->
        val rgb = prefs.getInt(PrefsKeys.fgPresetKey(i), -1)
        if (rgb != -1) rgbToColor(rgb).toComposeColor() else PresetColors.defaultForegroundPresets[i]
    }

    private fun loadBgPresetColors(): List<Color> = (0..9).map { i ->
        val rgb = prefs.getInt(PrefsKeys.bgPresetKey(i), -1)
        if (rgb != -1) rgbToColor(rgb).toComposeColor() else PresetColors.defaultBackgroundPresets[i]
    }

    private fun saveFgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { i, c -> putInt(PrefsKeys.fgPresetKey(i), colorToRgb(c.toLightStickColor())) }
            apply()
        }
    }

    private fun saveBgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { i, c -> putInt(PrefsKeys.bgPresetKey(i), colorToRgb(c.toLightStickColor())) }
            apply()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════

    fun clearToastMessage() { _toastMessage.value = null }
    fun clearError()         { _errorMessage.value = null }

    private fun loadAllSettings() {
        prefs.all.keys
            .filter {
                it != PrefsKeys.KEY_CUSTOM_EFFECTS &&
                        !it.startsWith(PrefsKeys.KEY_FG_PRESET_PREFIX) &&
                        !it.startsWith(PrefsKeys.KEY_BG_PRESET_PREFIX)
            }
            .forEach { key ->
                try {
                    effectSettingsMapInternal[key] =
                        EffectSettings.fromJson(prefs.getString(key, null) ?: return@forEach)
                } catch (e: Exception) { Log.e(TAG, "loadAllSettings[$key]: ${e.message}") }
            }
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    private fun saveSettings(key: String, settings: EffectSettings) {
        prefs.edit {
            putString(key, settings.toJson())
        }
    }

    private fun colorToRgb(color: LightStickColor): Int =
        (color.r shl 16) or (color.g shl 8) or color.b

    private fun rgbToColor(rgb: Int): LightStickColor = LightStickColor(
        r = (rgb shr 16) and 0xFF,
        g = (rgb shr 8)  and 0xFF,
        b = rgb and 0xFF
    )

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        effectListJob?.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    // Data Classes
    // ═══════════════════════════════════════════════════════════

    data class EffectSettings(
        val color:           LightStickColor = Colors.WHITE,
        val backgroundColor: LightStickColor = Colors.BLACK,
        val period:          Int             = 10,
        val transit:         Int             = 0,
        val randomColor:     Boolean         = false,
        val randomDelay:     Int             = 0,
        val broadcasting:    Boolean         = true
    ) {
        fun toJson(): String = json.encodeToString(
            EffectSettingsDto(
                colorR = color.r,             colorG = color.g,             colorB = color.b,
                bgColorR = backgroundColor.r, bgColorG = backgroundColor.g, bgColorB = backgroundColor.b,
                period = period,              transit = transit,
                randomColor = randomColor,    randomDelay = randomDelay,
                broadcasting = broadcasting
            )
        )

        companion object {
            fun defaultFor(effectType: UiEffectType): EffectSettings = when (effectType) {
                is UiEffectType.On         -> EffectSettings(period = 0,  transit = 50)
                is UiEffectType.Off        -> EffectSettings(period = 0,  transit = 100)
                is UiEffectType.Strobe     -> EffectSettings(period = 10, transit = 0)
                is UiEffectType.Blink      -> EffectSettings(period = 30, transit = 0)
                is UiEffectType.Breath     -> EffectSettings(period = 30, transit = 0)
                is UiEffectType.EffectList -> EffectSettings(period = 0,  transit = 0)
                is UiEffectType.Custom     -> EffectSettings(period = 30, transit = 0)
            }

            fun fromJson(jsonStr: String): EffectSettings {
                val dto = json.decodeFromString<EffectSettingsDto>(jsonStr)
                return EffectSettings(
                    color           = LightStickColor(dto.colorR, dto.colorG, dto.colorB),
                    backgroundColor = LightStickColor(dto.bgColorR, dto.bgColorG, dto.bgColorB),
                    period          = dto.period,       transit      = dto.transit,
                    randomColor     = dto.randomColor,  randomDelay  = dto.randomDelay,
                    broadcasting    = dto.broadcasting
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UiEffectType
    // ═══════════════════════════════════════════════════════════
    sealed class UiEffectType(val displayName: String) {

        data object On     : UiEffectType("ON")
        data object Off    : UiEffectType("OFF")
        data object Strobe : UiEffectType("STROBE")
        data object Blink  : UiEffectType("BLINK")
        data object Breath : UiEffectType("BREATH")

        data class EffectList(
            val number: Int,
            val subName: String = ""
        ) : UiEffectType(
            "EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}"
        )

        data class Custom(
            val id: String,
            val baseType: BaseEffectType,
            val name: String
        ) : UiEffectType(name)

        enum class BaseEffectType(val displayName: String) {
            ON("ON"),
            OFF("OFF"),
            STROBE("STROBE"),
            BLINK("BLINK"),
            BREATH("BREATH")
        }

        fun isConfigurable(): Boolean = this !is EffectList
    }
}