@echo off
chcp 65001 >nul
setlocal

echo ================================================
echo  BeatAccuracyChecker 디버그 빌드 (콘솔 창 포함)
echo  Beat Transformer / madmom 오류 메시지 확인용
echo ================================================
echo.

set SCRIPT_DIR=%~dp0
set TOOLS_DIR=%SCRIPT_DIR%..\
set DIST_DIR=%SCRIPT_DIR%dist_debug
set BUILD_DIR=%SCRIPT_DIR%build

cd /d "%TOOLS_DIR%"

pyinstaller beat_accuracy_checker.py ^
  --onedir ^
  --console ^
  --name "BeatAccuracyChecker_debug" ^
  --distpath "%DIST_DIR%" ^
  --workpath "%BUILD_DIR%\pyinstaller_work_debug" ^
  --specpath "%BUILD_DIR%" ^
  --add-data "%TOOLS_DIR%bt_infer.py;." ^
  --add-data "%SCRIPT_DIR%splash.png;." ^
  --icon "%SCRIPT_DIR%icon.ico" ^
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
    echo [오류] 빌드 실패.
    pause
    exit /b 1
)

echo.
echo 빌드 완료. 아래 명령으로 실행하면 콘솔에 오류가 출력됩니다:
echo   %DIST_DIR%\BeatAccuracyChecker_debug\BeatAccuracyChecker_debug.exe
echo.
start "" "%DIST_DIR%\BeatAccuracyChecker_debug\BeatAccuracyChecker_debug.exe"
pause
