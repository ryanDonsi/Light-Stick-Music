# 타임라인 생성 통계 로그 포맷

## 목적
각 노래별 이펙트 생성 시간을 측정하고 통계를 내기 위한 구조화된 로그 포맷입니다.

## 로그 포맷 (구조화, 파싱 가능)

### 전체 생성 시간 로그
```
[TIMELINE_STATS] file=<filename> musicId=<id> duration=<ms>
  [TOTAL] <ms>
  [BEAT_DETECT] <ms> version=<v>
  [SECTION_DETECT] <ms> version=<v> enabled=<bool>
  [EFFECT_MATCHING] <ms> version=<v>
  [OVERHEAD] <ms>
```

### 상세 예시

#### 예시 1: 기본 설정 (권장)
```
[TIMELINE_STATS] file=song1.mp3 musicId=12345 duration=180000
  [TOTAL] 25432ms
  [BEAT_DETECT] 5234ms version=3
  [SECTION_DETECT] 8145ms version=1 enabled=true
  [EFFECT_MATCHING] 10285ms version=1
  [OVERHEAD] 1768ms
```

#### 예시 2: 섹션 감지 비활성화
```
[TIMELINE_STATS] file=song2.mp3 musicId=67890 duration=240000
  [TOTAL] 8432ms
  [BEAT_DETECT] 5123ms version=3
  [SECTION_DETECT] 0ms version=1 enabled=false
  [EFFECT_MATCHING] 2145ms version=0
  [OVERHEAD] 1164ms
```

#### 예시 3: 고속 처리
```
[TIMELINE_STATS] file=song3.mp3 musicId=11111 duration=210000
  [TOTAL] 6234ms
  [BEAT_DETECT] 3456ms version=0
  [SECTION_DETECT] 0ms version=1 enabled=false
  [EFFECT_MATCHING] 1234ms version=0
  [OVERHEAD] 1544ms
```

## 파싱 규칙

### 정규식
```regex
^\[TIMELINE_STATS\] file=(.+) musicId=(\d+) duration=(\d+)$
  \[TOTAL\] (\d+)ms
  \[BEAT_DETECT\] (\d+)ms version=(\d)
  \[SECTION_DETECT\] (\d+)ms version=(\d) enabled=(true|false)
  \[EFFECT_MATCHING\] (\d+)ms version=(\d)
  \[OVERHEAD\] (\d+)ms
```

### CSV 형식 (for 통계)
```csv
파일명,musicId,음악길이(ms),전체(ms),비트감지(ms),섹션감지(ms),이펙트(ms),오버헤드(ms),비트버전,섹션버전,이펙트버전,섹션사용
song1.mp3,12345,180000,25432,5234,8145,10285,1768,3,1,1,true
song2.mp3,67890,240000,8432,5123,0,2145,1164,3,1,0,false
song3.mp3,11111,210000,6234,3456,0,1234,1544,0,1,0,false
```

## 통계 항목

### 기본 통계
- **평균 처리 시간**: 모든 곡의 평균
- **최빠른 곡**: 가장 빠르게 처리된 곡
- **가장 느린 곡**: 가장 느리게 처리된 곡
- **표준편차**: 처리 시간의 분산

### 파트별 통계
- **BeatDetector 시간 분포** (버전별)
  - V0: 평균, 최소, 최대
  - V1: 평균, 최소, 최대
  - V2: 평균, 최소, 최대
  - V3: 평균, 최소, 최대

- **SectionDetector 시간 분포**
  - 활성화 vs 비활성화
  - V0 vs V1

- **EffectMatchingEngine 시간 분포**
  - V0 vs V1

### 상관관계 분석
- **음악 길이 vs 처리 시간**
  - 선형 관계 확인
  - 이상치 탐지

- **버전 조합별 성능**
  - (BEAT_V3, SECTION_V1, EFFECT_V1) 조합 성능
  - (BEAT_V0, SECTION_OFF, EFFECT_V0) 조합 성능
  - 등등

## 로그 수집 방법

### 1. 로그 파일 저장
```kotlin
// Logcat 필터
adb logcat | grep "TIMELINE_STATS" > timeline_stats.log
```

