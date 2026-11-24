# D motion studies — throwaway prototypes

23 active studies compare motion and spatial composition, not speed presets.

After the historical third cleanup, 15 studies survived and proposals 93–112 were added. The sixth batch added six depth-comparison studies 113–118 without removing the 35 preceding candidates. On the next explicit cleanup request, 25 recorded rejects were removed; 13 kept and three unrated studies remained at that snapshot. Historic votes and notes remain stored by stable ID. The seventh batch added 30 CHAOTIC-origin studies, 119–148. The eighth batch added 30 independently conceived studies, 149–178. At this cleanup, 53 explicitly rejected candidates were archived; 23 kept or unrated candidates remain. The ninth batch adds 30 history-informed studies, 179–208. Historical votes and notes are never removed, and new IDs inherit none.

The v29 cleanup archives 31 newly rejected sources under `archive/v29-rejected/`
without modifying review history. Twenty-two retained or unrated studies remain;
composition **209** adds cathode tracing from 17 to two sparse, smoothly fading
extrusion layers derived from 118. Sources 17/118 remain selectable. The new ID
inherits neither vote nor Note. See the archive manifest for the exact snapshot.
Its 9.6-second cycle uses a 4.8-second layer offset and zero-opacity resets with
C1 head/depth curves, rather than cutting a visible layer back to its start.

Candidate filenames and API validation accept two- or three-digit IDs; the picker and arrows use numeric order across 99/100.
The dated review reports and concept ledgers are historical records; their original candidate counts do not describe the active catalogue.
The existing D home, hollow X, typography, media controls and slow OLED positioning remain the shared background.
Only the selected study renders. These are visual proposals, not approved production designs or burn-in protection.

For second-batch spatial proposals 21–40, read `FIELD-STUDIES.md` before implementing. It defines the full-width field geometry and spatial acceptance.
For historical third-batch variants and X-body motion 41-65, read `X-VARIANTS.md`.
For historical fourth-batch descendants 66-92, read `REFINEMENTS.md`.
For proposals 93-112 and evidence-linked preference hypotheses, read `PREFERENCE-STUDIES.md`.
For the historical depth-comparison pairs and the user’s background correction, read `DEPTH-DISCOVERY.md`.
For the CHAOTIC-origin briefs and implementation adaptations, read `CHAOTIC-STUDIES.md` and `CHAOTIC-BRIEFS.json`.
For the eighth-batch independently conceived directions, read `SELF-DIRECTED-BRIEFS.json`. For ninth-batch concepts and the evidence behind them, read `HISTORY-BRIEFS.json`. The limits recorded in previous briefs are historical, not current requirements.

## C particle assembly

In theme C, enabling the existing broadcast switch gradually gathers particles into the original Mirax mark over about 12 shared-clock seconds. Turning it off disperses them over about eight seconds.
The assembled X is anchored at the exact viewport centre in every aspect ratio. The 1,800-particle field is denser and slightly larger/brighter than the original 1,000-dot version.
Of those particles, 180 continuously trace the hollow central diamond, 1,260 follow the four arms and 360 stay in free flight.
While broadcast is on, C's generated-art scrim is lighter and status copy moves below the diamond (above it in short landscape), leaving the actual centre visible. Local media, projection and broadcast-off keep their existing readability treatment.
About 80% of particles continuously circulate along the real arm/diamond contours with individual speed, direction and gentle sway. The other 20% stay in the original freely drifting cloud; completing assembly never stops their flow.
The free-flight calculation is unchanged at zero assembly; only the population and dot appearance are richer. Pause, projection, local backgrounds and the simple home retain the shared host lifecycle; this does not create a real broadcast connection.
`design/big-standby-c-particle-assembly.js` owns only particle projection. The host owns particle population, sizes/alpha and the unchanged free-flight calculation.
Run `node design/big-standby-motion-prototype/verify-c-assembly.cjs` for deterministic transition, pause, centred responsive targets, uninterrupted contour/ambient flow, corner continuity, dedicated diamond population and exact OFF-position checks.

