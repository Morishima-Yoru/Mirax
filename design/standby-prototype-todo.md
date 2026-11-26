# Standby prototype follow-up

Temporary capture of user-requested TODOs, not a replacement issue tracker.
GitHub publication to `Morishima-Yoru/Mirax` is pending because `gh` is unavailable
in this environment. Keep these items open until implemented and verified.

- [ ] C: Remove perceived hesitation when broadcasting starts.
  v19 follows the confirmed direction: exactly 6 seconds to gather using
  normalized exponential ease-out, f(t) = (1 - exp(-4t)) / (1 - exp(-4)).
  Pickup is fast and keeps decelerating through completion; there is no late
  acceleration or midpoint pause.
  No random sensing shim or second ease-in is added. Full dispersal
  retains its existing easing and 8-second duration.
  `verify-c-assembly.cjs` passes 60,831 checks. `verify-c-onset-ui.js` observes
  the real mobile-browser tap/render path and checks population-wide movement
  toward X instead of one arbitrary particle's motion.
  Automated checks pass; keep this open pending real-phone visual acceptance.
- [ ] C: Accept the independently layered v23 gyroscope on a real phone.
  Angular velocity gain is one third of v19: 30 deg/s gives 1/3 input and
  90 deg/s reaches the cap. Particle motion retains a coefficient of 4/s;
  corner titles independently use 2/s, with a 1-percent short-edge cap versus
  1.89 percent for particles. The layers no longer share the same motion history.
  Foreground and particles use layered translation only, without perspective,
  rotation or dynamic scaling. A real drawing buffer covers background edges
  without enlarging the particle geometry. OLED drift and 6s/8s timing are unchanged.
  C now attempts default sensor subscription once when eligible, without an
  automatic permission prompt. Gesture-permission browsers still need one click.
  Manual stop survives render, focus and theme changes until reload.
  Automated geometry and simulated-sensor checks do not establish physical feel.
- [x] A: Add a nighttime appearance when receiving/broadcasting is off.
  Implemented in v24 and refined in v25 using the existing broadcast class: a cool dark sky,
  dimmer mountain contours and crescent at night; the original day palette
  and sun only while broadcasting. No wall-clock or connection-phase dependency.
  Reuse the existing SVG geometry, mist and motion; keep the state cue inside
  the portrait crop. v25 uses 3.6-second sky/mountain color interpolation and
  independent sun/moon tracks, rising from lower left/right and setting back
  behind the ridges. The Windows connection tooltip is empty and hidden while
  broadcasting is off. v26 fixes the reduced-motion visibility regression:
  A transitions have their own explicitly labeled preview override, default on,
  with opt-out to honor system reduction; explicit pause still disables them.
  v27 maps the actual visible viewport through SVG slice cropping: the sun
  enters/exits fully beyond the left edge and the moon beyond the right edge,
  settling in the upper center rather than sharing the old right-side anchor.
  Celestial tracks no longer inherit the mountain pan; resizing snaps their
  geometry to the current state so the next transition still starts at an edge.
  Desktop/mobile browser checks observe intermediate colors and
  actual movement, both switches, reversal, connection/failure phases and B/C/D
  isolation. The reduced-motion mobile tap regression now checks intermediate
  viewport positions and colors, plus opt-out, pause and preference changes.
  Six viewport edge checks and the v27 320-case responsive regression pass. Physical-phone
  appearance remains unreviewed; GitHub publication is still pending.
- [x] v28: Close out the A/C/D presentation shell before Android implementation.
  Keep the theme visible without prototype notes, comparison navigation, reviews,
  simulation controls or inspector overlays. Place all of these inside a single
  left settings drawer, with one option to expand or collapse prototype tools.
  Open settings from a centered left-edge handle or a background rightward swipe;
  preserve text editing, vertical scrolling, multi-touch cancellation and keyboard access.
  Keep one theme-specific broadcast switch at bottom center, outside decorative
  motion. A uses the landscape palette, C a monochrome instrument treatment,
  D a hard-edged geometric treatment; the differences identify each theme's action.
  Remove B from selection and scene code, retaining historical B review data.
  All 16 browser suites pass, including 240 scene layouts, 120 drawer layouts,
  18 clean-mode layouts and six native Chromium touch swipes. Keyboard focus,
  scrollable tools, three distinct button shapes, B URL fallback and mocked
  review persistence are checked. The 39 backend tests and existing particle/
  sensor checks also pass. Real review data retains its previous SHA-256.
  Physical-phone swipe behavior and Android implementation remain unverified.
- [x] v29: Icon-only controls, edge information and animation polish.
  All main broadcast actions use one icon and a simple outline, retaining an
  accessible label, switch state and bottom-center touch target. C uses an
  equilateral downward triangle with a double lower-left edge; its center is
  an upward triangle when off, X when on and a red exclamation mark during
  the existing temporary failure, returning to X on recovery.
  Redesign the left handle and all three requested transition families:
  broadcast state/press, settings open/close and projection entry/return.
  Keep reduced-motion, pause, cancellation, modal/focus and sensor policies.
  Bring edge information closer to safe screen bounds where it fits, never
  compromising the centered broadcast action. D removes both lower corners,
  places Win + K in the upper-right receiver group and labels it RECEIVER.
  Remove A/D center descriptions and main-scene prototype disclaimers, while
  retaining the honest web-prototype notice inside settings.
  Rail dialog title gains exactly 1pt, bold white; badge colors, the 50-percent
  dialog opacity and the other nine C styles remain unchanged.
  Browser checks pass: all four icon treatments, C warning/recovery, rail
  typography, drawer entry/exit/reversal/resize, reduced/pause behavior and
  projection entry/return. Main scenes pass 320 layouts and the drawers 160.
  Eight Chromium native touch swipes and compatibility-click regression pass;
  physical-phone feel is still unverified.
- [x] v29: Integrate composition 209 and archive explicitly rejected D studies.
  New study 209 combines 17's cathode trace with sparse 118-derived extrusion,
  never transforms the shared X and must not inherit either source review.
  Its 5,765-frame geometry/continuity checks and live Canvas in five layouts pass.
  At request time, 31 of 53 active studies have explicit reject votes. Keep the
  22 retained/unrated sources, add 209, and archive only current rejects.
  Preserve all original votes/notes and source 17/118; do not clean C studies.
  Register the new script and restart only the preview listener so its review
  allowlist discovers 209. Existing Tailscale routes must remain untouched.
  Browser integration, source archiving and independent mock review persistence
  pass; 23 active registrations and 2,990 deterministic contract draws pass.
  Backend archive/history regression is added; all 40 tests pass. Real-phone
  visual acceptance remains open. Original review JSON SHA-256 is unchanged.
- [x] Theme E: monochrome slash-cut sci-fi proposal.
  Add E alongside A/C/D, borrowing the diagonal/hard-line design vocabulary of
  ROG without copying its logo, assets or RGB palette. Slash channels and two
  fictional monospace text streams flow from lower-left to upper-right. The
  white outlined MIRAX X must remain recognizable and use the shared mark.
  Keep the scene decorative, pointer-transparent and absent from accessibility
  navigation. Use the existing drawer, icon-only centered broadcast action,
  shared state machine and an independent concept-e rating/Note identity.
  CSS-only decoration pauses when hidden, paused, reduced, on the simple home,
  during projection, under custom media and whenever E is not selected.
  Do not add another renderer, sensor subscription, clock, external font or
  dependency. Six browser layouts, seven flow gates, independent mock reviews
  and real local-image pause/resume pass. HTTPS loads all 28 active scripts
  without page errors; phone/narrow/landscape/desktop/wide screenshots are
  inspected. Physical-device appearance and performance remain unmeasured.
- [x] v30: Restore normal viewport-relative padding for readable edge information.
  Replace the v29 12px shared/16px E insets with one shared
  clamp(24px, 3vmin, 48px) content padding plus safe-area insets.
  Override the stale compact A header rule that still placed branding at 70px.
  Calculate C/E footer widths from the actual side inset and reserve the centered
  broadcast action. Below 480px, place C's two information blocks in a row above
  the action instead of shrinking them into the corners. Motion gains stay unchanged.
  The new margin regression fails on v29 and passes 112 state/layout cases on v30.
  Existing 320 scene/160 drawer, 450 HUD placement and 4,320 parallax-composition
  checks pass. HTTPS DPR-3 phone/landscape/desktop screenshots are inspected.
  Real-phone/notch acceptance remains unverified; original review data is unchanged.
- [x] v31: Restore C connection-rail inner spacing from the user's screenshot.
  The final C corner rule had reset padding to zero, so both receiver title and
  Windows hint touched the left rail despite correct viewport padding. Restore
  14px left padding, matching the unchanged right status rail; keep outer insets,
  anchors, font sizes, motion and primary action unchanged.
  The regression now checks both border-to-text distances and adds the screenshot's
  approximate 896x592 CSS viewport. It fails before the fix and passes 128 cases
  afterward; 320 scene/160 drawer and 450 HUD placement checks also pass.
  HTTPS DPR-2 reference/corner close-up/320px screenshots are inspected. Real-phone
  acceptance is still pending; review data remains unchanged.
- [x] v32: Slim the visible drawer handle, not the settings panel.
  The user clarified the exposed tab was too wide. Reduce its painted outline
  from 38px to 21px, simplify the arrow/rail and align the 24px artwork to the edge.
  Keep the existing 44x88px touch target, modal width, focus behavior and swipe flow.
  The shared drawer suite passes 24 layouts and eight native touch swipes; tapping
  the transparent hit area at x=36 also opens settings on all four themes.
  HTTPS DPR-2 phone/reference/close-up captures are inspected; real-phone visual
  acceptance remains pending. Other layout and review data are unchanged.
- [x] v33: Consolidate D/E and complete the nine visual-control corrections.
  Active themes are A/C/D. Legacy E URLs resolve to D; B still resolves to A.
  Original E votes/Notes remain historical and are not inherited by D.
  D keeps its original X geometry and central receiver state, uses E's slash-cut
  broadcast outline, two diagonal channels, three upper/left decorative copy rows,
  and masked white cathode tracing on those glyph edges. Default motion is the
  unchanged source 118 C2 hollow perspective; explicit comparison URLs still work.
  A uses a slim cloud-edge handle, rounded pill outline, rounded green play and
  rounded red square. C's extra triangle rail is outside its lower-left side.
  C rail headers are actually opaque white, not white underneath parent opacity;
  only the body retains 50% opacity, with a saturated non-white status for every
  preset. The other nine styles and their review identities stay unchanged.
  All themes share A's brand signature. C corner content adds a small inner margin
  when sharing the action row; below 480px it keeps the existing edge/raised row.
  Shared action changes crossfade frames/glyphs, colors, press/hover and dock
  entry/exit, respecting reduced motion, pause and hidden. Existing A/C previews
  do not override those interaction gates or the new D decoration gate.
  Text-size adjustment is 100%; native review fields are at least 16 CSS px to
  avoid focus zoom. Manual/accessibility zoom remains enabled. Equal CSS viewports
  match across desktop/mobile and DPR 1/3, not equal physical sizes across devices.
  Verification: 240 scene/120 drawer layouts, 120 branding checks, 120 edge cases,
  36 sizing cases, four manual viewport magnifications, three theme crossfades,
  real dock fade, 450 HUD placements/eight rail presets and 4,320 composed motion
  cases pass. Six native touch swipes, warning/recovery/view transitions, hybrid
  flow direction/cathode masks/seven lifecycle gates and mocked review persistence
  pass. The parallax suite now waits for the real resize event before comparing
  the canvas, removing an observed resize race without weakening its bounds.
  23 study registrations/2,990 deterministic draws and the 40 server tests pass.
  HTTPS phone/narrow/landscape/desktop captures are inspected. No Android port,
  physical sensor/phone, iOS focus-zoom, hardware performance or OLED burn-in claim.
- [x] v34: Reopen the four rejected v33 claims against observable behavior.
  Reproduction with the phone's reported system preference showed D tracks paused
  and broadcast transitions at 0s. The user explicitly removed reduced-motion
  behavior: delete its media queries, state, listener, A/C override controls and
  lifecycle branches. Keep explicit decoration pause, hidden/media/view gates,
  gyro manual stop and gesture permission. Decoration pause no longer suppresses
  user-triggered button/drawer/view feedback; hidden pages still finish transitions.
  D retains the three-row left band, single-row right band and 240/360s loops.
  Replace the almost always invisible 24s glyph mask with visible 4.8s cathode heads
  and fading tails on both long boundaries. Travel is confined to the viewport
  intersection. Live timelines advance under both system preferences; screenshot
  pixels at 0.5/1/2s show white heads, fading tails and no wide glow.
  C now measures actual descendants, including leftward flex overflow, before
  sharing the action row. Fit rows center exactly on the action and move inward;
  non-fit rows keep the old safe edge and raised placement. At 480x375, OFF fits
  while ESTABLISHING stacks, proving this is not another hard-coded breakpoint.
  Freeze reference typography in CSS pixels instead of changing fonts at width
  breakpoints. Presentation size-lock defaults on: native pinch, page keyboard
  zoom and Ctrl-wheel are intercepted. The drawer exposes an explicit unlock for
  ordinary/accessibility zoom; native multi-touch proves lock, unlock and relock.
  Browser-menu/site zoom, browser override settings and OS magnification cannot
  be absolutely prohibited by a web page; restore browser zoom to 100% for review.
  Do not claim identical physical size across hardware or absolute browser control.
  Verification: 36 desktop/mobile/DPR size cases with fixed-font comparisons,
  120 edge/glyph bounds, 240 scene/120 drawer layouts, 120 brand checks, six native
  swipes, 450 HUD placements, 2,400 dynamic placements and 4,320 composed parallax
  cases pass. A/C native-sensor mocks, 6/8s assembly onset, day/night, button warning
  recovery and isolated review persistence pass. HTTPS two cold loads contain 28
  scripts each; real votes hash remains unchanged. Browser captures are inspected.
  Physical Android/OS browser zoom override acceptance remains open, not inferred
  from CDP emulation. No Android implementation or new animation dependency.

## v35 scope, delegation and evidence

Original request wording, captured before implementation, is retained below.
Checked items mean parent-verified product BUILD completion. All v35 agent/browser
QA gates, latest D-diff oracle and final Git/votes/source audit passed. Earlier
manual acceptance stays OPEN; formal goal closure remains the parent's action.

- [x] 1. D: Make both lower and right text bands exactly two rows.
- [x] 2. D: Make cathode shots faster, with an independently random start per shot
  and an ease-in travel curve instead of a uniform repeating scanner.
- [x] 3. D: Use visibly varied, non-identical text patterns; selected spans may be
  brighter or bigger without making every row identical.
- [x] 4. D: Keep the second lower/right row as a fixed, brighter Win + K hint and
  remove the upper-right Win + K hint. This is the same band as item 1, not an
  additional hint region.
- [x] 5. A/C: Enlarge only the existing button border slightly, preserving its
  shape and the exact icon size; do not use a pure-black button color.
- [x] 6. A: Enlarge the sun and moon without changing their day/night contract.
- [x] 7. A: Raise landscape status and celestial placement; preserve portrait.
- [x] 8. C: Shorten every HUD body string enough to remain on one line at the
  supported layouts, without clipping or shrinking the font.
- [x] 9. A/D: Recover from failure after the same interval as C, preserving
  cancellation, stale-callback and scene/page lifecycle guards.
- [x] 10. A: Fade every visible main-screen text change instead of swapping it
  directly, including dynamic labels and any direct innerHTML updates.
- [x] 11. D: Move the existing central icon slightly left and down.

Confirmed D clarification: item 1 concerns the lower/right band only, with two
rows; the upper/left band retains three. Item 4's fixed hint is the second
lower/right row, not an additional region. The initial wording above is historical.

Delegation constraint: all implementation, tests and documentation edits are by
subagents; the parent only dispatches, reviews and verifies. The D designer owns
`big-standby-e.css`, `big-standby-e-scene.js` and an optional dedicated D helper
only. The visual designer owns `big-standby-prototype.html` first; the behavior
fixer may edit that same host only after the visual lane reaches terminal state.
No concurrent host writes, shared Playwright browser or review-data writes.

