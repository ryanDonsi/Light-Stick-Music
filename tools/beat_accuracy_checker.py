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
import json
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

# ──────────────────────────────────────────────
# 스플래시 화면 — 무거운 패키지 로드 전 즉시 표시
# ──────────────────────────────────────────────

# ──────────────────────────────────────────────
# 스플래시 화면 — 무거운 패키지 로드 전 즉시 표시
# ──────────────────────────────────────────────

def _show_splash():
    """앱 로딩 중 스플래시 창을 즉시 표시하고 위젯을 반환한다."""
    splash = tk.Tk()
    splash.title("")
    splash.resizable(False, False)
    splash.overrideredirect(True)
    splash.configure(bg="#1e272e")

    # 💡 OS별 설치된 한글 폰트를 동적으로 찾기
    from tkinter import font as tkfont
    available_fonts = tkfont.families(splash)
    kr_font = ""  # 기본 폰트
    font_candidates = [
        "Malgun Gothic", "맑은 고딕",            # Windows
        "AppleGothic", "Apple SD Gothic Neo",  # Mac
        "NanumGothic", "NanumSquare",          # Linux / 기타
        "Noto Sans KR", "Noto Sans CJK KR"     # 범용
    ]
    for f in font_candidates:
        if f in available_fonts:
            kr_font = f
            break

    # 스플래시 이미지 경로 (installer/ 폴더 또는 번들 내부)
    _is_frozen = getattr(sys, "frozen", False)
    _img_base  = sys._MEIPASS if _is_frozen else os.path.join(os.path.dirname(os.path.abspath(__file__)), "installer")
    splash_img_path = os.path.join(_img_base, "splash.png")

    W, H = 480, 200
    lbl_status = None
    try:
        from PIL import Image as _PILImage, ImageTk as _PILImageTk
        pil_img = _PILImage.open(splash_img_path).resize((W, H))
        tk_img  = _PILImageTk.PhotoImage(pil_img)
        lbl_img = tk.Label(splash, image=tk_img, bd=0)
        lbl_img.image = tk_img   # 참조 유지
        lbl_img.pack()
        use_image = True
    except Exception:
        use_image = False

    if not use_image:
        splash.configure(bg="#1e272e")
        W, H = 380, 160
        tk.Label(splash, text="Beat Accuracy Checker",
                 bg="#1e272e", fg="#ecf0f1").pack(pady=(28, 4))
        lbl_status = tk.Label(splash, text="Loading ...",
                 bg="#1e272e", fg="#78909c")
        lbl_status.pack()

    sw, sh = splash.winfo_screenwidth(), splash.winfo_screenheight()
    splash.geometry(f"{W}x{H}+{(sw-W)//2}+{(sh-H)//2}")

    # 진행 바 (이미지 위 오버레이 or 별도 프레임)
    bar_frame = tk.Frame(splash, bg="#1e272e" if not use_image else "#37474f")
    bar_frame.place(x=28, y=H-22, width=W-56, height=6)
    canvas = tk.Canvas(bar_frame, width=W-56, height=6, bg="#37474f",
                       highlightthickness=0, bd=0)
    canvas.pack()
    bar = canvas.create_rectangle(0, 0, 0, 6, fill="#42a5f5", outline="")

    _splash_state = {"pos": 0, "running": True, "job": None}
    bar_w = W - 56

    def _step_splash(msg, progress_pct):
        """라이브러리 로드 구간마다 텍스트와 프로그래스바를 수동 갱신"""
        if not _splash_state["running"]: return
        try:
            if lbl_status and not use_image:
                lbl_status.config(text=msg)
            p = int((progress_pct / 100.0) * bar_w)
            canvas.coords(bar, 0, 0, p, 6)
            splash.update()
        except tk.TclError:
            pass

    splash.update()
    return splash, _splash_state, _step_splash

_splash_root, _splash_state, _step_splash = _show_splash()

# ──────────────────────────────────────────────
# 의존 라이브러리 감지
# ──────────────────────────────────────────────

_step_splash("Loading Numpy module...", 15)
try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

_step_splash("Loading Matplotlib...", 35)
try:
    import matplotlib
    matplotlib.use("TkAgg")
    import matplotlib.pyplot as plt
    import matplotlib.gridspec as gridspec
    import matplotlib.patches as mpatches
    from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
    from matplotlib import font_manager

    def _setup_korean_font():
        """OS별 한글 폰트를 자동 탐색하여 matplotlib에 적용한다."""
        candidates = [
            "Malgun Gothic", "맑은 고딕", "AppleGothic", "Apple SD Gothic Neo",
            "NanumGothic", "NanumBarunGothic", "NanumSquare",
            "Noto Sans CJK KR", "Noto Sans KR", "UnDotum",
        ]
        available = {f.name for f in font_manager.fontManager.ttflist}
        for name in candidates:
            if name in available:
                matplotlib.rcParams["font.family"] = name
                matplotlib.rcParams["axes.unicode_minus"] = False
                return name
        matplotlib.rcParams["axes.unicode_minus"] = False
        return None

    _setup_korean_font()
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

_step_splash("Checking Beat Transformer...", 50)
HAS_BT = False
BT_LOCAL_PATH = ""
_is_frozen  = getattr(sys, "frozen", False)
_exe_dir    = os.path.dirname(sys.executable) if _is_frozen else os.path.dirname(os.path.abspath(__file__))
_bundle_dir = sys._MEIPASS if _is_frozen else _exe_dir
_script_dir = _exe_dir
_BT_CONF_FILE = os.path.join(_exe_dir, ".bt_path")

def _try_load_bt(local_path=""):
    global HAS_BT, BT_LOCAL_PATH
    for check_root in [local_path, os.path.dirname(local_path) if local_path else ""]:
        if not check_root or not os.path.isdir(check_root):
            continue
        if (os.path.isdir(os.path.join(check_root, "checkpoint")) and
                os.path.isdir(os.path.join(check_root, "code"))):
            BT_LOCAL_PATH = check_root
            HAS_BT = True
            return True
    return False

def _save_bt_path(path):
    try:
        with open(_BT_CONF_FILE, "w", encoding="utf-8") as f:
            f.write(path)
    except Exception: pass

