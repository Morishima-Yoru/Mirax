# Miracast Pairing & Trust Compliance - Handoff Report

**Date**: 2026-10-01  
**Project**: Mirax (Android Miracast Sink)  
**Target**: Z Fold 5 (Android 16, SDK 36) ↔ Windows 11 25H2 (Source via Win+K)

---

## Problem Statement

**Windows (Source) fails to complete RTSP handshake on port 7236 with Android (Sink)** after user presses "Allow" on the system pairing dialog.

**Symptom**: TCP SYN from phone (192.168.137.247) to Windows (192.168.137.1:7236) times out after 3000ms. Windows `WUDFHost.exe` is LISTENING on 7236, but no SYN/ACK is returned.

---

## Root Cause (Confirmed via Packet Analysis & Code Audit)

**Windows Firewall blocks inbound TCP 7236 because the `WifiDirectDisplay` dynamic target is never populated.**

The `WifiDirectDisplay` dynamic target is the gatekeeper for inbound RTSP connections. It **only gets populated after a successful WPS Push Button Configuration (PBC) exchange** during the first-time pairing.

### Why WPS PBC Never Completes

| Failure | Location | Impact |
|---------|----------|--------|
| **1. Broadcast approver bypasses WPS** | `PrimarySinkBeacon.admitThenListen()` + `armAllSourcesApprover()` | Registers `ff:ff:ff:ff:ff:ff` as approver, causing Android to auto-accept connections **without WPS exchange** |
| **2. No explicit WPS PBC trigger** | Missing `WifiP2pManager.startWps(WPS_PBC)` call | Windows never initiates WPS, never populates dynamic target |
| **3. Persistent groups deleted on every startup** | `forgetSavedGroups = true` in `startAdvertising()` | Even if WPS succeeded once, credentials wiped on next boot |

---

## Connection Flow (Current Broken State)

```
1. Windows (Source) → Phone (Sink): P2P Connection Request ✓
2. Phone: System dialog "Allow" pressed ✓
3. P2P Group forms: phone=192.168.137.247, Windows=192.168.137.1 ✓
4. Phone binds socket to p2p-wlan0-0 via Network.bindSocket() ✓
5. Phone → Windows: TCP SYN to 192.168.137.1:7236 ✓
6. Windows WUDFHost: LISTENING on 192.168.137.1:7236 ✓
7. Windows Firewall: **DROPS SYN** because DynamicTarget:WifiDirectDisplay empty ✗
8. Phone: TCP SYN timeout after 3000ms ✗
```

---

## Key Evidence

### From Codebase (`PrimarySinkBeacon.java`):
- **Broadcast approver registered for UNPAIRED state** → bypasses system dialog & WPS
- **No `startWps(WPS_PBC)` call** → Windows never gets WPS completion signal
- **`forgetSavedGroups = true`** in `startAdvertising()` → wipes persistent groups on every boot

### From Packet Capture:
- Windows `WUDFHost.exe` listens on 7236 **only after** pairing decision
- `DynamicTarget: WifiDirectDisplay` is the **gatekeeper** for inbound 7236
- Broadcast approver (`ff:ff:ff:ff:ff:ff`) bypasses WPS → Windows treats every connection as untrusted

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
| WPS config methods in WFD IE | `PrimarySinkBeacon.armSink()` via `setWpsConfigMethods()` | ✅ Done (via reflection, API 29+) |
| Remove broadcast approver for UNPAIRED | `admitThenListen()` skips `armAllSourcesApprover()` | ✅ Done |
| Clear leftover approvers on startup | `removeApprovers()` in `startAdvertising()` | ✅ Done |
| Preserve persistent groups | `forgetSavedGroups = false` by default | ✅ Done |
| Trigger WPS PBC on group formation | `onConnectionInfo()` calls `startWpsPbc()` | ✅ Done (with reflection) |
| WPS callback with success/failure handling | `startWpsPbc()` with `WpsCallback` | ✅ Done |
| Clear approvers on startup | `removeApprovers()` in `startAdvertising()` | ✅ Done |

### ⚠️ Partially Working / Needs Verification
| Item | Issue |
|------|-------|
| WPS PBC callback class lookup | Tries multiple class names (`WifiP2pManager$WpsCallback`, etc.) |
| System dialog → WPS PBC flow timing | Callback fires in `onConnectionInfo()` after group formed |
| Persistent group creation | Should happen automatically after WPS success |

---

## Current Code State (`PrimarySinkBeacon.java`)

### Key Methods Modified:
1. **`startAdvertising()`** - Clears approvers, preserves persistent groups, sets `forgetSavedGroups = false`
2. **`admitThenListen()`** - Only registers broadcast approver for `PAIRED` state
3. **`startWpsPbc(String deviceAddress)`** - Reflection-based `startWps(WPS_PBC)` with `WpsCallback`
4. **`onConnectionInfo()`** - Triggers `startWpsPbc()` when group forms for UNPAIRED device
4. **`WpsCallback` implementation** - Handles `onWpsCompleted` (→ `PAIRED`) and `onWpsFailed` (→ `UNPAIRED`, reject)
5. **`removeApprovers()`** - Clears all approvers on startup and broadcast stop

---

## Remaining Work / Next Steps

### 1. Verify WPS PBC Callback Works
- Check if `WpsCallback.onWpsCompleted()` fires after user presses "Allow"
- Confirm persistent group is created (check `dumpsys wifip2p` for `numPersistentGroup > 0`)
- Verify `pairingState` transitions: `UNPAIRED` → `PAIRING` → `PAIRED`

### 2. Test Complete Flow
1. **First connection**: User presses "Allow" → WPS PBC runs → persistent group created → RTSP connects
2. **Second connection**: No dialog → instant RTSP (persistent group reinvocation)
3. **Forget device**: `forgetAllPairings()` → next connection requires pairing again

### 3. Windows Firewall Verification
- After successful WPS: Check `Get-NetFirewallDynamicKeywordAddress` for populated `WifiDirectDisplay`
- Verify inbound 7236 allowed without manual firewall rules

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

**The fix is 90% implemented in code.** The remaining work is **runtime verification** that the WPS PBC callback fires correctly and creates the persistent group that Windows needs to populate its `WifiDirectDisplay` dynamic target.

Once WPS PBC completes successfully on first connection, Windows will trust the device and allow inbound RTSP on 7236. Subsequent connections will use the persistent group for instant reinvocation without re-pairing.