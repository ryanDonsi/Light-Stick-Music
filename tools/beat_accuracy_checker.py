"""
Beat Accuracy Checker
=====================
MP3/WAV 원곡 + 라이트스틱 타임라인 바이너리를 선택하여
librosa 기준 F-measure 정확도를 측정하는 도구.

사용법:
    pip install librosa numpy scipy
    python beat_accuracy_checker.py

바이너리 포맷 (Android 기록 기준):
    version   : Int  (4 bytes, big-endian)
    frameCount: Int  (4 bytes, big-endian)
    frames[]:
        timeMs   : Long (8 bytes, big-endian)
        payloadLen: Int  (4 bytes, big-endian)
        payload  : bytes (payloadLen bytes)
"""

import struct
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext
import threading
import os

try:
    import numpy as np
    import librosa
    HAS_LIBROSA = True
except ImportError:
    HAS_LIBROSA = False

# ──────────────────────────────────────────────
# 바이너리 파서
# ──────────────────────────────────────────────

def parse_timeline_binary(path: str):
    with open(path, "rb") as f:
        data = f.read()
    offset = 0
    version     = struct.unpack_from(">i", data, offset)[0]; offset += 4
    frame_count = struct.unpack_from(">i", data, offset)[0]; offset += 4
    beat_times_ms = []
    for _ in range(frame_count):
        if offset + 12 > len(data):
            break
        time_ms     = struct.unpack_from(">q", data, offset)[0]; offset += 8
        payload_len = struct.unpack_from(">i", data, offset)[0]; offset += 4
        offset += payload_len
        beat_times_ms.append(time_ms)
    return version, frame_count, beat_times_ms


# ──────────────────────────────────────────────
# F-measure
# ──────────────────────────────────────────────

def fmeasure(ref_sec, est_sec, tolerance=0.070):
    ref = np.sort(np.array(ref_sec, dtype=float))
    est = np.sort(np.array(est_sec, dtype=float))
    tp = 0
    used_ref = set()
    for e in est:
        diffs = np.abs(ref - e)
        idx = int(np.argmin(diffs))
        if diffs[idx] <= tolerance and idx not in used_ref:
            tp += 1
            used_ref.add(idx)
    fp = len(est) - tp
    fn = len(ref) - tp
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall    = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f = (2 * precision * recall / (precision + recall)
         if (precision + recall) > 0 else 0.0)
    return f, precision, recall, tp, fp, fn


def beat_stats(beat_times_ms):
    if len(beat_times_ms) < 2:
        return {}
    intervals = np.diff(beat_times_ms).astype(float)
    median_ms = float(np.median(intervals))
    bpm = 60_000.0 / median_ms if median_ms > 0 else 0
    gaps  = int(np.sum(intervals > 600))
    short = int(np.sum(intervals < 100))
    return {
        "count"      : len(beat_times_ms),
        "median_ms"  : median_ms,
        "bpm"        : bpm,
        "max_gap"    : float(np.max(intervals)),
        "gaps_600"   : gaps,
        "short_100"  : short,
        "anomaly_pct": round((gaps + short) / max(1, len(intervals)) * 100, 1),
    }


def grade_info(f_score):
    """F-measure → (등급, 색상, 한줄 평가)"""
    pct = f_score * 100
    if pct >= 85:
        return "S", "#2e7d32", "librosa / madmom급 — 실제 서비스에 즉시 적용 가능"
    elif pct >= 75:
        return "A", "#1565c0", "상용 서비스 수준 — 대부분의 K-pop 곡에서 정상 동작"
    elif pct >= 60:
        return "B", "#f57f17", "실용 가능 — 일부 복잡한 곡에서 오류 발생"
    elif pct >= 45:
        return "C", "#bf360c", "개선 필요 — 템포는 맞으나 타이밍 오류 잦음"
    else:
        return "D", "#b71c1c", "비트 감지 실패 수준 — 알고리즘 재검토 필요"


