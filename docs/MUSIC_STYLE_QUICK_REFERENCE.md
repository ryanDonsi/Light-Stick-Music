# Music Style Classification - Quick Reference

## Classification Decision Tree (by Priority)

```
┌─────────────────────────────────────────────────────────────────────┐
│ Input: BPM, Energy, Frequency Ratios, Periodicity, Onsets           │
│        (converted to: beatMs, avgEnergy, lowRatio, periodicity, etc) │
└─────────────────────────────────────────────────────────────────────┘
                             ↓
         ╔══════════════════════════════════════╗
         ║ Is beatMs >= 700ms                    ║
         ║ AND avgEnergy < 0.52f (or 0.62f)?    ║
         ║ (BPM <= 85, slow & quiet)            ║
         ╠══════════════════════════════════════╣
         ║ YES → BALLAD ✓                        ║
         ║ NO ↓                                  ║
         ╚══════════════════════════════════════╝
                             ↓
         ╔══════════════════════════════════════╗
         ║ Is beatMs < 480ms                     ║
         ║ AND lowRatio >= 0.38f (strong bass)   ║
         ║ AND periodicity >= 0.65f (regular)    ║
         ║ AND onsetDensity >= 4.0 (many hits)?  ║
         ║ (BPM > 125, electronic precision)    ║
         ╠══════════════════════════════════════╣
         ║ YES → EDM ✓                           ║
         ║ NO ↓                                  ║
         ╚══════════════════════════════════════╝
                             ↓
         ╔══════════════════════════════════════╗
         ║ Is beatMs < 545ms                     ║
         ║ AND (lowRatio >= 0.36f                ║
         ║  OR periodicity >= 0.50f)?            ║
         ║ (BPM > 110, fast & regular)          ║
         ╠══════════════════════════════════════╣
         ║ YES → DANCE_POP ✓                     ║
         ║ NO ↓                                  ║
         ╚══════════════════════════════════════╝
                             ↓
         ╔══════════════════════════════════════╗
         ║ Is beatMs in [545, 750)ms             ║
         ║ AND lowRatio >= 0.38f                 ║
         ║ AND periodicity < 0.62f?              ║
         ║ (95-110 BPM, syncopated groove)      ║
         ╠══════════════════════════════════════╣
         ║ YES → HIPHOP_RNB ✓                    ║
         ║ NO ↓                                  ║
         ╚══════════════════════════════════════╝
                             ↓
         ╔══════════════════════════════════════╗
         ║ Is highRatio >= 0.28f                 ║
         ║ AND energyFlux >= 0.040f?             ║
         ║ (High-pitched aggressive energy)      ║
         ╠══════════════════════════════════════╣
         ║ YES → ROCK ✓                          ║
         ║ NO ↓                                  ║
         ╚══════════════════════════════════════╝
                             ↓
         ╔══════════════════════════════════════╗
         ║ Default → POP ✓                       ║
         ║ (Fallback for unclassified songs)     ║
         ╚══════════════════════════════════════╝
```

---

## Quick BPM to Style Mapping

| BPM Range | beatMs Range | Primary Styles | Conditions |
|-----------|--------------|----------------|-----------|
| **60-85** | **706-1000+** | **BALLAD** | Low energy required |
| **85-110** | **545-706** | HIPHOP_RNB / POP | Strong bass needed for HIPHOP_RNB |
| **110-125** | **480-545** | DANCE_POP / EDM boundary | Onset density differentiates |
| **125-180** | **333-480** | **EDM / DANCE_POP** | High periodicity + high onsets → EDM |
| **180+** | **<333** | EDM (if periodic) | Fast beats, electronic |

---

## Feature Value Ranges

### Typical Values by Style

| Feature | BALLAD | HIPHOP_RNB | DANCE_POP | EDM | ROCK | POP |
|---------|--------|-----------|-----------|-----|------|-----|
| **BPM** | 60-85 | 95-109 | 110-140 | 125-180 | Any | Any |
| **beatMs** | 706-1000 | 550-750 | 430-545 | 333-480 | Any | Any |
| **avgEnergy** | <0.52 | 0.4-0.6 | 0.4-0.7 | 0.5-0.8 | 0.6+ | 0.3-0.7 |
| **lowRatio** | 0.3-0.4 | 0.38+ | 0.36+ | 0.38+ | <0.28 | 0.3-0.5 |
| **periodicity** | 0.3-0.5 | <0.62 | 0.5+ | 0.65+ | 0.4-0.7 | 0.4-0.6 |
| **onsetDensity** | 1-2 | 2-3 | 2-3.5 | 4+ | 2-4 | 1-3 |
| **energyFlux** | <0.03 | 0.02-0.04 | 0.02-0.05 | 0.04-0.06 | 0.04+ | 0.02-0.05 |

---

## Boundary Cases (Ambiguous)

### Songs near decision boundaries often get classified as:

