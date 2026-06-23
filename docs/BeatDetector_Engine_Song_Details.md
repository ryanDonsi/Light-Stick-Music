# BeatDetector 엔진별 곡 상세 분석

**측정 날짜**: 2026-06-23  
**분석**: 3버전 × 3엔진 × 32곡 = 288개 분석 결과  

---

## 📋 엔진별 성능 랭킹

### Beat Transformer 기준 - 곡별 V0/V1/V2 정확도

| 곡 제목 | V0 | V1 | V2 | 평가 |
|--------|-----|-----|-----|------|
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV   | 0.250 | 0.878 | **0.993** | S |
| Almond Chocolate                    | 0.996 | 0.941 | **0.986** | S |
| Dynamite                            | 0.997 | 0.819 | **0.984** | S |
| Tick-Tack                           | 0.281 | 0.826 | **0.978** | S |
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | 0.985 | 0.914 | **0.965** | S |
| IYKYK (If You Know You Know)        | 0.273 | 0.820 | **0.965** | S |
| Charlie Puth - Attention [Official Video] | 0.308 | 0.907 | **0.963** | S |
| I’ll Like You                       | 0.317 | 0.812 | **0.958** | S |
| My World                            | 0.283 | 0.808 | **0.957** | S |
| Pimple                              | 0.248 | 0.565 | **0.946** | S |
| BLACKPINK - 'Kill This Love' MV     | 0.494 | 0.829 | **0.939** | S |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | 0.529 | 0.741 | **0.930** | S |
| TOMBOY                              | 0.257 | 0.573 | **0.928** | S |
| Midnight Fiction                    | 0.275 | 0.685 | **0.928** | S |
| Let's go see the stars (별 보러 가자)    | 0.430 | 0.825 | **0.900** | S |
| 모든 날, 모든 순간 Every day, Every Moment | 0.567 | 0.844 | **0.893** | A |
| Good Goodbye                        | 0.647 | 0.790 | **0.880** | A |
| BLACKPINK - ‘뛰어(JUMP)’ MV           | 0.293 | 0.853 | **0.879** | A |
| Attention                           | 0.278 | 0.885 | **0.878** | A |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | 0.287 | 0.830 | **0.873** | A |


### librosa 기준 - 곡별 V0/V1/V2 정확도