## Review votes and notes

Start the preview from the repository root with the review-enabled server:

```sh
uv run --python 3.14 python design/serve_big_standby.py --bind 0.0.0.0 --port 8765
```

The arrows beside the D study selector follow its displayed order, wrap at both ends, and preserve the existing URL/history and pause behavior.
Use the review controls to mark the current numbered study as keep (check) or reject (cross). Selecting the active vote again clears it to unrated.
The same panel has an independently saved Note for the current numbered candidate. Typing saves automatically; the status shows whether it is saved or needs a retry. Clearing the text removes only that note.
A vote or note is saved only after the server confirms it. Reloading or returning to a study restores it; static hosting and `file://` cannot save reviews.

Votes and optional notes are stored by stable study ID in `design/big-standby-motion-prototype/review-votes.json` (local runtime data, ignored by Git).
`GET /api/d-reviews` reads the snapshot; `POST /api/d-reviews` updates one vote or note without replacing other reviews.
Candidate reviews are persisted by the review server, and a custom receiver name is persisted in browser `localStorage` (`mirax_broadcast_name`); local backgrounds, media and other receiver settings retain their existing nonpersistent behavior.

A reject vote does not delete, hide or skip a candidate. Cleanup requires a later explicit user request; only recorded rejects are eligible, not unrated studies.
This is an unauthenticated LAN prototype: anyone who can access this server can read and change votes and notes. Do not expose it to an untrusted network.

Run the server regression checks with `uv run --python 3.14 python -B -m unittest discover -s design -p test_serve_big_standby.py`.

## Implement one assigned study

1. Edit only your assigned numbered `.js` file in this directory.
2. Register one study with the interface below. Read the concept row matching your ID.
3. Keep all implementation helpers inside an IIFE. Use deterministic time-based motion.
4. Return the file, the visible tension mechanism, and three useful observation times.

Done means a complete distinct animation, not a placeholder or a renamed dashed current.
The parent owns the host HTML, script loading, browser checks, preview server and commits.

## Interface

The host initializes `window.miraxDStudies = new Map()` before loading classic scripts.
Use this shape (replace the example metadata):

```js
(() => {
    'use strict';
    window.miraxDStudies.set('study-01-surge', {
        id: 'study-01-surge',
        title: '01 彈射重力脈衝',
        category: '速度與重力',
        description: 'Slow loading, rapid launch, overshoot and recovery.',
        draw(context, frame) {
            // Paint the study using CSS-pixel coordinates and elapsed seconds.
        }
    });
})();
```

`context` is a CanvasRenderingContext2D already transformed to CSS pixels. The host clears it and brackets each call with save/restore.
`frame` contains:

- `width`, `height`: stage size in CSS pixels.
- `time`: elapsed seconds, frozen on pause/background/projection; starts at zero on selection.
- `rails`: two arrays `[startPoint, endPoint]`, running from lower-left toward upper-right, clipped to the visible lower field outside the center band and bottom control strip.
- `arms`: four arrays of polygon vertices for the ORIGINAL X in projected stage coordinates; close paths yourself.
- `diamond`: four projected vertices of the X's central diamond.
- `visibleEdges`: clipped, visible X segments `{ a, b, length, arm }` outside the opaque center information band. `length` is CSS pixels.
- All points have numeric `x` and `y`. Geometry is read-only and may change with viewport/OLED positioning.

The callback paints an overlay behind product information. It owns neither the clock nor the DOM.
Use the supplied rails/arms for registration. Use `visibleEdges` or rails when an upper arm is cropped on wide screens.
Create no timers, animation loops, event listeners, DOM elements, network requests or persistent storage.
Keep source comments English and user-visible titles Traditional Chinese.
White/grey neon lines and geometric overlays fit D; keep luminous area restrained and information readable.
Spatial deformation may affect your overlay, never the shared text/main X. Keep motion visibly evolving, with staggered activity during anticipation/recovery.
Use slow local discontinuities for glitch concepts; no strobe, full-screen flashes or framewise random brightness.

