# Test Status Report - Modifier Double-Tap Fix

## Commit: 4cc59c94

### Test Results

#### DoubleTapLockTimingTest: 4/5 tests passing ✓
- `doubleTapLockTiming_shift_fastAndSlow`: ✅ PASS (4/4 scenarios)
- `doubleTapLockTiming_ctrl_fastAndSlow`: ✅ PASS (4/4 scenarios)
- `doubleTapLockTiming_alt_fastAndSlow`: ✅ PASS (4/4 scenarios)
- `doubleTapMustBeConsecutive_tapLetterTap_noLock`: ⚠️ 2/3 passing
  - Shift: ✅ PASS
  - Ctrl: ✅ PASS
  - Alt: ❌ FAIL (locks when it shouldn't)
- `singleTapIsOneShot`: ✅ PASS

#### AltModifierComprehensiveTest: Much improved ✓
- Fixed HELD_ONLY test implementation bug (was throwing exceptions)
- Only 1 scenario failing: ON_FIRST_LAUNCH_DOUBLE_TAP_CLEAN_RIGHT
  - Expected: LATCHED (Alt double-tap should lock)
  - Actual: NO_LATCH (Alt double-tap not working)

#### PhysicalKeyboardInputMethodServiceDeviceBehaviorTest: ❌
- `altShiftLayoutSwitch_ignoresAltOneShot_whenShiftPressedLater`: Still failing

### Root Causes and Fixes

1. **Shift timing bug** (FIXED)
   - Issue: handleShiftKeyDown used System.currentTimeMillis() instead of event time
   - Impact: All double-taps appeared instantaneous in tests
   - Fix: Added `now` parameter and pass event time using ModifierKeyHandler.eventTimeOrUptime()

2. **Threshold exclusivity** (FIXED)
   - Issue: Double-tap threshold was exclusive (<) instead of inclusive (<=)
   - Impact: 250ms wait + 50ms tap = 300ms total wouldn't lock with 300ms threshold
   - Fix: Changed to `<=` and `in 0..threshold`

3. **Test implementation bug** (FIXED)
   - Issue: AltModifierComprehensiveTest missing return statement for HELD_ONLY branch
   - Fix: Added return statement to properly handle HELD_ONLY behavior

4. **Consecutive tap logic** (FIXED)
   - Issue: Code was clearing lastReleaseTime when taps weren't consecutive
   - Fix: Removed the clearing code; now properly tracks timing separately from consecutiveness

### Remaining Issues - Alt-specific

**Alt double-tap not working in tap-letter-tap scenarios**

The issue appears to be Q25-specific Alt key handling. On Q25:
- Physical Alt arrives as KEYCODE_ALT_RIGHT
- Gets remapped to KEYCODE_SYM (with symWasRemappedFromAlt flag)
- Then normalized to KEYCODE_ALT_LEFT for modifier tracking

This normalization path may be interfering with the consecutive tap tracking or the double-tap timing logic. Both Shift and Ctrl work correctly, suggesting the issue is specific to Alt's Q25 remapping path.

**Likely cause:** The registerModifierTap() call might be happening at different points in the normalization chain, causing lastModifierKeyCode mismatches between the first and second taps.

**Needs investigation:**
1. Where registerModifierTap is called in the Alt handling path
2. Whether keyCode changes between first and second tap
3. Whether there are multiple code paths handling the same Alt key press
