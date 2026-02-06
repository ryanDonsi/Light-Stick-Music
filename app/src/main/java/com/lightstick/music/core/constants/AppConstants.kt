package com.lightstick.music.core.constants

/**
 * 앱 전역 상수 관리
 *
 * 매직 넘버를 방지하고 중요한 설정값을 한 곳에서 관리
 */
object AppConstants {

    // ═══════════════════════════════════════════════════════════
    // BLE Scan Configuration
    // ═══════════════════════════════════════════════════════════

    /** 기본 스캔 지속 시간 (밀리초) */
    const val DEFAULT_SCAN_DURATION_MS = 3000L

    /** 스캔 타임아웃 (밀리초) */
    const val SCAN_TIMEOUT_MS = 30000L

    /** 디바이스 이름 필터 (LS로 끝나는 디바이스) */
    const val DEVICE_NAME_FILTER_SUFFIX = "LS"

    // ═══════════════════════════════════════════════════════════
    // BLE Connection Configuration
    // ═══════════════════════════════════════════════════════════

    /** 연결 타임아웃 (밀리초) */
    const val CONNECTION_TIMEOUT_MS = 10000L

    /** 연결 재시도 횟수 */
    const val CONNECTION_RETRY_COUNT = 3

    /** 연결 재시도 딜레이 (밀리초) */
    const val CONNECTION_RETRY_DELAY_MS = 1000L

    // ═══════════════════════════════════════════════════════════
    // Effect Configuration
    // ═══════════════════════════════════════════════════════════

    /** Manual Effect 반복 간격 (밀리초) */
    const val MANUAL_EFFECT_INTERVAL_MS = 1000L

    /** Timeline Effect 최소 간격 (밀리초) */
    const val TIMELINE_EFFECT_MIN_INTERVAL_MS = 100L

    /** FFT Effect 처리 간격 (밀리초) */
    const val FFT_EFFECT_INTERVAL_MS = 16L

    /** Custom Effect 최대 개수 */
    const val MAX_CUSTOM_EFFECTS = 7

    // ═══════════════════════════════════════════════════════════
    // Music Player Configuration
    // ═══════════════════════════════════════════════════════════

    /** 위치 모니터링 간격 (밀리초) */
    const val POSITION_MONITOR_INTERVAL_MS = 100L

    /** Seek backward 감지 임계값 (밀리초) */
    const val SEEK_BACKWARD_THRESHOLD_MS = 1000L

    /** Seek forward 감지 임계값 (밀리초) */
    const val SEEK_FORWARD_THRESHOLD_MS = 10000L

    // ═══════════════════════════════════════════════════════════
    // UI Configuration
    // ═══════════════════════════════════════════════════════════

    /** Toast 표시 시간 (밀리초) */
    const val TOAST_DURATION_SHORT_MS = 2000L
    const val TOAST_DURATION_LONG_MS = 3500L

    /** Animation 지속 시간 (밀리초) */
    const val ANIMATION_DURATION_SHORT_MS = 200L
    const val ANIMATION_DURATION_MEDIUM_MS = 300L
    const val ANIMATION_DURATION_LONG_MS = 500L

    /** 글라스모피즘 코너 반경 (dp) */
    const val GLASSMORPHISM_CORNER_RADIUS_DP = 20

    /** 최소 터치 영역 크기 (dp) */
    const val MIN_TOUCH_TARGET_SIZE_DP = 48

    // ═══════════════════════════════════════════════════════════
    // Storage Configuration
    // ═══════════════════════════════════════════════════════════

    /** 캐시 파일 최대 크기 (bytes) */
    const val MAX_CACHE_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5MB

    /** 앨범아트 캐시 최대 개수 */
    const val MAX_ALBUM_ART_CACHE_COUNT = 100

    // ═══════════════════════════════════════════════════════════
    // BLE Transmission Monitor Configuration
    // ═══════════════════════════════════════════════════════════

    /** 전송 히스토리 최대 개수 */
    const val MAX_TRANSMISSION_HISTORY = 100

    /** 전송 모니터 업데이트 간격 (밀리초) */
    const val TRANSMISSION_MONITOR_UPDATE_INTERVAL_MS = 50L

    // ═══════════════════════════════════════════════════════════
    // File Extensions
    // ═══════════════════════════════════════════════════════════

    const val EFX_FILE_EXTENSION = "efx"
    const val MP3_FILE_EXTENSION = "mp3"
    const val WAV_FILE_EXTENSION = "wav"
    const val FLAC_FILE_EXTENSION = "flac"

    /** 지원하는 음악 파일 확장자 목록 */
    val SUPPORTED_AUDIO_EXTENSIONS = setOf(
        MP3_FILE_EXTENSION,
        WAV_FILE_EXTENSION,
        FLAC_FILE_EXTENSION,
        "m4a",
        "aac",
        "ogg"
    )

    // ═══════════════════════════════════════════════════════════
    // Debug Configuration
    // ═══════════════════════════════════════════════════════════

    /** 디버그 모드 활성화 여부 */
    const val DEBUG_MODE = true

    /** 상세 로그 출력 여부 */
    const val VERBOSE_LOGGING = true

    /** BLE 패킷 로깅 여부 */
    const val LOG_BLE_PACKETS = false
}