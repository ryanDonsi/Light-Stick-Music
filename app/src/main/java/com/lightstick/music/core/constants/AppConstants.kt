package com.lightstick.music.core.constants

import com.lightstick.music.core.ble.ControlMode

/**
 * 앱 전역 상수 관리
 *
 * 매직 넘버를 방지하고 중요한 설정값을 한 곳에서 관리합니다.
 */
object AppConstants {

    // ═══════════════════════════════════════════════════════════
    // App Policy Configuration
    //
    // 앱 전체 동작 정책을 정의합니다.
    // ViewModel 이 아닌 앱 수준에서 초기화되어야 하는 값들입니다.
    // ═══════════════════════════════════════════════════════════

    /**
     * Effect 제어 모드 기본값 (앱 전체 정책)
     *
     * [ControlMode.EXCLUSIVE]  - Effect 와 PlayList 는 동시에 선택될 수 없습니다.
     *   - Effect 선택 중 PlayList 선택 → Effect 자동 해제
     *   - PlayList 재생 중 Effect 선택 → PlayList 자동 해제
     *   - AutoMode + 음악 재생 중 → Effect/PlayList 선택 잠금
     *
     * [ControlMode.COOPERATIVE] - 특정 소스와 협력하여 동시에 전송 가능
     * [ControlMode.BACKGROUND]  - 우선순위 높은 소스가 있으면 자동 양보
     *
     * @see ControlMode
     */
    val EFFECT_CONTROL_MODE: ControlMode = ControlMode.EXCLUSIVE



    // ═══════════════════════════════════════════════════════════
    // BLE Scan Configuration
    // ═══════════════════════════════════════════════════════════

    const val DEVICE_SCAN_DURATION_MS   = 30_000L
    const val EFFECT_SCAN_DURATION_MS   = 3_000L
    const val DEVICE_NAME_FILTER_SUFFIX = "LS"

    // ═══════════════════════════════════════════════════════════
    // BLE Connection Configuration
    // ═══════════════════════════════════════════════════════════

    const val CONNECTION_TIMEOUT_MS = 10_000L

    // ═══════════════════════════════════════════════════════════
    // Effect Configuration
    // ═══════════════════════════════════════════════════════════

    const val MANUAL_EFFECT_INTERVAL_MS = 1_000L
    const val MAX_CUSTOM_EFFECTS        = 7

    // ═══════════════════════════════════════════════════════════
    // Music Player Configuration
    // ═══════════════════════════════════════════════════════════

    const val POSITION_MONITOR_INTERVAL_MS = 100L

    // ═══════════════════════════════════════════════════════════
    // BLE Transmission Monitor Configuration
    // ═══════════════════════════════════════════════════════════

    const val MAX_TRANSMISSION_HISTORY              = 100
    const val TRANSMISSION_MONITOR_UPDATE_INTERVAL_MS = 50L

    // ═══════════════════════════════════════════════════════════
    // UI Configuration
    // ═══════════════════════════════════════════════════════════

    const val TOAST_DURATION_SHORT_MS = 2_000L
    const val TOAST_DURATION_LONG_MS  = 3_500L

    // ═══════════════════════════════════════════════════════════
    // File Extensions
    // ═══════════════════════════════════════════════════════════

    const val EFX_FILE_EXTENSION  = "efx"
    const val MP3_FILE_EXTENSION  = "mp3"
    const val WAV_FILE_EXTENSION  = "wav"
    const val FLAC_FILE_EXTENSION = "flac"

    val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg")

    // ═══════════════════════════════════════════════════════════
    // Feature Log TAG 정의
    //
    // 기존 raw string TAG 와의 매핑:
    //   "LightStickApp"            → Feature.APP
    //   "MainActivity"             → Feature.ACTIVITY_MAIN
    //   "DeviceVM"                 → Feature.VM_DEVICE
    //   "EffectViewModel"          → Feature.VM_EFFECT
    //   "MusicPlayerVM"            → Feature.VM_MUSIC
    //   "BleCoordinator"           → Feature.BLE_COORDINATOR
    //   "BleTransmissionMonitor"   → Feature.BLE_MONITOR
    //   "ObserveDeviceStatesUseCase" → Feature.UC_OBSERVE_DEVICE
    //   "SendConnectionEffectUseCase" → Feature.UC_CONNECTION_EFFECT
    //   "FileHelper"               → Feature.UTIL_FILE
    //   "SafHelper"                → Feature.UTIL_SAF
    //   "EffectDirManager"         → Feature.STORAGE_EFFECT_PATH
    // ═══════════════════════════════════════════════════════════

