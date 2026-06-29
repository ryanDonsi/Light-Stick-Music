# V1 Analysis - Next Actions

## Current Status ✅
- **87.5% accuracy** (35/40 songs correct ±1 BPM)
- **Diagnostic logging implemented** in BeatDetectorV1.kt
- **Root cause identified:** Half-tempo detection needs tuning

## Problem Identified 🎯

5 songs detected at 2x the correct tempo:
1. **TOMBOY**: Detected 146 BPM → GT 74.07 BPM (halfRatio=0.6426)
2. **장윤정 - 초혼**: Detected 142 BPM → GT 72.29 BPM (halfRatio=0.6185)
3. **진미령 - 미운사랑**: Detected 139 BPM → GT 69.77 BPM (doubleRatio=1.31)
4. **Let's go see the stars**: Detected 133 BPM → GT 66.67 BPM (doubleRatio=1.11)
5. **금잔디 - 오라버니**: Detected 136 BPM → GT 139.53 BPM (minor -3.5 BPM)

## What We Need from You 📱

**Step 1: Collect Device Logs** (10 minutes)
```bash
# On device terminal or via adb
adb logcat -s "DetectorV1" | tee v1_logs_$(date +%s).log &

# Run V1 detector on problematic songs:
# - TOMBOY
# - 장윤정 - 초혼  
# - 진미령 - 미운사랑
# - Let's go see the stars (별 보러 가자)

# Let it run 2-3 minutes, then Ctrl+C
```

**Step 2: Filter & Upload** (2 minutes)
```bash
grep "V1 ODF_DATA\|V1 ODF_STATS\|V1 AUTOCORR_ANALYSIS\|V1 BPM_METRICS" v1_logs_*.log > v1_filtered.log
# Send v1_filtered.log to workspace
```

## Why These Logs Matter 🔍

The logs will show us:
1. **ODF_DATA**: Time-series curve showing where peaks are
   - Can see if there's a spurious peak at 2x position
   
2. **AUTOCORR_ANALYSIS**: AC/Prior/Score for each lag
   - Can see why lag=41(146 BPM) won over lag=74(81 BPM) for TOMBOY
   - Can see if prior distribution is biased toward faster tempos

3. **ODF_STATS**: Signal quality (max/mean ODF values)
   - Understanding if songs have weak rhythm signals

4. **BPM_METRICS**: Complete metrics logged
   - Confirms doubleRatio values

## Expected Findings & Fixes 🔧

### Scenario A: Spurious ODF Peak at 2x
- **Fix:** Adjust ODF computation or filter out spurious peaks
- **Estimated impact:** Might help 1-2 songs

### Scenario B: Half-Tempo Logic Not Firing
- **Fix:** Lower halfRatio threshold from 0.60 → 0.50
- **Why:** Songs 1-2 have halfRatio > 0.60 but halfTempoFix not triggering
- **Estimated impact:** Should fix 4 songs

### Scenario C: Prior Distribution Over-Weights Fast Tempos  
- **Fix:** Adjust log-normal prior parameters
- **Why:** Prior might be biasing toward 130-150 BPM range over 65-75 BPM
- **Estimated impact:** Could affect entire dataset

## Timeline ⏰

1. **Data Collection:** 10 min (your device)
2. **Analysis:** 15 min (Claude)
3. **Implementation:** 30-60 min (Claude, depends on root cause)
4. **Verification:** 5-10 min (re-test on device)

**Total:** ~2-2.5 hours with you actively testing

## Immediate Next Step

📝 **Do you want to:**
- A) Collect device logs now and send them
- B) First verify Madmom GT data for the 5 error songs (check if GT is accurate)
- C) Just run the fix blindly (lower halfRatio threshold from 0.6 to 0.5)
  
**Recommendation:** Option A (collect logs) - gives us definitive data for the right fix
