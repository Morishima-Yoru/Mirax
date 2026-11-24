# ADR 0007: Standby Formal Theme Names and Localization Policy

Status: Accepted for product naming, multilingual policy, scale units, and the extreme-inset layout policy below; complete native locale/layout acceptance remains pending.
Date: 2026-11-01

## Context

User instruction explicitly mandates retiring alphabetic theme aliases (`A`, `C`, `D`)
from all user-facing surfaces, specifications, and discussions in favor of formal product
theme identities. Additionally, end-to-end multi-language localization must be anchored
from the initial native Android implementation slice rather than treated as a post-visual retrofit.

## Decision

### 1. Formal Product Theme Naming
Product UI, specifications, issue tickets, acceptance reports, and forward-looking discussions
MUST use formal theme names. Alphabetic designations (`A`, `C`, `D`) are strictly restricted
to isolated prototype provenance, URL query parameters, test fixture filenames, and historical
archive keys; no code rename migration or enum rewrites are authorized at this stage.

| Prototype Source ID | Formal Chinese Display Label | English Design Identity | Approved Baseline Reference |
| :--- | :--- | :--- | :--- |
| `A` | `山霧 · 流動地景` | Landscape | Ambient mountain / mist landscape motion |
| `C` | `微粒 · 黑白場域` | Matter | Particle field, launch default theme; FROZEN uses approved `frozen-lattice` (proposal 06) |
| `D` | `硬線 · MIRAX 形構` | Hardline | Geometry / stream lines; default `study-118-section-extrusion` only (historical alias: `硬線 · 斜切指令流`) |

Formal Chinese display labels are established directly from prototype settings options
(`design/big-standby-prototype.html:847-851`) with experimental letter prefixes removed.
English identities represent established design concepts; full localized English subtitles
require reviewed resource content rather than unvetted literal marketing inventions.
Native preferences MUST persist a stable non-localized theme identity, never visible localized strings.

### 2. End-to-End Localization Architecture
Localization is mandatory end-to-end from the first native slice. This encompasses theme
display names, state presentations (including the genuine pre-play FAILED presentation),
connection and idle hints, sidebar navigation items, history, settings dialogs, media framing/crop
controls, error feedback, and accessibility `contentDescription` attributes.

- No hardcoded semantic English or Chinese text may be embedded in Canvas or custom drawing routines.
- Renderers MUST consume locale-resolved strings resolved through Android resource mechanisms
  (`res/values/strings.xml` as English default, `res/values-zh-rTW/strings.xml` for Traditional Chinese).
- Logic reuses the existing baseline locale architecture: `LanguagePreference` (`FOLLOW_SYSTEM`,
  `TRADITIONAL_CHINESE`, `ENGLISH`), `AppLanguage`, and resolution logic in `SessionTypes.kt:104-119`,
  `MiraxSession.kt:591-603`, and `HostEnvironment.kt:21-38`. Other system languages fall back
  to English as the current repository baseline.
- Global device locale must not be mutated. User receiver and source names are treated as raw user
  data and must not be auto-translated. Raw diagnostic codes remain technical facts, but surrounding
  explanatory guidance must be fully localized. Fixed decorative brand art strings remain untranslated
  only when explicitly classified as brand art.
- Formatting for dates, times, and numbers must honor the resolved AppLanguage alongside reviewed
  localized placeholders and plural rules; explicit user language preference persists across restarts
  and overrides system locale, while follow-system retains existing fallback to English for non-Traditional
  Chinese systems (no new languages).
- Matter's status plate keeps the prototype's fixed visible codes. Its accessibility description uses
  the localized phase meaning or generic connection failure; cause details stay in the separate failure notice.

### 3. Typography, Layout Contracts, and Faithful Design
- Text must be dynamically measured using the active resolved font metrics and locale.
  Drawing caches and text layouts must invalidate upon configuration or language changes.
- Developers must not assume ASCII/CJK pixel equivalence, stretch text, or perform unapproved
  font downscaling to force fitment.
