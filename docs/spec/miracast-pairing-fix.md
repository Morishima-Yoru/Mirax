# Spec: Miracast Pairing & Trust Compliance for Mirax

## Problem Statement

Mirax acts as a Wi-Fi Display Primary Sink (Android phone), but Windows (Source) fails to establish a trusted pairing relationship. Every connection attempt is treated as a first-time connection, triggering Windows' firewall to block inbound TCP 7236 because the `WifiDirectDisplay` dynamic target is never populated.

Root causes identified through packet-level diagnosis:
1. **Persistent groups deleted on every startup** - `PrimarySinkBeacon.admitThenListen()` calls `requestPersistentGroupDeletion()` unconditionally, wiping all saved credentials
2. **No WPS config methods advertised** - WFD IE lacks WPS configuration methods (PBC/PIN) that Windows requires to initiate pairing
3. **Broadcast approver bypasses WPS flow** - Using `MacAddress.BROADCAST_ADDRESS` as external approver accepts connections without the WPS exchange Windows expects
4. **No persistent group creation** - Windows cannot reinvoke without a new WPS exchange because no credentials are persisted

## Solution

Make Mirax compliant with Wi-Fi Display specification for trust pairing by:
1. **Preserve persistent groups** - Only delete when explicitly requested (e.g., user "forget device"), not on every broadcast start
2. **Advertise WPS PBC support** - Include WPS config methods in WFD IE so Windows knows pairing is supported
3. **Implement proper WPS pairing flow** - Allow Windows to complete WPS Push Button Configuration, creating persistent group credentials
4. **Register device with Windows Device Association Framework** - Ensure the peer MAC gets added to `WifiDirectDisplay` dynamic target

## User Stories

1. As a Mirax user, I want Windows to remember my phone as a trusted display device, so that subsequent connections don't require re-pairing
2. As a Mirax user, I want the first connection to complete WPS pairing automatically, so that Windows firewall allows the RTSP connection
3. As a Mirax user, I want to see "Renathan's Z Fold5" in Windows Connect panel with a "Paired" indicator, so that I know trust is established
4. As a Mirax user, I want to optionally forget a paired device, so that I can reset the trust relationship
5. As a developer, I want the pairing logic to be testable without physical Windows hardware, so that CI can verify compliance

## Implementation Decisions

### 1. Persistent Group Management (`PrimarySinkBeacon.java`)

**Current behavior**: `forgetSavedGroups = true` by default, `admitThenListen()` always calls `requestPersistentGroupDeletion()`

**New behavior**:
- Add `forgetSavedGroups` configuration flag (default `false`)
- Only delete persistent groups when explicitly requested via new API `forgetAllPairings()`
- On first run with no persistent groups, allow Windows to create one via WPS
- On subsequent runs, reuse existing persistent group for instant reconnection

**Key changes**:
- `admitThenListen()` → skip deletion when `forgetSavedGroups == false`
- New public method `forgetAllPairings()` for user-initiated reset
- Persistent group IDs tracked in `SavedP2pGroups` (already exists)

### 2. WPS Config Methods in WFD IE (`PrimarySinkBeacon.java`)

**Current behavior**: `WifiP2pWfdInfo` only sets device type, control port, throughput

**New behavior**:
- Set WPS config methods to advertise Push Button Configuration (PBC) support
- Use reflection to call `WifiP2pWfdInfo.setWpsConfigMethodsSupported(int)` with `WPS_CONFIG_PUSH_BUTTON` (0x0080)
- Also support Keypad/Display (0x0008) for PIN fallback

**Key changes**:
- In `armSink()`, after creating `WifiP2pWfdInfo`, add WPS config methods via reflection
- Handle API level differences (method added in API 29+)

### 3. External Approver Strategy (`PrimarySinkBeacon.java`)

**Current behavior**: Always registers `MacAddress.BROADCAST_ADDRESS` as approver

**New behavior**:
- Register broadcast approver ONLY when no persistent group exists (first-time pairing mode)
- When persistent group exists, register the specific source MAC as approver (allow reinvocation)
- On `CONNECTION_REQUEST_ACCEPT`, if it's a new source, trigger WPS PBC flow