### 2. 로그 분석 (Python)
```python
import re
import csv

def parse_timeline_logs(log_file):
    data = []
    with open(log_file, 'r') as f:
        lines = f.readlines()
    
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith('[TIMELINE_STATS]'):
            # 헤더 파싱
            match = re.search(r'file=(.+) musicId=(\d+) duration=(\d+)', line)
            if match:
                filename, music_id, duration = match.groups()
                
                # 상세 정보 파싱
                total = int(re.search(r'\[TOTAL\] (\d+)ms', lines[i+1]).group(1))
                beat_match = re.search(r'\[BEAT_DETECT\] (\d+)ms version=(\d)', lines[i+2])
                beat_time, beat_ver = int(beat_match.group(1)), int(beat_match.group(2))
                
                section_match = re.search(r'\[SECTION_DETECT\] (\d+)ms version=(\d) enabled=(true|false)', lines[i+3])
                section_time, section_ver, section_enabled = int(section_match.group(1)), int(section_match.group(2)), section_match.group(3) == 'true'
                
                effect_match = re.search(r'\[EFFECT_MATCHING\] (\d+)ms version=(\d)', lines[i+4])
                effect_time, effect_ver = int(effect_match.group(1)), int(effect_match.group(2))
                
                overhead = int(re.search(r'\[OVERHEAD\] (\d+)ms', lines[i+5]).group(1))
                
                data.append({
                    'file': filename,
                    'musicId': music_id,
                    'duration': int(duration),
                    'total': total,
                    'beat': beat_time,
                    'section': section_time,
                    'effect': effect_time,
                    'overhead': overhead,
                    'beat_ver': beat_ver,
                    'section_ver': section_ver,
                    'section_enabled': section_enabled,
                    'effect_ver': effect_ver
                })
                
                i += 6
            else:
                i += 1
        else:
            i += 1
    
    return data

# 사용
logs = parse_timeline_logs('timeline_stats.log')

# CSV 저장
with open('timeline_stats.csv', 'w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=['file', 'musicId', 'duration', 'total', 'beat', 'section', 'effect', 'overhead', 'beat_ver', 'section_ver', 'section_enabled', 'effect_ver'])
    writer.writeheader()
    writer.writerows(logs)

# 통계
import statistics
total_times = [log['total'] for log in logs]
print(f"평균: {statistics.mean(total_times):.0f}ms")
print(f"중앙값: {statistics.median(total_times):.0f}ms")
print(f"표준편차: {statistics.stdev(total_times):.0f}ms")
print(f"최소: {min(total_times):.0f}ms")
print(f"최대: {max(total_times):.0f}ms")
```

## 로그 샘플 수집 가이드

### 단계 1: 앱 실행 및 로그 수집
```bash
# Logcat 시작
adb logcat -c  # 버퍼 초기화
adb logcat | grep "TIMELINE_STATS" | tee timeline_stats.log

# 앱에서 노래 여러 개 재생 (또는 배치 생성)
# 로그가 쌓일 때까지 대기...
# Ctrl+C로 종료
```

### 단계 2: 로그 파일 확인
```bash
cat timeline_stats.log
```

### 단계 3: CSV로 변환 (위 Python 스크립트 사용)

### 단계 4: 엑셀에서 분석
- CSV를 엑셀에서 열기
- 피벗 테이블로 버전별 성능 비교
- 차트로 시각화

## 예상 통계 결과

### 버전별 평균 처리 시간 (3분 음악 기준)
| 설정 | 비트 | 섹션 | 이펙트 | 전체 |
|------|------|------|--------|------|
| V0-V0-V0 | 0.5s | - | 0.2s | 0.7s |
| V0-V0-V1 | 0.5s | - | 1.2s | 1.7s |
| V3-V1-V0 | 12s | 5s | 0.2s | 17s |
| V3-V1-V1 | 12s | 5s | 10s | 27s |

(실제 결과는 기기·곡 특성에 따라 다름)

## 주의사항

### 로그 수집 팁
1. 같은 설정으로 여러 곡 테스트 (최소 10곡)
2. 다양한 장르·길이 곡 포함
3. 반복 테스트 (워밍업 후 측정)
4. 배경 앱 종료 (정확한 측정)

### 분석 팁
1. 이상치 제거 (첫 1-2곡, 시스템 이벤트)
2. 음악 길이 정규화 (ms/min으로 비교)
3. 버전 조합별로 그룹화
4. 표준편차 큰 경우 재측정
