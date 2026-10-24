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

Prepare interactive refresh alternatives inside capability assembly so production and RTSP tests use the same offer. A standard mode may gain a lower refresh only when that alternative is also in the standard table: do not invent VGA@30 or PAL@30 literal modes that displace the user's Microsoft EDID preference. Explicit user-defined custom modes, including custom VGA refreshes and the Fold low-latency alternative, remain valid.

### 5. EDID Detailed Timing Descriptor Integration
When an offered 3:2 mode is preferred, reuse the existing EDID builder to emit one 128-byte block with matching DTD dimensions, timing, and checksum. The monitor range descriptor must include that timing, including 24 Hz and lower horizontal frequencies. Without an explicit preferred mode, literal custom modes remain ahead of Microsoft modes as EDID candidates. EDID is a hint, not an instruction Windows must obey.

### 6. Preferences Persistence Reuse
Enabled Microsoft modes are persisted and retrieved using the existing `Set<VideoMode>` string serialization format (`<width>x<height>@<refresh>`), avoiding configuration schema migrations.

Retain existing preference keys and saved choices; filtering an unsupported mode must not erase it from stored settings. Settings edits apply to the next connection, not to the already frozen connection offer. Continue to use the selected `VideoMode` for picture scaling, diagnostics, and input coordinates without protocol-specific rendering changes.

## Testing Decisions

### Test Characteristics
Tests must exercise external behavior through existing component boundaries, focusing on wire-format input/output rather than private internal state.

### Modules Under Test
- **RTSP session public boundary**: Feed raw M3/M4 messages to `RtspSinkSession.handle()`. Assert emitted hex, all 15 allowed index mappings, excluded/unoffered/reserved/ambiguous selections, request-order independence, subsequent format changes, and the EDID bytes including checksum and timing range.
- **Product session public boundary**: Feed settings and actions to `MiraxSession`; inspect snapshots and exported settings. Assert the separate opt-in catalog, individual/bulk selection, restored settings, unsupported-mode filtering without data loss, frozen connection offers, and selected resolution reporting.
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
