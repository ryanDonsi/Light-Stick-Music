# Music Style Classification Examples - Comprehensive Song Database

This document shows how different songs are classified based on their BPM, energy characteristics, and frequency content.

## Classification Summary by Style

### BALLAD (느린, 부드러운 곡)
Slow tempo, low energy, smooth transitions

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| Korean Ballad | 60-70 | 857-1000 | **BALLAD** | Slow + low energy |
| Sad Ballad | 70-80 | 750-857 | **BALLAD** | Emotional + low dynamic |
| Love Ballad | 75-85 | 706-800 | **BALLAD** | Gentle vocals + minimal drums |
| Smooth R&B Ballad | 85 | 706 | BALLAD or HIPHOP_RNB | Edge case - energy matters |

---

### K-POP DANCE (빠르고 규칙적)
Fast tempo, regular beats, dance-oriented choreography

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| K-POP Dance (moderate) | 110-115 | 521-545 | **DANCE_POP** | 545ms boundary, regular beat |
| K-POP Dance (fast) | 120-130 | 461-500 | **DANCE_POP** / **EDM** | Depends on onsetDensity |
| K-POP Energetic | 130-140 | 428-461 | **DANCE_POP** | Strong bass, good periodicity |
| K-POP Club Mix | 135-145 | 414-444 | **DANCE_POP** / **EDM** | High onsets → EDM |

---

### EDM (전자음악, 매우 규칙적)
Very fast, electronic precision, heavy bass, many percussive hits

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| House Music | 120-130 | 461-500 | **EDM** | If periodicity > 0.65 + onsets > 4 |
| Trance | 125-140 | 428-480 | **EDM** | Regular + strong bass + many onsets |
| Techno | 120-150 | 400-500 | **EDM** | Electronic beats, high precision |
| Dubstep | 140-180 | 333-428 | **EDM** | Fast + syncopated drops |
| Future Bass | 100-120 | 500-600 | **DANCE_POP** / **EDM** | Boundary case - depends on onsets |

---

### POP (대중적, 혼합형)
Moderate to fast, melodic, mixed frequency content

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| Mainstream Pop | 90-105 | 571-666 | **POP** | No strong bass, moderate energy |
| Synth Pop | 100-120 | 500-600 | **DANCE_POP** / **POP** | Depends on periodicity |
| Indie Pop | 95-115 | 521-631 | **POP** | Acoustic elements reduce bass ratio |
| Pop Ballad | 80-95 | 631-750 | **HIPHOP_RNB** / **POP** | Slower pop, borderline |

---

### HIP-HOP / R&B (중간 템포, 싱코페이션)
Mid-range tempo, syncopated rhythm, groove-oriented

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| Classic Hip-Hop | 95-105 | 571-631 | **HIPHOP_RNB** | Mid tempo, syncopated beats |
| R&B Smooth | 90-100 | 600-666 | **HIPHOP_RNB** / **POP** | Low periodicity OK for HH |
| Trap Music | 100-140 | 428-600 | **DANCE_POP** / **EDM** | Fast sub-genre, periodic |
| Drill | 95-110 | 545-631 | **HIPHOP_RNB** | Syncopated delivery |

---

### ROCK (고음역 많음, 거친 에너지)
High-frequency content, aggressive energy transitions

| Song Genre | Typical BPM | beatMs | Expected Style | Reason |
|-----------|-----------|--------|-----------------|--------|
| Rock Ballad | 70-90 | 666-857 | **BALLAD** or **ROCK** | Depends: low energy → BALLAD |
| Alternative Rock | 100-130 | 461-600 | **ROCK** | If highRatio > 0.28 + energyFlux > 0.040 |
| Heavy Metal | 120-180 | 333-500 | **ROCK** | High frequencies + aggressive transitions |
| Pop Punk | 110-140 | 428-545 | **ROCK** or **DANCE_POP** | Guitar-heavy vs synth-heavy |
| Acoustic Rock | 90-120 | 500-666 | **ROCK** or **POP** | Depends on amplitude dynamics |

