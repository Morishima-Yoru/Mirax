# Third batch: 41–65, variants and X-body motion

Date: 2026-10-27. Status: **25 implemented proposals, not aesthetic approval.**

## What was asked

1. Five variants each of studies 36, 11, 14 and 17, actually implemented.
2. The X symbol does not have to stay completely still; it may animate slightly. Five additional
   proposals based on that, also implemented.

## Contract added for the X body

`xMotion(time, frame)` on a descriptor returns up to `{dx, dy, rotate, scale}`. The host composes it
with the existing OLED shift on `.x-plane` only, resets it every frame, and never touches text.
Budget in `X-VARIANTS.md`: `|dx|,|dy| <= 6` screen px, `|rotate| <= 2.4°`, `0.985 <= scale <= 1.015`.
Studies 41–60 must not define it; the verifier enforces that by scope rule, not by declaration.

Measured in the browser (net of the OLED shift):

| Study | X motion character | Measured peak |
| --- | --- | --- |
| 61 | asymmetric breath | scale +0.0119 |
| 62 | slow torsion | rotate 1.575° |
| 63 | diagonal shear | dx 3.36 px, dy 3.36 px |
| 64 | damped two-axis settle | dx 4.47 px, dy 3.27 px |
| 65 | small pivot | dx 2.38 px, dy 1.77 px, rotate 0.89° |

Text displacement across all five stayed within the pre-existing OLED envelope (max 3.1 px, width
change 0). All 41–60 measured `dx, dy <= 0.01 px` and no `rotate`/`scale` in the transform, so the
X body is untouched outside 61–65.

## Mechanism separation

| Study | Distinct mechanism (source) |
| --- | --- |
| 41 | travelling affine strain skews rhombi, no phason flip (36) |
| 42 | Ammann two-length bar network rotating in patches, mismatch then repair (36) |
| 43 | radial rhomb fan with a precessing hub, angular density wave (36) |
| 44 | horizontal rows sliding out of register, columns shear and realign (36) |
| 45 | tiling nucleates at a moving seed and retires at a growth frontier (36) |
| 46 | two ring gratings with orbiting centres, hyperbolic fringes (11) |
| 47 | coarse square grids twisting, moiré cells migrate and resize (11) |
| 48 | chevron gratings with different apex angles, fringe bends at the ridge (11) |
| 49 | three expanding wavefront sources, nodal lines merge and split (11) |
| 50 | travelling local pitch lens magnifies fringes as it passes (11) |
| 51 | two intersecting section planes cut arms and diamond (14) |
| 52 | punched apertures travel the arms, stretch and merge into slots (14) |
| 53 | needle threads arm → diamond gap → arm (14) |
| 54 | outline echo layers peel, tilt and re-register (14) |
| 55 | nested sections recede into an off-axis tunnel (14) |
| 56 | outward charge sweep returns along a different arm (17) |
| 57 | end accumulation, discharge splits into two unequal branches (17) |
| 58 | short spark jumps chain into a migrating relay (17) |
| 59 | mirrored sweeps, overlap region drifts diagonally (17) |
| 60 | drifting gradient, lit and dark spans trade length continuously (17) |
| 61–65 | five different X-body motion characters with matching field compositions |

## Observed distribution

Changed canvas alpha pixels outside the lower-left screen quadrant over seven natural-time samples,
1280×800. Locates activity only; not an aesthetic score.

41 .813 · 42 .840 · 43 .835 · 44 .813 · 45 .803 · 46 .768 · 47 .801 · 48 .802 · 49 .809 · 50 .751
51 .820 · 52 .937 · 53 .780 · 54 .963 · 55 .885 · 56 .749 · 57 .843 · 58 1.000 · 59 .745 · 60 .992
61 .842 · 62 .810 · 63 .496 · 64 .808 · 65 .824

63 sits at .496 because its band runs top-left → bottom-centre and the metric only excludes the
lower-left quadrant; the change map shows a full diagonal band, not a corner packet. 51–60 are
X-centred studies (derived from 14 and 17), so their activity is intentionally on the mark.

## Checks

| Check | Result |
| --- | --- |
| Contract verifier | `node design/big-standby-motion-prototype/verify.cjs`: 65 registrations, 8,450 deterministic frozen-frame draws, finite `xMotion` values at three times |
| First two batches untouched | SHA256 of all 40 pre-existing study files identical to the pre-work snapshot |
| Natural playback | 25 studies × 7 samples ≈ 8 s each: 175 original full-page JPEGs, five before/after/temporal-change contact sheets, all five inspected |
| Responsive | 25 × 5 viewports (1440×1000, 2560×720, 390×844, 844×390, 667×375) = 125 cases: visible evolution, no document overflow, controls inside viewport, no unexpected console error |
| Shared state | 29 boundary assertions re-run with new IDs: pause pixels/clock, paused selection, resume, URL/history/query, legacy modes, active-only rendering, simple home, projection, image/GIF/video overrides, video pause, visibility wiring, reduced motion, compact controls |
| LAN | `192.168.1.111:8765` loaded 65 studies and 69 options; `.x-plane` transform changed between samples while the text rect stayed put |
| Offline | `file://` load of a third-batch study rendered normally |

## Known limits

- 56–59 are thin charge sweeps on the X arms and are visually sparse; 58 reached 32 changed pixels at
  one sample. They are faithful to study 17's economy but may read as too faint at a distance.
- 51–55 are subtle in full-page stills because their detail sits on the mark; the change maps show
  the geometry moving, but the user may want them louder.
- 63 measured 49.6% by the crude quadrant metric. Judged by the change map it is a diagonal band.
- The X micro-motion is deliberately sub-perceptual in a still image; it is only visible in playback.
- Short landscape has shallow fields, so masked lower geometry can disappear in some phases.
- Not tested: device performance, long-duration OLED burn-in, external-phone connectivity.
  No Android change, dependency, persistence, commit, push or merge.

Evidence archive: `C:/Users/User/AppData/Local/Temp/opencode/x-variants-review/`.
