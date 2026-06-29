# V1 Technical Summary

## Algorithm Overview

**V1 BPM Detection Algorithm:**
```
1. Audio Input → PCM samples
2. Multi-band Flux ODF → Onset Detection Function (3 frequency bands: low/mid/full)
3. Autocorrelation Analysis → Find periodic patterns in ODF
4. Log-Normal Prior Weighting → Bias toward "natural" BPM range (100-130 BPM)
5. Half-Tempo Detection → If half-lag AC > 60% of best AC, use half tempo
6. Beat Tracking → DP algorithm to find consistent beat times
```

## Current Performance (43 Songs)

| Metric | Value |
|--------|-------|
| Accuracy | 87.5% (35/40 correct) |
| Songs with error | 5 |
| Average AC value | 0.001293 |
| Songs with half signal | 43/43 (100%) |
| Mean half ratio | 0.7261 |

## Error Analysis

### 2x Tempo Errors (Detected at 2x correct BPM)

| Song | Detected | GT | Ratio | Half Ratio | Double Ratio |
|------|----------|----|----|-----------|--------------|
| 진미령 - 미운사랑 | 139 | 69.77 | 1.99 | 0.5363 | **1.3129** ⚠️ |
| Let's go see the stars | 133 | 66.67 | 1.99 | 0.5556 | **1.1091** ⚠️ |
| TOMBOY | 146 | 74.07 | 1.97 | **0.6426** ⚠️ | 1.0937 |
| 장윤정 - 초혼 | 142 | 72.29 | 1.96 | **0.6185** ⚠️ | 0.9511 |

**Key Observation:**
- Songs with doubleRatio > 1.0 mean the 2x lag has STRONGER autocorrelation than the correct lag
- Songs with halfRatio >= 0.6 should trigger halfTempoFix but aren't being caught

### Minor Error

| Song | Detected | GT | Error |
|------|----------|----|----|
| 금잔디 - 오라버니 | 136 | 139.53 | -2.5% ✓ |

## Code Structure (BeatDetectorV1.kt)

### Main Entry Point
```kotlin
fun detectPcm(
    samples: ShortArray,
    sampleRate: Int,
    durationMs: Long,
    songTitle: String? = null
): TimedBeat[]?
```

### BPM Estimation Core (estimateBpmDense)
**Location:** Line 240-336

**Key Steps:**
1. **Autocorrelation Computation** (Line 266-282)
   - For each lag (37-100 at 10ms hop = 60-162 BPM range)
   - Compute AC(lag) = mean(ODF[i] * ODF[i+lag])
   
2. **Log-Normal Prior** (Line 274-276)
   ```kotlin
   val lagMs = lag * hopMs
   val logRatio = ln(lagMs / PRIOR_CENTER_MS) / ln(2)
   val prior = exp(-0.5 * (logRatio / PRIOR_STD_OCTAVE)²)
   ```
   - Center: 500ms (120 BPM)
   - σ: 0.25 octaves
   - Peaks around 100-130 BPM

3. **Score Calculation** (Line 278)
   ```kotlin
   score[lag] = AC[lag] * prior[lag]
   ```

4. **Half-Tempo Check** (Line 301-314)
   ```kotlin
   halfRatio = AC[lag/2] / AC[lag]
   if (halfRatio >= 0.60) return halfLag  // HALF_TEMPO_RATIO = 0.60
   ```

5. **Double-Tempo Check** (Line 316-332)
   ```kotlin
   doubleRatio = AC[lag*2] / AC[lag]
   // Just logs metrics, doesn't trigger fix
   ```

## Diagnostic Logging

### Four Log Types Implemented

**1. ODF_DATA** (Line 179-183)
```
V1 ODF_DATA: title="TOMBOY" t=0ms:0.0001;t=10ms:0.0002;...;t=410ms:0.0234;...
```
- Samples ODF at ~100 intervals
- Time-series for visualization

**2. ODF_STATS** (Line 264)
```
V1 ODF_STATS: title="TOMBOY" size=2273 max=0.034521 mean=0.001234
```
- Signal quality metrics
- ODF length, peak, average