---

## Real-World Test Cases (From Previous Sessions)

Based on songs tested in the previous development sessions:

| Song Name | Artist | BPM | beatMs | Detected Music Style | Classification Reason |
|-----------|--------|-----|--------|---------------------|----------------------|
| Concerto Grosso | 스트링앙상블 | 146 | 411 | DANCE_POP | 411ms < 545ms ✓ |
| The Drum | (instrumental) | 130 | 461 | EDM | 461ms < 480ms + high periodicity + high onsets |
| Magnetic | ILLIT | 153 | 392 | DANCE_POP | Fast K-POP dance track, 392ms < 545ms |
| Good Goodbye | (sample) | 101 | 594 | POP / HIPHOP_RNB | Borderline - depends on energy/periodicity |
| 모든 날, 모든 순간 | K-POP song | 111 | 541 | DANCE_POP | 541ms < 545ms boundary, regular beat |
| 별 보러 가자 | K-POP ballad | 136 | 441 | EDM / DANCE_POP | Fast ballad misclassified? Check energy |
| Dynamite | BTS | 125 | 480 | EDM / DANCE_POP | Exactly at boundary 480ms |
| (Ballad Example) | Typical ballad | 70 | 857 | BALLAD | Slow + low energy ✓ |

**Note**: Some classifications may differ based on actual feature values during detection. The BPM-based classification above assumes standard feature distributions for each genre.

---

## Genre-Specific Expected Patterns

### K-POP Category Breakdown

#### K-POP Ballad (케이팝 발라드)
- **Typical BPM**: 60-85
- **Expected Style**: BALLAD
- **Key Features**:
  - Slow, emotional tempo
  - Low background noise
  - Vocals are primary energy source
  - Minimal percussion
  - Long sustained notes

#### K-POP Dance (케이팝 댄스곡)
- **Typical BPM**: 110-140
- **Expected Style**: DANCE_POP or EDM
- **Key Features**:
  - Fast, energetic tempo
  - Choreography-friendly beat
  - Strong bass foundation
  - Regular 4/4 or 6/8 time
  - Synchronization with visual performance

#### K-POP Mid-Tempo (케이팝 중간곡)
- **Typical BPM**: 95-110
- **Expected Style**: HIPHOP_RNB or DANCE_POP
- **Key Features**:
  - Balanced between vocals and beat
  - Emotional depth with rhythm
  - Moderate energy
  - Often features rapping sections (lower periodicity)

---

### Genre Misclassification Scenarios

#### Scenario 1: Slow Dance Song (Downtempo)
- **Actual BPM**: 90-100 (slower dance)
- **beatMs**: 600-666
- **Expected Classification**: HIPHOP_RNB or DANCE_POP (depending on periodicity)
- **Why**: beatMs too high for DANCE_POP boundary (545ms)
- **Fix**: This is correct! Downtempo is not standard dance

#### Scenario 2: Fast Ballad (with orchestral elements)
- **Actual BPM**: 100-110 (unusually fast for ballad)
- **beatMs**: 545-600
- **Expected Classification**: DANCE_POP or HIPHOP_RNB
- **Why**: BPM doesn't match typical ballad range
- **Issue**: Energy level should be low, but BPM classification takes priority
- **Note**: This is a genre boundary case - may not be a "true" ballad

#### Scenario 3: Acoustic Pop (minimal bass)
- **Actual BPM**: 110-130
- **beatMs**: 461-545
- **Expected Classification**: DANCE_POP or POP
- **Why**: If lowRatio < 0.36 AND periodicity < 0.50, falls through to POP
- **Correct**: Acoustic instruments have less bass, so classification reflects this

#### Scenario 4: Electronic Ballad (Slow Synth Music)
- **Actual BPM**: 70 (slow electronic)
- **beatMs**: 857
- **Expected Classification**: BALLAD
- **Why**: beatMs >= 700ms and likely low energy
- **Note**: Style doesn't distinguish electronic from acoustic in ballad range

