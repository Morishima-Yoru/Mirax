# Spec: Microsoft Video Formats (MS-WFDPE 3:2) Support for Mirax

## Problem Statement

Windows sources without custom-format support cannot negotiate the native Galaxy Z Fold 5 resolution (`2176×1812`) through `microsoft_custom_video_formats`. Before this feature Mirax advertised `microsoft_video_formats: 000000000000`, so users could not enable Microsoft's fixed high-resolution 3:2 modes as another compatibility option. Windows could instead choose an offered CEA/VESA/HH mode; this was not necessarily limited to 1080p or 720p. Windows 10 does not gain custom-format support by installing a Windows 11 update.

## Solution

Implement `microsoft_video_formats` (the 21 pre-defined 3:2 modes in MS-WFDPE §2.7.1.2) as an opt-in high-resolution compatibility tier. The model retains all 21 bitmap indices; only 15 modes fit the current static receiver limits:
1. Provide user-selectable 3:2 resolution options in video settings.
2. Dynamically compute and advertise enabled 3:2 resolutions in RTSP M3 `microsoft_video_formats` responses, bounded by hardware decoder limits (excluding 4500×3000).
3. Honor Windows M4 `SET_PARAMETER` requests selecting `microsoft_video_formats`, enforcing negotiation precedence: `microsoft_custom_video_formats` > `microsoft_video_formats` > standard bitmap formats.
4. Support 3:2 modes in EDID Detailed Timing Descriptor (DTD) generation when selected as preferred mode.

## User Stories

1. As a Mirax user with a Surface laptop on Windows 10/11, I want Mirax to support 3:2 resolutions such as 2160×1440 and 2256×1504, so that my desktop displays sharp text and native aspect ratio on my phone without heavy letterboxing.
2. As a Mirax user connecting from a Windows host without custom-format support, I want to offer high-resolution 3:2 modes alongside standard formats, so that Windows has additional compatible choices.
3. As a Mirax user on a compatible Windows 11 host, I want native custom formats to retain preference over Microsoft fixed modes, so that enabling compatibility options does not displace the native picture preference.
4. As a Mirax user, I want all Microsoft 3:2 modes to be unselected by default on clean installation, so that initial advertisement sets remain compact and predictable.
5. As a Mirax user, I want to freely toggle individual 3:2 resolutions in picture settings, so that only resolutions compatible with my workflow are advertised to Windows.
6. As a Mirax user, I want my selected 3:2 resolutions to persist across app restarts, so that I do not need to reconfigure them every session.
7. As a Mirax user, I want modes exceeding the static receiver limits to be excluded, so that known unsupported frame sizes and macroblock rates are not advertised.
8. As a Mirax user, I want an enabled 3:2 preferred mode to be represented in EDID, so that Windows receives a matching preferred timing hint.
9. As a Mirax user, I want the in-stream debug overlay to correctly display the active negotiated 3:2 resolution, so that I can easily verify active stream properties.
10. As a Mirax user, I want touch and pen inputs to map accurately to the 3:2 viewport, so that interactive input remains aligned whether viewing in 等比 or 鋪滿 mode.
11. As a Mirax user, I want aspect-ratio scaling modes (等比, 鋪滿, 拉伸, 原寸) to apply cleanly to 3:2 streams, so that I control how non-native aspect ratios fit the display panel.
12. As a developer, I want Microsoft 3:2 modes to be modeled separately from standard CEA/VESA/HH modes, so that standard WFD bitmap encoding is protected from out-of-range mode pollution.
13. As a developer, I want M3 capability generation and M4 parameter parsing to be testable via unit tests at the RTSP session layer, so that protocol compliance can be verified without requiring physical Windows hardware.
14. As a developer, I want malformed, ambiguous, reserved, unsupported, or unoffered M4 Microsoft selections to be ignored with a warning, so that another valid format in the request can be used without inventing a source selection.
15. As a developer, I want explicit logging for 3:2 format negotiation events, so that field connection issues can be diagnosed easily from diagnostic logs.

## Implementation Decisions

