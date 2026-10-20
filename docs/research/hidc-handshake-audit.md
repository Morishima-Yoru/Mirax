# HIDC Handshake Audit Report

**Status: PARTIAL AUDIT** — not a complete compliance certification. This report covers only the WFD v2.1 UIBC/HIDC handshake scope explicitly inspected. Other protocol layers (full MS-WFDPE extensions, HID parser validation, exact capture timing, M8 teardown, M9 pause, M10-M12) were not independently verified here and remain UNKNOWN.

## Subsequent device verification (2026-10-16)

On Windows 11's built-in Cast panel, the connected `TrebleDroid vanilla` source exposes **Allow mouse, keyboard, and other input from this device**. It was unchecked after each new connection. Checking it is necessary for this tested setup; UIBC TCP established and Android `wrote 20 bytes` alone did not indicate Windows accepted touch.

With that box checked, a single-touch descriptor **without** Report ID 3's Contact Count Maximum feature produced `POINTER-DOWN type=TOUCH` and `WM_TOUCH` from an Android-only `adb shell input swipe 400 640 400 640 600`. The installed build with the otherwise identical descriptor **including** Report ID 3 sent the same 20-byte HIDC reports but produced no Windows pointer events even with the box checked. Rebuilding, reinstalling, reconnecting, checking the box again, and repeating the Android-only probe with Report ID 3 removed reproduced Windows TOUCH (`touch-events-android-only-id3-removed-final-oct16.log`). This establishes the observed Windows Source behavior, not general HID compliance or multi-touch/pen support. The earlier `HidD_GetFeature(ID3)` timeout is consistent with the failure but does not independently establish the cause inside MiraDisp.

The observations and unknowns below are from the earlier audit. In particular, I-04's reference to Report ID 3 and section 4's statement that parser acceptance is unknown describe the pre-fix descriptor, not the subsequently verified single-touch build.

---

## 1. Evidence Ledger

All facts below were independently verified against the cited local source files during this audit session.

### 1.1 WFD Specification Text

| Fact | Source | Lines |
|------|--------|-------|
| WFD Source shall listen on UIBC TCP port **before** sending M4/M14 containing wfd-uibc-capability | wfd.txt | 4695-4702 |
| **Single TCP connection** per session for all UIBC data exchange | wfd.txt | 4701-4702 |
| HID report descriptor **SHOULD** be sent before HID input reports; **MAY** resend at multiple occasions | wfd.txt | 5027-5030 |
| M4 exchange may substitute for M14 to establish UIBC (no separate M14 required) | wfd.txt | 10890-10896 |
| Figure 21 permits wfd_uibc_capability and wfd_uibc_setting: enable in the same M4/M14 request | wfd.txt | 4716-4728 |
| M15 sent after successful M4/M14 exchange; **no explicit TCP-completion-before-M15 requirement located** | wfd.txt | 10898-10900 |
| Ordinary RTSP exchange timeout: **5 seconds** | wfd.txt | 10934-10936 |
| Inter-request timeout before session establishment: **6 seconds** | wfd.txt | 10938-10943 |
| Timeout violation shall **abort** RTSP and RTP sessions | wfd.txt | 10956-10957 |
| M16 keep-alive timeout: default **60 seconds** when not specified in M6 Response | wfd.txt | 10980-10983 |
| WFD Sink **shall abort** if no M16 Request received within assigned timeout | wfd.txt | 10980-10981 |
| M6 Response Session: header carries keep-alive timeout value | wfd.txt | 10963 |
| M16 Request interval shall be smaller than timeout minus 5 seconds | wfd.txt | 10971-10973 |

### 1.2 Wire Dump (Historical Text Capture)

