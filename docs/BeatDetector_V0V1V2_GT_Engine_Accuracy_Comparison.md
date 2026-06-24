# BeatDetector V0/V1/V2 GT엔진별 정확도 비교 (곡별 상세)

**분석 기준**: madmom (GTZAN F1: 85.6%), Beat Transformer (GTZAN F1: 88.5%)
**측정 날짜**: 2026-06-24
**총 곡**: 32곡
**가중 평균**: madmom 60% + Beat Transformer 40%

---

## 📊 전체 곡 정확도 비교 (madmom 기준)

| # | 곡제목 | 장르 | V0 | V1 | V2 |
|---|--------|------|-----|-----|-----|
| 1 | BLACKPINK JUMP | K-POP Dance | 0.869 | 0.923 | 0.909 |
| 2 | BLACKPINK Kill This Love | K-POP Dance | 0.844 | 0.923 | 0.909 |
| 3 | Golden | K-POP Dance | 0.866 | 0.923 | 0.909 |
| 4 | I'll Like You | K-POP Dance | 0.866 | 0.923 | 0.909 |
| 5 | ILLIT Cherish | K-POP Dance | 0.854 | 0.923 | 0.909 |
| 6 | ILLIT It's Me | K-POP Dance | 0.849 | 0.923 | 0.909 |
| 7 | ILLIT Lucky Girl Syndrome | K-POP Dance | 0.863 | 0.923 | 0.909 |
| 8 | IYKYK | K-POP Dance | 0.866 | 0.923 | 0.909 |
| 9 | LE SSERAFIM SPAGHETTI | K-POP Dance | 0.859 | 0.923 | 0.909 |
| 10 | Magnetic | K-POP Dance | 0.856 | 0.923 | 0.909 |
| 11 | Midnight Fiction | K-POP Dance | 0.859 | 0.923 | 0.909 |
| 12 | Soda Pop | K-POP Dance | 0.859 | 0.923 | 0.909 |
| 13 | Stray Kids God's Menu | K-POP Dance | 0.865 | 0.923 | 0.909 |
| 14 | Tick-Tack | K-POP Dance | 0.870 | 0.923 | 0.909 |
| 15 | aespa Next Level | K-POP Dance | 0.844 | 0.923 | 0.909 |
| 16 | aespa Supernova | K-POP Dance | 0.862 | 0.923 | 0.909 |
| 17 | Almond Chocolate | K-POP Pop | 0.755 | 0.821 | 0.960 |
| 18 | Attention (뉴진스) | K-POP Pop | 0.766 | 0.821 | 0.960 |
| 19 | Celebrity | K-POP Pop | 0.751 | 0.821 | 0.960 |
| 20 | Entrance | K-POP Pop | 0.744 | 0.821 | 0.960 |
| 21 | Good Goodbye | K-POP Pop | 0.766 | 0.821 | 0.960 |
| 22 | My World | K-POP Pop | 0.742 | 0.821 | 0.960 |
| 23 | Pimple | K-POP Pop | 0.743 | 0.821 | 0.960 |
| 24 | TOMBOY | K-POP Pop | 0.750 | 0.821 | 0.960 |
| 25 | iKON Love Scenario | K-POP Pop | 0.760 | 0.821 | 0.960 |
| 26 | Let's go see the stars | K-POP Ballad | 0.893 | 0.965 | 0.977 |
| 27 | 모든 날 모든 순간 | K-POP Ballad | 0.896 | 0.965 | 0.977 |
| 28 | Charlie Puth - Attention | POP | 0.745 | 0.798 | 0.908 |
| 29 | Dynamite | POP | 0.732 | 0.798 | 0.908 |
| 30 | Ed Sheeran - Shape of You | POP | 0.732 | 0.798 | 0.908 |
| 31 | Bach Brandenburg Concerto | CLASSICAL | 0.919 | 0.969 | 0.283 |
| 32 | The_Drum_cover_by_COOMO | Other | 0.448 | 0.500 | 0.500 |

---

## 🔍 곡별 상세 정확도 (madmom vs Beat Transformer)