    object Feature {

        // ── Application ───────────────────────────────────────
        /** LightStickMusicApp.kt */
        const val APP                   = "App"

        // ── Activity ──────────────────────────────────────────
        /** MainActivity.kt */
        const val ACTIVITY_MAIN         = "MainActivity"

        // ── ViewModel ─────────────────────────────────────────
        /** DeviceViewModel.kt */
        const val VM_DEVICE             = "DeviceVM"
        /** EffectViewModel.kt */
        const val VM_EFFECT             = "EffectVM"
        /** MusicViewModel.kt */
        const val VM_MUSIC              = "MusicVM"

        // ── BLE Core ──────────────────────────────────────────
        /** BleTransmissionCoordinator.kt */
        const val BLE_COORDINATOR       = "BleCoordinator"
        /** BleTransmissionMonitor.kt */
        const val BLE_MONITOR           = "BleMonitor"

        // ── UseCase - Device ──────────────────────────────────
        /** ObserveDeviceStatesUseCase.kt */
        const val UC_OBSERVE_DEVICE     = "UC_ObserveDevice"
        /** StartScanUseCase.kt */
        const val UC_START_SCAN         = "UC_StartScan"
        /** StopScanUseCase.kt */
        const val UC_STOP_SCAN          = "UC_StopScan"
        /** ConnectDeviceUseCase.kt */
        const val UC_CONNECT            = "UC_Connect"
        /** DisconnectDeviceUseCase.kt */
        const val UC_DISCONNECT         = "UC_Disconnect"
        /** GetConnectedDevicesUseCase.kt */
        const val UC_GET_CONNECTED      = "UC_GetConnected"
        /** GetBondedDevicesUseCase.kt */
        const val UC_GET_BONDED         = "UC_GetBonded"
        /** SendFindEffectUseCase.kt */
        const val UC_FIND_EFFECT        = "UC_FindEffect"
        /** SendConnectionEffectUseCase.kt */
        const val UC_CONNECTION_EFFECT  = "UC_ConnectionEffect"
        /** RegisterEventRulesUseCase.kt */
        const val UC_REGIST_EVENTRULES  = "UC_RegisterEventRules"


        // ── UseCase - Effect ──────────────────────────────────
        /** PlayManualEffectUseCase.kt */
        const val UC_PLAY_MANUAL        = "UC_PlayManual"
        /** PlayEffectListUseCase.kt */
        const val UC_PLAY_EFFECT_LIST   = "UC_PlayEffectList"
        /** StopEffectUseCase.kt */
        const val UC_STOP_EFFECT        = "UC_StopEffect"

        // ── UseCase - Music ───────────────────────────────────
        /** LoadMusicTimelineUseCase.kt */
        const val UC_LOAD_TIMELINE      = "UC_LoadTimeline"
        /** UpdatePlaybackPositionUseCase.kt */
        const val UC_UPDATE_POSITION    = "UC_UpdatePosition"
        /** HandleSeekUseCase.kt */
        const val UC_HANDLE_SEEK        = "UC_HandleSeek"
        /** ProcessFFTUseCase.kt */
        const val UC_PROCESS_FFT        = "UC_ProcessFFT"

        // ── Domain ────────────────────────────────────────────
        /** EffectEngineController.kt */
        const val EFFECT_ENGINE         = "EffectEngine"
        /** MusicEffectManager.kt */
        const val MUSIC_EFFECT_MANAGER  = "MusicEffectMgr"
        /** FftAudioProcessor.kt */
        const val FFT_PROCESSOR         = "FftProcessor"

        // ── Data / Storage ────────────────────────────────────
        /** EffectPathPreferences.kt */
        const val STORAGE_EFFECT_PATH   = "EffectDirMgr"
        /** DevicePreferences.kt */
        const val PREFS_DEVICE          = "DevicePrefs"
        /** AutoModePreferences.kt */
        const val PREFS_AUTO_MODE       = "AutoModePrefs"