| 곡 제목 | V0 | V1 | V2 | 평가 |
|--------|-----|-----|-----|------|
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | 0.090 | 0.984 | **0.974** | S |
| Good Goodbye                        | 0.509 | 0.863 | **0.961** | S |
| BLACKPINK - 'Kill This Love' MV     | 0.411 | 0.990 | **0.959** | S |
| Stray Kids 神메뉴(God's Menu) MV       | 0.334 | 0.922 | **0.957** | S |
| Tick-Tack                           | 0.247 | 0.907 | **0.953** | S |
| IYKYK (If You Know You Know)        | 0.327 | 0.860 | **0.949** | S |
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV   | 0.189 | 0.916 | **0.927** | S |
| 모든 날, 모든 순간 Every day, Every Moment | 0.323 | 0.909 | **0.924** | S |
| Charlie Puth - Attention [Official Video] | 0.242 | 0.925 | **0.922** | S |
| My World                            | 0.271 | 0.960 | **0.921** | S |
| Attention                           | 0.274 | 0.921 | **0.915** | S |
| Almond Chocolate                    | 0.257 | 0.905 | **0.904** | S |
| Pimple                              | 0.251 | 0.586 | **0.894** | A |
| I’ll Like You                       | 0.297 | 0.839 | **0.844** | A |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | 0.299 | 0.810 | **0.842** | A |
| aespa 에스파 'Next Level' MV           | 0.276 | 0.801 | **0.840** | A |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | 0.354 | 0.848 | **0.814** | A |
| Midnight Fiction                    | 0.280 | 0.799 | **0.805** | A |
| Golden                              | 0.489 | 0.695 | **0.775** | B |
| Dynamite                            | 0.028 | 0.819 | **0.749** | B |


### madmom 기준 - 곡별 V0/V1/V2 정확도

| 곡 제목 | V0 | V1 | V2 | 평가 |
|--------|-----|-----|-----|------|
| Entrance                            | 0.339 | 0.439 | **1.000** | S |
| 모든 날, 모든 순간 Every day, Every Moment | 0.409 | 0.960 | **1.000** | S |
| Almond Chocolate                    | 0.617 | 0.981 | **0.999** | S |
| IYKYK (If You Know You Know)        | 0.283 | 0.883 | **0.998** | S |
| I’ll Like You                       | 0.288 | 0.977 | **0.993** | S |
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV   | 0.184 | 0.937 | **0.989** | S |
| Dynamite                            | 0.414 | 0.895 | **0.988** | S |
| Tick-Tack                           | 0.298 | 0.953 | **0.988** | S |
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | 0.357 | 0.989 | **0.987** | S |
| Pimple                              | 0.253 | 0.618 | **0.984** | S |
| BLACKPINK - 'Kill This Love' MV     | 0.474 | 0.979 | **0.979** | S |
| My World                            | 0.267 | 0.971 | **0.974** | S |
| Charlie Puth - Attention [Official Video] | 0.125 | 0.922 | **0.971** | S |
| Good Goodbye                        | 0.658 | 0.870 | **0.971** | S |
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | 0.848 | 0.967 | **0.971** | S |
| Midnight Fiction                    | 0.282 | 0.944 | **0.968** | S |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | 0.299 | 0.994 | **0.968** | S |
| Let's go see the stars (별 보러 가자)    | 0.467 | 0.970 | **0.954** | S |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | 0.443 | 0.846 | **0.944** | S |
| Stray Kids 神메뉴(God's Menu) MV       | 0.378 | 0.947 | **0.942** | S |


---

## 🔍 엔진 간 성능 차이가 큰 곡들

### V1 기준: 엔진별 성능이 가장 다른 곡 (Top 5)

| 곡 제목 | Beat Transformer | librosa | madmom | 차이 |
|--------|---|---|---|------|
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | 0.827 | 0.422 | 0.989 | **0.566** |
| Magnetic                            | 0.791 | 0.470 | 0.976 | **0.506** |
| aespa 에스파 'Supernova' MV            | 0.757 | 0.465 | 0.915 | **0.450** |
| Celebrity (Celebrity)               | 0.812 | 0.562 | 0.995 | **0.433** |
| BLACKPINK - ‘뛰어(JUMP)’ MV           | 0.853 | 0.510 | 0.941 | **0.431** |
| TOMBOY                              | 0.573 | 0.950 | 0.653 | **0.377** |
| Stray Kids 神메뉴(God's Menu) MV       | 0.580 | 0.922 | 0.947 | **0.368** |
| Ed Sheeran - Shape of You (Official Music Video) | 0.231 | 0.577 | 0.253 | **0.346** |
| Let's go see the stars (별 보러 가자)    | 0.825 | 0.635 | 0.970 | **0.335** |
| [클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo | 0.939 | 0.688 | 0.969 | **0.281** |


### V2 기준: 엔진별 성능이 가장 다른 곡 (Top 5)

| 곡 제목 | Beat Transformer | librosa | madmom | 차이 |
|--------|---|---|---|------|
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | 0.852 | 0.415 | 0.987 | **0.572** |
| BLACKPINK - ‘뛰어(JUMP)’ MV           | 0.879 | 0.488 | 0.927 | **0.439** |
| Stray Kids 神메뉴(God's Menu) MV       | 0.599 | 0.957 | 0.942 | **0.358** |
| Let's go see the stars (별 보러 가자)    | 0.900 | 0.619 | 0.954 | **0.335** |
| TOMBOY                              | 0.928 | 0.616 | 0.927 | **0.312** |
| aespa 에스파 'Supernova' MV            | 0.760 | 0.451 | 0.763 | **0.311** |
| Entrance                            | 0.786 | 0.708 | 1.000 | **0.292** |
| Magnetic                            | 0.496 | 0.739 | 0.525 | **0.243** |
| Dynamite                            | 0.984 | 0.749 | 0.988 | **0.239** |
| [클래식을 좋아하세요! CD 01] Bach 01 Brandenburg Concerto No.3 In G Majo | 0.266 | 0.464 | 0.283 | **0.199** |


---

## 🎯 엔진별 특성 곡 분류

### Beat Transformer 기준 S등급 달성 곡 (V2)

**15곡 달성**

| 곡 제목 | F-Measure |
|--------|----------|
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | **0.9926** |
| Almond Chocolate | **0.9862** |
| Dynamite | **0.9837** |
| Tick-Tack | **0.9781** |
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | **0.9654** |
| IYKYK (If You Know You Know) | **0.9648** |
| Charlie Puth - Attention [Official Video] | **0.9630** |
| I’ll Like You | **0.9576** |
| My World | **0.9565** |
| Pimple | **0.9460** |
| BLACKPINK - 'Kill This Love' MV | **0.9388** |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | **0.9299** |
| TOMBOY | **0.9281** |
| Midnight Fiction | **0.9281** |
| Let's go see the stars (별 보러 가자) | **0.8997** |


### librosa 기준 S등급 달성 곡 (V2)

**12곡 달성**

| 곡 제목 | F-Measure |
|--------|----------|
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | **0.9735** |
| Good Goodbye | **0.9611** |
| BLACKPINK - 'Kill This Love' MV | **0.9589** |
| Stray Kids 神메뉴(God's Menu) MV | **0.9571** |
| Tick-Tack | **0.9531** |
| IYKYK (If You Know You Know) | **0.9494** |
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | **0.9274** |
| 모든 날, 모든 순간 Every day, Every Moment | **0.9244** |
| Charlie Puth - Attention [Official Video] | **0.9216** |
| My World | **0.9206** |
| Attention | **0.9147** |
| Almond Chocolate | **0.9038** |


### madmom 기준 S등급 달성 곡 (V2)

**22곡 달성** ⭐ 가장 많음

| 곡 제목 | F-Measure |
|--------|----------|
| Entrance | **1.0000** |
| 모든 날, 모든 순간 Every day, Every Moment | **1.0000** |
| Almond Chocolate | **0.9986** |
| IYKYK (If You Know You Know) | **0.9984** |
| I’ll Like You | **0.9928** |
| iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV | **0.9891** |
| Dynamite | **0.9877** |
| Tick-Tack | **0.9875** |
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | **0.9872** |
| Pimple | **0.9839** |
| BLACKPINK - 'Kill This Love' MV | **0.9786** |
| My World | **0.9740** |
| Charlie Puth - Attention [Official Video] | **0.9711** |
| Good Goodbye | **0.9705** |
| ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV | **0.9705** |
| Midnight Fiction | **0.9684** |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | **0.9677** |
| Let's go see the stars (별 보러 가자) | **0.9544** |
| LE SSERAFIM(르세라핌) 'SPAGHETTI' (4K)  STUDIO CHOOM ORIGINAL | **0.9442** |
| Stray Kids 神메뉴(God's Menu) MV | **0.9421** |
| TOMBOY | **0.9275** |
| BLACKPINK - ‘뛰어(JUMP)’ MV | **0.9271** |


---

## 📊 엔진 호환성 분석

### 모든 엔진에서 S등급을 달성한 곡 (V2)

**8곡** (모든 엔진에서 우수)

- Almond Chocolate
- BLACKPINK - 'Kill This Love' MV
- Charlie Puth - Attention [Official Video]
- ILLIT (아일릿) 'Lucky Girl Syndrome' Official MV
- IYKYK (If You Know You Know)
- My World
- Tick-Tack
- iKON - ‘사랑을 했다(LOVE SCENARIO)’ MV


### Beat Transformer와 madmom 성능 차이가 큰 곡

| 곡 제목 | Beat Transformer | madmom | 차이 |
|--------|---|---|------|
| Stray Kids 神메뉴(God's Menu) MV       | 0.599 | 0.942 | **0.343** |
| Entrance                            | 0.786 | 1.000 | **0.214** |
| ILLIT(아일릿) 'It’s Me' (4K)  STUDIO CHOOM ORIGINAL | 0.852 | 0.987 | **0.135** |
| 모든 날, 모든 순간 Every day, Every Moment | 0.893 | 1.000 | **0.107** |
| Celebrity (Celebrity)               | 0.692 | 0.794 | **0.102** |
| Soda Pop Official Lyric Video  KPop Demon Hunters  Sony Animation | 0.873 | 0.968 | **0.095** |
| Good Goodbye                        | 0.880 | 0.971 | **0.090** |
| ILLIT (아일릿) ‘Cherish (My Love)’ Dance Practice (Fix Ver.) | 0.839 | 0.764 | **0.075** |
| Golden                              | 0.818 | 0.874 | **0.056** |
| Let's go see the stars (별 보러 가자)    | 0.900 | 0.954 | **0.055** |
| BLACKPINK - ‘뛰어(JUMP)’ MV           | 0.879 | 0.927 | **0.048** |
| Midnight Fiction                    | 0.928 | 0.968 | **0.040** |
| BLACKPINK - 'Kill This Love' MV     | 0.939 | 0.979 | **0.040** |
| Pimple                              | 0.946 | 0.984 | **0.038** |
| I’ll Like You                       | 0.958 | 0.993 | **0.035** |
