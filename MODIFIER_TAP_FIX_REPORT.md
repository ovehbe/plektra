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

1. Modify `ModifierKeyHandler` to add DOWN time tracking
2. Update `ModifierStateController` to remove `ShiftStateMachine` and use unified handler
3. Test and verify fixes
4. Update SYM key handling if needed