Baseline entry: v34 exposes A/C/D only, has no reduced-motion feature, and starts
with size-lock enabled plus an explicit ordinary/accessibility zoom unlock.
Preserve source17/source118/source209 and D's X semantics. The real vote fixture
must retain SHA-256
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Detailed baseline contracts and audit references follow after read-only inspection.

### v34 read-only baseline findings for v35

References below are v34 line numbers, before implementation. No v35 item is
verified by this baseline. The existing worktree was already dirty; no product
file, test, source study or review fixture was edited by this baseline lane.

- Host: `big-standby-prototype.html:1012,1049-1065` defines exactly A/C/D;
  `variant=E` aliases D, and default D motion remains source118 C2. Size-lock is
  true at lines 2/1063, synchronized at 1607-1612, with the explicit unlock at
  930. No system reduced-motion feature remains. Asset links are at 607/1004.
- Host call graph: user actions enter `act` (1742-1746), then `render`
  (1632-1740), then HUD lifecycle, `syncMotion`, particle draw and inspector.
  Direct render paths also exist for drawer open/close, media load/playback,
  settings close and boot (1876/1895/1994/2014/2614/2733). Browser history
  (2702-2711) restores variant/log/motion through `act`; visibility (2713)
  uses `act` too. A text work cannot assume render only runs for phase changes.
- A visible main-screen mutations are direct writes in render: `status-title`,
  `status-eyebrow`, `.device .micro-label` (1657-1661) and `connection-hint`
  (1666-1674). A OFF empties the hint with textContent; ON recreates nested
  kbd/receiver spans with innerHTML on every render. `status-detail` is hidden
  outside C. Dynamic labels also include `simple-status`, `simulation-state`,
  concept/review controls and `return-home-label` (1662-1699). `broadcast-label`
  is dynamic but sr-only (802/1692); the visible settings broadcast label is
  separate (1696). `showToast` directly replaces visible text outside render
  (1570-1574; video autoplay failure caller 2015). No shared A text-fade hook
  exists. `syncViewTransition` (1832-1861) only handles changes of view nodes,
  not text changes or A/C/D changes within the same standby node.
- Failure contract: `setPhase` (1766-1782) validates transitions; OFF toggles
  clear the phase through `toggleBroadcast` (1760-1764). Only C big-home
  failures call `startCFailure` after render. C's 250ms body glitch starts the
  warning on the actual, identity-guarded animation finish (1791-1811).
  Recovery is 7500 active particle-clock ms after warning start (2388-2390),
  not a 7500ms wall-clock timeout. Color attack is 500ms; full-red ends at
  5500ms; white is restored at 7500ms (1813-1819). Explicit pause, hidden page,
  simple home and projection stop the shared clock; per-frame elapsed time is
  capped at 80ms (1098-1102/2379-2393). `syncMotion` (2436-2460) cancels/clears
  on non-failed phases and guards C glitch pause/resume. A/D failures entered
  directly have no start clock; a C failure carried into another theme can
  already have one. History preserves the phase. Media-ready gates D decoration
  and C HUD, but is not a shared failure-clock stop condition.
- A geometry: SVG is 1600x1000 with xMidYMid slice (638). Celestials precede
  ridges (646-663), so mountain occlusion is real. Sun radius is 27; moon uses
  27/24-radius path arcs (649/653). `resizeCanvas` recomputes the celestial
  group position using visible slice bounds, a 48-unit visible-top margin,
  visibleWidth/2 + 48 entry offsets and visibleHeight * .09 horizon offset
  (2395-2416). A size/height changes therefore touch both geometry and resize
  anchoring. Status center is 49% by default (62), 47% short portrait (354),
  51% compact/landscape (360-370). The final short-landscape rule currently
  changes only the hint (601-604). Reference typography is fixed (587-595).
- A/C buttons: 84x84 button and art, SVG viewBox 0 0 100 100 (531/535/786).
  Frames and glyphs are sibling groups; A frame is the 86x46 rx23 rect at
  7,25; C is the existing triangle plus double edge (787-788). Glyph paths
  live independently at 790-799. Button background is transparent; no black
  fill is needed to preserve its current contract. Whole-art scaling would
  change the icon as well as the border. Dock remains outside the stage/views.
- C HUD: eight presets contain 24 body strings (1364-1445), plus the initial
  `> LATTICE LOCKED` fallback (749-750). `showRandomCHud` injects lines as
  `.c-hud-line` nodes (1512-1515). Base font is 10px/1.5 with .05em tracking;
  current lines explicitly allow anywhere wrapping (127-132). The `index`
  style reserves 82px plus a 10px grid gap, while telemetry/terminal consume
  additional inline room (141/150/155). Checking rail alone is insufficient.
  Placement uses measured dialog size, five zones, brand/footer bounds and
  bounded sway after content injection (1495-1559); size must remain measurable,
  not clipped. HUD lifecycle gates are at 1449-1460; existing parallax and OLED
  composition remain independent of string changes.
- D decoration is still named E internally. `big-standby-e-scene.js:7-27`
  creates two aria-hidden, non-interactive channels: upper has three moving
  rows, lower one; each track has two equal repeated segments, no fixed hint
  row. CSS heights are 64px/30px (18/32-37), with 240s upper and 360s lower
  flow (83-97). Four pseudo-edge scanners use a fixed 4.8s repeating curve
  cubic-bezier(.35,0,.65,1), viewport-clipped travel and a 56-96px head/tail
  (39-67/100-103). There is no per-shot random JS scheduler. Sole host
  lifecycle contract is `.stage.v-D.e-motion-active` (host1650; CSS106-110),
  false for explicit pause, hidden page, other themes, simple home, projection
  or media-ready. The upper-right Win + K is host `connection-hint`
  (574/1668-1669), not owned by the D decorator. `concepts.D.hint` still says
  three rows (1040), so host copy is also in the visual/behavior handoff scope.
- D X: host `.x-core` and echo keep their original href, x/y, 1370 size and
  paints (684-686); shared symbol arms are at 620-624. `applyXPlaneTransform`
  rewrites the whole `.x-plane` SVG transform each animation frame (2314-2349).
  Study-frame screen geometry derives from xCore plus its screen CTM
  (2190-2227). A static transform can be overwritten, or disagree with the
  source118/source209 canvas if relocation is applied to only one layer.
- Test updates implicated by the new scope: `verify-e-theme-ui.js:70-165`
  asserts three/one rows, four moving tracks and exact 240/360s distribution;
  its original-X checks (55-65), seamless flow and lifecycle checks remain
  preservation evidence. `verify-v29-controls-ui.js:49-54` asserts A's old
  frame coordinates. `verify-a-day-night-ui.js` and
  `verify-a-reduced-motion-ui.js` cover celestial trajectories/preferences,
  not all-text fades. `verify-c-status.cjs`, `verify-c-failure-ui.js` and
  `verify-c-recovery.js` cover C timing/stale finish/pause/history, not a failure
  originating in A/D. `verify-c-log-styles.js` and `verify-c-motion.js` cover
  HUD styles/placement but do not assert every body's single-line fit. Recheck
  `verify-v33-sizing-ui.js`, `verify-edge-padding-ui.js`, `verify-rwd.js`,
  `verify-c-parallax-ui.js`, `verify-d-209-ui.js` and review isolation as shared
  regression boundaries. No fixture/config/export change is currently required;
  the preview server serves ordinary static assets (serve_big_standby.py:277).

Baseline evidence: read-only Node suites `verify.cjs` (23 registrations/2990
draws), `verify-209.cjs` (5765 frames), `verify-c-status.cjs`,
`verify-c-assembly.cjs` (60831 checks), `verify-c-oled-drift.cjs` (864000 checks),
`verify-c-parallax.cjs` and `verify-c-gyroscope.cjs` passed. No browser, device or
visual verification was performed by this lane. Real votes hash was rechecked
after those runs and still matches the protected SHA above.

Audit snapshot: `C:/Users/User/AppData/Local/Temp/opencode/tidy-squid-v34-baseline.html`.
Its SHA-256 equals the untouched v34 host:
`949fad98b0c273b402ee71d5540f5dc7f0996a039dedfb23942449000fb0ef5f`.
Additional baseline SHA-256 values:

- `big-standby-e.css`: `c087ece4061ee8d24c00f6c8f2638feee6951470865e97c88c6d5f3472aaf6fe`
- `big-standby-e-scene.js`: `c66ec6c9acd7ff6fae0959decf606c6ebb74c973da42876966beb13031e3adae`
- `17-cathode.js`: `f1893c12bd8f94b1cac66435d11098db36f1f1748cb406931c42ba4b3f3cb526`
- `118-section-extrusion.js`: `43ed31dc1debaaf58cf46cd7fe7b53e6a1d38d64d975d87a081c3f2d7ba30767`
- `209-cathode-extrusion.js`: `44678823d73064b47ba4a9eda50d9a13ba5e1390092fdbb7054b9e997624930d`

### v35 product BUILD completion matrix; final UI suite PASS

Parent independently verified all eleven product changes below. This updates the
checkpoint in place, not a second implementation history. This documentation
child did not rerun tests; all implementation/test/doc edits remain child-owned.
Final reviewed host SHA-256 is unchanged:
`b27179f649976ee9d3d06777437209e9b7121ed37135274a92dda5404197ba76`.
The latest D-diff oracle reached foreground terminal completion: no blockers.
It reviewed visible-intersection math, the 9.5px second-row offset and odd 65
repeats. A read-only comparison normalizing 65 back to 64 matched the prior JS
SHA; no additional rAF, timers or layout reads were introduced.

Final D source SHA-256 values:
- `big-standby-e.css`: `a2f726a4c9114f1a9f721a8615aaaaeed5897ef5226ca2273ea4d0260b89379d`
- `big-standby-e-scene.js`: `3f2f01a3711f3e4427b15af17e425f2d25cb6a088e1f03198c339192f33bbbee`

| Item | Parent-verified product evidence |
| --- | --- |
| 1 | Upper/left: three rows. Lower/right: exactly two rows, including the fixed hint. |
| 2 | Four independent edges; fresh 0.12-0.90s gaps, 0.95-1.60s shots, position = progress squared. Node: 90 shots / 6,784 acceleration checks; independent review: 80,000 frames / 8,988 shots / eight viewports. Actual 2.4s host observation advanced about 2.407s with eight distinct shots; pause froze it. |
| 3 | Varied register/hex/comment upper rows and packet lower copy; four moving tracks retain 240s/360s flow. |
| 4 | Fixed, repeated, brighter white `Win + K` in the second lower/right row; upper-right hint removed. Final HTTPS complete-group counts: 390x844 = 5, 568x320 = 1, 1440x1000 = 3. |
| 5 | A/C targets 96 CSS px; original frame paths and 84 CSS-px artwork preserved; frame scale 1.12, exact glyph CTM 0.84. Non-black fills: A `#193b30`, C `#262a28`. |
| 6 | A sun radius 33, moon scale 11/9; two normal and six edge viewports passed full left/right exit, rapid reversal and gradient checks. |
| 7 | Landscape status 43%, higher celestials (896x592 y=250 to 190); normal portrait 49%, existing short-portrait 47% retained. Both celestial exits clear the edge by at least 16 CSS px in six sizes. |
| 8 | 1,680 one-line text Ranges across seven viewports x ten styles x eight presets x three lines; maximum 108.109px < minimum body 130px, unchanged 10px font, no cropping. Footer/action 16px clearance passed 120 cases. |
| 9 | FAILED at 7,499 active ms; WAITING with anchor cleared at 7,500. Actual A wall/active 8,526.1/7,899.5ms (includes about 330ms visible post-recovery fade), D 8,199/7,524.8ms; A 622.6ms / D 633.5ms pauses each added zero active time. |
| 10 | A exit at 75ms showed glyphs/partial opacity and per-character PNG differences; normal OFF clear/hide/repeated render, rapid phase reversal and hidden/theme/simple/projection flush passed. Clean native context OFF/ON 45ms reversal sampled at 550/1,100ms: all three labels correct, opacity 1, no animations or pending work. |
| 11 | D target remains 84 CSS px; existing central icon alone shifts (-2, +2) CSS px. Shared X/source artwork is unchanged. |

D's initial `minmax(0, 1fr)` fix removed the 9,179px min-content overflow, but
568x320 then exposed an incomplete hint: one glyph bottom 321.5775, the next
right 597.1979. The final shared CSS centers the viewport intersection, offsets
the hint row with the edge by 9.5px and uses 65 repeats instead of 64 to center a
whole word. It keeps 32px padding, font, colors, API and timing; no 568px special
case. The writer separately reports a 983-viewport scan with minimum 16.03px
margin; that is the writer's claim, not a parent-reproduced scan. Parent HTTPS
568x320 measured a complete group at x=493.706, y=245.696, right=541.712,
bottom=293.702; paired row/window widths agreed at 1,350/909/1,600px.

### v35 verified regressions and artifacts