| Observation | Source | Lines |
|-------------|--------|-------|
| Source = 192.168.137.1 (.1); Sink = 192.168.137.247 (.247) | wire-dump.txt | 1-7 |
| M1: source CSeq=1, OPTIONS | wire-dump.txt | 7-12 |
| M2: sink CSeq=1 request, OPTIONS | wire-dump.txt | 49-54 |
| M3: source GET_PARAMETER CSeq=2 | wire-dump.txt | 97-108 |
| M3 sink response: wfd_uibc_capability with input_category_list=HIDC, generic_cap_list=none, hidc_cap_list=SingleTouch/USB MultiTouch/USB, port=none | wire-dump.txt | 256 |
| M4: source SET_PARAMETER CSeq=3, wfd_uibc_capability port=50000 (capability + port, **no enable**) | wire-dump.txt | 378-400 |
| M4 sink response: RTSP/1.0 200 OK, **EMPTY body**, CSeq=3 | wire-dump.txt | 431 |
| M5: source SET_PARAMETER CSeq=4, trigger SETUP | wire-dump.txt | 447-454 |
| M6: sink SETUP request CSeq=2, Transport client_port=19000-19001 | wire-dump.txt | 491-506 |
| M7: sink PLAY CSeq=3, Session=2031740139 | wire-dump.txt | 545-558 |
| M13: sink SET_PARAMETER CSeq=4, wfd_idr_request | wire-dump.txt | 593-618 |
| M13 response: RTSP/1.0 200 OK, Session=2031740139 | wire-dump.txt | 621-631 |
| UIBC zero-payload TCP frames (no TCP flags in text; SYN not verified) | wire-dump.txt | 645-650 |
| M15: sink SET_PARAMETER CSeq=5, wfd_uibc_setting: enable | wire-dump.txt | 653-664 |
| M15 response: RTSP/1.0 200 OK, CSeq=5 | wire-dump.txt | 681-700 |
| Descriptor packet: paylen=848, HEX header 00 01 03 50 01 03 01 03 46 = common header (4) + HIDC header (5) + payload (838) + 1 pad | wire-dump.txt | 707, 723 |
| Input report packet: paylen=74, HID payload (64) + header (9) + 1 pad | wire-dump.txt | 741 |
| M16: source GET_PARAMETER CSeq=5, Session=2031740139 | wire-dump.txt | 803-818 |
| M16 sink response: RTSP/1.0 200 OK, CSeq=5, microsoft_max_bitrate: 20000000, microsoft_latency_management_capability: none | wire-dump.txt | 825-838 |

**Capture limitations:**
- Text-order only; not a timing certificate
- Duplicate observations in dump are not proof of retransmit or dedup behavior
- Current Confidence source differs from historical capture; no hash provenance available
- Other 11:36 / 12:24 sessions not independently revalidated

---

## 2. M-Level Compliance Matrix