# ──────────────────────────────────────────────
# GUI
# ──────────────────────────────────────────────

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Beat Accuracy Checker")
        self.resizable(True, True)
        self.minsize(700, 560)
        self._build_ui()

    def _build_ui(self):
        pad = dict(padx=8, pady=4)

        # ── 파일 선택 ──────────────────────────────
        frame_files = tk.LabelFrame(self, text="파일 선택", **pad)
        frame_files.pack(fill="x", **pad)

        tk.Label(frame_files, text="원곡 (MP3/WAV/FLAC):").grid(row=0, column=0, sticky="w", **pad)
        self.audio_var = tk.StringVar()
        tk.Entry(frame_files, textvariable=self.audio_var, width=58).grid(row=0, column=1, **pad)
        tk.Button(frame_files, text="찾기", command=self._pick_audio).grid(row=0, column=2, **pad)

        tk.Label(frame_files, text="타임라인 바이너리:").grid(row=1, column=0, sticky="w", **pad)
        self.bin_var = tk.StringVar()
        tk.Entry(frame_files, textvariable=self.bin_var, width=58).grid(row=1, column=1, **pad)
        tk.Button(frame_files, text="찾기", command=self._pick_bin).grid(row=1, column=2, **pad)

        # ── 설정 ───────────────────────────────────
        frame_opt = tk.LabelFrame(self, text="설정", **pad)
        frame_opt.pack(fill="x", **pad)

        tk.Label(frame_opt, text="허용 오차 (ms):").grid(row=0, column=0, sticky="w", **pad)
        self.tol_var = tk.IntVar(value=70)
        tk.Spinbox(frame_opt, from_=20, to=200, increment=10,
                   textvariable=self.tol_var, width=6).grid(row=0, column=1, sticky="w", **pad)
        tk.Label(frame_opt, text="  ※ 업계 표준 70ms").grid(row=0, column=2, sticky="w")

        tk.Label(frame_opt, text="librosa BPM 힌트 (0=자동):").grid(row=0, column=3, sticky="w", **pad)
        self.bpm_hint_var = tk.DoubleVar(value=0.0)
        tk.Entry(frame_opt, textvariable=self.bpm_hint_var, width=8).grid(row=0, column=4, sticky="w", **pad)

        # ── 등급 패널 (결과 전 빈 상태) ─────────────
        self.grade_frame = tk.Frame(self, bd=2, relief="groove")
        self.grade_frame.pack(fill="x", padx=8, pady=2)
        self.grade_label  = tk.Label(self.grade_frame, text="",
                                     font=("", 28, "bold"), width=3)
        self.grade_label.pack(side="left", padx=16)
        self.grade_detail = tk.Label(self.grade_frame, text="분석 전",
                                     font=("", 13), justify="left", anchor="w")
        self.grade_detail.pack(side="left", fill="x", expand=True)

        # ── 실행 버튼 ──────────────────────────────
        self.run_btn = tk.Button(self, text="▶  분석 시작",
                                 font=("", 12, "bold"),
                                 bg="#4CAF50", fg="white",
                                 command=self._run)
        self.run_btn.pack(pady=4)

        # ── 결과 로그 ──────────────────────────────
        frame_out = tk.LabelFrame(self, text="상세 결과", **pad)
        frame_out.pack(fill="both", expand=True, **pad)
        self.out = scrolledtext.ScrolledText(frame_out, font=("Courier", 10), state="disabled")
        self.out.pack(fill="both", expand=True)

        # 상태바
        self.status_var = tk.StringVar(value="파일을 선택하고 [분석 시작]을 누르세요.")
        tk.Label(self, textvariable=self.status_var, anchor="w",
                 relief="sunken").pack(fill="x", side="bottom")

    # ── 파일 선택 다이얼로그 ───────────────────────

    def _pick_audio(self):
        p = filedialog.askopenfilename(
            title="원곡 파일 선택",
            filetypes=[("Audio", "*.mp3 *.wav *.flac *.ogg *.m4a"), ("All", "*.*")])
        if p:
            self.audio_var.set(p)

    def _pick_bin(self):
        p = filedialog.askopenfilename(
            title="타임라인 바이너리 선택",
            filetypes=[("Binary", "*.bin"), ("All", "*.*")])
        if p:
            self.bin_var.set(p)

    # ── 로그 헬퍼 ──────────────────────────────────

    def _log(self, text):
        self.out.config(state="normal")
        self.out.insert("end", text + "\n")
        self.out.see("end")
        self.out.config(state="disabled")

    def _clear_log(self):
        self.out.config(state="normal")
        self.out.delete("1.0", "end")
        self.out.config(state="disabled")

    def _set_grade_panel(self, grade, color, detail):
        self.grade_label.config(text=grade, fg=color)
        self.grade_detail.config(text=detail, fg=color)

    # ── 분석 실행 ──────────────────────────────────

    def _run(self):
        audio_path = self.audio_var.get().strip()
        bin_path   = self.bin_var.get().strip()

        if not audio_path or not os.path.isfile(audio_path):
            messagebox.showerror("오류", "원곡 파일을 선택하세요."); return
        if not bin_path or not os.path.isfile(bin_path):
            messagebox.showerror("오류", "타임라인 바이너리를 선택하세요."); return
        if not HAS_LIBROSA:
            messagebox.showerror("라이브러리 없음",
                "librosa가 설치되지 않았습니다.\npip install librosa 후 재시작하세요."); return

        self._clear_log()
        self._set_grade_panel("…", "#888888", "분석 중…")
        self.run_btn.config(state="disabled")
        self.status_var.set("분석 중… librosa 오디오 로딩은 수십 초 걸릴 수 있습니다.")

        tol_ms   = self.tol_var.get()
        bpm_hint = self.bpm_hint_var.get()
        threading.Thread(target=self._analyze_thread,
                         args=(audio_path, bin_path, tol_ms, bpm_hint),
                         daemon=True).start()

    def _analyze_thread(self, audio_path, bin_path, tol_ms, bpm_hint):
        try:
            self._do_analyze(audio_path, bin_path, tol_ms, bpm_hint)
        except Exception as e:
            import traceback
            msg = traceback.format_exc()
            self.after(0, lambda: self._log(f"\n[오류] {e}\n{msg}"))
            self.after(0, lambda: self._set_grade_panel("!", "#b71c1c", f"오류 발생: {e}"))
        finally:
            self.after(0, lambda: self.run_btn.config(state="normal"))
            self.after(0, lambda: self.status_var.set("완료"))

    def _do_analyze(self, audio_path, bin_path, tol_ms, bpm_hint):
        SEP  = "─" * 60
        SEP2 = "═" * 60

        # ① 바이너리 파싱 ─────────────────────────
        self.after(0, lambda: self._log("[ 1/3 ]  타임라인 바이너리 파싱…"))
        version, frame_count, app_ms = parse_timeline_binary(bin_path)
        app_sec = [t / 1000.0 for t in app_ms]
        app_st  = beat_stats(app_ms)

        def _log_bin():
            self._log(SEP)
            self._log(f"  바이너리 : {os.path.basename(bin_path)}")
            self._log(f"  포맷 버전   : {version}")
            self._log(f"  총 프레임   : {frame_count}  (파싱 성공: {len(app_ms)})")
            self._log(f"  감지 BPM    : {app_st.get('bpm', 0):.1f}")
            self._log(f"  중앙 간격   : {app_st.get('median_ms', 0):.1f} ms")
            self._log(f"  대형갭>600ms: {app_st.get('gaps_600', 0)}개")
            self._log(f"  단기간<100ms: {app_st.get('short_100', 0)}개")
            self._log(f"  이상 인터벌 : {app_st.get('anomaly_pct', 0):.1f}%")
        self.after(0, _log_bin)

        # ② librosa beat tracking ──────────────────
        self.after(0, lambda: self._log(f"\n[ 2/3 ]  librosa beat tracking… (오디오 로딩 포함)"))
        y, sr = librosa.load(audio_path, sr=None, mono=True)
        duration_sec = len(y) / sr

        tempo_result, ref_frames = librosa.beat.beat_track(
            y=y, sr=sr,
            start_bpm=float(bpm_hint) if bpm_hint > 0 else 120.0,
            tightness=100,
            trim=False
        )
        librosa_bpm = float(tempo_result[0]) if hasattr(tempo_result, '__len__') else float(tempo_result)
        ref_sec  = librosa.frames_to_time(ref_frames, sr=sr).tolist()
        ref_ms   = [int(t * 1000) for t in ref_sec]
        ref_st   = beat_stats(ref_ms)

        def _log_ref():
            self._log(SEP)
            self._log(f"  원곡     : {os.path.basename(audio_path)}")
            self._log(f"  길이        : {duration_sec:.1f} 초")
            self._log(f"  librosa BPM : {librosa_bpm:.1f}")
            self._log(f"  librosa 비트: {len(ref_sec)}개")
            self._log(f"  중앙 간격   : {ref_st.get('median_ms', 0):.1f} ms")
        self.after(0, _log_ref)

        # ③ F-measure 계산 ────────────────────────
        self.after(0, lambda: self._log(f"\n[ 3/3 ]  F-measure 계산 (허용 오차 ±{tol_ms}ms)…"))
        tol_sec = tol_ms / 1000.0
        f, p, r, tp, fp, fn = fmeasure(ref_sec, app_sec, tol_sec)

        app_end_sec  = app_sec[-1] if app_sec else 0
        coverage_pct = min(100.0, app_end_sec / duration_sec * 100) if duration_sec > 0 else 0
        bpm_err_pct  = (abs(app_st.get('bpm', 0) - librosa_bpm) / librosa_bpm * 100
                        if librosa_bpm > 0 else 0)
        grade, color, verdict = grade_info(f)

        # ── 등급 판정 바 ──────────────────────────────────────────
        bar_total = 50
        bar_fill  = round(f * bar_total)
        bar_str   = "█" * bar_fill + "░" * (bar_total - bar_fill)

        def _log_result():
            self._log(SEP2)
            self._log("  ▣  정확도 결과")
            self._log(SEP2)
            self._log(f"  등급       :  {grade}  ({verdict})")
            self._log(f"  F-measure  : {f*100:5.1f}%   [{bar_str}]")
            self._log(f"  Precision  : {p*100:5.1f}%   (앱 비트 중 librosa 일치 비율)")
            self._log(f"  Recall     : {r*100:5.1f}%   (librosa 비트 중 앱이 맞힌 비율)")
            self._log(f"  TP / FP / FN : {tp} / {fp} / {fn}")
            self._log("")
            self._log(f"  BPM (앱)   : {app_st.get('bpm', 0):.1f}")
            self._log(f"  BPM (lib)  : {librosa_bpm:.1f}")
            self._log(f"  BPM 오차   : {bpm_err_pct:.1f}%")
            self._log(f"  커버리지   : {coverage_pct:.1f}%  (곡 전체 대비 앱 마지막 비트)")
            self._log(SEP2)
            self._log("  참고 기준:")
            self._log(f"  {'등급':<4}  {'F-measure':<12}  설명")
            self._log(f"  {'S':<4}  {'≥ 85%':<12}  librosa / madmom 급")
            self._log(f"  {'A':<4}  {'75~85%':<12}  상용 서비스 수준")
            self._log(f"  {'B':<4}  {'60~75%':<12}  실용 가능")
            self._log(f"  {'C':<4}  {'45~60%':<12}  개선 여지 있음")
            self._log(f"  {'D':<4}  {'< 45%':<12}  비트 감지 실패 수준")
            self._log(SEP2)

        self.after(0, _log_result)
        self.after(0, lambda: self._set_grade_panel(
            grade, color,
            f"F-measure {f*100:.1f}%   |   BPM {app_st.get('bpm',0):.1f}  vs  librosa {librosa_bpm:.1f}\n"
            f"Precision {p*100:.1f}%  /  Recall {r*100:.1f}%  /  커버리지 {coverage_pct:.1f}%\n"
            f"{verdict}"
        ))


# ──────────────────────────────────────────────

if __name__ == "__main__":
    if not HAS_LIBROSA:
        print("=" * 56)
        print("librosa가 설치되지 않았습니다. 아래 명령으로 설치하세요:")
        print("  pip install librosa numpy")
        print("=" * 56)

    App().mainloop()
