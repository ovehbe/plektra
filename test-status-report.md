# Test Status Report

## Summary
Fixed major regressions in 3 out of 6 test classes. Remaining issues are in tests added by this branch or broken by earlier branch commits.

## Test Class Results

| Test Class | Status | Details |
|------------|--------|---------|
| **InputEventRouterModifierE2ETest** | ✅ 36/36 | All passing. Fixed Uyghur Alt punctuation and Ctrl pass-through logic. |
| **Q25AltCtrlIntegrationTest** | ✅ 8/8 | All passing. Fixed Ctrl shortcuts to take precedence over Alt mappings on BlackBerry keyboards. |
| **LanguageSwitchServiceE2ETest** | ✅ 14/14 | All passing. Fixed `altActive` to check both state machine AND event metastate. |
| PhysicalKeyboardInputMethodServiceDeviceBehaviorTest | ⚠️  85/86 | 1 failure: `altShiftLayoutSwitch_ignoresAltOneShot_whenShiftPressedLater` (failing since commit 5349d47f) |
| DoubleTapLockTimingTest | ❌ 3/5 | 2 failures: `doubleTapMustBeConsecutive_tapLetterTap_noLock`, `doubleTapLockTiming_shift_fastAndSlow` (new test added by branch) |
| AltModifierComprehensiveTest | ❌ 0/1 | 1 failure: `runAllAltModifierScenarios` (new test added by branch) |

## Key Fixes Implemented

### 1. Ctrl Shortcuts vs Alt Mappings (Q25)
- **Problem**: On Q25, physically held Ctrl+A/C/V wasn't performing shortcuts when keys had Alt mappings
- **Fix**: Added `physicalProfileOverride` parameter to detect BlackBerry keyboards and block Ctrl pass-through only when key has Alt mapping on those devices
- **Files**: `InputEventRouter.kt`, `PhysicalKeyboardInputMethodService.kt`

### 2. Alt Active Detection
- **Problem**: Router's `altActive` function wasn't checking `event.isAltPressed`, breaking Uyghur Alt punctuation tests
- **Fix**: Changed both router and service `altActive` to check BOTH state machine flags AND event metastate
- **Impact**: Fixed all language switching and Uyghur punctuation tests

### 3. Layout Switch Shortcuts
- **Problem**: Alt+Shift and Alt+Enter layout switches were incorrectly triggering on Alt one-shot
- **Fix**: Reverted to checking only physical Alt press (`event.isAltPressed || altPhysicallyPressed`) for these shortcuts
- **Files**: `PhysicalKeyboardInputMethodService.kt`

## Remaining Issues

### PhysicalKeyboardInputMethodServiceDeviceBehaviorTest (1 failure)
- Test: `altShiftLayoutSwitch_ignoresAltOneShot_whenShiftPressedLater`
- Status: Failing since commit 5349d47f "Implement principled Alt state fix"
- Expected: Shift key NOT consumed when Alt one-shot is active
- Actual: Shift key is being consumed by some code path
- Note: Alt+Shift layout switch check was already reverted but test still fails

### DoubleTapLockTimingTest (2 failures)
- New test added by this branch to verify double-tap timing requirements
- Tests verify fast double-taps (80-250ms) lock modifiers, slow ones don't
- Need to implement or fix double-tap timing logic

### AltModifierComprehensiveTest (1 failure)
- New comprehensive test added by this branch
- Single test method `runAllAltModifierScenarios` runs multiple scenarios
- Need to investigate which specific scenarios are failing

## Commits Made

1. bc128c2f - Fix altActive to always check event.isAltPressed for language switching
2. 2e25a8af - Fix Ctrl shortcuts to take precedence over Alt mappings on BlackBerry keyboards
3. Earlier commits in branch history (inherited)

## Files Changed

- `app/src/main/java/rocks/pkb/plektra/inputmethod/InputEventRouter.kt`
- `app/src/main/java/rocks/pkb/plektra/inputmethod/PhysicalKeyboardInputMethodService.kt`
- `app/src/test/java/rocks/pkb/plektra/inputmethod/Q25AltCtrlIntegrationTest.kt` (reverted to original)
