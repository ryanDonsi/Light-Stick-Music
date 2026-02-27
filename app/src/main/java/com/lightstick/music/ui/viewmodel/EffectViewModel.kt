package com.lightstick.music.ui.viewmodel

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
        private const val TAG = AppConstants.Feature.VM_EFFECT
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PrefsKeys.PREFS_EFFECT_SETTINGS, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val playManualEffectUseCase     = PlayManualEffectUseCase()
    private val playEffectListUseCase       = PlayEffectListUseCase()
    private val stopEffectUseCase           = StopEffectUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()
    private val getBondedDevicesUseCase     = GetBondedDevicesUseCase()
    private val startScanUseCase            = StartScanUseCase()
    private val connectDeviceUseCase        = ConnectDeviceUseCase()

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

    private val _playingEffect = MutableStateFlow<UiEffectType?>(null)
    val playingEffect: StateFlow<UiEffectType?> = _playingEffect.asStateFlow()

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

//    init {
//        loadCustomEffects()
//        loadAllSettings()
//        observeDeviceConnection()
//        observeDeviceDisconnect()
//        observeMusicPlaybackState()
//    }

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
    // Device Connection Observer  (연결 감지)
    // ═══════════════════════════════════════════════════════════

    /**
     * 기기 연결 이벤트를 관찰합니다.
     * 연결(Connected) 전환만 처리합니다.
     * 해제(null) 처리는 [observeDeviceDisconnect] 에서 담당합니다.
     */
//    private fun observeDeviceConnection() {
//        viewModelScope.launch {
//            ObserveDeviceStatesUseCase
//                .observeFirstConnectedDevice(preferLsDevice = false)
//                .collect { connectedDevice ->
//                    if (connectedDevice != null) {
//                        val current = _deviceConnectionState.value
//                        val alreadyConnected =
//                            current is DeviceConnectionState.Connected &&
//                                    current.device.mac == connectedDevice.mac
//                        if (!alreadyConnected) {
//                            _deviceConnectionState.value =
//                                DeviceConnectionState.Connected(connectedDevice)
//                            Log.d(TAG, "Device state → Connected: ${connectedDevice.mac}")
//                        }
//                    }
//                    // null 처리는 observeDeviceDisconnect 에서 reason 로그와 함께 처리
//                }
//        }
//    }

    // ═══════════════════════════════════════════════════════════
    // Device Disconnect Observer  (해제 감지)
    // ═══════════════════════════════════════════════════════════

    /**
     * 기기 연결 해제 이벤트를 관찰합니다.
     *
     * Connected → Disconnected 전환 시 호출됩니다.
     * reason은 로그로만 출력하고 상태는 [DeviceConnectionState.Disconnected]로 전환합니다.
     */
