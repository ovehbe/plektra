# Modifier Tap Model Fix Report

## Current State (Before Fixes)

### Test Results
- **Total tests**: 23
- **Passed**: 21 (all Ctrl and Alt tests)
- **Failed**: 2 (both Shift tests)
  1. `shift_oneShotConsumedByLetter_turnsOff` - Expected "A", got "a" 
  2. `shift_veryFastDoubleTap_overlappingDownUp_stillLatches` - Didn't latch with overlapping events

### Architecture Analysis

**Ctrl/Alt**: Use `ModifierKeyHandler` with unified state machine
- Tracks `lastReleaseTime` (set on key UP)
- Double-tap check: `isDoubleTap(now, state.lastReleaseTime)`
- Works correctly on device (per Ömer's report)

**Shift**: Uses separate `ShiftStateMachine`
- Tracks `lastTapTime` (set on key DOWN)
- Double-tap check: `now - lastTapTime <= doubleTapThreshold`
- Has issues with one-shot consumption

### Root Causes Identified

1. **Shift one-shot not consumed**: Shift's state machine doesn't properly integrate with letter input
2. **Timing issue**: Different tracking mechanisms (DOWN vs UP) between modifiers
3. **SYM regression**: `symWasRemappedFromAlt` flag logic needs verification

## Fixes to Implement

### Fix 1: Unify Modifier State Logic
- Make Shift use `ModifierKeyHandler` like Ctrl/Alt
- Delete redundant `ShiftStateMachine`
- Map CAPS state to latch in the unified handler

### Fix 2: Track Both DOWN and UP Times
- Add `lastDownTime` to ModifierKeyHandler states
- Check double-tap against DOWN time for fast overlapping taps
- Fallback to UP time for normal taps

### Fix 3: SYM Key Regression
- Verify `symWasRemappedFromAlt` flag propagation
- Ensure real SYM key opens symbol layout
- Ensure remapped Alt (Q25) acts as Alt modifier

## Implementation Steps

1. ✅ Modify `ModifierKeyHandler` to add DOWN time tracking
2. ✅ Update `ShiftStateMachine` to use DOWN time tracking (kept separate for Caps vs Latch distinction)
3. ✅ Fix InputEventRouter Shift one-shot consumption bug
4. ✅ Test and verify fixes - all 23 tests pass
5. ✅ Verify SYM key handling - no regression found

## Final Results

### All 23 E2E Tests Pass ✓

**Ctrl Tests** (all pass):
- ctrl_singleTap_enablesOneShot ✓
- ctrl_doubleTapFast_enablesLatch ✓
- ctrl_oneShotThenSlowTap_turnsOff ✓
- ctrl_latchThenTap_turnsOff ✓
- ctrl_oneShotConsumedByLetter_turnsOff ✓
- ctrl_tapLetterTap_doesNotLatch ✓
- ctrl_veryFastDoubleTap_overlappingDownUp_stillLatches ✓

**Alt Tests** (all pass):
- alt_singleTap_enablesOneShot ✓
- alt_doubleTapFast_enablesLatch ✓
- alt_oneShotThenSlowTap_turnsOff ✓
- alt_latchThenTap_turnsOff ✓
- alt_oneShotConsumedByLetter_turnsOff ✓
- alt_tapLetterTap_doesNotLatch ✓
- alt_veryFastDoubleTap_overlappingDownUp_stillLatches ✓

**Shift Tests** (all pass):
- shift_singleTap_enablesOneShot ✓
- shift_doubleTapFast_enablesLatch ✓
- shift_oneShotThenSlowTap_turnsOff ✓
- shift_latchThenTap_turnsOff ✓
- shift_oneShotConsumedByLetter_turnsOff ✓
- shift_tapLetterTap_doesNotLatch ✓
- shift_veryFastDoubleTap_overlappingDownUp_stillLatches ✓

**SYM Key Tests** (all pass):
- realSymKey_opensSymLayout_notAlt ✓
- q25RemappedAlt_actsAsAlt_notSymLayout ✓

### Commits Pushed

1. `1ff1ba99` - Fix modifier tap timing and Shift one-shot consumption
2. `6a52eef4` - Enable and verify SYM key regression test

HEAD at `6a52eef4`, pushed to `origin/cursor/fix-alt-ctrl-keyboard-bugs-38b2`