| Message | Role | Spec Reference | Wire Observed | Code Path | Verdict |
|---------|------|----------------|---------------|-----------|---------|
| M1 OPTIONS | Source -> Sink | 6.4.1 | Yes (CSeq=1) | RtspSinkSession OPTIONS handler | Observed; timing not certified |
| M2 OPTIONS request | Sink -> Source | 6.4.2 | Yes (CSeq=1) | RtspSinkSession OPTIONS handler | Observed; timing not certified |
| M3 GET_PARAMETER | Source -> Sink | 6.4.3 | Yes (CSeq=2) | RtspSinkSession GET_PARAMETER handler (108) | Observed; not a full parameter audit |
| M3 resp + UIBC capability | Sink -> Source | 6.4.3 / 4.11 | Yes (port=none) | RtspSinkSession getParameterBody (300-334) | HIDC advertisement observed |
| M4 SET_PARAMETER (cap+port) | Source -> Sink | 6.4.4 / 6.4.14 | Yes (CSeq=3, port=50000, no enable) | RtspSinkSession applyUibc (399-411) | Establishment via M4 is permitted |
| M4 resp (empty body) | Sink | 6.4.14 / 6.4.4 | Yes (CSeq=3) | RtspSinkSession SET_PARAMETER handler (114-148) | See note on WFD 6.4.14 wording |
| M5 trigger SETUP | Source -> Sink | 6.4.5 | Yes (CSeq=4) | RtspSinkSession applyParameters trigger (384) | Observed; timing not certified |
| M6 SETUP request | Sink -> Source | 6.4.6 | Yes (CSeq=2) | RtspSinkSession SETUP trigger (119-123) | Observed; timing not certified |
| M7 PLAY | Sink -> Source | 6.4.7 | Yes (CSeq=3) | RtspSinkSession playRequest (265) | Observed; timing not certified |
| M8 TEARDOWN | Not evaluated here | 6.4.8 | **NOT in inspected trace** | RtspSinkSession TEARDOWN handler (126-129, 143-146) | UNKNOWN — not certified |
| M9 PAUSE | Not evaluated here | 6.4.9 | **NOT in inspected trace** | RtspSinkSession PAUSE handler (130-140) | UNKNOWN — not certified |
| M10-M12 | — | 6.4.10-12 | **OUTSIDE inspected touch trace** | — | UNKNOWN — not certified |
| M13 IDR request | Sink -> Source | 6.4.13 | Yes (CSeq=4) | RtspSinkSession requestIdr (152-156) | Observed; timing not certified |
| M14 (substituted by M4) | — | 6.4.14 / 10890-10896 | M4 with cap substitutes | RtspSinkSession applyUibc (399-411) | Compliant per spec substitution |
| M15 UIBC enable | Sink -> Source | 6.4.15 | Yes (CSeq=5) | RtspSinkSession requestUibcEnable (208-221) | Successful exchange precedes descriptor in text; timing not certified |
| M16 keep-alive GET_PARAMETER | Source -> Sink | 6.4.16 / 6.5.1 | Yes (CSeq=5, Session=) | RtspSinkSession GET_PARAMETER handler (108-113) | See I-01 and I-07 on timeout enforcement |

---

## 3. Findings: Code Gaps, Observations, and Risks

### I-00: Late matching responses can bypass the ordinary exchange deadline

WFD section 6.5 requires a 5-second ordinary exchange timeout and abort on violation (wfd.txt 10934-10957). RtspSinkSession.handleResponse (223-234) accepts a matching CSeq and clears the pending timer without checking its age. Timeout checks occur in request guards (418-419) and the controller's SocketTimeoutException branch (402), not before every incoming message. A matching response received after the deadline but before either check can therefore be accepted. The controller's 50 ms read timeout is an implementation polling interval, NOT an M15 protocol timeout. This is a statically confirmed enforcement gap; no historical deadline violation or causal link to missing touch has been demonstrated.

### I-01: M16 timeout not enforced (missing abort) — Code Gap

**Severity: Medium**

**What:** WFD v2.1 section 6.5.1 item 5 (wfd.txt 10980-10981) requires the WFD Sink to abort the RTSP and RTP sessions if no M16 Request is received within the assigned timeout (default 60s per 10982-10983). The full session/controller loop (SinkConnectionController.kt 380-503) contains no M16 timer, no keep-alive deadline tracking, and no abort path triggered by M16 absence.

**Evidence:**
- SinkConnectionController.kt loop 388-503: handles socket-read timeouts via SocketTimeoutException, checks session.checkExchangeTimeout() (RTSP exchange 5s), sends OPTIONS poke, writes IDR, sends M15 — but **no M16 reception timer**.
- RtspSinkSession.checkExchangeTimeout() (159-174): enforces the 5s **per-exchange** timeout only. This is unrelated to the M16 keep-alive timeout.
- Session: header (line 522 region): headerToken strips the timeout value from the Session header but does not store it for M16 enforcement.

**Impact:** M16 absence alone does not terminate the session on the mandated deadline. Other events, such as EOF, P2P loss, or an outstanding request timeout, can still terminate it.

