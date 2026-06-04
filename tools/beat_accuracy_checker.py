"""
Beat Accuracy Checker
=====================
MP3/WAV 원곡 + 라이트스틱 타임라인 바이너리를 비교하여
F-measure 정확도를 측정하는 도구.

Ground-truth 엔진 우선순위 (설치된 것 중 가장 정확한 것 자동 선택):
  1. Beat Transformer  ~91%  (pip install beat-transformer torch)
  2. madmom            ~87%  (pip install madmom)
  3. librosa           ~68%  (pip install librosa)

바이너리 포맷 (Android 기록 기준):
  version    : Int  (4 bytes, big-endian)
  frameCount : Int  (4 bytes, big-endian)
  frames[]:
    timeMs     : Long (8 bytes, big-endian)
    payloadLen : Int  (4 bytes, big-endian)
    payload    : bytes
"""

import struct
import re
import os
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext

# ──────────────────────────────────────────────
# 의존 라이브러리 감지
# ──────────────────────────────────────────────

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

# Beat Transformer
HAS_BT = False
try:
    # beat-transformer PyPI 패키지 (0.1.x)
    from beat_transformer import BeatTransformer as _BT
    HAS_BT = True
    _BT_SOURCE = "beat-transformer (PyPI)"
except ImportError:
    pass

if not HAS_BT:
    try:
        # 일부 버전에서 다른 모듈명 사용
        import beat_transformer as _bt_module
        HAS_BT = True
        _BT_SOURCE = "beat_transformer (module)"
    except ImportError:
        pass

# madmom
HAS_MADMOM = False
try:
    import madmom
    HAS_MADMOM = True
except ImportError:
    pass

# librosa
HAS_LIBROSA = False
try:
    import librosa
    HAS_LIBROSA = True
except ImportError:
    pass

# ──────────────────────────────────────────────
# 엔진 정보 헬퍼
# ──────────────────────────────────────────────

ENGINE_INFO = {
    "beat_transformer": ("Beat Transformer", "~91%", "#1b5e20"),
    "madmom"          : ("madmom DBNBeatTracker", "~87%", "#0d47a1"),
    "librosa"         : ("librosa beat_track", "~68%", "#e65100"),
}

def best_available_engine(preferred: str) -> str:
    order = {
        "beat_transformer": ["beat_transformer", "madmom", "librosa"],
        "madmom"          : ["madmom", "librosa"],
        "librosa"         : ["librosa"],
    }
    for eng in order.get(preferred, ["librosa"]):
        if   eng == "beat_transformer" and HAS_BT:      return eng
        elif eng == "madmom"           and HAS_MADMOM:  return eng
        elif eng == "librosa"          and HAS_LIBROSA: return eng
    return "none"


# ──────────────────────────────────────────────
# beat detection 래퍼
# ──────────────────────────────────────────────

def detect_beats_beat_transformer(audio_path: str) -> tuple[list[float], float]:
    """Beat Transformer로 비트 감지 → (beat_times_sec, bpm)"""
    try:
        # beat-transformer PyPI 패키지 API (v0.1.x)
        from beat_transformer import BeatTransformer
        model = BeatTransformer()
        beats, downbeats = model.predict(audio_path)
        beats_sec = sorted(float(b) for b in beats)
    except Exception:
        # 대안 API 시도
        import beat_transformer as bt
        beats_sec = sorted(float(b) for b in bt.predict(audio_path))

    if len(beats_sec) >= 2:
        intervals = [beats_sec[i+1] - beats_sec[i] for i in range(len(beats_sec)-1)]
        import statistics
        bpm = 60.0 / statistics.median(intervals)
    else:
        bpm = 0.0
    return beats_sec, bpm


