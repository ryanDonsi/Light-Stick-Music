# 📊 librosa vs 우리 구현 비교

## 1️⃣ ODF (Onset Detection Function) 계산

### librosa의 방식:
```
음성 파일
  ↓
Spectrogram 계산 (STFT)
  ↓
Log-power spectrogram
  ↓
프레임별 에너지 변화 감지 (frame-wise energy)
  ↓
ODF (onset strength envelope)
```
- **기반**: Spectrogram의 에너지 변화
- **대역**: 모든 주파수 대역 포함
- **특징**: 신호 에너지의 절대값 기반

### 우리의 방식 (BeatDetectorV1 재현):
```
음성 파일
  ↓
IIR 필터 (Low, Mid, Full)
  ↓
각 대역별 신호 크기 추출
  ↓
Moving Average (평활화)
  ↓
Positive Diff (상승만 감지)
  ↓
Local Normalize (정규화)
  ↓
다중 대역 결합 (가중 평균)
  ↓
ODF
```
- **기반**: IIR 필터 출력의 RMS 에너지
- **대역**: 저역(Low), 중역(Mid), 전역(Full) 3개 분리
- **특징**: 필터별로 특정 주파수 대역만 추출

**📌 차이점**:
- librosa: Spectrogram 기반 (광범위 주파수 분석)
- 우리: IIR 필터 기반 (특정 대역만 강조)

---

## 2️⃣ BPM 탐지 (Tempo Estimation)

### librosa의 방식:
```
ODF
  ↓
Autocorrelation 계산
  ↓
Log-normal prior 적용
  ↓
Score = AC × Prior
  ↓
최고 점수의 BPM 선택
  ↓
(추가: Tempogram 검증)
```

### 우리의 방식 (현재):
```
ODF
  ↓
Autocorrelation 계산
  ↓
Log-normal prior 적용
  ↓
Score = AC × Prior
  ↓
최고 점수의 BPM 선택 ← 여기서 끝!
```

### BeatDetectorV1의 방식:
```
ODF
  ↓
Autocorrelation 계산
  ↓
Log-normal prior 적용
  ↓
Score = AC × Prior
  ↓
최고 점수의 BPM 선택
  ↓
✅ Half-tempo check (반박자 체크)
   - 2배 빠른 BPM의 AC가 55% 이상이면 선택
✅ Double-tempo check (2배속 체크)
   - 2배 느린 BPM의 AC가 임계값 이상이면 선택
  ↓
최종 BPM 결정
```

**📌 차이점**:
```
우리: bestLag 선택 → BPM 결정 (끝)
BeatDetectorV1: bestLag 선택 → Half/Double 체크 → BPM 결정
librosa: 유사하지만 tempogram 기반 검증 추가
```

---

## 3️⃣ 비트 추적 (Beat Tracking)

### librosa의 방식:
```
BPM 결정
  ↓
Dynamic Programming (Ellis DP)
  ↓
Gaussian local scoring
  ↓
최적 비트 시퀀스 추적
  ↓
각 비트의 시간 위치 반환
```

### BeatDetectorV1의 방식:
```
BPM 결정
  ↓
Dynamic Programming (Ellis DP)
  ↓
Gaussian local scoring + tightness=100
  ↓
최적 비트 시퀀스 추적
  ↓
Phase 추정 (시작 위치 결정)
  ↓
각 비트의 시간 위치 + confidence 반환
  ↓
(실패 시) Segment-based fallback
```

### 우리의 방식:
```
BPM만 반환
(비트 추적 없음) ❌
```

**📌 차이점**:
```
우리: BPM만 반환 (정보 부족)
librosa/BeatDetectorV1: BPM + 각 비트 위치 + confidence
```

---

## 🔴 우리 구현의 부족한 부분

| 항목 | librosa | BeatDetectorV1 | 우리 |
|------|---------|----------------|------|
| ODF 계산 | Spectrogram 기반 | IIR 필터 기반 | IIR 필터 기반 ✓ |
| Autocorrelation | ✓ | ✓ | ✓ |
| Log-normal Prior | ✓ | ✓ | ✓ |
| **Half-tempo Check** | ✓ | ✓ | ❌ |
| **Double-tempo Check** | ✓ | ✓ | ❌ |
| **Dynamic Programming** | ✓ | ✓ | ❌ |
| **Gaussian Scoring** | ✓ | ✓ | ❌ |
| **Phase Estimation** | ✓ | ✓ | ❌ |
| **비트 위치 반환** | ✓ | ✓ | ❌ |

---

## 🎯 Ed Sheeran 문제의 원인

### 예상 시나리오:

**1단계: ODF 계산**
```
Ed Sheeran "Shape of You" 입력
  ↓
우리의 IIR 필터 ODF 계산
  → 우리와 librosa의 ODF가 다를 수 있음
  → librosa는 광범위 Spectrogram 기반
  → 우리는 특정 대역 IIR 필터 기반
```

**2단계: Autocorrelation**
```
Lag 10 (96.77 BPM): AC = 0.02
Lag 20 (48.39 BPM): AC = 0.01
Lag 13.3 (133.33 BPM): AC = 0.025  ← 최고
  ↓
우리는 Lag 13.3을 선택 ❌
```

**3단계: Half-tempo Check (우리에게 없음)**
```
BeatDetectorV1:
  best_lag = 13.3 (133.33 BPM)
  half_lag = 6.65 (266.66 BPM)
  if (ac[6.65] / ac[13.3] >= 0.60):
      return half_lag
  
우리: 이 체크가 없어서 133.33 BPM 선택 ❌
```

---

## ✅ 해결 방안

### 즉시 가능 (낮은 난이도):
1. **Half-tempo & Double-tempo Check 추가**
   ```python
   # detect_bpm()에 추가
   half_lag = best_lag // 2
   if half_lag >= min_lag and ac_vals[half_lag] / ac_vals[best_lag] >= 0.60:
       return 60000 / (half_lag * hop_ms)
   ```

### 중기 목표 (중간 난이도):
2. **Phase Estimation 추가**
   - Onset 감지로 시작 위상 추정
   
3. **Dynamic Programming 추가**
   - Ellis DP로 비트 추적
   - 더 정확한 BPM 검증

### 장기 목표 (높은 난이도):
4. **ODF 계산 개선**
   - Spectrogram 기반 옵션 추가
   - librosa와의 비교 검증

---

## 📝 결론

| 측면 | 상태 |
|------|------|
| **기본 구조** | ✅ BeatDetectorV1 재현 완료 |
| **ODF 계산** | ⚠️ IIR 필터 방식은 맞지만, librosa와 다를 수 있음 |
| **BPM 탐지** | ❌ Half/Double-tempo Check 없음 |
| **비트 추적** | ❌ DP 미구현 |
| **최종 출력** | ❌ BPM만 반환 (비트 위치 없음) |

**다음 단계: Half-tempo Check를 먼저 추가해서 Ed Sheeran 문제 해결하기!** 🎯