### 1. Dedicated Domain Model for Microsoft Video Formats
A dedicated domain model handles the 21 entries specified in MS-WFDPE §2.7.1.2.1, mapping indices 0–20 to width, height, and refresh. Each size uses p30, p60, then p24 bit order. Standard CEA/VESA/HH catalogs remain separate. Picture settings add a dedicated Microsoft 3:2 group using the existing size/refresh controls, selection counts, and individual/all-refresh toggles. All Microsoft rows are initially unchecked.

### 2. Negotiation Precedence Hierarchy
Within each `SET_PARAMETER` request, valid format selection evaluates independently of parameter order, in strict priority:
1. `microsoft_custom_video_formats` (literal width, height, refresh)
2. `microsoft_video_formats` (single-bit index from MS 3:2 table)
3. `wfdx_video_formats` / `wfd_video_formats` (CEA/VESA/HH bitmaps)

Microsoft selections must contain exactly twelve hexadecimal digits and exactly one defined, supported, offered bit. Zero, multiple bits, reserved bits, invalid hex, and wrong field lengths are rejected with a warning. Without another valid format, the current selection remains unchanged. Priority does not latch across requests, allowing later format changes. Windows still chooses the transmitted mode in M4: this hierarchy interprets a received request and is not a guarantee of the source's choice. Microsoft bits supplement H.264 capabilities rather than supplying a separate codec/profile/level.

### 3. Decoder Hardware Constraint Bounds
Use the existing H.264 Level 5.1 validator: at most 36,864 macroblocks/frame and 983,040 macroblocks/second, with each dimension rounded up to 16-pixel macroblocks. Also require width ≤ 4096. This excludes 2736×1824@60, 3000×2000@60, 3240×2160@60, and all three 4500×3000 modes. The remaining 15 modes include 3000×2000 and 3240×2160 at 24/30 Hz, not 60 Hz. These are static compatibility bounds, not a verified MediaCodec hardware guarantee. Keep the existing bitrate setting and standard-catalog estimation policy; do not infer the Microsoft modes' required bitrate solely from pixel count.

### 4. Dynamic RTSP M3 Bitmap Generation
The 12-digit hex string is generated from the enabled connection offer intersected with supported Microsoft modes. With no enabled mode, return `000000000000`; bits 21–47 remain clear. Fixed Microsoft modes are not duplicated into the literal custom-format parameter, so they cannot displace native custom modes there. Filter unsupported Microsoft modes even when restored through preferred/custom settings. The existing interactive refresh workaround must not synthesize an unchecked Microsoft sibling refresh.

First-use automatic wm-size provisioning may prefer a fixed Microsoft mode only when it is already in the settings-owned offer. Otherwise consume that one-time read without enabling the mode or a sibling refresh; explicit user selections and ordinary Fold/custom provisioning keep their existing behavior.

Prepare interactive refresh alternatives inside capability assembly so production and RTSP tests use the same offer. A standard mode may gain a lower refresh only when that alternative is also in the standard table: do not invent VGA@30 or PAL@30 literal modes that displace the user's Microsoft EDID preference. Explicit user-defined custom modes, including custom VGA refreshes and the Fold low-latency alternative, remain valid.

### 5. EDID Detailed Timing Descriptor Integration
When an offered 3:2 mode is preferred, reuse the existing EDID builder to emit one 128-byte block with matching DTD dimensions, timing, and checksum. The monitor range descriptor must include that timing, including 24 Hz and lower horizontal frequencies. Without an explicit preferred mode, literal custom modes remain ahead of Microsoft modes as EDID candidates. EDID is a hint, not an instruction Windows must obey.

### 6. Preferences Persistence Reuse
Enabled Microsoft modes are persisted and retrieved using the existing `Set<VideoMode>` string serialization format (`<width>x<height>@<refresh>`), avoiding configuration schema migrations.

Retain existing preference keys and saved choices; filtering an unsupported mode must not erase it from stored settings. Settings edits apply to the next connection, not to the already frozen connection offer. Continue to use the selected `VideoMode` for picture scaling, diagnostics, and input coordinates without protocol-specific rendering changes.