def detect_beats_madmom(audio_path: str) -> tuple[list[float], float]:
    """madmom DBNBeatTracker로 비트 감지 → (beat_times_sec, bpm)"""
    from madmom.features.beats import DBNBeatTrackingProcessor, RNNBeatProcessor
    act   = RNNBeatProcessor()(audio_path)
    proc  = DBNBeatTrackingProcessor(fps=100)
    beats = proc(act)
    beats_sec = sorted(float(b) for b in beats)
    if len(beats_sec) >= 2:
        intervals = [beats_sec[i+1] - beats_sec[i] for i in range(len(beats_sec)-1)]
        import statistics
        bpm = 60.0 / statistics.median(intervals)
    else:
        bpm = 0.0
    return beats_sec, bpm


def detect_beats_librosa(audio_path: str, bpm_hint: float = 0.0) -> tuple[list[float], float]:
    """librosa beat_track으로 비트 감지 → (beat_times_sec, bpm)"""
    y, sr = librosa.load(audio_path, sr=None, mono=True)
    tempo_result, ref_frames = librosa.beat.beat_track(
        y=y, sr=sr,
        start_bpm=float(bpm_hint) if bpm_hint > 0 else 120.0,
        tightness=100,
        trim=False
    )
    bpm = float(tempo_result[0]) if hasattr(tempo_result, '__len__') else float(tempo_result)
    beats_sec = librosa.frames_to_time(ref_frames, sr=sr).tolist()
    return sorted(beats_sec), bpm


def get_audio_duration(audio_path: str) -> float:
    """오디오 길이(초) 반환. librosa 우선, 없으면 0."""
    if HAS_LIBROSA:
        return librosa.get_duration(path=audio_path)
    return 0.0


# ──────────────────────────────────────────────
# 바이너리 파서
# ──────────────────────────────────────────────

def extract_music_id(filename: str) -> str:
    name = os.path.splitext(os.path.basename(filename))[0]
    m = re.search(r'timeline[_\-](\d+)', name)
    if m:
        return m.group(1)
    m = re.search(r'(\d{5,})', name)
    if m:
        return m.group(1)
    return name


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
        offset     += payload_len
        beat_times_ms.append(time_ms)
    return version, frame_count, beat_times_ms


# ──────────────────────────────────────────────
# 분석 유틸
# ──────────────────────────────────────────────

def fmeasure(ref_sec, est_sec, tolerance=0.070):
    ref = np.sort(np.array(ref_sec, dtype=float))
    est = np.sort(np.array(est_sec, dtype=float))
    tp = 0; used_ref = set()
    for e in est:
        diffs = np.abs(ref - e)
        idx   = int(np.argmin(diffs))
        if diffs[idx] <= tolerance and idx not in used_ref:
            tp += 1; used_ref.add(idx)
    fp = len(est) - tp
    fn = len(ref) - tp
    p  = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    r  = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f  = 2*p*r / (p+r) if (p+r) > 0 else 0.0
    return f, p, r, tp, fp, fn


def beat_stats(beat_times_ms):
    if len(beat_times_ms) < 2:
        return {}
    intervals = np.diff(np.array(beat_times_ms, dtype=float))
    median_ms = float(np.median(intervals))
    bpm   = 60_000.0 / median_ms if median_ms > 0 else 0.0
    gaps  = int(np.sum(intervals > 600))
    short = int(np.sum(intervals < 100))
    return {
        "count"      : len(beat_times_ms),
        "median_ms"  : median_ms,
        "bpm"        : bpm,
        "max_gap_ms" : float(np.max(intervals)),
        "gaps_600"   : gaps,
        "short_100"  : short,
        "anomaly_pct": round((gaps + short) / max(1, len(intervals)) * 100, 1),
    }