- Layout contracts—including single-line prototype-nominal 20 CSSpx status typography and intentional receiver-name
  truncation (`text-overflow: ellipsis`) in `微粒 · 黑白場域`—must hold across both Chinese and English
  without accidental glyph clipping, unintended wrapping, or overlapping controls.
- Any font, spacing, or motion adaptations required under the faithful-design policy must be
  documented for explicit review rather than excused via catch-all locale exceptions.
- **Full-layout test acceptance**: The original packing checks apply at an inclusive 144 dp usable short side:
  `min(W - L - R, H - T - B) / childDensity`, subtracting only content-safe insets applied to the measured child, once.
  Below that floor, stress tests still protect state and raw copy, measured 48 dp targets, scroll and focus,
  feasible interactions, and no unsolicited callbacks. This is not an Android rule, design resolution, runtime gate,
  or densityDpi/fontScale waiver. The floor alone does not authorize moving anchors, shrinking targets, hiding controls, or whole-page scrolling.

#### Extreme-inset layouts (2026-11-18, #38)

The two reviewed 200 dp safe-height cases are the **last required fit for these inset layouts**, not examples of unlimited responsive support. Dimensions below are the full child and the usable rectangle after applying its insets once; both references use density 1.5 and fontScale 1.

| Theme | Full child; top/bottom inset | Usable area | Approved change only when the normal positions collide |
| --- | --- | --- | --- |
| Landscape | 1200 × 540 px; 120 px each | 800 × 200 dp | Lift the central text just clear of the broadcast hit target. Put the invitation to the left of the dock; lift the brand only if it intersects the settings handle. The existing right-side failure notice keeps its own space. |
| Hardline | 540 × 1200 px; 450 px each | 360 × 200 dp | Move the entire opaque status band to the top of the safe area. In the lower row place the brand, unchanged dock, and receiver from left to right. The handle may overlay the blank end of the band, not its text. Keep the raw receiver name; ellipsize its single visible line only if the available width requires it. |

If density, aspect ratio or insets constrain the same content more severely than these cases, **complete responsive packing is best effort, not guaranteed**. Judge the available space in dp and the measured content, not raw DPI or a universal aspect-ratio cutoff. This does not replace the 144 dp test floor, excuse a failure in either limit case, or remove existing regression checks. Normal anchors, platform font scaling, actual 48 dp targets, raw copy, focus, and feasible actions remain requirements. No new runtime cutoff or whole-page scroll is authorized; Matter is unchanged.

