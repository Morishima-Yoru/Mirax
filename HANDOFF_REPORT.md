# Miracast Pairing & Trust Compliance - Handoff Report

**Date**: 2026-10-01  
**Project**: Mirax (Android Miracast Sink)  
**Target**: Z Fold 5 (Android 16, SDK 36) ↔ Windows 11 25H2 (Source via Win+K)

---

## Problem Statement

**Windows (Source) fails to complete RTSP handshake on port 7236 with Android (Sink)** after user presses "Allow" on the system pairing dialog.

**Symptom**: TCP SYN from phone (192.168.137.247) to Windows (192.168.137.1:7236) times out after 3000ms. Windows `WUDFHost.exe` is LISTENING on 7236, but no SYN/ACK is returned.

---

## Current Diagnosis (2026-10-02)

The first-click failure had two confirmed app-side issues. `P2pNetworkBinder` rejected the P2P interface until Java reported `NetworkInterface.isUp()`, even while the kernel already showed its IPv4 address. Also, the app called `startWps()` after group formation and treated its immediate `ActionListener.onSuccess()` as WPS completion.

The connected Android 16 framework shows that P2P provision discovery sets `WifiP2pConfig.wps.setup` before group creation. In `GroupCreatedState`, `startWpsPbc()` returns a native boolean that the service immediately maps to the `ActionListener` reply; it is not a supplicant WPS completion callback. The normal group-formed path no longer issues this late second WPS request; group formation now advances the app pairing state.

One fresh, OCR-confirmed first-click test after reinstall reached RTSP on attempt 1 and started playback/RTP. This is a successful run, not yet a repeatability result. `Get-NetFirewallDynamicKeywordAddress` still returned 0 after that successful TCP connection, so its output does not currently explain the observed success and must be reconciled before calling it the blocker.

---

## Connection Flow (Latest Verified Run)

```
1. Win+K OCR matched `Renathan's Z Fold5` inside the Cast device-list row; no fallback coordinates were used.
2. P2P group formed and the app transitioned `UNPAIRED` → `PAIRED`.
3. The app selected `p2p-wlan0-0` by its assigned IPv4 address and bound the socket.
4. RTSP connected to `192.168.137.1:7236` on attempt 1.
5. RTSP negotiation reached playback; RTP listening and the first RTP packet were observed.
```

---

## Key Evidence

### From Codebase (`PrimarySinkBeacon.java`):
- **UNPAIRED state skips the broadcast approver** → allows the system pairing flow
- **WPS PBC is negotiated by Android's P2P provision-discovery flow**; no extra `startWps()` call is issued after the group forms
- **`ActionListener.onSuccess()` only acknowledges the native `startWpsPbc()` request**; it is not treated as supplicant WPS completion
- **`forgetSavedGroups` defaults to `false`** → persistent groups survive normal restarts

### From Packet Capture:
- Windows `WUDFHost.exe` listens on 7236 **only after** pairing decision
- Earlier SYNs to 7236 timed out during failed first-click runs.
- In the latest successful run, RTSP connected even though `Get-NetFirewallDynamicKeywordAddress` returned zero entries; the firewall rule/keyword relationship needs re-verification.
- The broadcast approver is not registered for UNPAIRED devices.

### From dumpsys wifip2p:
```
connectionType=FRESH, wpsMethod=PBC  ← Android DOES use PBC by default!
```
Android's framework already uses WPS PBC during GO negotiation, but **only if the broadcast approver doesn't bypass it**.

---

## Implemented Fixes (Status: Partial)

### ✅ Fixed
| Fix | File | Status |
|-----|------|--------|
| WPS config methods in WFD IE | `PrimarySinkBeacon.armSink()` via `setWpsConfigMethods()` | ⚠️ The connected phone does not expose `setWpsConfigMethodsSupported`; runtime log says unavailable |
| Remove broadcast approver for UNPAIRED | `admitThenListen()` skips `armAllSourcesApprover()` | ✅ Done |
| Clear leftover approvers on startup | `removeApprovers()` in `startAdvertising()` | ✅ Done |
| Preserve persistent groups | `forgetSavedGroups = false` by default | ✅ Done |
| Use framework WPS PBC negotiation | `onConnectionInfo()` no longer issues a late `startWps()` after group formation | ✅ Implemented; one fresh first-click session reached playback |
| WPS request callback semantics | `startWpsPbc()` logs request acceptance; it no longer marks PAIRED on `ActionListener.onSuccess()` | ✅ Corrected |
| Clear approvers on startup | `removeApprovers()` in `startAdvertising()` | ✅ Done |

### ⚠️ Partially Working / Needs Verification
| Item | Issue |
|------|-------|
| Repeatability | First-click playback succeeded once after a fresh reinstall; repeat with the same OCR-only script before declaring stable |
| Persistent group identity | `dumpsys wifip2p` reports 1 group, but its peer identity/new creation is not yet attributed |
| Windows dynamic target | Cmdlet returned 0 entries despite successful RTSP; determine whether this is expected for the active rule or a trust gap |
| Supplicant WPS completion | The previous manual `startWps()` trial logged `WPS-PBC-ACTIVE`, not `WPS-SUCCESS`; native provision-discovery completion remains unverified |

---

## Current Code State (`PrimarySinkBeacon.java`)

### Key Methods Modified:
1. **`startAdvertising()`** - Clears approvers, preserves persistent groups, sets `forgetSavedGroups = false`
2. **`admitThenListen()`** - Only registers broadcast approver for `PAIRED` state
3. **`startWpsPbc(String deviceAddress)`** - Retained for the external-approver path; `onSuccess` means request accepted, not WPS completion
4. **`onConnectionInfo()`** - Uses group formation to transition pairing state; does not issue a late WPS request
5. **`armApproverForPairedDevices()`** - Keeps the broadcast approver restricted to paired reinvocation
6. **`removeApprovers()`** - Clears all approvers on startup and broadcast stop

---

## Remaining Work / Next Steps

### 1. Repeat First-Click Verification
- Reinstall before a new test run and use only OCR-confirmed `Renathan's Z Fold5` selection; if OCR does not match inside the Cast row, stop without clicking.
- Confirm first-click RTSP/PLAY succeeds across repeated runs.
- Identify which persistent group belongs to the Fold 5 rather than relying on the aggregate count.