def grade_info(f_score: float, engine: str):
    pct = f_score * 100
    # Beat Transformer 기준 등급 기준이 librosa보다 엄격
    if engine == "beat_transformer":
        thresholds = [(85, "S", "#1b5e20", "최고 수준 — 서비스 즉시 적용 가능"),
                      (75, "A", "#1565c0", "상용 서비스 수준"),
                      (60, "B", "#f57f17", "실용 가능 — 일부 곡에서 오류"),
                      (45, "C", "#bf360c", "개선 필요"),
                      (  0, "D", "#b71c1c", "비트 감지 실패 수준")]
    elif engine == "madmom":
        thresholds = [(80, "S", "#1b5e20", "최고 수준 — 서비스 즉시 적용 가능"),
                      (68, "A", "#1565c0", "상용 서비스 수준"),
                      (55, "B", "#f57f17", "실용 가능"),
                      (40, "C", "#bf360c", "개선 필요"),
                      (  0, "D", "#b71c1c", "비트 감지 실패 수준")]
    else:  # librosa
        thresholds = [(75, "S", "#1b5e20", "librosa 대비 최고 수준"),
                      (62, "A", "#1565c0", "librosa 동등 이상"),
                      (50, "B", "#f57f17", "librosa 대비 실용 수준"),
                      (35, "C", "#bf360c", "librosa 대비 개선 필요"),
                      (  0, "D", "#b71c1c", "비트 감지 실패 수준")]

    for th, g, c, v in thresholds:
        if pct >= th:
            return g, c, v
    return "D", "#b71c1c", "비트 감지 실패 수준"