**Landscape follow-up (2026-11-19, [#38](https://github.com/Morishima-Yoru/Mirax/issues/38#issuecomment-7003319476)):**
In the approved 800 × 200 dp layout, a plain hint, including the outgoing copy during a fade, may use the dock-left lane only when its normal placement would collide with the title or dock and its complete text fits in that lane. Keep the original anchor without a collision, the 150 ms fade-out, the dock and real 48 dp targets. Do not hide, shrink or cut copy to make it fit.
The separate 540 × 1200 px portrait **Landscape** regression at density 1.5, fontScale 1 and 450 px top/bottom insets (360 × 200 dp usable) still requires full packing. A different composition needs a same-device comparison and approval before adoption; the Hardline portrait layout is not that approval.

**Portrait Landscape extreme-inset approval (2026-11-19, [#38](https://github.com/Morishima-Yoru/Mirax/issues/38#issuecomment-7008891073)):**
In the 540 × 1200 px portrait **Landscape** layout at density 1.5, fontScale 1 and 450 px top/bottom insets (360 × 200 dp usable), when normal placement collides, pack all roles disjointly inside the safe area using measured lanes: place the title above the dock and right of the settings handle, the intact failure notice and >=48 dp Dismiss action in the upper-right lane, the brand and decoupled status row below the settings handle, and the intact styled Win + K invitation with the raw receiver name in the dock-right lane. Keep the dock and settings handle at their native anchors and >=48 dp hit targets, preserve typography and fades without shrinking text or whole-page scrolling, clamp each lane independently against ambient drift, and restore source geometry when the collision clears.

#### Scale-unit clarification (2026-11-17)

- **Units and conversion paths**: `W` and `H` are native physical view pixels; Android density is
  physical pixels per dp. Android dp, SP converted with platform fontScale, and prototype CSS units
  are distinct. `StandbyViewportScale.scale` is a dimensionless factor `s`, not pixels or density.
  Matter's current default AREA path uses `W / density` and `H / density`:
  `s = sqrt(((W / density) * (H / density)) / (390 * 844))`; `px(value) = value * s * density`.
  The reference area `390 * 844` is an engineering reference, not a user-approved fixed design resolution.
  Landscape/Hardline's current CSS-presentation opt-in sets `s = 1`; its conversion still includes density.
  These named paths do not prove every consumer correct. Do not multiply density twice, treat source px
  as constant physical pixels across DPI, silently unify/revert paths, or discard accepted reference comparisons.
  Matter's nominal 280-unit aligned plate ceiling uses AREA pixel conversion; measured content may exceed it
  (#34), but the aligned width remains bounded by the available side width.
  Separately, the aligned hint's 320 AREA-unit limit is bounded by available side width and the applicable 45% width limit.
  Matter's aligned slot budgets and anchors share the extra horizontal inset
  `clamp(16 AREA units, 3% of full physical stage width, 40 AREA units)`.
- **Font-relative title indentation**: Landscape's frozen `text-indent: .10em` applies only to the first
  rendered title line, not whole-view padding or later lines. Resolve the indent from the actual platform-SP
  title size and round to the nearest physical pixel (at most 0.5px quantization); retain centered layout.
  FontScale checks follow platform SP conversion, not an assumed linear multiplier or strict growth at every
  nominal size. **Localized English Landscape (2026-11-19):** retain the Android system typeface and let
  the resolved metrics and available width determine natural wrapping. The two-line English-text-normalized
  zh-Hant reference is not a native line-count requirement. Retain the original comparison and verify complete,
  unclipped text, native line flow and the first-line indent; no font substitution or forced line break is approved.
- **Legacy motion units**: In [the frozen prototype](../../design/big-standby-prototype.html),
  `applyAmbientTransforms` uses shared `t = particleTime / 1000`, not wall/display time;
  `StandbyOledDriftMath.burnOffsets` matches and is wired. Landscape/Hardline `#standby-view` receives
  two-decimal burn offsets in CSS pixels, converted to native pixels with display density; the broadcast dock does not drift.
  Matter's independent OLED/gyro calibration is not this model. In `applyXPlaneTransform`, each base
  `oledShift = round2(1.4 * burn)` is numeric SVG user units: `oledShift + studyDelta * (1 / CTM.a)`.
  Native physical X base translation uses the existing main-SVG slice scale; do not scale the whole sum by density/inverse CTM.
  Default study 118 adds zero optional motion; other studies remain unauthorized. Rails/header/ROG/viewport diamond do not move with X;
  projected 118 anchors follow the moved X. The diamond remains viewport CSS size/gap/stroke 26/12/1, separate from main-SVG slice units.
  `dStatusHandleTop` anchors the Hardline handle CENTER at stage-local `0.47 * stageHeight + burnY` for Big/nonprojecting/generated, otherwise 50%.
  This does not change the normal centerBand's 47% anchor; do not use fixed PNG offsets or derived font padding to align unsynchronized frames.
  Existing AREA/CSS paths are unchanged; legacy shared-clock wiring is implemented and scoped native checks have passed; full phase/DPI/capture visual acceptance remains pending.
- **Preserve intentional DPI adjustments before diagnosing bugs**: [#27](https://github.com/Morishima-Yoru/Mirax/issues/27)
  covers density normalization, removal of the `0.75` patch, and nominal 84/96 artwork dimensions;
  [#34](https://github.com/Morishima-Yoru/Mirax/issues/34#issuecomment-6896445290) preserves the content-width
  compensation intended to let `DISCONN` fit; [#35](https://github.com/Morishima-Yoru/Mirax/issues/35) records
  the footer-alignment tolerance's purpose. Nominal artwork dimensions are neither invariant physical pixels
  nor actual hit bounds. Exact per-constant calibration is not proven. The Hardline 50% center and stroke
  embellishments from #35 were explicitly withdrawn; this is not blanket approval of #35 or arbitrary constants.
  Preserve coupled calculations: Matter's aligned-slot gap deduction and tolerance currently cancel,
  retaining an effective height allowance equal to the action size. Do not convert/remove the tolerance independently.
  Agent-written completion claims are not per-item human approval.
- **Accessibility and proportional layout**: Actual minimum 48dp hit targets and user fontScale remain
  independent of visual-art scaling; never stretch/shrink text or shrink targets to hide failures.
  Preserve the [proportional-layout guideline](../../.scratch/big-standby/spec.md) (line 150): with the same
  aspect ratio, fontScale, content, and proportionally equivalent safe areas, resolution or DPI alone must
  not change visible geometry proportions or cause reflow beyond pixel-quantization boundary effects.
  Fixed physical stage bounds with changed context
  density can test density-only visual-art invariance; actual 48dp hit bounds remain a separate check.
  Changed aspect, safe areas, or fontScale are different inputs, not resolution/DPI-only comparisons.
  Synthetic child-view fixtures are not physical-panel measurements, but their assertions must not be dismissed solely for being synthetic.
  This criterion does not claim current Landscape/Hardline pass all DPI/resolution cases or promise universal
  fit for incompatible inputs; current stress failures remain open.
- **No whole-foreground scrolling fallback**: The latest user decision prohibits whole-page foreground
  scrolling. It does not delete the [limited notice exception](../../.scratch/big-standby/verification.md)
  (line 762): FAILED/explanatory notices may move right of the broadcast button and scroll vertically only
  when they cannot fit above. The notice exception does not move the dock or authorize other content to scroll.
  Outside the two collision layouts approved above, preserve normal prototype hierarchy and anchors. Further overflow behavior needs approval.
- **Current Matter authorization and evidence limits**: Under [#38](https://github.com/Morishima-Yoru/Mirax/issues/38),
  the user authorizes confirmed Matter unit-conversion fixes in this round, not redesign or blanket
  `scale`-to-`px` rewriting. Preserve intentional adjustments' purpose and measured references. Distinguish
  fixture expectations, current code, and accepted requirements: a raw scale/px difference or RED test alone
  is not proof of a bug. At fixed physical bounds, density cancels from AREA `px(value)` outputs;
  omitting density from that expected-value calculation alone cannot explain a differing pixel gap.
  Bind actual density explicitly in fixtures. Per-consumer verification and calibration remain pending;
  the following approval is limited to the dock bottom-margin branch and does not authorize any other gate change.
- **Matter dock breakpoint (2026-11-19):** For landscape only, evaluate 440 in AREA units of the full measured
  stage: `H / (s × density) <= 440` selects 8 AREA units, otherwise use 12. Select the same margin in measurement
  and layout, retaining the existing safe-bottom maximum. At matched bounds, 800 × 450 uses 8 and 896 × 592
  uses 12 regardless of density. Do not reuse `compact`, change the other themes' CSS-density queries, or alter
  the calibrated artwork, footer alignment, width caps, minimum hit areas or platform font scaling.

## Consequences & Verification (Complete Acceptance Pending)

- **Trade-offs**: Standard Android resource localization and dynamic text measurement prevent layout
  breakages and internationalization debt, avoiding costly retrofits, while requiring comprehensive
  multi-viewport testing across both languages.
- **Acceptance Checklist (PENDING)**: Verification requires testing both Traditional Chinese and
  English under language switching, app restart, follow-system, and fallback scenarios; layout
  validation across portrait, landscape, and foldable postures; TalkBack / DPAD accessibility
  navigation; and verification of zero untranslated semantic strings.
- **Scope Boundaries**: ADR 0006 remains historical prototype scope; its parallax motion math and
  calibration are preserved. Native work follows separately approved slices; this ADR does not authorize
  new language additions, RTL layouts, or third-party rendering libraries.
- **Copy contract**: the frozen prototype document renders its own phase copy while the native
foreground renders Android string resources; where the two diverge the native render is
authoritative and the divergence is measured and recorded, never gated as a test failure.
**References**: Terminology aligns with `docs/CONTEXT.md`; active implementation requirements track
  `docs/research/android-standby-rendering-feasibility.md`.
