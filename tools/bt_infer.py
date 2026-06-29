"""
Beat Transformer 단일 파일 추론 래퍼
=====================================
beat_accuracy_checker.py 에서 subprocess로 호출된다.

사용법:
    python bt_infer.py --audio <audio_path> --checkpoint <ckpt_dir> --out <out.json>

출력 JSON:
    { "beats": [0.44, 0.88, ...], "downbeats": [0.44, 2.20, ...] }

이 스크립트는 Beat-Transformer/code/ 디렉터리에 복사하거나
tools/ 에 두고 --code_dir 로 code 경로를 지정한다.
"""

import argparse
import json
import os
import sys

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio",      required=True,  help="입력 오디오 경로")
    parser.add_argument("--checkpoint", required=True,  help="checkpoint 폴더 (*.pt 파일 위치)")
    parser.add_argument("--out",        required=True,  help="출력 JSON 경로")
    parser.add_argument("--code_dir",   default="",     help="Beat-Transformer/code 폴더 경로")
    parser.add_argument("--device",     default="cpu",  help="cpu 또는 cuda")
    args = parser.parse_args()

    # code 디렉터리를 sys.path에 추가
    if args.code_dir and args.code_dir not in sys.path:
        sys.path.insert(0, args.code_dir)

    try:
        import torch
        import numpy as np
        import librosa
    except ImportError as e:
        print(f"[bt_infer] 필수 패키지 없음: {e}", file=sys.stderr)
        print("pip install torch torchaudio librosa numpy", file=sys.stderr)
        sys.exit(1)

    # ── Beat Transformer 모델 로드 ──────────────────
    try:
        from DilatedTransformer import Demixed_DilatedTransformerModel
    except ImportError as e:
        print(f"[bt_infer] DilatedTransformer 임포트 실패: {e}", file=sys.stderr)
        print(f"  --code_dir 경로를 확인하세요: {args.code_dir}", file=sys.stderr)
        sys.exit(1)

    device = torch.device(args.device if torch.cuda.is_available() or args.device == "cpu"
                          else "cpu")

    # checkpoint 파일 탐색 (.pt or .pth)
    ckpt_file = None
    for fname in sorted(os.listdir(args.checkpoint)):
        if fname.endswith(".pt") or fname.endswith(".pth"):
            ckpt_file = os.path.join(args.checkpoint, fname)
            break
    if ckpt_file is None:
        print(f"[bt_infer] checkpoint 파일(.pt/.pth) 없음: {args.checkpoint}", file=sys.stderr)
        sys.exit(1)

    print(f"[bt_infer] 체크포인트 로드: {ckpt_file}")
    state = torch.load(ckpt_file, map_location=device, weights_only=False)

    # 체크포인트는 instr=5 (5-stem demixed) 로 학습됨
    model = Demixed_DilatedTransformerModel(
        attn_len=5, instr=5, ntoken=2,
        dmodel=256, nhead=8, d_hid=1024,
        nlayers=9, norm_first=True
    )
    sd = state.get("state_dict", state)
    sd = {k.replace("module.", ""): v for k, v in sd.items()}
    model.load_state_dict(sd, strict=False)
    model.to(device)
    model.eval()
    print("[bt_infer] 모델 로드 완료")

    # ── 오디오 → 스펙트로그램 (청크 처리) ────────────
    print(f"[bt_infer] 오디오 로드: {args.audio}")
    y, sr = librosa.load(args.audio, sr=22050, mono=True)

    duration_sec = len(y) / sr
    print(f"[bt_infer] 오디오 길이: {duration_sec:.1f}초")

    # 128-mel log spectrogram (모델 입력 형식: (batch, instr, time, melbin=128))
    hop_length = 512   # ~23ms @ 22050 Hz

    S = librosa.feature.melspectrogram(
        y=y, sr=sr, n_fft=2048, hop_length=hop_length,
        n_mels=128, fmin=20, fmax=11025, power=2.0
    )
    spec_mono = librosa.power_to_db(S, ref=np.max)  # (128, T)
    spec_mono = spec_mono.T  # (T, 128)

    # 단일 오디오를 5개 채널로 복제 (demixed 5-stem 대체)
    # shape: (1, 5, T, 128)
    spec = np.stack([spec_mono] * 5, axis=0)  # (5, T, 128)

    # 메모리 부족 대비: 청크 처리 (최대 120초씩)
    chunk_size_sec = 120.0
    hop_sec = hop_length / sr
    chunk_frames = max(1, int(chunk_size_sec / hop_sec))
    total_frames = spec.shape[1]

    all_beat_peaks = []
    all_downbeat_peaks = []
    frame_offset = 0

    print(f"[bt_infer] 청크 처리: {chunk_size_sec:.0f}초씩 ({chunk_frames} 프레임)")

    # ── 청크별 추론 ──────────────────────────────────
    for chunk_start in range(0, total_frames, chunk_frames):
        chunk_end = min(chunk_start + chunk_frames, total_frames)
        chunk = spec[:, chunk_start:chunk_end, :]  # (5, chunk_T, 128)

        print(f"[bt_infer] 청크 처리: {chunk_start}/{total_frames} (shape={chunk.shape})")

        chunk_tensor = torch.tensor(chunk, dtype=torch.float32).unsqueeze(0).to(device)  # (1, 5, chunk_T, 128)

        try:
            with torch.no_grad():
                output, _ = model(chunk_tensor)  # output: (1, chunk_T, 2)

            beat_act = output[0, :, 0].cpu().numpy()
            downbeat_act = output[0, :, 1].cpu().numpy()

            # Peak picking (각 청크별)
            from scipy.signal import find_peaks
            min_dist = max(1, int(0.25 / hop_sec))
            threshold = 0.3

            beat_peaks, _ = find_peaks(beat_act, height=threshold, distance=min_dist)
            downbeat_peaks, _ = find_peaks(downbeat_act, height=threshold, distance=min_dist)

            # 원본 타임라인으로 변환 (청크 오프셋 적용)
            beat_peaks = beat_peaks + chunk_start
            downbeat_peaks = downbeat_peaks + chunk_start

            all_beat_peaks.extend(beat_peaks.tolist())
            all_downbeat_peaks.extend(downbeat_peaks.tolist())

        except RuntimeError as e:
            error_msg = str(e)
            print(f"[bt_infer] ❌ 청크 추론 중 에러 발생: {error_msg}", file=sys.stderr)
            print(f"[bt_infer] 청크 shape: {chunk.shape}", file=sys.stderr)

            if "out of memory" in error_msg.lower() or "not enough memory" in error_msg.lower():
                print(f"[bt_infer] 💡 메모리 부족: 청크 크기를 줄여야 합니다", file=sys.stderr)

            sys.exit(1)

    # 중복 제거 및 정렬
    all_beat_peaks = sorted(set(all_beat_peaks))
    all_downbeat_peaks = sorted(set(all_downbeat_peaks))

    # 프레임을 초 단위로 변환
    hop_sec = hop_length / sr
    beats_sec = [float(i * hop_sec) for i in all_beat_peaks]
    downbeats_sec = [float(i * hop_sec) for i in all_downbeat_peaks]

    print(f"[bt_infer] 비트 {len(beats_sec)}개, 다운비트 {len(downbeats_sec)}개 감지")

    # ── JSON 출력 ──────────────────────────────────
    with open(args.out, "w") as f:
        json.dump({"beats": beats_sec, "downbeats": downbeats_sec}, f)
    print(f"[bt_infer] 저장: {args.out}")