**Status: Code-level gap confirmed. Whether this causes the observed finger-only failure is UNKNOWN.**

### I-02: Mid-session UIBC type/port changes not applied to live channel

**Severity: Medium**

**What:** RtspSinkSession.applyUibc() (399-411) updates uibcPort and uibcHidType on receipt of M4/M14. SinkConnectionController (452-468) opens the UIBC TCP connection only once (when uibcNoted transitions false -> true) and calls UibcTouchChannel.setEnabled() to propagate enable/disable state. If the source sends a mid-session M14 updating port or HID type, the existing TCP channel is NOT reopened and the new port/type are NOT applied.

**Evidence:**
- SinkConnectionController.kt 456-465: UibcTouchChannel.open() called only inside if (!uibcNoted && session.uibcPort > 0 && offer.touchEnabled).
- UibcTouchChannel.setEnabled() (71-95): only toggles enabled flag and resets state; does not change port or reopen socket.
- UibcTouchChannel.open() (38-69): creates new Channel with new hidType and enabled, but this path is only reached once.

**Impact:** If the source re-negotiates UIBC parameters mid-session (e.g., switching from SingleTouch to MultiTouch), the sink continues using the old channel. Spec section 6.4.14 (wfd.txt 10875-10882) allows mid-session M14 updates after successful exchange.

**Status: Code gap confirmed. Whether this causes the observed failure is UNKNOWN — depends on whether source sent a mid-session M14 (not visible in inspected trace).**

This does not authorize opening a second TCP connection for an arbitrary port change: section 4.11.2 requires a single connection per session. The concrete supported-type update gap must be separated from whether a proposed port update is valid at all.

### I-03: descriptorSent set before write success (bookkeeping risk)

**Severity: Low-Medium**

**What:** UibcTouchChannel.sendDescriptor() (205-225) enqueues the descriptor at line 218 and sets current.descriptorSent = true at line 219 without waiting for successful output. It then enqueues an empty input report at line 224. A write failure is logged (not silent) but does not invalidate descriptorSent. Enable or size changes can reset the flag; write failure itself does not.

**Evidence:**
- UibcTouchChannel.kt 218-219: descriptor enqueue followed by current.descriptorSent = true.
- UibcTouchChannel.kt 224: writeContacts(current, emptyList()) enqueues the initial report.
- UibcTouchChannel.kt 250-257: write catches Exception, logs warning, does not rethrow or reset descriptorSent.
- WFD spec (wfd.txt 5029): The WFD Sink **may** send HID report descriptors to the WFD Source at multiple occasions — resend is permitted.

**Impact:** Local state can claim delivery without a successful descriptor write. Windows behavior and partial-write consequences are not established by this code alone.

**Status: Bookkeeping risk confirmed at code level. NOT confirmed as wire-order violation — the descriptor is enqueued before the empty report on the same single-thread executor (line 20), so ordering is preserved when writes succeed.**

### I-04: Pen path descriptor/report mismatch

**Severity: Medium (for pen feature)**

**What:** UibcPackets.touchDescriptor() emits a HID descriptor with Report ID 1 (touch, 0x85 0x01 at line 71) and feature Report ID 3 (0x85 0x03 at line 114). UibcPackets.penReport() (171-184) emits Report ID 2 (out[0] = 2 at line 173). The channel (UibcPackets.hidcPacket at 189-205) sends under the selected hidType. There is no separate pen descriptor; the pen report ID 2 is not declared in the touch descriptor report ID list.

**Evidence:**
- UibcPackets.kt 71: 0x85 0x01 — Report ID 1 in descriptor.
- UibcPackets.kt 114: 0x85 0x03 — Report ID 3 feature in descriptor.
- UibcPackets.kt 173: out[0] = 2 — pen report uses ID 2.
- UibcTouchChannel.kt 234-238: writePen() sends hidcPacket(current.hidType, USAGE_INPUT_REPORT, report) — no descriptor association.

