# BeatDetector V0/V1/V2 GT엔진별 정확도 비교 (실제 측정 데이터)

**분석 기준**: Beat Transformer (GTZAN F1: 88.5%), madmom (GTZAN F1: 85.6%)
**측정 날짜**: 2026-06-23
**총 곡**: 32곡
**정확도 가중치**: Beat Transformer 60% + madmom 40%
**평가 메트릭**: F-Measure (70ms tolerance window)

---

## 📊 전체 곡 정확도 비교

| # | 곡제목 | 장르 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|---|--------|------|--------|-----------|--------|-----------|--------|-----------|
| 1 | Almond Chocolate | K-POP Pop | 0.996 | 0.617 | 0.941 | 0.981 | 0.986 | 0.999 |
| 2 | Attention | K-POP Pop | 0.278 | 0.268 | 0.885 | 0.902 | 0.878 | 0.898 |
| 3 | BLACKPINK - 'Kill This Love' MV | K-POP Dance | 0.494 | 0.474 | 0.829 | 0.979 | 0.939 | 0.979 |
| 4 | BLACKPINK - ‘뛰어(JUMP)’ MV | Unknown | 0.293 | 0.328 | 0.853 | 0.941 | 0.879 | 0.927 |
| 5 | Celebrity (Celebrity) | K-POP Pop | 0.440 | 0.417 | 0.812 | 0.995 | 0.692 | 0.794 |
| 6 | Charlie Puth - Attention [Official Video] | POP | 0.308 | 0.125 | 0.907 | 0.922 | 0.963 | 0.971 |
| 7 | Dynamite | POP | 0.997 | 0.414 | 0.819 | 0.895 | 0.984 | 0.988 |
| 8 | Ed Sheeran - Shape of You (Official Music Video) | POP | 0.246 | 0.263 | 0.231 | 0.253 | 0.266 | 0.275 |
| 9 | Entrance | K-POP Pop | 0.455 | 0.339 | 0.329 | 0.439 | 0.786 | 1.000 |
| 10 | Golden | K-POP Dance | 0.440 | 0.511 | 0.647 | 0.833 | 0.818 | 0.874 |
| 11 | Good Goodbye | K-POP Pop | 0.647 | 0.658 | 0.790 | 0.870 | 0.880 | 0.971 |
| 12 | ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | K-POP Dance | 0.985 | 0.848 | 0.914 | 0.967 | 0.965 | 0.971 |
| 13 | ILLIT (아일릿) ‘Cherish (My Love)’ Dance Practice (Fix Ver.) | K-POP Dance | 0.288 | 0.311 | 0.458 | 0.620 | 0.839 | 0.764 |
| 14 | ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | K-POP Dance | 0.179 | 0.357 | 0.827 | 0.989 | 0.852 | 0.987 |
| 15 | IYKYK (If You Know You Know) | K-POP Dance | 0.273 | 0.283 | 0.820 | 0.883 | 0.965 | 0.998 |
| 16 | I’ll Like You | Unknown | 0.317 | 0.288 | 0.812 | 0.977 | 0.958 | 0.993 |
| 17 | LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | K-POP Dance | 0.529 | 0.443 | 0.741 | 0.846 | 0.930 | 0.944 |
| 18 | Let's go see the stars (별 보러 가자) | K-POP Ballad | 0.430 | 0.467 | 0.825 | 0.970 | 0.900 | 0.954 |
| 19 | Magnetic | K-POP Dance | 0.381 | 0.343 | 0.791 | 0.976 | 0.496 | 0.525 |
| 20 | Midnight Fiction | K-POP Dance | 0.275 | 0.282 | 0.685 | 0.944 | 0.928 | 0.968 |
| 21 | My World | K-POP Pop | 0.283 | 0.267 | 0.808 | 0.971 | 0.957 | 0.974 |
| 22 | Pimple | K-POP Pop | 0.248 | 0.253 | 0.565 | 0.618 | 0.946 | 0.984 |
| 23 | Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | K-POP Dance | 0.287 | 0.299 | 0.830 | 0.994 | 0.873 | 0.968 |
| 24 | Stray Kids 神메뉴(God's Menu) MV | K-POP Dance | 0.219 | 0.378 | 0.580 | 0.947 | 0.599 | 0.942 |
| 25 | TOMBOY | K-POP Pop | 0.257 | 0.278 | 0.573 | 0.653 | 0.928 | 0.927 |
| 26 | The_Drum_cover_by_COOMO | Other | 0.318 | 0.285 | 0.473 | 0.514 | 0.488 | 0.469 |
| 27 | Tick-Tack | K-POP Dance | 0.281 | 0.298 | 0.826 | 0.953 | 0.978 | 0.988 |
| 28 | [클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo | CLASSICAL | 0.260 | 0.258 | 0.939 | 0.969 | 0.266 | 0.283 |
| 29 | aespa 에스파 'Next Level' MV | Unknown | 0.248 | 0.256 | 0.738 | 0.787 | 0.784 | 0.799 |
| 30 | aespa 에스파 'Supernova' MV | Unknown | 0.948 | 0.240 | 0.757 | 0.915 | 0.760 | 0.763 |
| 31 | iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | K-POP Pop | 0.250 | 0.184 | 0.878 | 0.937 | 0.993 | 0.989 |
| 32 | 모든 날, 모든 순간 Every day, Every Moment | K-POP Ballad | 0.567 | 0.409 | 0.844 | 0.960 | 0.893 | 1.000 |

---

## 📈 등급 분포

| 버전 | A | B | C | D | F |
|------|---|---|---|---|---|
| V0 | 1 | 3 | 19 | 69 | 4 |
| V1 | 26 | 16 | 16 | 3 | 35 |
| V2 | 17 | 14 | 11 | 5 | 49 |

---

## 📊 버전별 정확도 평균

| 버전 | 평균 F-Measure | 정확도 |
|------|----------------|--------|
| V0 | 0.361 | 36.1% |
| V1 | 0.786 | 78.6% |
| V2 | 0.821 | 82.1% |

---

## 🔍 GT엔진별 정확도

| 버전 | Beat Transformer | madmom | 가중평균 (BT 60% + madmom 40%) |
|------|------------------|--------|-------------------------------|
| V0 | 0.419 (41.9%) | 0.358 (35.8%) | 0.395 (39.5%) |
| V1 | 0.741 (74.1%) | 0.856 (85.6%) | 0.787 (78.7%) |
| V2 | 0.824 (82.4%) | 0.871 (87.1%) | 0.843 (84.3%) |

---

## 🎭 장르별 정확도 비교

### CLASSICAL (1곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| [클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo | 0.260 | 0.258 | 0.939 | 0.969 | 0.266 | 0.283 |

**CLASSICAL 평균** | 0.260 | 0.258 | 0.939 | 0.969 | 0.266 | 0.283 |

### K-POP Ballad (2곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| Let's go see the stars (별 보러 가자) | 0.430 | 0.467 | 0.825 | 0.970 | 0.900 | 0.954 |
| 모든 날, 모든 순간 Every day, Every Moment | 0.567 | 0.409 | 0.844 | 0.960 | 0.893 | 1.000 |

**K-POP Ballad 평균** | 0.498 | 0.438 | 0.834 | 0.965 | 0.896 | 0.977 |

### K-POP Dance (12곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| BLACKPINK - 'Kill This Love' MV | 0.494 | 0.474 | 0.829 | 0.979 | 0.939 | 0.979 |
| Golden | 0.440 | 0.511 | 0.647 | 0.833 | 0.818 | 0.874 |
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | 0.985 | 0.848 | 0.914 | 0.967 | 0.965 | 0.971 |
| ILLIT (아일릿) ‘Cherish (My Love)’ Dance Practice (Fix Ver.) | 0.288 | 0.311 | 0.458 | 0.620 | 0.839 | 0.764 |
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | 0.179 | 0.357 | 0.827 | 0.989 | 0.852 | 0.987 |
| IYKYK (If You Know You Know) | 0.273 | 0.283 | 0.820 | 0.883 | 0.965 | 0.998 |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | 0.529 | 0.443 | 0.741 | 0.846 | 0.930 | 0.944 |
| Magnetic | 0.381 | 0.343 | 0.791 | 0.976 | 0.496 | 0.525 |
| Midnight Fiction | 0.275 | 0.282 | 0.685 | 0.944 | 0.928 | 0.968 |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | 0.287 | 0.299 | 0.830 | 0.994 | 0.873 | 0.968 |
| Stray Kids 神메뉴(God's Menu) MV | 0.219 | 0.378 | 0.580 | 0.947 | 0.599 | 0.942 |
| Tick-Tack | 0.281 | 0.298 | 0.826 | 0.953 | 0.978 | 0.988 |

**K-POP Dance 평균** | 0.386 | 0.402 | 0.746 | 0.911 | 0.848 | 0.909 |

### K-POP Pop (9곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| Almond Chocolate | 0.996 | 0.617 | 0.941 | 0.981 | 0.986 | 0.999 |
| Attention | 0.278 | 0.268 | 0.885 | 0.902 | 0.878 | 0.898 |
| Celebrity (Celebrity) | 0.440 | 0.417 | 0.812 | 0.995 | 0.692 | 0.794 |
| Entrance | 0.455 | 0.339 | 0.329 | 0.439 | 0.786 | 1.000 |
| Good Goodbye | 0.647 | 0.658 | 0.790 | 0.870 | 0.880 | 0.971 |
| My World | 0.283 | 0.267 | 0.808 | 0.971 | 0.957 | 0.974 |
| Pimple | 0.248 | 0.253 | 0.565 | 0.618 | 0.946 | 0.984 |
| TOMBOY | 0.257 | 0.278 | 0.573 | 0.653 | 0.928 | 0.927 |
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | 0.250 | 0.184 | 0.878 | 0.937 | 0.993 | 0.989 |

**K-POP Pop 평균** | 0.428 | 0.364 | 0.731 | 0.818 | 0.894 | 0.948 |

### Other (1곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| The_Drum_cover_by_COOMO | 0.318 | 0.285 | 0.473 | 0.514 | 0.488 | 0.469 |

**Other 평균** | 0.318 | 0.285 | 0.473 | 0.514 | 0.488 | 0.469 |

### POP (3곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| Charlie Puth - Attention [Official Video] | 0.308 | 0.125 | 0.907 | 0.922 | 0.963 | 0.971 |
| Dynamite | 0.997 | 0.414 | 0.819 | 0.895 | 0.984 | 0.988 |
| Ed Sheeran - Shape of You (Official Music Video) | 0.246 | 0.263 | 0.231 | 0.253 | 0.266 | 0.275 |

**POP 평균** | 0.517 | 0.267 | 0.652 | 0.690 | 0.737 | 0.745 |

### Unknown (4곡)

| 곡제목 | V0 (BT) | V0 (madmom) | V1 (BT) | V1 (madmom) | V2 (BT) | V2 (madmom) |
|--------|---------|-----------|---------|-----------|---------|----------|
| BLACKPINK - ‘뛰어(JUMP)’ MV | 0.293 | 0.328 | 0.853 | 0.941 | 0.879 | 0.927 |
| I’ll Like You | 0.317 | 0.288 | 0.812 | 0.977 | 0.958 | 0.993 |
| aespa 에스파 'Next Level' MV | 0.248 | 0.256 | 0.738 | 0.787 | 0.784 | 0.799 |
| aespa 에스파 'Supernova' MV | 0.948 | 0.240 | 0.757 | 0.915 | 0.760 | 0.763 |

**Unknown 평균** | 0.451 | 0.278 | 0.790 | 0.905 | 0.845 | 0.871 |


---

## 🏆 성능 순위 (V2 가중평균 기준)

### 상위 15곡

| 순위 | 곡제목 | 장르 | V2 (BT) | V2 (madmom) | 가중평균 |
|------|--------|------|---------|-----------|----------|
| 1 | iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | K-POP Pop | 0.993 | 0.989 | 0.991 |
| 2 | Almond Chocolate | K-POP Pop | 0.986 | 0.999 | 0.991 |
| 3 | Dynamite | POP | 0.984 | 0.988 | 0.985 |
| 4 | Tick-Tack | K-POP Dance | 0.978 | 0.988 | 0.982 |
| 5 | IYKYK (If You Know You Know) | K-POP Dance | 0.965 | 0.998 | 0.978 |
| 6 | I’ll Like You | Unknown | 0.958 | 0.993 | 0.972 |
| 7 | ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | K-POP Dance | 0.965 | 0.971 | 0.967 |
| 8 | Charlie Puth - Attention [Official Video] | POP | 0.963 | 0.971 | 0.966 |
| 9 | My World | K-POP Pop | 0.957 | 0.974 | 0.964 |
| 10 | Pimple | K-POP Pop | 0.946 | 0.984 | 0.961 |
| 11 | BLACKPINK - 'Kill This Love' MV | K-POP Dance | 0.939 | 0.979 | 0.955 |
| 12 | Midnight Fiction | K-POP Dance | 0.928 | 0.968 | 0.944 |
| 13 | 모든 날, 모든 순간 Every day, Every Moment | K-POP Ballad | 0.893 | 1.000 | 0.936 |
| 14 | LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | K-POP Dance | 0.930 | 0.944 | 0.936 |
| 15 | TOMBOY | K-POP Pop | 0.928 | 0.927 | 0.928 |

### 하위 15곡

| 순위 | 곡제목 | 장르 | V2 (BT) | V2 (madmom) | 가중평균 |
|------|--------|------|---------|-----------|----------|
| 1 | Ed Sheeran - Shape of You (Official Music Video) | POP | 0.266 | 0.275 | 0.269 |
| 2 | [클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo | CLASSICAL | 0.266 | 0.283 | 0.273 |
| 3 | The_Drum_cover_by_COOMO | Other | 0.488 | 0.469 | 0.481 |
| 4 | Magnetic | K-POP Dance | 0.496 | 0.525 | 0.507 |
| 5 | Celebrity (Celebrity) | K-POP Pop | 0.692 | 0.794 | 0.733 |
| 6 | Stray Kids 神메뉴(God's Menu) MV | K-POP Dance | 0.599 | 0.942 | 0.736 |
| 7 | aespa 에스파 'Supernova' MV | Unknown | 0.760 | 0.763 | 0.761 |
| 8 | aespa 에스파 'Next Level' MV | Unknown | 0.784 | 0.799 | 0.790 |
| 9 | ILLIT (아일릿) ‘Cherish (My Love)’ Dance Practice (Fix Ver.) | K-POP Dance | 0.839 | 0.764 | 0.809 |
| 10 | Golden | K-POP Dance | 0.818 | 0.874 | 0.840 |
| 11 | Entrance | K-POP Pop | 0.786 | 1.000 | 0.872 |
| 12 | Attention | K-POP Pop | 0.878 | 0.898 | 0.886 |
| 13 | BLACKPINK - ‘뛰어(JUMP)’ MV | Unknown | 0.879 | 0.927 | 0.898 |
| 14 | ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | K-POP Dance | 0.852 | 0.987 | 0.906 |
| 15 | Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | K-POP Dance | 0.873 | 0.968 | 0.911 |

---

## 📋 최종 요약

### 버전별 성능
- **V0**: 가장 빠르지만 정확도 낮음 (36.1%)
- **V1**: 속도와 정확도 균형 (78.6%)
- **V2**: 가장 느리지만 정확도 높음 (82.1%)

### GT엔진 특성
- **Beat Transformer**: 더 엄격한 평가 기준
- **madmom**: 상대적으로 관대한 평가
- **권장**: 두 엔진의 가중평균(BT 60% + madmom 40%)을 정확도 지표로 활용

### 최고 성능 곡 (V2 가중평균 기준)
**iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV** (K-POP Pop): 0.991

### 최저 성능 곡 (V2 가중평균 기준)
**Ed Sheeran - Shape of You (Official Music Video)** (POP): 0.269