### 7. Custom Refresh Range
Custom mode input accepts every integer refresh from 24 through 240 Hz inclusive, rather than snapping to the former 24/25/30/50/60 set. Preferred-mode text rounds decimals to the nearest integer with ties upward; positive out-of-range values clamp to 24 or 240. Preserve existing invalid-input and blank-input behavior. The custom-resolution UI remains integer-only and shows the range in English and Traditional Chinese.

Keep H.264 Level 5.1 correction: shrink dimensions along the current aspect ratio when necessary, retaining the corrected refresh. Do not change fixed CEA/VESA/HH or Microsoft bitmap tables. Retain existing interactive 30 Hz alternatives and EDID preference, including the Microsoft/standard exceptions above. Existing settings keys and export/restore retain accepted custom Hz without a migration. M3 literal custom fields encode 120 Hz as `0078` and 240 Hz as `00F0`; M4 must preserve either selected rate. This input/negotiation range is not a physical source, decoder, or display throughput guarantee.

## Testing Decisions

### Test Characteristics
Tests must exercise external behavior through existing component boundaries, focusing on wire-format input/output rather than private internal state.

### Modules Under Test
- **RTSP session public boundary**: Feed raw M3/M4 messages to `RtspSinkSession.handle()`. Assert emitted hex, all 15 allowed index mappings, excluded/unoffered/reserved/ambiguous selections, request-order independence, subsequent format changes, and the EDID bytes including checksum and timing range. Custom-refresh regression covers 120/240 Hz M3 hex and M4 selection while retaining the interactive 30 Hz EDID preference.
- **Product session public boundary**: Feed settings and actions to `MiraxSession`; inspect snapshots and exported settings. Assert the separate opt-in catalog, individual/bulk selection, restored settings, unsupported-mode filtering without data loss, frozen connection offers, and selected resolution reporting. Custom-refresh regression covers all 217 integer rates, decimal rounding, endpoint clamping, invalid inputs, settings export/restore, and Level 5.1 aspect-preserving shrink without lowering Hz.
- Reuse existing tests for rendering and input regressions. Settings export/restore does not prove Android disk persistence, and host tests do not prove physical source selection, decoder playback, or rendered UI layout.

### Prior Art
- `WfdProtocolTest.kt`: Standard protocol tests exercising `RtspSinkSession.handle()` across raw M3/M4 messages.
- `SinkConnectionWireTest.kt`: Wire-level integration tests for capability negotiation.
- `MiraxSessionTest.kt`: Existing public-action/snapshot/settings behavior tests.

## Out of Scope
- Support for H.265 / HEVC codec profiles in `microsoft_video_formats`.
- Dynamic runtime querying of Android `MediaCodecList` during M3 generation (static hardware boundary limits are used).
- UI changes outside the Picture settings resolution picker.
- Modifications to UIBC touch or pen transmission protocols.
- A full compliance rewrite of the pre-existing standard/extended WFD codecs.

## Further Notes
- MS-WFDPE §2.7.1.2 defines indices 0 to 20 for resolutions ranging from 1920×1280 to 4500×3000 across 24, 30, and 60 Hz.
- Bits 21 to 47 are reserved by Microsoft and must be masked to zero in all transmitted bitmaps.
- Source references: [Microsoft parameter](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/2d701496-f94c-402a-aa10-c61bb7e74949), [bitmap table](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/e03b7049-d663-460f-bb15-82276f6ca476), [product behavior](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/6b66a34f-8b8b-461d-8261-6f285248c3b7).
- This is a local implementation spec, not a published GitHub issue or an applied tracker label.

## Device Evidence — 2026-10-24

The user authorized a different phone, `RFCW91N2T2T` (Samsung SM-F946U,
Android 16/API 36). No commands targeted the occupied phone. The corrected
implementation is commit `e4ddf75`; its deployed APK was pulled back and matched
SHA256 `CF587735D9C9C7AC4E8821AC84E4C9A19CE589E7E66EBF44975603377B223D31`.

- Real settings UI passed Microsoft opt-in, individual/all-refresh toggles,
  disk persistence, and process-restart restoration. Shizuku/shell WFD listening
  and real floating-ball display, drag/snap, and return also passed.
