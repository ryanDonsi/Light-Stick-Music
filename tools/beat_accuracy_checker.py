"""
Beat Accuracy Checker
=====================
MP3/WAV 원곡 + 라이트스틱 타임라인 바이너리를 비교하여
F-measure 정확도를 측정 + 비트맵 이미지로 시각화하는 도구.

Ground-truth 엔진 우선순위 (설치된 것 중 가장 정확한 것 자동 선택):
  1. Beat Transformer  ~91%  (pip install torch; git clone + pip install -e .)
  2. madmom            ~87%  (pip install madmom)
  3. librosa           ~68%  (pip install librosa)

pip install numpy matplotlib librosa soundfile
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

try:
    import matplotlib
    matplotlib.use("TkAgg")
    import matplotlib.pyplot as plt
    import matplotlib.gridspec as gridspec
    import matplotlib.patches as mpatches
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

# Beat Transformer
HAS_BT = False
BT_LOCAL_PATH = ""   # 로컬 클론 경로 (GUI에서 설정)

def _try_load_bt(local_path=""):
    """Beat Transformer 임포트 시도. local_path 있으면 sys.path에 추가."""
    global HAS_BT, BT_LOCAL_PATH
    if local_path and os.path.isdir(local_path):
        if local_path not in sys.path:
            sys.path.insert(0, local_path)
        BT_LOCAL_PATH = local_path
    try:
        import importlib
        import beat_transformer as _bt
        importlib.reload(_bt)          # 경로 변경 후 재로드
        HAS_BT = True
        return True
    except Exception:
        HAS_BT = False
        return False

# 시작 시 자동 감지 (pip 설치 또는 같은 폴더에 있는 경우)
_script_dir = os.path.dirname(os.path.abspath(__file__))
for _candidate in [
    "",                                              # pip 설치
    os.path.join(_script_dir, "Beat-Transformer"),  # tools/Beat-Transformer
    os.path.join(_script_dir, "beat_transformer"),  # tools/beat_transformer
]:
    if _try_load_bt(_candidate):
        break

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
# 엔진 정보
# ──────────────────────────────────────────────

ENGINE_INFO = {
    "beat_transformer": ("Beat Transformer", "~91%", "#1b5e20"),
    "madmom"          : ("madmom DBNBeatTracker", "~87%", "#0d47a1"),
    "librosa"         : ("librosa beat_track",    "~68%", "#e65100"),
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
# Beat detection 래퍼
# ──────────────────────────────────────────────

def detect_beats_beat_transformer(audio_path):
    """
    Beat Transformer 로컬 레포 또는 pip 패키지로 비트 감지.
    레포 구조: Beat-Transformer/code/ 에 inference 모듈 존재.
    """
    # 방법 1: beat_transformer 패키지 직접 임포트
    try:
        from beat_transformer import BeatTransformer
        model = BeatTransformer()
        beats, _ = model.predict(audio_path)
        beats_sec = sorted(float(b) for b in beats)
        return beats_sec, _median_bpm(beats_sec)
    except Exception:
        pass

    # 방법 2: 로컬 레포 code/ 디렉터리에서 inference 모듈 탐색
    candidate_dirs = [BT_LOCAL_PATH,
                      os.path.join(BT_LOCAL_PATH, "code") if BT_LOCAL_PATH else ""]
    for d in candidate_dirs:
        if not d or not os.path.isdir(d):
            continue
        if d not in sys.path:
            sys.path.insert(0, d)
        # inference.py 직접 실행 방식 (subprocess)
        infer_py = os.path.join(d, "inference.py")
        if os.path.isfile(infer_py):
            import subprocess, json, tempfile
            with tempfile.NamedTemporaryFile(suffix=".json", delete=False, mode="w") as tf:
                out_path = tf.name
            try:
                result = subprocess.run(
                    [sys.executable, infer_py,
                     "--audio", audio_path,
                     "--out_json", out_path],
                    capture_output=True, text=True, timeout=300
                )
                if result.returncode == 0 and os.path.isfile(out_path):
                    with open(out_path) as f:
                        data = json.load(f)
                    beats_sec = sorted(float(b) for b in data.get("beats", []))
                    if beats_sec:
                        return beats_sec, _median_bpm(beats_sec)
            except Exception:
                pass
            finally:
                if os.path.isfile(out_path):
                    os.unlink(out_path)

    raise RuntimeError(
        "Beat Transformer 실행 실패.\n"
        "1) GUI에서 Beat-Transformer 클론 폴더를 지정하고 [적용]을 누르세요.\n"
        "2) pip install torch torchaudio einops 설치 확인\n"
        "3) 또는 엔진을 librosa로 변경하세요."
    )

def detect_beats_madmom(audio_path):
    from madmom.features.beats import DBNBeatTrackingProcessor, RNNBeatProcessor
    act   = RNNBeatProcessor()(audio_path)
    beats = DBNBeatTrackingProcessor(fps=100)(act)
    beats_sec = sorted(float(b) for b in beats)
    return beats_sec, _median_bpm(beats_sec)

def detect_beats_librosa(audio_path, bpm_hint=0.0):
    y, sr = librosa.load(audio_path, sr=None, mono=True)
    tempo_r, frames = librosa.beat.beat_track(
        y=y, sr=sr,
        start_bpm=float(bpm_hint) if bpm_hint > 0 else 120.0,
        tightness=100, trim=False)
    bpm = float(tempo_r[0]) if hasattr(tempo_r, '__len__') else float(tempo_r)
    beats_sec = sorted(librosa.frames_to_time(frames, sr=sr).tolist())
    return beats_sec, bpm

def load_waveform(audio_path, max_sr=22050):
    """파형 로드 (librosa 있을 때만). → (y, sr) or (None, 0)"""
    if not HAS_LIBROSA:
        return None, 0
    y, sr = librosa.load(audio_path, sr=max_sr, mono=True)
    return y, sr

def get_audio_duration(audio_path):
    if HAS_LIBROSA:
        return librosa.get_duration(path=audio_path)
    return 0.0

def _median_bpm(beats_sec):
    if len(beats_sec) < 2:
        return 0.0
    ivs = [beats_sec[i+1] - beats_sec[i] for i in range(len(beats_sec)-1)]
    import statistics
    return 60.0 / statistics.median(ivs)

# ──────────────────────────────────────────────
# 바이너리 파서
# ──────────────────────────────────────────────

def extract_music_id(filename):
    name = os.path.splitext(os.path.basename(filename))[0]
    m = re.search(r'timeline[_\-](\d+)', name)
    if m: return m.group(1)
    m = re.search(r'(\d{5,})', name)
    if m: return m.group(1)
    return name

def parse_timeline_binary(path):
    with open(path, "rb") as f:
        data = f.read()
    offset = 0
    version     = struct.unpack_from(">i", data, offset)[0]; offset += 4
    frame_count = struct.unpack_from(">i", data, offset)[0]; offset += 4
    times_ms = []
    for _ in range(frame_count):
        if offset + 12 > len(data): break
        time_ms     = struct.unpack_from(">q", data, offset)[0]; offset += 8
        payload_len = struct.unpack_from(">i", data, offset)[0]; offset += 4
        offset     += payload_len
        times_ms.append(time_ms)
    return version, frame_count, times_ms

# ──────────────────────────────────────────────
# 분석 유틸
# ──────────────────────────────────────────────

def fmeasure(ref_sec, est_sec, tolerance=0.070):
    ref = np.sort(np.array(ref_sec, dtype=float))
    est = np.sort(np.array(est_sec, dtype=float))
    tp = 0; used = set()
    for e in est:
        diffs = np.abs(ref - e)
        idx   = int(np.argmin(diffs))
        if diffs[idx] <= tolerance and idx not in used:
            tp += 1; used.add(idx)
    fp = len(est) - tp
    fn = len(ref) - tp
    p  = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    r  = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f  = 2*p*r / (p+r)  if (p + r)  > 0 else 0.0
    return f, p, r, tp, fp, fn

def classify_beats(ref_sec, est_sec, tolerance=0.070):
    """각 비트를 TP/FP/FN으로 분류"""
    ref = np.sort(np.array(ref_sec, dtype=float))
    est = np.sort(np.array(est_sec, dtype=float))
    used_ref = set()
    tp_est, fp_est = [], []
    for e in est:
        diffs = np.abs(ref - e)
        idx   = int(np.argmin(diffs))
        if diffs[idx] <= tolerance and idx not in used_ref:
            tp_est.append(e); used_ref.add(idx)
        else:
            fp_est.append(e)
    fn_ref = [ref[i] for i in range(len(ref)) if i not in used_ref]
    return tp_est, fp_est, fn_ref

def beat_stats(beat_times_ms):
    if len(beat_times_ms) < 2: return {}
    iv = np.diff(np.array(beat_times_ms, dtype=float))
    med = float(np.median(iv))
    return {
        "count"      : len(beat_times_ms),
        "median_ms"  : med,
        "bpm"        : 60_000.0 / med if med > 0 else 0.0,
        "max_gap_ms" : float(np.max(iv)),
        "gaps_600"   : int(np.sum(iv > 600)),
        "short_100"  : int(np.sum(iv < 100)),
        "anomaly_pct": round((int(np.sum(iv > 600)) + int(np.sum(iv < 100)))
                             / max(1, len(iv)) * 100, 1),
    }

def grade_info(f_score, engine):
    pct = f_score * 100
    if engine == "beat_transformer":
        th = [(85,"S","#1b5e20","최고 수준 — 서비스 즉시 적용 가능"),
              (75,"A","#1565c0","상용 서비스 수준"),
              (60,"B","#f57f17","실용 가능 — 일부 곡에서 오류"),
              (45,"C","#bf360c","개선 필요"),
              ( 0,"D","#b71c1c","비트 감지 실패 수준")]
    elif engine == "madmom":
        th = [(80,"S","#1b5e20","최고 수준 — 서비스 즉시 적용 가능"),
              (68,"A","#1565c0","상용 서비스 수준"),
              (55,"B","#f57f17","실용 가능"),
              (40,"C","#bf360c","개선 필요"),
              ( 0,"D","#b71c1c","비트 감지 실패 수준")]
    else:
        th = [(75,"S","#1b5e20","librosa 대비 최고 수준"),
              (62,"A","#1565c0","librosa 동등 이상"),
              (50,"B","#f57f17","실용 수준"),
              (35,"C","#bf360c","개선 필요"),
              ( 0,"D","#b71c1c","비트 감지 실패 수준")]
    for t, g, c, v in th:
        if pct >= t: return g, c, v
    return "D", "#b71c1c", "비트 감지 실패 수준"

# ──────────────────────────────────────────────
# 비트맵 시각화
# ──────────────────────────────────────────────

def build_beatmap_figure(
    ref_sec, app_sec, tp_est, fp_est, fn_ref,
    waveform, wav_sr, duration_sec,
    f_score, bpm_app, bpm_ref, engine_name,
    audio_name, music_id, tol_ms,
    zoom_start=None, zoom_end=None
):
    """
    비트맵 Figure 생성.
    반환: matplotlib Figure 객체
    """
    plt.style.use("dark_background")
    fig = plt.figure(figsize=(18, 10), facecolor="#1a1a2e")

    # ── 레이아웃: 파형 + 전체 비트맵 + 줌 비트맵 + 인터벌 히스토그램
    gs = gridspec.GridSpec(
        4, 1, figure=fig,
        height_ratios=[1.2, 1.5, 1.5, 1.2],
        hspace=0.55
    )
    ax_wave  = fig.add_subplot(gs[0])
    ax_full  = fig.add_subplot(gs[1])
    ax_zoom  = fig.add_subplot(gs[2])
    ax_hist  = fig.add_subplot(gs[3])

    C_BG    = "#1a1a2e"
    C_TP    = "#00e676"   # 초록 — 일치 (TP)
    C_FP    = "#ff5252"   # 빨강 — 앱 오감지 (FP)
    C_FN    = "#448aff"   # 파랑 — GT 누락 (FN)
    C_WAVE  = "#b0bec5"
    C_GRID  = "#37474f"

    for ax in [ax_wave, ax_full, ax_zoom, ax_hist]:
        ax.set_facecolor(C_BG)
        ax.tick_params(colors="#90a4ae", labelsize=8)
        for sp in ax.spines.values(): sp.set_edgecolor(C_GRID)

    # ── 타이틀 ────────────────────────────────────
    grade, g_color, _ = grade_info(f_score, engine_name.lower().replace(" ", "_")
                                   if "beat" in engine_name.lower() else
                                   "madmom" if "madmom" in engine_name.lower() else "librosa")
    fig.suptitle(
        f"Beat Map  —  {audio_name}   Music ID: {music_id}\n"
        f"F-measure {f_score*100:.1f}%  (등급 {grade})  |  "
        f"BPM 앱 {bpm_app:.1f} / GT {bpm_ref:.1f}  |  "
        f"허용 오차 ±{tol_ms}ms  |  엔진: {engine_name}",
        color="white", fontsize=11, y=0.98
    )

    # ── 범례 패치 ─────────────────────────────────
    legend_patches = [
        mpatches.Patch(color=C_TP, label=f"TP  일치  ({len(tp_est)}개)"),
        mpatches.Patch(color=C_FP, label=f"FP  앱 오감지  ({len(fp_est)}개)"),
        mpatches.Patch(color=C_FN, label=f"FN  GT 누락  ({len(fn_ref)}개)"),
    ]
    fig.legend(handles=legend_patches, loc="upper right",
               fontsize=9, framealpha=0.3, labelcolor="white",
               facecolor="#263238", edgecolor=C_GRID)

    # ── [1] 파형 ──────────────────────────────────
    ax_wave.set_title("Waveform  +  비트 위치", color="#90a4ae", fontsize=9, loc="left")
    if waveform is not None and wav_sr > 0:
        times_wav = np.linspace(0, duration_sec, len(waveform))
        ax_wave.plot(times_wav, waveform, color=C_WAVE, linewidth=0.4, alpha=0.7)
        ax_wave.set_xlim(0, duration_sec)
    else:
        ax_wave.text(0.5, 0.5, "파형 없음 (librosa 미설치)", transform=ax_wave.transAxes,
                     ha="center", va="center", color="#546e7a")
        ax_wave.set_xlim(0, duration_sec if duration_sec > 0 else 1)

    y_top = 1.0
    for t in tp_est: ax_wave.axvline(t, color=C_TP, alpha=0.55, linewidth=0.7)
    for t in fp_est: ax_wave.axvline(t, color=C_FP, alpha=0.70, linewidth=0.9)
    for t in fn_ref: ax_wave.axvline(t, color=C_FN, alpha=0.45, linewidth=0.7)
    ax_wave.set_xlabel("시간 (초)", color="#90a4ae", fontsize=8)
    ax_wave.set_ylabel("진폭", color="#90a4ae", fontsize=8)

    # ── [2] 전체 비트맵 (피아노롤 스타일) ──────────
    ax_full.set_title("전체 비트맵  (위: GT / 아래: 앱)", color="#90a4ae", fontsize=9, loc="left")
    _draw_beat_lanes(ax_full, ref_sec, app_sec, tp_est, fp_est, fn_ref,
                     0, duration_sec, C_TP, C_FP, C_FN, C_GRID)

    # 줌 범위 기본값: 처음 30초
    if zoom_start is None: zoom_start = 0.0
    if zoom_end   is None: zoom_end   = min(30.0, duration_sec)

    # ── [3] 줌 비트맵 ─────────────────────────────
    ax_zoom.set_title(
        f"줌 비트맵  ({zoom_start:.0f}s ~ {zoom_end:.0f}s)  "
        "— 비트 간격·오차를 상세히 확인",
        color="#90a4ae", fontsize=9, loc="left"
    )
    _draw_beat_lanes(ax_zoom, ref_sec, app_sec, tp_est, fp_est, fn_ref,
                     zoom_start, zoom_end, C_TP, C_FP, C_FN, C_GRID,
                     show_ms_label=True)

    # ── [4] 비트 인터벌 히스토그램 ────────────────
    ax_hist.set_title("비트 간격 분포  (ms)", color="#90a4ae", fontsize=9, loc="left")
    ref_iv  = np.diff(np.array(ref_sec)) * 1000
    app_iv  = np.diff(np.array(app_sec)) * 1000
    bins    = np.arange(200, 1001, 20)
    ax_hist.hist(ref_iv,  bins=bins, color=C_FN, alpha=0.6, label=f"GT  중앙 {np.median(ref_iv):.0f}ms")
    ax_hist.hist(app_iv,  bins=bins, color=C_TP, alpha=0.6, label=f"앱  중앙 {np.median(app_iv):.0f}ms")
    # BPM 수직선
    if bpm_ref > 0:
        ax_hist.axvline(60_000/bpm_ref, color=C_FN, linestyle="--", linewidth=1.2,
                        label=f"GT BPM {bpm_ref:.1f}")
    if bpm_app > 0:
        ax_hist.axvline(60_000/bpm_app, color=C_TP, linestyle="--", linewidth=1.2,
                        label=f"앱 BPM {bpm_app:.1f}")
    ax_hist.set_xlabel("간격 (ms)", color="#90a4ae", fontsize=8)
    ax_hist.set_ylabel("비트 수", color="#90a4ae", fontsize=8)
    ax_hist.legend(fontsize=8, framealpha=0.3, labelcolor="white",
                   facecolor="#263238", edgecolor=C_GRID)
    ax_hist.set_xlim(200, 1000)
    ax_hist.grid(axis="y", color=C_GRID, linewidth=0.5)

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    return fig


def _draw_beat_lanes(ax, ref_sec, app_sec, tp_est, fp_est, fn_ref,
                     t_start, t_end, C_TP, C_FP, C_FN, C_GRID,
                     show_ms_label=False):
    """GT 레인(위)과 앱 레인(아래)에 비트를 그린다."""
    ax.set_xlim(t_start, t_end)
    ax.set_ylim(0, 2.4)
    ax.set_yticks([0.6, 1.8])
    ax.set_yticklabels(["앱", "GT"], color="#90a4ae", fontsize=8)
    ax.set_xlabel("시간 (초)", color="#90a4ae", fontsize=8)
    ax.axhline(1.2, color=C_GRID, linewidth=0.5)

    bar_h = 0.55

    def in_range(t):
        return t_start <= t <= t_end

    # GT 레인 (y=1.8 중심)
    for t in [x for x in fn_ref if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=1.525, color=C_FN, alpha=0.9)
    for t in [x for x in tp_est if in_range(x)]:
        # GT에서의 TP 위치 — ref에서 가장 가까운 것 사용
        ax.bar(t, bar_h, width=0.012, bottom=1.525, color=C_TP, alpha=0.9)

    # 앱 레인 (y=0.6 중심)
    for t in [x for x in tp_est if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=0.325, color=C_TP, alpha=0.9)
    for t in [x for x in fp_est if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=0.325, color=C_FP, alpha=0.9)

    # ms 오차 라벨 (줌 뷰 전용)
    if show_ms_label:
        ref_arr = np.array(ref_sec)
        for t in [x for x in tp_est if in_range(x)]:
            diffs = np.abs(ref_arr - t)
            nearest_ref = ref_arr[int(np.argmin(diffs))]
            err_ms = (t - nearest_ref) * 1000
            ax.text(t, 1.2, f"{err_ms:+.0f}ms",
                    ha="center", va="center", fontsize=6,
                    color="#ffd740", alpha=0.85)

    ax.grid(axis="x", color=C_GRID, linewidth=0.4, alpha=0.5)


# ──────────────────────────────────────────────
# GUI
# ──────────────────────────────────────────────

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Beat Accuracy Checker")
        self.resizable(True, True)
        self.minsize(820, 680)
        self._last_result = None   # 비트맵 재렌더용 캐시
        self._build_ui()

    def _build_ui(self):
        pad = dict(padx=8, pady=4)

        # ── 상단 엔진 상태 배너 ─────────────────────
        banner = tk.Frame(self, bg="#263238")
        banner.pack(fill="x")
        for txt, col in [
            (("● Beat Transformer" if HAS_BT      else "✗ Beat Transformer (미설치)"),
             "#69f0ae" if HAS_BT      else "#ef9a9a"),
            (("● madmom"           if HAS_MADMOM  else "✗ madmom (미설치)"),
             "#69f0ae" if HAS_MADMOM  else "#ef9a9a"),
            (("● librosa"          if HAS_LIBROSA else "✗ librosa (미설치)"),
             "#69f0ae" if HAS_LIBROSA else "#ef9a9a"),
            (("● matplotlib"       if HAS_MPL     else "✗ matplotlib (미설치 → 비트맵 불가)"),
             "#69f0ae" if HAS_MPL     else "#ffcc02"),
        ]:
            tk.Label(banner, text=txt, bg="#263238", fg=col,
                     font=("", 9, "bold")).pack(side="left", padx=10, pady=3)

        # ── Beat Transformer 경로 설정 ─────────────
        fb = tk.LabelFrame(self, text="Beat Transformer 로컬 경로 (클론한 폴더)", **pad)
        fb.pack(fill="x", **pad)
        fb.columnconfigure(1, weight=1)
        tk.Label(fb, text="클론 폴더:").grid(row=0, column=0, sticky="w", **pad)
        self.bt_path_var = tk.StringVar(value=BT_LOCAL_PATH)
        tk.Entry(fb, textvariable=self.bt_path_var).grid(row=0, column=1, sticky="ew", **pad)
        tk.Button(fb, text="찾기", command=self._pick_bt_path, width=6).grid(row=0, column=2, **pad)
        tk.Button(fb, text="적용", command=self._apply_bt_path,
                  bg="#1565c0", fg="white", width=6).grid(row=0, column=3, **pad)
        self.bt_status_var = tk.StringVar(
            value="● 감지됨" if HAS_BT else "✗ 미감지 — 폴더 선택 후 [적용]")
        tk.Label(fb, textvariable=self.bt_status_var,
                 font=("", 9, "bold"),
                 fg="#69f0ae" if HAS_BT else "#ef9a9a"
                 ).grid(row=0, column=4, sticky="w", **pad)
        tk.Label(fb, text="예) D:\\...\\Beat-Transformer",
                 fg="#888", font=("", 8)).grid(row=0, column=5, sticky="w", **pad)

        # ── 파일 선택 ──────────────────────────────
        ff = tk.LabelFrame(self, text="파일 선택", **pad)
        ff.pack(fill="x", **pad)
        ff.columnconfigure(1, weight=1)

        tk.Label(ff, text="원곡 (MP3/WAV/FLAC):").grid(row=0, column=0, sticky="w", **pad)
        self.audio_var = tk.StringVar()
        tk.Entry(ff, textvariable=self.audio_var).grid(row=0, column=1, sticky="ew", **pad)
        tk.Button(ff, text="찾기", command=self._pick_audio, width=6).grid(row=0, column=2, **pad)
        tk.Label(ff, text="Music ID:").grid(row=0, column=3, sticky="w", **pad)
        self.audio_id_var = tk.StringVar(value="—")
        tk.Label(ff, textvariable=self.audio_id_var,
                 font=("Courier", 10, "bold"), fg="#1565c0", width=14, anchor="w"
                 ).grid(row=0, column=4, sticky="w", **pad)
        self.audio_var.trace_add("write", self._update_audio_id)

        tk.Label(ff, text="타임라인 바이너리:").grid(row=1, column=0, sticky="w", **pad)
        self.bin_var = tk.StringVar()
        tk.Entry(ff, textvariable=self.bin_var).grid(row=1, column=1, sticky="ew", **pad)
        tk.Button(ff, text="찾기", command=self._pick_bin, width=6).grid(row=1, column=2, **pad)
        tk.Label(ff, text="Music ID:").grid(row=1, column=3, sticky="w", **pad)
        self.bin_id_var = tk.StringVar(value="—")
        tk.Label(ff, textvariable=self.bin_id_var,
                 font=("Courier", 10, "bold"), fg="#1565c0", width=14, anchor="w"
                 ).grid(row=1, column=4, sticky="w", **pad)
        self.bin_var.trace_add("write", self._update_bin_id)

        # ── 설정 ───────────────────────────────────
        fo = tk.LabelFrame(self, text="설정", **pad)
        fo.pack(fill="x", **pad)

        tk.Label(fo, text="Ground-truth 엔진:").grid(row=0, column=0, sticky="w", **pad)
        self.engine_var = tk.StringVar(value="beat_transformer")
        for col, (label, val) in enumerate([
            ("Beat Transformer (~91%)", "beat_transformer"),
            ("madmom (~87%)",           "madmom"),
            ("librosa (~68%)",          "librosa"),
        ], 1):
            tk.Radiobutton(fo, text=label, variable=self.engine_var,
                           value=val).grid(row=0, column=col, sticky="w", **pad)

        tk.Label(fo, text="허용 오차 (ms):").grid(row=1, column=0, sticky="w", **pad)
        self.tol_var = tk.IntVar(value=70)
        tk.Spinbox(fo, from_=20, to=200, increment=10,
                   textvariable=self.tol_var, width=6).grid(row=1, column=1, sticky="w", **pad)
        tk.Label(fo, text="업계 표준 70ms", fg="#555").grid(row=1, column=2, sticky="w", **pad)

        tk.Label(fo, text="줌 시작 (초):").grid(row=1, column=3, sticky="w", **pad)
        self.zoom_s_var = tk.DoubleVar(value=0.0)
        tk.Entry(fo, textvariable=self.zoom_s_var, width=6).grid(row=1, column=4, sticky="w", **pad)
        tk.Label(fo, text="줌 끝 (초):").grid(row=1, column=5, sticky="w", **pad)
        self.zoom_e_var = tk.DoubleVar(value=30.0)
        tk.Entry(fo, textvariable=self.zoom_e_var, width=6).grid(row=1, column=6, sticky="w", **pad)

        tk.Label(fo, text="BPM 힌트 (librosa):").grid(row=1, column=7, sticky="w", **pad)
        self.bpm_hint_var = tk.DoubleVar(value=0.0)
        tk.Entry(fo, textvariable=self.bpm_hint_var, width=6).grid(row=1, column=8, sticky="w", **pad)

        # ── 등급 패널 ─────────────────────────────
        self.grade_frame = tk.Frame(self, bd=2, relief="groove", bg="#263238")
        self.grade_frame.pack(fill="x", padx=8, pady=2)
        self.grade_label  = tk.Label(self.grade_frame, text=" — ",
                                     font=("", 30, "bold"), width=3, bg="#263238", fg="white")
        self.grade_label.pack(side="left", padx=14, pady=4)
        self.grade_detail = tk.Label(self.grade_frame, text="분석 전",
                                     font=("", 11), justify="left", anchor="w",
                                     bg="#263238", fg="#cfd8dc")
        self.grade_detail.pack(side="left", fill="x", expand=True)

        # ── 버튼 행 ───────────────────────────────
        btn_frame = tk.Frame(self)
        btn_frame.pack(pady=4)
        self.run_btn = tk.Button(btn_frame, text="▶  분석 시작",
                                 font=("", 11, "bold"), bg="#2e7d32", fg="white",
                                 activebackground="#1b5e20", command=self._run, width=16)
        self.run_btn.pack(side="left", padx=6)
        self.map_btn = tk.Button(btn_frame, text="🗺  비트맵 보기",
                                 font=("", 11, "bold"), bg="#1565c0", fg="white",
                                 activebackground="#0d47a1", command=self._show_beatmap,
                                 width=16, state="disabled")
        self.map_btn.pack(side="left", padx=6)
        self.save_btn = tk.Button(btn_frame, text="💾  이미지 저장",
                                  font=("", 11, "bold"), bg="#6a1b9a", fg="white",
                                  activebackground="#4a148c", command=self._save_beatmap,
                                  width=16, state="disabled")
        self.save_btn.pack(side="left", padx=6)

        # ── 로그 ──────────────────────────────────
        fout = tk.LabelFrame(self, text="상세 결과", **pad)
        fout.pack(fill="both", expand=True, **pad)
        self.out = scrolledtext.ScrolledText(fout, font=("Courier", 9), state="disabled",
                                             bg="#1e1e1e", fg="#d4d4d4")
        self.out.tag_config("green",  foreground="#69f0ae")
        self.out.tag_config("blue",   foreground="#82b1ff")
        self.out.tag_config("yellow", foreground="#ffcc02")
        self.out.tag_config("red",    foreground="#ef9a9a")
        self.out.tag_config("gray",   foreground="#9e9e9e")
        self.out.tag_config("bold",   font=("Courier", 9, "bold"))
        self.out.pack(fill="both", expand=True)

        self.status_var = tk.StringVar(value="파일을 선택하고 [분석 시작]을 누르세요.")
        tk.Label(self, textvariable=self.status_var, anchor="w",
                 relief="sunken", fg="#555").pack(fill="x", side="bottom")

    # ── ID 추출 ────────────────────────────────────

    def _pick_bt_path(self):
        p = filedialog.askdirectory(title="Beat-Transformer 클론 폴더 선택")
        if p:
            self.bt_path_var.set(p)

    def _apply_bt_path(self):
        path = self.bt_path_var.get().strip()
        if not path or not os.path.isdir(path):
            messagebox.showerror("오류", "유효한 폴더를 선택하세요."); return
        ok = _try_load_bt(path)
        if ok:
            self.bt_status_var.set("● 로드 성공!")
            # 배너 업데이트
            messagebox.showinfo("성공", f"Beat Transformer 로드 완료\n경로: {path}")
        else:
            # code/ 서브폴더도 시도
            code_path = os.path.join(path, "code")
            ok = _try_load_bt(code_path)
            if ok:
                self.bt_status_var.set("● 로드 성공! (code/ 서브폴더)")
                messagebox.showinfo("성공", f"Beat Transformer 로드 완료\n경로: {code_path}")
            else:
                self.bt_status_var.set("✗ 로드 실패 — beat_transformer 모듈 없음")
                messagebox.showerror(
                    "로드 실패",
                    "beat_transformer 모듈을 찾을 수 없습니다.\n\n"
                    "폴더 구조 확인:\n"
                    "  Beat-Transformer/\n"
                    "  └── code/\n"
                    "      └── beat_transformer/  ← 여기에 __init__.py 있어야 함\n\n"
                    "또는 아래 명령으로 의존성 설치:\n"
                    "pip install torch torchaudio librosa einops"
                )

    def _update_audio_id(self, *_):
        p = self.audio_var.get().strip()
        if not p: self.audio_id_var.set("—"); return
        mid = extract_music_id(p)
        self.audio_id_var.set(mid if mid != os.path.splitext(os.path.basename(p))[0] else "—")

    def _update_bin_id(self, *_):
        p = self.bin_var.get().strip()
        self.bin_id_var.set(extract_music_id(p) if p else "—")

    def _pick_audio(self):
        p = filedialog.askopenfilename(
            title="원곡 파일 선택",
            filetypes=[("Audio", "*.mp3 *.wav *.flac *.ogg *.m4a"), ("All", "*.*")])
        if p: self.audio_var.set(p)

    def _pick_bin(self):
        p = filedialog.askopenfilename(
            title="타임라인 바이너리 선택",
            filetypes=[("Binary", "*.bin"), ("All", "*.*")])
        if p: self.bin_var.set(p)

    # ── 로그 ──────────────────────────────────────

    def _log(self, text, tag=None):
        self.out.config(state="normal")
        self.out.insert("end", text + "\n", tag or ())
        self.out.see("end")
        self.out.config(state="disabled")

    def _clear_log(self):
        self.out.config(state="normal")
        self.out.delete("1.0", "end")
        self.out.config(state="disabled")

    def _set_grade(self, grade, color, detail):
        self.grade_frame.config(bg=color)
        self.grade_label.config(text=grade, fg="white", bg=color)
        self.grade_detail.config(text=detail, fg="white", bg=color)

    # ── 분석 ──────────────────────────────────────

    def _run(self):
        audio = self.audio_var.get().strip()
        binf  = self.bin_var.get().strip()
        if not audio or not os.path.isfile(audio):
            messagebox.showerror("오류", "원곡 파일을 선택하세요."); return
        if not binf or not os.path.isfile(binf):
            messagebox.showerror("오류", "타임라인 바이너리를 선택하세요."); return

        preferred = self.engine_var.get()
        engine    = best_available_engine(preferred)
        if engine == "none":
            messagebox.showerror("라이브러리 없음",
                "분석 엔진 없음.\npip install librosa 설치 후 재시작하세요."); return
        if engine != preferred:
            messagebox.showwarning("엔진 변경",
                f"{ENGINE_INFO[preferred][0]} 미설치\n→ {ENGINE_INFO[engine][0]} 으로 대체")

        self._clear_log()
        self._set_grade("…", "#455a64", "분석 중…")
        self.run_btn.config(state="disabled")
        self.map_btn.config(state="disabled")
        self.save_btn.config(state="disabled")
        self._last_result = None
        self.status_var.set(f"분석 중… 엔진: {ENGINE_INFO[engine][0]}")

        threading.Thread(
            target=self._analyze_thread,
            args=(audio, binf, engine, self.tol_var.get(), self.bpm_hint_var.get()),
            daemon=True
        ).start()

    def _analyze_thread(self, audio, binf, engine, tol_ms, bpm_hint):
        try:
            self._do_analyze(audio, binf, engine, tol_ms, bpm_hint)
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            self.after(0, lambda: self._log(f"\n[오류] {e}\n{tb}", "red"))
            self.after(0, lambda: self._set_grade("!", "#b71c1c", f"오류: {e}"))
        finally:
            self.after(0, lambda: self.run_btn.config(state="normal"))
            self.after(0, lambda: self.status_var.set("완료"))

    def _do_analyze(self, audio_path, bin_path, engine, tol_ms, bpm_hint):
        SEP  = "─" * 62
        SEP2 = "═" * 62
        eng_name, eng_acc, _ = ENGINE_INFO[engine]

        # ① 바이너리
        self.after(0, lambda: self._log("[ 1/4 ]  타임라인 바이너리 파싱…", "gray"))
        version, frame_count, app_ms = parse_timeline_binary(bin_path)
        app_sec  = [t / 1000.0 for t in app_ms]
        app_st   = beat_stats(app_ms)
        music_id = extract_music_id(bin_path)

        def _lb():
            self._log(SEP, "gray")
            self._log(f"  바이너리   : {os.path.basename(bin_path)}")
            self._log(f"  Music ID   : {music_id}", "blue")
            self._log(f"  포맷 버전  : {version}")
            self._log(f"  총 프레임  : {frame_count}  (파싱: {len(app_ms)})")
            self._log(f"  감지 BPM   : {app_st.get('bpm',0):.1f}")
            self._log(f"  중앙 간격  : {app_st.get('median_ms',0):.1f} ms")
            g = app_st.get('gaps_600', 0)
            s = app_st.get('short_100', 0)
            self._log(f"  대형갭>600ms: {g}개{'  ← 주의' if g else ''}", "yellow" if g else None)
            self._log(f"  단기간<100ms: {s}개{'  ← 주의' if s else ''}", "yellow" if s else None)
            self._log(f"  이상 인터벌: {app_st.get('anomaly_pct',0):.1f}%")
        self.after(0, _lb)

        # ② Ground-truth
        self.after(0, lambda: self._log(f"\n[ 2/4 ]  Ground-truth 감지… ({eng_name} {eng_acc})", "gray"))
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

        def _lr():
            self._log(SEP, "gray")
            self._log(f"  원곡       : {os.path.basename(audio_path)}")
            self._log(f"  길이       : {duration_sec:.1f} 초")
            self._log(f"  엔진       : {eng_name}  ({eng_acc})", "green")
            self._log(f"  GT BPM     : {ref_bpm:.1f}")
            self._log(f"  GT 비트 수 : {len(ref_sec)}개")
            self._log(f"  중앙 간격  : {ref_st.get('median_ms',0):.1f} ms")
        self.after(0, _lr)

        # ③ 파형 로드
        self.after(0, lambda: self._log("\n[ 3/4 ]  파형 로드…", "gray"))
        waveform, wav_sr = load_waveform(audio_path)

        # ④ F-measure + 분류
        self.after(0, lambda: self._log(f"\n[ 4/4 ]  F-measure 계산 (±{tol_ms}ms)…", "gray"))
        tol_sec          = tol_ms / 1000.0
        f, p, r, tp, fp_cnt, fn = fmeasure(ref_sec, app_sec, tol_sec)
        tp_est, fp_est, fn_ref  = classify_beats(ref_sec, app_sec, tol_sec)

        bpm_err  = abs(app_st.get('bpm',0) - ref_bpm) / ref_bpm * 100 if ref_bpm > 0 else 0
        cov_pct  = min(100.0, (app_sec[-1] if app_sec else 0) / duration_sec * 100) \
                   if duration_sec > 0 else 0
        grade, g_color, verdict = grade_info(f, engine)

        bar = "█" * round(f * 50) + "░" * (50 - round(f * 50))
        advice = {"S":"서비스 적용 가능 수준입니다.",
                  "A":"대부분의 K-pop에서 정상 동작합니다.",
                  "B":"BPM은 정확하나 비트 타이밍 개선이 필요합니다.",
                  "C":"Ellis DP / Adaptive Threshold 튜닝을 권장합니다."
                  }.get(grade, "BPM 감지 로직부터 재검토가 필요합니다.")

        def _lr2():
            self._log(SEP2, "gray")
            self._log("  ▣  정확도 결과", "bold")
            self._log(SEP2, "gray")
            g_tag = "green" if grade in ("S","A") else ("yellow" if grade == "B" else "red")
            self._log(f"  등급       :  {grade}   {verdict}", g_tag)
            self._log(f"  F-measure  : {f*100:5.1f}%   [{bar}]", g_tag)
            self._log(f"  Precision  : {p*100:5.1f}%   (앱 비트 중 GT 일치)")
            self._log(f"  Recall     : {r*100:5.1f}%   (GT 비트 중 앱이 맞힘)")
            self._log(f"  TP / FP / FN : {tp} / {fp_cnt} / {fn}")
            self._log("")
            self._log(f"  BPM (앱)   : {app_st.get('bpm',0):.1f}")
            self._log(f"  BPM (GT)   : {ref_bpm:.1f}")
            self._log(f"  BPM 오차   : {bpm_err:.1f}%",
                      "green" if bpm_err < 5 else ("yellow" if bpm_err < 15 else "red"))
            self._log(f"  커버리지   : {cov_pct:.1f}%",
                      "green" if cov_pct > 95 else "yellow")
            self._log(f"\n  권고: {advice}", g_tag)
            self._log(SEP2, "gray")
            self._log("   S ≥85%  A 75~85%  B 60~75%  C 45~60%  D <45%", "gray")
            self._log(SEP2, "gray")
        self.after(0, _lr2)

        self.after(0, lambda: self._set_grade(
            grade, g_color,
            f"F-measure {f*100:.1f}%  |  BPM 앱 {app_st.get('bpm',0):.1f} / GT {ref_bpm:.1f}"
            f"  |  엔진: {eng_name}\n"
            f"Precision {p*100:.1f}%  /  Recall {r*100:.1f}%  /  커버리지 {cov_pct:.1f}%\n"
            f"{verdict} — {advice}"
        ))

        # 결과 캐시
        self._last_result = dict(
            ref_sec=ref_sec, app_sec=app_sec,
            tp_est=tp_est, fp_est=fp_est, fn_ref=fn_ref,
            waveform=waveform, wav_sr=wav_sr,
            duration_sec=duration_sec,
            f_score=f, bpm_app=app_st.get('bpm',0), bpm_ref=ref_bpm,
            engine_name=eng_name,
            audio_name=os.path.basename(audio_path),
            music_id=music_id, tol_ms=tol_ms,
        )
        if HAS_MPL:
            self.after(0, lambda: self.map_btn.config(state="normal"))
            self.after(0, lambda: self.save_btn.config(state="normal"))
        else:
            self.after(0, lambda: self._log(
                "\n  ※ matplotlib 미설치 — 비트맵 불가 (pip install matplotlib)", "yellow"))

    # ── 비트맵 창 ─────────────────────────────────

    def _show_beatmap(self):
        if not self._last_result or not HAS_MPL:
            return
        r = self._last_result
        fig = build_beatmap_figure(
            **r,
            zoom_start=self.zoom_s_var.get(),
            zoom_end=self.zoom_e_var.get(),
        )
        win = tk.Toplevel(self)
        win.title(f"Beat Map — {r['audio_name']}  ID:{r['music_id']}")
        win.geometry("1280x760")
        canvas = FigureCanvasTkAgg(fig, master=win)
        canvas.draw()
        canvas.get_tk_widget().pack(fill="both", expand=True)

        # 툴바
        from matplotlib.backends.backend_tkagg import NavigationToolbar2Tk
        toolbar = NavigationToolbar2Tk(canvas, win)
        toolbar.update()

    def _save_beatmap(self):
        if not self._last_result or not HAS_MPL:
            return
        r = self._last_result
        default = f"beatmap_{r['music_id']}.png"
        path = filedialog.asksaveasfilename(
            title="비트맵 이미지 저장",
            initialfile=default,
            defaultextension=".png",
            filetypes=[("PNG", "*.png"), ("PDF", "*.pdf"), ("All", "*.*")]
        )
        if not path:
            return
        fig = build_beatmap_figure(
            **r,
            zoom_start=self.zoom_s_var.get(),
            zoom_end=self.zoom_e_var.get(),
        )
        fig.savefig(path, dpi=150, bbox_inches="tight", facecolor="#1a1a2e")
        plt.close(fig)
        messagebox.showinfo("저장 완료", f"저장됨:\n{path}")


# ──────────────────────────────────────────────

if __name__ == "__main__":
    if not HAS_NUMPY:
        print("numpy가 필요합니다: pip install numpy"); sys.exit(1)
    if not (HAS_BT or HAS_MADMOM or HAS_LIBROSA):
        print("분석 엔진 없음. pip install librosa 설치 후 재시작하세요."); sys.exit(1)
    App().mainloop()
