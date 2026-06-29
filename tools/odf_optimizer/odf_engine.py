#!/usr/bin/env python3
"""
V1 ODF 최적화 엔진
- Kotlin BeatDetectorV1의 ODF 계산 로직을 Python으로 재현
- librosa를 사용해 음성 파일 처리
"""

import librosa
import numpy as np
import math
from typing import List, Tuple, Optional

class ODFOptimizer:
    """ODF 계산 및 BPM 탐지"""

    def __init__(self):
        self.current_odf = None
        self.current_audio = None
        self.current_sr = None

    def load_audio(self, file_path: str, sr: int = 28000) -> Tuple[np.ndarray, int]:
        """음성 파일 로드"""
        try:
            audio, sr = librosa.load(file_path, sr=sr, mono=True)
            self.current_audio = audio
            self.current_sr = sr
            return audio, sr
        except Exception as e:
            print(f"오디오 로드 실패: {e}")
            return None, None

    def moving_average(self, src: np.ndarray, window: int) -> np.ndarray:
        """이동 평균"""
        if len(src) == 0 or window <= 1:
            return src.copy()

        out = np.zeros_like(src)
        half = window // 2

        for i in range(len(src)):
            s = max(0, i - half)
            e = min(len(src) - 1, i + half)
            out[i] = np.mean(src[s:e+1])

        return out

    def positive_diff(self, src: np.ndarray) -> np.ndarray:
        """양수 차분 (상승만)"""
        if len(src) == 0:
            return np.array([])

        out = np.zeros_like(src)
        out[0] = 0.0

        for i in range(1, len(src)):
            out[i] = max(0.0, src[i] - src[i-1])

        return out

    def local_normalize_max(self, src: np.ndarray, window_frames: int) -> np.ndarray:
        """로컬 최댓값 정규화"""
        if len(src) == 0:
            return np.array([])

        out = np.zeros_like(src)

        for i in range(len(src)):
            s = max(0, i - window_frames)
            e = min(len(src) - 1, i + window_frames)
            local_max = np.max(src[s:e+1])

            if local_max > 1e-6:
                out[i] = np.clip(src[i] / local_max, 0, 1)
            else:
                out[i] = 0.0

        return out

    def compute_odf(self, env: np.ndarray, smooth_window: int, norm_window: int) -> np.ndarray:
        """단일 대역 ODF 계산"""
        # 1. Moving average (smoothing)
        smoothed = self.moving_average(env, smooth_window)

        # 2. Positive diff
        diffed = self.positive_diff(smoothed)

        # 3. Local normalize max
        normalized = self.local_normalize_max(diffed, norm_window)

        return normalized

    def local_normalize_mean(self, src: np.ndarray, window_frames: int) -> np.ndarray:
        """배경 제거 + 정규화"""
        if len(src) == 0:
            return np.array([])

        # Step 1: 로컬 평균 제거
        bg_removed = np.zeros_like(src)

        for i in range(len(src)):
            s = max(0, i - window_frames)
            e = min(len(src) - 1, i + window_frames)
            local_mean = np.mean(src[s:e+1])
            bg_removed[i] = max(0.0, src[i] - local_mean)

        # Step 2: 로컬 최댓값 정규화
        out = np.zeros_like(bg_removed)

        for i in range(len(bg_removed)):
            s = max(0, i - window_frames)
            e = min(len(bg_removed) - 1, i + window_frames)
            local_max = np.max(bg_removed[s:e+1])

            if local_max > 1e-6:
                out[i] = np.clip(bg_removed[i] / local_max, 0, 1)
            else:
                out[i] = 0.0

        return out

    def iir_filter_envelope(self, audio: np.ndarray, sr: int, hop_ms: int = 50) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        """IIR 필터로 low/mid/full 엔벨로프 추출"""
        LOW_ALPHA = 0.12
        MID_LP1_ALPHA = 0.35
        MID_LP2_ALPHA = 0.08

        hop_samples = max(1, int(sr * hop_ms / 1000))
        num_frames = len(audio) // hop_samples

        out_low = []
        out_mid = []
        out_full = []

        low_z = 0.0
        mid_lp1 = 0.0
        mid_lp2 = 0.0
        low_sum_sq = 0.0
        mid_sum_sq = 0.0
        full_sum_sq = 0.0
        win_pos = 0

        for sample in audio:
            low_z += LOW_ALPHA * (sample - low_z)
            mid_lp1 += MID_LP1_ALPHA * (sample - mid_lp1)
            mid_lp2 += MID_LP2_ALPHA * (sample - mid_lp2)

            low_val = abs(low_z)
            mid_val = abs(mid_lp1 - mid_lp2)

            low_sum_sq += low_val * low_val
            mid_sum_sq += mid_val * mid_val
            full_sum_sq += sample * sample

            win_pos += 1

            if win_pos >= hop_samples:
                out_low.append(math.sqrt(low_sum_sq / win_pos))
                out_mid.append(math.sqrt(mid_sum_sq / win_pos))
                out_full.append(math.sqrt(full_sum_sq / win_pos))

                low_sum_sq = 0.0
                mid_sum_sq = 0.0
                full_sum_sq = 0.0
                win_pos = 0

        # 정규화
        def normalize_env(env):
            env = np.array(env)
            mx = np.max(env) if len(env) > 0 else 0.0
            if mx > 1e-6:
                return np.clip(env / mx, 0, 1)
            return env

        return normalize_env(out_low), normalize_env(out_mid), normalize_env(out_full)

    def compute_multi_band_flux_odf(
        self, low: np.ndarray, mid: np.ndarray, full: np.ndarray,
        smooth_window: int = 3, local_window: int = 60, global_window: int = 80
    ) -> np.ndarray:
        """다중 대역 Flux ODF 계산"""
        # 각 대역의 ODF 계산
        low_flux = self.compute_odf(low, smooth_window, local_window)
        mid_flux = self.compute_odf(mid, smooth_window, local_window)
        full_flux = self.compute_odf(full, smooth_window, local_window)

        # 가중 결합
        n = min(len(low_flux), len(mid_flux), len(full_flux))
        combined = low_flux[:n] * 1.0 + mid_flux[:n] * 1.8 + full_flux[:n] * 0.8

        # 최종 정규화
        return self.local_normalize_mean(combined, global_window)

    def detect_bpm(
        self, odf: np.ndarray, hop_ms: int = 50,
        prior_center_ms: int = 500, prior_std_octave: float = 2.0,
        min_beat_ms: int = 375, max_beat_ms: int = 1000
    ) -> Tuple[float, np.ndarray, np.ndarray, np.ndarray]:
        """BPM 탐지 (AC + Prior 스코어)"""
        min_lag = max(1, min_beat_ms // hop_ms)
        max_lag = max(min_lag + 1, max_beat_ms // hop_ms)

        if len(odf) <= max_lag + 2:
            return None, None, None, None

        ac_vals = np.zeros(max_lag + 1)
        prior_vals = np.zeros(max_lag + 1)
        score_vals = np.zeros(max_lag + 1)

        # Autocorrelation 계산
        for lag in range(min_lag, max_lag + 1):
            ac_sum = 0.0
            for i in range(len(odf) - lag):
                ac_sum += odf[i] * odf[i + lag]

            ac_val = ac_sum / (len(odf) - lag)
            ac_vals[lag] = ac_val

            # Log-normal prior
            lag_ms = lag * hop_ms
            log_ratio = math.log(lag_ms / prior_center_ms) / math.log(2)
            prior = math.exp(-0.5 * (log_ratio / prior_std_octave) ** 2)

            prior_vals[lag] = prior
            score_vals[lag] = ac_val * prior

        # 최고 점수 찾기
        best_lag = np.argmax(score_vals[min_lag:max_lag+1]) + min_lag
        best_bpm = 60000 / (best_lag * hop_ms)

        return best_bpm, ac_vals, prior_vals, score_vals

    def optimize(
        self, file_path: str,
        smooth_window: int = 3,
        local_window: int = 60,
        global_window: int = 80,
        prior_center_ms: int = 500,
        prior_std_octave: float = 2.0,
        min_beat_ms: int = 375,
        max_beat_ms: int = 1000,
        hop_ms: int = 50
    ) -> dict:
        """파일에서 ODF 계산 및 BPM 탐지"""
        try:
            audio, sr = self.load_audio(file_path, sr=28000)
            if audio is None:
                return None

            # IIR 필터로 엔벨로프 추출
            low, mid, full = self.iir_filter_envelope(audio, sr, hop_ms)

            # ODF 계산
            odf = self.compute_multi_band_flux_odf(
                low, mid, full,
                smooth_window=smooth_window,
                local_window=local_window,
                global_window=global_window
            )

            self.current_odf = odf

            # BPM 탐지
            bpm, ac_vals, prior_vals, score_vals = self.detect_bpm(
                odf, hop_ms=hop_ms,
                prior_center_ms=prior_center_ms,
                prior_std_octave=prior_std_octave,
                min_beat_ms=min_beat_ms,
                max_beat_ms=max_beat_ms
            )

            return {
                'bpm': bpm,
                'odf': odf,
                'ac_vals': ac_vals,
                'prior_vals': prior_vals,
                'score_vals': score_vals,
                'duration_s': len(audio) / sr
            }

        except Exception as e:
            print(f"최적화 실패: {e}")
            return None


if __name__ == "__main__":
    # 테스트
    optimizer = ODFOptimizer()
    print("ODF Optimizer 엔진 로드됨")