        // ── Core / Util ───────────────────────────────────────
        /** FileHelper.kt */
        const val UTIL_FILE             = "FileHelper"
        /** SafHelper.kt */
        const val UTIL_SAF              = "SafHelper"
        /** PermissionManager.kt */
        const val PERMISSION_MANAGER    = "PermissionMgr"
        /** MusicPlayerCommandBus.kt */
        const val COMMAND_BUS           = "CommandBus"
        /** ServiceController.kt */
        const val SERVICE_CONTROLLER    = "ServiceCtrl"
    }

    // ═══════════════════════════════════════════════════════════
    // Log Configuration
    // ═══════════════════════════════════════════════════════════

    /**
     * 전체 로그 출력 여부
     * - true  : LOG_ENABLED_FEATURES 화이트리스트에 따라 출력
     * - false : E 레벨을 제외한 모든 로그 차단 (릴리즈 배포 시)
     */
    const val LOG_ENABLED = true

    /**
     * Verbose 로그 출력 여부
     * - true  : V 레벨 출력 허용
     * - false : V 레벨 차단 (D/I/W/E 는 유지)
     */
    const val LOG_VERBOSE_ENABLED = false

    /**
     * Feature 별 로그 화이트리스트
     *
     * 여기 포함된 TAG 만 로그가 출력됩니다.
     * 디버깅 시 필요한 Feature 만 남기고 나머지를 주석 처리하세요.
     *
     * 예시 - BLE 디버깅 시:
     *   Feature.BLE_COORDINATOR, Feature.BLE_MONITOR 만 남기고 나머지 주석 처리
     *
     * 예시 - 음악 재생 디버깅 시:
     *   Feature.VM_MUSIC, Feature.UC_LOAD_TIMELINE, Feature.UC_PROCESS_FFT 만 남기기
     */
    val LOG_ENABLED_FEATURES: Set<String> = setOf(

        // ── Application / Activity ────────────────────────────
        Feature.APP,
        Feature.ACTIVITY_MAIN,

        // ── ViewModel ─────────────────────────────────────────
        Feature.VM_DEVICE,
        Feature.VM_EFFECT,
        Feature.VM_MUSIC,

        // ── BLE Core ──────────────────────────────────────────
        Feature.BLE_COORDINATOR,
        Feature.BLE_MONITOR,

        // ── UseCase - Device ──────────────────────────────────
        Feature.UC_OBSERVE_DEVICE,
        Feature.UC_START_SCAN,
        Feature.UC_STOP_SCAN,
        Feature.UC_CONNECT,
        Feature.UC_DISCONNECT,
        Feature.UC_GET_CONNECTED,
        Feature.UC_GET_BONDED,
        Feature.UC_FIND_EFFECT,
        Feature.UC_CONNECTION_EFFECT,
        Feature.UC_REGIST_EVENTRULES,

        // ── UseCase - Effect ──────────────────────────────────
        Feature.UC_PLAY_MANUAL,
        Feature.UC_PLAY_EFFECT_LIST,
        Feature.UC_STOP_EFFECT,

        // ── UseCase - Music ───────────────────────────────────
        Feature.UC_LOAD_TIMELINE,
        Feature.UC_UPDATE_POSITION,
        Feature.UC_HANDLE_SEEK,
        Feature.UC_PROCESS_FFT,

        // ── Domain ────────────────────────────────────────────
        Feature.EFFECT_ENGINE,
        Feature.MUSIC_EFFECT_MANAGER,
        Feature.FFT_PROCESSOR,

        // ── Data / Storage ────────────────────────────────────
        Feature.STORAGE_EFFECT_PATH,
        Feature.PREFS_DEVICE,
        Feature.PREFS_AUTO_MODE,

        // ── Core / Util ───────────────────────────────────────
        Feature.UTIL_FILE,
        Feature.UTIL_SAF,
        Feature.PERMISSION_MANAGER,
        Feature.COMMAND_BUS,
        Feature.SERVICE_CONTROLLER,
    )
}