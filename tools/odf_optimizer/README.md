# ODF Optimizer GUI - V1 매개변수 최적화 도구

## 개요

이 애플리케이션은 Light-Stick-Music V1 BeatDetector의 ODF (Onset Detection Function) 계산 매개변수를 인터랙티브하게 조정하고 최적화할 수 있는 GUI 도구입니다.

## 설치

### 1. 요구사항
- Python 3.8 이상
- PyQt6, matplotlib, librosa, numpy

### 2. 의존성 설치

```bash
pip install -r requirements.txt
```

### 3. 음악 파일 준비
- MP3, WAV, FLAC 형식 지원
- 테스트할 음악 파일을 준비하면 됩니다

## 사용 방법

### 기본 사용법

```bash
python3 gui_app.py
```

### 주요 기능

#### 1. 파일 등록 ("📁 파일 추가" 버튼)
- 한 개 이상의 음악 파일을 선택하여 등록
- Beat Accuracy Checker처럼 파일 목록에 추가됨

#### 2. 매개변수 조정 ("파라미터" 탭)
다음 7개의 주요 매개변수를 실시간으로 조정:

| 매개변수 | 범위 | 기본값 | 설명 |
|---------|------|--------|------|
| Smooth Window | 1-10 | 3 | ODF 평활화 윈도우 |
| Local Window | 30-120 | 60 | 로컬 정규화 윈도우 |
| Global Window | 30-120 | 80 | 전역 정규화 윈도우 |
| Prior Center (ms) | 300-800 | 500 | 로그-정규 분포 중심 (약 120 BPM) |
| Prior STD (octave) | 0.5-4.0 | 2.0 | 로그-정규 분포 표준편차 |
| Min Beat (ms) | 250-500 | 375 | 최소 비트 간격 (최대 BPM) |
| Max Beat (ms) | 800-1500 | 1000 | 최대 비트 간격 (최소 BPM) |

#### 3. 분석 실행 ("🔄 분석 시작" 버튼)
- 선택된 곡을 현재 매개변수로 분석
- 백그라운드 스레드에서 처리 (UI 응답성 유지)

#### 4. 결과 시각화 ("결과" 탭)
- **ODF 곡선**: Onset Detection Function 시각화
- **AC/Prior/Score 곡선**: 자동상관, 사전확률, 최종 점수
- **MADMOM_GT 비교**: 지정된 곡의 경우 GT BPM과 비교
  - ✅: 오차 ≤1 BPM (정확)
  - ❌: 오차 >1 BPM (부정확)

## 기술 상세

### ODF 계산 파이프라인 (odf_engine.py)

1. **음성 로드**: librosa로 28kHz mono로 로드
2. **IIR 필터링**: 저역, 중역, 전역 밴드 분리
   - LOW_ALPHA: 0.12
   - MID_LP1_ALPHA: 0.35
   - MID_LP2_ALPHA: 0.08
3. **ODF 계산** (각 대역):
   - Moving Average (평활화)
   - Positive Diff (상승만 감지)
   - Local Normalize Max (로컬 최댓값 정규화)
4. **다중 대역 결합**: low×1.0 + mid×1.8 + full×0.8
5. **최종 정규화**: Global window로 배경 제거
6. **BPM 탐지**: 자동상관 + 로그-정규 사전확률

### 로그-정규 Prior 분포

```
log_ratio = log₂(lag_ms / prior_center_ms)
prior = exp(-0.5 × (log_ratio / prior_std_octave)²)
score = ac × prior
```

## 예상 정확도

현재 설정 (PRIOR_STD=2.0)으로:
- **전체 정확도**: 83.3% (35/42곡)
- **중간 속도 (110-140 BPM)**: 95.2% (매우 우수)
- **느린 곡 (<80 BPM)**: 80.0% (약간 취약)
- **중느린 (80-110 BPM)**: 63.6% (개선 필요)

## 알려진 제한사항

### Ed Sheeran - Shape of You
- **GT**: 96.77 BPM
- **현재 검출**: 130 BPM (오차 34.8%)
- **원인**: AC 신호가 매우 약함 (0.000877)
- **필요한 해결책**: ODF 신호 자체의 개선 (음성 전처리, 필터 최적화 등)
- **상태**: 매개변수 조정만으로는 해결 불가능

## 최적화 절차

### 1단계: 문제 곡 분석
```
1. 파일 등록 후 분석
2. "결과" 탭에서 ODF 곡선 확인
3. AC/Prior/Score 그래프에서 신호 강도 확인
```

### 2단계: 매개변수 조정
```
1. 약한 신호 문제:
   - Smooth Window 증가 (더 평활화)
   - Local/Global Window 조정

2. 잘못된 BPM 선택:
   - Prior Center MS 조정 (중심 BPM 변경)
   - Prior STD 조정 (분포 폭 변경)

3. 음역대별 문제:
   - 저음역: Global Window 증가
   - 고음역: Smooth Window 감소
```

### 3단계: 검증
```
1. MADMOM_GT가 있는 곡들로 정확도 확인
2. 전체 곡에 미치는 영향 검토
3. 매개변수 조합 테스트
```

## 출력 형식

### 분석 결과 (Python dict)
```python
{
    'bpm': 120.5,                    # 검출된 BPM
    'odf': np.array([...]),          # ODF 곡선 (frame 단위)
    'ac_vals': np.array([...]),      # Autocorrelation 값들
    'prior_vals': np.array([...]),   # Prior 분포 값들
    'score_vals': np.array([...]),   # 최종 점수 (AC × Prior)
    'duration_s': 240.5              # 음성 파일 길이 (초)
}
```

## 주의사항

1. **첫 분석은 느림**: 음성 파일 로드 및 ODF 계산에 시간 소요
   - MP3 파일: 30초~수 분
   - 파일 크기에 따라 다름

2. **배치 분석**: 여러 곡 동시 분석 시 순차 처리됨

3. **메모리**: 큰 음성 파일은 메모리 사용량 증가
   - 제한 없음 (시스템 메모리에 따라)

## 다음 단계

1. **배치 분석 모드**: 등록된 모든 곡을 한번에 분석
2. **매개변수 스윕**: 여러 조합을 자동으로 테스트
3. **결과 내보내기**: 최적화 결과를 JSON으로 저장
4. **신호 분석**: ODF 품질 진단 도구 추가

## 문제해결

### PyQt6 설치 실패
```bash
pip install --upgrade pip
pip install PyQt6 --no-cache-dir
```

### librosa 설치 실패 (의존성 문제)
```bash
# 먼저 필수 라이브러리 설치
pip install numpy scipy soundfile
pip install librosa
```

### GUI 실행 안 됨 (Linux/WSL)
```bash
# 가상 디스플레이 필요
sudo apt-get install xvfb
xvfb-run python3 gui_app.py
```

## 피드백 & 개선사항

현재 구현:
- ✅ 파일 등록 및 선택
- ✅ 매개변수 조정
- ✅ 실시간 분석
- ✅ ODF 곡선 시각화
- ✅ MADMOM_GT 비교

향후 개선 예정:
- 📋 배치 분석 모드
- 📊 매개변수 스윕
- 💾 결과 내보내기
- 📈 통계 분석
- 🎚️ 파일 드래그앤드롭

---

**마지막 수정**: 2026-06-29  
**버전**: V1.0 Beta
