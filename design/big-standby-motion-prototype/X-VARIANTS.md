# Third batch: 41–65, variants and X-body motion

Question: which direction does each promising study want to grow, and what changes when the X itself may move a little?

Twenty variants of four user-nominated studies, plus five proposals that animate the X body within a small budget.
Studies 01–40 stay untouched for comparison.

## Drawing contract

Same IIFE / `window.miraxDStudies.set(id, descriptor)` contract as `README.md`.
Studies 41–50 and 61–65 set `scope: 'field'`, so the host clips them to the upper/lower full-width fields
and subtracts visible labels. Studies 51–60 are X-centred (derived from 14 and 17) and deliberately
leave `scope` unset: clipping them to the fields would erase the mark they are about. Build one whole-scene composition from `frame.width`,
`frame.height` and `frame.field.top` / `.bottom`; the field rectangles are not mini-canvases.

`frame` for this batch:

```js
frame = { width, height, time, field: { top: {x,y,width,height}, bottom: {...} },
          rails, arms, diamond, visibleEdges }   // legacy data, not the design scaffold
```

No DOM, timers, own rAF, network or storage. Only the selected callback paints. Time is elapsed
seconds from the shared pausable clock.

### X-body motion (61–65 only)

A descriptor may add `xMotion(time, frame)` returning up to four finite numbers:

```js
{ dx, dy, rotate, scale }   // dx/dy: screen pixels, rotate: degrees, scale: ratio around the X centre
```

The host composes it with the existing OLED shift, resets it every frame, and never applies it to
text or captions. Omitted keys mean no change. Budget: `|dx|, |dy| <= 6`, `|rotate| <= 2.4`,
`0.985 <= scale <= 1.015`, continuous (no step, no loop seam). Anything larger stops reading as a
standby screen. The X must still be legible as the same hollow double-outline mark.

Studies 41–60 must not define `xMotion`.

## Acceptance

- Each variant must be recognisably a different *mechanism* from its source study, not a renamed
  parameter preset. Reusing the source's geometry with a different speed fails.
- 41–45 vary the lozenge/tiling organisation; 46–50 vary the interference construction;
  51–55 vary the section/reveal relation to the X arms; 56–60 vary the cathode charge sweep;
  61–65 give the X body its own small motion plus a matching spatial field.
- Main information text stays fixed. Motion stays visible across the whole composition, not only
  the lower-left quadrant.
- White/grey angular outlines, restrained luminosity, no solid bright planes, no strobe, no random
  particles.

## Assigned concepts

| File / ID suffix | Category | Source | Distinct mechanism |
| --- | --- | --- | --- |
| `41-strain-drift` | 第三批 · 準晶變體 | 36 | Travelling affine strain front continuously skews the rhomb lattice; cells stretch and recover without any phason flip. |
| `42-bar-relay` | 第三批 · 準晶變體 | 36 | Ammann bar network of two segment lengths; local bar patches rotate, break length matching, then repair along the front. |
| `43-radial-crowding` | 第三批 · 準晶變體 | 36 | Rhombs arranged in a radial fan around an off-centre hub; the hub precesses so tiles crowd and relax as an angular density wave. |
| `44-row-register` | 第三批 · 準晶變體 | 36 | Horizontal lozenge rows slide laterally at different phases; vertical column registration shears and re-forms across the field. |
| `45-growth-front` | 第三批 · 準晶變體 | 36 | Tiling nucleates at a moving seed; tiles are born and retired at a travelling growth frontier, changing local topology. |
| `46-radial-moire` | 第三批 · 干涉變體 | 11 | Two concentric ring gratings whose centres orbit each other; hyperbolic fringes sweep the whole field. |
| `47-grid-twist` | 第三批 · 干涉變體 | 11 | Two coarse square grids twisting against each other; moiré cells migrate and change size instead of fine-line drift. |
| `48-chevron-moire` | 第三批 · 干涉變體 | 11 | Two chevron gratings with different apex angles; fringe bands bend at the ridge and travel outward. |
| `49-wavefront-mix` | 第三批 · 干涉變體 | 11 | Expanding circular wavefronts from three moving sources; nodal lines form and dissolve where they interfere. |
| `50-pitch-lens` | 第三批 · 干涉變體 | 11 | One grating with a travelling lens region where local pitch compresses, magnifying fringes as it passes. |
| `51-multi-section` | 第三批 · 穿透變體 | 14 | Two intersecting section planes cut the X arms and diamond; the section polygons change shape as both advance. |
| `52-aperture-punch` | 第三批 · 穿透變體 | 14 | Punched apertures travel through the arms, stretching and merging into slots as they cross the diamond. |
| `53-needle-thread` | 第三批 · 穿透變體 | 14 | A thin needle enters one arm, threads the diamond gap and exits another; entry and exit faces change angle. |
| `54-peel-layer` | 第三批 · 穿透變體 | 14 | Outline echo layers peel away from the arms, tilt, and re-register; revealed interior contour shifts with the peel depth. |
| `55-contour-tunnel` | 第三批 · 穿透變體 | 14 | Nested section outlines recede into the mark like a tunnel; the vanishing section moves off-axis so depth is asymmetric. |
| `56-cathode-return` | 第三批 · 陰極變體 | 17 | Charge sweeps travel outward from the diamond and return along a different arm, meeting as a travelling interference band. |
| `57-cathode-charge` | 第三批 · 陰極變體 | 17 | Charge accumulates at arm ends, discharges across the gap, and the discharge front splits into two unequal branches. |
| `58-cathode-spark` | 第三批 · 陰極變體 | 17 | Short angular spark segments jump between arm edges; each jump starts the next, so the chain migrates. |
| `59-cathode-mirror` | 第三批 · 陰極變體 | 17 | Two charge sweeps travel in mirror, their overlap region drifts diagonally instead of the sweep direction. |
| `60-cathode-drift` | 第三批 · 陰極變體 | 17 | The charge gradient itself drifts: lit and dark arm spans trade length continuously, with no repeating sweep start. |
| `61-x-breath` | 第三批 · X 本體微動 | — | X scale breathes 1.000–1.012 with an asymmetric easing; a matching aperture field opens around it in the same phase. |
| `62-x-torsion` | 第三批 · X 本體微動 | — | X rotates ±1.6° on a slow torsion; four corner brackets in the fields lag by a quarter phase. |
| `63-x-shear` | 第三批 · X 本體微動 | — | X translates ≤5px along one diagonal while a parallel shear band crosses the fields in the opposite direction. |
| `64-x-settle` | 第三批 · X 本體微動 | — | X settles with a damped two-axis offset that never fully stops; surrounding rings tighten as the offset decays. |
| `65-x-flex` | 第三批 · X 本體微動 | — | X pivots ±0.9° and shifts 3px while long field ribs flex away from the pivot, then rebound. |

## Delivery

Edit only your assigned numbered JS files. The parent owns the host HTML, docs, verifier and browser
evidence. Return completed files, the distinct mechanism, the spatial centre of motion, and three
useful observation times each. Do not claim browser validation unless you actually performed it.
