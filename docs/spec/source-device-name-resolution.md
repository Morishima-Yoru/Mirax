# Spec: Source Device Name Resolution for Casting Panel

## Problem Statement

When a user projects their Windows desktop onto Mirax as a second screen, opening the Casting Panel (投放控制面板) displays "來源名稱未知" (Source name unknown) instead of the actual name of the source device (e.g. "DESKTOP-ABC1234" or "My-Laptop").

Currently, the application relies on a reverse DNS lookup on the Wi-Fi Direct (P2P) IP address. A usable PTR record is not guaranteed on this network, so the lookup may return only the IP address or finish too late for its result to be accepted. Meanwhile, the peer name, when supplied by Wi-Fi Direct, is printed to logs by the shell beacon rather than forwarded to the session model. Code inspection establishes this missing data path; the device-specific failure remains to be verified on hardware.

## Solution

Capture the source computer's friendly device name directly during the Wi-Fi Direct (P2P) connection request and group formation phases in the privileged Wi-Fi Display sink beacon. Propagate the resolved name across the owner bridge transport directly into the active connection session upon initial connection setup, and eliminate the non-functional reverse DNS lookup entirely.

The Casting Panel connection summary will display the name supplied for the connected Wi-Fi Direct peer when available. Missing or ambiguous names retain the existing unknown-source fallback; name discovery must not prevent casting.

## User Stories

1. As a user projecting my Windows PC to Mirax, I want the Casting Panel to display my PC's computer name (e.g., "DESKTOP-XXXX"), so that I can easily verify which device is connected.
2. As a user with multiple computers, I want to distinguish which laptop is currently casting to my phone, so that I don't confuse multiple active devices.
3. As a user in an enterprise or home environment, I want the source name to be independent of reverse DNS, so that missing PTR records do not hide a name already supplied by Wi-Fi Direct.
4. As a user whose computer name contains spaces or special characters (e.g., "Alice's Surface"), I want the Casting Panel to show the full name intact, so that the name is clear and not truncated.
5. As a user experiencing a dropped connection or session end, I want the source device name to be reset cleanly, so that subsequent connections start with a fresh slate.
6. As a user reconnecting via a persistent Wi-Fi Direct group, I want the device name to be refreshed and accurately displayed, so that reinvocation retains complete connection summary information.
7. As a developer maintaining the session architecture, I want the source device name to be delivered with the initial connection action, so that the session does not need asynchronous DNS updates.
8. As a developer running tests in CI, I want unit tests that verify wire format parsing, session state transitions, and connection lifecycle without requiring live Wi-Fi Direct hardware or external DNS servers.

## Implementation Decisions

1. **Native Wi-Fi Direct Device Name Capture**
   - The privileged beacon module captures the peer `WifiP2pDevice.deviceName` at two key lifecycle points:
     - On inbound connection request notification (`onConnectionRequested`).
     - On group formation observed through connection-info polling, request group information (`requestGroupInfo`) before publishing the initial ready state, inspecting the group owner (if client) or uniquely identifiable client (if group owner).
   - The beacon stores this name for the lifetime of the P2P group and clears it whenever the group drops.
   - A connection-request name may be used only when its peer identity matches the current group peer. Ambiguous peers must not be guessed. Late platform callbacks must not repopulate a stopped or replaced group.

2. **Wire Protocol Encoding & Delimitation**
   - The group state wire line between the privileged process and app process is formatted as:
     `UP <sourceAddress> [OWNER]\t<deviceName>`
   - A tab character (`\t`) separates the connection header from the device name, preserving spaces and Unicode. Tabs, carriage returns, and newlines inside names are replaced with spaces before single-line transport.
   - For backwards compatibility, lines lacking a tab delimiter continue to parse normally with an empty/null device name.
   - Both existing owner transports carry this same string without an AIDL signature change. Increment the cached Shizuku service version so a later deployment uses the new producer. An already-running standalone helper must likewise be restarted with the updated code before it can supply names.

3. **Domain & Session Model Integration**
   - The group state parser produces an `Up` state carrying both `sourceAddress` and nullable `sourceDeviceName`.
   - The connection controller forwards this device name directly into `SessionAction.BeginConnectionRun(remoteHost, sourceDeviceName)`.
   - The session model assigns the device name synchronously upon receiving `BeginConnectionRun`, removing the need for a separate asynchronous lookup.

4. **Complete Elimination of Reverse DNS Mechanism**
   - All asynchronous reverse DNS lookup logic, background thread scopes, timeout constants, and lookup generation counters (`sourceNameGeneration`) are excised from the host bridge and session model.
   - `SessionAction.SetSourceDeviceName` is retained solely for testing or manual override scenarios, but is no longer relied upon by the runtime connection pipeline.

## Testing Decisions

- **Seam Strategy**:
  - Wire encoding/parsing seam: Validate real producer formatting and parsing of group state variants (`UP <IP>`, `UP <IP> OWNER`, `UP <IP>\t<Name>`, `UP <IP> OWNER\t<Name with spaces>`), including blank names and embedded protocol delimiters.
  - Session state seam: Test `MiraxSession` handling `BeginConnectionRun` with and without a source device name, confirming immediate snapshot reflection and lifecycle cleanup upon disconnection.
  - Integration seam: Test the sink connection controller translating group state into session actions without real network sockets.
  - Android platform callbacks, actual peer identity, both live owner transports, and visible panel rendering require later device verification; JVM stubs cannot establish those claims.
- **Good Test Criteria**:
  - Tests verify observable state transitions (e.g. `snapshot.sourceDeviceName`) given simulated wire inputs, without testing private coroutines or mocked platform DNS subsystems.
- **Prior Art**:
  - `P2pGroupStateTest` and `SinkConnectionWireTest` for wire protocol validation.
  - `MiraxSessionTest` for session action handling and snapshot verification.

## Out of Scope

- NetBIOS / mDNS / LLMNR broadcast queries over the Wi-Fi Direct interface.
- Manual editing or renaming of the source device name from the receiving client UI.
- Miracast over Infrastructure (MICE / MS-MICE) device name discovery.

## Further Notes

- In accordance with the domain glossary (`docs/CONTEXT.md`), when no device name is supplied by Wi-Fi Direct, the Casting Panel continues to display "來源名稱未知" (Source name unknown) rather than fabricating a name or displaying the raw IP address.
- Implementation scope is code, local JVM checks, and code review only. Device deployment and E2E execution are deferred until the user explicitly authorizes them; do not modify the shared experimental environment or build configuration.
