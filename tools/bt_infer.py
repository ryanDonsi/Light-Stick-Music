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
        print("pip install torch torchaudio librosa numpy einops", file=sys.stderr)
        sys.exit(1)

    # ── Beat Transformer 모델 로드 ──────────────────
    try:
        from DilatedTransformer import DilatedTransformer
    except ImportError:
        print("[bt_infer] DilatedTransformer 임포트 실패. --code_dir 를 확인하세요.", file=sys.stderr)
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
    state = torch.load(ckpt_file, map_location=device)

    # 모델 하이퍼파라미터 기본값 (Beat Transformer 논문 기준)
    model = DilatedTransformer(
        attn_len=5, instr=1, ntoken=2,
        dmodel=256, nhead=8, d_hid=1024,
        nlayers=9, norm_first=True
    )
    # state_dict 키 처리 (module. prefix 제거)
    sd = state.get("state_dict", state)
    sd = {k.replace("module.", ""): v for k, v in sd.items()}
    model.load_state_dict(sd, strict=False)
    model.to(device)
    model.eval()
    print("[bt_infer] 모델 로드 완료")

    # ── 오디오 → 스펙트로그램 ──────────────────────
    print(f"[bt_infer] 오디오 로드: {args.audio}")
    y, sr = librosa.load(args.audio, sr=22050, mono=True)

    # 3-band log-mel spectrogram (Beat Transformer 입력 형식)
    hop_length = 512   # ~23ms @ 22050 Hz

    def log_mel(y, n_mels, fmin, fmax):
        S = librosa.feature.melspectrogram(
            y=y, sr=sr, n_fft=2048, hop_length=hop_length,
            n_mels=n_mels, fmin=fmin, fmax=fmax, power=2.0)
        return librosa.power_to_db(S, ref=np.max)

    spec_low  = log_mel(y, n_mels=24, fmin=20,   fmax=300)
    spec_mid  = log_mel(y, n_mels=24, fmin=300,  fmax=3000)
    spec_high = log_mel(y, n_mels=24, fmin=3000, fmax=11025)

    # (3, n_mels, T) → (1, 3, n_mels, T)
    spec = np.stack([spec_low, spec_mid, spec_high], axis=0)
    spec = torch.tensor(spec, dtype=torch.float32).unsqueeze(0).to(device)

    # ── 추론 ──────────────────────────────────────
    print("[bt_infer] 추론 실행 중…")
    with torch.no_grad():
        output = model(spec)

    # output: (1, T, 2) — [beat_act, downbeat_act] 또는 (1, 2, T)
    if output.dim() == 3:
        if output.shape[-1] == 2:
            beat_act     = output[0, :, 0].cpu().numpy()
            downbeat_act = output[0, :, 1].cpu().numpy()
        else:
            beat_act     = output[0, 0, :].cpu().numpy()
            downbeat_act = output[0, 1, :].cpu().numpy()
    else:
        beat_act = output[0].cpu().numpy()
        downbeat_act = beat_act

    # ── Peak picking ───────────────────────────────
    from scipy.signal import find_peaks

    hop_sec    = hop_length / sr
    min_dist   = max(1, int(0.25 / hop_sec))   # 최소 250ms
    threshold  = 0.3

    beat_peaks, _     = find_peaks(beat_act,     height=threshold, distance=min_dist)
    downbeat_peaks, _ = find_peaks(downbeat_act, height=threshold, distance=min_dist)

    beats_sec     = [float(i * hop_sec) for i in beat_peaks]
    downbeats_sec = [float(i * hop_sec) for i in downbeat_peaks]

    print(f"[bt_infer] 비트 {len(beats_sec)}개, 다운비트 {len(downbeats_sec)}개 감지")

    # ── JSON 출력 ──────────────────────────────────
    with open(args.out, "w") as f:
        json.dump({"beats": beats_sec, "downbeats": downbeats_sec}, f)
    print(f"[bt_infer] 저장: {args.out}")


if __name__ == "__main__":
    main()
