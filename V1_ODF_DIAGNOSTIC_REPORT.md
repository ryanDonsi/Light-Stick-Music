# V1 ODF Analysis & Diagnostic Report

**Date:** 2026-06-29  
**Analysis:** 43 songs tested, 40 with Madmom GT reference  
**Result:** 87.5% accuracy (35/40 correct ±1 BPM), 5 major errors

---

## Executive Summary

V1 baseline performance is **strong (87.5% accuracy)** with clear pattern in errors: **2+ songs detected at exactly 2x the correct tempo**. This indicates:

1. ✅ ODF peak detection working reasonably well
2. ✅ Autocorrelation scoring functional  
3. ⚠️ **Half-tempo detection needs tuning** - halfTempoFix not triggering for slow songs
4. ⚠️ Madmom GT reference data may have issues (verify songs like TOMBOY, 장윤정 - 초혼)

---

## Detailed Error Analysis

### Top 5 Problematic Songs

| # | Song | Detected | GT | Error | Error% | Issue Type |
|---|------|----------|----|----|--------|-------------|
| 1 | TOMBOY | 146 BPM | 74.07 BPM | +72 | 97.1% | **2X TEMPO** |
| 2 | 장윤정 - 초혼 | 142 BPM | 72.29 BPM | +70 | 96.4% | **2X TEMPO** |
| 3 | 진미령 - 미운사랑 | 139 BPM | 69.77 BPM | +69 | 99.2% | **2X TEMPO** |
| 4 | Let's go see the stars | 133 BPM | 66.67 BPM | +66 | 99.5% | **2X TEMPO** |
| 5 | 금잔디 - 오라버니 | 136 BPM | 139.53 BPM | -3.5 | 2.5% | Minor ✓ |

**Pattern:** Songs 1-4 all detected at **exactly 2x the ground truth BPM**
- TOMBOY: 146 ≈ 74.07 × 2
- 장윤정: 142 ≈ 72.29 × 2  
- 진미령: 139 ≈ 69.77 × 2
- 별 보러: 133 ≈ 66.67 × 2

### Error Classification

```
Total Errors: 5 songs
├─ 2X Tempo Errors: 2 songs (진미령, 별 보러 가자)
├─ Generic Fast Bias: 1 song (장윤정 - 초혼)
├─ Fundamental Errors: 2 songs (TOMBOY, 금잔디)
└─ Half-Tempo Errors: 0 songs
```

---

## Signal Quality Analysis

### Autocorrelation (bestAc) Statistics
- **Mean:** 0.001293
- **Range:** 0.000122 - 0.005308
- **Low AC (<0.0005):** 15 songs (35% of dataset)
- **Observation:** AC values generally very weak, indicating low rhythm signal or challenging audio

### Half-Tempo Ratio Statistics  
- **Songs with half signal:** 43/43 (100%)
- **Mean ratio:** 0.7261
- **High ratio (≥0.6):** 32 songs (74%)
- **Observation:** Strong half-tempo signals present! Why isn't halfTempoFix triggering?

### Double-Tempo Ratio Statistics
- **Songs with double signal:** 29 songs (67%)
- **Mean ratio:** 0.9755
- **Observation:** Double signals moderately strong, some songs have weak double signal (0.0)

---

## Root Cause Analysis

### Why are half-tempo songs not being detected?

Looking at the 5 error songs:
- **TOMBOY:** halfRatio=0.6426 (≥0.6 threshold), but still returns 2x
- **장윤정:** halfRatio=0.6185 (≥0.6 threshold), but still returns 2x
- **진미령:** halfRatio=0.5363 (<0.6 threshold), misses half-tempo fix
- **별 보러:** halfRatio=0.5556 (<0.6 threshold), misses half-tempo fix

**Hypothesis:** 
1. **For TOMBOY/장윤정:** halfTempoFix logic exists but may have additional conditions blocking it
2. **For 진미령/별 보러:** halfRatio threshold (0.6) is too high - these songs need detection at lower ratio

### Why does doubleRatio > 1.0 for some songs?

- 진미령: doubleRatio=1.3129 (double is STRONGER than best!)
- Let's go: doubleRatio=1.1091
- TOMBOY: doubleRatio=1.0937

This violates the assumption that `score[2*lag] < score[lag]`. The half-tempo should definitely be detected here.

---

## Diagnostic Data Required

To complete ODF analysis and identify exact causes, we need V1 diagnostic logs:

### 1. ODF_DATA Logs
```
V1 ODF_DATA: title="TOMBOY" t=0ms:0.0001;t=10ms:0.0002;...;t=410ms:0.0234;...
```
**Needed for:** Visualizing ODF curve, identifying spurious peaks at 2x positions

### 2. ODF_STATS Logs  
```
V1 ODF_STATS: title="TOMBOY" size=2273 max=0.034521 mean=0.001234
```
**Needed for:** Understanding signal-to-noise ratio across songs

