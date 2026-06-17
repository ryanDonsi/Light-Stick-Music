# Music Style Classification Analysis (MusicStyleClassifier.kt)

## Overview
The `MusicStyleClassifier` uses a priority-based decision tree to classify songs into 6 music styles based on:
- **beatMs** (beat duration in milliseconds, derived from BPM: beatMs = 60000/BPM)
- **avgEnergy** (average energy level of the song)
- **lowRatio** (proportion of low-frequency energy to total)
- **periodicity** (beat regularity/consistency, 0-1 scale)
- **onsetDensity** (number of detected onsets per second)
- **energyFlux** (positive frame-to-frame energy differences, indicator of roughness)

## Classification Thresholds and Rules

### 1. BALLAD (Priority: Highest)
**When:**
- beatMs >= 700ms (BPM <= 85)
- avgEnergy < 0.52f (or < 0.62f if beatMs >= 900ms)

**Characteristics:**
- Slow tempo (60-85 BPM)
- Low energy output
- Gentle, smooth sound
- Simple beat structure

**Example Songs:**
- Ballads typically 700-1100ms beat duration
- Energy peaks are minimal and distant

---

### 2. EDM (Priority: Very High)
**When:**
- beatMs < 480ms (BPM > 125)
- AND lowRatio >= 0.38f (bass is strong)
- AND periodicity >= 0.65f (high beat consistency)
- AND onsetDensity >= 4.0 onsets/second (many sudden energy peaks)

**Characteristics:**
- Fast tempo (>125 BPM)
- Heavy bass foundation (strong low frequencies)
- Very regular beat (electronic precision)
- Lots of percussive/onset events
- High energy flux (frequent energy changes)

**Example BPM Ranges:**
- 125-180+ BPM → 480-333ms beat duration
- Electronic synths + kick drums + snares

---

### 3. DANCE_POP (Priority: High)
**When:**
- beatMs < 545ms (BPM > 110)
- AND (lowRatio >= 0.36f OR periodicity >= 0.50f)

**Characteristics:**
- Fast tempo (110-137 BPM)
- Moderate-to-strong bass (lower threshold than EDM)
- Regular beat (not as strict as EDM)
- More melody-driven than EDM
- Suitable for choreography

**Example BPM Ranges:**
- 110-136 BPM → 545-441ms beat duration
- Pop/K-POP songs with dance appeal

---

### 4. HIPHOP_RNB (Priority: Medium)
**When:**
- 545ms <= beatMs < 750ms (95 <= BPM < 110)
- AND lowRatio >= 0.38f (decent bass)
- AND periodicity < 0.62f (syncopated/less regular than dance)

**Characteristics:**
- Mid-range tempo (95-110 BPM)
- Strong bass (similar to EDM/DANCE_POP)
- Syncopated rhythm (intentionally irregular)
- Groove-oriented
- Spoken vocals or laid-back singing

**Example BPM Ranges:**
- 95-109 BPM → 750-550ms beat duration

---

### 5. ROCK (Priority: Low)
**When:**
- highRatio >= 0.28f (at least 28% high-frequency content)
- AND energyFlux >= 0.040f (aggressive energy changes)

**Characteristics:**
- High-pitched instruments (guitars, cymbals, vocals)
- Rough/aggressive energy transitions
- Can be any tempo
- Electric guitars and strong percussion

---

### 6. POP (Priority: Fallback)
**When:**
- None of the above conditions match

**Characteristics:**
- Catch-all category
- Moderate energy
- Mixed frequency content
- Mainstream commercial songs

---

## Classification Flow Diagram

```
Song → Extract BeatMs, Features
         ↓
    1. Is beatMs >= 700ms AND energy < threshold?
         ├─ YES → BALLAD ✓
         └─ NO ↓
    
    2. Is beatMs < 480ms AND bass strong AND periodicity high AND onsets > 4/sec?
         ├─ YES → EDM ✓
         └─ NO ↓
    
    3. Is beatMs < 545ms AND (bass >= 0.36 OR periodicity >= 0.50)?
         ├─ YES → DANCE_POP ✓
         └─ NO ↓
    
    4. Is 545 <= beatMs < 750 AND bass >= 0.38 AND periodicity < 0.62?
         ├─ YES → HIPHOP_RNB ✓
         └─ NO ↓
    
    5. Is highRatio >= 0.28 AND energyFlux >= 0.040?
         ├─ YES → ROCK ✓
         └─ NO ↓
    
    6. Default → POP ✓
```

---

## Key Feature Definitions

### beatMs (Beat Duration)
- Calculated from BPM: `beatMs = 60000 / BPM`
- Example: 120 BPM = 500ms beat
- Range: 280-1100ms in typical music detection

| BPM  | beatMs (ms) | Category        |
|------|-------------|-----------------|
| 60   | 1000        | Ballad          |
| 85   | 706         | Ballad edge     |
| 110  | 545         | Dance boundary  |
| 125  | 480         | EDM boundary    |
| 150  | 400         | EDM             |
| 180  | 333         | EDM fast        |