**3. AUTOCORR_ANALYSIS** (Line 285-290)
```
V1 AUTOCORR_ANALYSIS: title="TOMBOY" lag=37(162BPM) ac=0.0001 prior=0.95 score=0.00010;lag=41(146BPM) ac=0.0009 prior=0.98 score=0.00088;...;lag=74(81BPM) ac=0.0009 prior=0.98 score=0.00088;
```
- AC/Prior/Score for key lags (~16 samples across range)
- Shows why algorithm selected winning lag

**4. BPM_METRICS** (Line 328-331)
```
V1 BPM_METRICS: title="TOMBOY" bestLag=41 bestMs=410 bestBpm=146 bestAc=0.000930 halfLag=20 halfRatio=0.6426 doubleLag=82 doubleMs=820 doubleBpm=146 doubleAc=0.001018 doubleRatio=1.0937 ...
```
- Complete analysis results
- All metrics from algorithm

**5. halfTempoFix FIRED** (Line 308-311)  
```
V1 halfTempoFix FIRED: title="TOMBOY" 410ms(146BPM) → 200ms(300BPM) halfRatio=0.6426 bestAc=0.000930 halfAc=0.000587
```
- Triggered when halfRatio >= threshold
- Shows tempo correction

## Constants (BeatDetectorV1.kt)

```kotlin
private const val PRIOR_CENTER_MS = 500L    // 120 BPM preference
private const val PRIOR_STD_OCTAVE = 0.25f  // Gaussian width
private const val HALF_TEMPO_RATIO = 0.60f  // Half detection threshold
private const val MIN_BEAT_MS = 350L        // Minimum beat (171 BPM)
private const val MAX_BEAT_MS = 1000L       // Maximum beat (60 BPM)
```

## Hypothesis about Errors

### Why are 4 songs detected at 2x?

**Hypothesis 1: AC peaks at both lag and lag/2**
- If ODF has strong periodicity at slow rate (65 BPM)
- Will see peaks at both lag=74 (81 BPM via prior) AND lag=37 (162 BPM via AC)
- Prior biases toward 120 BPM middle ground, picking lag=41 (146 BPM)
- Need to see AUTOCORR_ANALYSIS to confirm

**Hypothesis 2: Prior over-weights faster tempos**
- Log-normal centered at 500ms (120 BPM)
- For slow songs (~70 BPM = 857ms), prior heavily penalizes
- Even though AC is strong at lag=74, prior*AC might be weaker than lag=41
- Need to compare prior values at different lags

**Hypothesis 3: halfTempoFix threshold too high**
- TOMBOY has halfRatio=0.6426 (just above 0.60)
- If threshold dropped to 0.50, would catch more cases
- But this is band-aid if AC is biased by prior

## Next Debug Steps

### If Device Logs Show:
1. **AC stronger at lag=74 than lag=41** 
   → Problem is PRIOR (needs adjustment)

2. **AC stronger at lag=41 than lag=74**
   → Problem is ODF (spurious peaks)

3. **AC similar at both, prior vastly different**
   → Problem is PRIOR (definitely needs adjustment)

### Tuning Options

**Option A: Lower halfRatio Threshold**
```kotlin
private const val HALF_TEMPO_RATIO = 0.50f  // Was 0.60
```
- Catches more half-tempo cases
- But doesn't fix if AC is fundamentally biased

**Option B: Adjust Log-Normal Prior**
```kotlin
private const val PRIOR_CENTER_MS = 600L    // Shift from 120 BPM to 100 BPM
private const val PRIOR_STD_OCTAVE = 0.35f  // Widen from 0.25 to 0.35 octaves
```
- Spreads probability across wider range
- Less biased toward 120-130 BPM

**Option C: Dynamic Prior Based on Audio**
- Analyze ODF energy distribution
- Estimate if song is slow or fast
- Adjust prior center accordingly

## Additional Metrics Available

From logcat_raw.txt:
- **bestPrior:** Prior value at best lag (useful for analyzing prior bias)
- **halfMs/halfBpm:** Beat interval if half-tempo selected
- **errorRate:** |doubleMs/bestMs - 1.0| * 100

## Files for Analysis

- **BeatDetectorV1.kt**: Main implementation
- **logcat_raw.txt**: 43 songs baseline metrics
- **MADMOM_GT_STANDARD.json**: Ground truth reference
- **plot_v1_odf.py**: Visualization script (when logs available)
- **analyze_v1_odf_complete.py**: Diagnostic analysis

---

**Last Updated:** 2026-06-29  
**Status:** Waiting for device diagnostic logs
