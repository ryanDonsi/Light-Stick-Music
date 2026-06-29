# ODF Optimizer 설치 가이드

## 시스템 요구사항

- **Python**: 3.8 이상
- **OS**: Windows, macOS, Linux
- **RAM**: 2GB 이상 권장
- **디스크**: 500MB 이상 여유공간

## 1단계: Python 설치 확인

```bash
python3 --version
```

Python 3.8 이상이 설치되어 있는지 확인하세요.

## 2단계: 의존성 설치

### Windows / macOS / Linux (공통)

```bash
# 1. 이 디렉토리로 이동
cd odf_optimizer

# 2. 의존성 설치
pip install -r requirements.txt
```

### 설치 문제 해결

#### PyQt6 설치 실패
```bash
# 캐시 무시하고 설치
pip install PyQt6 --no-cache-dir --force-reinstall
```

#### librosa 설치 실패
```bash
# 필수 라이브러리 먼저 설치
pip install numpy scipy soundfile

# 그 후 librosa 설치
pip install librosa
```

#### matplotlib 설치 실패
```bash
pip install matplotlib --upgrade
```

## 3단계: 기본 테스트

GUI 전에 ODF 엔진이 제대로 작동하는지 테스트:

```bash
python3 test_odf_engine.py
```

예상 출력:
```
🚀 ODF Optimizer Engine 테스트

================================================================================
테스트 1: ODF 엔진 기본 기능
================================================================================
✅ 더미 오디오 생성: 1.0초, 28000Hz
...
✅ 기본 기능 테스트 완료
```

## 4단계: GUI 실행

```bash
python3 gui_app.py
```

### 플랫폼별 주의사항

#### Linux / WSL
GUI가 실행되지 않으면 virtual display 필요:

```bash
# xvfb 설치
sudo apt-get install xvfb

# GUI 실행
xvfb-run python3 gui_app.py
```

또는 SSH로 접속한 경우:
```bash
# X11 포워딩 활성화 후
python3 gui_app.py
```

#### Windows
- cmd 또는 PowerShell에서 실행
- 명령 프롬프트에서 직접 입력 가능

#### macOS
- Terminal에서 실행
- Homebrew를 통해 Python 설치 권장

## 사용 시작

### 1. 음악 파일 준비
- MP3, WAV, FLAC 파일 준비
- 최소 10초 이상의 음악 파일 권장

### 2. GUI에서 파일 등록
- "📁 파일 추가" 버튼 클릭
- 한 개 이상의 음악 파일 선택

### 3. 매개변수 설정
- "파라미터" 탭에서 조정
- 기본값으로 시작하는 것을 권장

### 4. 분석 실행
- 파일 목록에서 곡 선택
- "🔄 분석 시작" 버튼 클릭
- 첫 분석은 시간이 걸릴 수 있음 (30초~수 분)

### 5. 결과 확인
- "결과" 탭에서 ODF 곡선 시각화
- AC/Prior/Score 그래프 확인
- MADMOM_GT 비교 (등록된 곡의 경우)

## 성능 팁

### 빠른 분석을 위해
1. **첫 로드**: 음성 파일 로드 및 처리 시간
   - 파일 크기가 작을수록 빠름
   - 첫 분석 후 메모리에 캐시됨

2. **매개변수 조정**: 분석 결과가 캐시되어 있으면 빠름
   - 같은 파일을 여러 번 분석 가능

3. **배치 분석**: 모든 등록 파일 한번에 분석
   - 순차 처리되므로 시간 소요

### 메모리 최적화
- 큰 음성 파일(>100MB)은 메모리 사용량 증가
- 필요시 음성을 잘라서 사용
- librosa 기본 설정(28kHz)으로 자동 다운샘플링

## 문제해결

### "No module named 'PyQt6'" 오류
```bash
pip install PyQt6
pip install pyside6  # 또는 대체 GUI 프레임워크
```

### "ModuleNotFoundError: No module named 'librosa'" 오류
```bash
pip install librosa
# 또는 전체 재설치
pip install -r requirements.txt --force-reinstall
```

### GUI가 응답하지 않음
- 첫 파일 로드 중: 잠시 기다림 (분석 진행 중)
- 계속 응답 없음: 터미널에서 Ctrl+C로 종료 후 재시작
- 시스템 메모리 부족: 큰 파일부터 테스트 (작은 파일로 시작)

### ODF 곡선이 보이지 않음
1. 파일이 올바르게 로드되었는지 확인
2. "분석 시작" 버튼을 다시 클릭
3. "결과" 탭으로 전환

### 검출 BPM이 이상함
1. 매개변수 재확인 (기본값으로 리셋)
2. 다른 곡으로 테스트
3. README의 "알려진 제한사항" 섹션 확인

## 다음 단계

### 기본 사용
1. ✅ 파일 등록 및 분석
2. ✅ 결과 시각화
3. ✅ MADMOM_GT 비교

### 고급 기능
1. 📋 배치 분석으로 모든 곡 한번에 테스트
2. 💾 결과 JSON 내보내기
3. 📊 여러 매개변수 조합 비교

### 최적화 워크플로우
```
1. 문제 곡 식별
   → MADMOM_GT 비교를 통해 부정확한 곡 찾기

2. ODF 신호 분석
   → ODF 곡선 확인
   → AC 신호 강도 체크

3. 매개변수 조정
   → Prior Center/STD 조정
   → Window 크기 조정
   → 배치 분석으로 전체 영향 확인

4. 결과 검증
   → 정확도 개선 확인
   → 다른 곡에 미치는 영향 검토
   → 최적 설정 선택
```

## 추가 리소스

### 관련 문서
- `README.md` - 상세 사용 설명서
- `MADMOM_GT` 필드 - 43곡 ground truth BPM

### 테스트 데이터
- 기본 내장: 43곡 MADMOM_GT
- 추가 곡: 사용자 등록

### 커스터마이징
- `odf_engine.py`: ODF 계산 로직 수정
- `gui_app.py`: GUI 인터페이스 수정

## 버전 정보

- **ODF Optimizer**: V1.0 Beta
- **BeatDetectorV1 기반**: Kotlin에서 Python 포트
- **마지막 업데이트**: 2026-06-29

---

**문의 사항이 있으신가요?**
- 기본 사용: README.md 참고
- 기술 문제: GitHub Issue 등록
- 커스터마이징: `SETUP.md` 참고
