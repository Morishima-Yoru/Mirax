# Fourth-batch review and C particle assembly

Date: 2026-10-27. Implemented proposals, not user aesthetic approval.

## Cleanup and lineage

The second review snapshot contained 20 active rejects. They were removed from the active directory
and host script list after copying their exact bytes and the review snapshot to the evidence archive.
The nine originals are byte-identical: seven kept (14, 17, 20, 36, 45, 51, 58), two unrated (09, 34).
The saved review file was not rewritten. Historical rejected IDs remain in it for provenance.

Each original now has three independent descendants, with new IDs and no inherited votes.
The active catalogue is 36 studies plus four legacy/baseline options. IDs are never renumbered.

| Original | New IDs | Distinct mechanisms |
| --- | --- | --- |
| 09 vernier | 66-68 | travelling slip defect; woven tooth banks; compliant corner latch |
| 14 penetration | 69-71 | curved section; folded wedge section; connected depth shells |
| 17 cathode | 72-74 | paired contour orbit; corner-node transfer; interwoven traces |
| 20 shield | 75-77 | sliding shingles; paired hinges; torque between opposite edges |
| 34 depth | 78-80 | forked vanishing points; geometric gimbal; folding depth planes |
| 36 quasicrystal | 81-83 | bound dislocation; domain interfaces; mirrored twin boundary |
| 45 growth | 84-86 | connected branching; coalescing domains; retiring/renewing void |
| 51 cross-sections | 87-89 | shared hinge; complementary sliding windows; depth tomography |
| 58 spark relay | 90-92 | bifurcating branch; paired angular bridges; threshold contact |

Descriptors record `sourceId`, and categories identify the source in the existing picker. No new
study animates the actual X. Mark-based descendants leave scope unset; only 34/36/45 descendants
use field clipping. This avoids the earlier failure where a field mask erased a mark-based subject.

## D verification

- `verify.cjs`: 36 registrations and 4,680 deterministic frozen-frame draws; finite geometry,
  context-stack balance, correct lineage/scope and no fourth-batch `xMotion`.
- Natural playback: each original and its three descendants sampled at approximately .15, 1.25,
  2.75 and 4.25 elapsed seconds. 144 original JPEGs and nine early/late/change contact sheets.
  All nine sheets were visually inspected; each descendant changes geometry, not just brightness.
- 27 new studies across five viewports (1440x1000, 800x528, 390x844, 667x375, 320x568): 135
  cases with visible evolution, no overflow, controls in bounds and no unintended X transform.
- Shared UI: 320 responsive/foreground-occlusion cases and ten arrow/history/pause/keyboard checks.
- Shared lifecycle: 34 assertions for pause, history, old modes, active-only rendering, simple home,
  projection, local image/GIF/video overrides, visibility wiring and reduced-motion behavior.
- Review backend: 15 tests plus an isolated current-catalogue API check for a new ID's
  keep/reject/reset and removed-ID rejection. Production review data was not used for test writes.

## C assembly: initial review

This records the initial side-positioned C implementation. The later centred, continuously flowing
80/20 particle revision supersedes that layout and the all-particles assembly; see `README.md`.

The original free-flight coordinates, dot sizes and alpha remain in the host. A separate geometry
module projects the same 1,000 particles toward the Mirax arm/diamond contours when broadcast is
enabled: about 12 shared-clock seconds in, eight seconds out. Individual phase lags and curved
approaches keep the movement organic. The geometry is derived from `ic_mirax_mark.xml`.

The first portrait layout collided with navigation; it was corrected rather than hiding the mark.
Tall portrait parks the complete X between status and cast hint. Short portrait uses the right gutter.
Landscape remains beside the status, with its upper edge clear of receiver text. No foreground text moves.

- `verify-c-assembly.cjs`: 25,222 checks for exact OFF coordinates, bounded progress, pause,
  toggle continuity, finite targets, four populated arms and responsive placement.
- Real natural playback verified gathering, paused pixels and full dispersal. Six formed sizes
  (800x528, 390x844, 320x568, 1440x1000, 667x375, 568x320) kept all particles on screen,
  above navigation and outside measured foreground glyph boxes. Final frames were visually inspected.

Limits: no physical-device performance or long-duration OLED test. Small portrait uses a smaller,
denser particle mark. Some D variants are intentionally subtle; slow crystal-domain changes need
longer viewing than a still image. Android, dependencies, commits and automatic cleanup are unchanged.