- PASS: parent ran `verify-v35-ui.js` to terminal completion in 60,219ms;
  all 26 groups passed. `failures = pageErrors = blockedWrites = []`;
  `ownedContexts = closedContexts = 2`. `reviewBefore = reviewAfter =`
  `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
  Coverage includes D 568x320 paint, DPR 1/3 geometry, C footer real font ink,
  all ten styles / eight presets / 24 body strings, all A fades including OFF
  75ms PNG/reversal/hidden cancellation, and exact A/C/D 7,500ms active-clock
  boundaries, lifecycle gates and stale episodes. This supersedes the earlier
  fixture-failing UI run; no new-suite UI gate remains open.
- PASS: old controls play/stop/warnings/recovery/drawer/playback; A day/night;
  both system motion preferences retain 3.6s transitions, including native
  550ms interpolation. The legacy reduced-motion test filename is not a feature.
- PASS: sizing 36 layouts at DPR 1/3 plus two native pinch lock/unlock cases;
  edge padding 120; clean drawer; full RWD 240 cases, ten navigation checks,
  120 drawer checks and 120 brand checks. The old drawer x=-440 failures sampled
  time zero of a 320ms entry; the helper now awaits real animation completion,
  without disabling animation or removing guards.
- PASS: C motion 2,400 positions / 64 ellipse samples; styles 450 placements;
  colors 80 cases plus 80 visible checks. The obsolete v29 color fixture now
  uses the unchanged v34 rail mapping. Failure UI retains the 250ms glitch and
  active-clock STANDBY recovery; onset preserves directed first pickup and 6s
  under both preferences. Auto/explicit gyroscope UI passed. HTTPS sensor checks
  loaded 28 scripts under each preference; native Chromium mocks produced seven
  samples, 1.824 CSS-px reversal and zero settled offset. Valid-origin handling
  and invalid-payload rejection passed; mocks do not prove physical sensors.
- PASS: backend real-HTTP 40 tests, flake8, all Node CJS suites, D streams and
  v35 production-function unit checks, including 16.7ms/80ms clock offsets and
  Range-leading regressions.
- PASS: final foreground D-diff oracle found no blockers; all JavaScript syntax
  and `git diff --check` passed. Final parent audit confirmed the protected review
  SHA and original studies 17/118/209 unchanged.
- PASS: actual HTTPS A/C/D v35 cold loads, valid TLS, 200/`no-store`, no 404s or
  page errors; served D CSS/JS contain the final hint fix and default
  `state.motion.dMode` selects source118. Parent read and visually reviewed the three D
  PNGs, including white shot heads, plus A/C phone and A 896x592 day PNGs.
  Artifacts under `C:/Users/User/AppData/Local/Temp/opencode/`:
  `mirax-v35-HTTPS-D-{390x844,568x320,1440x1000}.png`,
  `mirax-v35-HTTPS-{A,C}-390x844.png`, `mirax-v35-A-day-896x592.png`.

Official preview:
`https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=35`.

### v35 reproduction

Run from the repository root; these are reproduction instructions, not extra
runs by this documentation lane. Python is CPython 3.14+. Backend tests use
isolated scratch fixtures, not real votes.

```bash
uv run --python 3.14 -m unittest design.test_serve_big_standby -v
uvx --python 3.14 flake8 design/serve_big_standby.py design/test_serve_big_standby.py
for test in design/big-standby-motion-prototype/*.cjs design/verify-d-streams.cjs; do
    node "$test" || exit
done
```

Use the existing loopback server; only if port 8765 is free, start this in a
separate terminal and leave it foreground while running browser checks:

```bash
uv run --python 3.14 design/serve_big_standby.py --bind 127.0.0.1 --port 8765
```

In Code Mode, on a dedicated browser, run each block separately and serially.
The first reproduces the now-passed new UI suite. Legacy query tokens do not pin
source versions; the served host SHA identifies the snapshot.

```javascript
return await tools.playwright.browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-v35-ui.js' });
```

```javascript
return await tools.playwright.browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-rwd.js' });
```

```javascript
await tools.playwright.browser_navigate({ url: 'https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=C&v=35' });
return await tools.playwright.browser_run_code_unsafe({ filename: 'design/big-standby-motion-prototype/verify-c-gyroscope-https.js' });
```

### v35 completed agent/browser QA and unchanged limits

All v35 product/agent/browser QA gates are PASS: the 26-group / 60,219ms UI run,
240-case RWD, 40 backend tests, HTTPS A/C/D, final oracle and syntax/Git/votes/source
audits. No implementation or browser QA remains. The parent still performs its
final documentation-assertion audit and formal goal closure; this record does
not claim those session-management actions have already happened.

The shared C active clock still caps frames at 80ms: low FPS can extend recovery
beyond 7.5 wall-clock seconds. Pause, hidden, simple-home and projection freezes
are intentional. v34 removed reduced-motion functionality; explicit background
pause remains while short user-triggered UI transitions continue. Fixed CSS
pixels are not equal physical size across devices; browser-menu/site zoom and OS
magnification cannot be guaranteed blocked.

OPEN: all earlier real-phone/manual acceptance, C aggregation-onset and gyro-feel
checks. No Android implementation, real-device/GPU/power measurement or long
OLED test is claimed. Preview is unauthenticated, `no-store`, trusted-network
only; review writes are origin-restricted. Original studies 17/118/209 remain
unchanged; last verified review SHA-256 is
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Final parent audit confirmed that digest. No votes were edited by this lane.
GitHub TODO sync is not complete (`gh` unavailable); no issue, commit or push
was created.

## v36 D secondary-status marquee: PASS

All v36 agent/browser gates are complete. This ordinary request supersedes only
v35's fixed shortcut row; the completed pre-v36 goal and all v35 history above
remain unchanged. No v36 goal was created.

Preview: <https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=36>

### v36 request checklist

- [x] PASS: lower/right band has exactly two rows: moving semantic status first,
  decoration second. Actual type is 14.6667 CSS px (12px + 2pt), weight 700,
  normal `#f2f2f2`, without font shrinking or vertical ink clipping.
- [x] PASS: OFF immediately says `Press the broadcast button to start receiving`,
  without `Win + K`; waiting guides Windows `Win + K` then `MIRAX · 客廳`;
  connecting gives wait guidance; failure gives meaningful `#ff6b6b` retry copy.
  Projection hides the status while preserving the latest state.
- [x] PASS: failed at 7,499 active ms becomes waiting/normal white at 7,500;
  retry, OFF, theme and history leave no stale failure copy or red tint.
- [x] PASS: both lower rows move north-west by about (-11.3137, -11.3137) CSS px;
  upper geometry is unchanged. Visible length never decreases; most sizes gain
  about 32px, including 568x320. 390x844 correctly gains zero at full-width fit.
- [x] PASS: real RAF transform/glyph motion, continuous duplicate seams,
  controlled-seek PNG proof for every `W/i/n/+/K` glyph, and one partial shortcut
  becoming fully visible on native RAFs without another seek. A moving sentence
  or complete shortcut is not required to stay wholly visible at every instant.
- [x] PASS: 320x568, 390x844, 480x375, 568x320, 667x375, 896x592, 1440x1000,
  2560x720; bounded viewport/clip ink and genuine CSS paused/hidden/simple-home/
  projected/media gates. Native motion also runs under reduced-motion preference.
- [x] PASS: A/C geometry, copy/fades and simulated sensor/OLED behavior regressions;
  original X118/default and studies 17/118/209 preserved. Reviews/notes digest
  unchanged, isolated read-only checks, no real review POSTs or vote changes.

### v36 terminal verification evidence

Browser results below are actual parent runs on the current v36-served host,
not browser runs performed by this documentation lane.

| Current v36-host artifact | Terminal result |
| --- | --- |
| `verify-v36-ui.js` | `ok: true`; 5 groups PASS in 20,662ms; `failures = errors = blockedWrites = []`; `ownedContexts = closedContexts = 1`; before/after review SHA unchanged |
| Adapted `verify-rwd.js` | 240 cases, 10 navigation, 120 drawer and 120 brand checks PASS; `failures = []` |
| Adapted `verify-v29-controls-ui.js` | A/C/D symbols, C warning/automatic recovery, four edge cases, six drawer transitions and playback PASS |
| Full adapted `verify-v35-ui.js` | 26 groups PASS in 70,656ms; failures/errors/writes empty; owned/closed contexts 2; review SHA unchanged |

The 70,656ms row is a new v36-host regression run, not a replacement for the
historical v35 60,219ms result above. The five targeted groups cover immediate
OFF/ON/connect/fail/retry/OFF native ink; eight-size rows/geometry/PNG/seams;
partial-to-whole native shortcut entry; theme/history/projection and five CSS
pause gates; active-clock recovery and stale-episode rejection.

- Self-checks: `verify-v36-status.cjs` 56 checks plus three setup-failure cleanup
  cases; unchanged behavior 715 shots / 89,816 samples; D streams 90 shots /
  6,784 acceleration checks; C status, syntax and diff checks all PASS.
- The sole earlier fixture failure was an undefined `ensureAnimation()` call.
  The verified existing `syncMotion()` hook replaced it; all 7,500ms/gated/stale
  assertions remain intact. No product bug or new production API was involved.
- Independent eight-size x four-phase proof measured 24.339 CSS px/s. Minimum
  complete visible character counts, in checklist size order, were
  30 / 40 / 15 / 12 / 17 / 22 / 30 / 69. Actual DOM buttons select OFF to waiting
  and failed to OFF/white/no-shortcut immediately; real phase actions select failure.
- HTTPS cold load: valid TLS, actual 200 / `no-store`, title v36, version-36 E
  CSS/JS assets and original X118 default; no 404s, page errors or writes.
  Parent visually read OFF/waiting/failed phone and failed 568x320 PNG ink as
  clearly legible. Temp artifacts include `mirax-v36-D-off-phone.png`,
  `mirax-v36-HTTPS-D-waiting-phone.png` and `mirax-v36-HTTPS-D-failed-phone.png`.

### v36 integrity and remaining non-agent limits

`beforeHash = afterHash =`
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Original study SHA-256 prefixes remain 17 `f1893c12`, 118 `43ed31dc`,
209 `44678823`. Parent confirms DStreams and the publication suffix are
byte-identical to v35; host changes are only four metadata/concept/version-query
lines. Baselines remain under
`C:/Users/User/AppData/Local/Temp/opencode/mirax-v35-before-v36/`, including
`status-tests-` / `status-docs-` copies. Both full v35 document prefixes are intact.

Final product SHA-256 identifiers supplied by parent, recorded verbatim rather
than rehashed or LF-normalized by this documentation lane:

| File | Parent-supplied SHA-256 |
| --- | --- |
| `design/big-standby-prototype.html` | `2530f79f98a98bdbd4a30bf0c2b97f791318b91260eb0d813b594ea398279766` |
| `design/big-standby-e.css` | `263998f24bacb2267387f512e452bd2f418dcb4afe7c0cfeab2714bad1484ae7` |
| `design/big-standby-e-scene.js` | `d092f1a0b1a38e7a2323fb8bed4c2f0dc8cd0da935d6efa47bec5497ff8f8ba1` |

No agent QA remains open. The browser PASS does not close these separate limits:

- [ ] OPEN: manual real-phone/sensor/hardware acceptance, Android integration,
  GPU/performance, power and OLED longevity measurement. Existing manual checks
  above remain open; simulated/browser evidence is not real-device evidence.
- [ ] OPEN: deployment security/hardening review; TLS/cache-header checks are
  not a security audit. GitHub TODO synchronization was not performed.

No product/test edits in this final documentation pass, no Android/dependency
changes, no vote changes, and no commit, push or GitHub publication.

## v37 final acceptance

**All current v37 AGENT-QA CLOSED (PASS). Manual/hardware/Android/security gates remain OPEN.**
Unreleased v37. Final browser/HTTPS evidence below was supplied by the parent;
this documentation-only lane did not run a browser or capture screenshots.
These final results supersede intermediate v37 timings, not the preserved v35/v36 history.

### Verified requirements

- [x] Upper-left row three is the complete real status marquee: upper register / hex /
  status; lower comment / packet, two rows only, no duplicate status. Upper 64px,
  lower 52px and lower -16px local-normal offset retain the north-west diagonal.
- [x] Receiver is bottom-left and left-aligned; widths <=400px stack above the 84px
  dock. Nominal gap 12px, actual gap >=8px with retained OLED pure translation.
  Exact `RECEIVER` / `MIRAX · 客廳` remain 8px/16px, without shrinking/cropping;
  brand stays top-left, primary UI and A/C stay unchanged.
- [x] Four original X leaves plus diamond are opaque #090909 at x-plane opacity 1;
  white contours, original paths/projected vertices, default118 and sources 17/118/209 stay unchanged.
- [x] Latest text-layer product diff is exactly `#e-scene` z-index -1 -> 3:
  text/channel art 3 > overlay 2 > opaque X 1 > study-wave canvas 0, all confined
  inside `.generated-background` -5. Main UI/brand/receiver/dock/settings stay above.
  Boundary pulses rise with channel art; actual left-right study waves stay behind X.
- [x] Exact OFF/WAIT/CONNECT/FAIL/PROJECT copy, `calc(12px + 2pt)` = 14.6667px,
  weight 700, normal #f2f2f2/failure #ff6b6b, durations/speeds, lifecycle/gates and
  7,500 active-ms recovery logic stay unchanged. No new production API/clock or CSS border engine.

### Final parent evidence

| Gate | Final result |
| --- | --- |
| `verify-v36-ui.js` | **8 groups PASS, 28,996ms**; failures/errors/blockedWrites empty; contexts 1/1 closed. Eight receiver sizes/four-state geometry, 390/480/568 full-word PNG, native entry/seam/motion/state/gates/recovery covered. |
| `verify-v35-ui.js` | **26 groups PASS, 62,691ms**; failures/pageErrors/blockedWrites empty; contexts 2/2 closed. A/C, 480 glyphs, fonts/sizing/fades, reduced-motion parity and clock/gates covered; fixture epsilon only 1e-6ms, production unchanged. |
| `verify-rwd.js` | 240 cases, 10 navigation, 120 drawer, 120 brand checks PASS; failures empty. |
| `verify-v29-controls-ui.js` | Play/stop, warning auto-recovery, four edge cases, drawer/reversal/resize/playback PASS. |
| `verify-edge-padding-ui.js` | 144 cases / three themes PASS; errors empty; true 8px/16px type, safe glyph bounds/no overlap and OLED-calibrated gap verified. |
| `verify-e-theme-ui.js` | Six layouts, six lifecycle gates, five active tracks and four real `MiraxDStreams` edges PASS; seams and engine/CSS freeze/resume without catch-up PASS; legacy E resolves to D/default118. |
| Syntax / units / diff | Four modified browser fixtures and git diff check PASS; status 56 checks + three cleanup cases; behavior 715 shots / 89,816 samples; streams 90 shots / 6,784 checks PASS. |
| HTTPS cold load | A/C/D 200, no-store, TLS 1.3, title/assets v37; served CSS SHA matches local below. Zero JS errors, HTTP >=400 responses or writes; owned context closed 1/1. |

Native edge evidence: reduced/no-preference CSS timelines advanced 278.4/280.1ms
and the real engine .2784/.2801s; flow-speed ratio difference <3%. All four
bindings were true and same-shot position/alpha/transform moved, mismatches empty,
wall 818.8ms. Legacy adapters corrected px parsing, receiver/OLED expectations and
real native-edge bindings, not product fonts or fictional CSS edge animations.
Backend/Python checks were **not rerun** in this CSS-only phase.

### Actual PNG compositing and provenance

- **Text GREEN/RED:** at 1440x1000, all five Win + K ink quads fit the original NW
  face with 4px inset; fraction `0.08949416342412451` from 257 bounded seeks.
  Fixed changed pixels `[3,13,10,9,7]`; temporary root z-index -1 control
  `[0,0,0,0,0]`. State/inline styles/seek/viewport were restored in finally.
- **Opaque non-text interiors:** actual `drawDMotion(0)` / `drawDMotion(13)` and
  magenta qualification gave maxWaveDelta = maxProbeDelta = 0, strict limit <=3.
  Text/foreground-covered pixels were excluded only by actual qualification;
  coverage was never waived and outside-face changes prove waves remained live.

| Viewport | Qualified NE | Qualified SE | Qualified diamond | Outside-wave changed pixels |
| --- | --- | --- | --- | --- |
| 800x800 | 159 | Foreground-covered, not proof | 384 | 2,066 |
| 1440x1000 | 2,552 | 1,655 | 643 | 4,974 |

HTTPS OFF/WAIT at 1440, WAIT at 390 and FAIL at 568 had statusIndex 3, upper3/lower2,
lowerStatus 0 and correct white/red ink. The parent visually read these paused/seeked
illustrations; **native suites, not still images, prove motion**.
Artifact directory: `C:/Users/User/AppData/Local/Temp/opencode/`

- `mirax-v37-text-above-X-HTTPS-D-off-1440x1000.png`
- `mirax-v37-text-above-X-HTTPS-D-waiting-1440x1000.png`
- `mirax-v37-text-above-X-HTTPS-D-waiting-390x844.png`
- `mirax-v37-text-above-X-HTTPS-D-failed-568x320.png`

Preview: <https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=37>

### Integrity and remaining limits

| Final product | SHA-256 |
| --- | --- |
| `design/big-standby-prototype.html` | `4de79c8a86247f8febbcc800fa900f649e39efe87fda8cd85384e7eaee00d139` |
| `design/big-standby-e.css` | `7edb8ecf5eb49d495bcf8a9a3612cda66db842c2b6e2c08fd9e8a11227dc79ad` |
| `design/big-standby-e-scene.js` | `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e` |

Both principal suites kept before/after votes SHA-256
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Parent raw audit confirmed full 17/118/209/votes hashes unchanged, DStreams class /
publication suffix identical to v36, production inline scripts identical to the
pre-face-fill snapshot, one-line text-layer CSS diff and intact historical doc prefixes.

Retained raw archives under `C:/Users/User/AppData/Local/Temp/opencode/`:
`mirax-v36-before-v37`, `mirax-v37-before-face-fill`,
`mirax-v37-before-fixture-occlusion`, `mirax-v37-before-text-layer`,
`mirax-v37-before-final-docs` (this pass's two original documents).

- [ ] OPEN, non-agent acceptance: manual phone/sensor feel/hardware, Android integration,
  real GPU/performance/power and OLED longevity measurements.
- [ ] OPEN: deployment security/hardening audit; TLS/cache smoke tests are not security acceptance.

No product/test changes in this documentation pass. No GitHub TODO synchronization,
Android/dependency work, commit, push or publication is claimed.

## v38 final acceptance: whole X and safe-left receiver section

**All v38 AGENT-QA CLOSED/PASS. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**
Final browser/HTTPS evidence was supplied by the parent, not run or photographed
by this documentation lane. These results close the current v38 browser checklist.

- [x] Entire original opaque X masks crossing text: main SVG 4 / overlay 5 >
  marquee 3 > study canvas 0, inside generated-background -5 below all main UI.
  Original five paths/vertices/echo/contours/default118 and opacity 1/#090909 survive.
- [x] Original header `M0 68H1600` is white, non-scaling 2 CSS px behind X;
  decorative `M1390 0H1444V65H1390Z` is absent, not merely hidden.
- [x] Receiver retains safe-left/bottom, deep-dark backing, 2px #eee left rule,
  padding 0/0/0/10px and exact RECEIVER 8px / MIRAX · 客廳 16px. At <=400px it
  stacks above the unchanged 84px dock at nominal 12px/actual >=8px gap with
  retained OLED pure translation. No rightward/compact dodging or primary relocation.
- [x] Upper three rows register/hex/status, lower two comment/packet; status index 3,
  no lower duplicate. Exact copy/fonts/colors/durations/gates/7,500 active-ms clock
  and production logic stay unchanged apart from D placement metadata.

| Final parent gate | Verified result |
| --- | --- |
| `verify-v36-ui.js` | **8 groups PASS, 40,575ms**; 40 receiver cases = (8 sizes + 400/401) x four states; backing/gray-AA/both labels/left-rule PNG controls PASS; 257-seek full-word PNG includes 568x320 and 480x375 without dodging. Failures/errors/writes empty; context 1/1 closed. |
| `verify-v35-ui.js` | **26 groups PASS, 64,684ms**; failures/pageErrors/writes empty; contexts 2/2 closed; votes unchanged. |
| RWD / v29 controls | RWD 240 cases/10 navigation/120 drawer/120 brand PASS; v29 controls PASS. |
| Edge / E legacy fixtures | Edge 144 cases/three themes/errors empty; E six layouts/six gates/five CSS tracks/four actual native edges, mismatches empty, PASS. |
| Syntax / units / diff | Six fixtures syntax; status 56 + three cleanup; behavior 715 shots/89,816 samples; streams 90 shots/6,784 checks; diff check PASS. |
| HTTPS cold A/C/D | 200/no-store/TLS 1.3/title/assets v38; raw remote host/CSS match local. Errors/HTTP failures/writes empty; context 1/1 closed. |

Actual PNG compositing: fixed crossing-word pixels `[0,0,0,0,0]`, versus raised-text
control `[3,13,10,6,7]`, all inside the genuine NW face with 4px inset. Header has
58 inside/455 outside samples; removed-stub probe has 17, cornerAbsent/cornerClean
true. Test-only old stub peak 109 versus fixed 8; outside header 231 versus old thin
108. Wave/magenta deltas are all 0 (limit <=3), with qualified NE/SE/diamond coverage;
1,850/4,410 outside-wave pixels changed. Waves were not disabled. Receiver backing
coverage >=40/delta <=3, both labels >=2, real left-rule versus transparent-border
negative control PASS. Receiver AA uses computed minimum RGB x .6; white marquee
keeps >=200/delta >=16. Temporary styles/DOM order are restored in finally.

Parent captured and read four paused illustrations under
`C:/Users/User/AppData/Local/Temp/opencode/`:

- `mirax-v38-final-HTTPS-D-off-896x592.png`
- `mirax-v38-final-HTTPS-D-off-1440x1000.png`
- `mirax-v38-final-HTTPS-D-waiting-390x844.png`
- `mirax-v38-final-HTTPS-D-failed-568x320.png`

Native suites, not these stills, establish motion.

| Sealed v38 product | SHA-256 |
| --- | --- |
| Host | `ae1c32945ba7dd955a39ec10017b72dd987c13a353ad3fc56a707e22926a6411` |
| E CSS | `7edb8ecf5eb49d495bcf8a9a3612cda66db842c2b6e2c08fd9e8a11227dc79ad` |
| Scene | `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e` |

Votes before/after: `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Full 17/118/209/votes hashes, one original X group/symbol, production inline logic
normalized only for D metadata, unchanged E CSS/scene and complete v37 raw document
prefixes passed audit. Older raw archives remain intact; pending-doc originals are
in `C:/Users/User/AppData/Local/Temp/opencode/mirax-v38-before-final-docs/`.

- [ ] OPEN: manual phone/sensor/hardware, Android integration, GPU/performance/power/OLED longevity.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization; TLS is not security acceptance.

Backend/Python checks were not rerun. No dependencies, vote writes, commit or push.

## v39 final acceptance: matching brand rule and tighter receiver gap

**All v39 AGENT-QA CLOSED/PASS. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**
Final actual browser/HTTPS runs were supplied by the parent, not run by this
docs-only lane. These final results supersede intermediate floating-point false failures.

- [x] Product changes are only two CSS edits plus v39 title/assets/D description:
  winning D-only `.stage.v-D #standby-view .brand` adds 2px #eee left rule and
  padding 0/0/0/10. Container keeps its original safe-top/left anchor; content moves
  12px inside it and width gains 12px. Original logo 30x30 CSS px, name 18px,
  version 8px, version margin 7px, mark-name gap 8px and color stay unchanged.
  A/C retain border 0/padding 0; their shared branding references pass unchanged.
- [x] Receiver label margin 7px ->3px; exact 8px/16px fonts/family/left/bottom,
  existing rule/backing, <=400px breakpoint, 84px dock and OLED nominal 12px/actual
  >=8px gap stay intact. No X/header/wave/marquee/state/clock/runtime/study changes.
- [x] Test-only calibration in three brand guards uses abs(DOMRect dimension -30)
  <.01: real 30.000003814697266/29.999998092651367 pass; 29/31/nonfinite fail.
  Edge negates the positive combined predicate, so NaN fails. This corrects a
  fixture false failure, not a product resize; no production change was made for it.

| Final parent gate | Verified result |
| --- | --- |
| `verify-v36-ui.js` | **8 groups PASS, 40,293ms**; 40 section cases = (8 sizes + 400/401) x four phases. Failures/errors/writes empty; context 1/1 closed. |
| Actual spacing / PNG controls | At 896 waiting, receiver Range gap **6.796875px** versus old-margin-7 control **10.796875px**, exactly -4px with unchanged fonts/anchors. Backing 3,424 qualified pixels/max delta 0; RECEIVER/name ink 36/423; receiver/brand left-rule ink 33/27 versus border-color-transparent control with layout kept. |
| `verify-v35-ui.js` | **26 groups PASS, 71,112ms**; failures/pageErrors/writes empty; contexts 2/2 closed. |
| RWD / controls / edge | RWD 240 cases/10 navigation/120 drawer/120 brand; v29 controls; edge 144 cases/three themes/errors empty: **PASS**. |
| E native motion | **PASS**: six layouts/six gates/five CSS tracks/four real native edges, mismatches empty. Reduced/no-preference actual timelines 278.7/280.3ms, engine .2787/.2803 seconds; native-edge wall 676ms, all four bindings native, not fictional CSS edge animations. |
| Syntax / units / audits | Parent reran six-fixture syntax, status 56 + three cleanup, behavior 715 shots/89,816 samples, streams 90 shots/6,784 checks, diff/protected-hash checks: **PASS**. |
| HTTPS cold A/C/D | **PASS**: 200/no-store/TLS 1.3/title and E asset queries v39; errors/HTTP >=400/writes empty, one context closed. Brand border/padding A 0/0, C 0/0, D 2/10. Four viewport probes all show Range 6.796875/10.796875px, receiver margin 3px/type 8/16, logo 30 CSS px, stub count 0. |

Native full Win + K/257-seek SAT at eight sizes including 480x375/568x320 PNG,
entry/seam/lifecycle gates, whole-X mask/header/stub absence/wave and 7,500 active-ms
recovery all PASS. Receiver gray-AA calibration and white >=200/delta >=16 stay intact.
Votes before/after: `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.

Parent captured and visually read four paused illustrations under
`C:/Users/User/AppData/Local/Temp/opencode/`:

- `mirax-v39-final-HTTPS-D-off-896x592.png`
- `mirax-v39-final-HTTPS-D-waiting-390x844.png`
- `mirax-v39-final-HTTPS-D-waiting-320x568.png`
- `mirax-v39-final-HTTPS-D-failed-568x320.png`

Native suites, not these stills, prove motion. Final product digests:

| Product | SHA-256 |
| --- | --- |
| Host (fetched raw bytes match local) | `6a96407c3ab4e00ac5cc31b989c2171edecf8e599333b4fc906e8e95b054aea9` |
| E CSS | `7edb8ecf5eb49d495bcf8a9a3612cda66db842c2b6e2c08fd9e8a11227dc79ad` |
| Scene | `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e` |

Full 17/118/209/votes hashes, original symbol/X group and E CSS/scene remain unchanged;
runtime JS matches v38 except D description metadata. Complete sealed v38 and older
raw document prefixes remain intact. Baseline `mirax-v38-before-v39` is preserved;
pending-doc originals: `C:/Users/User/AppData/Local/Temp/opencode/mirax-v39-before-final-docs/`.

Preview: https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=39
Preview has no authentication and is for trusted networks only. HTTPS smoke checks
are not security-hardening acceptance.

- [ ] OPEN: manual phone/sensor feel/hardware, GPU/performance/power/OLED longevity and Android integration.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization.

Backend/Python were not rerun in this CSS-only phase. No dependencies, vote writes,
commit or push; no new authentication or security claims.


## v40 browser-pending acceptance

**v40 AGENT-QA remains BROWSER PENDING overall. Targeted parent browser PASS now covers the earlier C log correction, `A wide landscape name-to-eyebrow Range gap`, and `D upper band inner breathing`. A is the separate rerun of `verify-v35-ui.js` `cd5c6d3f0f116d6a3f67434757a9f232ad65494a8d696e6d4ff3cf0f84664692` at 25,639ms. D is 32 cases on unchanged `verify-v36-ui.js` `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`. The 27,233ms figure is the earlier combined run, in which A still failed the literal `auto` assert. It is not a D-only duration, and that combined run is not an all-PASS. Other A/D work, controls/footer, and the full v40 suite remain PENDING. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**

- Sun radius is 30. The moon uses `scale(38/27)` on the original crescent. With vertical room both centers drop `0.045` of the visible height; short layouts clamp clear of the brand and primary copy. Orbit timing stays 3.6s. A/C hit target stays 96 and D stays 84.
- A pill is `x=7 y=13 width=86 height=70 rx=35`. C and D artwork stay unchanged.
- Variant A `failed` shows the existing warning glyph in white inside the broadcast button. Recovery still uses the one 7500 active-ms clock.
- C keeps 10 styles, 8 presets, 24 body lines and 10px type. CORE and GRAV restore their original three-short log frames; other frames use compact machine states, events and unequal row lengths rather than a repeated short-to-long conversational recipe. No layout, orbit, lifecycle, header, badge or accent changes.
- C restores the sealed-v39 DISPLAY-ONLY values: `OPTICS: 421 THz`, `PHASE: <0.08%`, `DRIFT: -12ps/s`, `BUS: 0.42ms`, `APERTURE: 98.2%`, `REFRESH: 60fps`, `RF: -138dBm`, `COLLISIONS: 0%`. All appear in row one or two; BUS and REFRESH move from row three to row two. These are decorative prototype model values, not measured device/network telemetry; no sensor polling was added.
- Parent independently reran `node design/verify-d-streams.cjs`: PASS, 90 shots / 6,784 acceleration checks; C 8x3 rows, eight restored measurement rows, four all-short frames (at most 16 characters per row), three mixed frames (over 16 characters with a spread of at least five), six relative-length patterns, longest rows in positions 1/2/3, maximum 19 characters including `> `, and one rejected eight-preset conversational-pool negative control.
- Parent scope audit PASS: host content outside `cLogPresets` exactly matches the before-C snapshot; original 17/118/209 source checks, `node --check` for `verify-v35-ui.js`, `git diff --check`, and complete sealed-v39 document-prefix checks pass. Protected votes remain byte-exact SHA-256 `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
- The marquee window uses the shared scan axis (`--e-axis-start` / `--e-axis-end`). Unit checks cover 1920x1080 and 2560x720 against the old centered start. Glyph entry still needs the parent browser.
- The decorative diamond is no longer in the main SVG. `#d-corner-diamond` is a 26 CSS px overlay, hidden at width 600 or below and when it would overlap the receiver, dock, or review control. The five X paths stay unchanged.
- D broadcast fill is opaque `#262a28`. D center copy, broadcast glyph and upper status fade in about 180ms. Phase and broadcast update immediately.
- D brand padding, handle centerline and the full-width study field from the previous v40 pass stay.

### C log correction: targeted parent acceptance PASS

The parent served and SHA-256-checked the exact current `verify-v35-ui.js` (`313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`), then isolated its existing, unmodified C group with the probe/styles/sizes/frames/check/run harness. This was not the full 26-group suite.

- Targeted C browser group: **PASS, 6,427ms**. Seven sizes (`280x568`, `320x568`, `390x844`, `480x375`, `896x592`, `1440x1000`, `2560x720`) x ten styles x eight presets produced 560 actual dialogs, 1,680 actual single-line Range/font/glyph/bounds checks, 560 original measurement-row checks and 70 actual content pools. Body type stayed 10px; maximum text length was 19 characters. The group used active motion and actual `showRandomCHud()`, not hidden/stale fallback DOM. Errors, writes and HTTP errors were empty; one isolated context was created and closed. Real votes before/after matched the protected SHA above.
- HTTPS cold C preview: **PASS**, `https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=C&v=40`; actual 200 / no-store / TLS 1.3 / v40 title. Served host SHA-256 `b8b8c8950bb80dec55178c8d1e524210508afcb34631fb3198d3f171f5f5b17c` matched local bytes. JavaScript/console errors, writes and HTTP >=400 were empty; the second isolated context was closed. No certificate bypass. This preview is unauthenticated and for trusted networks only; HTTPS smoke is not security acceptance.

The parent captured, READ and visually inspected all six actual PNGs below, under `C:/Users/User/AppData/Local/Temp/opencode/`. All six showed 10px single-line body rows with the popup inside the viewport. The first four measured row-width vectors prove visible variation, the all-short exception and longest-row positions 1/2/3, rather than character-count simulation alone.

| PNG | Viewport / style / preset | Actual Range row widths (px) or inspected result |
|---|---|---|
| `mirax-v40-C-log-three-short.png` | 390x844 / Rail / CORE.FLUX.SECTOR-07 | 89.03125 / 89.03125 / 89.03125 |
| `mirax-v40-C-log-longest-first.png` | 390x844 / Rail / SPECTRAL.BUS.DAEMON | 108.109375 / 89.03125 / 57.234375 |
| `mirax-v40-C-log-longest-second.png` | 390x844 / Rail / ANISOTROPIC.SHELL | 69.953125 / 101.75 / 82.671875 |
| `mirax-v40-C-log-longest-third.png` | 390x844 / Rail / NEURAL.RELAY.CLUSTER | 89.03125 / 76.3125 / 120.828125 |
| `mirax-v40-C-log-narrow-index.png` | 280x568 / Index / KINETIC.COUPLER.REG | Single-line body; popup inside viewport |
| `mirax-v40-C-log-desktop-metric.png` | 1440x1000 / Rail / CHRONO.DIAGNOSTICS | Single-line body; popup inside viewport |

PNGs are static illustrations, not motion proof. The narrow Index check accepts the C popup only: existing narrow status/header wrapping is unrelated and unchanged, so this does not claim the entire 280px layout is perfect. At that documentation follow-up, only this C correction had targeted browser PASS. The later named A and D group results are in the final section. Controls/footer, the full 26-group suite, and the remaining six-fixture whole-v40 browser gates remain PENDING. This documentation-only follow-up did not run a browser or change product/tests.

### A wide landscape name-to-eyebrow gap: source fix, TARGETED_PARENT_BROWSER_PASS

Wide theme A kept `.device` at `calc(49% - 151px)` while `@media (orientation: landscape)` places `.stage.v-A .center` at `43%`. That 6% viewport-height anchor split shrinks the actual Range gap (`#status-eyebrow` top minus `.device-name` bottom) as the screen gets taller. Parent RED, same vertical geometry in off/waiting/connecting/failed: 1920x900 = 17.4140625; 1920x1080 and 2560x1080 = 6.6171875; 2560x1440 and 3440x1440 = -14.9921875; 3840x2160 = -58.1796875. Portrait 390x844 stayed 48.9140625 on `calc(49% - 127px)`. 568x320 keeps the device hidden.

The landscape block now also sets `.stage.v-A .device { top: calc(43% - 111px); }`. The shared 43% anchor plus 111px is height-independent and targets a Range gap of about 31.4px (acceptable 24..40). `.center` stays `43%`. Portrait, short-height, C, and D rules are unchanged. Celestial placement still uses the brand and the center bounds, not the device.

New durable group in `verify-v35-ui.js`: `A wide landscape name-to-eyebrow Range gap`. It measures that Range at 1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, and 901x900 across four phases; checks 16px/9px type and viewport bounds; keeps the center and title fixed while an inline `calc(49% - 151px)` negative control restores the 1080 and 1440 RED gaps; then restores the device inline style. Each phase is its own evaluate and awaits the production fade `finished` promise plus two frames, at most 8 passes, before it accepts a clear map and fresh title/eyebrow. Portrait 390x844, hidden 568x320, and C/D computed baselines are in the same group. This child ran `node --check` on the fixture and `node design/verify-d-streams.cjs` (90 shots / 6,784 acceleration checks, C corpus intact). It did not run a browser. That write-up was still PARENT_BROWSER_PENDING. The separate parent rerun in the final section is TARGETED_PARENT_BROWSER_PASS.

The earlier C log targeted PASS remains valid for the prior fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`. This edit changes `verify-v35-ui.js`, so that hash is not the post-gap file. Other A/D work, controls/footer, and the full v40 suite stay PENDING. Manual phone/sensor/hardware, Android integration, and security remain OPEN.

### D upper band inner breathing: source fix, TARGETED_PARENT_BROWSER_PASS

The upper marquee specified `--e-channel-height: 64px` with `border-block` of 1px each side. Under the page `border-box` rule the content box is 62px, and rows `16+16+24` plus two `3px` gaps fill that 62px, so the first code line sat on the upper edge. Only that custom property is now `80px`. `align-content: center`, the offset, the -45 degree rotation, row sizes, gap, copy, durations, the lower `52px` band and the X geometry stay. The extra 16px is split by centering into about 8px above the first row and 8px below the status row. Padding inside a 64px band was not used, because that would shrink the content box and clip the third row.

Parent pre-landing negative control and isolated candidate, not written into product and not a landing PASS: 320x568, 390x844, 896x592, 1920x1080, 2560x720. Live upper height was 64 everywhere. Old 64 local inset was 0 at the first row and 0 at the last row bottom. Isolated inline 80 was 8/8. Row heights stayed 16/16/24 and the gap stayed 3. Screen position of the three rows had max delta 0. Upper center float delta was <= 0.000123px. Lower band bbox matched value for value. Errors and writes were empty, context 1/1 closed, and the inline style was restored.

Durable group in `verify-v36-ui.js`: `D upper band inner breathing`. Eight existing sizes x off/waiting/connecting/failed. It requires the oriented row quad and `offsetTop` inset in 7.5..8.5, a non-empty first-row Range, row boxes 16/16/24, gap 3, lower height 52, type 11px and status 12px+2pt/24, durations 240s/360s, and X at z-index 4 above the marquee at 3. An inline `--e-channel-height: 64px` control must bring the inset back to about 0 and fail the 8px contract, while row screen positions, the upper center and the lower box stay within 0.05px; `finally` writes back the exact style attribute saved before the override. The check requires that same string, null only when the controller had not set one, plus the restored 80px band and 8px inset. The existing eight-size group now expects `upperHeight === 80` with center shift still under 0.6 and `lowerHeight === 52`. `v35BandLayout` is unchanged. `verify-e-theme-ui.js` only changes its hard `band === 64` caller to `band === 80`. Win + K entry, vertical clip and opaque-X paint checks in the existing v36 groups are untouched. This child ran `node --check` on `verify-v36-ui.js` and `verify-e-theme-ui.js`, plus `node design/verify-d-streams.cjs`. It did not run a browser. That write-up was still PARENT_BROWSER_PENDING. The 32-case parent pass is in the final section, and the fixture was not changed after that pass.

The C log targeted PASS remains valid for fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83` only. `verify-v35-ui.js` is still the A-gap edit, so that hash is not the current v35 file. At that write-up the A wide gap was still PARENT_BROWSER_PENDING. Other A/D work, controls/footer and the full v40 suite stay PENDING.

Parent isolated run of only these two new groups took 4958ms on SHA-verified served bytes. Both RED results were fixture assumptions, not product failures. A called `animation.finish()` and read the copy before `onfinish` wrote the next text. D required `style === null` while the stream controller already stores `--e-top-*` and `--e-bottom-*` position and alpha on the channel. The harness now awaits each phase's real `finished` promise plus two frames, and compares the restored style attribute to the saved string. Product bytes are unchanged. That 4958ms run was a fixture failure, not a landing PASS.

### Isolated HTTPS geometry probe: not a durable-group PASS

Parent ran an isolated HTTPS product probe on the served bytes. Cold A/D responses were HTTP 200, `no-store`, TLS 1.3, title v40. Served host `a46676c6670a044df0e0a63e0189a3ec7e515e90fb45e61929c540a75da4c9b6` and CSS `26694084ca81ae2f61d1b56024b21bdeb99d43a125ee07c46bc31954cd1dbfbf` matched local. Errors, writes, and HTTP status >= 400 were empty. Context 1/1 closed. This probe is static geometry. It is not a PASS of `A wide landscape name-to-eyebrow Range gap` or `D upper band inner breathing`.

A used paused final positions, so it is not native fade evidence. Eight wide viewports (1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, 901x900) times off and waiting all measured Range gap `31.4140625`. Sun and moon did not overlap the device. The smallest wide clearance was `32.720px` (901x900 moon). Portrait 390x844 stayed `48.914`. 568x320 kept the device hidden.

D at 1920x1080, 390x844, and 568x320 measured upper height 80, first and last row insets 8/8, row boxes 16/16/24, and gap 3.

Parent captured six PNGs under `C:/Users/User/AppData/Local/Temp/opencode/` and READ them. IHDR sizes below are the viewports. A returned screenshot field of height 80 is the upper `bandHeight` override, not the viewport.

| PNG | IHDR | bytes | SHA-256 |
| --- | --- | --- | --- |
| `mirax-v40-final-A-gap-1920x1080-off.png` | 1920x1080 | 225129 | `4ab1867e22680301e27a7140d78b956e4634359f205db6bc6f2562ea5058823e` |
| `mirax-v40-final-A-gap-2560x1440-waiting.png` | 2560x1440 | 455492 | `2a0e439d80e9ff2d80972ebae1adced603cd355a91b7c8c7de3135f5789a806e` |
| `mirax-v40-final-A-gap-390x844-off.png` | 390x844 | 87278 | `46229c72aa0279c7e88f85151f175e156adc9fc3b11b690257c8289b44cf163b` |
| `mirax-v40-final-D-breathing-1920x1080.png` | 1920x1080 | 247734 | `ecdc978526e2a7531635bb15f48cd9920a4d5111c350540f4ec4825d88fc8929` |
| `mirax-v40-final-D-breathing-390x844.png` | 390x844 | 61636 | `d08c2892e6b71ee1f19f7785d8cd7f19a0039b5fa24dc6c259e35b8fbde8eed7` |
| `mirax-v40-final-D-breathing-568x320.png` | 568x320 | 48792 | `fb18cd914295f392147de3490a86ffbc8b507bb3981083ed8ef93a82caac560f` |

The first durable-group run stayed RED for the two fixture reasons already corrected: A read copy before `onfinish`, and D required `style === null`. Those groups are not closed by this probe. That rerun request was superseded by the 27233ms run recorded below: the D group passed inside that combined run and its fixture is not replayed; the A group was still open. Product bytes stay the audited pair: host only the scoped A landscape anchor plus its why comment, CSS only the D height 64 to 80 plus its why comment. Scene, votes, C pool, and the sealed v39 prefixes are unchanged. The named groups were still open at this probe.

### E theme native motion: full fixture PASS, not an A/D group close

Parent ran the complete `design/big-standby-motion-prototype/verify-e-theme-ui.js` on its own. The only assertion change in that file is `band === 80`. Result PASS: 6 layouts, 6 lifecycle gates, 5 CSS tracks, and 4 native `MiraxDStreams` edges. Both `reduce` and `no-preference` advance native toward the upper right, and the seam stays continuous. Original X, opaque faces, layers, brand padding, and the receiver still pass. Legacy E to D plus the actual broadcast and drawer actions pass. The fixture closed its owned context in `finally` and wrote no files. Physical device was not tested. Fixture SHA-256 stays `facc095cd8c3bc9bd2514e70c11da5d29fcba4888245bb04b768a8668ab36f39`. This E run by itself does not close `A wide landscape name-to-eyebrow Range gap` or `D upper band inner breathing`. Their targeted parent results are in the final section.

### Combined 27233ms run: D 32 cases passed while A failed the literal auto assert

Parent targeted rerun took 27233ms for the combined A and D groups. `D upper band inner breathing` passed all 8 sizes x 4 phases: first and last insets 8/8, the inline height 64 control returned inset 0, row delta 0, upper center delta <= 0.000123, and the original inline style was restored. `verify-v36-ui.js` stays `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`. That file is not edited again and the parent does not replay it.

`A wide landscape name-to-eyebrow Range gap` passed all 32 wide phase checks, 4 portrait phases, 4 short-height phases, and both old `calc(49% - 151px)` negative controls, including style restoration and a fresh off copy. It failed only the last D baseline. At 1920x1080, `getComputedStyle(device).top` was `1006.22px` and the center was `507.594px`. The center is 47% of 1080. The device value is the used pixel length for stylesheet `top: auto` with `bottom: var(--edge-bottom)`, not the literal word `auto`. Product bytes were not changed.

The D baseline no longer asks for the word `auto`. It sets inline `top: auto` and requires the used rect top, bottom, and left to stay within 0.6px, with the center still at 47%. A leaked `calc(43% - 111px)` would move that rect. The same check then sets that A offset inline, requires the used top to resolve to it and move by more than 40px, and `finally` writes back the exact style attribute saved before the override. C stays hidden on `calc(49% - 151px)` with center 49%. The A copy waits and the old 49% controls are unchanged. That A failure was the fixture reading the literal word `auto`. The separate rerun after the used-top change is TARGETED_PARENT_BROWSER_PASS below. Other whole-v40 gates stay pending.

### A and D named groups: TARGETED_PARENT_BROWSER_PASS

These two results close only the named groups. Whole v40 stays open. Other A/D work, controls/footer, and the full suite remain PENDING. Manual phone/sensor/hardware, Android integration, security, and GitHub TODO synchronization remain OPEN.

`A wide landscape name-to-eyebrow Range gap`, exact fixture `verify-v35-ui.js` `cd5c6d3f0f116d6a3f67434757a9f232ad65494a8d696e6d4ff3cf0f84664692`: TARGETED_PARENT_BROWSER_PASS, 25,639ms. Eight wide viewports (1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, 901x900) times four fresh phases (off, waiting, connecting, failed) is 32 cases. Every Range gap was 31.4140625. Fonts stayed 16px and 9px. Center stayed 43%. Portrait 390x844, all four phases, gap 48.9140625. 568x320 hid the device in all four phases. Inline `calc(49% - 151px)` restored the old gaps 6.6171875 at 1920x1080 and -14.9921875 at 2560x1440, then restoration returned 31.4140625. The main heading and center stayed put. C stayed hidden on the old 49% device rule. D stayed at 47%. The `top: auto` used-rect counterfactual and the leaked A-offset control passed, and `finally` restored the exact style. Native fades were awaited with the existing `finished` promise plus two animation frames. The group does not call `animation.finish()`. Failures were null. Errors, writes, and HTTP errors were empty. One context was created and closed. Votes before and after were `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.

`D upper band inner breathing`, exact unchanged fixture `verify-v36-ui.js` `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`: TARGETED_PARENT_BROWSER_PASS, 8 sizes times 4 phases, 32 cases. Upper height was 80, with the left and right axes unchanged and top and bottom insets 8. The inline height 64 control returned inset 0. Rows stayed 16/16/24, gap 3, fonts 11 and 14.6667, status line 24, upper duration 240s, lower duration 360s, and the lower band 52 with 2 rows. Opaque X stayed at z-index 4 above `#e-scene` at z-index 3. The counterfactual ran 32 times: every row delta was 0, center delta was <= 0.0001230203px, the lower box was unchanged, and the exact original inline style was restored. These 32 cases passed inside the earlier combined run of 27,233ms, while the A group in that same run still failed the old literal `auto` assert. That clock is not a D-only duration, and the combined run is not an all-PASS. The D fixture was not edited after the pass, so this evidence is reused and the group was not rerun.

Harness corrections did not change product behavior. A waits for the production fade `finished` promise plus two frames instead of calling `animation.finish()`. D compares the restored style attribute with the saved string instead of requiring `null`. The last D baseline checks the used rect of `top: auto` instead of the literal word `auto`.

Still valid, with product bytes unchanged since those runs: `verify-e-theme-ui.js` `facc095cd8c3bc9bd2514e70c11da5d29fcba4888245bb04b768a8668ab36f39` passed 6 layouts, 6 lifecycle gates, 5 CSS tracks, and 4 native `MiraxDStreams` edges, for both motion preferences, and closed its owned context. That fixture is the native motion proof. HTTPS cold A/D was HTTP 200, `no-store`, TLS 1.3, title v40. Served host `a46676c6670a044df0e0a63e0189a3ec7e515e90fb45e61929c540a75da4c9b6` and CSS `26694084ca81ae2f61d1b56024b21bdeb99d43a125ee07c46bc31954cd1dbfbf` matched local. Errors, writes, and HTTP status >= 400 were empty, and the context closed. Scene stays `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e`. The paused HTTPS geometry probe passed 20 A cases (8 wide viewports plus portrait plus short height, times off and waiting) with no art/device overlap. The smallest wide clearance was 32.720px. Three D layouts measured insets 8/8. Parent READ and visually reviewed six PNGs under `C:/Users/User/AppData/Local/Temp/opencode/`: `mirax-v40-final-A-gap-1920x1080-off.png`, `mirax-v40-final-A-gap-2560x1440-waiting.png`, `mirax-v40-final-A-gap-390x844-off.png`, `mirax-v40-final-D-breathing-1920x1080.png`, `mirax-v40-final-D-breathing-390x844.png`, `mirax-v40-final-D-breathing-568x320.png`. Those paused stills are not native animation proof.

Parent audited the product diff as one A landscape anchor plus its why comment, and one D upper height change from 64 to 80 plus its why comment. Every other host byte, the C pool, and the other CSS bytes match the pre-fix snapshot. `node design/verify-d-streams.cjs` had already passed at 90 shots and 6,784 acceleration checks with the C corpus intact. No product change followed, so that unit result is reused and was not rerun for this documentation edit. The earlier C log targeted browser PASS at 6,427ms stays valid for historical fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`. It was not rerun on the newer v35 fixture. The fixed C pool and C styles were not touched.

This documentation edit did not run a browser or a unit suite.

- [ ] OPEN: manual phone/sensor/hardware, GPU/performance/power/OLED longevity and Android integration.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization.

No dependencies, vote writes, commit or push.

## v41 native ScreenPhase preview & user confirmed decisions

**Targeted v41 native-phase parent browser QA PASS (7 groups, 65,890ms); whole-v40/v41 other gates remain PENDING.**

### USER CONFIRMED decision log Q1–Q7 (dated 2026-10-31)
*Supersedes earlier research recommendations regarding respecting system motion, not keeping awake, deferring media, and simple default.*

1. **Q1 (Main Homepage)**: Big Standby is the new full-window animated main homepage. The legacy native Dashboard is also maintained; browser simple view is a lightweight web illustration, not an implemented copy of the maintained native Dashboard.
2. **Q2 (Design Faithfulness)**: User confirmed direction is strictly "Faithful to the design" (『忠於設計』). Explicit all-device pixel, RWD, and typography equivalence tolerances remain UNRESOLVED; this is not approved as an exemption from pixel identity.
3. **Q3 (Native ScreenPhase Source of Truth)**: Real native `ScreenPhase` (`app/src/main/java/me/trinitrix/mirax/session/SessionTypes.kt`: FROZEN, READY, ARMING, ADVERTISING, CONNECTING, CONNECTED, WIFI_PAUSED) is the authoritative source of truth. The prototype 7,500 active-ms timer is strictly a legacy demo simulator, NOT network recovery logic. Missing native phases (ARMING, WIFI_PAUSED, FROZEN) are added to the browser prototype preview.
4. **Q4 (Animation Continuity)**: Big Standby ambient motion continues even if OS reduced/disabled motion is set (user does not want a static Big Standby). Lifecycle pausing when hidden, backgrounded, or actively projecting is preserved.
5. **Q5 (Product Drawer Scope)**: All ratings, Notes, inspectors, and manual connection simulation tools belong solely to prototype QA and are excluded from the formal native product. The native drawer will be genuine App settings (WYSIWYG).
6. **Q6 (Wake & Brightness Policy)**: Foreground Big Standby keeps the device awake and the app must not actively dim screen brightness. This is application-level display policy and does not claim to override OS policies, thermal throttling, or hardware protections. Web Wake Lock is not added to the web prototype.
7. **Q7 (Custom Media Scope)**: Custom images, videos, and GIFs are required within the native port scope, not deferred.

*Note: This preview extension is not a complete Android spec, ADR, or native implementation. Precise error taxonomy, OS thermals, and device test gates remain OPEN.*

### Parent RED findings & test harness/prototype corrections (dated 2026-10-31)
Parent execution of `verify-v41-native-phases-ui.js` (5,310ms, votes SHA `7c5f...` intact) revealed 4 test harness timing/assertion discrepancies and 2 prototype issues:
- ON -> ARMING and phase checks read copy during in-flight 180ms CSS fades. Fixed in harness by awaiting `mainCopyChanges.animation.finished` and bounded RAF loops.
- Failure fixture: C startCFailure anchor is set on glitch finish (250ms). Fixed in harness by awaiting real glitch finish without changing production clocks.
- C RWD: Checked `#status-title` which is intentionally hidden in C. Fixed in harness by measuring actual visible `#c-status-word`, `#c-standby-time`, connection labels, non-overlap, and computed fonts (>= 12px for status word, >= 20px for title).
- Prototype startup assertion: Fixed `console.assert` on startup to validate non-off initial phases and intents without throwing errors on valid queries.
- Prototype CSS active status selectors: Replaced blanket `[data-broadcast="false"]` with canonical phase-based selectors (`.stage[data-phase="..."]`) so that `FROZEN` renders exactly 1 active message whether intent is retained or false, and new native phase tracks run when active in Theme D.

### Parent HTTPS cold-load audit (PASS, 2026-10-31)
Remote parent HTTPS run verified 9 cold-loaded pages (3 new states ARMING, WIFI_PAUSED, FROZEN x 3 themes A, C, D at 1440x1000):
- Remote production code verified identical: host `4e7bb436...`, css `36f62cf0...`, scene `648d5b9f...`.
- Exact native phase mapping, title copy, C status word (`ARMING`, `WAIT_WIFI`, `UNAVAILABLE`), disabled button in FROZEN verified.
- Theme D: exact 1 active status message running, no Win + K invitation, 0 console/page errors, 0 writes, 1 context closed.
- Visual inspection of 9 screenshots in `Temp/opencode/mirax-v41-{A,C,D}-{ARMING,WIFI_PAUSED,FROZEN}.png` PASS. Remote ratings `7c5f...` intact.

### Parent actual browser suite execution (PASS, 2026-10-31)
Parent executed `verify-v41-native-phases-ui.js` (`9960b45...`) in 65,890ms: **PASS ALL 7 groups, 0 failures, 0 errors, 0 blocked writes, 1 owned context closed**:
- 13 cold queries PASS without console errors or manual clicks.
- 24 phase checks (3 variants x 8 phases) PASS: D projecting hides ambient row with 0 visible ambient messages; non-projecting D has exact 1 actually visible message running with advancing transform after CSS transitions; unready phases show no Win + K invitation.
- 72 RWD cases (8 sizes x 3 new states x A/C/D) + CONNECTED projection across 8 sizes PASS without font downscaling or viewport overflow.
- Interactive UI picker + explicit confirmation ACK verified through settings drawer tools; ON -> ARMING observable and stable >400ms without fake auto-ack.
- Live focus transition: enabled broadcast toggle focus falls back to `#settings-handle` on FROZEN while background motion stays active.
- Glitch cancellation: entering ARMING, WIFI_PAUSED, or FROZEN cancels failure glitch, clears 7.5s recovery anchor, and blocks late callback resurrection.
- Before/after protected votes SHA-256 `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036` intact.
- Additional parent checks: `verify-e-theme-ui.js` PASS (6 layouts, 6 lifecycle gates, 5 CSS tracks, 4 native DStreams edges); compatibility probe PASS (simple 9 cases, real PNG `loadFile` media 9 cases [image coverage only, not video codec/GIF/hardware proof], D new phase pause/hidden/resume 6 cases, theme history to C preserves FROZEN).

### Preview URL Parameters & Interactive Review Flow
- Preview query: `?phase=ARMING|WIFI_PAUSED|FROZEN&variant=A|C|D&v=41` (query parameters do NOT pin an immutable artifact version; served host SHA `4e7bb4363dcbcbcb36cc72af6966a8148ea99f1895d43a1842a9c37bb7c81985` identifies the snapshot).
- Prototype review flow: open drawer via `#settings-handle` -> enable `#show-prototype-tools` -> pick phase via `#native-phase` -> click `#confirm-service-ready` to transition from ARMING to WAITING.

### Checklist: v41 Native Phase Prototype (TARGETED QA PASS)
- [x] Node unit test `verify-native-phases.cjs` passes all 7 native mappings, phase guards, query parameters, intent retention, and stale recovery cancellation.
- [x] Node suite `verify-d-streams.cjs` passes all status copy, duration, and selector checks.
- [x] Standalone browser fixture `verify-v41-native-phases-ui.js` updated and verified PASS (65,890ms, 7 groups, 0 failures/errors/writes).
- [x] Parent browser audit: 24 phase checks: 7 native + legacy failed x A/C/D at matrix viewport; 72 RWD cases: ONLY 3 new states x A/C/D x 8 sizes; 8 CONNECTED projection checks.
- [x] Parent browser audit: cold URL parameter initialization (`?phase=ARMING`, etc., 13 cold queries).
- [x] Parent browser audit: OFF -> ON -> ARMING observable before explicit confirmation (>400ms stable).
- [x] Parent browser audit: unready states never display Win + K invitation.
- [x] Parent browser audit: FROZEN disables broadcast button and moves focus to settings-handle without stopping motion.
- [x] Parent browser audit: entering new native phases clears stale 7.5s failure episodes.
- [x] Parent browser audit: protected `review-votes.json` SHA-256 untouched before and after execution (`7c5f...`).

*Separate non-agent / hardware limits remain OPEN: Android native implementation, Gradle build, physical devices, thermal/power/OLED measurement, deployment security, and GitHub sync.*

### ARMING copy refinement (TARGETED_SHORT_COPY_PARENT_PASS, 2026-10-31)
Per user request, shortened ARMING display copy: title `啟動中`, eyebrow `STARTING`, detail `接收啟動中，尚不可連接。`, hint/marquee `Starting · Not ready`. Option text updated to `ARMING · 啟動中`; cStatus stays `ARMING`. Marquee `--e-status-duration` adjusted 128s -> 68s. Parent actual browser verified latest fixture `4d7cc...` in 64,908ms (all 7 groups PASS, 0 errors/writes/closed 1, votes `7c5f...` intact). Actual HTTPS UI path (handle -> show-prototype-tools -> native-phase -> select ARMING -> ack) PASS at 390x844 and 568x320: title `啟動中`, eyebrow `STARTING`, font 40CSSpx unchanged, no clip, actual D status speed 25.323529 CSS px/s (not derived 24.47). Four HTTPS cold PNGs (A/C/D 390 + D 568) visually PASS; C ARMING code and short secondary correct. Remote artifacts exact host `974399...`, css `d6648...`, scene `e870...`. No Android or whole project readiness claimed.

### WIFI/FROZEN copy and FROZEN info button (TARGETED_FROZEN_DETAILS_PARENT_PASS, 2026-10-31)
WIFI_PAUSED title `Wi-Fi 已關閉`, detail `Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播`, C hint `Wi-Fi is off`. D marquee `Wi-Fi is off · Please turn on Wi-Fi to broadcast`. `--e-status-duration` stays 144s (48/40 character-ratio estimate; CSS unchanged). Parent measured D WIFI 26.208333 CSS px/s and FROZEN 26.5, both in 18..32. FROZEN title and summary `接收不可用`; C hint `Receiver unavailable`; C right short code stays `UNAVAILABLE` with responsive wrapping. Parent NO-WRAP candidate stayed temporary in the browser and was not applied, because 320 overlaps the receiver hint. D marquee stays `Receiver unavailable · Check settings` at 112s. FROZEN existing controls are enabled info buttons (`role=button`, no `aria-checked`, `aria-haspopup=dialog`, controls `#receiver-details`, label `查看接收不可用詳情`): an info action, not a broadcast switch and not a 7.5s retry. `toggleBroadcast()` still returns immediately while frozen. This supersedes only the earlier note that FROZEN disables the broadcast toggle. `#receiver-details` is a prototype explanation, not actual Android error detection. A/C warning colors reuse FAILED; D status row `#ff6b6b`. No FAILED glitch or 7.5s recovery on FROZEN. A/C control hit 96x96; D hit 84x84, same as before. Glyph color: A white, C red, D red.

Parent Node: host/scene/fixture syntax, native VM, `verify-v35-behavior`, `verify-c-status`, `verify-v36-status` (60 checks), `verify-d-streams` PASS; `git diff --check` PASS. Fixture `86f1a3961359d954c640512b5d2ba72aa8fa43fd4db18d320a817bcb3c13b25a` 8 groups PASS 78,491ms: 13 cold queries; 24 phase checks; 72 RWD cases (ARMING, WIFI_PAUSED, FROZEN x A/C/D x 8 sizes only; not all 7 phases x 8) + 8 CONNECTED projection; stale glitch 3 targets; detail group 3 themes / 6 intents / 8 popup bounds / 1 simple-settings entry; click close and Escape restore focus; role and `aria-checked` restore; no intent mutation; persistent warning colors; no FAILED timer. 0 errors, 0 writes, 1 owned context closed. Votes `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036` identical before and after. HTTPS remote hashes exact: host `edf7c95daafdb2f0203508af37aa48431b70d5647beff5563f85fe73ab942df7`, css `a6b094a26a146cc48f4405257e5fb9ea3d28f4708d147cabcf8069788429d198`, scene `057d851d15cc30b1788f2ce3df519915b5010c34bec9888debeb73647f6cc848`. C hints `Wi-Fi is off` and `Receiver unavailable` exact. D WIFI marquee exact. Chinese titles `Wi-Fi 已關閉` and `接收不可用`. 6 cold C/D/A text and warning PNGs at 390x844 parent-read, plus modal C 320x568 and D 568x320 parent-read: 0 horizontal overflow, close focus returns, phase frozen, intent false unchanged, layout offsets before and after exact. PNGs `Temp/opencode/mirax-v41-frozen-details-{A,C,D}-{WIFI_PAUSED,FROZEN}.png` and `Temp/opencode/mirax-v41-frozen-popup-{320,568}.png`. 0 errors, 0 writes, 1 owned context closed.
### Five state-motion requests (TARGETED_STATE_MOTION_5ITEMS_PARENT_PASS, 2026-10-31)
A day/night rise root cause: `#standby-view` has `translate3d(burnX, burnY, 0)` and `#landscape` does not. `layoutCelestial()` measured `.brand` and `.center` from `stage.getBoundingClientRect().top`, so `burnY` entered the clamped Y at 667x375, 2560x720, and 915x412. A 0.01 to 0.05px change added `.a-layout-reset` (`transition: none`), forced reflow, and snapped `.a-sun-orbit` / `.a-moon-orbit` in one frame (`midCount=0`, `getAnimations().length=0`). The measurement now uses `#standby-view` top, or stage top when that view has no box, and writes the transform and edge variables at 0.01px so subpixel noise does not abort the rise. The 3.6s duration, sun r=30, moon scale 38/27, anchors, and pause policy are unchanged.

ARMING detail is exactly `接收啟動中`. Title `啟動中`, eyebrow `STARTING`, and hint `Starting · Not ready` are unchanged. The A connecting hint is blank, and the existing blank-target fade removes it without reserved space. Shared `phases.connecting.detail` stays `模擬發送端已選取此裝置。` for C. Title `正在連接` and the C/D hint `Connecting · Please wait` stay.

The C gather target is now `state.broadcast && state.phase !== 'wifi-paused'`. `big-standby-c-particle-assembly.js` is byte-identical: 6s gather, 8s disperse, and the owned `particleTime` clock. FROZEN particle policy is unchanged. WIFI_PAUSED still keeps broadcast intent and does not start the 7.5s failure recovery.

C and D WIFI_PAUSED use the existing warning glyph at `#ff5757` on the shared dock symbol. The control stays `role=switch`, `aria-checked=true`, label `Wi-Fi 已關閉，停止廣播`. A stays stop or play from intent. Click still calls `toggleBroadcast()` and returns to READY with broadcast false. This is not the FROZEN info action.

Pre-edit Node RED was 4: the long ARMING detail, the A connecting sentence, C Wi-Fi symbol `stop`, and Wi-Fi `setBroadcast(true)`. The fixture now expects 10 groups, including A burnY rise at 667x375, 2560x720, and 390x844. TARGETED_STATE_MOTION_5ITEMS_PARENT_PASS. Parent browser: all 10 groups of `verify-v41-native-phases-ui.js` (`d38e1847...`) PASS in 99,394ms, 0 failures, 0 console/page errors, 0 blocked writes, votes hash `7c5f032...` intact. HTTPS A celestial burnY check (`particleTime = 1700`) over 12 viewports: `brokenCount=0`, exact anchor, active 3.6s transition (`mid >= 4`, `anims >= 2`). 4 HTTPS PNGs inspected: A ARMING, A CONNECTING, C WIFI_PAUSED, D WIFI_PAUSED. Android, device, power, and OLED stay OPEN.

### A arming hint blank, C status no-wrap, and C particle samples (TARGETED_C_PARTICLE_SAMPLES_PARENT_PASS, 2026-11-01)
1. A arming bottom hint: `.v-A[data-phase="arming"] .connection-hint:not(.a-copy-exiting)` set to `display: none`; `render()` passes blank `''` for A `arming` like `off` and `connecting`; `phases.arming.detail: '接收啟動中'` retained for C and simulation tools.
2. C status word wrap fix: `.stage.v-C .c-standby-word` set to `white-space: nowrap; font-size: 20px;` across aligned and stacked layouts. In stacked mode, plate width is `max-content` and hint width adapts via `--c-stacked-plate-width`. `layoutCCornerInformation()` measures text range bounding rects to detect leftward overflow in right-aligned flex containers, keeping `#c-status-word` on 1 line across all viewports with zero overlap against `.connection-hint`.
3. C particle state samples: `big-standby-c-particle-assembly.js` preserved byte-identical. Added 6 distinct particle formation presets (`auto`, `cloud`, `x-mark`, `arming-vortex`, `connecting-tunnel`, `wifi-ripple`, `frozen-lattice`) accessible via `#c-particle-mode` in gyro settings, `#c-particle-picker` in review tools, and URL parameter `?particle=` or `?cParticle=`. Formations project deterministically and freeze when `state.motion.paused` is true.
Node verification PASS: `node --check`, `verify-native-phases.cjs`, `verify-c-assembly.cjs`, `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`. Parent actual browser verified all 12 groups of `verify-v41-native-phases-ui.js` (`3013baea...`) PASS in 111,094ms (0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact), including 56 C status word single-line checks (8 sizes x 7 phases) with zero overlap against hint. Independent parent HTTPS verification on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` visually inspected 6 HTTPS PNGs (`mirax-v41-A-ARMING-blank-hint.png`, `mirax-v41-C-narrow-WAIT_WIFI-360.png`, `mirax-v41-C-particle-arming-vortex.png`, `mirax-v41-C-particle-connecting-tunnel.png`, `mirax-v41-C-particle-wifi-ripple.png`, `mirax-v41-C-particle-frozen-lattice.png`).

### Custom broadcast name input (TARGETED_CUSTOM_DEVICE_NAME_PARENT_PASS, 2026-11-01)
1. `#settings` gained a `廣播名稱` fieldset with `#device-name-input` (text, `maxlength="40"`, `autocomplete="off"`, `spellcheck="false"`) and `#device-name-help`. `#settings input[type="text"], #theme-select` now share the 44px field box; `:focus-visible` gets a white 2px outline.
2. `readDeviceName()` reads `?name=` (alias `?deviceName=`), trims, clamps to 40 characters, and falls back to `MIRAX · 客廳`. `state.deviceName` is seeded from it and re-read in the `popstate` handler.
3. `escapeHtml()` escapes `&`, `<`, `>`, `"`, `'`. `render()` pushes `state.deviceName` into every `.device-name`, C `.connection-title` (still read from the synced `.device-name` DOM node so `verify-c-log-styles.js` keeps copying one source), the A `.receiver-name` hint, and D `.e-status-message[data-status="waiting"] .e-status-phrase`; the settings input is synced only while it is not focused so typing is never overwritten. `input` calls `act('change-device-name')`; `blur` on a blank value restores `MIRAX · 客廳` in both the input and state.
4. Fixtures: `verify-native-phases.cjs` adds 7 `readDeviceName()`/`escapeHtml()` VM checks (default, `name`, `deviceName` alias, whitespace-only fallback, 40-char clamp, markup preserved as text, full escape set). `verify-v41-native-phases-ui.js` adds Group 13 `custom broadcast name: URL init, cross-theme sync, live typing, escaping, and default restore` covering the default name in A/C/D, `?name=MIRAX%20%C2%B7%20%E6%9B%B8%E6%88%BF` in A/C/D, live typing `會議室大螢幕`, inert `<b>Test</b>`, and blank-blur restore.
5. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7), `verify-c-assembly.cjs` (60,831 checks), `verify-c-status.cjs`, `verify-v35-behavior.cjs` (715 shots), `verify-v36-status.cjs` (60 checks, 8 viewports), `verify-d-streams.cjs` (90 shots, 6,784 acceleration checks), `git diff --check` clean. Prototype `42d398a2...`, `verify-native-phases.cjs` `e2563ecd...`, `verify-v41-native-phases-ui.js` `fa0bc52c...`. This child ran no browser. Parent browser: all 13 groups of `verify-v41-native-phases-ui.js` (`fa0bc52c...`) PASS in 116,658ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` verified live settings input editing (`會議室投影主機`) and URL query parameter init (`臥室電視`) syncing across A, C, and D, and inspected 5 HTTPS PNGs (`mirax-v41-custom-name-settings.png`, `mirax-v41-custom-name-A-waiting.png`, `mirax-v41-custom-name-C-waiting.png`, `mirax-v41-custom-name-D-waiting.png`, `mirax-v41-custom-name-url-init.png`). TARGETED_CUSTOM_DEVICE_NAME_PARENT_PASS. Android, device, power, and OLED stay OPEN.


### Broadcast name persistence (TARGETED_DEVICE_NAME_PERSISTENCE_PARENT_PASS, 2026-11-01)
1. `STORAGE_BROADCAST_NAME = 'mirax_broadcast_name'` stores the custom receiver name in `localStorage`. `readDeviceName()` resolution order is `?name=` / `?deviceName=` -> stored value -> `MIRAX · 客廳`; both reads trim and clamp to 40 characters.
2. `saveDeviceName(name)` writes the trimmed, 40-character-clamped name. Empty, whitespace-only, and the default `MIRAX · 客廳` remove the entry instead of writing it, so clearing the field returns to a clean default state.
3. `#device-name-input` `input` persists after `act('change-device-name')`; `blur` on a blank value restores the default in the input, in state, and in storage. Storage access is wrapped in `try/catch` with `console.warn('Broadcast name storage unavailable', error)`, so private-mode or denied storage degrades to the old in-memory behavior.
4. `#device-name-help` now reads `Windows 投射與連線提示顯示的名稱；修改即時生效並儲存於瀏覽器。空白時還原為預設名稱。`
5. Fixtures: `verify-native-phases.cjs` adds section 6, 14 mock-`localStorage` VM checks (restore on load, URL beats storage, whitespace/corrupt values ignored, 40-char clamp on read and write, default and empty clear the entry, denied `getItem`/`setItem`/`removeItem` logged and swallowed). `verify-v41-native-phases-ui.js` Group 13 adds the persistence leg: type `臥室電視機` -> stored -> reload `?v=41` restores state, input, and every `.device-name` -> blank blur clears storage -> reload returns to `MIRAX · 客廳` with a null storage entry.
6. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7, broadcast name persistence 14), `verify-c-assembly.cjs` (60,831), `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`, `git diff --check` clean.
7. Hash: prototype `380e2382078d00ddf6d2d9134fedc39d04f5a8862fb9caa2898153c13aeb07f1`, `verify-native-phases.cjs` `3b39d493891b8a9c0206e2ba3ea0f64febe3fc6add73fdc085914a7c557ff0b1`, `verify-v41-native-phases-ui.js` `bb2940d4629aba0550b9f7ecca05da4066010f76664f25153f2f5e79ba66b307`. Parent actual browser PASS: all 13 groups of `verify-v41-native-phases-ui.js` (`bb2940d4...`) in 107,351ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` set custom name `客廳大型顯示器`, verified reload persistence from `localStorage` with no URL query parameter and clean removal after clearing the input, and inspected 2 HTTPS PNGs (`mirax-v41-persisted-reload.png`, `mirax-v41-persisted-reset-reload.png`). Android, device, power, and OLED stay OPEN.

### Long device name containment fix (TARGETED_PORTRAIT_LONG_NAME_PARENT_PASS, 2026-11-01)
1. User report: `直屏時，過長的裝置名稱會有顯示問題`. Theme A let a long name overflow both screen edges; theme D ran the receiver across the bottom into the shared broadcast dock.
2. `.device` now carries `width: max-content` with `max-width: min(88vw, calc(100vw - var(--edge-left) - var(--edge-right) - 24px))` and `box-sizing: border-box`, so the A receiver row can never widen past the safe insets.
3. `.device-name` now carries `display: block; max-width: 100%; overflow: hidden; text-overflow: ellipsis` next to the existing `white-space: nowrap`, so an overlong name clips to one ellipsised line instead of scrolling the stage.
4. `.stage.v-D .device` landscape bound changed from `calc(100% - var(--edge-left) - var(--edge-right))` to `calc(50vw - var(--edge-left) - 54px)` with `box-sizing: border-box`, keeping the receiver to the left of the 84px dock that sits at `left: 50%`.
5. The D stacking media query widened from `@media (max-width: 400px)` to `@media (max-width: 440px), (orientation: portrait) and (max-width: 600px)`, setting `bottom: calc(max(12px, env(safe-area-inset-bottom)) + 84px + 12px)` and `max-width: calc(100vw - var(--edge-left) - var(--edge-right) - 24px)`. `.stage.v-D .device-name` gained the same clip properties.
6. Fixtures: `verify-v41-native-phases-ui.js` Group 13 adds a portrait containment leg with the exact user string `Renathan's Fold 5555555555555555555` at `390x844` and `412x915`. Theme A must keep `.device-name` inside the viewport with one line and no document horizontal overflow; theme D must place `.device.bottom <= dock.top` with no dock overlap and stay inside the viewport. `verify-d-streams.cjs` D receiver assertions realigned to the new landscape bound, the widened media query, and the `.device-name` clip rule.
7. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7, persistence 14), `verify-c-assembly.cjs` (60,831), `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`, `git diff --check` clean. This child ran no browser. Parent actual browser PASS: all 13 groups of `verify-v41-native-phases-ui.js` (`c28bd61b...`) in 126,010ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` at 390x844 and 412x915 with the exact user string `Renathan's Fold 5555555555555555555`: theme A `.device-name` contained in viewport, single line, ellipsised `...`, 0 horizontal overflow; theme D `.device.bottom <= dock.top` at both sizes, 0 overlap and 0 collision. 4 HTTPS PNGs inspected: `mirax-v41-portrait-long-name-A-390.png`, `mirax-v41-portrait-long-name-D-390.png`, `mirax-v41-portrait-long-name-A-412.png`, `mirax-v41-portrait-long-name-D-412.png`. Android, device, power, and OLED stay OPEN.
### C FROZEN adopts proposal 06 (TARGETED_C_FROZEN_LATTICE_ADOPTED_PARENT_PASS, 2026-11-01)
1. `cParticleSampleProject(mode, ...)` keeps `auto` as the only state-aware branch: `state.phase === 'frozen'` delegates to the `frozen-lattice` (06 / Locked Stasis Lattice) projection, every other phase keeps `cParticleAssembly.project(...)`. Explicit sample modes are unchanged.
2. `drawParticles()` now routes every particle through `cParticleSampleProject(state.cParticleMode || 'auto', ...)`, so the default `auto` mode renders the 06 stasis lattice on `FROZEN` (receiver unavailable) and keeps the existing assembly/free-flight behaviour on `READY`, `WIFI_PAUSED`, `ARMING`, `ADVERTISING`, `CONNECTING`, `CONNECTED`.
3. `#c-particle-mode` and `#c-particle-picker` option copy now reads `依連線狀態（預設 · 接收不可用套用 06 靜滯晶格）` and `06 / Locked Stasis Lattice（靜滯晶格 · 已套用至接收不可用）`.
4. `big-standby-c-particle-assembly.js` is byte-identical; FROZEN still animates (no glitch, no 7.5s recovery) and `state.motion.paused` still freezes it.
5. Fixtures: `verify-native-phases.cjs` asserts `auto` equals `frozen-lattice` coordinates on `frozen` and equals `cParticleAssembly.project(...)` outside it; `verify-v41-native-phases-ui.js` Group 12 asserts `auto` in `FROZEN` matches `cParticleSampleProject('frozen-lattice', 3, 400, 300, frame)`.
6. Parent actual browser: `verify-v41-native-phases-ui.js` (`e657cb86...`) all 13 groups PASS in 114,842ms (0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes `7c5f032...` intact). Live HTTPS `?variant=C&phase=FROZEN&v=41` in default `auto` renders proposal 06 frozen-lattice coordinates matching `cParticleSampleProject('frozen-lattice')`; `?variant=C&phase=READY&v=41` stays free-flight. 2 HTTPS PNGs inspected: `mirax-v41-C-frozen-auto-lattice.png`, `mirax-v41-C-ready-auto-cloud.png`.

### 2026-11-01 resumed specification interview: Q8-Q9 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q8 (Navigation Seams and Homepage Hierarchy)**:
   - **User Decision**: `1. 採用建議，2. 抽屜可以展開以前的側邊攔，3. 為此，舊版首頁的歷史功能需要額外抽取出。`
   - **Unmistakable Decisions**:
     - Cold Start: Non-projecting startup defaults unconditionally to Big Standby; no new "remember-last-home" preference is created.
     - Navigation Drawer: The left handle opens/reuses the existing native sidebar navigation drawer (`MainActivity.kt:2036-2046`) rather than shipping prototype QA controls, providing access to both Big Standby and the Legacy Dashboard.
     - Independent History Access: The connection-history feature must be accessible independently of the Legacy Dashboard.
     - Session & Broadcast Continuity: Switching UI presentation modes does not modify background advertising or active session state.
   - **Codebase Context & Verified Facts**:
     - `MainActivity.kt:927-929` provides an existing shortcut to history from the Legacy Dashboard (`openPage(Page.HISTORY)`).
     - `Page.HISTORY` and its screen renderer `buildHistory()` already exist independently in the native codebase (`MainActivity.kt:839`, `MainActivity.kt:995-1070`), with `parentOf(Page.HISTORY) == Page.DASHBOARD` (`MainActivity.kt:328-335`).
     - The existing native sidebar (`MainActivity.kt:2036-2046`) currently omits a history entry. The user requirement to "extract" signifies providing independent navigation access/routing, not reimplementing the history page, storage, or session logging. Native extraction remains UNEXECUTED.
   - **Items Remaining OPEN**:
     - Exact history menu placement in the navigation drawer or top bar, retention vs deprecation of the Legacy Dashboard shortcut, Back key target policy from Big Standby (existing Back at `MainActivity.kt:390-416` handles drawer close, parent navigation, and two-press exit confirmation), and return destinations remain OPEN for subsequent questions.

2. **Q9 (Design Fidelity and Motion Scope)**:
   - **User Decision**: `採用建議，但是按照目前所見及所得，我指的是D有許多已經不再使用的主題，但我現在只需要default你給我看的即可`
   - **Unmistakable Decisions**:
     - Fidelity Process: Approved faithful porting process for themes A, C, and D at equivalent viewports, preserving existing responsive behavior, name ellipsis, and C single-line status without universal pixel tolerance assumptions.
     - Theme D Port Scope: Scoped strictly to the currently approved default motion. Historical Theme D study variants are excluded from the native implementation port.
     - Source Fact: The prototype default Theme D motion fallback is identified as `study-118-section-extrusion` (`design/big-standby-prototype.html:1145-1147`).
     - Prototype Scope: The user did NOT authorize deleting prototype studies, ratings, notes, or research files from the repository.
     - Approved States: C FROZEN proposal 06 (Locked Stasis Lattice) remains approved. No new font or pixel tolerance rules are invented.

### 2026-11-01 specification interview: Q10-Q11 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q10 (Navigation Drawer History Entry and Shortcut Preservation)**:
   - **User Decision**: `是，採用建議 (Recommended)`
   - **Closed Decisions**:
     - Existing native sidebar drawer (`MainActivity.kt:2036-2046`) gains a top-level `連線歷程` (Connection History) item, providing unified access from both Big Standby and the Legacy Dashboard.
     - Direct reuse of existing `Page.HISTORY` and backing connection run history; no duplicated data storage, models, or parallel screens.
     - Existing Legacy Dashboard connection history shortcut (`MainActivity.kt:927-929`) is preserved as-is.

2. **Q11 (Big Standby Back-Press Leaving Policy)**:
   - **User Decision**: `是，沿用兩次返回 (Recommended)`
   - **Closed Decisions**:
     - When not actively receiving projection and with drawer, dialogs, and keyboard closed, Back key on Big Standby reuses the native two-press exit confirmation flow (`MainActivity.kt:390-416`): first press displays Toast prompt, second press within 2000ms exits/leaves the application screen.
     - Back on Big Standby does NOT navigate back to Legacy Dashboard and does NOT toggle or terminate advertising state directly.
     - Background broadcast duration remains an uncoupled, separate policy; leaving UI screen makes no promise to terminate or retain background service indefinitely.

3. **Items Remaining OPEN & Unchanged Scope**:
   - **Open Specifications**:
     - Origin-sensitive return destinations for history/settings and switching-home back-stack navigation semantics.
     - Custom media persistence, lifecycle, and runtime error recovery behavior.
     - Background broadcast lifespan and duration limit policy.
     - Remaining native port acceptance gates and error-handling details.
   - **Unchanged Baseline**: Prior decisions Q1–Q9, Themes A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q12-Q13 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q12 (Navigation Back-Stack and Origin-Sensitive Return)**:
   - **User Decision**: `是，採用建議 (Recommended)`
   - **Closed Decisions**:
     - Origin-Sensitive Returns: History and settings pages entered from either homepage (Big Standby or Legacy Dashboard) return step-by-step to that originating homepage.
     - Homepage Switching: Selecting an alternative home from the sidebar replaces the current navigation root; the two homepages do not stack on top of each other.
     - Double-Back Boundary: The approved double-Back exit policy applies exclusively once back at the Big Standby root, never as an exit shortcut directly from child history or settings views.
     - Future Seam Note: Existing native fixed parent mapping (`parentOf(HISTORY) == Page.DASHBOARD` at `MainActivity.kt:328-335`) and settings root logic must be modified in the future to fulfill this spec; native implementation remains UNEXECUTED.

2. **Q13 (Custom Media Background Persistence)**:
   - **User Decision**: `是，重啟後保留 (Recommended)`
   - **Closed Decisions**:
     - Background Persistence: Selected image/video/GIF media persists across normal application restarts until explicitly replaced or removed by the user.
     - Single Media Scope: Supports strictly one media file at a time; playlists and cloud synchronization are excluded.
   - **Unsettled / Open Details**:
     - Storage policy: internal copy-import vs retained external URI reference permissions.
     - Error handling: handling of source file invalidation, deletion, or decoding failure.
     - Playback resumption: paused timeline restoration vs restart-from-beginning behavior.
     - Formats, audio track handling, aspect crop rules, and file size limits.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Media storage/error policy and resume timeline; background broadcast duration limits; remaining native receiver error states and acceptance criteria.
   - **Unchanged Baseline**: Prior decisions Q1–Q11, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q14-Q15 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q14 (Custom Media Cropper and Playback Timeline)**:
   - **User Decision**: `自訂媒體需要一個簡單的裁切器。播放進度總是從頭，開關側邊攔不重設`
   - **Closed Decisions**:
     - Cropper Requirement: Custom media requires a simple cropper (new native product requirement; native implementation remains UNEXECUTED).
     - Timeline Policy: Playback always restarts from the beginning when started or resumed; no saved within-session or persistent resume timestamp (supersedes proposed within-session resume).
     - Drawer Interaction: Merely opening or closing the sidebar drawer does not reset playback progress.
     - Unaccepted Bundle Scope: Audio muting, GIF loop bounds, and crop-fill behavior were not explicitly confirmed and are not accepted by default.
     - Lifecycle Motion: Existing Q4 lifecycle pausing continues to apply.

2. **Q15 (Private Copy Storage and Degradation Protection)**:
   - **User Decision**: `是，保存副本與保護 (Recommended)`
   - **Closed Decisions**:
     - Private Copy Storage: The active selected custom media is stored as an App-private copy to ensure deletion or renaming of the external original does not invalidate the imported background (extra internal storage acknowledged).
     - Safe Transactional Replacement: Existing background is replaced only after a new media import and decoding are validated usable; previous active copy is pruned upon successful replacement. Temporary transactional staging is permitted.
     - Safe Degradation: If a new import fails verification/decoding, the previous background is retained with an error reason. If an already-stored media becomes unreadable or corrupted, the system falls back gracefully to the currently selected A/C/D theme with an error notice, without black screen or connection state disruption.
     - Execution Boundary: No claims of implemented decoders, format parsers, or re-encoders.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Cropper definition (spatial framing vs video time trimming, aspect/fold adaptation, file/resolution bounds); audio track, loop, and fill specifics; background idle broadcast policy (ADVERTISING timeout source); native acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q13, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q16-Q17 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q16 (Background Framing vs Cropping Definition)**:
   - **User Decision**: `是，只做背景取景 (Recommended)`
   - **Closed Decisions**:
     - Spatial Background Framing: Custom media (images/videos/GIF) supports spatial background framing via interactive pan, zoom, visible-area preview, and confirm/cancel actions.
     - Confirmation & Cancellation: Confirmation applies the new spatial viewport framing; cancellation preserves the existing background intact.
     - Scope Boundary: Modifies only the displayed framing geometry (scale and viewport offset). Does NOT perform content re-encoding/re-compression, video duration trimming (start/end points), or visual filters.
     - Decision Meaning: Closes the definition of "simple cropper" as non-destructive spatial framing; does NOT constitute approval of any implemented native control (native implementation remains UNEXECUTED).
     - Still Open: Exact viewport adaptation across portrait, landscape, and foldable posture transitions remains OPEN.

2. **Q17 (Idle Advertising Auto-Stop Policy)**:
   - **User Decision**: `是，沿用現行規則 (Recommended)`
   - **Closed Decisions**:
     - Preserve Existing Rule: Preserves the current idle advertising auto-stop behavior (default 1 minute, user-configurable 1–180 minutes; source: `BroadcastAutoStop.kt:29-44`).
     - Phase Scoping: Only `ScreenPhase.ADVERTISING` counts toward the idle countdown; `ARMING`, `CONNECTING`, and active projection do not run or expire this timer.
     - UI Independence: Switching between Big Standby and Legacy Dashboard or opening/closing the sidebar drawer does not reset the running countdown.
     - App Background Boundary: Transitioning the app to the background does not stop or extend the existing timer deadline.
     - Timeout Scope: Expiration disables advertising broadcast; it does NOT trigger screen dimming or app-initiated sleep. Foreground Big Standby keepawake/motion continues per Q4/Q6. OS/platform lifecycle limitations are not overridden.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Orientation and fold posture framing adaptation; custom media audio track handling and loop policies (not explicitly confirmed earlier); theme selection preference and initial default; receiver error presentation and native verification acceptance criteria.
   - **Unchanged Baseline**: Prior decisions Q1–Q15, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), C FROZEN proposal 06, media restart timeline (Q14), and private copy storage (Q15) remain strictly unchanged.

### 2026-11-01 specification interview: Q18-Q19 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q18 (Multi-Posture Background Framing Adaptation)**:
   - **User Decision**: `是，共用一組取景 (Recommended)`
   - **Closed Decisions**:
     - Shared Framing Geometry: A single shared spatial framing configuration applies across portrait, landscape, and foldable inner/outer displays; separate per-posture presets are not required.
     - Center & Aspect Fill: Resizing prioritizes the same framing center and aspect-preserving fill with clamped repositioning/cropping to prevent stretching or uncovered screen gaps.
     - User Control: No automatic cropper prompt on layout resize; users manually adjust framing when desired.
     - Visual Acceptance: User explicitly accepts that visible content margins vary across differing aspect ratios rather than demanding identical revealed boundaries.

2. **Q19 (Custom Media Audio, Looping, and Control Scope)**:
   - **User Decision**: `是，無聲循環 (Recommended)`
   - **Closed Decisions**:
     - Ambient Muting & Looping: Custom background videos are strictly muted and looping; GIFs continuously loop regardless of single-play headers; still images remain static.
     - UI Surface Scoping: No player controls, seek bars, or volume sliders on the standby stage.
     - Audio Boundary: Applies exclusively to the ambient standby background; received stream audio during projection is unaffected.
     - Retained Continuity: Playback restarts from the beginning upon actual start/resume per Q14; opening/closing the sidebar drawer does not reset playback. Native player implementation remains UNEXECUTED.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Initial A/C/D theme default and persistent theme selection memory (Theme D default motion identity is separate); truthful FROZEN info presentation and compatibility-settings navigation; remaining resource/format constraints and native acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q17, Theme A/C fidelity, Theme D default motion (`study-118-section-extrusion`), C FROZEN proposal 06, Q4 ambient motion, Q14 restart timeline, Q15 private copy protection, and Q17 idle timer remain strictly unchanged.

### 2026-11-01 specification interview: Q20-Q21 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q20 (Initial Default Theme and Selection Persistence)**:
   - **User Decision**: `首次 C，之後記住選擇`
   - **Closed Decisions**:
     - Initial Launch Default: First cold start with no stored theme preference defaults to Theme C (supersedes previous proposed Theme A default).
     - Theme Persistence: Subsequent selections between A, C, or D are persisted across application restarts.
     - Media Independence: Imported custom background media and framing geometry remain preserved independently across theme changes.
     - Theme D Scoping: Theme D strictly includes the approved default motion (`study-118-section-extrusion`); historical study variants are excluded.

2. **Q21 (Compatibility Settings Page Removal and Precedence Correction)**:
   - **User Decision Sequence**: Initial selection `是，加入設定入口 (Recommended)` was immediately superseded and corrected by explicit user instruction: `不，相容性設定整個頁面砍掉，那裏面的文本內容過於陳舊`.
   - **Controlling Precedence & Closed Decisions**:
     - Complete Removal from Product Scope: The entire Compatibility settings page (`Page.COMPATIBILITY`) and its obsolete text are removed from future product scope (including sidebar, Legacy Dashboard, and proposed FROZEN navigation routes), not merely hidden or renamed.
     - Proposal Superseded: The previously proposed FROZEN compatibility-settings navigation link is REJECTED/SUPERSEDED.
     - Spec Boundary (UNEXECUTED): Removal is an unexecuted product specification decision; native code, underlying permission checks, WFD-owner logic, privilege functions, and diagnostic hooks are NOT deleted. No replacement instruction page or alternative permission destination is invented.
     - FROZEN Presentation: Existing FROZEN truthful info dialog and motion baseline remain intact; final actionable content and handling remain open pending functional source audit.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Final FROZEN actionable info content (without compatibility routing); native measured performance, media capability bounds, and acceptance test gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q19, Theme A/C fidelity, Theme D default motion (`study-118-section-extrusion`), C FROZEN proposal 06, Q4 ambient motion, Q14 restart timeline, Q15 private copy, Q17 idle timer, Q18 shared framing, and Q19 muted looping remain strictly unchanged.

### 2026-11-01 scope update: native FAILED implementation required (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Native FAILED Requirement**:
   - **User Instruction**: `另外，FAILED模式我知道在原本的設計裡沒有，所以在這次需要補上相關實現。`
   - **Closed Requirement**: The native Android port MUST implement a genuine native FAILED presentation/mode; it is no longer excluded as a permanently prototype-only simulator (supersedes earlier product scoping that omitted native FAILED functionality).
   - **Architecture & Implementation Status**: Current native `ScreenPhase` retains seven states; an eighth enum value or alternative presentation architecture has not yet been selected or approved by the user. No native code or model changes have been executed.
   - **Prototype Simulator Boundary**: The prototype 7.5s active-ms countdown is strictly visual reference behavior, NOT approved network reconnect, socket reset, or service recovery logic. The prototype timer must not drive native network reconnection, socket resets, or service recovery. The native FAILED visual lifetime remains undecided.

2. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open for Next Interview**: FAILED trigger classification, source of truthful error reasons, priority relative to new connections / `WIFI_PAUSED` / `FROZEN`, visible lifetime / dismissal, and recovery action handling.
   - **Unchanged Baseline**: Prior decisions Q1–Q21 remain strictly unchanged, including initial Theme C default, private copied media/framing/muted looping/restart, full removal of the Compatibility settings page (latest Q21 correction; no compatibility route), and C FROZEN proposal 06.

### 2026-11-01 specification interview: Q22-Q23 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q22 (Native FAILED Scope Boundary and Pre-Play Requirement)**:
   - **User Decision**: `建立與協商失敗或逾時才需要，因為如果是投放中出錯，是沒機會在Big Standby顯示錯誤的。`
   - **Closed Decisions**:
     - Pre-Play Scope Only: Native Big Standby FAILED presentation is required EXCLUSIVELY for genuine connection establishment or RTSP negotiation failure/timeout occurring BEFORE projection playback begins.
     - Post-Play Errors Excluded: User specifically rejects routing post-PLAY streaming, decoder errors, or playing-session disconnects into this Standby FAILED mode; existing projection-view error handling is left intact without invasive decoder-error routing. (User's reasoning is recorded as product rationale, not as universal proof regarding Standby display lifecycle).
     - Non-Failure Exclusions: Voluntary user cancellation and normal session teardown do not trigger FAILED. System Wi-Fi disablement remains `WIFI_PAUSED`; device/P2P unavailability remains `FROZEN`.
     - Visual Assets: Retains A/C/D prototype visual failure styling for verified pre-play failures.
   - **Engineering Implications**: Per `SessionTypes.kt:595-605`, `succeeded` indicates playback started; `FinishConnectionRun(false)` alone is insufficient to discriminate real failure from voluntary cancellation. Pre-play failure handling requires discrete reason classification and unique attempt identity rather than relying on session history flags or generic drop events. Native implementation remains UNEXECUTED.

2. **Q23 (FAILED Visual Lifetime and Dismissal Semantics)**:
   - **User Decision**: `是，7.5 秒後收起 (Recommended)`
   - **Closed Decisions**:
     - Visible Foreground Duration: Visual FAILED presentation displays for 7.5 seconds of active foreground time; backgrounding or hiding the app pauses this countdown.
     - Pure Visual Dismissal: Expiration at 7.5 seconds strictly dismisses the failure presentation and returns the UI to the CURRENT underlying state; it never forces an `ADVERTISING` state transition.
     - No Service Side-Effects: Dismissal triggers no broadcast toggles, socket resets, or reconnect attempts, and does not restart or extend the idle advertising timer.
     - Event Preemption: Any new incoming connection attempt, active projection, explicit stop, `WIFI_PAUSED`, or `FROZEN` immediately supersedes the active failure episode; stale timers or outdated attempt IDs cannot overwrite current state. 7.5 seconds provides no guaranteed network recovery.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Formal specification assembly and final shared-understanding confirmation; platform media/codec limits, thermal/battery constraints, and native hardware acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q21 remain strictly unchanged, including initial Theme C default, private copied media/framing/muted looping/restart, complete removal of the Compatibility settings page (with no replacement route or page), and C FROZEN proposal 06. Native code implementation and whether `ScreenPhase` adds an eighth state remain unexecuted and open.

### 2026-11-01 specification interview: Q24 confirmation & consolidated draft pointer (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

- **Q24 Decision**: User confirmed consolidated product specifications (`確認，只整理草稿 (Recommended)`).
- **Draft Pointer**: The authoritative unpublished specification lives in `docs/research/android-standby-rendering-feasibility.md` under section `## Big Standby Android port: final specification (2026-11-01)`; that section also holds the final Issue title and body.
- **Still-Open Technical Implementation & Acceptance Checklist (ALL UNEXECUTED / PENDING)**:
  - [ ] Architecture decision: 8th `ScreenPhase.FAILED` enum state vs distinct presentation layer over existing 7 native phases using existing codebase seams.
  - [ ] Discrete pre-play failure cause classification and unique connection attempt identity (differentiating true timeout/error from voluntary cancel, `PrePlayGroupDropped`, or `FinishConnectionRun(false)`).
  - [ ] Hardware sensor availability, permission model, and gyroscope/accelerometer gain calibration on real Fold hardware.
  - [ ] Native host/renderer architecture selection meeting measured frame pacing, thermal, and power criteria under approved visuals (subject to explicit engineering review, no unapproved numerical target or dependency addition).
  - [ ] Platform codec, file size, and decoded bitmap/frame memory bounds defined and enforced for custom media (image/video/GIF).
  - [ ] Long-running OLED burn-in drift verification and thermal throttling soak tests.
  - [ ] Native test suite creation for navigation back-stack, media transactions/cancellation, pre-play failure preemption/stale outcome guards, and compatibility route removal regressions.

### 2026-11-01 formal theme naming & localization policy update (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

- **Formal Theme Names**: Experimental letter prefixes are retired in favor of formal product theme identities: `山霧 · 流動地景` (Landscape), `微粒 · 黑白場域` (Matter, launch default), and `硬線 · MIRAX 形構` (Hardline default motion `study-118-section-extrusion`). Historical prototype source keys and fixture IDs remain untouched for provenance.
- **ADR 0007 Pointer**: End-to-end multi-language localization policy and naming rationale are formally recorded in `docs/adr/0007-standby-theme-names-and-localization.md`.
- **Bilingual Acceptance (PENDING)**: All native localization verification (Traditional Chinese / English string parity, runtime locale switching, dynamic typography measurements, and narrow/fold posture containment without accidental glyph clipping or unapproved font downscaling, preserving intentional receiver-name ellipsis) remains unexecuted and PENDING.

### 2026-11-01 final spec ready, publication pending
*Status: SPEC COMPLETE / NOT PUBLISHED.*

- Final specification body and Issue title are in `docs/research/android-standby-rendering-feasibility.md` under `## Big Standby Android port: final specification (2026-11-01)`.
- User confirmed the three testing seams; no implementation has run.
- GitHub publication with `ready-for-agent` is still PENDING, owned by the parent session; this file must not be read as a published issue.
- Still open: eighth enum value vs presentation episode for pre-play failure, renderer choice, and all hardware measurements.