def run_inference(audio_path: str, checkpoint_dir: str, code_dir: str = "", device: str = "cpu"):
    """
    beat_accuracy_checker.py 에서 frozen exe 환경에서 직접 호출하는 진입점.
    subprocess 없이 동일한 프로세스 안에서 추론 후 (beats_sec, bpm) 를 반환한다.
    """
    import json, tempfile, sys as _sys

    if code_dir and code_dir not in _sys.path:
        _sys.path.insert(0, code_dir)

    try:
        import torch
        import numpy as np
        import librosa
    except ImportError as e:
        raise RuntimeError(f"필수 패키지 없음: {e}")

    try:
        from DilatedTransformer import Demixed_DilatedTransformerModel
    except ImportError as e:
        raise RuntimeError(f"DilatedTransformer 임포트 실패: {e}\n  code_dir={code_dir}")

    _device = torch.device(device if (torch.cuda.is_available() or device == "cpu") else "cpu")

    ckpt_file = None
    for fname in sorted(os.listdir(checkpoint_dir)):
        if fname.endswith(".pt") or fname.endswith(".pth"):
            ckpt_file = os.path.join(checkpoint_dir, fname)
            break
    if ckpt_file is None:
        raise RuntimeError(f"checkpoint 파일(.pt/.pth) 없음: {checkpoint_dir}")

    state = torch.load(ckpt_file, map_location=_device, weights_only=False)
    model = Demixed_DilatedTransformerModel(
        attn_len=5, instr=5, ntoken=2,
        dmodel=256, nhead=8, d_hid=1024,
        nlayers=9, norm_first=True
    )
    sd = state.get("state_dict", state)
    sd = {k.replace("module.", ""): v for k, v in sd.items()}
    model.load_state_dict(sd, strict=False)
    model.to(_device).eval()

    # 청크 처리 (메모리 부족 대비)
    y, sr = librosa.load(audio_path, sr=22050, mono=True)
    hop_length = 512
    S = librosa.feature.melspectrogram(y=y, sr=sr, n_fft=2048, hop_length=hop_length,
                                       n_mels=128, fmin=20, fmax=11025, power=2.0)
    spec_mono = librosa.power_to_db(S, ref=np.max).T

    # 5개 채널로 복제
    spec = np.stack([spec_mono] * 5, axis=0)  # (5, T, 128)

    # 청크 처리 (최대 120초씩)
    hop_sec = hop_length / sr
    chunk_size_sec = 120.0
    chunk_frames = max(1, int(chunk_size_sec / hop_sec))
    total_frames = spec.shape[1]

    from scipy.signal import find_peaks
    min_dist = max(1, int(0.25 / hop_sec))
    all_beat_peaks = []

    for chunk_start in range(0, total_frames, chunk_frames):
        chunk_end = min(chunk_start + chunk_frames, total_frames)
        chunk = spec[:, chunk_start:chunk_end, :]  # (5, chunk_T, 128)

        chunk_tensor = torch.tensor(chunk, dtype=torch.float32).unsqueeze(0).to(_device)

        try:
            with torch.no_grad():
                output, _ = model(chunk_tensor)

            beat_act = output[0, :, 0].cpu().numpy()
            beat_peaks, _ = find_peaks(beat_act, height=0.3, distance=min_dist)
            beat_peaks = beat_peaks + chunk_start

            all_beat_peaks.extend(beat_peaks.tolist())

        except RuntimeError as e:
            raise RuntimeError(
                f"추론 중 에러 (청크 shape={chunk.shape}): {str(e)[:150]}\n"
                f"해결책: 메모리 부족 → CPU 모드 시도 또는 다른 프로그램 종료"
            )

    all_beat_peaks = sorted(set(all_beat_peaks))
    beats_sec = sorted(float(i * hop_sec) for i in all_beat_peaks)

    bpm = 0.0
    if len(beats_sec) >= 2:
        intervals = [beats_sec[i+1] - beats_sec[i] for i in range(len(beats_sec)-1)]
        median_interval = sorted(intervals)[len(intervals)//2]
        bpm = 60.0 / median_interval if median_interval > 0 else 0.0

    return beats_sec, bpm


if __name__ == "__main__":
    main()