1. **beatMs ≈ 545ms (110-110.5 BPM)**
   - Could be: DANCE_POP, HIPHOP_RNB, or even POP
   - **Differentiator**: lowRatio and periodicity
   - If lowRatio ≥ 0.36 OR periodicity ≥ 0.50 → DANCE_POP
   - Otherwise → May be classified as HIPHOP_RNB or POP

2. **beatMs ≈ 480ms (125 BPM)**
   - Could be: EDM or DANCE_POP
   - **Differentiator**: onsetDensity
   - If onsetDensity ≥ 4.0 AND periodicity ≥ 0.65 → EDM
   - Otherwise → DANCE_POP

3. **beatMs ≈ 700ms (85-86 BPM)**
   - Could be: BALLAD or HIPHOP_RNB
   - **Differentiator**: Energy level
   - If avgEnergy < 0.52 → BALLAD
   - If avgEnergy ≥ 0.52 AND lowRatio ≥ 0.38 → HIPHOP_RNB
   - Otherwise → POP

4. **High-frequency energy ≥ 0.28 with aggressive transitions**
   - Could be: ROCK or DANCE_POP with aggressive synths
   - **Differentiator**: energyFlux ≥ 0.040
   - If true → ROCK
   - Otherwise → Continues through tree

---

## Why Window Size 3 is Better than 5

### Smoothing Window Effect on ODF (Onset Detection Function)

The `smoothOdf()` function applies moving-average smoothing:

```
Window Size = 3:    frame[i] = (orig[i-1] + orig[i] + orig[i+1]) / 3
              → More granular, preserves fine details
              → Good for slow tempos (large beat intervals)

Window Size = 5:    frame[i] = (orig[i-2] + ... + orig[i+2]) / 5
              → More smoothed, loses fine details
              → Can blur small peaks (problematic for slow songs)
```

### Impact on Ballad Detection

**Scenario: 70 BPM Ballad (857ms beat interval)**

With **Window = 3**:
- Small variations in ODF are preserved
- True beat center peak is distinct
- Accurate beatMs detection → BALLAD classification works ✓

With **Window = 5**:
- Over-smoothing flattens ODF
- Beat center peak becomes blurred
- System detects at wrong position → BPM becomes 140 or 35 → Misclassification ✗
- Energy classification still requires low energy, so sometimes still classified BALLAD but with wrong BPM

### Mathematical Reason

For a slow tempo song with beat period T:
- Small window (3) requires only 3 samples to smooth
- Preserves peaks within the beat period
- Large window (5) requires 5 samples, may span multiple beat periods
- Averaging across beat period boundaries creates aliasing/blurring

**Recommendation**: Keep window size at **3** for accurate beat detection across all tempos.

---

## How to Verify Classifications

### Step 1: Extract Diagnostic Logs
When the app runs beat detection, it logs:
```
[MusicStyle] EDM | beatMs=400 energy=0.65 low=0.40 mid=0.35 high=0.25 cv=0.48 period=0.72 onset=4.5 flux=0.045
```

### Step 2: Parse the Log
- `beatMs` = 400ms (BPM = 150)
- `energy` = 0.65 (average energy level)
- `low` = 0.40 (low-frequency ratio)
- `period` = 0.72 (very periodic)
- `onset` = 4.5 (many onsets per second)
- `flux` = 0.045 (some aggressive transitions)

### Step 3: Verify Classification
1. Is beatMs < 480? **YES (400 < 480)** ✓
2. Is lowRatio >= 0.38? **YES (0.40 >= 0.38)** ✓
3. Is periodicity >= 0.65? **YES (0.72 >= 0.65)** ✓
4. Is onsetDensity >= 4.0? **YES (4.5 >= 4.0)** ✓
5. Result: **EDM** ✓

This classification is correct!

---

## Common Misclassifications and Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| Slow dance song classified as BALLAD | Energy threshold too loose | Verify avgEnergy < 0.52 is intended |
| Fast ballad classified as DANCE_POP | Energy too high | Recheck: should be classified based on energy first |
| EDM classified as DANCE_POP | Missing high onsets | May have low onsetDensity, that's correct |
| K-POP classified as DANCE_POP | Correct! | K-POP dance tracks ARE DANCE_POP by definition |
| Ballad with wrong BPM | Window size too large | Use window=3 for smoothOdf |

---

## For Developers: Testing Music Type Detection

```kotlin
// Example: Test a specific song
val result = BeatDetectorV3.detect("path/to/song.mp3")
val beatMs = result.beatMs
val bpm = 60000L / beatMs

// Then classify
val styleResult = MusicStyleClassifier.classify(
    lowEnv = lowEnv, midEnv = midEnv, fullEnv = fullEnv, highEnv = highEnv,
    beatMs = beatMs, beats = beats, hopMs = 50L
)

Log.d(TAG, "Style=${styleResult.style} BPM=$bpm beatMs=$beatMs " +
    "energy=${styleResult.avgEnergy} low=${styleResult.lowRatio} " +
    "period=${styleResult.periodicity} onsets=${styleResult.onsetDensity}")
```

Check the log against the decision tree above to verify correctness!
