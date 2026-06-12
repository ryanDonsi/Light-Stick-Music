# BeatAccuracyChecker 인스톨러 빌드 가이드

## 사전 설치 목록

| # | 소프트웨어 | 버전 | 다운로드 링크 |
|---|-----------|------|--------------|
| 1 | **Python** | 3.10 이상 (3.11 권장) | https://www.python.org/downloads/ |
| 2 | **Inno Setup 6** | 최신 | https://jrsoftware.org/isdl.php |

> Python 설치 시 **"Add Python to PATH"** 반드시 체크

---

## Python 패키지 설치

명령 프롬프트(cmd)에서 아래 명령어 실행:

```
pip install pyinstaller numpy matplotlib librosa soundfile
```

madmom도 포함하려면 (선택):
```
pip install madmom
```

> Beat Transformer는 별도 Python 패키지 없이 `Beat-Transformer/` 폴더를 그대로 포함합니다.

---

## 빌드 방법

1. 이 폴더(`tools/installer/`)에서 `build_exe.bat` 실행
2. 완료 후 `tools/installer/output/BeatAccuracyChecker_Setup.exe` 생성됨

---

## 배포 파일

`BeatAccuracyChecker_Setup.exe` 파일 하나만 배포하면 됩니다.

포함 내용:
- BeatAccuracyChecker.exe
- Beat-Transformer/ 폴더 전체 (checkpoint 모델 포함)
- beat_analysis_records.json (분석 기록)