### 2. Test Complete Flow
1. **First connection**: Framework provision discovery negotiates PBC → persistent group is verified → RTSP reaches PLAY
2. **Second connection**: No dialog → instant RTSP (persistent group reinvocation)
3. **Forget device**: `forgetAllPairings()` → next connection requires pairing again

### 3. Windows Firewall Verification
- After successful WPS: Check `Get-NetFirewallDynamicKeywordAddress` for populated `WifiDirectDisplay`
- Verify inbound 7236 allowed without manual firewall rules

### 4. Historical Failed First-Click Trace (Before Latest Fix)
- Earlier fresh Win+K attempts completed group formation but failed before RTSP; these logs predate the latest successful one-click run.
- `ip -4 addr` shows `p2p-wlan0-0` with `192.168.137.247/24` during the initial group, while `NetworkInterface.isUp` did not expose it to the app. `P2pNetworkBinder` now selects the P2P interface by its assigned IPv4 address and binds early.
- The app then attempts TCP to `192.168.137.1:7236`; the first connect times out after 3 seconds. Windows ends the P2P group about 11 seconds after formation.
- `Get-NetFirewallDynamicKeywordAddress` reports 0 entries after the first WPS attempt, so the Windows `WifiDirectDisplay` trust target is still missing.
- The `ActionListener.onSuccess()` arrives about 50-70 ms after `startWps`; this is not verified as a completed supplicant WPS exchange. System log showed `WPS-PBC-ACTIVE`, not `WPS-SUCCESS`.
- `click-fold.ps1` must only click after OCR matches the exact `Renathan ... Z Fold5` row. OCR false positives from background VS Code text have occurred; do not use a default-coordinate fallback.

---

## File Locations

| File | Purpose |
|------|---------|
| `helper/src/me/trinitrix/mirax/wfd/PrimarySinkBeacon.java` | Core pairing logic |
| `helper/src/me/trinitrix/mirax/wfd/SavedP2pGroups.java` | Persistent group filtering |
| `app/src/main/aidl/me/trinitrix/mirax/shell/IMiraxShellService.aidl` | AIDL with `forgetAllPairings()`, `getPairingState()` |
| `app/src/main/java/me/trinitrix/mirax/shell/MiraxShellUserService.kt` | AIDL implementation |
| `app/src/main/java/me/trinitrix/mirax/wfd/WfdOwnerBridge.kt` | App ↔ Shell bridge |
| `app/src/main/java/me/trinitrix/mirax/wfd/SinkConnectionController.kt` | RTSP dial & session management |

---

## Debug Commands

```bash
# Check pairing state
adb shell am broadcast -a me.trinitrix.mirax.SESSION_ACTION --es action GET_PAIRING_STATE

# Forget all pairings (reset)
adb shell am broadcast -a me.trinitrix.mirax.SESSION_ACTION --es action FORGET_PAIRINGS

# Check persistent groups
adb shell dumpsys wifip2p | grep -A5 "numPersistentGroup"

# Check Windows DynamicTarget
powershell -Command "Get-NetFirewallDynamicKeywordAddress"
```

---

## Summary

**One fresh first-click session has reached RTSP playback after the late manual WPS request was removed.** The app now binds as soon as the P2P interface has an IPv4 address, and group formation—not `ActionListener.onSuccess()`—advances pairing state. Repeatability is still unverified. The Windows dynamic keyword cmdlet returned 0 even though RTSP succeeded, so do not claim that table is populated or that it is required for the successful path until its semantics are reconciled.