def _load_bt_path():
    try:
        with open(_BT_CONF_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except Exception: return ""

def _auto_detect_bt():
    candidates = []
    for rel in ["Beat-Transformer", "beat-transformer", "beat_transformer"]:
        candidates.append(os.path.join(_exe_dir, rel))
    saved = _load_bt_path()
    if saved: candidates.append(saved)
    for rel in ["Beat-Transformer", "beat-transformer", "beat_transformer"]:
        candidates.append(os.path.join(_script_dir, "..", rel))
        candidates.append(os.path.join(_script_dir, "..", "..", rel))
    import string
    for drv in string.ascii_uppercase:
        root = f"{drv}:\\"
        if os.path.isdir(root):
            for rel in ["Beat-Transformer", "beat-transformer"]:
                candidates.append(os.path.join(root, rel))
    for path in candidates:
        if _try_load_bt(path):
            _save_bt_path(BT_LOCAL_PATH)
            return True
    return False

_auto_detect_bt()

_step_splash("Loading Madmom engine... (This may take a while)", 75)
HAS_MADMOM = False
try:
    import madmom
    HAS_MADMOM = True
except ImportError:
    pass

_step_splash("Loading Librosa engine...", 100)
HAS_LIBROSA = False
try:
    import librosa
    HAS_LIBROSA = True
except ImportError:
    pass

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
    from matplotlib import font_manager

    def _setup_korean_font():
        """OS별 한글 폰트를 자동 탐색하여 matplotlib에 적용한다."""
        candidates = [
            # Windows
            "Malgun Gothic", "맑은 고딕",
            # Mac
            "AppleGothic", "Apple SD Gothic Neo",
            # Linux (nanum 계열)
            "NanumGothic", "NanumBarunGothic", "NanumSquare",
            # 범용 CJK
            "Noto Sans CJK KR", "Noto Sans KR", "UnDotum",
        ]
        available = {f.name for f in font_manager.fontManager.ttflist}
        for name in candidates:
            if name in available:
                matplotlib.rcParams["font.family"] = name
                matplotlib.rcParams["axes.unicode_minus"] = False
                return name
        # 못 찾으면 마이너스 부호 깨짐만 방지
        matplotlib.rcParams["axes.unicode_minus"] = False
        return None

    _setup_korean_font()
    HAS_MPL = True
except ImportError:
    HAS_MPL = False

# Beat Transformer
HAS_BT = False
BT_LOCAL_PATH = ""
_is_frozen  = getattr(sys, "frozen", False)
# exe 실행 시: exe가 있는 폴더(Beat-Transformer 탐색·설정 저장용)
# 스크립트 실행 시: 스크립트가 있는 폴더
_exe_dir    = os.path.dirname(sys.executable) if _is_frozen else os.path.dirname(os.path.abspath(__file__))
# frozen 시 번들 내 임시 폴더(bt_infer.py 등 포함), 아니면 스크립트 폴더
_bundle_dir = sys._MEIPASS if _is_frozen else _exe_dir
_script_dir = _exe_dir
_BT_CONF_FILE = os.path.join(_exe_dir, ".bt_path")   # 경로 저장 파일

def _try_load_bt(local_path=""):
    """checkpoint/ + code/ 두 폴더가 있으면 유효한 Beat-Transformer 루트로 판단."""
    global HAS_BT, BT_LOCAL_PATH
    for check_root in [local_path,
                       os.path.dirname(local_path) if local_path else ""]:
        if not check_root or not os.path.isdir(check_root):
            continue
        if (os.path.isdir(os.path.join(check_root, "checkpoint")) and
                os.path.isdir(os.path.join(check_root, "code"))):
            BT_LOCAL_PATH = check_root
            HAS_BT = True
            return True
    return False

def _save_bt_path(path):
    try:
        with open(_BT_CONF_FILE, "w", encoding="utf-8") as f:
            f.write(path)
    except Exception:
        pass

def _load_bt_path():
    try:
        with open(_BT_CONF_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""

def _auto_detect_bt():
    """
    여러 위치를 순서대로 탐색해 Beat-Transformer 폴더를 자동 감지한다.
    frozen exe 배포판: exe 옆 Beat-Transformer/ 폴더가 항상 1순위
    1) exe/스크립트와 같은 폴더 (배포판 고정 위치)
    2) 이전 세션에서 저장한 경로 (.bt_path)
    3) 스크립트 상위 폴더들 (개발 환경)
    4) 현재 드라이브 루트 직하위 (C:\\Beat-Transformer 등)
    """
    candidates = []

    # 1. exe/스크립트 기준 고정 위치 (배포판 우선)
    for rel in ["Beat-Transformer", "beat-transformer", "beat_transformer"]:
        candidates.append(os.path.join(_exe_dir, rel))

    # 2. 저장된 경로
    saved = _load_bt_path()
    if saved:
        candidates.append(saved)

    # 3. 스크립트 상위 폴더 (개발 환경)
    for rel in ["Beat-Transformer", "beat-transformer", "beat_transformer"]:
        candidates.append(os.path.join(_script_dir, "..", rel))
        candidates.append(os.path.join(_script_dir, "..", "..", rel))

    # 4. 드라이브 루트 직하위 (Windows C:\ D:\ 등)
    import string
    for drv in string.ascii_uppercase:
        root = f"{drv}:\\"
        if os.path.isdir(root):
            for rel in ["Beat-Transformer", "beat-transformer"]:
                candidates.append(os.path.join(root, rel))

    for path in candidates:
        if _try_load_bt(path):
            _save_bt_path(BT_LOCAL_PATH)   # 다음 실행을 위해 저장
            return True
    return False

_auto_detect_bt()

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
    "madmom"          : ("madmom",           "~87%", "#0d47a1"),
    "librosa"         : ("librosa",          "~68%", "#e65100"),
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
    Beat Transformer 비트 감지.
    - frozen exe: bt_infer.run_inference() 를 직접 호출 (subprocess 없음)
    - 스크립트: bt_infer.py subprocess 호출 (기존 방식 유지)
    """
    bt_root  = BT_LOCAL_PATH or os.path.join(_exe_dir, "Beat-Transformer")
    ckpt_dir = os.path.join(bt_root, "checkpoint")
    code_dir = os.path.join(bt_root, "code")

    if not os.path.isdir(ckpt_dir):
        raise RuntimeError(
            f"checkpoint 폴더 없음: {ckpt_dir}\n"
            "Beat-Transformer 폴더가 exe와 같은 위치에 있는지 확인하세요."
        )

    if _is_frozen:
        # frozen exe: bt_infer 를 번들에서 직접 임포트하여 호출
        import importlib.util, types
        infer_py = os.path.join(_bundle_dir, "bt_infer.py")
        spec = importlib.util.spec_from_file_location("bt_infer", infer_py)
        bt_mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bt_mod)
        beats_sec, bpm = bt_mod.run_inference(audio_path, ckpt_dir, code_dir)
        if not beats_sec:
            raise RuntimeError("비트가 감지되지 않았습니다.")
        return beats_sec, bpm
    else:
        # 스크립트 실행: subprocess 방식 유지
        import subprocess, json, tempfile
        infer_script = os.path.join(_bundle_dir, "bt_infer.py")
        if not os.path.isfile(infer_script):
            raise RuntimeError(f"bt_infer.py 없음: {infer_script}")
        with tempfile.NamedTemporaryFile(suffix=".json", delete=False, mode="w") as tf:
            out_path = tf.name
        try:
            cmd = [sys.executable, infer_script,
                   "--audio", audio_path, "--checkpoint", ckpt_dir,
                   "--out", out_path, "--code_dir", code_dir]
            print(f"[BT] 실행: {' '.join(cmd)}")
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
            if result.stdout: print(result.stdout)
            if result.returncode != 0:
                raise RuntimeError(
                    f"bt_infer.py 실패 (exit {result.returncode}):\n{result.stderr[-1000:]}\n\n"
                    "pip install torch torchaudio librosa einops scipy 설치 후 재시도하세요."
                )
            with open(out_path) as f:
                data = json.load(f)
            beats_sec = sorted(float(b) for b in data.get("beats", []))
            if not beats_sec:
                raise RuntimeError("비트가 감지되지 않았습니다.")
            return beats_sec, _median_bpm(beats_sec)
        finally:
            if os.path.isfile(out_path):
                os.unlink(out_path)

def detect_beats_madmom(audio_path):
    """
    madmom DBNBeatTracker.
    우선 ffmpeg(직접)로 시도, 실패 시 librosa → WAV 변환으로 폴백.
    """
    import tempfile
    from madmom.features.beats import DBNBeatTrackingProcessor, RNNBeatProcessor

    def _run(path):
        act   = RNNBeatProcessor()(path)
        beats = DBNBeatTrackingProcessor(fps=100)(act)
        return sorted(float(b) for b in beats)

    # 1차: ffmpeg 경유 직접 로드 (madmom 기본)
    try:
        beats_sec = _run(audio_path)
        return beats_sec, _median_bpm(beats_sec)
    except Exception as e_direct:
        if not HAS_LIBROSA:
            raise RuntimeError(
                f"madmom 오디오 로드 실패: {e_direct}\n"
                "ffmpeg 설치 또는 pip install librosa 필요"
            ) from e_direct

    # 2차: librosa → 임시 WAV 변환 폴백
    import soundfile as sf
    y, sr = librosa.load(audio_path, sr=44100, mono=True, duration=600.0)
    tmp   = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    sf.write(tmp.name, y, sr)
    try:
        beats_sec = _run(tmp.name)
    finally:
        if os.path.isfile(tmp.name):
            os.unlink(tmp.name)
    return beats_sec, _median_bpm(beats_sec)

def detect_beats_librosa(audio_path, bpm_hint=0.0):
    y, sr = librosa.load(audio_path, sr=None, mono=True, duration=600.0)
    tempo_r, frames = librosa.beat.beat_track(
        y=y, sr=sr,
        start_bpm=float(bpm_hint) if bpm_hint > 0 else 120.0,
        tightness=100, trim=False)
    bpm = float(tempo_r[0]) if hasattr(tempo_r, '__len__') else float(tempo_r)
    beats_sec = sorted(librosa.frames_to_time(frames, sr=sr).tolist())
    return beats_sec, bpm

def compute_music_style_librosa(audio_path, beats_sec, bpm):
    """librosa로 음악 스타일 분류 (MusicStyleClassifier.kt와 동일한 로직)."""
    if not HAS_LIBROSA:
        return 'N/A', {}
    try:
        import numpy as _np
        y, sr = librosa.load(audio_path, sr=22050, mono=True, duration=300.0)
        beat_ms = int(60000 / bpm) if bpm > 0 else 500

        # 주파수 대역별 에너지
        hop_len = 512
        S = np.abs(librosa.stft(y, n_fft=2048, hop_length=hop_len))
        freqs = librosa.fft_frequencies(sr=sr, n_fft=2048)
        low_mask  = freqs < 300
        mid_mask  = (freqs >= 300) & (freqs < 3000)
        high_mask = freqs >= 3000

        low_env  = S[low_mask].mean(axis=0)
        mid_env  = S[mid_mask].mean(axis=0)
        high_env = S[high_mask].mean(axis=0)
        full_env = S.mean(axis=0)

        # 활성 프레임 (max의 5% 이상)
        threshold = full_env.max() * 0.05
        active = full_env > threshold
        if not active.any():
            active = np.ones(len(full_env), dtype=bool)

        def aavg(e): return float(e[active].mean()) if active.any() else 0.0

        avg_full = max(aavg(full_env), 1e-6)
        avg_low  = aavg(low_env)
        avg_mid  = aavg(mid_env)
        avg_high = aavg(high_env)
        band_sum = max(avg_low + avg_mid + avg_high, 1e-9)
        low_ratio  = avg_low  / band_sum
        mid_ratio  = avg_mid  / band_sum
        high_ratio = avg_high / band_sum

        # 에너지 정규화 (0~1 스케일로)
        rms = librosa.feature.rms(y=y, hop_length=hop_len)[0]
        rms_max = rms.max() + 1e-9
        rms_norm = rms / rms_max
        active_rms = rms_norm > 0.05
        avg_energy = float(rms_norm[active_rms].mean()) if active_rms.any() else 0.0

        # 비트 규칙성
        if len(beats_sec) >= 4:
            ivs = np.diff(beats_sec)
            mean_iv = float(ivs.mean())
            cv = float(ivs.std()) / max(mean_iv, 1e-9)
            periodicity = max(0.0, min(1.0, 1.0 - cv * 2.0))
        else:
            periodicity = 0.0

        # onset 밀도
        onset_frames = librosa.onset.onset_detect(y=y, sr=sr, hop_length=hop_len, backtrack=False)
        duration_sec = len(y) / sr
        onset_density = len(onset_frames) / duration_sec if duration_sec > 0 else 0.0

        # energy flux
        diff = np.diff(rms_norm)
        pos = diff[diff > 0]
        energy_flux = float(pos.mean()) if len(pos) > 0 else 0.0

        # MusicStyleClassifier와 동일한 분류 로직
        if beat_ms >= 700 and avg_energy < (0.62 if beat_ms >= 900 else 0.52):
            style = 'BALLAD'
        elif beat_ms < 480 and low_ratio >= 0.38 and periodicity >= 0.65 and onset_density >= 4.0:
            style = 'EDM'
        elif beat_ms < 545 and (low_ratio >= 0.36 or periodicity >= 0.50):
            style = 'DANCE_POP'
        elif 545 <= beat_ms < 750 and low_ratio >= 0.38 and periodicity < 0.62:
            style = 'HIPHOP_RNB'
        elif high_ratio >= 0.28 and energy_flux >= 0.040:
            style = 'ROCK'
        else:
            style = 'POP'

        features = dict(beat_ms=beat_ms, avg_energy=round(avg_energy,3),
                        low_ratio=round(low_ratio,3), mid_ratio=round(mid_ratio,3),
                        high_ratio=round(high_ratio,3), periodicity=round(periodicity,3),
                        onset_density=round(onset_density,2), energy_flux=round(energy_flux,4))
        return style, features
    except Exception as e:
        return 'N/A', {}


def detect_librosa_sections(audio_path, duration_sec, n_segments=10):
    """다중 특징 기반 섹션 분류.
    RMS 에너지 + Spectral centroid + Onset density + Chroma 유사도 + 시간적 위치
    를 복합 사용하여 INTRO/VERSE/PRE-CHORUS/CHORUS/BRIDGE/OUTRO 를 판별한다.
    """
    if not HAS_LIBROSA:
        return []
    try:
        load_dur = min(300.0, duration_sec) if duration_sec > 0 else 300.0
        y, sr = librosa.load(audio_path, sr=22050, mono=True, duration=load_dur)
        total_dur = len(y) / sr

        # ── 1. 구조적 경계 검출 (coarse chroma) ──
        hop_seg = 4096
        chroma_seg = librosa.feature.chroma_cqt(y=y, sr=sr, hop_length=hop_seg)
        k = min(n_segments, chroma_seg.shape[1] // 2)
        if k < 2:
            return [{"start_ms": 0, "end_ms": int(total_dur * 1000), "index": 0, "type": "INTRO"}]
        bounds = librosa.segment.agglomerative(chroma_seg, k)
        bound_times = librosa.frames_to_time(bounds, sr=sr, hop_length=hop_seg).tolist()
        all_times = [0.0] + bound_times + [total_dur]
        n = len(all_times) - 1

        # ── 2. 세부 특징 추출 ──
        hop_feat = 512
        chroma_f  = librosa.feature.chroma_cqt(y=y, sr=sr, hop_length=hop_feat)
        rms_f     = librosa.feature.rms(y=y, hop_length=hop_feat)[0]
        centroid_f = librosa.feature.spectral_centroid(y=y, sr=sr, hop_length=hop_feat)[0]
        onset_f   = librosa.onset.onset_strength(y=y, sr=sr, hop_length=hop_feat)

        # ── 3. 구간별 특징 집계 ──
        def _frames(t):
            return min(int(t * sr / hop_feat), chroma_f.shape[1])

        seg_feats = []
        for i in range(n):
            t0, t1 = all_times[i], all_times[i + 1]
            f0, f1 = _frames(t0), _frames(t1)
            if f1 <= f0:
                f1 = f0 + 1
            chroma_mean = chroma_f[:, f0:f1].mean(axis=1)
            rms_mean     = float(rms_f[f0:min(f1, len(rms_f))].mean())
            cent_mean    = float(centroid_f[f0:min(f1, len(centroid_f))].mean())
            onset_mean   = float(onset_f[f0:min(f1, len(onset_f))].mean())
            seg_feats.append({
                'chroma':   chroma_mean,
                'rms':      rms_mean,
                'centroid': cent_mean,
                'onset':    onset_mean,
                'pos':      (t0 + t1) / 2 / total_dur,
            })

        # ── 4. 특징 정규화 (0–1) ──
        def _norm(vals):
            a = np.array(vals, dtype=float)
            mn, mx = a.min(), a.max()
            return (a - mn) / (mx - mn + 1e-8)

        rms_n   = _norm([f['rms']      for f in seg_feats])
        cent_n  = _norm([f['centroid'] for f in seg_feats])
        onset_n = _norm([f['onset']    for f in seg_feats])

        # ── 5. 크로마 유사도 그룹화 ──
        def _cos_sim(a, b):
            na, nb = np.linalg.norm(a), np.linalg.norm(b)
            return float(np.dot(a, b) / (na * nb)) if na > 1e-6 and nb > 1e-6 else 0.0

        labels = [-1] * n
        gid = 0
        for i in range(n):
            if labels[i] >= 0:
                continue
            labels[i] = gid
            for j in range(i + 1, n):
                if labels[j] < 0 and _cos_sim(seg_feats[i]['chroma'], seg_feats[j]['chroma']) >= 0.90:
                    labels[j] = gid
            gid += 1

        from collections import Counter
        grp_cnt = Counter(labels)
        rep_n = _norm([float(grp_cnt[labels[i]]) for i in range(n)])

        # ── 6. CHORUS 그룹 판별 복합 점수 ──
        # 반복도 30% + 에너지 35% + 밝기 20% + onset 15%
        chorus_score = 0.30 * rep_n + 0.35 * rms_n + 0.20 * cent_n + 0.15 * onset_n

        grp_scores: dict = {}
        for i in range(n):
            grp_scores.setdefault(labels[i], []).append(chorus_score[i])
        grp_avg = {g: float(np.mean(v)) for g, v in grp_scores.items()}
        ranked  = sorted(grp_avg.items(), key=lambda x: x[1], reverse=True)

        chorus_grp = ranked[0][0] if ranked else -1
        verse_grp  = ranked[1][0] if len(ranked) > 1 else -1

        # ── 7. 1차 레이블 부여 ──
        types = []
        for i in range(n):
            pos = seg_feats[i]['pos']
            g   = labels[i]
            if i == 0 or pos < 0.10:
                types.append("INTRO")
            elif i == n - 1 or pos > 0.90:
                types.append("OUTRO")
            elif g == chorus_grp and rms_n[i] >= 0.35:
                types.append("CHORUS")
            elif g == verse_grp:
                types.append("VERSE")
            elif grp_cnt[g] == 1:
                types.append("BRIDGE")
            else:
                types.append("VERSE")

        # ── 8. POST: PRE-CHORUS 탐지 ──
        # VERSE 구간 중 다음이 CHORUS이고 에너지가 VERSE 평균보다 높으면 PRE-CHORUS
        verse_e = [rms_n[i] for i in range(n) if types[i] == "VERSE"]
        verse_mean = float(np.mean(verse_e)) if verse_e else 0.5
        for i in range(1, n - 1):
            if types[i] == "VERSE" and types[i + 1] == "CHORUS":
                if rms_n[i] > verse_mean + 0.12:
                    types[i] = "PRE-CHORUS"

        return [
            {"start_ms": int(all_times[i] * 1000),
             "end_ms":   int(all_times[i + 1] * 1000),
             "index":    i,
             "type":     types[i]}
            for i in range(n)
        ]
    except Exception:
        return []


def load_waveform(audio_path, max_sr=22050, max_duration=600.0):
    """파형 로드 (librosa 있을 때만). → (y, sr) or (None, 0)
    max_duration 초 초과 파일은 앞부분만 로드 (앨범 통합 mp3 대응).
    """
    if not HAS_LIBROSA:
        return None, 0
    try:
        duration = librosa.get_duration(path=audio_path)
        offset, dur = 0.0, None
        if duration > max_duration:
            dur = max_duration
        y, sr = librosa.load(audio_path, sr=max_sr, mono=True,
                             offset=offset, duration=dur)
        return y, sr
    except Exception:
        return None, 0

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

def compute_music_id(path: str) -> int:
    """SDK와 동일한 알고리즘: SHA-256 앞 4바이트를 Little Endian u32로 변환.
    20MB 이하: 파일 전체 읽기 / 20MB 초과: 앞 4MB만 읽기 (SDK 동일 정책).
    """
    import hashlib, struct as _struct
    THRESHOLD = 20 * 1024 * 1024  # 20 MB
    READ_LIMIT = 4 * 1024 * 1024  # 4 MB
    file_size = os.path.getsize(path)
    with open(path, "rb") as f:
        data = f.read(READ_LIMIT if file_size > THRESHOLD else file_size)
    digest = hashlib.sha256(data).digest()
    return _struct.unpack("<I", digest[:4])[0]

def _fmt_sec(sec: float) -> str:
    """초(float)를 mm:ss 형식 문자열로 변환."""
    sec = max(0.0, sec)
    m   = int(sec) // 60
    s   = int(sec) % 60
    return f"{m:02d}:{s:02d}"

def _to_signed_display(val: int) -> str:
    """unsigned int32 값을 Android signed int32 표기로 변환 (표시 전용)."""
    if val >= (1 << 31):
        val -= (1 << 32)
    return str(val)

def extract_music_id(filename):
    """파일명에서 Music ID(숫자)를 추출. 음수(Android signed int32)도 unsigned로 변환."""
    name = os.path.splitext(os.path.basename(filename))[0]

    def _to_unsigned(val_str):
        v = int(val_str)
        if v < 0:
            v = v + (1 << 32)
        return str(v)

    m = re.search(r'timeline[_\-](-?\d+)', name)
    if m: return _to_unsigned(m.group(1))
    if re.fullmatch(r'-?\d+', name): return _to_unsigned(name)
    m = re.search(r'(-?\d{5,})', name)
    if m: return _to_unsigned(m.group(1))
    return None

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

# SectionDetector.SectionType enum 순서 (Kotlin 정의와 동일해야 함)
_SECTION_TYPES = ['INTRO', 'VERSE', 'PRE-CHORUS', 'CHORUS', 'BRIDGE', 'END',
                  'VOCAL', 'BEAT', 'BUILD', 'CLIMAX', 'BREAK', 'OUTRO']

def parse_section_meta_binary(path):
    """SectionMetaStorage 바이너리 파싱.
    반환: (version, list of section dicts)
    각 dict: start_ms, end_ms, type, beat_ms, beat_confidence,
             energy, peak_energy, low_ratio, mid_ratio, high_ratio,
             onset_density, periodicity, beat_times_ms (List[int])
    """
    with open(path, "rb") as f:
        data = f.read()
    offset = 0
    if len(data) < 12:
        return 0, 'N/A', []
    version   = struct.unpack_from(">i", data, offset)[0]; offset += 4
    _style    = struct.unpack_from(">i", data, offset)[0]; offset += 4
    count     = struct.unpack_from(">i", data, offset)[0]; offset += 4
    sections  = []
    for _ in range(count):
        if offset + 8 > len(data): break
        start_ms      = struct.unpack_from(">q", data, offset)[0]; offset += 8
        end_ms        = struct.unpack_from(">q", data, offset)[0]; offset += 8
        type_ord      = struct.unpack_from(">i", data, offset)[0]; offset += 4
        _str_ord      = struct.unpack_from(">i", data, offset)[0]; offset += 4
        beat_ms       = struct.unpack_from(">q", data, offset)[0]; offset += 8
        beat_conf     = struct.unpack_from(">f", data, offset)[0]; offset += 4
        energy        = struct.unpack_from(">f", data, offset)[0]; offset += 4
        peak_energy   = struct.unpack_from(">f", data, offset)[0]; offset += 4
        low_ratio     = struct.unpack_from(">f", data, offset)[0]; offset += 4
        mid_ratio     = struct.unpack_from(">f", data, offset)[0]; offset += 4
        high_ratio    = struct.unpack_from(">f", data, offset)[0]; offset += 4
        onset_density = struct.unpack_from(">f", data, offset)[0]; offset += 4
        periodicity   = struct.unpack_from(">f", data, offset)[0]; offset += 4
        # beatTimesMs (VERSION=1 이상)
        beat_times_ms = []
        if offset + 4 <= len(data):
            beat_count = struct.unpack_from(">i", data, offset)[0]; offset += 4
            for _ in range(beat_count):
                if offset + 8 > len(data): break
                beat_times_ms.append(struct.unpack_from(">q", data, offset)[0]); offset += 8
        sections.append({
            "start_ms":      start_ms,
            "end_ms":        end_ms,
            "type":          _SECTION_TYPES[type_ord] if 0 <= type_ord < len(_SECTION_TYPES) else "VERSE",
            "beat_ms":       beat_ms,
            "beat_confidence": beat_conf,
            "energy":        round(energy, 4),
            "peak_energy":   round(peak_energy, 4),
            "low_ratio":     round(low_ratio, 4),
            "mid_ratio":     round(mid_ratio, 4),
            "high_ratio":    round(high_ratio, 4),
            "onset_density": round(onset_density, 4),
            "periodicity":   round(periodicity, 4),
            "beat_times_ms": beat_times_ms,
        })
    style_name = _MUSIC_STYLES[_style] if 0 <= _style < len(_MUSIC_STYLES) else 'N/A'
    return version, style_name, sections

# MusicStyleClassifier enum 순서 (Kotlin MusicStyle enum과 동일)
_MUSIC_STYLES = ['EDM', 'DANCE_POP', 'HIPHOP_RNB', 'BALLAD', 'ROCK', 'POP']

# 스타일별 색상
_STYLE_COLORS = {
    'EDM':       '#00e5ff',  # 청록
    'DANCE_POP': '#ffab40',  # 주황
    'HIPHOP_RNB':'#ce93d8',  # 보라
    'BALLAD':    '#80cbc4',  # 민트
    'ROCK':      '#ef5350',  # 빨강
    'POP':       '#a5d6a7',  # 연두
    'N/A':       '#546e7a',  # 회색
}

# 섹션 타입별 색상
_SECTION_COLORS = {
    'INTRO':       '#7c4dff', 'VERSE':  '#1565c0', 'PRE-CHORUS': '#00838f',
    'CHORUS':      '#e65100', 'BRIDGE': '#2e7d32', 'END':        '#b71c1c',
    'VOCAL':       '#00acc1', 'BEAT':   '#f57f17', 'BUILD':      '#0277bd',
    'CLIMAX':      '#ad1457', 'BREAK':  '#546e7a', 'OUTRO':      '#4a148c',
}

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
    pct = round(f_score * 100, 1)
    th = [(90,"S","#1b5e20","최고 수준"),
          (80,"A","#1565c0","상용 서비스 수준"),
          (60,"B","#f57f17","실사용 가능 - 일부 구간 오류"),
          (40,"C","#bf360c","다수 구간 오류"),
          ( 0,"D","#b71c1c","비트 감지 실패")]
    for t, g, c, v in th:
        if pct >= t: return g, c, v
    return "D", "#b71c1c", "비트 감지 실패"

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
        f"BPM 앱 {bpm_app:.1f} / {engine_name} {bpm_ref:.1f}  |  "
        f"허용 오차 ±{tol_ms}ms  |  엔진: {engine_name}",
        color="white", fontsize=11, y=0.98
    )

    # ── 범례 패치 ─────────────────────────────────
    legend_patches = [
        mpatches.Patch(color=C_TP, label=f"TP  일치  ({len(tp_est)}개)"),
        mpatches.Patch(color=C_FP, label=f"FP  앱 오감지  ({len(fp_est)}개)"),
        mpatches.Patch(color=C_FN, label=f"FN  {engine_name} 누락  ({len(fn_ref)}개)"),
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
    ax_full.set_title(f"전체 비트맵  (파형 / {engine_name} / 앱)", color="#90a4ae", fontsize=9, loc="left")
    _draw_beat_lanes(ax_full, ref_sec, app_sec, tp_est, fp_est, fn_ref,
                     0, duration_sec, C_TP, C_FP, C_FN, C_GRID, engine_name=engine_name,
                     waveform=waveform, wav_sr=wav_sr, duration_sec=duration_sec)

    # 줌 범위 기본값: 처음 30초
    if zoom_start is None: zoom_start = 0.0
    if zoom_end   is None: zoom_end   = min(30.0, duration_sec)

    # ── [3] 줌 비트맵 ─────────────────────────────
    ax_zoom.set_title(
        f"줌 비트맵  ({_fmt_sec(zoom_start)} ~ {_fmt_sec(zoom_end)})  "
        "— 비트 간격·오차를 상세히 확인",
        color="#90a4ae", fontsize=9, loc="left"
    )
    _draw_beat_lanes(ax_zoom, ref_sec, app_sec, tp_est, fp_est, fn_ref,
                     zoom_start, zoom_end, C_TP, C_FP, C_FN, C_GRID,
                     show_ms_label=True, engine_name=engine_name,
                     waveform=waveform, wav_sr=wav_sr, duration_sec=duration_sec)

    # ── [4] 비트 인터벌 히스토그램 ────────────────
    ax_hist.set_title("비트 간격 분포  (ms)", color="#90a4ae", fontsize=9, loc="left")
    ref_iv  = np.diff(np.array(ref_sec)) * 1000
    app_iv  = np.diff(np.array(app_sec)) * 1000
    bins    = np.arange(200, 1001, 20)
    ax_hist.hist(ref_iv,  bins=bins, color=C_FN, alpha=0.6, label=f"{engine_name}  중앙 {np.median(ref_iv):.0f}ms")
    ax_hist.hist(app_iv,  bins=bins, color=C_TP, alpha=0.6, label=f"앱  중앙 {np.median(app_iv):.0f}ms")
    # BPM 수직선
    if bpm_ref > 0:
        ax_hist.axvline(60_000/bpm_ref, color=C_FN, linestyle="--", linewidth=1.2,
                        label=f"{engine_name} BPM {bpm_ref:.1f}")
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
                     show_ms_label=False, engine_name="GT",
                     waveform=None, wav_sr=0, duration_sec=0):
    """파형(최상단) + GT 레인 + 앱 레인을 하나의 axes에 그린다."""
    # Y 레이아웃: 파형(2.5~3.35) | 구분선(2.4) | GT(1.8) | 구분선(1.2) | 앱(0.6)
    WAVE_CTR  = 2.875
    WAVE_HALF = 0.375
    GT_BOT    = 1.525
    APP_BOT   = 0.325
    bar_h     = 0.55

    ax.set_xlim(t_start, t_end)
    ax.set_ylim(0, 3.6)
    ax.set_yticks([0.6, 1.8, WAVE_CTR])
    ax.set_yticklabels(["앱", engine_name, "파형"], color="#90a4ae", fontsize=8)
    ax.set_xlabel("시간 (초)", color="#90a4ae", fontsize=8)
    ax.axhline(1.2, color=C_GRID, linewidth=0.5)
    ax.axhline(2.4, color=C_GRID, linewidth=0.5)

    def in_range(t):
        return t_start <= t <= t_end

    # ── 파형 레인 ─────────────────────────────────
    C_WAVE = "#b0bec5"
    if waveform is not None and wav_sr > 0 and duration_sec > 0:
        total_samples = len(waveform)
        s_start = max(0, int(t_start / duration_sec * total_samples))
        s_end   = min(total_samples, int(t_end / duration_sec * total_samples))
        seg = waveform[s_start:s_end]
        if len(seg) > 1:
            # 화면 픽셀보다 많으면 다운샘플
            if len(seg) > 3000:
                step = len(seg) // 3000
                seg  = seg[::step]
            times_seg = np.linspace(t_start, t_end, len(seg))
            peak = float(np.max(np.abs(seg)))
            if peak < 1e-9:
                peak = 1.0
            seg_scaled = seg / peak * WAVE_HALF
            ax.plot(times_seg, WAVE_CTR + seg_scaled,
                    color=C_WAVE, linewidth=0.9, alpha=0.85, zorder=3)
            ax.fill_between(times_seg, WAVE_CTR, WAVE_CTR + seg_scaled,
                            color=C_WAVE, alpha=0.25, zorder=2)

    # ── GT 레인 (y=1.8 중심) ──────────────────────
    for t in [x for x in fn_ref if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=GT_BOT, color=C_FN, alpha=0.9)
    for t in [x for x in tp_est if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=GT_BOT, color=C_TP, alpha=0.9)

    # ── 앱 레인 (y=0.6 중심) ──────────────────────
    for t in [x for x in tp_est if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=APP_BOT, color=C_TP, alpha=0.9)
    for t in [x for x in fp_est if in_range(x)]:
        ax.bar(t, bar_h, width=0.012, bottom=APP_BOT, color=C_FP, alpha=0.9)

    # ── ms 오차 라벨 (줌 뷰 전용) ─────────────────
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

class _Tooltip:
    """위젯에 마우스 오버 툴팁을 달아주는 헬퍼."""

    _BG  = "#37474f"
    _FG  = "#eceff1"
    _PAD = 6

    def __init__(self, widget, text, delay=400):
        self._widget = widget
        self._text   = text
        self._delay  = delay
        self._tip    = None
        self._job    = None
        widget.bind("<Enter>",    self._schedule, add="+")
        widget.bind("<Leave>",    self._cancel,   add="+")
        widget.bind("<Button>",   self._cancel,   add="+")

    def _schedule(self, _evt=None):
        self._cancel()
        self._job = self._widget.after(self._delay, self._show)

    def _cancel(self, _evt=None):
        if self._job:
            self._widget.after_cancel(self._job)
            self._job = None
        if self._tip:
            self._tip.destroy()
            self._tip = None

    def _show(self):
        x = self._widget.winfo_rootx() + self._widget.winfo_width() // 2
        y = self._widget.winfo_rooty() + self._widget.winfo_height() + 4
        self._tip = tw = tk.Toplevel(self._widget)
        tw.wm_overrideredirect(True)
        tw.wm_geometry(f"+{x}+{y}")
        tw.attributes("-topmost", True)
        lbl = tk.Label(tw, text=self._text, justify="left",
                       bg=self._BG, fg=self._FG,
                       font=("", 9), relief="solid", bd=1,
                       padx=self._PAD, pady=self._PAD,
                       wraplength=320)
        lbl.pack()


def _attach_tooltip(widget, text):
    _Tooltip(widget, text)


# ── 각 지표별 툴팁 설명 ────────────────────────────────────────────
_TIPS = {
    "F-measure": (
        "F-measure (F1 점수)\n"
        "Precision과 Recall의 조화평균.\n"
        "비트 감지 정확도를 나타내는 종합 지표.\n\n"
        "F = 2·P·R / (P+R)\n"
        "허용 오차 ±70ms 이내를 '일치'로 판정.\n"
        "  S ≥ 90%  /  A ≥ 80%  /  B ≥ 70%\n"
        "  C ≥ 55%  /  D < 55%"
    ),
    "Precision": (
        "Precision (정밀도)\n"
        "앱이 감지한 비트 중 실제로 맞은 비율.\n\n"
        "P = TP / (TP + FP)\n\n"
        "낮으면 앱이 없는 비트를 많이 만들어낸 것 (오감지 과다)."
    ),
    "Recall": (
        "Recall (재현율)\n"
        "실제 비트 중 앱이 맞게 감지한 비율.\n\n"
        "R = TP / (TP + FN)\n\n"
        "낮으면 앱이 실제 비트를 많이 놓친 것 (누락 과다)."
    ),
    "커버리지": (
        "커버리지 (Coverage)\n"
        "앱 비트가 곡 전체 구간에 얼마나 고르게 분포하는지.\n\n"
        "= (마지막 비트 시각 − 첫 비트 시각) / 곡 길이\n\n"
        "낮으면 곡의 앞이나 뒤 구간에 비트가 없는 것."
    ),
    "TP (일치)": (
        "True Positive (일치)\n"
        "앱 비트와 GT 비트가 ±허용오차 이내에서 매칭된 수.\n"
        "많을수록 좋음."
    ),
    "FP (오감지)": (
        "False Positive (오감지)\n"
        "앱이 감지했지만 GT 기준으로는 존재하지 않는 비트.\n"
        "많으면 타임라인에 불필요한 이벤트가 많다는 뜻."
    ),
    "FN (누락)": (
        "False Negative (누락)\n"
        "GT에는 있지만 앱이 감지하지 못한 비트.\n"
        "많으면 타임라인에 빠진 이벤트가 많다는 뜻."
    ),
    "대형갭": (
        "대형갭 (Large Gap)\n"
        "연속 비트 간격이 예상 박자 간격의 2배를 초과하는 횟수.\n\n"
        "예: BPM 130이면 정상 간격 ≈ 461ms,\n"
        "    922ms 초과 간격이 나타날 때마다 +1 카운트.\n\n"
        "0이 이상적. 높으면 특정 구간에서 비트가 뚝 끊긴 것."
    ),
    "BPM (앱)": (
        "BPM — 앱 타임라인 기준\n"
        "앱이 생성한 타임라인의 비트 간격 중앙값으로 계산한 BPM.\n"
        "(60 000ms / 중앙 간격ms)"
    ),
    "BPM (GT)": (
        "BPM — Ground Truth 기준\n"
        "Beat Transformer / madmom / librosa가 감지한\n"
        "실제 비트 간격 중앙값으로 계산한 BPM."
    ),
    "BPM 오차": (
        "BPM 오차\n"
        "앱 BPM과 GT BPM의 절대 차이.\n\n"
        "±2 BPM 이내면 정상,\n"
        "5 이상이면 템포 자체가 맞지 않을 가능성이 높음."
    ),
}


class App(tk.Tk):
    _STATE_FILE = os.path.join(os.path.dirname(__file__), ".beat_checker_state.json")

    def __init__(self):
        super().__init__()
        self.title("Beat Accuracy Checker")
        self.geometry("1920x1080")
        self.resizable(True, True)
        self.minsize(1280, 800)
        self._last_result = None           # 비트맵 재렌더용 캐시
        self._card_widgets = {}            # engine → widget dict
        self._selected_card_engine = ""   # 현재 선택된 카드 엔진
        self.engine_var = tk.StringVar(value="beat_transformer")  # 호환용
        self._build_ui()
        self._load_state()

    def _build_ui(self):
        pad = dict(padx=6, pady=3)

        # ── 상단 배너 ──────────────────────────────
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

        # ── 상태바 (하단 고정) ──────────────────────
        self.status_var = tk.StringVar(value="음악 파일을 추가하고 [▶ 분석]을 누르세요.")
        tk.Label(self, textvariable=self.status_var, anchor="w",
                 relief="sunken", fg="#555").pack(fill="x", side="bottom")

        # ── 하단 비트 타임라인 ─────────────────────
        tl_frame = tk.LabelFrame(self, text="비트 타임라인",
                                 bg="#1a1a2e", fg="#90a4ae")
        tl_frame.pack(fill="x", side="bottom", padx=6, pady=(0, 2))

        tl_ctrl = tk.Frame(tl_frame, bg="#1a1a2e")
        tl_ctrl.pack(fill="x", padx=6, pady=(3, 2))
        legend_frame = tk.Frame(tl_ctrl, bg="#1a1a2e")
        legend_frame.pack(side="left")
        for lc, lt in [("#69f0ae", "■ 일치(TP)"), ("#ff5252", "■ 오감지(FP)"), ("#448aff", "■ 누락(FN)")]:
            tk.Label(legend_frame, text=lt, bg="#1a1a2e", fg=lc,
                     font=("", 8, "bold")).pack(side="left", padx=6)
        tk.Label(tl_ctrl, text="  |", bg="#1a1a2e", fg="#37474f", font=("", 8)).pack(side="left")
        # 섹션 범례 (우측)
        sec_legend = tk.Frame(tl_ctrl, bg="#1a1a2e")
        sec_legend.pack(side="right", padx=(0, 6))
        tk.Label(sec_legend, text="섹션:", bg="#1a1a2e", fg="#546e7a",
                 font=("", 7)).pack(side="left", padx=(0, 3))
        for stype in ["INTRO", "VERSE", "PRE-CHORUS", "CHORUS", "BRIDGE", "OUTRO", "VOCAL", "BEAT", "BUILD", "CLIMAX", "BREAK", "END"]:
            sc = _SECTION_COLORS.get(stype, "#546e7a")
            tk.Label(sec_legend, text=f"■{stype}", bg="#1a1a2e", fg=sc,
                     font=("", 7, "bold")).pack(side="left", padx=2)
        tk.Label(tl_ctrl, text="시작(초/mm:ss):", bg="#1a1a2e", fg="#90a4ae",
                 font=("", 8)).pack(side="left", padx=(8, 0))
        self.zoom_s_var = tk.DoubleVar(value=0.0)
        tk.Entry(tl_ctrl, textvariable=self.zoom_s_var, width=5,
                 bg="#263238", fg="white", insertbackground="white").pack(side="left", padx=2)
        tk.Label(tl_ctrl, text="끝(초/mm:ss):", bg="#1a1a2e", fg="#90a4ae",
                 font=("", 8)).pack(side="left")
        self.zoom_e_var = tk.DoubleVar(value=30.0)
        tk.Entry(tl_ctrl, textvariable=self.zoom_e_var, width=5,
                 bg="#263238", fg="white", insertbackground="white").pack(side="left", padx=2)
        tk.Button(tl_ctrl, text="갱신", command=self._redraw_timeline,
                  bg="#455a64", fg="white", font=("", 8), pady=1).pack(side="left", padx=4)
        tk.Label(tl_ctrl, text="전체보기: 시작=0, 끝=곡길이",
                 bg="#1a1a2e", fg="#546e7a", font=("", 7)).pack(side="left")

        # 캔버스 높이: TICK_H(16) + BEAT_H*3(132) + SEC_H*2(44) = 192px (공백 없음)
        self.tl_canvas = tk.Canvas(tl_frame, bg="#0d1117", height=192, highlightthickness=0)
        self.tl_canvas.pack(fill="x", padx=4, pady=(0, 4))
        self.tl_canvas.bind("<Configure>", lambda e: self._redraw_timeline())

        # ── 2열 메인 영역 ──────────────────────────
        main = tk.Frame(self)
        main.pack(fill="both", expand=True, padx=6, pady=4)
        main.columnconfigure(0, weight=1, minsize=180)
        main.columnconfigure(1, weight=5, minsize=700)
        main.rowconfigure(0, weight=1)

        # ══════════════════════════════════════════
        # 1열: 설정 + 타임라인 폴더 + 음악 목록 + 버튼
        # ══════════════════════════════════════════
        col1 = tk.Frame(main)
        col1.grid(row=0, column=0, sticky="nsew", padx=(0, 3))
        col1.columnconfigure(0, weight=1)
        col1.rowconfigure(1, weight=1)

        # 설정값 (UI 없이 기본값 유지)
        self.tol_var      = tk.IntVar(value=70)
        self.bpm_hint_var = tk.DoubleVar(value=0.0)

        # ── 타임라인 바이너리 폴더 ────────────────
        ff = tk.LabelFrame(col1, text="타임라인 바이너리 폴더", **pad)
        ff.grid(row=0, column=0, sticky="ew", **pad)
        ff.columnconfigure(0, weight=1)
        ff_inner = tk.Frame(ff)
        ff_inner.pack(fill="x", padx=4, pady=(2, 0))
        self._bin_folder_var = tk.StringVar()
        tk.Entry(ff_inner, textvariable=self._bin_folder_var).pack(
            side="left", fill="x", expand=True, padx=(0, 4))
        tk.Button(ff_inner, text="폴더 선택",
                  command=self._pick_bin_folder).pack(side="left", padx=2)
        tk.Button(ff_inner, text="↺", command=self._refresh_matching,
                  bg="#455a64", fg="white", font=("", 9), width=2).pack(side="left", padx=2)
        self._match_status_var = tk.StringVar(value="폴더를 선택하세요.")
        tk.Label(ff, textvariable=self._match_status_var,
                 fg="#78909c", font=("", 8), anchor="w").pack(fill="x", padx=6, pady=(1, 4))

        # ── 음악 파일 목록 ─────────────────────────
        self._audio_items = {}
        fm = tk.LabelFrame(col1, text="음악 파일 목록", **pad)
        fm.grid(row=1, column=0, sticky="nsew", **pad)
        fm.rowconfigure(1, weight=1)
        fm.columnconfigure(0, weight=1)

        btn_row = tk.Frame(fm)
        btn_row.grid(row=0, column=0, sticky="ew", padx=4, pady=2)
        tk.Button(btn_row, text="＋ 추가", command=self._add_audio_files,
                  bg="#1565c0", fg="white", font=("", 9, "bold")).pack(side="left", padx=2)
        tk.Button(btn_row, text="－ 제거", command=self._remove_audio_file,
                  font=("", 9)).pack(side="left", padx=2)

        tree_wrap = tk.Frame(fm)
        tree_wrap.grid(row=1, column=0, sticky="nsew", padx=4, pady=(0, 4))
        tree_wrap.rowconfigure(0, weight=1)
        tree_wrap.columnconfigure(0, weight=1)
        self._tree = ttk.Treeview(tree_wrap,
                                   columns=("name", "id", "match", "bt", "madmom", "librosa"),
                                   show="headings", height=8, selectmode="browse")
        self._tree.heading("name",    text="파일명")
        self._tree.heading("id",      text="Music ID")
        self._tree.heading("match",   text="타임라인")
        self._tree.heading("bt",      text="BT")
        self._tree.heading("madmom",  text="madmom")
        self._tree.heading("librosa", text="librosa")
        self._tree.column("name",    width=130, stretch=True)
        self._tree.column("id",      width=90,  stretch=False, anchor="center")
        self._tree.column("match",   width=110, stretch=True)
        self._tree.column("bt",      width=60,  stretch=False, anchor="center")
        self._tree.column("madmom",  width=60,  stretch=False, anchor="center")
        self._tree.column("librosa", width=60,  stretch=False, anchor="center")
        # Treeview 기본 스타일: 배경 흰색, 기본 글자 진회색
        _ts = ttk.Style()
        _ts.configure("Treeview",
                       foreground="#546e7a", rowheight=24)
        _ts.configure("Treeview.Heading",
                       foreground="#546e7a", relief="flat")
        # 매칭 상태 태그 (파일명/ID/타임라인 컬럼용 행 색상)
        self._tree.tag_configure("matched",   foreground="#546e7a")
        self._tree.tag_configure("unmatched", foreground="#c62828")
        self._tree.tag_configure("pending",   foreground="#546e7a")
        # 등급별 태그 (분석 결과가 있을 때 행 전체에 적용)
        for _g, _c in [("S", "#00897b"), ("A", "#1976d2"),
                        ("B", "#f9a825"), ("C", "#e65100"), ("D", "#c62828")]:
            self._tree.tag_configure(f"grade_{_g}", foreground=_c)
        tree_sb = tk.Scrollbar(tree_wrap, orient="vertical", command=self._tree.yview)
        self._tree.configure(yscrollcommand=tree_sb.set)
        self._tree.grid(row=0, column=0, sticky="nsew")
        tree_sb.grid(row=0, column=1, sticky="ns")
        self._tree.bind("<<TreeviewSelect>>", self._on_list_select)

        # ── 버튼 행 ───────────────────────────────
        btn_frame = tk.Frame(col1)
        btn_frame.grid(row=3, column=0, pady=4)
        self.run_btn = tk.Button(btn_frame, text="▶ 분석",
                                 font=("", 10, "bold"), bg="#2e7d32", fg="white",
                                 activebackground="#1b5e20", command=self._run, width=8)
        self.run_btn.pack(side="left", padx=2)
        self.run_all_btn = tk.Button(btn_frame, text="▶▶ 전체 분석",
                                     font=("", 10, "bold"), bg="#1b5e20", fg="white",
                                     activebackground="#0a3d10", command=self._run_all, width=11)
        self.run_all_btn.pack(side="left", padx=2)
        self.map_btn = tk.Button(btn_frame, text="🗺 비트맵",
                                 font=("", 10, "bold"), bg="#1565c0", fg="white",
                                 activebackground="#0d47a1", command=self._show_beatmap,
                                 width=8, state="disabled")
        self.map_btn.pack(side="left", padx=2)
        self.save_btn = tk.Button(btn_frame, text="💾 저장",
                                  font=("", 10, "bold"), bg="#6a1b9a", fg="white",
                                  activebackground="#4a148c", command=self._save_beatmap,
                                  width=7, state="disabled")
        self.save_btn.pack(side="left", padx=2)

        # ══════════════════════════════════════════
        # 2열: 분석 결과(위) + 분석 로그(아래)
        # ══════════════════════════════════════════
        col2 = tk.Frame(main)
        col2.grid(row=0, column=1, sticky="nsew", padx=(3, 0))
        col2.columnconfigure(0, weight=1)
        col2.rowconfigure(0, weight=9)
        col2.rowconfigure(1, weight=1)

        # 2열 1행: 분석 결과 — 엔진별 풀 상세 카드 3개
        results_frame = tk.Frame(col2, bg="#1a1a2e", bd=1, relief="solid")
        results_frame.grid(row=0, column=0, sticky="nsew", pady=(0, 3))
        results_frame.columnconfigure(0, weight=1)
        results_frame.rowconfigure(1, weight=1)

        # ── 곡 정보 헤더 ──────────────────────────
        song_info_frame = tk.Frame(results_frame, bg="#1e272e")
        song_info_frame.grid(row=0, column=0, sticky="ew")
        tk.Label(song_info_frame, text="🎵", bg="#1e272e", fg="#546e7a",
                 font=("", 11)).pack(side="left", padx=(10, 4), pady=5)
        song_info_inner = tk.Frame(song_info_frame, bg="#1e272e")
        song_info_inner.pack(side="left", fill="x", expand=True, pady=3)
        self.v_song_title = tk.StringVar(value="—")
        self.v_song_id    = tk.StringVar(value="—")
        tk.Label(song_info_inner, textvariable=self.v_song_title,
                 bg="#1e272e", fg="#cfd8dc", font=("", 11, "bold"), anchor="w").pack(anchor="w")
        tk.Label(song_info_inner, textvariable=self.v_song_id,
                 bg="#1e272e", fg="#546e7a", font=("", 8), anchor="w").pack(anchor="w")

        # 스타일 표시 (우측)
        style_badge_frame = tk.Frame(song_info_frame, bg="#1e272e")
        style_badge_frame.pack(side="right", padx=(4, 10), pady=3)
        self.v_app_style      = tk.StringVar(value="N/A")
        self.v_librosa_style  = tk.StringVar(value="N/A")
        # GT 엔진 스타일
        gt_row = tk.Frame(style_badge_frame, bg="#1e272e")
        gt_row.pack(anchor="e")
        tk.Label(gt_row, text="GT :", bg="#1e272e", fg="#546e7a", font=("", 8)).pack(side="left")
        self._lbl_librosa_style = tk.Label(gt_row, textvariable=self.v_librosa_style,
                 bg="#1e272e", fg="#82b1ff", font=("", 9, "bold"))
        self._lbl_librosa_style.pack(side="left", padx=(2, 0))
        # 앱 스타일
        app_row = tk.Frame(style_badge_frame, bg="#1e272e")
        app_row.pack(anchor="e")
        tk.Label(app_row, text="앱:", bg="#1e272e", fg="#546e7a", font=("", 8)).pack(side="left")
        self._lbl_app_style = tk.Label(app_row, textvariable=self.v_app_style,
                 bg="#1e272e", fg="#69f0ae", font=("", 9, "bold"))
        self._lbl_app_style.pack(side="left", padx=(2, 0))

        # ── 엔진 카드 3개 (풀 상세, 세로 꽉 채움) ──
        cards_outer = tk.Frame(results_frame, bg="#1a1a2e")
        cards_outer.grid(row=1, column=0, sticky="nsew", padx=4, pady=(4, 4))
        for _ci in range(3):
            cards_outer.columnconfigure(_ci, weight=1)
        cards_outer.rowconfigure(0, weight=1)

        # 카드 내부 grid 기반 metric 셀 헬퍼
        def _mmetric(parent, label, var, fg_color, bg, row, col, colspan=1, tip_key=None):
            cell = tk.Frame(parent, bg=bg)
            cell.grid(row=row, column=col, columnspan=colspan,
                      sticky="nsew", padx=2, pady=2)
            hf = tk.Frame(cell, bg=bg)
            hf.pack(anchor="w", fill="x", padx=6, pady=(5, 0))
            tk.Label(hf, text=label, bg=bg, fg="#90a4ae", font=("", 9)).pack(side="left")
            if tip_key and tip_key in _TIPS:
                tl = tk.Label(hf, text=" ?", bg=bg, fg="#546e7a",
                              font=("", 8, "bold"), cursor="question_arrow")
                tl.pack(side="left")
                _attach_tooltip(tl, _TIPS[tip_key])
            tk.Label(cell, textvariable=var, bg=bg, fg=fg_color,
                     font=("", 16, "bold"), anchor="w").pack(
                     anchor="w", padx=6, pady=(2, 5), fill="x")
            # 클릭 이벤트 전파용 빈 공간 채우기
            return cell

        for col_i, eng in enumerate(("beat_transformer", "madmom", "librosa")):
            eng_label, eng_acc, eng_color = ENGINE_INFO[eng]
            CARD_BG = "#1e2836"
            CELL_BG = "#263238"

            card = tk.Frame(cards_outer, bg=CARD_BG, bd=2, relief="groove", cursor="hand2")
            card.grid(row=0, column=col_i, sticky="nsew", padx=3, pady=2)
            card.columnconfigure(0, weight=1)
            card.rowconfigure(2, weight=1)  # metrics_body 세로 확장

            # 헤더
            hdr = tk.Frame(card, bg=eng_color)
            hdr.grid(row=0, column=0, sticky="ew")
            tk.Label(hdr, text=f"{eng_label}  {eng_acc}", bg=eng_color, fg="white",
                     font=("", 11, "bold"), anchor="w").pack(padx=10, pady=(6, 5), anchor="w")

            # 등급 행
            grade_f = tk.Frame(card, bg=eng_color)
            grade_f.grid(row=1, column=0, sticky="ew")
            grade_lbl = tk.Label(grade_f, text="—", bg=eng_color, fg="white",
                                  font=("", 36, "bold"), width=3)
            grade_lbl.pack(side="left", padx=(10, 4), pady=6)
            grade_sub = tk.Label(grade_f, text="미분석", bg=eng_color, fg="white",
                                  font=("", 11), justify="left", anchor="w", wraplength=220)
            grade_sub.pack(side="left", fill="x", expand=True, padx=(0, 8))

            # 지표 영역 — grid 4행 × 3열
            metrics_body = tk.Frame(card, bg=CARD_BG)
            metrics_body.grid(row=2, column=0, sticky="nsew", padx=2, pady=2)
            for _ci2 in range(3):
                metrics_body.columnconfigure(_ci2, weight=1)
            for _ri in range(4):
                metrics_body.rowconfigure(_ri, weight=1)

            # StringVar
            v_f     = tk.StringVar(value="—")
            v_p     = tk.StringVar(value="—")
            v_r     = tk.StringVar(value="—")
            v_cov   = tk.StringVar(value="—")
            v_tp    = tk.StringVar(value="—")
            v_fp    = tk.StringVar(value="—")
            v_fn    = tk.StringVar(value="—")
            v_bpm_a = tk.StringVar(value="—")
            v_bpm_g = tk.StringVar(value="—")
            v_bpm_e = tk.StringVar(value="—")
            v_gaps  = tk.StringVar(value="—")

            cells = []
            cells += [_mmetric(metrics_body, "F-measure",  v_f,     "#69f0ae", CELL_BG, 0, 0, 2, "F-measure")]
            cells += [_mmetric(metrics_body, "커버리지",    v_cov,   "#82b1ff", CELL_BG, 0, 2, 1, "커버리지")]
            cells += [_mmetric(metrics_body, "Precision",  v_p,     "#b3e5fc", CELL_BG, 1, 0, 1, "Precision")]
            cells += [_mmetric(metrics_body, "Recall",     v_r,     "#b3e5fc", CELL_BG, 1, 1, 1, "Recall")]
            cells += [_mmetric(metrics_body, "대형갭",     v_gaps,  "#ffcc02", CELL_BG, 1, 2, 1, "대형갭")]
            cells += [_mmetric(metrics_body, "TP (일치)",  v_tp,    "#69f0ae", CELL_BG, 2, 0, 1, "TP (일치)")]
            cells += [_mmetric(metrics_body, "FP (오감지)",v_fp,    "#ef9a9a", CELL_BG, 2, 1, 1, "FP (오감지)")]
            cells += [_mmetric(metrics_body, "FN (누락)",  v_fn,    "#82b1ff", CELL_BG, 2, 2, 1, "FN (누락)")]
            cells += [_mmetric(metrics_body, "BPM (앱)",   v_bpm_a, "#ffffff", CELL_BG, 3, 0, 1, "BPM (앱)")]
            cells += [_mmetric(metrics_body, "BPM (GT)",   v_bpm_g, "#ffffff", CELL_BG, 3, 1, 1, "BPM (GT)")]
            cells += [_mmetric(metrics_body, "BPM 오차",   v_bpm_e, "#ffcc02", CELL_BG, 3, 2, 1, "BPM 오차")]

            # 클릭 바인딩 — 카드 내 모든 위젯에 재귀 적용
            def _bind_card(widget, _eng=eng):
                widget.bind("<Button-1>", lambda e, eng=_eng: self._select_engine_card(eng))
                for child in widget.winfo_children():
                    _bind_card(child, _eng)
            _bind_card(card)

            self._card_widgets[eng] = {
                "frame": card, "hdr": hdr,
                "grade_lbl": grade_lbl, "grade_f": grade_f, "grade_sub": grade_sub,
                "eng_color": eng_color,
                "v_f": v_f, "v_p": v_p, "v_r": v_r, "v_cov": v_cov,
                "v_tp": v_tp, "v_fp": v_fp, "v_fn": v_fn,
                "v_bpm_a": v_bpm_a, "v_bpm_g": v_bpm_g, "v_bpm_e": v_bpm_e,
                "v_gaps": v_gaps,
            }

        # ══════════════════════════════════════════
        # 2열 2행: 분석 로그
        # ══════════════════════════════════════════
        log_frame = tk.LabelFrame(col2, text="분석 로그", bg="#1a1a2e", fg="#90a4ae")
        log_frame.grid(row=1, column=0, sticky="nsew", pady=(3, 0))
        log_frame.rowconfigure(0, weight=1)
        log_frame.columnconfigure(0, weight=1)
        self.out = scrolledtext.ScrolledText(log_frame, font=("Courier", 9),
                                             state="disabled", height=20,
                                             bg="#0d1117", fg="#7c8b9c")
        self.out.tag_config("green",  foreground="#69f0ae")
        self.out.tag_config("yellow", foreground="#ffcc02")
        self.out.tag_config("red",    foreground="#ef9a9a")
        self.out.tag_config("gray",   foreground="#455a64")
        self.out.grid(row=0, column=0, sticky="nsew")

    # ── 음악 파일 목록 관리 ────────────────────────

    def _add_audio_files(self):
        paths = filedialog.askopenfilenames(
            title="음악 파일 선택 (복수 선택 가능)",
            filetypes=[("Audio", "*.mp3 *.wav *.flac *.ogg *.m4a *.mp4"), ("All", "*.*")])
        added = False
        for p in paths:
            if not p:
                continue
            if any(v["path"] == p for v in self._audio_items.values()):
                continue
            iid = self._tree.insert("", "end",
                                    values=(os.path.basename(p), "계산 중…", "—",
                                            "미분석", "미분석", "미분석"),
                                    tags=("pending",))
            self._audio_items[iid] = {"path": p, "music_id": "", "bin_path": "",
                                      "results": {}}
            threading.Thread(target=self._compute_id_for_item,
                             args=(iid, p), daemon=True).start()
            added = True
        if added:
            self._refresh_matching()
            self._save_state()

    def _compute_id_for_item(self, iid, path):
        try:
            mid = str(compute_music_id(path))
        except Exception as e:
            print(f"[compute_music_id] FAILED for {os.path.basename(path)}: {e}")
            mid = extract_music_id(path) or "—"
        if iid not in self._audio_items:
            return
        self._audio_items[iid]["music_id"] = mid  # unsigned (매칭용)
        disp_mid = _to_signed_display(int(mid)) if mid.lstrip("-").isdigit() else mid

        def _update():
            try:
                vals = list(self._tree.item(iid, "values"))
                vals[1] = disp_mid
                self._tree.item(iid, values=tuple(vals))
            except Exception:
                pass
            self._refresh_matching()

        self.after(0, _update)

    def _remove_audio_file(self):
        sel = self._tree.selection()
        if not sel:
            return
        for iid in sel:
            self._tree.delete(iid)
            self._audio_items.pop(iid, None)
        self._refresh_matching()
        self._save_state()

    # ── 상태 저장 / 불러오기 ──────────────────────

    def _save_state(self):
        state = {
            "bin_folder": self._bin_folder_var.get(),
            "items": [
                {
                    "path":     item["path"],
                    "music_id": item.get("music_id", ""),
                    "results":  item.get("results", {}),
                }
                for item in self._audio_items.values()
                if os.path.isfile(item.get("path", ""))
            ],
        }
        try:
            with open(self._STATE_FILE, "w", encoding="utf-8") as f:
                json.dump(state, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def _load_state(self):
        if not os.path.isfile(self._STATE_FILE):
            return
        try:
            with open(self._STATE_FILE, encoding="utf-8") as f:
                state = json.load(f)
        except Exception:
            return

        bin_folder = state.get("bin_folder", "")
        if bin_folder:
            self._bin_folder_var.set(bin_folder)

        for entry in state.get("items", []):
            path = entry.get("path", "")
            if not path or not os.path.isfile(path):
                continue
            if any(v["path"] == path for v in self._audio_items.values()):
                continue

            mid     = entry.get("music_id", "")
            results = entry.get("results", {})
            disp_mid = _to_signed_display(int(mid)) if mid.lstrip("-").isdigit() else (mid or "—")

            # 등급 태그 결정
            _grade_order = ["S", "A", "B", "C", "D", "!"]
            best = max(
                (r["grade"] for r in results.values() if r.get("grade") in _grade_order),
                key=lambda g: -_grade_order.index(g),
                default=None
            )

            # 엔진 컬럼 값 복원
            def _eng_label(engine):
                r = results.get(engine)
                if not r:
                    return "미분석"
                return f"{r['grade']} {r['f_score']*100:.0f}%"

            iid = self._tree.insert("", "end",
                values=(os.path.basename(path), disp_mid, "—",
                        _eng_label("beat_transformer"),
                        _eng_label("madmom"),
                        _eng_label("librosa")),
                tags=("pending",))
            self._audio_items[iid] = {
                "path": path, "music_id": mid,
                "bin_path": "", "results": results,
            }

            # 복원 후 태그 갱신은 매칭 후 처리
            if best:
                self._tree.item(iid, tags=("pending", f"grade_{best}"))

        if self._audio_items:
            self._refresh_matching()

    def _reset_analysis_results(self):
        """바이너리 폴더 변경 시 모든 항목의 분석 결과를 초기화한다."""
        for iid, item in self._audio_items.items():
            item["results"] = {}
            try:
                vals = list(self._tree.item(iid, "values"))
                while len(vals) < 6:
                    vals.append("미분석")
                vals[3] = "미분석"
                vals[4] = "미분석"
                vals[5] = "미분석"
                self._tree.item(iid, values=tuple(vals))
            except Exception:
                pass

    def _pick_bin_folder(self):
        p = filedialog.askdirectory(title="타임라인 바이너리 폴더 선택")
        if p:
            old = self._bin_folder_var.get().strip()
            self._bin_folder_var.set(p)
            if p != old:
                self._reset_analysis_results()
            self._refresh_matching()
            self._save_state()

    def _refresh_matching(self):
        folder = self._bin_folder_var.get().strip()
        # 폴더 없으면 전체 미매칭으로 초기화
        bin_files = {}
        if folder and os.path.isdir(folder):
            for fname in os.listdir(folder):
                if fname.lower().endswith(".bin"):
                    mid = extract_music_id(fname)
                    if mid:
                        bin_files[mid] = os.path.join(folder, fname)

        matched = 0
        for iid, item in self._audio_items.items():
            mid = item.get("music_id", "")
            bin_path = bin_files.get(mid, "") if mid and mid != "계산 중…" else ""
            item["bin_path"] = bin_path
            if bin_path:
                match_text = f"✓  {os.path.basename(bin_path)}"
                tag = "matched"
                matched += 1
            elif not mid or mid == "계산 중…":
                match_text = "—  ID 계산 중"
                tag = "pending"
            else:
                match_text = "✗  미매칭"
                tag = "unmatched"
            try:
                vals = list(self._tree.item(iid, "values"))
                # 컬럼이 6개 미만이면 패딩
                while len(vals) < 6:
                    vals.append("미분석")
                vals[2] = match_text
                self._tree.item(iid, values=tuple(vals), tags=(tag,))
            except Exception:
                pass

        total = len(self._audio_items)
        if folder:
            self._match_status_var.set(
                f"총 {total}개 중 {matched}개 매칭됨  |  폴더: {folder}")
        else:
            self._match_status_var.set("폴더를 선택하면 자동으로 매칭을 확인합니다.")

    def _on_list_select(self, event=None):
        sel = self._tree.selection()
        if not sel:
            return
        iid  = sel[0]
        item = self._audio_items.get(iid, {})
        name = os.path.basename(item.get("path", "")) or "—"
        mid  = item.get("music_id", "—") or "—"
        bin_p = item.get("bin_path", "")
        if bin_p:
            self.status_var.set(
                f"선택: {name}  |  Music ID: {mid}  |  타임라인: {os.path.basename(bin_p)}")
        else:
            self.status_var.set(
                f"선택: {name}  |  Music ID: {mid}  |  타임라인: 미매칭 (분석 불가)")
        self._update_engine_cards(iid)

    def _update_engine_cards(self, iid):
        """3개 엔진 카드를 현재 iid의 분석 결과로 갱신한다."""
        item = self._audio_items.get(iid, {})
        results = item.get("results", {})
        music_id = item.get("music_id", "")

        name = os.path.splitext(os.path.basename(item.get("path", "")))[0] or "—"
        mid  = music_id
        disp = _to_signed_display(int(mid)) if mid.lstrip("-").isdigit() else (mid or "—")
        self.v_song_title.set(name)
        self.v_song_id.set(f"Music ID: {disp}")

        # records.json에서 상세 데이터 로드
        records_path = os.path.join(os.path.dirname(__file__), "beat_analysis_records.json")
        records_map = {}
        try:
            with open(records_path, encoding="utf-8") as _f:
                for rec in json.load(_f):
                    records_map[rec.get("_key", "")] = rec
        except Exception:
            pass

        best_f   = -1.0
        best_eng = ""

        for eng, cw in self._card_widgets.items():
            eng_color = cw["eng_color"]
            rec = records_map.get(f"{music_id}_{eng}") if music_id else None
            if rec:
                s       = rec.get("summary", {})
                grade   = s.get("grade", "—")
                f_val   = s.get("f_measure", 0)
                bpm_app = s.get("bpm_app", 0)
                bpm_ref = s.get("bpm_gt", 0)
                bpm_err = abs(bpm_app - bpm_ref) / bpm_ref * 100 if bpm_ref > 0 else 0
                _, g_color, verdict = grade_info(f_val, eng)
                cw["grade_f"].config(bg=g_color)
                cw["grade_lbl"].config(text=grade, fg="white", bg=g_color)
                cw["grade_sub"].config(text=verdict, fg="white", bg=g_color)
                # coverage 계산
                dur = rec.get("duration_sec", 0)
                app_ms_list = rec.get("beats", {}).get("app_ms", [])
                cov = min(100.0, app_ms_list[-1] / 1000.0 / dur * 100) \
                      if dur > 0 and app_ms_list else 0.0
                # 대형갭 계산
                gaps_cnt = 0
                if len(app_ms_list) >= 2:
                    ivs = [app_ms_list[i+1] - app_ms_list[i]
                           for i in range(len(app_ms_list) - 1)]
                    gaps_cnt = sum(1 for iv in ivs if iv > 600)
                self._update_cards(eng, f_val,
                                   s.get("precision", 0), s.get("recall", 0),
                                   s.get("tp", 0), s.get("fp", 0), s.get("fn", 0),
                                   bpm_app, bpm_ref, bpm_err, cov, gaps_cnt)
                if f_val > best_f:
                    best_f   = f_val
                    best_eng = eng
            else:
                r = results.get(eng)
                if r:
                    grade = r.get("grade", "—")
                    f_val = r.get("f_score", 0)
                    _, g_color, verdict = grade_info(f_val, eng)
                    cw["grade_f"].config(bg=g_color)
                    cw["grade_lbl"].config(text=grade, fg="white", bg=g_color)
                    cw["grade_sub"].config(text=verdict, fg="white", bg=g_color)
                    cw["v_f"].set(f"{f_val*100:.1f}%")
                    if f_val > best_f:
                        best_f   = f_val
                        best_eng = eng
                else:
                    cw["grade_f"].config(bg=eng_color)
                    cw["grade_lbl"].config(text="—", fg="white", bg=eng_color)
                    cw["grade_sub"].config(text="미분석", fg="white", bg=eng_color)
                    for vk in ("v_f","v_p","v_r","v_cov","v_tp","v_fp","v_fn",
                               "v_bpm_a","v_bpm_g","v_bpm_e","v_gaps"):
                        cw[vk].set("—")

        if best_eng:
            self._select_engine_card(best_eng, iid)
        else:
            # 아직 분석 전 — 카드 테두리 초기화
            for cw in self._card_widgets.values():
                cw["frame"].config(relief="groove", bd=2)

    def _select_engine_card(self, engine, iid=None):
        """카드를 선택하여 강조하고 상세 지표 + 타임라인을 갱신한다."""
        if iid is None:
            sel = self._tree.selection()
            iid = sel[0] if sel else None
        if not iid:
            return
        self._selected_card_engine = engine
        for eng, cw in self._card_widgets.items():
            if eng == engine:
                cw["frame"].config(relief="solid", bd=3)
            else:
                cw["frame"].config(relief="groove", bd=2)
        self._load_result_for_engine(iid, engine)

    def _load_result_for_engine(self, iid, engine):
        """beat_analysis_records.json에서 해당 항목+엔진 결과를 로드하여 UI에 복원한다."""
        item = self._audio_items.get(iid, {})
        if not item:
            return

        music_id = item.get("music_id", "")
        eng_name = ENGINE_INFO.get(engine, (engine,))[0]
        disp_mid = _to_signed_display(int(music_id)) if music_id.lstrip("-").isdigit() else (music_id or "—")

        # 곡 정보
        name = os.path.splitext(os.path.basename(item.get("path", "")))[0] or "—"
        self.v_song_title.set(name)
        self.v_song_id.set(f"Music ID: {disp_mid}")

        # records.json 에서 상세 데이터 로드
        records_path = os.path.join(os.path.dirname(__file__), "beat_analysis_records.json")
        record = None
        try:
            with open(records_path, encoding="utf-8") as f_:
                records = json.load(f_)
            record = next((rec for rec in records
                           if rec.get("_key") == f"{music_id}_{engine}"), None)
        except Exception:
            pass

        if record:
            s     = record.get("summary", {})
            beats = record.get("beats", {})

            f_val    = s.get("f_measure", 0)
            p_val    = s.get("precision", 0)
            r_val    = s.get("recall", 0)
            tp       = s.get("tp", 0)
            fp_cnt   = s.get("fp", 0)
            fn       = s.get("fn", 0)
            bpm_app  = s.get("bpm_app", 0)
            bpm_ref  = s.get("bpm_gt", 0)
            bpm_err  = (abs(bpm_app - bpm_ref) / bpm_ref * 100) if bpm_ref > 0 else 0
            dur      = record.get("duration_sec", 0)
            tol_ms   = record.get("tolerance_ms", 70)
            grade    = s.get("grade", "—")

            app_ms   = beats.get("app_ms", [])
            ref_ms   = beats.get("gt_ms", [])
            tp_est   = beats.get("tp_sec", [])
            fp_est   = beats.get("fp_sec", [])
            fn_ref   = beats.get("fn_sec", [])

            app_sec  = [t / 1000.0 for t in app_ms]
            ref_sec  = [t / 1000.0 for t in ref_ms]

            cov_pct  = min(100.0, (app_sec[-1] if app_sec else 0) / dur * 100) \
                       if dur > 0 else 0
            gaps = 0
            if len(app_ms) >= 2:
                ivs  = [app_ms[i+1] - app_ms[i] for i in range(len(app_ms)-1)]
                gaps = sum(1 for iv in ivs if iv > 600)

            advice = {"S": "서비스 적용 가능 수준입니다.",
                      "A": "대부분의 K-pop에서 정상 동작합니다.",
                      "B": "BPM은 정확하나 비트 타이밍 개선이 필요합니다.",
                      "C": "Ellis DP / Adaptive Threshold 튜닝을 권장합니다."
                      }.get(grade, "BPM 감지 로직부터 재검토가 필요합니다.")

            self._update_cards(engine, f_val, p_val, r_val, tp, fp_cnt, fn,
                               bpm_app, bpm_ref, bpm_err, cov_pct, gaps)

            # 스타일/섹션 복원
            _app_style     = record.get("app_style", "N/A")
            _lib_style     = record.get("librosa_style", "N/A")
            _lib_feats     = record.get("librosa_style_features", {})
            _lib_sections  = record.get("librosa_sections", [])
            _app_sections  = record.get("app_sections", [])

            # 기존 레코드에 app_sections 없으면 바이너리 파일에서 직접 읽기
            if not _app_sections:
                bin_path = item.get("bin_path", "")
                if bin_path and os.path.exists(bin_path):
                    try:
                        _, style_from_bin, _app_sections = parse_section_meta_binary(bin_path)
                        if style_from_bin and style_from_bin != "N/A" and _app_style == "N/A":
                            _app_style = style_from_bin
                    except Exception:
                        _app_sections = []

            # 헤더 스타일 레이블 갱신
            self.v_app_style.set(_app_style)
            self.v_librosa_style.set(_lib_style)
            self._lbl_app_style.config(fg=_STYLE_COLORS.get(_app_style, '#69f0ae'))
            self._lbl_librosa_style.config(fg=_STYLE_COLORS.get(_lib_style, '#82b1ff'))

            # 결과 먼저 저장 (파형 없이) → 타임라인 즉시 렌더
            self._last_result = dict(
                ref_sec=ref_sec, app_sec=app_sec,
                tp_est=tp_est, fp_est=fp_est, fn_ref=fn_ref,
                waveform=item.get("waveform"),  # 캐시 있으면 즉시 사용
                wav_sr=item.get("wav_sr", 0),
                duration_sec=dur,
                f_score=f_val, bpm_app=bpm_app, bpm_ref=bpm_ref,
                engine_name=eng_name,
                audio_name=os.path.basename(item.get("path", "")),
                music_id=music_id, tol_ms=tol_ms,
                sections=_app_sections,
                app_style=_app_style,
                librosa_style=_lib_style,
                librosa_style_features=_lib_feats,
                librosa_sections=_lib_sections,
            )
            self.zoom_s_var.set(0.0)
            self.zoom_e_var.set(round(dur, 1))
            self.after(100, self._redraw_timeline)
            if HAS_MPL:
                self.map_btn.config(state="normal")

            # 파형이 아직 캐시되지 않은 경우 백그라운드 로드
            if "waveform" not in item:
                audio_path = item.get("path", "")
                if audio_path and os.path.exists(audio_path):
                    def _load_waveform_async(it=item, lr=self._last_result,
                                             ap=audio_path):
                        wv, ws = load_waveform(ap)
                        it["waveform"] = wv
                        it["wav_sr"]   = ws
                        lr["waveform"] = wv
                        lr["wav_sr"]   = ws
                        self.after(0, self._redraw_timeline)
                    threading.Thread(target=_load_waveform_async, daemon=True).start()
                else:
                    item["waveform"] = None
                    item["wav_sr"]   = 0
                self.save_btn.config(state="normal")

        else:
            # records.json 없을 때: 저장된 grade/f_score만 표시
            eng_results = item.get("results", {}).get(engine, {})
            if eng_results:
                f_val  = eng_results.get("f_score", 0)
                cw = self._card_widgets.get(engine)
                if cw:
                    cw["v_f"].set(f"{f_val*100:.1f}%")

    def _save_result_to_item(self, audio_path, engine, grade, f_score):
        """분석 결과를 해당 항목에 저장하고 Treeview 컬럼을 갱신한다."""
        _grade_order = ["S", "A", "B", "C", "D", "!"]
        col_idx = {"beat_transformer": 3, "madmom": 4, "librosa": 5}
        label = f"{grade} {f_score*100:.0f}%"
        for iid, item in self._audio_items.items():
            if item.get("path") == audio_path:
                item.setdefault("results", {})[engine] = {
                    "grade": grade, "f_score": f_score}
                try:
                    vals = list(self._tree.item(iid, "values"))
                    while len(vals) < 6:
                        vals.append("미분석")
                    vals[col_idx[engine]] = label
                    # pick best grade across all analyzed engines for row color
                    results = item["results"]
                    best = max(
                        (r["grade"] for r in results.values() if r["grade"] in _grade_order),
                        key=lambda g: -_grade_order.index(g),
                        default=None
                    )
                    match_tag = "matched" if item.get("bin_path") else "unmatched"
                    tags = (match_tag,) + ((f"grade_{best}",) if best else ())
                    self._tree.item(iid, values=tuple(vals), tags=tags)
                except Exception:
                    pass
                break

    def _run_all(self):
        """타임라인이 매칭된 모든 항목을 3종 엔진으로 순차 분석한다."""
        engines = [e for e in ("beat_transformer", "madmom", "librosa")
                   if best_available_engine(e) == e]
        if not engines:
            messagebox.showerror("라이브러리 없음",
                "분석 엔진 없음.\npip install librosa 설치 후 재시작하세요."); return

        targets = [(iid, item) for iid, item in self._audio_items.items()
                   if item.get("bin_path") and os.path.isfile(item["bin_path"])
                   and os.path.isfile(item.get("path", ""))]
        if not targets:
            messagebox.showinfo("알림", "타임라인이 매칭된 파일이 없습니다."); return

        self.run_btn.config(state="disabled")
        self.run_all_btn.config(state="disabled")
        self.map_btn.config(state="disabled")
        self.save_btn.config(state="disabled")
        self._clear_log()
        eng_names = " / ".join(ENGINE_INFO[e][0] for e in engines)
        self._log(f"전체 분석 시작 — {len(targets)}개 파일 / 엔진: {eng_names}", "green")

        threading.Thread(
            target=self._run_all_thread,
            args=(targets, engines, self.tol_var.get(), self.bpm_hint_var.get()),
            daemon=True
        ).start()

    def _run_all_thread(self, targets, engines, tol_ms, bpm_hint):
        total = len(targets)
        for idx, (iid, item) in enumerate(targets, 1):
            audio = item["path"]
            binf  = item["bin_path"]
            name  = os.path.basename(audio)
            for engine in engines:
                eng_lbl = ENGINE_INFO[engine][0]
                self.after(0, lambda n=name, i=idx, t=total, el=eng_lbl:
                           self.status_var.set(f"전체 분석 [{i}/{t}]  {n}  ({el})"))
                try:
                    self._do_analyze(audio, binf, engine, tol_ms, bpm_hint)
                except Exception as e:
                    import traceback
                    tb  = traceback.format_exc()
                    msg = str(e)
                    self.after(0, lambda n=name, el=eng_lbl, m=msg, tb=tb:
                               self._log(f"\n[오류] {n} ({el}): {m}\n{tb}", "red"))
        self.after(0, lambda: self.run_btn.config(state="normal"))
        self.after(0, lambda: self.run_all_btn.config(state="normal"))
        self.after(0, lambda t=total: self.status_var.set(
            f"전체 분석 완료 — {t}개 처리"))
        self.after(0, lambda: self._log(
            f"\n전체 분석 완료 — {total}개 처리", "green"))
        self.after(0, self._save_state)

    # ── 진단 데이터 내보내기 ──────────────────────

    def _export_analysis_record(self, *, audio_path, bin_path, engine, eng_name,
                                 tol_ms, music_id, duration_sec,
                                 app_ms, ref_ms, tp_est, fp_est, fn_ref,
                                 f, p, r, tp, fp_cnt, fn,
                                 bpm_app, bpm_ref, grade, section_results=None,
                                 app_style=None, librosa_style=None,
                                 librosa_style_features=None, librosa_sections=None,
                                 app_sections=None):
        """분석 결과를 beat_analysis_records.json 에 누적 저장한다."""
        import datetime

        records_path = os.path.join(os.path.dirname(__file__), "beat_analysis_records.json")

        # 기존 레코드 로드
        try:
            with open(records_path, encoding="utf-8") as f_:
                records = json.load(f_)
        except Exception:
            records = []

        # 인접 오차 분포: 각 앱 비트마다 가장 가까운 GT 비트와의 오차(ms)
        ref_arr = sorted(ref_ms)
        offset_errors = []
        for a in sorted(app_ms):
            if not ref_arr:
                break
            closest = min(ref_arr, key=lambda g: abs(g - a))
            offset_errors.append(round(a - closest, 1))

        # FP 오차 (오감지 비트들의 GT 대비 오차)
        fp_errors = []
        for a in sorted(fp_est):
            a_ms = int(a * 1000)
            if ref_arr:
                closest = min(ref_arr, key=lambda g: abs(g - a_ms))
                fp_errors.append(round(a_ms - closest, 1))

        # 키: (music_id, engine) 조합으로 기존 레코드 덮어쓰기
        key = f"{music_id}_{engine}"
        records = [rec for rec in records if rec.get("_key") != key]

        record = {
            "_key":           key,
            "timestamp":      datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "audio_file":     os.path.basename(audio_path),
            "bin_file":       os.path.basename(bin_path),
            "music_id":       _to_signed_display(int(music_id)) if music_id.lstrip("-").isdigit() else music_id,
            "engine":         engine,
            "engine_name":    eng_name,
            "tolerance_ms":   tol_ms,
            "duration_sec":   round(duration_sec, 3),

            # 요약 지표
            "summary": {
                "grade":      grade,
                "f_measure":  round(f, 4),
                "precision":  round(p, 4),
                "recall":     round(r, 4),
                "tp":         tp,
                "fp":         fp_cnt,
                "fn":         fn,
                "bpm_app":    round(bpm_app, 2),
                "bpm_gt":     round(bpm_ref, 2),
                "bpm_error":  round(abs(bpm_app - bpm_ref), 2),
                "beat_count_app": len(app_ms),
                "beat_count_gt":  len(ref_ms),
            },

            # 전체 비트 타임스탬프 (ms)
            "beats": {
                "app_ms":     sorted(app_ms),
                "gt_ms":      sorted(ref_ms),
                "tp_sec":     sorted(round(t, 4) for t in tp_est),
                "fp_sec":     sorted(round(t, 4) for t in fp_est),
                "fn_sec":     sorted(round(t, 4) for t in fn_ref),
            },

            # 오차 분포 (비트 개선 진단용)
            "offset_errors_ms": offset_errors,   # 앱 비트 - 가장 가까운 GT 비트 (전체)
            "fp_offset_ms":     fp_errors,        # FP 비트들의 GT 대비 오차
            "error_stats": {
                "mean_offset_ms":   round(sum(abs(e) for e in offset_errors) / len(offset_errors), 2) if offset_errors else 0,
                "median_offset_ms": round(sorted(abs(e) for e in offset_errors)[len(offset_errors)//2], 2) if offset_errors else 0,
                "within_tol_pct":   round(sum(1 for e in offset_errors if abs(e) <= tol_ms) / len(offset_errors) * 100, 1) if offset_errors else 0,
            },

            # 섹션별 비트 정확도 (section meta 바이너리가 있을 때만 채워짐)
            "sections": section_results or [],

            # 음악 스타일 및 구조 섹션 정보
            "app_style":              app_style or "N/A",
            "librosa_style":          librosa_style or "N/A",
            "librosa_style_features": librosa_style_features or {},
            "librosa_sections":       librosa_sections or [],
            "app_sections":           app_sections or [],
        }

        records.append(record)

        try:
            with open(records_path, "w", encoding="utf-8") as f_:
                json.dump(records, f_, ensure_ascii=False, indent=2)
            self.after(0, lambda: self._log(
                f"  진단 데이터 저장: beat_analysis_records.json  ({len(records)}건 누적)", "gray"))
        except Exception as e_:
            self.after(0, lambda m=str(e_): self._log(f"  [진단 저장 실패] {m}", "yellow"))

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
        pass  # 등급 표시는 엔진 카드로 이전됨

    def _update_cards(self, engine, f, p, r, tp, fp_cnt, fn,
                      bpm_app, bpm_ref, bpm_err, cov_pct, gaps):
        cw = self._card_widgets.get(engine)
        if not cw:
            return
        def pct(v): return f"{v*100:.1f}%"
        cw["v_f"].set(pct(f))
        cw["v_p"].set(pct(p))
        cw["v_r"].set(pct(r))
        cw["v_tp"].set(str(tp))
        cw["v_fp"].set(str(fp_cnt))
        cw["v_fn"].set(str(fn))
        cw["v_bpm_a"].set(f"{bpm_app:.1f}")
        cw["v_bpm_g"].set(f"{bpm_ref:.1f}")
        cw["v_bpm_e"].set(f"{bpm_err:.1f}%")
        cw["v_cov"].set(f"{cov_pct:.1f}%")
        cw["v_gaps"].set(f"{gaps}개")

    def _redraw_timeline(self):
        """비트 타임라인 캔버스를 현재 _last_result 기준으로 다시 그린다."""
        if not self._last_result:
            return
        r = self._last_result
        try:
            z_s = float(self.zoom_s_var.get())
            z_e = float(self.zoom_e_var.get())
        except Exception:
            z_s, z_e = 0.0, r["duration_sec"]
        if z_e <= z_s:
            z_e = z_s + 1.0
        self._draw_beat_timeline(
            r["tp_est"], r["fp_est"], r["fn_ref"],
            r["ref_sec"], r["app_sec"],
            r["duration_sec"], z_s, z_e,
            waveform=r.get("waveform"), wav_sr=r.get("wav_sr", 0),
            engine_failed=r.get("engine_failed", False),
            sections=r.get("sections", []),
            app_style=r.get("app_style", "N/A"),
            librosa_style=r.get("librosa_style", "N/A"),
            librosa_style_features=r.get("librosa_style_features", {}),
            librosa_sections=r.get("librosa_sections", []),
        )

    def _draw_beat_timeline(self, tp_est, fp_est, fn_ref,
                            ref_sec, app_sec, duration_sec,
                            z_start, z_end,
                            waveform=None, wav_sr=0,
                            engine_failed=False,
                            sections=None, app_style='N/A',
                            librosa_style='N/A', librosa_style_features=None,
                            librosa_sections=None):
        """
        캔버스에 7-레인 비트 타임라인을 그린다.
          파형 레인: Waveform
          GT 비트 레인: GT 엔진 분석 결과  — 초록=TP(일치), 파랑=FN(누락)
          GT 스타일 레인: Librosa 음악 스타일
          GT 섹션 레인: Librosa 구조 분석
          앱 비트 레인: 앱 타임라인 결과  — 초록=TP(일치), 빨강=FP(오감지)
          앱 스타일 레인: 앱 음악 스타일
          앱 섹션 레인: 앱 섹션 메타
        """
        if sections is None:
            sections = []
        if librosa_style_features is None:
            librosa_style_features = {}
        if librosa_sections is None:
            librosa_sections = []
        c = self.tl_canvas
        c.delete("all")
        W = c.winfo_width()
        H = c.winfo_height()
        if W < 10 or H < 10:
            return

        span = max(z_end - z_start, 0.001)

        LABEL_W = 110   # 좌측 레인 라벨 영역 너비
        TICK_H  = 16    # 상단 시간 눈금 영역 높이
        PAD     = 4

        # 5-레인 고정 픽셀 분할 (하단 공백 없음)
        # TICK(16) | 파형(44) | GT비트(44) | GT섹션(22) | 앱비트(44) | 앱섹션(22) = 192
        BEAT_H  = 44
        SEC_H   = 22

        WAVE_BOT   = TICK_H + BEAT_H
        GT_BOT     = WAVE_BOT + BEAT_H
        GT_SEC_BOT = GT_BOT + SEC_H
        APP_BOT    = GT_SEC_BOT + BEAT_H
        APP_SEC_BOT= APP_BOT + SEC_H

        WAVE_TOP   = TICK_H + PAD
        WAVE_MID   = (WAVE_TOP + WAVE_BOT) // 2
        GT_TOP     = WAVE_BOT + PAD
        GT_MID     = (GT_TOP + GT_BOT) // 2
        GT_SEC_TOP = GT_BOT + 1
        APP_TOP    = GT_SEC_BOT + PAD
        APP_MID    = (APP_TOP + APP_BOT) // 2
        APP_SEC_TOP= APP_BOT + 1

        def tx(t):
            return int(LABEL_W + (t - z_start) / span * (W - LABEL_W))

        in_range = lambda t: z_start <= t <= z_end

        # ── 배경 레인 (5레인) ──
        c.create_rectangle(0, TICK_H,      W, WAVE_BOT,    fill="#0d1117", outline="")   # 파형
        c.create_rectangle(0, WAVE_BOT,    W, GT_BOT,      fill="#0a1520", outline="")   # GT 비트
        c.create_rectangle(0, GT_BOT,      W, GT_SEC_BOT,  fill="#060c14", outline="")   # GT 섹션
        c.create_rectangle(0, GT_SEC_BOT,  W, APP_BOT,     fill="#0e1a0e", outline="")   # 앱 비트
        c.create_rectangle(0, APP_BOT,     W, APP_SEC_BOT, fill="#071007", outline="")   # 앱 섹션

        # ── 레인 구분선 ──
        c.create_line(0, WAVE_BOT,    W, WAVE_BOT,    fill="#263238", width=2)   # 파형/GT 경계
        c.create_line(0, GT_BOT,      W, GT_BOT,      fill="#1a2a38", width=1)   # GT비트/섹션
        c.create_line(0, GT_SEC_BOT,  W, GT_SEC_BOT,  fill="#263238", width=2)   # GT/앱 경계
        c.create_line(0, APP_BOT,     W, APP_BOT,     fill="#1a2a38", width=1)   # 앱비트/섹션
        c.create_line(LABEL_W, TICK_H, LABEL_W, APP_SEC_BOT, fill="#1e2d3a", width=1, dash=(3, 3))

        # ── 레인 라벨 (5레인) ──
        eng_label = (self._last_result or {}).get("engine_name", "GT 엔진")

        def _lane_label(y0, y1, txt, fg, bg):
            c.create_rectangle(0, y0, LABEL_W - 2, y1, fill=bg, outline="")
            c.create_text(LABEL_W // 2, (y0 + y1) // 2, text=txt, fill=fg,
                          font=("", 7, "bold"), anchor="center", justify="center")

        _lane_label(TICK_H,      WAVE_BOT,    "파형",           "#b0bec5", "#111318")
        _lane_label(WAVE_BOT,    GT_BOT,      f"{eng_label}비트","#82b1ff", "#0d1b2a")
        _lane_label(GT_BOT,      GT_SEC_BOT,  "GT섹션",         "#82b1ff", "#060b14")
        _lane_label(GT_SEC_BOT,  APP_BOT,     "앱비트",          "#69f0ae", "#0d1a0d")
        _lane_label(APP_BOT,     APP_SEC_BOT, "앱섹션",          "#69f0ae", "#071007")

        # ── 시간 눈금 ──
        target_ticks = max(5, (W - LABEL_W) // 60)
        raw = span / target_ticks
        for base in [1, 2, 5, 10, 15, 30, 60, 120, 300]:
            if base >= raw:
                tick_interval = base
                break
        else:
            tick_interval = int(raw / 60 + 1) * 60

        t = int(z_start / tick_interval) * tick_interval
        while t <= z_end + tick_interval:
            if t < z_start:
                t += tick_interval; continue
            x = tx(t)
            if x < LABEL_W:
                t += tick_interval; continue
            c.create_line(x, TICK_H, x, APP_SEC_BOT, fill="#1a2a38", width=1)
            mins, secs = divmod(int(t), 60)
            label = f"{mins}:{secs:02d}" if mins else f"{secs}s"
            c.create_text(x + 2, 2, anchor="nw", text=label,
                          fill="#455a64", font=("", 7))
            t += tick_interval

        # ── 파형 레인 — 픽셀 컬럼당 min/max 수직선 방식 ──
        if waveform is not None and wav_sr > 0 and duration_sec > 0:
            total_samples = len(waveform)
            s_start = max(0, int(z_start / duration_sec * total_samples))
            s_end   = min(total_samples, int(z_end / duration_sec * total_samples))
            seg = waveform[s_start:s_end]
            n_seg = len(seg)
            if n_seg > 1:
                draw_w = max(2, W - LABEL_W)
                half_h = max(2, (WAVE_BOT - WAVE_TOP) // 2 - PAD)
                peak = float(max(abs(float(seg.max())), abs(float(seg.min()))))
                if peak < 1e-9:
                    peak = 1.0
                # 각 픽셀 컬럼에 대해 해당 구간의 min/max 수직선 그리기
                for x_off in range(draw_w):
                    i0 = int(x_off       * n_seg / draw_w)
                    i1 = int((x_off + 1) * n_seg / draw_w)
                    if i1 <= i0:
                        i1 = i0 + 1
                    chunk = seg[i0:min(i1, n_seg)]
                    if len(chunk) == 0:
                        continue
                    y_hi = WAVE_MID - int(float(chunk.max()) / peak * half_h)
                    y_lo = WAVE_MID - int(float(chunk.min()) / peak * half_h)
                    px = LABEL_W + x_off
                    c.create_line(px, y_hi, px, y_lo, fill="#78909c")

        # ── GT 레인 비트 막대 ──
        if engine_failed:
            # 분석 실패 — GT 비트 레인에 안내 텍스트만 표시
            c.create_rectangle(LABEL_W, WAVE_BOT, W, GT_BOT, fill="#1a0a0a", outline="")
            c.create_text((LABEL_W + W) // 2, (WAVE_BOT + GT_BOT) // 2,
                          text="분석 실패 (메모리 부족 — 파일이 너무 큼)",
                          fill="#ef5350", font=("", 9, "bold"), anchor="center")
        else:
            for t in fn_ref:   # 누락 — 파랑
                if in_range(t):
                    x = tx(t)
                    c.create_rectangle(x-1, GT_TOP, x+1, GT_BOT - PAD,
                                       fill="#448aff", outline="")
            for t in tp_est:   # 일치 — 초록
                if in_range(t):
                    x = tx(t)
                    c.create_rectangle(x-1, GT_TOP, x+1, GT_BOT - PAD,
                                       fill="#69f0ae", outline="")

        # ── 앱 레인 비트 막대 ──
        for t in (tp_est if not engine_failed else app_sec):   # 일치/전체 — 초록
            if in_range(t):
                x = tx(t)
                c.create_rectangle(x-1, APP_TOP, x+1, APP_BOT,
                                   fill="#69f0ae", outline="")
        if not engine_failed:
            for t in fp_est:   # 오감지 — 빨강
                if in_range(t):
                    x = tx(t)
                    c.create_rectangle(x-1, APP_TOP, x+1, APP_BOT,
                                       fill="#ff5252", outline="")

        # 섹션 텍스트 트런케이션 헬퍼 (font size 7 bold ≈ 6px/char)
        _CHAR_W = 6
        def _sec_text(lbl, bar_w):
            max_ch = max(1, (bar_w - 4) // _CHAR_W)
            return lbl[:max_ch]

        def _draw_sec_bar(secs, y_top, y_bot):
            for sec in secs:
                s_t = sec["start_ms"] / 1000.0
                e_t = sec["end_ms"]   / 1000.0
                if e_t < z_start or s_t > z_end:
                    continue
                x1 = tx(max(s_t, z_start))
                x2 = tx(min(e_t, z_end))
                if x2 <= x1:
                    continue
                lbl = sec.get("type", "?")
                col = _SECTION_COLORS.get(lbl, "#546e7a")
                bar_w = x2 - x1
                # 채운 바
                c.create_rectangle(x1, y_top+1, x2, y_bot-1, fill=col, outline="")
                # 경계 구분선 (좌측 1px 어두운 선)
                c.create_line(x1, y_top+1, x1, y_bot-1, fill="#1a1a1a", width=1)
                # 텍스트 (최소 1자, 영역 초과 시 트런케이션)
                txt = _sec_text(lbl, bar_w)
                if txt:
                    mid_x = (x1 + x2) // 2
                    mid_y = (y_top + y_bot) // 2
                    c.create_text(mid_x+1, mid_y+1, text=txt, fill="#111111",
                                  font=("", 7, "bold"), anchor="center")
                    c.create_text(mid_x, mid_y, text=txt, fill="white",
                                  font=("", 7, "bold"), anchor="center")

        # ── GT 섹션 레인 ──
        if librosa_sections:
            _draw_sec_bar(librosa_sections, GT_SEC_TOP, GT_SEC_BOT)

        # ── 앱 섹션 레인 ──
        if sections:
            _draw_sec_bar(sections, APP_SEC_TOP, APP_SEC_BOT)

    # ── 분석 ──────────────────────────────────────

    def _run(self):
        sel = self._tree.selection()
        if not sel:
            messagebox.showerror("오류", "음악 파일 목록에서 항목을 선택하세요."); return
        iid   = sel[0]
        item  = self._audio_items.get(iid, {})
        audio = item.get("path", "").strip()
        binf  = item.get("bin_path", "").strip()
        if not audio or not os.path.isfile(audio):
            messagebox.showerror("오류", "유효한 음악 파일을 선택하세요."); return
        if not binf or not os.path.isfile(binf):
            messagebox.showerror("오류",
                "매칭되는 타임라인 바이너리가 없습니다.\n"
                "타임라인 폴더를 설정하고 매칭을 확인하세요."); return

        engines = [e for e in ("beat_transformer", "madmom", "librosa")
                   if best_available_engine(e) == e]
        if not engines:
            messagebox.showerror("라이브러리 없음",
                "분석 엔진 없음.\npip install librosa 설치 후 재시작하세요."); return

        self._clear_log()
        self.run_btn.config(state="disabled")
        self.map_btn.config(state="disabled")
        self.save_btn.config(state="disabled")
        self._last_result = None
        eng_names = " / ".join(ENGINE_INFO[e][0] for e in engines)
        self.status_var.set(f"분석 중… 엔진: {eng_names}")

        threading.Thread(
            target=self._analyze_thread,
            args=(audio, binf, engines, self.tol_var.get(), self.bpm_hint_var.get()),
            daemon=True
        ).start()

    def _analyze_thread(self, audio, binf, engines, tol_ms, bpm_hint):
        for engine in engines:
            try:
                self._do_analyze(audio, binf, engine, tol_ms, bpm_hint)
            except Exception as e:
                import traceback
                tb       = traceback.format_exc()
                msg      = str(e)
                eng_name = ENGINE_INFO.get(engine, (engine,))[0]
                is_oom   = "not enough memory" in msg or "MemoryError" in msg or "alloc" in msg.lower()
                self.after(0, lambda n=eng_name, m=msg, t=tb, oom=is_oom:
                           self._log(f"\n[오류] {n}: {m}\n{t}", "red"))
                if is_oom:
                    self.after(0, lambda eng=engine, n=eng_name:
                               self._handle_engine_oom(audio, binf, eng, n, tol_ms))
        self.after(0, lambda: self.run_btn.config(state="normal"))
        self.after(0, lambda: self.run_all_btn.config(state="normal"))
        self.after(0, lambda: self.status_var.set("완료"))
        self.after(0, self._save_state)

    def _update_cards_failed(self, engine, reason="메모리 부족"):
        """엔진 분석 실패 시 카드에 '분석 실패' 표시."""
        cw = self._card_widgets.get(engine)
        if not cw:
            return
        cw["v_f"].set("분석 실패")
        cw["v_p"].set("—")
        cw["v_r"].set("—")
        cw["v_tp"].set("—")
        cw["v_fp"].set("—")
        cw["v_fn"].set("—")
        cw["v_bpm_a"].set("—")
        cw["v_bpm_g"].set("—")
        cw["v_bpm_e"].set("—")
        cw["v_cov"].set("—")
        cw["v_gaps"].set("—")
        # 헤더 색상을 경고색으로
        frame = cw.get("frame")
        if frame:
            try:
                frame.config(bg="#4a1010")
            except Exception:
                pass

    def _handle_engine_oom(self, audio_path, bin_path, engine, eng_name, tol_ms):
        """OOM 실패 시: 카드에 분석 실패 표시 + 파형/앱 타임라인만 그리기."""
        self._update_cards_failed(engine, "메모리 부족 (파일이 너무 큼)")
        self.status_var.set(f"{eng_name}: 분석 실패 (메모리 부족)")

        # 바이너리 파싱 후 파형+앱 타임라인만 표시
        try:
            version, frame_count, app_ms = parse_timeline_binary(bin_path)
            app_sec = [t / 1000.0 for t in app_ms]
            try:
                duration_sec = get_audio_duration(audio_path)
            except Exception:
                duration_sec = app_ms[-1] / 1000.0 + 1.0 if app_ms else 0.0

            waveform, wav_sr = None, 0
            iid = next((i for i, v in self._audio_items.items()
                        if v.get("path") == audio_path), None)
            if iid and self._audio_items[iid].get("waveform") is not None:
                waveform = self._audio_items[iid]["waveform"]
                wav_sr   = self._audio_items[iid].get("wav_sr", 0)
            else:
                try:
                    waveform, wav_sr = load_waveform(audio_path)
                except Exception:
                    pass

            self._last_result = dict(
                ref_sec=[], app_sec=app_sec,
                tp_est=app_sec, fp_est=[], fn_ref=[],
                waveform=waveform, wav_sr=wav_sr,
                duration_sec=duration_sec,
                f_score=0.0, bpm_app=0.0, bpm_ref=0.0,
                engine_name=eng_name + " (실패)",
                audio_name=os.path.basename(audio_path),
                music_id="—", tol_ms=tol_ms,
                engine_failed=True,
            )
            self.zoom_e_var.set(round(duration_sec, 1))
            self._redraw_timeline()
        except Exception as ex:
            self._log(f"[타임라인 표시 오류] {ex}", "red")

    def _do_analyze(self, audio_path, bin_path, engine, tol_ms, bpm_hint):
        SEP  = "─" * 62
        SEP2 = "═" * 62
        eng_name, eng_acc, _ = ENGINE_INFO[engine]

        # ─ 곡 정보 헤더 갱신 ─
        _song_name = os.path.splitext(os.path.basename(audio_path))[0]
        self.after(0, lambda n=_song_name: self.v_song_title.set(n))

        # ① 바이너리
        self.after(0, lambda: self._log("[ 1/4 ]  타임라인 바이너리 파싱…", "gray"))
        version, frame_count, app_ms = parse_timeline_binary(bin_path)
        app_sec  = [t / 1000.0 for t in app_ms]
        app_st   = beat_stats(app_ms)
        bin_id   = extract_music_id(bin_path) or os.path.splitext(os.path.basename(bin_path))[0]

        # 섹션 메타 바이너리 탐색 (같은 폴더의 sections_<id>_v*.bin)
        sections = []
        app_style = 'N/A'
        try:
            bin_folder = os.path.dirname(bin_path)
            for fname in sorted(os.listdir(bin_folder), reverse=True):
                if fname.startswith("sections_") and fname.endswith(".bin"):
                    fid = extract_music_id(fname)
                    if fid == bin_id:
                        _, app_style, sections = parse_section_meta_binary(
                            os.path.join(bin_folder, fname))
                        break
        except Exception:
            pass

        # 오디오 해시 기반 Music ID (SDK 동일 알고리즘)
        try:
            audio_hash_id = str(compute_music_id(audio_path))
        except Exception:
            audio_hash_id = extract_music_id(audio_path) or "—"

        # Music ID 헤더 갱신 (표시는 signed int32)
        _disp_bin  = _to_signed_display(int(bin_id))       if bin_id.lstrip("-").isdigit()       else bin_id
        _disp_hash = _to_signed_display(int(audio_hash_id)) if audio_hash_id.lstrip("-").isdigit() else audio_hash_id
        self.after(0, lambda b=_disp_bin, h=_disp_hash:
                   self.v_song_id.set(f"Music ID: {b}" +
                                      (f"  (mp3 hash: {h})" if b != h else "")))

        music_id = bin_id   # 바이너리 파일명 기준 ID

        def _lb():
            self._log(SEP, "gray")
            self._log(f"  바이너리   : {os.path.basename(bin_path)}")
            self._log(f"  Music ID (bin) : {bin_id}  |  Music ID (mp3 hash) : {audio_hash_id}",
                      "yellow" if bin_id != audio_hash_id else "green")
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

        # ② Ground-truth — 캐시 우선 사용 (GT는 오디오가 변하지 않으면 재실행 불필요)
        records_path = os.path.join(os.path.dirname(__file__), "beat_analysis_records.json")
        cached_gt_ms  = None
        cached_gt_bpm = None
        try:
            with open(records_path, encoding="utf-8") as _f:
                _recs = json.load(_f)
            _cached = next((r for r in _recs
                            if r.get("_key") == f"{audio_hash_id}_{engine}"), None)
            if _cached:
                _gt_beats = _cached.get("beats", {}).get("gt_ms")
                _gt_bpm   = _cached.get("summary", {}).get("bpm_gt")
                if _gt_beats and _gt_bpm:
                    cached_gt_ms  = _gt_beats
                    cached_gt_bpm = _gt_bpm
        except Exception:
            pass

        if cached_gt_ms is not None:
            self.after(0, lambda: self._log(
                f"\n[ 2/4 ]  Ground-truth 캐시 사용 ({eng_name} — 재분석 생략)", "gray"))
            ref_ms  = cached_gt_ms
            ref_bpm = cached_gt_bpm
        else:
            self.after(0, lambda: self._log(f"\n[ 2/4 ]  Ground-truth 감지… ({eng_name} {eng_acc})", "gray"))
            if engine == "beat_transformer":
                ref_sec, ref_bpm = detect_beats_beat_transformer(audio_path)
            elif engine == "madmom":
                ref_sec, ref_bpm = detect_beats_madmom(audio_path)
            else:
                ref_sec, ref_bpm = detect_beats_librosa(audio_path, bpm_hint)
            ref_ms = [int(t * 1000) for t in ref_sec]
        ref_sec = [t / 1000.0 for t in ref_ms]  # 통합: 이후 코드는 ref_sec 사용
        ref_st  = beat_stats(ref_ms)
        try:
            duration_sec = get_audio_duration(audio_path)
        except Exception:
            duration_sec = ref_ms[-1] / 1000.0 + 1.0 if ref_ms else 0.0

        def _lr():
            self._log(SEP, "gray")
            self._log(f"  원곡       : {os.path.basename(audio_path)}")
            self._log(f"  Music ID   : {audio_hash_id}  (SHA-256 앞 4바이트, SDK 동일)", "green")
            self._log(f"  길이       : {_fmt_sec(duration_sec)}")
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

        advice = {"S":"서비스 적용 가능 수준입니다.",
                  "A":"대부분의 K-pop에서 정상 동작합니다.",
                  "B":"BPM은 정확하나 비트 타이밍 개선이 필요합니다.",
                  "C":"Ellis DP / Adaptive Threshold 튜닝을 권장합니다."
                  }.get(grade, "BPM 감지 로직부터 재검토가 필요합니다.")

        def _update_ui():
            self._update_cards(
                engine, f, p, r, tp, fp_cnt, fn,
                app_st.get('bpm', 0), ref_bpm, bpm_err, cov_pct,
                app_st.get('gaps_600', 0)
            )
            self._log(f"완료 — F={f*100:.1f}%  P={p*100:.1f}%  R={r*100:.1f}%"
                      f"  BPM 앱{app_st.get('bpm',0):.0f}/GT{ref_bpm:.0f}", "green")
            self._save_result_to_item(audio_path, engine, grade, f)
            sel = self._tree.selection()
            if sel:
                self._update_engine_cards(sel[0])
        self.after(0, _update_ui)

        # ── 섹션별 비트 정확도 분석 ──────────────────
        section_results = []
        if sections:
            def _log_sections(secs=sections):
                self._log("\n─── 섹션별 비트 정확도 ───────────────────────────", "gray")
                self._log(f"  {'#':>2}  {'타입':<8}  {'구간':>17}  {'F':>5}  {'P':>5}  {'R':>5}  "
                          f"{'TP':>4}  {'FP':>4}  {'FN':>4}  앱비트/GT비트")
            self.after(0, _log_sections)
            for i, sec in enumerate(sections):
                s_beats_ms = sec.get("beat_times_ms", [])
                s_start = sec["start_ms"] / 1000.0
                s_end   = sec["end_ms"]   / 1000.0
                sec_type = sec["type"]
                sec_ref  = [t for t in ref_sec if s_start <= t < s_end]
                sec_app  = [t / 1000.0 for t in s_beats_ms]
                if sec_ref or sec_app:
                    sf, sp, sr, stp, sfp, sfn = fmeasure(sec_ref, sec_app, tol_sec) \
                        if sec_ref and sec_app else (0.0, 0.0, 0.0, 0, 0, len(sec_ref))
                    section_results.append({
                        "index": i, "type": sec_type,
                        "start_ms": sec["start_ms"], "end_ms": sec["end_ms"],
                        "beat_count_app": len(sec_app), "beat_count_gt": len(sec_ref),
                        "f_measure": round(sf, 4), "precision": round(sp, 4), "recall": round(sr, 4),
                        "tp": stp, "fp": sfp, "fn": sfn,
                        "energy": sec.get("energy", 0), "beat_ms": sec.get("beat_ms", 0),
                    })
                    tag = "green" if sf >= 0.8 else ("yellow" if sf >= 0.6 else "red")
                    def _ls(i=i, st=sec_type, ss=s_start, se=s_end,
                             sf=sf, sp=sp, sr=sr, stp=stp, sfp=sfp, sfn=sfn,
                             na=len(sec_app), ng=len(sec_ref), tag=tag):
                        self._log(
                            f"  {i:2d}  {st:<8}  {_fmt_sec(ss)}~{_fmt_sec(se)}"
                            f"  {sf*100:5.1f}%  {sp*100:5.1f}%  {sr*100:5.1f}%"
                            f"  {stp:4d}  {sfp:4d}  {sfn:4d}  {na}/{ng}", tag)
                    self.after(0, _ls)

        # ── librosa 음악 스타일 + 섹션 감지 ──────────
        librosa_style = 'N/A'
        librosa_style_features = {}
        librosa_sections = []
        if HAS_LIBROSA:
            try:
                self.after(0, lambda: self._log("\n[+lib]  Librosa 음악 스타일 분류…", "gray"))
                librosa_style, librosa_style_features = compute_music_style_librosa(
                    audio_path, ref_sec, ref_bpm)
                self.after(0, lambda s=librosa_style, f=librosa_style_features:
                    self._log(f"  Librosa Style : {s}  |  {f}", "cyan"))
                self.after(0, lambda: self._log("[+lib]  Librosa 섹션 감지…", "gray"))
                librosa_sections = detect_librosa_sections(audio_path, duration_sec)
                self.after(0, lambda n=len(librosa_sections):
                    self._log(f"  Librosa 섹션 : {n}개 감지", "cyan"))
            except Exception as _ex:
                self.after(0, lambda e=str(_ex): self._log(f"  Librosa 분석 오류: {e}", "red"))

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
            sections=sections,
            app_style=app_style,
            librosa_style=librosa_style,
            librosa_style_features=librosa_style_features,
            librosa_sections=librosa_sections,
        )
        # 헤더 스타일 레이블 업데이트
        _as, _ls = app_style or 'N/A', librosa_style or 'N/A'
        self.after(0, lambda a=_as, l=_ls: (
            self.v_app_style.set(a),
            self.v_librosa_style.set(l),
            self._lbl_app_style.config(fg=_STYLE_COLORS.get(a, '#69f0ae')),
            self._lbl_librosa_style.config(fg=_STYLE_COLORS.get(l, '#82b1ff')),
        ))

        # 진단 데이터 저장
        self._export_analysis_record(
            audio_path=audio_path, bin_path=bin_path,
            engine=engine, eng_name=eng_name, tol_ms=tol_ms,
            music_id=music_id, duration_sec=duration_sec,
            app_ms=app_ms, ref_ms=ref_ms,
            tp_est=tp_est, fp_est=fp_est, fn_ref=fn_ref,
            f=f, p=p, r=r, tp=tp, fp_cnt=fp_cnt, fn=fn,
            bpm_app=app_st.get('bpm', 0), bpm_ref=ref_bpm,
            grade=grade,
            section_results=section_results,
            app_style=app_style,
            librosa_style=librosa_style,
            librosa_style_features=librosa_style_features,
            librosa_sections=librosa_sections,
            app_sections=sections,
        )
        # 비트 타임라인 그리기 (전체 기본)
        def _draw():
            self.zoom_e_var.set(round(duration_sec, 1))
            self._redraw_timeline()
        self.after(200, _draw)   # 캔버스 레이아웃 완성 후 실행

        if HAS_MPL:
            self.after(0, lambda: self.map_btn.config(state="normal"))
            self.after(0, lambda: self.save_btn.config(state="normal"))

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
        _splash_state["running"] = False
        _splash_root.destroy()
        print("numpy가 필요합니다: pip install numpy"); sys.exit(1)
    if not (HAS_BT or HAS_MADMOM or HAS_LIBROSA):
        _splash_state["running"] = False
        _splash_root.destroy()
        print("분석 엔진 없음. pip install librosa 설치 후 재시작하세요."); sys.exit(1)

    # 스플래시 닫고 메인 앱 시작
    _splash_state["running"] = False
    if _splash_state.get("job"):
        try:
            _splash_root.after_cancel(_splash_state["job"])
        except Exception:
            pass
    _splash_root.destroy()
    App().mainloop()