### 3. AUTOCORR_ANALYSIS Logs
```
V1 AUTOCORR_ANALYSIS: title="TOMBOY" lag=20(300BPM) ac=0.0001 prior=0.5 score=0.00005; lag=37(162BPM) ac=0.0008 prior=0.95 score=0.00076; ... lag=74(81BPM) ac=0.0009 prior=0.98 score=0.00088;
```
**Needed for:** Seeing why lag=41(146BPM) wins over lag=74(81BPM)

### How to Collect

On development device:
```bash
adb logcat -s "DetectorV1" | tee v1_diagnostic_$(date +%s).log
# Run app, detect songs
# Let run for 2-3 minutes
# Ctrl+C to stop
```

Then analyze with:
```bash
grep "V1 ODF_DATA\|V1 ODF_STATS\|V1 AUTOCORR_ANALYSIS" v1_diagnostic_*.log > v1_diagnostic_filtered.log
python3 plot_v1_odf.py v1_diagnostic_filtered.log
```

---

## Next Steps & Recommendations

### Phase 1: Verify Data (Quick - 5 min)
- [ ] Verify Madmom GT data for TOMBOY, 장윤정 - 초혼
  - Are these really 74 BPM ballads, or is reference data wrong?
  - If GT is wrong, update MADMOM_GT_STANDARD.json

### Phase 2: Collect Diagnostic Logs (Medium - 10 min)
- [ ] Connect device via adb
- [ ] Run V1 detector on problematic songs: TOMBOY, 장윤정 - 초혼, 진미령, 별 보러 가자
- [ ] Capture full diagnostic logs with ODF_DATA, AUTOCORR_ANALYSIS
- [ ] Upload logs to scratchpad for analysis

### Phase 3: Visualize & Diagnose (Medium - 15 min)  
- [ ] Run plot_v1_odf.py on diagnostic logs
- [ ] Generate ODF graphs showing:
  - Time-series ODF curve
  - AC/Prior/Score across lag range
  - Mark which lag wins and why
- [ ] Identify if peak is spurious or if prior/AC is biased

### Phase 4: Implement Fix (Variable)
Depending on Phase 3 findings:

**Option A - Half-Tempo Detection Fix (if highRatio signals present)**
```kotlin
// Strengthen half-tempo detection threshold
// Current: if (halfRatio >= 0.60) trigger halfTempoFix
// Proposed: if (halfRatio >= 0.50) trigger halfTempoFix
// Rationale: Lower threshold catches more half-tempo cases
```

**Option B - Prior Distribution Adjustment (if prior is biasing toward 2x)**
```kotlin
// Current: Log-normal with σ=0.25
// Issue: May over-weight faster tempos (130 BPM) vs slower (65 BPM)
// Proposed: Adjust σ or mean to match song distribution
```

**Option C - Lag Search Range Tuning (if spurious peak at 2x)**
```kotlin
// Current: minLag=37 (600 ms), maxLag=100 (1000 ms) → 60-162 BPM
// Issue: Range may have spurious peaks at certain frequencies
// Proposed: Dynamic range based on first clear peak
```

---

## Signal Quality Insights

### Low AC Issue (35% songs < 0.0005)
- Indicates many songs have weak rhythm signals
- Could be due to:
  - ✅ Synth-heavy songs with soft percussion
  - ✅ Slow ballads with sparse rhythm
  - ⚠️ Audio preprocessing (might need tweaking)
  
**Recommendation:** Compare with songs that have high AC values to see if there's a pattern (genre, production style, etc.)

### Strong Half-Signals (100% songs have half signal)
- **Good sign:** Algorithm is finding sub-beat rhythm
- **But:** Not being utilized properly for half-tempo detection
- **Action needed:** Debug why halfTempoFix isn't firing

---

## Summary for Next Session

**V1 Status:** 
- ✅ 87.5% baseline accuracy - strong starting point
- ✅ ODF extraction working
- ✅ Autocorrelation calculation functional
- ⚠️ Half-tempo detection mechanism needs tuning
- 🔴 **Need device diagnostic logs to pinpoint exact issue**

**Immediate Action:**
Collect ODF_DATA/AUTOCORR_ANALYSIS logs from device for the 5 error songs.
This will reveal if the problem is:
1. ODF peaks (spurious 2x peak)
2. Prior distribution (over-weighting fast tempos)
3. halfTempoFix logic (not triggering when it should)
4. Search range (missing correct peak entirely)

**Time Estimate:**  
- Data collection: 10 minutes (device testing)
- Analysis: 15 minutes (graph generation + interpretation)
- Fix implementation: 30-60 minutes (depends on root cause)

---

*Report Generated: 2026-06-29*  
*Analysis Tool: analyze_v1_odf_complete.py*  
*Reference: BEAT_METRICS from logcat_raw.txt (43 songs)*