# ──────────────────────────────────────────────
# GUI
# ──────────────────────────────────────────────

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Beat Accuracy Checker")
        self.resizable(True, True)
        self.minsize(760, 600)
        self._build_ui()

    # ── UI 구성 ──────────────────────────────────

    def _build_ui(self):
        pad = dict(padx=8, pady=4)

        # ── 라이브러리 상태 배너 ────────────────────
        banner_frame = tk.Frame(self, bg="#263238")
        banner_frame.pack(fill="x")
        status_parts = []
        if HAS_BT:
            status_parts.append(("● Beat Transformer", "#69f0ae"))
        else:
            status_parts.append(("✗ Beat Transformer (미설치)", "#ef9a9a"))
        if HAS_MADMOM:
            status_parts.append(("● madmom", "#69f0ae"))
        else:
            status_parts.append(("✗ madmom (미설치)", "#ef9a9a"))
        if HAS_LIBROSA:
            status_parts.append(("● librosa", "#69f0ae"))
        else:
            status_parts.append(("✗ librosa (미설치)", "#ef9a9a"))

        for txt, col in status_parts:
            tk.Label(banner_frame, text=txt, bg="#263238", fg=col,
                     font=("", 9, "bold")).pack(side="left", padx=10, pady=3)

        # ── 파일 선택 ──────────────────────────────
        frame_files = tk.LabelFrame(self, text="파일 선택", **pad)
        frame_files.pack(fill="x", **pad)
        frame_files.columnconfigure(1, weight=1)

        # 원곡
        tk.Label(frame_files, text="원곡 (MP3/WAV/FLAC):").grid(row=0, column=0, sticky="w", **pad)
        self.audio_var = tk.StringVar()
        tk.Entry(frame_files, textvariable=self.audio_var).grid(row=0, column=1, sticky="ew", **pad)
        tk.Button(frame_files, text="찾기", command=self._pick_audio, width=6).grid(row=0, column=2, **pad)
        tk.Label(frame_files, text="Music ID:").grid(row=0, column=3, sticky="w", **pad)
        self.audio_id_var = tk.StringVar(value="—")
        tk.Label(frame_files, textvariable=self.audio_id_var,
                 font=("Courier", 10, "bold"), fg="#1565c0", width=14, anchor="w"
                 ).grid(row=0, column=4, sticky="w", **pad)
        self.audio_var.trace_add("write", self._update_audio_id)

        # 바이너리
        tk.Label(frame_files, text="타임라인 바이너리:").grid(row=1, column=0, sticky="w", **pad)
        self.bin_var = tk.StringVar()
        tk.Entry(frame_files, textvariable=self.bin_var).grid(row=1, column=1, sticky="ew", **pad)
        tk.Button(frame_files, text="찾기", command=self._pick_bin, width=6).grid(row=1, column=2, **pad)
        tk.Label(frame_files, text="Music ID:").grid(row=1, column=3, sticky="w", **pad)
        self.bin_id_var = tk.StringVar(value="—")
        tk.Label(frame_files, textvariable=self.bin_id_var,
                 font=("Courier", 10, "bold"), fg="#1565c0", width=14, anchor="w"
                 ).grid(row=1, column=4, sticky="w", **pad)
        self.bin_var.trace_add("write", self._update_bin_id)

        # ── 설정 ───────────────────────────────────
        frame_opt = tk.LabelFrame(self, text="설정", **pad)
        frame_opt.pack(fill="x", **pad)

        # Ground-truth 엔진 선택
        tk.Label(frame_opt, text="Ground-truth 엔진:").grid(row=0, column=0, sticky="w", **pad)
        self.engine_var = tk.StringVar(value="beat_transformer")
        engines = [
            ("Beat Transformer (~91%)", "beat_transformer"),
            ("madmom (~87%)",           "madmom"),
            ("librosa (~68%)",          "librosa"),
        ]
        for col, (label, val) in enumerate(engines, start=1):
            tk.Radiobutton(frame_opt, text=label, variable=self.engine_var,
                           value=val).grid(row=0, column=col, sticky="w", **pad)

        # 허용 오차
        tk.Label(frame_opt, text="허용 오차 (ms):").grid(row=1, column=0, sticky="w", **pad)
        self.tol_var = tk.IntVar(value=70)
        tk.Spinbox(frame_opt, from_=20, to=200, increment=10,
                   textvariable=self.tol_var, width=6).grid(row=1, column=1, sticky="w", **pad)
        tk.Label(frame_opt, text="업계 표준 70ms",
                 fg="#555").grid(row=1, column=2, sticky="w", **pad)

        # BPM 힌트 (librosa 전용)
        tk.Label(frame_opt, text="BPM 힌트 (librosa 전용, 0=자동):").grid(row=1, column=3, sticky="w", **pad)
        self.bpm_hint_var = tk.DoubleVar(value=0.0)
        tk.Entry(frame_opt, textvariable=self.bpm_hint_var, width=7).grid(row=1, column=4, sticky="w", **pad)

        # ── 등급 패널 ─────────────────────────────
        self.grade_frame = tk.Frame(self, bd=2, relief="groove", bg="#f5f5f5")
        self.grade_frame.pack(fill="x", padx=8, pady=2)
        self.grade_label = tk.Label(self.grade_frame, text=" — ",
                                    font=("", 32, "bold"), width=3, bg="#f5f5f5")
        self.grade_label.pack(side="left", padx=16, pady=4)
        self.grade_detail = tk.Label(self.grade_frame, text="분석 전",
                                     font=("", 12), justify="left", anchor="w", bg="#f5f5f5")
        self.grade_detail.pack(side="left", fill="x", expand=True)

        # ── 실행 버튼 ──────────────────────────────
        self.run_btn = tk.Button(self, text="▶  분석 시작",
                                 font=("", 12, "bold"), bg="#2e7d32", fg="white",
                                 activebackground="#1b5e20", activeforeground="white",
                                 command=self._run)
        self.run_btn.pack(pady=5)

        # ── 결과 로그 ──────────────────────────────
        frame_out = tk.LabelFrame(self, text="상세 결과", **pad)
        frame_out.pack(fill="both", expand=True, **pad)
        self.out = scrolledtext.ScrolledText(frame_out, font=("Courier", 10), state="disabled",
                                             bg="#1e1e1e", fg="#d4d4d4",
                                             insertbackground="white")
        # 색상 태그
        self.out.tag_config("green",  foreground="#69f0ae")
        self.out.tag_config("blue",   foreground="#82b1ff")
        self.out.tag_config("yellow", foreground="#ffcc02")
        self.out.tag_config("red",    foreground="#ef9a9a")
        self.out.tag_config("gray",   foreground="#9e9e9e")
        self.out.tag_config("bold",   font=("Courier", 10, "bold"))
        self.out.pack(fill="both", expand=True)

        # 상태바
        self.status_var = tk.StringVar(value="파일을 선택하고 [분석 시작]을 누르세요.")
        tk.Label(self, textvariable=self.status_var, anchor="w",
                 relief="sunken", fg="#555").pack(fill="x", side="bottom")

        # 설치 안내
        if not (HAS_BT or HAS_MADMOM or HAS_LIBROSA):
            messagebox.showerror(
                "라이브러리 없음",
                "분석 엔진이 하나도 설치되지 않았습니다.\n\n"
                "# 최고 정확도\npip install beat-transformer torch\n\n"
                "# 빠른 대안\npip install madmom\n\n"
                "# 기본\npip install librosa"
            )

    # ── ID 추출 ────────────────────────────────────

    def _update_audio_id(self, *_):
        path = self.audio_var.get().strip()
        if not path:
            self.audio_id_var.set("—"); return
        mid = extract_music_id(path)
        base = os.path.splitext(os.path.basename(path))[0]
        self.audio_id_var.set(mid if mid != base else "—")

    def _update_bin_id(self, *_):
        path = self.bin_var.get().strip()
        self.bin_id_var.set(extract_music_id(path) if path else "—")

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

    def _log(self, text, tag=None):
        self.out.config(state="normal")
        if tag:
            self.out.insert("end", text + "\n", tag)
        else:
            self.out.insert("end", text + "\n")
        self.out.see("end")
        self.out.config(state="disabled")

    def _clear_log(self):
        self.out.config(state="normal")
        self.out.delete("1.0", "end")
        self.out.config(state="disabled")

    def _set_grade_panel(self, grade, color, detail):
        self.grade_frame.config(bg=color)
        self.grade_label.config(text=grade, fg="white", bg=color)
        self.grade_detail.config(text=detail, fg="white", bg=color)

    # ── 분석 실행 ──────────────────────────────────

    def _run(self):
        audio_path = self.audio_var.get().strip()
        bin_path   = self.bin_var.get().strip()

        if not audio_path or not os.path.isfile(audio_path):
            messagebox.showerror("오류", "원곡 파일을 선택하세요."); return
        if not bin_path or not os.path.isfile(bin_path):
            messagebox.showerror("오류", "타임라인 바이너리를 선택하세요."); return

        preferred = self.engine_var.get()
        engine    = best_available_engine(preferred)
        if engine == "none":
            messagebox.showerror("라이브러리 없음",
                "분석 가능한 엔진이 없습니다.\npip install librosa 로 최소 설치 후 재시작하세요.")
            return

        # 요청 엔진과 실제 엔진이 다르면 안내
        if engine != preferred:
            eng_name = ENGINE_INFO[preferred][0]
            act_name = ENGINE_INFO[engine][0]
            messagebox.showwarning(
                "엔진 변경",
                f"{eng_name}이(가) 설치되지 않아\n{act_name}으로 대체합니다.")

        self._clear_log()
        self._set_grade_panel("…", "#455a64", "분석 중…")
        self.run_btn.config(state="disabled")
        eng_display = ENGINE_INFO[engine][0]
        self.status_var.set(f"분석 중… 엔진: {eng_display}  (오디오 로딩 포함, 수십 초 소요)")

        tol_ms   = self.tol_var.get()
        bpm_hint = self.bpm_hint_var.get()
        threading.Thread(
            target=self._analyze_thread,
            args=(audio_path, bin_path, engine, tol_ms, bpm_hint),
            daemon=True
        ).start()

    def _analyze_thread(self, audio_path, bin_path, engine, tol_ms, bpm_hint):
        try:
            self._do_analyze(audio_path, bin_path, engine, tol_ms, bpm_hint)
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            self.after(0, lambda: self._log(f"\n[오류] {e}", "red"))
            self.after(0, lambda: self._log(tb, "red"))
            self.after(0, lambda: self._set_grade_panel("!", "#b71c1c", f"오류: {e}"))
        finally:
            self.after(0, lambda: self.run_btn.config(state="normal"))
            self.after(0, lambda: self.status_var.set("완료"))

    def _do_analyze(self, audio_path, bin_path, engine, tol_ms, bpm_hint):
        SEP  = "─" * 62
        SEP2 = "═" * 62

        eng_name, eng_acc, eng_color = ENGINE_INFO[engine]

        # ① 바이너리 파싱 ─────────────────────────
        self.after(0, lambda: self._log(f"[ 1/3 ]  타임라인 바이너리 파싱…", "gray"))
        version, frame_count, app_ms = parse_timeline_binary(bin_path)
        app_sec  = [t / 1000.0 for t in app_ms]
        app_st   = beat_stats(app_ms)
        music_id = extract_music_id(bin_path)

        def _log_bin():
            self._log(SEP, "gray")
            self._log(f"  바이너리   : {os.path.basename(bin_path)}")
            self._log(f"  Music ID   : {music_id}", "blue")
            self._log(f"  포맷 버전  : {version}")
            self._log(f"  총 프레임  : {frame_count}  (파싱 성공: {len(app_ms)})")
            self._log(f"  감지 BPM   : {app_st.get('bpm', 0):.1f}")
            self._log(f"  중앙 간격  : {app_st.get('median_ms', 0):.1f} ms")
            self._log(f"  대형갭>600ms: {app_st.get('gaps_600', 0)}개"  + (
                "" if app_st.get('gaps_600', 0) == 0 else "  ← 주의"), "yellow" if app_st.get('gaps_600', 0) else None)
            self._log(f"  단기간<100ms: {app_st.get('short_100', 0)}개" + (
                "" if app_st.get('short_100', 0) == 0 else "  ← 주의"), "yellow" if app_st.get('short_100', 0) else None)
            self._log(f"  이상 인터벌: {app_st.get('anomaly_pct', 0):.1f}%")
        self.after(0, _log_bin)

        # ② Ground-truth beat detection ───────────
        self.after(0, lambda: self._log(
            f"\n[ 2/3 ]  Ground-truth 감지 중… (엔진: {eng_name} {eng_acc})", "gray"))

        if engine == "beat_transformer":
            ref_sec, ref_bpm = detect_beats_beat_transformer(audio_path)
        elif engine == "madmom":
            ref_sec, ref_bpm = detect_beats_madmom(audio_path)
        else:
            ref_sec, ref_bpm = detect_beats_librosa(audio_path, bpm_hint)

        ref_ms = [int(t * 1000) for t in ref_sec]
        ref_st = beat_stats(ref_ms)

        try:
            duration_sec = get_audio_duration(audio_path)
        except Exception:
            duration_sec = ref_sec[-1] + 1.0 if ref_sec else 0.0

        def _log_ref():
            self._log(SEP, "gray")
            self._log(f"  원곡       : {os.path.basename(audio_path)}")
            self._log(f"  길이       : {duration_sec:.1f} 초")
            self._log(f"  엔진       : {eng_name}  (참고 정확도 {eng_acc})", "green")
            self._log(f"  Ground-truth BPM : {ref_bpm:.1f}")
            self._log(f"  Ground-truth 비트: {len(ref_sec)}개")
            self._log(f"  중앙 간격  : {ref_st.get('median_ms', 0):.1f} ms")
        self.after(0, _log_ref)

        # ③ F-measure 계산 ────────────────────────
        self.after(0, lambda: self._log(
            f"\n[ 3/3 ]  F-measure 계산 (허용 오차 ±{tol_ms}ms)…", "gray"))

        tol_sec = tol_ms / 1000.0
        f, p, r, tp, fp, fn = fmeasure(ref_sec, app_sec, tol_sec)

        app_end_sec  = app_sec[-1] if app_sec else 0.0
        coverage_pct = min(100.0, app_end_sec / duration_sec * 100) if duration_sec > 0 else 0.0
        bpm_err_pct  = (abs(app_st.get('bpm', 0) - ref_bpm) / ref_bpm * 100
                        if ref_bpm > 0 else 0.0)

        grade, g_color, verdict = grade_info(f, engine)

        bar_total = 50
        bar_fill  = round(f * bar_total)
        bar_str   = "█" * bar_fill + "░" * (bar_total - bar_fill)

        # 등급별 권고 메시지
        if grade == "S":
            advice = "서비스 적용 가능 수준입니다."
        elif grade == "A":
            advice = "대부분의 K-pop에서 정상 동작합니다."
        elif grade == "B":
            advice = "BPM은 정확하나 비트 타이밍 개선이 필요합니다."
        elif grade == "C":
            advice = "Ellis DP 또는 Adaptive Threshold 튜닝을 권장합니다."
        else:
            advice = "BPM 감지 로직부터 재검토가 필요합니다."

        def _log_result():
            self._log(SEP2, "gray")
            self._log("  ▣  정확도 결과", "bold")
            self._log(SEP2, "gray")
            g_tag = "green" if grade in ("S","A") else ("yellow" if grade == "B" else "red")
            self._log(f"  등급       :  {grade}   {verdict}", g_tag)
            self._log(f"  F-measure  : {f*100:5.1f}%   [{bar_str}]", g_tag)
            self._log(f"  Precision  : {p*100:5.1f}%   (앱 비트 중 GT 일치 비율)")
            self._log(f"  Recall     : {r*100:5.1f}%   (GT 비트 중 앱이 맞힌 비율)")
            self._log(f"  TP / FP / FN : {tp} / {fp} / {fn}")
            self._log("")
            self._log(f"  BPM (앱)        : {app_st.get('bpm', 0):.1f}")
            self._log(f"  BPM (GT)        : {ref_bpm:.1f}")
            self._log(f"  BPM 오차        : {bpm_err_pct:.1f}%",
                      "green" if bpm_err_pct < 5 else ("yellow" if bpm_err_pct < 15 else "red"))
            self._log(f"  커버리지        : {coverage_pct:.1f}%",
                      "green" if coverage_pct > 95 else "yellow")
            self._log(f"  Ground-truth 엔진: {eng_name} (참고 정확도 {eng_acc})", "gray")
            self._log("")
            self._log(f"  권고: {advice}", g_tag)
            self._log(SEP2, "gray")
            self._log("  등급 기준 (엔진: Beat Transformer 기준 / librosa는 별도 기준 적용):")
            self._log("   S  ≥85%   최고 수준 — 서비스 즉시 적용 가능", "green")
            self._log("   A  75~85% 상용 서비스 수준", "blue")
            self._log("   B  60~75% 실용 가능", "yellow")
            self._log("   C  45~60% 개선 여지 있음", "red")
            self._log("   D   <45%  비트 감지 실패 수준", "red")
            self._log(SEP2, "gray")

        self.after(0, _log_result)
        self.after(0, lambda: self._set_grade_panel(
            grade, g_color,
            f"F-measure {f*100:.1f}%   |   BPM 앱 {app_st.get('bpm',0):.1f}  vs  GT {ref_bpm:.1f}   |   엔진: {eng_name}\n"
            f"Precision {p*100:.1f}%  /  Recall {r*100:.1f}%  /  커버리지 {coverage_pct:.1f}%\n"
            f"{verdict} — {advice}"
        ))


# ──────────────────────────────────────────────

if __name__ == "__main__":
    if not HAS_NUMPY:
        print("numpy가 필요합니다: pip install numpy")
        sys.exit(1)

    if not (HAS_BT or HAS_MADMOM or HAS_LIBROSA):
        print("=" * 60)
        print("분석 엔진을 하나 이상 설치하세요:\n")
        print("  # 최고 정확도 (~91%)")
        print("  pip install beat-transformer torch\n")
        print("  # 빠른 대안 (~87%)")
        print("  pip install madmom\n")
        print("  # 기본 (~68%)")
        print("  pip install librosa")
        print("=" * 60)

    App().mainloop()