**Key changes**:
- `armAllSourcesApprover()` → check persistent group state before registering broadcast
- New `armSpecificApprover(MacAddress)` for reinvocation
- Track pairing state: `UNPAIRED`, `PAIRING_IN_PROGRESS`, `PAIRED`

### 4. WPS Pairing Flow Integration

**Current behavior**: Auto-accepts all connection requests via `CONNECTION_REQUEST_ACCEPT`

**New behavior**:
- For new sources: Accept connection request, then initiate WPS PBC
- Use `WifiP2pManager.startWps()` with `WPS_PBC` config method
- On WPS success (`onWpsCompleted`), persistent group is created by framework
- On WPS failure/timeout: Reject connection, clean up

**Key changes**:
- Add `WpsCallback` implementation to handle `onWpsCompleted`, `onWpsFailed`
- In `onConnectionRequest()`, if source not in persistent groups → start WPS
- Track pairing timeout (120s default)

### 5. Windows Device Association Notification

**Current behavior**: No explicit notification to Windows DAF

**New behavior**:
- After WPS success and persistent group creation, the framework should automatically notify DAF
- Ensure device name and MAC are properly registered in Windows device container
- This happens automatically when WPS completes successfully on Android 13+

### 6. Configuration & User Control

**New APIs in `WfdOwnerBridge.kt` / `SessionHost.kt`**:
- `forgetAllPairings()` - User action to reset trust
- `getPairingState()` - Returns `UNPAIRED`, `PAIRING`, `PAIRED`
- `getPairedDevices()` - List of saved persistent group MACs

## Testing Decisions

### Unit Tests
- `SavedP2pGroupsTest` - Verify persistent network ID filtering (already exists)
- `PrimarySinkBeaconPairingTest` - Mock `WifiP2pManager` to verify:
  - WPS config methods set in WFD IE
  - Persistent group deletion only when requested
  - Approver registration logic based on pairing state
  - WPS flow initiation on new connection

### Integration Tests
- `SinkConnectionWireTest` - Verify RTSP connection succeeds after pairing
- Test with Windows Simulator (if available) or document manual test procedure

### Manual Verification Checklist
1. First connection: Windows shows pairing prompt → accept → RTSP connects
2. Second connection: No prompt → instant RTSP connection
3. "Forget device" in app → next connection requires pairing again
4. Windows Connect panel shows "Paired" status

## Out of Scope

- WPA3-SAE support (requires Android 14+ and Windows 11 22H2+)
- PIN-based pairing (PBC is primary for Miracast)
- Multiple simultaneous paired sources (single Primary Sink)
- Cross-user pairing (Android work profile / multi-user)
- Removed standalone Miracast Receiver prototype; its Shizuku-only path is historical and out of scope

## Further Notes

### ADR References
- ADR 0001: Standalone Sink architecture (Shizuku user-service owns WFD)
- ADR 0002: Visible Picture Axes (not directly related)
- ADR 0003: Picture Scale (not directly related)

### Windows Version Compatibility
- Windows 10 1809+: Basic Miracast with WPS PBC
- Windows 11 22H2+: Enhanced pairing UI, WPA3 support
- Enterprise/Managed devices: May require Group Policy for wireless display

### Android Version Compatibility
- API 29 (Android 10): `WifiP2pManager.startWps()`, `addExternalApprover`
- API 33 (Android 13): `MacAddress.BROADCAST_ADDRESS` fallback approver
- API 34+ (Android 14): Enhanced WPS callbacks

### Key Files to Modify
1. `helper/src/me/trinitrix/mirax/wfd/PrimarySinkBeacon.java` - Core pairing logic
2. `helper/src/me/trinitrix/mirax/wfd/SavedP2pGroups.java` - Add utility methods
3. `app/src/main/java/me/trinitrix/mirax/wfd/WfdOwnerBridge.kt` - Expose pairing APIs
4. `app/src/main/java/me/trinitrix/mirax/SessionHost.kt` - User-facing pairing controls

### Prototype Evidence
The packet capture analysis proved:
- Windows `WUDFHost.exe` listens on 7236 only AFTER pairing decision
- `DynamicTarget: WifiDirectDisplay` is the gatekeeper for inbound 7236
- WPS PBC completion is what triggers Windows to populate this dynamic target
- Broadcast approver (`ff:ff:ff:ff:ff:ff`) bypasses WPS, so Windows never marks device as trusted