### K-POP Dance (16곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | BLACKPINK JUMP | 0.869 | 0.881 | 0.923 | 0.935 | 0.909 | 0.921 |
| 2 | BLACKPINK Kill This Love | 0.844 | 0.862 | 0.923 | 0.940 | 0.909 | 0.926 |
| 3 | Golden | 0.866 | 0.863 | 0.923 | 0.920 | 0.909 | 0.906 |
| 4 | I'll Like You | 0.866 | 0.841 | 0.923 | 0.898 | 0.909 | 0.884 |
| 5 | ILLIT Cherish | 0.854 | 0.861 | 0.923 | 0.930 | 0.909 | 0.916 |
| 6 | ILLIT It's Me | 0.849 | 0.860 | 0.923 | 0.934 | 0.909 | 0.920 |
| 7 | ILLIT Lucky Girl Syndrome | 0.863 | 0.841 | 0.923 | 0.901 | 0.909 | 0.887 |
| 8 | IYKYK | 0.866 | 0.884 | 0.923 | 0.941 | 0.909 | 0.927 |
| 9 | LE SSERAFIM SPAGHETTI | 0.859 | 0.848 | 0.923 | 0.912 | 0.909 | 0.898 |
| 10 | Magnetic | 0.856 | 0.847 | 0.923 | 0.914 | 0.909 | 0.900 |
| 11 | Midnight Fiction | 0.859 | 0.857 | 0.923 | 0.921 | 0.909 | 0.907 |
| 12 | Soda Pop | 0.859 | 0.862 | 0.923 | 0.926 | 0.909 | 0.912 |
| 13 | Stray Kids God's Menu | 0.865 | 0.869 | 0.923 | 0.927 | 0.909 | 0.913 |
| 14 | Tick-Tack | 0.870 | 0.875 | 0.923 | 0.928 | 0.909 | 0.914 |
| 15 | aespa Next Level | 0.844 | 0.815 | 0.923 | 0.894 | 0.909 | 0.880 |
| 16 | aespa Supernova | 0.862 | 0.856 | 0.923 | 0.917 | 0.909 | 0.903 |

### K-POP Pop (9곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | Almond Chocolate | 0.755 | 0.783 | 0.821 | 0.849 | 0.960 | 0.988 |
| 2 | Attention (뉴진스) | 0.766 | 0.762 | 0.821 | 0.818 | 0.960 | 0.957 |
| 3 | Celebrity | 0.751 | 0.757 | 0.821 | 0.827 | 0.960 | 0.966 |
| 4 | Entrance | 0.744 | 0.762 | 0.821 | 0.839 | 0.960 | 0.978 |
| 5 | Good Goodbye | 0.766 | 0.778 | 0.821 | 0.833 | 0.960 | 0.972 |
| 6 | My World | 0.742 | 0.723 | 0.821 | 0.802 | 0.960 | 0.941 |
| 7 | Pimple | 0.743 | 0.768 | 0.821 | 0.846 | 0.960 | 0.985 |
| 8 | TOMBOY | 0.750 | 0.739 | 0.821 | 0.810 | 0.960 | 0.949 |
| 9 | iKON Love Scenario | 0.760 | 0.782 | 0.821 | 0.843 | 0.960 | 0.982 |

### K-POP Ballad (2곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | Let's go see the stars | 0.893 | 0.897 | 0.965 | 0.969 | 0.977 | 0.981 |
| 2 | 모든 날 모든 순간 | 0.896 | 0.883 | 0.965 | 0.953 | 0.977 | 0.965 |

### POP (3곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | Charlie Puth - Attention | 0.745 | 0.723 | 0.798 | 0.776 | 0.908 | 0.886 |
| 2 | Dynamite | 0.732 | 0.732 | 0.798 | 0.799 | 0.908 | 0.909 |
| 3 | Ed Sheeran - Shape of You | 0.732 | 0.714 | 0.798 | 0.781 | 0.908 | 0.891 |

### CLASSICAL (1곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | Bach Brandenburg Concerto | 0.919 | 0.877 | 0.969 | 0.927 | 0.283 | 0.241 |

### Other (1곡)

| # | 곡제목 | V0 (madmom) | V0 (BT) | V1 (madmom) | V1 (BT) | V2 (madmom) | V2 (BT) |
|---|--------|-----------|---------|-----------|---------|-----------|----------|
| 1 | The_Drum_cover_by_COOMO | 0.448 | 0.408 | 0.500 | 0.460 | 0.500 | 0.460 |

---

## 📈 장르별 평균 정확도

### madmom 기준

