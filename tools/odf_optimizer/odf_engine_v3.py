#!/usr/bin/env python3
"""
V3 ODF 최적화 엔진 (BeatDetectorV3)
- V1의 IIR 필터 기반 ODF 계산 유지
- librosa의 Tempogram 개념 추가
- 시간-BPM 2D 분석으로 더 정확한 BPM 탐지
- 신뢰도(confidence) 점수 제공
"""

import librosa
import numpy as np
import math
from typing import List, Tuple, Optional
from scipy import signal as scipy_signal


class BeatDetectorV3:
    """V1 ODF + Tempogram 기반 BPM 탐지 (신뢰도 포함)"""

    def __init__(self):
        self.current_odf = None
        self.current_audio = None
        self.current_sr = None
        self.current_tempogram = None

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

    def preprocess_audio(self, audio: np.ndarray, sr: int,
                         normalize_strength: float = 1.0,
                         pre_emphasis: float = 0.0,
                         compress_ratio: float = 1.0) -> np.ndarray:
        """음성 전처리"""
        processed = audio.copy()

        if normalize_strength > 0:
            rms = np.sqrt(np.mean(processed ** 2))
            if rms > 1e-6:
                target_rms = 0.1 * normalize_strength
                processed = processed * (target_rms / rms)

        if pre_emphasis > 0:
            emphasized = np.zeros_like(processed)
            emphasized[0] = processed[0]
            for i in range(1, len(processed)):
                emphasized[i] = processed[i] - pre_emphasis * processed[i-1]
            processed = emphasized

        if compress_ratio > 1.0:
            abs_audio = np.abs(processed)
            compressed_env = np.sign(processed) * np.log(1 + compress_ratio * abs_audio)
            processed = compressed_env

        return processed

    def bandpass_filter(self, audio: np.ndarray, sr: int,
                       low_freq: float = 50.0, high_freq: float = 8000.0) -> np.ndarray:
        """Bandpass 필터"""
        try:
            sos = scipy_signal.butter(4, [low_freq, high_freq], 'band', fs=sr, output='sos')
            filtered = scipy_signal.sosfilt(sos, audio)
            return filtered
        except Exception as e:
            print(f"Bandpass 필터 적용 실패: {e}")
            return audio

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
        """양수 차분"""
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
        smoothed = self.moving_average(env, smooth_window)
        diffed = self.positive_diff(smoothed)
        normalized = self.local_normalize_max(diffed, norm_window)
        return normalized

    def local_normalize_mean(self, src: np.ndarray, window_frames: int) -> np.ndarray:
        """배경 제거 + 정규화"""
        if len(src) == 0:
            return np.array([])

        bg_removed = np.zeros_like(src)

        for i in range(len(src)):
            s = max(0, i - window_frames)
            e = min(len(src) - 1, i + window_frames)
            local_mean = np.mean(src[s:e+1])
            bg_removed[i] = max(0.0, src[i] - local_mean)

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

    def iir_filter_envelope(self, audio: np.ndarray, sr: int, hop_ms: int = 50,
                           low_alpha: float = 0.12,
                           mid_lp1_alpha: float = 0.35,
                           mid_lp2_alpha: float = 0.08) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        """IIR 필터로 low/mid/full 엔벨로프 추출"""
        LOW_ALPHA = low_alpha
        MID_LP1_ALPHA = mid_lp1_alpha
        MID_LP2_ALPHA = mid_lp2_alpha

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

        def normalize_env(env):
            env = np.array(env)
            mx = np.max(env) if len(env) > 0 else 0.0
            if mx > 1e-6:
                return np.clip(env / mx, 0, 1)
            return env

        return normalize_env(out_low), normalize_env(out_mid), normalize_env(out_full)

    def compute_multi_band_flux_odf(
        self, low: np.ndarray, mid: np.ndarray, full: np.ndarray,
        smooth_window: int = 3, local_window: int = 60, global_window: int = 80,
        weight_low: float = 1.0, weight_mid: float = 1.8, weight_full: float = 0.8
    ) -> np.ndarray:
        """다중 대역 Flux ODF 계산"""
        low_flux = self.compute_odf(low, smooth_window, local_window)
        mid_flux = self.compute_odf(mid, smooth_window, local_window)
        full_flux = self.compute_odf(full, smooth_window, local_window)

        n = min(len(low_flux), len(mid_flux), len(full_flux))
        combined = low_flux[:n] * weight_low + mid_flux[:n] * weight_mid + full_flux[:n] * weight_full

        return self.local_normalize_mean(combined, global_window)

    def compute_tempogram(self, odf: np.ndarray, hop_ms: int = 50,
                         min_beat_ms: int = 375, max_beat_ms: int = 1000,
                         window_len: int = 384) -> np.ndarray:
        """
        Tempogram 계산: 시간-BPM 2D 표현

        Returns:
            tempogram: (bpm_bins, time_frames) 형태의 2D 배열
                      각 셀 값 = 해당 시간대에서 해당 BPM의 강도 (0.0-1.0)
        """
        min_lag = max(1, min_beat_ms // hop_ms)
        max_lag = max(min_lag + 1, max_beat_ms // hop_ms)

        num_bpms = max_lag - min_lag + 1

        # ODF를 슬라이딩 윈도우로 나누기
        n_frames = len(odf)
        step = max(1, n_frames // (window_len // 2))  # 충분한 시간 프레임

        tempogram = np.zeros((num_bpms, window_len))

        for t_idx in range(window_len):
            # 시간 윈도우 설정
            center = (t_idx * step) + (step // 2)
            window_half = step // 2
            start_frame = max(0, center - window_half)
            end_frame = min(n_frames, center + window_half)

            if start_frame >= end_frame:
                continue

            # 이 시간 윈도우에서 autocorrelation 계산
            window_odf = odf[start_frame:end_frame]

            for lag_idx, lag in enumerate(range(min_lag, max_lag + 1)):
                if lag >= len(window_odf):
                    continue

                # Autocorrelation at this lag
                ac_sum = 0.0
                count = 0
                for i in range(len(window_odf) - lag):
                    ac_sum += window_odf[i] * window_odf[i + lag]
                    count += 1

                if count > 0:
                    ac_val = ac_sum / count
                    tempogram[lag_idx, t_idx] = ac_val

        # 정규화
        max_val = np.max(tempogram)
        if max_val > 1e-6:
            tempogram = tempogram / max_val

        return tempogram

    def find_modal_peak(self, tempogram: np.ndarray, hop_ms: int = 50,
                       min_beat_ms: int = 375) -> Tuple[float, float]:
        """
        Tempogram에서 모달 피크 찾기

        Returns:
            (best_bpm, confidence): 최고 점수의 BPM과 신뢰도 (0.0-1.0)
        """
        min_lag = max(1, min_beat_ms // hop_ms)

        # 각 BPM별로 시간을 통합 (모든 시간대에서의 강도 합산)
        bpm_strengths = np.sum(tempogram, axis=1)

        # 정규화
        max_strength = np.max(bpm_strengths)
        if max_strength > 1e-6:
            bpm_strengths = bpm_strengths / max_strength

        # 최고 강도의 lag 찾기
        best_lag_idx = np.argmax(bpm_strengths)
        best_lag = best_lag_idx + min_lag
        best_bpm = 60000 / (best_lag * hop_ms)

        # 신뢰도: 최고 강도와 2번째 강도의 비율
        sorted_strengths = np.sort(bpm_strengths)
        if len(sorted_strengths) >= 2:
            confidence = sorted_strengths[-1] / (sorted_strengths[-2] + 1e-6)
            confidence = min(1.0, confidence)  # 1.0으로 제한
        else:
            confidence = sorted_strengths[-1] if len(sorted_strengths) > 0 else 0.0

        return best_bpm, confidence

    def detect_bpm_v3(
        self, odf: np.ndarray, hop_ms: int = 50,
        prior_center_ms: int = 500, prior_std_octave: float = 2.0,
        min_beat_ms: int = 375, max_beat_ms: int = 1000,
        half_tempo_ratio: float = 0.60, double_tempo_ratio: float = 0.55,
        use_tempogram: bool = True
    ) -> Tuple[float, float, np.ndarray, np.ndarray, np.ndarray]:
        """
        BeatDetectorV3 BPM 탐지

        Returns:
            (bpm, confidence, ac_vals, prior_vals, score_vals)
        """
        min_lag = max(1, min_beat_ms // hop_ms)
        max_lag = max(min_lag + 1, max_beat_ms // hop_ms)

        if len(odf) <= max_lag + 2:
            return None, 0.0, None, None, None

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

        # Tempogram을 사용하는 경우
        if use_tempogram:
            tempogram = self.compute_tempogram(odf, hop_ms, min_beat_ms, max_beat_ms)
            self.current_tempogram = tempogram

            best_bpm, confidence = self.find_modal_peak(tempogram, hop_ms, min_beat_ms)
        else:
            # V1 방식: 최고 점수 선택
            best_lag = np.argmax(score_vals[min_lag:max_lag+1]) + min_lag
            best_ac = ac_vals[best_lag]

            # Half-tempo check
            half_lag = best_lag // 2
            if half_lag >= min_lag and best_ac > 1e-6:
                half_ac = ac_vals[half_lag]
                if half_ac / best_ac >= half_tempo_ratio:
                    best_lag = half_lag

            # Double-tempo check
            double_lag = best_lag * 2
            if double_lag <= max_lag and best_ac > 1e-6:
                double_ac = ac_vals[double_lag]
                if double_ac / best_ac >= double_tempo_ratio:
                    best_lag = double_lag

            best_bpm = 60000 / (best_lag * hop_ms)

            # V1 방식 신뢰도: AC 값 기반
            confidence = min(1.0, best_ac * 10)  # AC 값을 0-1로 스케일

        return best_bpm, confidence, ac_vals, prior_vals, score_vals

    def optimize(
        self, file_path: str,
        smooth_window: int = 3,
        local_window: int = 60,
        global_window: int = 80,
        prior_center_ms: int = 500,
        prior_std_octave: float = 2.0,
        min_beat_ms: int = 375,
        max_beat_ms: int = 1000,
        hop_ms: int = 50,
        half_tempo_ratio: float = 0.60,
        double_tempo_ratio: float = 0.55,
        use_tempogram: bool = True,
        # 음성 전처리 파라미터
        sample_rate: int = 28000,
        normalize_strength: float = 1.0,
        pre_emphasis: float = 0.0,
        compress_ratio: float = 1.0,
        enable_bandpass: bool = False,
        bandpass_low: float = 50.0,
        bandpass_high: float = 8000.0,
        # IIR 필터 계수
        low_alpha: float = 0.12,
        mid_lp1_alpha: float = 0.35,
        mid_lp2_alpha: float = 0.08,
        # ODF 대역 가중치
        weight_low: float = 1.0,
        weight_mid: float = 1.8,
        weight_full: float = 0.8
    ) -> dict:
        """파일에서 ODF 계산 및 BPM 탐지 (V3: Tempogram 지원)"""
        try:
            audio, sr = self.load_audio(file_path, sr=sample_rate)
            if audio is None:
                return None

            # 음성 전처리
            audio = self.preprocess_audio(
                audio, sr,
                normalize_strength=normalize_strength,
                pre_emphasis=pre_emphasis,
                compress_ratio=compress_ratio
            )

            # Bandpass 필터 적용
            if enable_bandpass:
                audio = self.bandpass_filter(audio, sr, bandpass_low, bandpass_high)

            # IIR 필터로 엔벨로프 추출
            low, mid, full = self.iir_filter_envelope(
                audio, sr, hop_ms,
                low_alpha=low_alpha,
                mid_lp1_alpha=mid_lp1_alpha,
                mid_lp2_alpha=mid_lp2_alpha
            )

            # ODF 계산
            odf = self.compute_multi_band_flux_odf(
                low, mid, full,
                smooth_window=smooth_window,
                local_window=local_window,
                global_window=global_window,
                weight_low=weight_low,
                weight_mid=weight_mid,
                weight_full=weight_full
            )

            self.current_odf = odf

            # BPM 탐지 (V3)
            bpm, confidence, ac_vals, prior_vals, score_vals = self.detect_bpm_v3(
                odf, hop_ms=hop_ms,
                prior_center_ms=prior_center_ms,
                prior_std_octave=prior_std_octave,
                min_beat_ms=min_beat_ms,
                max_beat_ms=max_beat_ms,
                half_tempo_ratio=half_tempo_ratio,
                double_tempo_ratio=double_tempo_ratio,
                use_tempogram=use_tempogram
            )

            return {
                'bpm': bpm,
                'confidence': confidence,  # ✨ 새로 추가!
                'odf': odf,
                'ac_vals': ac_vals,
                'prior_vals': prior_vals,
                'score_vals': score_vals,
                'tempogram': self.current_tempogram,  # ✨ 새로 추가!
                'duration_s': len(audio) / sr
            }

        except Exception as e:
            print(f"최적화 실패: {e}")
            return None


if __name__ == "__main__":
    detector = BeatDetectorV3()
    print("BeatDetectorV3 엔진 로드됨")