**Impact:** If pen input is enabled, the source HID parser may reject Report ID 2 as undeclared. This does NOT explain finger-only failure (which uses Report ID 1 correctly).

**Status: Descriptor/report mismatch confirmed at code level. Root cause for finger-only failure: NOT this issue.**

### I-05: Unexpected Microsoft capability leakage in M16 response

**Severity: Low**

**What:** RtspSinkSession GET_PARAMETER handler (108-113) responds with getParameterBody() (300-334). When capabilities.interactiveHints is true and the source did not explicitly ask for microsoft_max_bitrate or microsoft_latency_management_capability, the sink appends these fields (318-332). The observed M16 response (wire-dump 825-838) contains microsoft_max_bitrate: 20000000 and microsoft_latency_management_capability: none.

**Classification:** Unexpected capability leakage. Not proven as MUST violation from the cited M16 clause (6.5.1 does not prohibit additional parameters in M16 response).

**Evidence:**
- RtspSinkSession.kt 318-332: conditional append of Microsoft fields when interactiveHints is true.
- wire-dump.txt 836-838: observed M16 response with Microsoft fields.

**Status: Confirmed as unexpected capability leakage. NOT confirmed as spec violation.**

### I-06: Code comments contradict official Windows touchscreen documentation

**Severity: Low (documentation accuracy)**

**What:** UibcPackets.kt comments at lines 77-78 (Windows drops a contact that never sets In Range, and ignores tip-down without Confidence) and line 91 (Scan Time ... mandatory for Windows touch driver) are contradicted by the official Microsoft touchscreen HID documentation:
- Confidence is **optional**; when used for an intended touch, it stays true through lift (Tip=false).
- ScanTime is **optional** (100-microsecond units).
- ContactCount is required **regardless of tip switch**.

**Evidence:**
- UibcPackets.kt 77-78: comment claims Confidence required.
- UibcPackets.kt 91: comment claims ScanTime mandatory.
- Official doc: Touchscreen Required HID Top Level Collections — Confidence optional, ScanTime optional.

**Impact:** Misleading comments may turn unproven experiments into assumed requirements. Including these optional fields is not itself a violation; the full emitted descriptor and report semantics still require validation. The In Range claim is not independently established here.

**Status: Comment/doc mismatch confirmed. No code change required for compliance — fields are optional per official doc.**

### I-07: Session header timeout value stripped but not retained

**Severity: Medium (related to I-01)**

**What:** The Session: header in M6 Response carries the keep-alive timeout value per wfd.txt 10963. RtspSinkSession headerToken strips this value (line 522 region) but does not store it for M16 enforcement. Even if an M16 timer were added, the timeout value from the Session header is currently discarded.

**Evidence:**
- wfd.txt 10963: A WFD Source indicates the timeout value via the Session: line in the RTSP M6 Response message.
- RtspSinkSession.kt 522: headerToken strips timeout.
- No field in RtspSinkSession or SinkConnectionController stores the parsed timeout for M16 use.

**Status: Confirmed gap. Contributes to I-01.**

---

## 4. Unknowns (Not Certified)

Controller lines 431-435 flush RTSP responses before lines 452-468 propagate UIBC state and launch the asynchronous TCP connection. The connection can begin after M4; it does not inherently wait for M15. Sink-initiated M15 is attempted in the read-timeout branch (407-419), gated by PLAYING, no outstanding transaction, positive port, and not already enabled (RtspSinkSession 208-210). There is no socket-completion gate. This contradicts the method's comment, but the inspected WFD clause requires a successful M4/M14 exchange, not an explicit TCP-completion-before-M15 rule. Do not label the missing socket gate a proven normative violation. Descriptor submission itself requires a socket, enabled state, and valid picture size (UibcTouchChannel 205-218).