---

## Feature Correlation Matrix

How different features interact in classification:

### For Fast Songs (BPM > 110)
| Feature | Impact on Classification |
|---------|------------------------|
| **periodicity** | HIGH - differentiates EDM (>0.65) from DANCE_POP (0.50-0.65) from POP (<0.50) |
| **onsetDensity** | MEDIUM - EDM requires >4.0, DANCE_POP typically 2-3.5 |
| **lowRatio** | MEDIUM - Need >= 0.36-0.38 for dance classification |
| **avgEnergy** | LOW - Most fast songs have moderate-to-high energy |

### For Slow Songs (BPM < 85)
| Feature | Impact on Classification |
|---------|------------------------|
| **avgEnergy** | CRITICAL - Must be < 0.52 for BALLAD classification |
| **lowRatio** | MEDIUM - If high, might be HIPHOP_RNB instead |
| **periodicity** | LOW - Ballads often have lower periodicity anyway |
| **highRatio** | LOW - Most ballads don't have strong high-frequency content |

---

## Debugging Classification Issues

### Issue: Song classified differently than expected

#### Check 1: Verify BPM
```
Expected BPM = 60000 / beatMs
If detected BPM is 2x or 0.5x expected → Octave error
Example: Expected 70 BPM (857ms) but detected 140 BPM (428ms)
```

#### Check 2: Compare to thresholds
Use the quick reference guide to trace through the decision tree:
1. Is beatMs >= 700ms? (BALLAD check)
2. Is beatMs < 480ms? (EDM check)
3. Is beatMs < 545ms? (DANCE_POP check)
4. Etc.

#### Check 3: Verify feature values
If song doesn't match expected style, check:
- `avgEnergy` - Is it higher/lower than expected?
- `lowRatio` - Does it have enough bass?
- `periodicity` - Is the beat consistent?
- `onsetDensity` - How many percussive hits?

#### Check 4: Window size effect
- If ballad detection seems unreliable → Verify window size is 3, not 5
- Window size 5 causes over-smoothing → BPM detection becomes inaccurate
- This is the PRIMARY REASON for ballad misclassification

---

## Summary Statistics

### Typical Feature Ranges by Style (Averaged)

```
BALLAD:
  - BPM: 60-85 (beatMs: 706-1000)
  - avgEnergy: 0.30-0.52
  - lowRatio: 0.30-0.42
  - periodicity: 0.30-0.50
  - onsetDensity: 1.0-2.5
  
HIPHOP_RNB:
  - BPM: 95-109 (beatMs: 550-632)
  - avgEnergy: 0.40-0.65
  - lowRatio: 0.38-0.50
  - periodicity: 0.40-0.62
  - onsetDensity: 2.0-3.5

DANCE_POP:
  - BPM: 110-140 (beatMs: 428-545)
  - avgEnergy: 0.45-0.75
  - lowRatio: 0.36-0.48
  - periodicity: 0.50-0.68
  - onsetDensity: 2.5-4.0

EDM:
  - BPM: 125-180 (beatMs: 333-480)
  - avgEnergy: 0.55-0.85
  - lowRatio: 0.38-0.55
  - periodicity: 0.65-0.95
  - onsetDensity: 4.0-8.0

ROCK:
  - BPM: Any (varies widely)
  - avgEnergy: 0.60-0.90
  - lowRatio: 0.25-0.45
  - highRatio: 0.28-0.50
  - periodicity: 0.40-0.70
  - energyFlux: 0.040-0.100

POP:
  - BPM: Any (70-140 common)
  - avgEnergy: 0.40-0.70
  - lowRatio: 0.30-0.45
  - periodicity: 0.35-0.65
  - onsetDensity: 1.5-4.0
```

This forms the basis for understanding why certain songs are classified as they are!
