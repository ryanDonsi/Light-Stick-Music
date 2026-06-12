package com.lightstick.music.core.constants

import com.lightstick.music.core.ble.ControlMode

/**
 * 앱 전역 상수 관리
 *
 * 매직 넘버를 방지하고 중요한 설정값을 한 곳에서 관리합니다.
 */
object AppConstants {

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

    const val DEVICE_SCAN_DURATION_MS = 30_000L
    const val EFFECT_SCAN_DURATION_MS = 3_000L

    /** 배터리 모니터링 주기 - 펌웨어 BAS Notification 미지원으로 인한 Polling 방식 (30초) */
    const val BATTERY_MONITOR_INTERVAL_MS = 30_000L

    const val MAX_CUSTOM_EFFECTS = 7

    const val POSITION_MONITOR_INTERVAL_MS = 50L

    const val MAX_TRANSMISSION_HISTORY              = 100
    const val TRANSMISSION_MONITOR_UPDATE_INTERVAL_MS = 50L

    const val EFX_FILE_EXTENSION  = "efx"
    const val MP3_FILE_EXTENSION  = "mp3"
    const val WAV_FILE_EXTENSION  = "wav"
    const val FLAC_FILE_EXTENSION = "flac"

    val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "aac", "mp4")

    object Feature {

        /** LightStickMusicApp.kt */
        const val APP                   = "App"

        /** MainActivity.kt */
        const val ACTIVITY_MAIN         = "MainActivity"

        /** DeviceViewModel.kt */
        const val VM_DEVICE             = "DeviceVM"
        /** EffectViewModel.kt */
        const val VM_EFFECT             = "EffectVM"
        /** MusicViewModel.kt */
        const val VM_MUSIC              = "MusicVM"

        /** BleTransmissionCoordinator.kt */
        const val BLE_COORDINATOR       = "BleCoordinator"
        /** BleTransmissionMonitor.kt */
        const val BLE_MONITOR           = "BleMonitor"

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
        /** GetBondedDevicesUseCase.kt */
        const val UC_GET_BONDED         = "UC_GetBonded"
        /** SendFindEffectUseCase.kt */
        const val UC_FIND_EFFECT        = "UC_FindEffect"
        /** SendConnectionEffectUseCase.kt */
        const val UC_CONNECTION_EFFECT  = "UC_ConnectionEffect"

        /** PlayManualEffectUseCase.kt */
        const val UC_PLAY_MANUAL        = "UC_PlayManual"
        /** PlayEffectListUseCase.kt */
        const val UC_PLAY_EFFECT_LIST   = "UC_PlayEffectList"
        /** StopEffectUseCase.kt */
        const val UC_STOP_EFFECT        = "UC_StopEffect"

        /** LoadEfxUseCase.kt */
        const val UC_LOAD_TIMELINE      = "UC_LoadEfx"
        /** UpdatePlaybackPositionUseCase.kt */
        const val UC_UPDATE_POSITION    = "UC_UpdatePosition"
        /** HandleSeekUseCase.kt */
        const val UC_HANDLE_SEEK        = "UC_HandleSeek"
        /** ProcessFFTUseCase.kt */
        const val UC_PROCESS_FFT        = "UC_ProcessFFT"

        /** DeviceEventEffectSender.kt */
        const val DEVICE_EVENT_SENDER      = "DeviceEventSender"
        /** EventNotificationListenerService.kt */
        const val NOTIFICATION_LISTENER    = "EventNotifListener"

        /** GameViewModel.kt */
        const val VM_GAME               = "GameVM"
        /** GameBleManager.kt */
        const val GAME_BLE_MANAGER      = "GameBleManager"

        /** EffectEngineController.kt */
        const val EFFECT_ENGINE         = "EffectEngine"
        /** MusicEffectManager.kt */
        const val MUSIC_EFFECT_MANAGER  = "MusicEffectMgr"
        /** FftAudioProcessor.kt */
        const val FFT_PROCESSOR         = "FftProcessor"
        const val AUTO_TIMELINE         = "AutoTimeline"

        /** EffectPathPreferences.kt */
        const val STORAGE_EFFECT_PATH   = "EffectDirMgr"
        /** DevicePreferences.kt */
        const val PREFS_DEVICE          = "DevicePrefs"
        /** AutoModePreferences.kt */
        const val PREFS_AUTO_MODE       = "AutoModePrefs"

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

        Feature.APP,
        Feature.ACTIVITY_MAIN,

        Feature.VM_DEVICE,
        Feature.VM_EFFECT,
        Feature.VM_MUSIC,

        Feature.BLE_COORDINATOR,
        Feature.BLE_MONITOR,

        Feature.UC_OBSERVE_DEVICE,
        Feature.UC_START_SCAN,
        Feature.UC_STOP_SCAN,
        Feature.UC_CONNECT,
        Feature.UC_DISCONNECT,
        Feature.UC_GET_BONDED,
        Feature.UC_FIND_EFFECT,
        Feature.UC_CONNECTION_EFFECT,

        Feature.UC_PLAY_MANUAL,
        Feature.UC_PLAY_EFFECT_LIST,
        Feature.UC_STOP_EFFECT,

        Feature.UC_LOAD_TIMELINE,
        Feature.UC_UPDATE_POSITION,
        Feature.UC_HANDLE_SEEK,
        Feature.UC_PROCESS_FFT,

        Feature.EFFECT_ENGINE,
        Feature.MUSIC_EFFECT_MANAGER,
        Feature.FFT_PROCESSOR,
        Feature.AUTO_TIMELINE,

        Feature.STORAGE_EFFECT_PATH,
        Feature.PREFS_DEVICE,
        Feature.PREFS_AUTO_MODE,

        Feature.UTIL_FILE,
        Feature.UTIL_SAF,
        Feature.PERMISSION_MANAGER,
        Feature.COMMAND_BUS,
        Feature.SERVICE_CONTROLLER,

        Feature.GAME_BLE_MANAGER,
        Feature.VM_GAME,

        Feature.DEVICE_EVENT_SENDER,
        Feature.NOTIFICATION_LISTENER,
    )
}