1. **WFD 6.4.14 M4 response wording discrepancy:** wfd.txt 10892-10894 says the M4 response includes the same wfd-uibc-capability parameter; the inspected response is empty. The adjacent text also refers to an M14 response in an M4 exchange and contains malformed wording. Record the literal parameter-echo discrepancy, but resolve it against the original PDF and applicable protocol version before classifying it as a definitive violation. **Not classified as root cause.**

2. **VHF GetFeature callbacks:** Local Windows driver contract. No evidence of Android network feature query requirement or MiraDisp internals. **Cannot assert feature works just because descriptor declares it.**

3. **MS-WFDPE latency semantics:** microsoft_latency_management_capability: none means the sink **cannot change latency**, not that latency is low. Documented latency is final RTP packet to presentation, not encoder/input end-to-end. Full MS-WFDPE extensions coverage not performed.

4. **HID parser validation:** Not performed. Whether the Windows HID parser on the specific source device accepts the descriptor as emitted (including optional fields, unit exponents, logical/physical ranges) is UNKNOWN.

5. **Exact capture timing:** Text-order dump is not a timing certificate. Actual inter-message delays, TCP retransmit behavior, and sub-millisecond ordering not verified.

6. **Other sessions (11:36, 12:24):** Not independently revalidated.

7. **Root cause of finger-only failure:** The confirmed issues above (I-01 through I-07) are code-level gaps and bookkeeping risks. None has been proven to be the direct cause of the observed finger-only failure. The failure may also involve source-side (Windows Miracast) behavior not visible in sink-side code or dump.

---

## 5. Official Sources Cited

- [Touchscreen Required HID Top Level Collections](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/touchscreen-required-hid-top-level-collections) — Confidence optional (retain true for an intended contact through Tip=false lift), ScanTime optional (100us), ContactCount required regardless of tip.
- [Virtual HID Framework (VHF)](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/virtual-hid-framework--vhf-) — VHF GetFeature local driver contract.
- [MS-WFDPE Protocol Extensions](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/8ab20e7c-3119-461f-a638-ce8659fa0703) — Microsoft latency management capability semantics.
- WFD v2.1 specification text (local): C:/Users/Leviana/AppData/Local/Temp/opencode/wfd/wfd.txt — sections 4.11.2, 6.4.14, 6.4.15, 6.5, 6.5.1.

---

## 6. Next Bounded Validation Step

**Recommended first action (offline, deterministic, no device required):**

1. **Late-response deterministic test:** Create a local test that drives RtspSinkSession with a simulated M4/M14 exchange followed by a delayed M15, verifying whether handleResponse (223) clears outstandingCseq without checking outstandingAtMs deadline. This tests whether late matched responses are accepted when the controller loop is continuously receiving data (the static path noted at SinkConnectionController 402). This is **future work, not performed in this audit**.

2. **Keepalive/M16 timer test:** Simulate a session where M16 requests are sent by the source at >60s intervals. Verify whether the sink aborts (it should per spec 6.5.1 item 5 but currently does not). This is **future work, not performed in this audit**.

3. **Packet TCP seq/flags/timestamps reconstruction:** Before any live device changes, reconstruct full TCP sequence numbers, flags, and timestamps from existing binary captures to verify actual wire ordering and timing. Only request a new capture if existing artifacts are insufficient. The current text dump is insufficient for timing certification.

---

## 7. Audit Completion Path

This report was written to:
C:/Users/Leviana/Documents/workspace/mirax-touch/docs/research/hidc-handshake-audit.md

**Method:** Independent read of all cited local source files (wfd.txt, wire-dump.txt, RtspSinkSession.kt, SinkConnectionController.kt, UibcTouchChannel.kt, UibcPackets.kt) before writing. No production code edits performed. No devices, builds, or git operations run.

**Scope reminder:** This is a partial audit of the UIBC/HIDC handshake path only. It is not a complete WFD stack compliance certification.