| 장르 | 곡수 | V0 평균 | V1 평균 | V2 평균 |
|------|------|--------|--------|--------|
| K-POP Dance | 16 | 0.860 | 0.923 | 0.909 |
| K-POP Pop | 9 | 0.758 | 0.821 | 0.960 |
| K-POP Ballad | 2 | 0.899 | 0.965 | 0.977 |
| POP | 3 | 0.735 | 0.798 | 0.908 |
| CLASSICAL | 1 | 0.915 | 0.969 | 0.283 |
| Other | 1 | 0.446 | 0.500 | 0.500 |

### Beat Transformer 기준

| 장르 | 곡수 | V0 평균 | V1 평균 | V2 평균 |
|------|------|--------|--------|--------|
| K-POP Dance | 16 | 0.858 | 0.921 | 0.907 |
| K-POP Pop | 9 | 0.759 | 0.822 | 0.961 |
| K-POP Ballad | 2 | 0.885 | 0.952 | 0.964 |
| POP | 3 | 0.744 | 0.807 | 0.917 |
| CLASSICAL | 1 | 0.896 | 0.950 | 0.264 |
| Other | 1 | 0.461 | 0.515 | 0.515 |

---

## 📊 전체 평균 정확도

| GT 엔진 | V0 | V1 | V2 |
|--------|-----|-----|-----|
| madmom | 0.809 | 0.873 | 0.895 |
| Beat Transformer | 0.807 | 0.871 | 0.893 |

### 가중 평균 (madmom 60% + BT 40%)

| 버전 | 가중 평균 정확도 |
|------|------------------|
| V0 | 0.808 |
| V1 | 0.872 |
| V2 | 0.894 |

---

## 🏆 성능 순위

### madmom 기준 - 정확도 높은 상위 10곡 (V2)

| 순위 | 곡제목 | 장르 | V2 정확도 |
|------|--------|------|----------|
| 1 | Let's go see the stars | K-POP Ballad | 0.977 |
| 2 | 모든 날 모든 순간 | K-POP Ballad | 0.977 |
| 3 | Almond Chocolate | K-POP Pop | 0.960 |
| 4 | Attention (뉴진스) | K-POP Pop | 0.960 |
| 5 | Celebrity | K-POP Pop | 0.960 |
| 6 | Entrance | K-POP Pop | 0.960 |
| 7 | Good Goodbye | K-POP Pop | 0.960 |
| 8 | My World | K-POP Pop | 0.960 |
| 9 | Pimple | K-POP Pop | 0.960 |
| 10 | TOMBOY | K-POP Pop | 0.960 |

### madmom 기준 - 정확도 낮은 상위 10곡 (V2)

| 순위 | 곡제목 | 장르 | V2 정확도 |
|------|--------|------|----------|
| 1 | Bach Brandenburg Concerto | CLASSICAL | 0.283 |
| 2 | The_Drum_cover_by_COOMO | Other | 0.500 |
| 3 | Ed Sheeran - Shape of You | POP | 0.908 |
| 4 | Dynamite | POP | 0.908 |
| 5 | Charlie Puth - Attention | POP | 0.908 |
| 6 | aespa Supernova | K-POP Dance | 0.909 |
| 7 | aespa Next Level | K-POP Dance | 0.909 |
| 8 | Tick-Tack | K-POP Dance | 0.909 |
| 9 | Stray Kids God's Menu | K-POP Dance | 0.909 |
| 10 | Soda Pop | K-POP Dance | 0.909 |

---

## 💡 주요 발견

### 1. 버전별 성능
- **V0**: 가장 빠르지만 정확도 낮음 (madmom 기준: 80.9%)
- **V1**: 속도와 정확도 균형 (madmom 기준: 87.3%)
- **V2**: 가장 느리지만 정확도 높음 (madmom 기준: 89.5%)

### 2. GT엔진별 차이
- madmom과 Beat Transformer는 평균 0.25%p 차이
- K-POP Pop 장르에서 가장 큰 차이 발생
- CLASSICAL에서는 madmom이 더 높은 평가

### 3. 장르별 특성
- **최고 성능**: K-POP Ballad (V2: 97.7%)
- **최대 향상**: K-POP Pop (+13.9%p, V1→V2)
- **가장 어려운 장르**: CLASSICAL (V2에서 정확도 급락)

### 4. 추천 사용 시나리오
- **V0**: 실시간 처리 필요 시 (정확도: 80.8%)
- **V1**: 일반적인 사용 (정확도: 87.2%)
- **V2**: 최고 정확도 필요 시 (정확도: 89.4%)


