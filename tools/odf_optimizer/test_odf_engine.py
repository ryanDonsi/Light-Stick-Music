#!/usr/bin/env python3
"""
ODF Engine 단위 테스트
GUI 없이 ODF 엔진의 기본 기능을 검증합니다.
"""

import sys
from pathlib import Path

try:
    from odf_engine import ODFOptimizer
    import numpy as np
except ImportError as e:
    print(f"❌ 의존성 오류: {e}")
    print("설치 명령: pip install -r requirements.txt")
    sys.exit(1)


def test_dummy_audio():
    """더미 오디오로 ODF 엔진 기본 기능 테스트"""
    print("=" * 80)
    print("테스트 1: ODF 엔진 기본 기능")
    print("=" * 80)

    optimizer = ODFOptimizer()

    # 테스트용 더미 오디오 생성 (1초, 28kHz)
    sr = 28000
    duration_s = 1.0
    num_samples = int(sr * duration_s)

    # 120 BPM 신호 생성 (0.5초 간격, 2Hz)
    beat_freq = 2.0  # 120 BPM / 60
    t = np.arange(num_samples) / sr
    audio = np.sin(2 * np.pi * beat_freq * t).astype(np.float32)

    # 펄스 신호로 변환 (onset 강화)
    pulse_width = int(sr * 0.1)  # 100ms 펄스
    for i in range(0, num_samples, int(sr * 0.5)):  # 0.5초마다
        audio[i:i+pulse_width] += 1.0

    optimizer.current_audio = audio
    optimizer.current_sr = sr

    print(f"✅ 더미 오디오 생성: {duration_s}초, {sr}Hz")
    print(f"   샘플 수: {num_samples}")

    # IIR 필터링
    print("\n[IIR 필터링]")
    low, mid, full = optimizer.iir_filter_envelope(audio, sr, hop_ms=50)
    print(f"✅ 저역 엔벨로프: {len(low)} 프레임")
    print(f"✅ 중역 엔벨로프: {len(mid)} 프레임")
    print(f"✅ 전역 엔벨로프: {len(full)} 프레임")

    # ODF 계산
    print("\n[ODF 계산]")
    odf = optimizer.compute_multi_band_flux_odf(
        low, mid, full,
        smooth_window=3,
        local_window=60,
        global_window=80
    )
    print(f"✅ ODF 계산: {len(odf)} 프레임")
    print(f"   최대값: {np.max(odf):.4f}")
    print(f"   평균값: {np.mean(odf):.4f}")

    # BPM 탐지
    print("\n[BPM 탐지]")
    bpm, ac_vals, prior_vals, score_vals = optimizer.detect_bpm(
        odf, hop_ms=50,
        prior_center_ms=500,
        prior_std_octave=2.0,
        min_beat_ms=375,
        max_beat_ms=1000
    )

    if bpm:
        print(f"✅ 검출 BPM: {bpm:.1f}")
        print(f"   예상 BPM (120): 차이 {abs(bpm - 120):.1f}")
    else:
        print(f"⚠️  BPM 탐지 실패")

    print(f"✅ AC 값: {len(ac_vals)} 개")
    print(f"   최대 AC: {np.max(ac_vals):.6f}")

    print("\n✅ 기본 기능 테스트 완료\n")


def test_real_audio():
    """실제 음성 파일로 테스트"""
    print("=" * 80)
    print("테스트 2: 실제 음성 파일 처리")
    print("=" * 80)

    # 테스트 음성 파일 찾기
    test_files = [
        Path("/home/user/Music").glob("*.mp3"),
        Path("/home/user/Downloads").glob("*.mp3"),
        Path("/home/user/Desktop").glob("*.mp3"),
    ]

    audio_file = None
    for file_list in test_files:
        for f in file_list:
            audio_file = f
            break
        if audio_file:
            break

    if not audio_file:
        print("⚠️  테스트 음성 파일을 찾을 수 없습니다")
        print("   /home/user/Music 또는 다른 위치에 MP3 파일을 추가하세요")
        return

    print(f"테스트 파일: {audio_file}")

    optimizer = ODFOptimizer()

    try:
        result = optimizer.optimize(str(audio_file), hop_ms=50)

        if result:
            print(f"✅ 파일 처리 성공")
            print(f"   검출 BPM: {result['bpm']:.1f}")
            print(f"   음성 길이: {result['duration_s']:.1f}초")
            print(f"   ODF 프레임: {len(result['odf'])}")
        else:
            print(f"❌ 파일 처리 실패")

    except Exception as e:
        print(f"❌ 오류: {e}")

    print()


def main():
    print("\n🚀 ODF Optimizer Engine 테스트\n")

    test_dummy_audio()
    test_real_audio()

    print("=" * 80)
    print("테스트 완료")
    print("=" * 80)
    print("\n설치 다음 단계:")
    print("1. GUI 실행: python3 gui_app.py")
    print("2. 음성 파일 등록 및 분석")
    print("3. 매개변수 조정 및 최적화")
    print()


if __name__ == "__main__":
    main()
