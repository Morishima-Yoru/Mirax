# E2E Testing & Verification Guide

This guide outlines the End-to-End (E2E) testing procedures for the Mirax application using `adb` and `UiAutomator`. It replaces legacy desktop GUI automation with reliable Android app-level automation.

---

## 1. Core Toolchain & Prerequisites

To perform device-side App UI testing, we recommend setting up one of the following testing frameworks:
- **Python UIAutomator2 (Recommended for Agentic flow)**: run a script with `uv run --no-project --python 3.14 --with uiautomator2 python path/to/script.py`.
- **AndroidX UiAutomator**: `androidx.test.uiautomator:uiautomator` (for native instrumentation tests)

**Key Verification Targets**:
- **View Hierarchy (UiAutomator)**: Verifies that layout nodes (e.g., `DebugOverlayView`) are correctly mounted, visible, and possess correct boundaries on the Android device.
- **Logcat (ADB)**: Monitors realtime state transitions in the Mirax engine (codec, session, P2P network).

---

## 2. Test Environment Setup

1. **Verify ADB Connection**:
   ```pwsh
   adb devices
   ```
2. **Clear Logs and Prepare Filtering**:
   ```pwsh
   adb logcat -c
   ```
   * **Output location**: Write every experiment artifact — logcat dumps, probe logs, UI screenshots — into a single per-run directory under the OpenCode temp dir (`C:/Users/User/AppData/Local/Temp/opencode/mirax/<run-name>/`). Never scatter logs or screenshots into `scripts/`, the repo root, or the working tree.

---

## 3. End-to-End Execution Flow

### Step A: App Launch & P2P State
Instead of driving Windows, trigger the Android app's required states via App automation bindings (UIAutomator) or intent broadcasts:
```pwsh
# Launch app via ADB
adb shell monkey -p <mirax.package.name> -c android.intent.category.LAUNCHER 1
```

### Step B: Monitor Logcat Assertions
While the app is running and a connection is established, assert the following logs:
```pwsh
# Fetch logs for the last 60 seconds
adb logcat -d -t 60 -s MiraxWfdBeacon:V MiraxSession:V MiraxSink:V MiraxH264:V PictureActivity:V
```
**Assertion Criteria**:
- `MiraxWfdBeacon`: Must contain `P2P group up`
- `MiraxSession`: Must contain `resolveWfdAdvertise ok owner=HELPER`
- `MiraxSink`: Must contain `sync command ... running=true`
- `PictureActivity`: Must contain `updateDebugOverlay called, showDebugOverlay=true, showPicture=true`

---

## 4. UI Component Assertions (UiAutomator)

When the stream is active and `PictureActivity` is in the foreground, assert the UI hierarchy to ensure the Debug Overlay is visible:

### Via direct ADB Dump (Fallback)
```pwsh
adb shell "uiautomator dump /sdcard/dump.xml && cat /sdcard/dump.xml" | Select-String "bounds.*\[848"
```
*Expected*: A node containing bounds around `[848,24][1256,776]` must be present, indicating `DebugOverlayView` is successfully drawn and not hidden.

### Via Python UIAutomator2 (Preferred)
Example validation script pattern:
```python
import uiautomator2 as u2
d = u2.connect() # connects via adb
# Assert app is in foreground
assert d.app_current()['package'] == "<mirax.package.name>"
# Assert overlay text/component exists
assert d(textContains="DEBUG OVERLAY").exists
assert d(textContains="REAL-TIME PLOTS").exists
```

---

## 5. Typical Troubleshooting

| Issue | Verification | Remediation Command |
|---|---|---|
| Connection not initiated | PrivilegeProbe failed or P2P failed | `adb logcat -d -t 50 -s MiraxWfdBeacon:V PrivilegeProbe:V` |
| `PictureActivity` does not open | Missing `BringProjectionToFront` effect | Check `MiraxSession.enterPlay()` implementation |
| Overlay not updating | UI handler missing in `onResume` | Check `PictureActivity.onResume()` for `startDebugOverlayUpdates()` |
| Overlay node missing in Dump | `showDebugOverlay` state is false | Check `DebugOverlayView.visibility` and Session snapshot state |