## Concept ledger

| File / registry ID suffix | Category | Distinct tension mechanism |
| --- | --- | --- |
| `01-surge` | 速度與重力 | Slingshot: draw back, load, accelerate, overshoot, recover. |
| `02-impact` | 速度與重力 | Damped impact: rush into a real corner, recoil and dissipate. |
| `03-dual-pulse` | 速度與重力 | Unequal long/short paired cycles with quick contraction and slow release. |
| `04-gear` | 速度與重力 | Change gear at geometric junctions, with distinct acceleration regimes. |
| `05-tide` | 速度與重力 | Asymmetric outward push and inward attraction on multiple tracks. |
| `06-tension` | 幾何張力 | A leading tip stretches a lagging tail, then the tail snaps back. |
| `07-shear` | 幾何張力 | Opposed, asynchronous diagonal motion produces visible shear. |
| `08-diamond` | 幾何張力 | A diamond overlay locks inward and springs outward against the X. |
| `09-vernier` | 幾何張力 | Dense teeth and long strokes beat against each other with changing phase. |
| `10-origami` | 幾何張力 | Hinged geometric overlay panels unfold/reclose with mechanical tension. |
| `11-moire` | 能量干涉 | Fine angular lattices produce travelling interference bands, not random grain. |
| `12-arc` | 能量干涉 | Charge approaches a gap, bridges it in a restrained arc, leaves a damped wake. |
| `13-sonar` | 能量干涉 | Staggered diamond wavefronts expand, compress and cross the two rails. |
| `14-penetration` | 能量干涉 | A narrow oblique sectional beam reveals moving geometric cross-sections. |
| `15-glitch` | 能量干涉 | Coherent signal loss, spatial stutter and recovery without flashing. |
| `16-relay` | 空間場域 | Energy transfers between rails and returns through an angular closed route. |
| `17-cathode` | 空間場域 | Successive geometric trace fronts rapidly construct outlines with a longer decay. |
| `18-parallax` | 空間場域 | Multiple registered overlay depths move with different inertial responses. |
| `19-rendezvous` | 空間場域 | Opposed arrivals accelerate into a meeting, exchange energy and separate. |
| `20-shield` | 空間場域 | Local angular shield facets react to travelling pressure and interference. |

## Check changes

Run `node design/big-standby-motion-prototype/verify.cjs` from the repository root.
For responsive masking, serve `design/` on port 8765 and run `verify-rwd.js` through Playwright's `browser_run_code_unsafe` filename argument.
The browser check covers 20 sizes, three themes (A/C/D), two status messages and both control states (240 cases), plus 120 matching A-brand checks. D must keep all asymmetric plates visible; it checks glyph occlusion, not only non-overlapping boxes. Visual inspection is still required.
This checks all 23 active host registrations and 2,990 deterministic, finite-coordinate, read-only-frame drawing calls. When present, study 191 also checks local growth transitions at cycle boundaries, and 199/203 check that displayed edge counts match the frame. Numbering gaps are intentional. Active descendants and composition 209 keep the shared X fixed. Previous per-frame Canvas operation and paint limits have been withdrawn after the user clarified that they were not a general CPU requirement. Visual quality still requires browser review.
For first-batch historical evidence, read `REVIEW.md`. For second-batch spatial evidence, integration checks and limits, read `FIELD-REVIEW.md`.

`node design/big-standby-motion-prototype/verify-209.cjs` separately checks 5,765
frames across five layouts, including unchanged vertices, finite/read-only
geometry, balanced drawing state and continuity around phase boundaries.
`verify-d-209-ui.js` checks the real Canvas and live dock geometry in five layouts.
These checks do not establish phone/GPU cost or subjective visual acceptance.