### Energy Ratios
- **lowRatio** = Bass energy / (Bass + Mid + High)
  - High > 0.38 = Bass-heavy
  - Low < 0.30 = Bass-light
  
- **periodicity** = Beat consistency score (0-1)
  - 1.0 = Perfectly regular beats
  - 0.65+ = Electronic/dance music
  - 0.50+ = Pop with good rhythm
  - <0.50 = Hip-hop/R&B with swing/syncopation

- **onsetDensity** = Percussive events per second
  - >4.0 = Lots of kicks/snares (EDM indicator)
  - 2-4.0 = Normal dance/pop
  - <2.0 = Smooth/ballad

- **energyFlux** = Aggressiveness indicator
  - >0.040 = Rough transitions (rock/metal)
  - <0.040 = Smooth transitions

---

## Analysis: Why Ballad Detection Fails with Larger Window Sizes

### Window Size Effect (smoothOdf parameter)
The `smoothOdf()` function in BeatDetectorV3 applies a moving-average smoothing to the Onset Detection Function (ODF):
- **Window size 3** (3-frame moving average): Preserves fine frequency details, better for slow tempo detection
- **Window size 5** (5-frame moving average): Over-smoothing reduces frequency resolution

### Impact on Ballads
1. **Ballads have slower tempos** (60-85 BPM → 706-1000ms beats)
2. **Small ODF window = More sensitivity to subtle variations** in slow-changing envelopes
3. **Large ODF window = Excessive smoothing** flattens the ODF, making it harder to detect the precise beat location
4. **Result with Window=5**: BPM detection becomes inaccurate, might detect octave errors (half or double the true BPM)

### Example Scenario
- **True ballad**: 70 BPM = 857ms beat
- With window=5 smoothing:
  - ODF becomes overly smooth
  - Peak detection misses the true beat center
  - System might detect 140 BPM (half beat = 428ms) or 35 BPM (double beat = 1714ms)
  - Octave gate fails to correct because ballad energy signature differs from dance

### Solution: Window Size 3
- Maintains ODF granularity
- Detects true peaks for slow tempos
- Prevents octave confusion between ballads and dance songs

---

## Example Classification Table

Based on typical songs from the test dataset:

| Song Name | BPM | beatMs | Detected Features | Expected Style | Reason |
|-----------|-----|--------|-------------------|-----------------|--------|
| Concerto Grosso | 146 | 411 | fastTempo, midBass, regular | DANCE_POP | 411ms < 545ms ✓ |
| The Drum | 130 | 461 | fastTempo, strongBass, periodic, >4 onsets | EDM | 461ms < 480ms + high periodicity + high onsets ✓ |
| Magnetic (ILLIT) | 153 | 392 | fastTempo, midBass, regular | DANCE_POP | 392ms < 545ms ✓ |
| Good Goodbye | 101 | 594 | midTempo, midBass, periodic | HIPHOP_RNB/POP | borderline case |
| 모든 날, 모든 순간 | 111 | 541 | fastTempo, midBass, moderate | DANCE_POP | 541ms < 545ms (barely!) ✓ |
| 별 보러 가자 | 136 | 441 | fastTempo, midBass, regular | DANCE_POP/EDM | depends on periodicity & onsets |
| Dynamite | 125 | 480 | fastTempo, strongBass, regular, many onsets | EDM/DANCE_POP | borderline 480ms |
| Ballad Example | 70 | 857 | slowTempo, lowEnergy | BALLAD | 857ms >= 700ms + low energy ✓ |

---

## How to Debug Music Style Detection

When a song is classified differently than expected:

1. **Check BPM accuracy first**
   - Is beatMs correct? (beatMs = 60000/BPM)
   - Verify with music player metadata
   - If BPM is off, the classification will be wrong

2. **Check Energy Threshold**
   - BALLAD requires low energy - is the song actually quiet?
   - ROCK/EDM require high energy - is there enough dynamic range?

3. **Check Frequency Content**
   - Extract lowRatio, midRatio, highRatio from envelope data
   - Bass-heavy songs → higher lowRatio → EDM/DANCE_POP
   - Guitar-heavy songs → higher highRatio → might be ROCK

4. **Check Periodicity**
   - High periodicity (>0.65) suggests electronic music
   - Low periodicity (<0.50) suggests hip-hop/organic rhythm

5. **Check Onset Density**
   - High density (>4.0) suggests many percussion hits
   - This is the key differentiator between EDM and DANCE_POP

---

## Implementation Location
- **File**: `/app/src/main/java/com/lightstick/music/domain/music/MusicStyleClassifier.kt`
- **Used in**: `AutoTimelineGeneratorBeat_v4.kt` (line 153)
- **Logging**: Each classification logs features to Android logs with tag `AppConstants.Feature.AUTO_TIMELINE`

---

## Version History
- **V3 Update** (Window Size Revert): Reverted smoothOdf window from 5 to 3 to improve BPM detection accuracy
  - Window=5 caused over-smoothing
  - Window=3 provides better frequency resolution for all tempos
  - Particularly improves ballad detection accuracy
