@echo off
chcp 65001 >nul
setlocal

echo ================================================
echo  BeatAccuracyChecker 빌드 스크립트
echo ================================================
echo.

:: ── 경로 설정 ──────────────────────────────────────
set SCRIPT_DIR=%~dp0
set TOOLS_DIR=%SCRIPT_DIR%..\
set DIST_DIR=%SCRIPT_DIR%dist
set BUILD_DIR=%SCRIPT_DIR%build
set INNO_SETUP="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"

:: ── 1단계: PyInstaller ─────────────────────────────
echo [1/2] PyInstaller로 EXE 빌드 중...
echo       (torch + madmom 포함으로 수 분 소요될 수 있습니다)
echo.

cd /d "%TOOLS_DIR%"

pyinstaller beat_accuracy_checker.py ^
  --onefile ^
  --windowed ^
  --name "BeatAccuracyChecker" ^
  --distpath "%DIST_DIR%" ^
  --workpath "%BUILD_DIR%\pyinstaller_work" ^
  --specpath "%BUILD_DIR%" ^
  --add-data "bt_infer.py;." ^
  --collect-all librosa ^
  --collect-all soundfile ^
  --collect-all torch ^
  --collect-all torchaudio ^
  --collect-all madmom ^
  --collect-all einops ^
  --collect-all scipy ^
  --hidden-import matplotlib.backends.backend_tkagg ^
  --hidden-import sklearn.utils._cython_blas ^
  --hidden-import sklearn.neighbors._typedefs ^
  --hidden-import madmom.features.beats ^
  --hidden-import madmom.audio.signal ^
  --hidden-import madmom.ml.rnn ^
  --hidden-import torch ^
  --hidden-import torchaudio

if errorlevel 1 (
    echo.
    echo [오류] PyInstaller 빌드 실패.
    pause
    exit /b 1
)

echo.
echo [1/2] EXE 빌드 완료: %DIST_DIR%\BeatAccuracyChecker.exe
echo.

:: ── 2단계: Inno Setup ──────────────────────────────
echo [2/2] Inno Setup으로 인스톨러 생성 중...
echo.

if not exist %INNO_SETUP% (
    echo [오류] Inno Setup을 찾을 수 없습니다: %INNO_SETUP%
    echo        https://jrsoftware.org/isdl.php 에서 설치 후 다시 실행하세요.
    pause
    exit /b 1
)

%INNO_SETUP% "%SCRIPT_DIR%setup.iss"

if errorlevel 1 (
    echo.
    echo [오류] Inno Setup 빌드 실패.
    pause
    exit /b 1
)

echo.
echo ================================================
echo  완료!
echo  인스톨러: %SCRIPT_DIR%output\BeatAccuracyChecker_Setup.exe
echo ================================================
pause
