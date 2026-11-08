# Implementation Plan: Alt/Ctrl Priority and Visibility Fixes

## Target: Owner's fork https://github.com/ovehbe/plektra
## Branch: fix/alt-ctrl-priority-and-visibility

## Three Required Fixes:

### 1. Alt Latching Bug (PRIORITY: HIGH)
**Symptom:** Owner reports "Alt is ALWAYS latching"
**Root Cause Investigation Needed:**
- Confirm actual alt_tap_latches default (appears to be false based on source)
- Test hardware down/up/hold/repeat/double-tap event sequences
- Verify preference loading and device-specific overrides

**Expected Behavior:**
- With single-tap-lock OFF (default): Tap → one-shot → clears after next key
- Double-tap within 500ms → persistent lock
- Hold+key → momentary, releases normally
- With single-tap-lock ON: Single tap locks immediately

**Test Strategy:**
- Unit tests for ModifierStateController state transitions
- Integration tests for InputEventRouter with actual event sequences
- Verify one-shot vs persistent lock distinction

### 2. Ctrl Priority Bug (PRIORITY: HIGH)
**Symptom:** Ctrl+A produces '*' instead of select-all when Alt is active
**Root Cause:** InputEventRouter checks Alt before Ctrl (lines 490-530)

**Fix:**
- Reorder event routing: Check Ctrl BEFORE Alt
- OR: When both Ctrl and Alt active, Ctrl takes precedence
- Preserve: Ctrl+Shift, unmapped Ctrl fallback, Alt-only symbols

**Test Strategy:**
- Unit tests: Ctrl+A with Alt latched should call select_all action
- Integration tests: Verify actual clipboard/selection operations
- Red baseline → Green fix

### 3. Per-App Bar Exclusion (PRIORITY: MEDIUM)
**Feature:** Add "Never show the bar" per-app selection under Visibility

**Requirements:**
- New setting: Per-app list of excluded apps
- Behavior: Selected apps completely hide bar (no residual height)
- Keyboard and text input still functional
- Reuse existing app picker patterns
- Safe empty default

**Test Strategy:**
- Unit tests for settings persistence
- Integration tests for bar visibility logic per app

## Build Configuration:
- User has: rocks.pkb.plektra 26.10/26100000 stableNoAccessibility
- Need: Test APK that can coexist or update
- Check: Signing certificate compatibility
- Flavor: stableNoAccessibility or separate debug identity

## Testing Sequence:
1. Write failing tests (RED)
2. Implement fixes
3. Verify tests pass (GREEN)
4. Build APK
5. Generate test reports and logs

## Deliverables:
- Committed code with pinned SHA
- Full JUnit XML + untruncated logs
- APK with metadata (hash, applicationId, version)
- ZIP containing: APK(s), JUnit XML, logs, findings
- PR against owner fork (no merge)