//    private fun observeDeviceDisconnect() {
//        viewModelScope.launch {
//            ObserveDeviceStatesUseCase.observeDisconnectEvents()
//                .collect { event ->
//                    // reason 로그 출력
//                    Log.d(TAG, "Disconnect detected: mac=${event.mac} reason=${event.reason}")
//
//                    // Connected 상태일 때만 처리 (중복 이벤트 방지)
//                    if (_deviceConnectionState.value !is DeviceConnectionState.Connected) {
//                        Log.d(TAG, "Disconnect ignored: current state is not Connected")
//                        return@collect
//                    }
//
//                    _deviceConnectionState.value = DeviceConnectionState.Disconnected
//                    Log.d(TAG, "Device state → Disconnected")
//                }
//        }
//    }

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
                    _playingEffect.value            = null
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
                if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                    Log.d(TAG, "Already connected")
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

                connectDeviceUseCase(
                    context     = context,
                    device      = bestDevice,
                    onConnected = {
                        _deviceConnectionState.value = DeviceConnectionState.Connected(bestDevice)
                        Log.d(TAG, "Auto connected: ${bestDevice.mac}")
                        viewModelScope.launch {
                            sendConnectionEffectUseCase(context, bestDevice)
                                .onFailure { Log.e(TAG, "Connection animation failed: ${it.message}") }
                        }
                    },
                    onFailed = { error ->
                        _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                        _errorMessage.value = "연결 실패: ${error.message}"
                        Log.e(TAG, "Connection failed: ${error.message}")
                    }
                ).onFailure { error ->
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.e(TAG, "Connect error: ${error.message}")
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
                        _playingEffect.value = effectType
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
                    _playingEffect.value            = effectType
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
                    _playingEffect.value            = null
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
            _playingEffect.value            = null
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
        val key = getEffectKey(effectType)
        effectSettingsMapInternal[key] = settings
        saveSettings(key, settings)
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
    }

    fun getEffectSettings(effectType: UiEffectType): EffectSettings {
        val key = getEffectKey(effectType)
        return effectSettingsMapInternal[key] ?: EffectSettings.defaultFor(effectType)
    }

    fun getEffectKey(effectType: UiEffectType): String = when (effectType) {
        is UiEffectType.On         -> "on"
        is UiEffectType.Off        -> "off"
        is UiEffectType.Strobe     -> "strobe"
        is UiEffectType.Blink      -> "blink"
        is UiEffectType.Breath     -> "breath"
        is UiEffectType.EffectList -> "effect_list_${effectType.number}"
        is UiEffectType.Custom     -> "custom_${effectType.id}"
    }

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
            _playingEffect.value  = null
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
        _customEffects.value = _customEffects.value + UiEffectType.Custom(
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
        effectSettingsMapInternal.remove(getEffectKey(effect))
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        saveCustomEffects()
    }

    private fun loadCustomEffects() {
        val json = prefs.getString(PrefsKeys.KEY_CUSTOM_EFFECTS, null) ?: return
        try {
            val arr = JSONArray(json)
            _customEffects.value = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { obj ->
                    UiEffectType.Custom(
                        id       = obj.getString("id"),
                        baseType = UiEffectType.BaseEffectType.valueOf(obj.getString("baseType")),
                        name     = obj.getString("name")
                    )
                }
            }
        } catch (e: Exception) { Log.e(TAG, "loadCustomEffects: ${e.message}") }
    }

    private fun saveCustomEffects() {
        try {
            val arr = JSONArray()
            _customEffects.value.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id); put("baseType", c.baseType.name); put("name", c.name)
                })
            }
            prefs.edit().putString(PrefsKeys.KEY_CUSTOM_EFFECTS, arr.toString()).apply()
        } catch (e: Exception) { Log.e(TAG, "saveCustomEffects: ${e.message}") }
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
        prefs.edit().putString(key, settings.toJson()).apply()
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
        fun toJson(): String = JSONObject().apply {
            put("colorR",      color.r);   put("colorG",   color.g);   put("colorB",   color.b)
            put("bgColorR",    backgroundColor.r)
            put("bgColorG",    backgroundColor.g)
            put("bgColorB",    backgroundColor.b)
            put("period",      period);    put("transit",  transit)
            put("randomColor", randomColor); put("randomDelay", randomDelay)
            put("broadcasting", broadcasting)
        }.toString()

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

            fun fromJson(json: String): EffectSettings {
                val o = JSONObject(json)
                return EffectSettings(
                    color           = LightStickColor(o.optInt("colorR",255), o.optInt("colorG",255), o.optInt("colorB",255)),
                    backgroundColor = LightStickColor(o.optInt("bgColorR",0), o.optInt("bgColorG",0), o.optInt("bgColorB",0)),
                    period          = o.optInt("period",10),   transit  = o.optInt("transit",0),
                    randomColor     = o.optBoolean("randomColor",false),
                    randomDelay     = o.optInt("randomDelay",0),
                    broadcasting    = o.optBoolean("broadcasting",true)
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UiEffectType
    // ═══════════════════════════════════════════════════════════

    sealed class UiEffectType(val displayName: String, val description: String) {
        data object On     : UiEffectType("ON",     "LED를 선택한 색상으로 켭니다")
        data object Off    : UiEffectType("OFF",    "LED를 끕니다")
        data object Strobe : UiEffectType("STROBE", "플래시 터지는 효과")
        data object Blink  : UiEffectType("BLINK",  "깜빡이는 효과")
        data object Breath : UiEffectType("BREATH", "숨쉬듯 밝아졌다 어두워지는 효과")

        data class EffectList(val number: Int, val subName: String = "") :
            UiEffectType(
                "EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}",
                "내장 이펙트 리스트 재생"
            )

        data class Custom(val id: String, val baseType: BaseEffectType, val name: String) :
            UiEffectType(name, getDescriptionForBase(baseType))

        enum class BaseEffectType(val displayName: String) {
            ON("ON"), OFF("OFF"), STROBE("STROBE"), BLINK("BLINK"), BREATH("BREATH")
        }

        fun isConfigurable(): Boolean = this !is EffectList

        companion object {
            private fun getDescriptionForBase(b: BaseEffectType) = when (b) {
                BaseEffectType.ON     -> "커스텀 ON 효과"
                BaseEffectType.OFF    -> "커스텀 OFF 효과"
                BaseEffectType.STROBE -> "커스텀 STROBE 효과"
                BaseEffectType.BLINK  -> "커스텀 BLINK 효과"
                BaseEffectType.BREATH -> "커스텀 BREATH 효과"
            }
        }
    }
}