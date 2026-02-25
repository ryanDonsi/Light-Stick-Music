package com.lightstick.music.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.lightstick.music.core.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        private const val TAG = AppConstants.Feature.VM_EFFECT
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PrefsKeys.PREFS_EFFECT_SETTINGS, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // UseCase 인스턴스
    // ═══════════════════════════════════════════════════════════

    private val playManualEffectUseCase    = PlayManualEffectUseCase()
    private val playEffectListUseCase      = PlayEffectListUseCase()
    private val stopEffectUseCase          = StopEffectUseCase()
    private val sendConnectionEffectUseCase = SendConnectionEffectUseCase()
    private val getBondedDevicesUseCase    = GetBondedDevicesUseCase()
    private val startScanUseCase           = StartScanUseCase()
    private val connectDeviceUseCase       = ConnectDeviceUseCase()

    // ═══════════════════════════════════════════════════════════
    // Control Mode
    // ═══════════════════════════════════════════════════════════

    /**
     * Effect 제어 모드
     *
     * [EXCLUSIVE] - Effect 와 PlayList 는 동시에 선택될 수 없습니다.
     *   - Effect 선택 중 PlayList 선택 → Effect 자동 해제
     *   - PlayList 재생 중 Effect 선택 → PlayList 자동 해제
     *   - AutoMode + 음악 재생 중 → Effect/PlayList 선택 잠금
     */
    enum class ControlMode { EXCLUSIVE }

    // 현재 앱은 항상 EXCLUSIVE 모드로 동작합니다.
    private val controlMode = ControlMode.EXCLUSIVE

    // ═══════════════════════════════════════════════════════════
    // State Flows
    // ═══════════════════════════════════════════════════════════

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // PlayList 선택 번호 - default null (설정 해제 상태)
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
        data object Scanning       : DeviceConnectionState()
        data object ScanFailed     : DeviceConnectionState()
        data class  Connected(val device: Device) : DeviceConnectionState()
    }

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

    // ── [추가] Effect 선택/재생 잠금 상태 ────────────────────────
    // EXCLUSIVE 모드에서 음악 재생 중 + AutoMode 활성화 시 true 로 설정됩니다.
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
        observeDeviceConnection()
        observeMusicPlaybackState()
    }

    // ═══════════════════════════════════════════════════════════
    // [추가] 음악 재생 상태 관찰 → Effect 잠금 처리
    // ═══════════════════════════════════════════════════════════

    /**
     * MusicPlaybackState 를 관찰하여 EXCLUSIVE 모드에서
     * 음악 재생 중 + AutoMode 활성화 시 Effect 선택을 차단합니다.
     *
     * 잠금이 해제되면 현재 선택/재생 중인 Effect 상태는 유지됩니다.
     */
    private fun observeMusicPlaybackState() {
        viewModelScope.launch {
            MusicPlaybackState.isPlayingWithAutoMode.collect { playingWithAutoMode ->
                val blocked = playingWithAutoMode && controlMode == ControlMode.EXCLUSIVE
                _isEffectBlocked.value = blocked
                Log.d(TAG, "Effect blocked: $blocked (EXCLUSIVE mode, music playing with AutoMode)")

                // 잠금 시작 시 현재 진행 중인 Effect/PlayList 도 즉시 중지
                if (blocked) {
                    effectListJob?.cancel()
                    effectListJob = null
                    _selectedEffectListNumber.value = null
                    _selectedEffect.value           = null
                    _isPlaying.value                = false
                    _playingEffect.value            = null
                    Log.d(TAG, "Effect/PlayList stopped by music AutoMode lock")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Device Connection Observer
    // ═══════════════════════════════════════════════════════════

    private fun observeDeviceConnection() {
        Log.d(TAG, "observeDeviceConnection() started")

        viewModelScope.launch {
            ObserveDeviceStatesUseCase.observeFirstConnectedDevice(preferLsDevice = false)
                .collect { connectedDevice ->
                    Log.d(TAG, "Connected device: ${connectedDevice?.mac ?: "none"}")

                    if (connectedDevice != null) {
                        val currentState   = _deviceConnectionState.value
                        val alreadyConnected = currentState is DeviceConnectionState.Connected &&
                                currentState.device.mac == connectedDevice.mac

                        if (!alreadyConnected) {
                            _deviceConnectionState.value =
                                DeviceConnectionState.Connected(connectedDevice)
                            Log.d(TAG, "Device state → Connected: ${connectedDevice.mac}")
                        }
                    } else {
                        if (_deviceConnectionState.value is DeviceConnectionState.Connected) {
                            _deviceConnectionState.value = DeviceConnectionState.NoBondedDevice
                            Log.d(TAG, "Device state → NoBondedDevice")
                        }
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
                    Log.d(TAG, "No bonded devices")
                    return@launch
                }
                Log.d(TAG, "Found ${bondedDevices.size} bonded device(s)")

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

                val scannedDevices = scanResult.getOrNull() ?: emptyList()
                val bestDevice     = scannedDevices
                    .filter { it.rssi != null }
                    .maxByOrNull { it.rssi!! }

                if (bestDevice == null) {
                    _deviceConnectionState.value = DeviceConnectionState.ScanFailed
                    Log.d(TAG, "No suitable device found during scan")
                    return@launch
                }

                Log.d(TAG, "Best device: ${bestDevice.mac} RSSI=${bestDevice.rssi}")

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
                    val result = playEffectListUseCase(
                        context        = context,
                        effectListNumber = effectType.number,
                        coroutineScope = viewModelScope
                    )

                    result.onSuccess { job ->
                        effectListJob?.cancel()
                        effectListJob  = job
                        _playingEffect.value = effectType
                        _isPlaying.value     = true
                        Log.d(TAG, "EffectList play started: ${effectType.displayName}")
                    }.onFailure { error ->
                        _errorMessage.value = "EffectList 재생 실패: ${error.message}"
                        Log.e(TAG, "playEffect error: ${error.message}")
                    }
                    return@launch
                }

                val result = playManualEffectUseCase(
                    context    = context,
                    effectType = effectType,
                    settings   = settings
                )

                result.onSuccess {
                    _playingEffect.value             = effectType
                    _isPlaying.value                 = true
                    _selectedEffectListNumber.value  = null
                    Log.d(TAG, "Effect sent: ${effectType.displayName}")
                }.onFailure { error ->
                    _errorMessage.value = "Effect 전송 실패: ${error.message}"
                    Log.e(TAG, "playEffect error: ${error.message}")
                }

            } catch (e: Exception) {
                _errorMessage.value = "Effect 전송 실패: ${e.message}"
                Log.e(TAG, "playEffect error: ${e.message}")
            }
        }
    }

    fun stopEffect(context: Context) {
        viewModelScope.launch {
            try {
                val result = stopEffectUseCase(context = context, effectListJob = effectListJob)
                result.onSuccess {
                    effectListJob   = null
                    _playingEffect.value            = null
                    _isPlaying.value                = false
                    _selectedEffectListNumber.value  = null
                    Log.d(TAG, "Effect stopped")
                }.onFailure { error ->
                    Log.e(TAG, "stopEffect error: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopEffect error: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // [추가] toggleEffect - 요구사항 1, 2, 3 통합 처리
    // ═══════════════════════════════════════════════════════════

    /**
     * Effect 카드 클릭 시 호출합니다.
     *
     * ### 처리 순서
     * 1. [요구사항 3] EXCLUSIVE + AutoMode + 음악 재생 중 → 선택 차단 (toast 표시)
     * 2. [요구사항 1] 이미 선택된 Effect 재클릭 → 선택 해제 & Effect 중지
     * 3. [요구사항 2-a] EXCLUSIVE 모드에서 PlayList 재생 중 → PlayList 즉시 해제 후 Effect 재생
     * 4. 새로운 Effect 선택 & 재생
     */
    fun toggleEffect(context: Context, effect: UiEffectType) {
        // [요구사항 3] 음악 AutoMode 잠금 확인
        if (_isEffectBlocked.value) {
            _toastMessage.value = "음악 자동 재생 중에는 Effect를 선택할 수 없습니다"
            Log.d(TAG, "toggleEffect blocked: music playing with AutoMode")
            return
        }

        // [요구사항 1] 동일 Effect 재클릭 → 선택 해제
        if (_selectedEffect.value == effect && _isPlaying.value) {
            stopEffect(context)
            _selectedEffect.value = null
            Log.d(TAG, "Effect deselected: ${effect.displayName}")
            return
        }

        // [요구사항 2-a] EXCLUSIVE: PlayList 재생 중이면 즉시 취소 후 Effect 재생
        if (controlMode == ControlMode.EXCLUSIVE && _selectedEffectListNumber.value != null) {
            effectListJob?.cancel()
            effectListJob = null
            _selectedEffectListNumber.value = null
            _isPlaying.value                = false
            _playingEffect.value            = null
            Log.d(TAG, "EXCLUSIVE: PlayList cleared before playing Effect")
        }

        selectEffect(effect)
        playEffect(context, effect)
    }

    // ═══════════════════════════════════════════════════════════
    // Effect 설정
    // ═══════════════════════════════════════════════════════════

    fun selectEffect(effect: UiEffectType) {
        _selectedEffect.value   = effect
        _currentSettings.value  = getEffectSettings(effect)
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
    // EffectList 선택 (EXCLUSIVE 로직 포함)
    // ═══════════════════════════════════════════════════════════

    /**
     * EffectList 카드 클릭 시 호출합니다.
     *
     * ### 처리 순서
     * 1. [요구사항 3] EXCLUSIVE + AutoMode + 음악 재생 중 → 선택 차단
     * 2. 동일 PlayList 재클릭 → 선택 해제 (기본 해제 상태)
     * 3. [요구사항 2-b] EXCLUSIVE 모드에서 Effect 재생 중 → Effect 먼저 중지 후 PlayList 재생
     * 4. 새로운 PlayList 선택 & 재생
     */
    fun selectEffectList(context: Context, effectNumber: Int) {
        // [요구사항 3] 음악 AutoMode 잠금 확인
        if (_isEffectBlocked.value) {
            _toastMessage.value = "음악 자동 재생 중에는 Effect List를 선택할 수 없습니다"
            Log.d(TAG, "selectEffectList blocked: music playing with AutoMode")
            return
        }

        // 동일 PlayList 토글 → 해제 (default = 설정 해제 상태)
        if (_selectedEffectListNumber.value == effectNumber) {
            stopEffect(context)
            _selectedEffectListNumber.value = null
            _selectedEffect.value           = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다"
            Log.d(TAG, "PlayList $effectNumber deselected")
            return
        }

        // [요구사항 2-b] EXCLUSIVE: Effect 선택 중이면 먼저 중지
        if (controlMode == ControlMode.EXCLUSIVE && _isPlaying.value && _selectedEffectListNumber.value == null) {
            // EffectList 가 아닌 일반 Effect 재생 중인 경우
            viewModelScope.launch {
                stopEffectUseCase(context = context, effectListJob = null)
                    .onSuccess { Log.d(TAG, "EXCLUSIVE: Effect stopped before PlayList") }
            }
            _isPlaying.value     = false
            _playingEffect.value = null
            _selectedEffect.value = null
        }

        // PlayList 재생 시작
        val effect = UiEffectType.EffectList(effectNumber)
        selectEffect(effect)
        playEffect(context, effect)
        _selectedEffectListNumber.value = effectNumber
        _toastMessage.value = "저장된 리스트를 반복 재생합니다"
        Log.d(TAG, "PlayList $effectNumber selected")
    }

    /**
     * PlayList 선택 강제 해제 (Sheet 닫기 버튼 등에서 호출)
     */
    fun clearEffectListSelection(context: Context) {
        if (_selectedEffectListNumber.value != null) {
            stopEffect(context)
            _selectedEffect.value           = null
            _selectedEffectListNumber.value = null
            _toastMessage.value = "리스트 반복 재생을 중지합니다"
            Log.d(TAG, "PlayList selection cleared")
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
        val newEffect = UiEffectType.Custom(
            id       = UUID.randomUUID().toString(),
            baseType = baseType,
            name     = name
        )
        _customEffects.value = _customEffects.value + newEffect
        saveCustomEffects()
        Log.d(TAG, "Custom effect added: $name")
    }

    fun renameCustomEffect(effect: UiEffectType.Custom, newName: String) {
        _customEffects.value = _customEffects.value.map {
            if (it.id == effect.id) it.copy(name = newName) else it
        }
        saveCustomEffects()
        Log.d(TAG, "Custom effect renamed: ${effect.name} → $newName")
    }

    fun deleteCustomEffect(effect: UiEffectType.Custom) {
        _customEffects.value = _customEffects.value.filter { it.id != effect.id }
        effectSettingsMapInternal.remove(getEffectKey(effect))
        _effectSettingsMap.value = effectSettingsMapInternal.toMap()
        saveCustomEffects()
        Log.d(TAG, "Custom effect deleted: ${effect.name}")
    }

    private fun loadCustomEffects() {
        val json = prefs.getString(PrefsKeys.KEY_CUSTOM_EFFECTS, null) ?: return
        try {
            val jsonArray = JSONArray(json)
            val effects   = mutableListOf<UiEffectType.Custom>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                effects.add(
                    UiEffectType.Custom(
                        id       = obj.getString("id"),
                        baseType = UiEffectType.BaseEffectType.valueOf(obj.getString("baseType")),
                        name     = obj.getString("name")
                    )
                )
            }
            _customEffects.value = effects
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom effects: ${e.message}")
        }
    }

    private fun saveCustomEffects() {
        try {
            val jsonArray = JSONArray()
            _customEffects.value.forEach { custom ->
                jsonArray.put(JSONObject().apply {
                    put("id",       custom.id)
                    put("baseType", custom.baseType.name)
                    put("name",     custom.name)
                })
            }
            prefs.edit().apply {
                putString(PrefsKeys.KEY_CUSTOM_EFFECTS, jsonArray.toString())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom effects: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Preset Colors
    // ═══════════════════════════════════════════════════════════

    fun selectFgPreset(index: Int) { _selectedFgPreset.value = index }
    fun selectBgPreset(index: Int) { _selectedBgPreset.value = index }

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
            val rgb = prefs.getInt(PrefsKeys.fgPresetKey(index), -1)
            if (rgb != -1) rgbToColor(rgb).toComposeColor()
            else PresetColors.defaultForegroundPresets[index]
        }
    }

    private fun loadBgPresetColors(): List<Color> {
        return (0..9).map { index ->
            val rgb = prefs.getInt(PrefsKeys.bgPresetKey(index), -1)
            if (rgb != -1) rgbToColor(rgb).toComposeColor()
            else PresetColors.defaultBackgroundPresets[index]
        }
    }

    private fun saveFgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { index, color ->
                putInt(PrefsKeys.fgPresetKey(index), colorToRgb(color.toLightStickColor()))
            }
            apply()
        }
    }

    private fun saveBgPresetColors(colors: List<Color>) {
        prefs.edit().apply {
            colors.forEachIndexed { index, color ->
                putInt(PrefsKeys.bgPresetKey(index), colorToRgb(color.toLightStickColor()))
            }
            apply()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════

    fun clearToastMessage() { _toastMessage.value = null }
    fun clearError()         { _errorMessage.value = null }

    private fun loadAllSettings() {
        val allKeys = prefs.all.keys.filter {
            it != PrefsKeys.KEY_CUSTOM_EFFECTS &&
                    !it.startsWith(PrefsKeys.KEY_FG_PRESET_PREFIX) &&
                    !it.startsWith(PrefsKeys.KEY_BG_PRESET_PREFIX)
        }
        allKeys.forEach { key ->
            try {
                val json     = prefs.getString(key, null) ?: return@forEach
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
        Log.d(TAG, "EffectViewModel cleared")
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
            put("colorR",      color.r)
            put("colorG",      color.g)
            put("colorB",      color.b)
            put("bgColorR",    backgroundColor.r)
            put("bgColorG",    backgroundColor.g)
            put("bgColorB",    backgroundColor.b)
            put("period",      period)
            put("transit",     transit)
            put("randomColor", randomColor)
            put("randomDelay", randomDelay)
            put("broadcasting",broadcasting)
        }.toString()

        companion object {
            fun defaultFor(effectType: UiEffectType): EffectSettings = when (effectType) {
                is UiEffectType.On         -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 0,  transit = 50,  randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.Off        -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 0,  transit = 100, randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.Strobe     -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 10, transit = 0,   randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.Blink      -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 30, transit = 0,   randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.Breath     -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 30, transit = 0,   randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.EffectList -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 0,  transit = 0,   randomColor = false, randomDelay = 0, broadcasting = true)
                is UiEffectType.Custom     -> EffectSettings(color = Colors.WHITE, backgroundColor = Colors.BLACK, period = 30, transit = 0,   randomColor = false, randomDelay = 0, broadcasting = true)
            }

            fun fromJson(json: String): EffectSettings {
                val obj = JSONObject(json)
                return EffectSettings(
                    color           = LightStickColor(r = obj.optInt("colorR", 255),   g = obj.optInt("colorG", 255),  b = obj.optInt("colorB", 255)),
                    backgroundColor = LightStickColor(r = obj.optInt("bgColorR", 0),   g = obj.optInt("bgColorG", 0),  b = obj.optInt("bgColorB", 0)),
                    period          = obj.optInt("period", 10),
                    transit         = obj.optInt("transit", 0),
                    randomColor     = obj.optBoolean("randomColor", false),
                    randomDelay     = obj.optInt("randomDelay", 0),
                    broadcasting    = obj.optBoolean("broadcasting", true)
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
        data object Strobe : UiEffectType("STROBE", "플래시 터지는 효과 (period: 0~255)")
        data object Blink  : UiEffectType("BLINK",  "깜빡이는 효과 (period: 0~255)")
        data object Breath : UiEffectType("BREATH", "숨쉬듯 밝아졌다 어두워지는 효과 (period: 0~255)")

        data class EffectList(val number: Int, val subName: String = "") :
            UiEffectType(
                displayName = "EFFECT LIST $number${if (subName.isNotEmpty()) " ($subName)" else ""}",
                description = "내장 이펙트 리스트 재생"
            )

        data class Custom(
            val id:       String,
            val baseType: BaseEffectType,
            val name:     String
        ) : UiEffectType(name, getDescriptionForBase(baseType))

        enum class BaseEffectType(val displayName: String) {
            ON("ON"), OFF("OFF"), STROBE("STROBE"), BLINK("BLINK"), BREATH("BREATH")
        }

        companion object {
            private fun getDescriptionForBase(baseType: BaseEffectType): String = when (baseType) {
                BaseEffectType.ON     -> "커스텀 ON 효과"
                BaseEffectType.OFF    -> "커스텀 OFF 효과"
                BaseEffectType.STROBE -> "커스텀 STROBE 효과"
                BaseEffectType.BLINK  -> "커스텀 BLINK 효과"
                BaseEffectType.BREATH -> "커스텀 BREATH 효과"
            }
        }
    }
}