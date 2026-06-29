#!/usr/bin/env python3
"""
BeatDetectorV3 테스트
V1 vs V3 (with Tempogram) 비교
"""

import sys
from pathlib import Path
from odf_engine_v3 import BeatDetectorV3

# MADMOM_GT
MADMOM_GT = {
    "Ed Sheeran - Shape of You (Official Music Video)": 96.77,
    "BLACKPINK - 'Kill This Love' MV": 133.33,
    "Golden": 122.45,
}

def test_v3():
    """V3 테스트"""
    detector = BeatDetectorV3()

    print("=" * 80)
    print("BeatDetectorV3 (Tempogram 기반) 테스트")
    print("=" * 80)

    # 테스트 곡 경로 찾기
    test_songs = [
        "/home/user/Light-Stick-Music/test_songs/Ed Sheeran - Shape of You (Official Music Video).mp3",
        "/home/user/Light-Stick-Music/test_songs/BLACKPINK - 'Kill This Love' MV.mp3",
    ]

    for song_path in test_songs:
        if not Path(song_path).exists():
            print(f"⚠️  파일 없음: {song_path}")
            continue

        song_name = Path(song_path).stem

        print(f"\n🎵 {song_name}")
        print("-" * 80)

        # V3 (Tempogram 사용)
        result_v3 = detector.optimize(
            song_path,
            use_tempogram=True,  # ✨ Tempogram 사용
            normalize_strength=2.0,
            pre_emphasis=0.5,
            compress_ratio=3.5,
            enable_bandpass=True
        )

        if result_v3:
            detected_bpm = result_v3['bpm']
            confidence = result_v3['confidence']

            gt_bpm = MADMOM_GT.get(song_name, None)

            print(f"V3 (Tempogram):")
            print(f"  검출 BPM: {detected_bpm:.2f}")
            print(f"  신뢰도: {confidence:.2%}")  # ✨ 새로운 지표!
            print(f"  Tempogram 형태: {result_v3['tempogram'].shape if result_v3['tempogram'] is not None else 'None'}")

            if gt_bpm:
                error = abs(detected_bpm - gt_bpm) / gt_bpm * 100
                status = "✅" if error <= 1 else "❌"
                print(f"  GT BPM: {gt_bpm:.2f}")
                print(f"  오차율: {error:.1f}% {status}")
        else:
            print("❌ V3 분석 실패")

if __name__ == "__main__":
    test_v3()