The shared prototype also includes A/C/E themes. E uses isolated
`big-standby-e.css` and `big-standby-e-scene.js`, not another D study or renderer.
Its diagonal fictional text streams are decorative, never real telemetry.


## v33 D/E composition

The three selectable themes are A/C/D; old `variant=E` URLs resolve to D.
D retains its original SVG X and central receiver band, adopts E's slash-cut
broadcast frame and diagonal streams, and defaults to the unchanged
`study-118-section-extrusion` (sixth-batch C2). Other study URLs and the full
23-study catalogue remain selectable. E's historical reviews remain stored;
no prior votes or notes are copied into the combined composition.

`big-standby-e-scene.js` and `big-standby-e.css` keep their filenames but now own
only D's decorative streams: three upper/left copy rows and one lower row.
The glyph-edge cathode masks and 240/360-second marquees use CSS animation,
not a new renderer or timer. Raw reduced-motion, pause, hidden, simple home,
projection, custom media and other themes stop this decoration.

Run `verify-e-theme-ui.js` for the combined scene and lifecycle gates;
`verify-v33-sizing-ui.js` checks auto-inflation, equal CSS sizes across DPR/mobile,
manual magnification and action/dock transitions. `verify-v29-controls-ui.js`
retains its historical filename but checks the current pill/triangle/slash-cut
controls. For rail logs, only the body is half-transparent; the first line is
opaque white and every status badge is colored. Other nine styles stay unchanged.


## v34 correction after rejected v33 behavior

The user explicitly removed reduced-motion functionality from this prototype.
There are no system-preference gates, A/C override settings or corresponding
state/listeners. Explicit decoration pause and hidden/media/view gates remain;
manual gyro stop and browser permission are unchanged. Interaction transitions
remain available when decoration is paused. Historical evidence above describes
older versions, not the current policy or a future Android accessibility policy.

The three left-band rows still rise bottom-left to upper-right. Cathode heads
now trace the **band boundaries**, with fading tails, a 4.8s cycle and visible
viewport-local travel, not a mostly invisible glyph-mask sweep over offscreen text.
`verify-e-theme-ui.js` verifies real elapsed movement under both system preference
values, 0.5/1/2s rendered head/tail pixels, seamless loops and six actual pause gates.

C corner layout is measured from actual child bounds. It aligns both block centers
with the action only when all text fits, otherwise retaining near-edge raised
placement. `verify-edge-padding-ui.js` checks real child bounds and different
layout decisions at the same width for OFF and the longer ESTABLISHING state.

`verify-v33-sizing-ui.js` keeps its filename but now verifies the v34 size lock:
fixed CSS-pixel reference fonts, no breakpoint font shrinkage, native pinch locked,
an explicit accessible unlock, relock, keyboard/wheel suppression, 90ms real glyph
blends and theme/dock transitions with the system preference set to reduce.
Browser menu zoom, user-agent overrides and OS magnification are outside a page's
absolute control. Reset browser page zoom to 100% for a fair visual comparison.


## v41 native phases browser verification

Run the deterministic Node checks from the repository root with `node design/big-standby-motion-prototype/verify-native-phases.cjs`.

For a clean-checkout read-only browser verification against local loopback, serve `design/` statically without touching working-tree review data:

```sh
uv run --no-project --python 3.14 python -B -m http.server 8765 --bind 127.0.0.1 --directory design
```

Then run `design/big-standby-motion-prototype/verify-v41-native-phases-ui.js` through Playwright's `browser_run_code_unsafe`. It opens an isolated browser context against `http://127.0.0.1:8765`, aborts non-loopback requests, blocks non-read-only HTTP methods (`405`), and mocks `GET /api/d-reviews`.

`design/big-standby-motion-prototype/review-votes.json` is gitignored local runtime data. On a clean checkout it is absent (HTTP `404`, reported as `beforeHash: null` and `afterHash: null`); on a working tree with local votes it is hashed by raw bytes. The fixture checks `beforeHash === afterHash` (including absent/present transitions) without modifying or requiring local votes.