- With only `2160x1440@30` and standard `640x480@60` enabled, no custom modes,
  and automatic wm-size injection disabled, Windows source
  `MSMiracastSource/10.00.26100.6584` selected
  `microsoft_video_formats: 000000000008` in M4. M3 EDID advertised a 2160×1440
  DTD, and `c2.qti.avc.decoder` decoded the real Windows desktop at that size and
  refresh. Sampled counters advanced with zero pending frames and roughly
  27–29 FPS; this is not a throughput benchmark.
- Fit, Crop, Stretch, and 1:1 were checked during that Microsoft-mode stream.
  A foreground/hit-test-gated Windows probe received four actual `PT_TOUCH`
  downs and a drag from Android-injected gestures. Coordinate error against
  the measured Fit surface was at most one picture pixel per axis in four samples.
  The diagnostic probe selected the receiver monitor with per-monitor DPI
  awareness; early timing/readiness-rejected attempts are not input-loss proof.
- Host verification after the fix: 19 suites, 296 tests, no failures/errors or
  skipped tests; Lint has zero errors and 108 existing warnings; Debug APK
  assembly passed. An initial UIBC loopback timeout passed a focused and full
  rerun. A separately interrupted Lint run passed when rerun with `--no-daemon`.
- Original settings, one-minute broadcast timeout, overlay permission app-op,
  and Windows input consent were restored. Projection and advertising stopped;
  the corrected APK and real connection history remain on the new phone.

Raw evidence is retained outside Git under
`C:/Users/User/AppData/Local/Temp/opencode/mirax/20261024-newphone-RFCW91N2T2T/`.
This verifies one Microsoft mode on one source/device combination, not all 15
selectable modes, every OEM/root path, custom-first source selection, or
physical multitouch/S Pen pressure, buttons, and coordinate calibration.

## Integration Readiness — 2026-10-24

The feature branch incorporates `origin/dev` at
`d882aaec1f74c2211ea15becf1b4cf7c12c922d4` through merge commit `fb25cdf`.
The verified implementation candidate is `5d07ddd`, which also fixes the
first-use provisioning opt-in bypass found during specification review.
Conflict resolution preserves upstream source-name lifecycle checks and
approver rearming, including the announcement guard on listening failures.
The Microsoft decision is now [ADR 0007](../adr/0007-microsoft-video-formats-support.md);
upstream's projection-control ADR 0006 keeps its existing identity.

The integrated candidate passed 20 host test suites / 312 tests with zero
failures, errors, or skips, Debug Lint with zero errors and 108 existing
warnings, Debug and Release APK assembly, and instrumentation APK assembly.
The independent helper javac/d8 build also passed. Instrumentation assembly
does not mean those tests were run on a device. Release signing still uses
the documented debug keystore for sideload/testing, not a production key.

Three additional provisioning regressions cover both picture orientations,
an explicitly enabled mode, and an unchecked sibling refresh. The new
negative cases failed before the fix, then the full session suite and final
verification command passed:

```sh
ANDROID_HOME='C:/Users/User/AppData/Local/Android/Sdk' ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon --console=plain --stacktrace
```

Candidate artifacts:

| Artifact | SHA256 |
| --- | --- |
| Debug APK | `0B800C2BE8F174DAB8D11B3B8BA6B7E33E2CFC48C8C2000D0CA791944DED7F27` |
| Release APK | `68690EF06A1AD49256D83F769CFEBAE01C51BB0D973863F84120C5662C8B8ED5` |
| Standalone helper | `931D3E227FE8233E00F5BC241413A6E2C909BCD4C0601CB771CA92423DBBE4AA` |

Evidence is retained under
`C:/Users/User/AppData/Local/Temp/opencode/mirax/20261024-merge-ready-fb25cdf/`.
The earlier hardware run certifies the archived `e4ddf75` APK only; the
integrated APK has not been redeployed or hardware-tested in this closeout.
Remote CI, branch-protection approval, push, and merging into `dev` are not
performed by these local readiness checks.
