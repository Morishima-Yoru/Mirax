# Second batch: spatial review of 21–40

Date: 2026-10-27. Status: **20 additional implemented proposals, not aesthetic approval.**

## Why the first batch clustered lower-left

The original shared contract encouraged anchoring to `rails` and the cropped X arms. The host then clipped those rails into the lower visible field to avoid the opaque status band. Many otherwise different mechanisms therefore inherited the same lower-left stage. The first review proved moving pixels and compatible controls, but did not adequately judge spatial distribution. This was a design constraint and review failure, not a requirement from the user.

## Prompt-to-artifact audit

| Requirement | Concrete evidence |
| --- | --- |
| Twenty additional different proposals | New files `21-perspective.js` through `40-vortex.js`, defined individually in `FIELD-STUDIES.md`, implemented by four independent Designer lanes. No old proposal was renamed or moved to count as a new one. |
| Stop using the lower-left rail as the common scaffold | Every new descriptor has `scope: 'field'`; implementations use full viewport / `frame.field.top` and `.bottom`, not `rails`. Each has a distinct mechanism and spatial organization. |
| Explore other areas and broad compositions | Five upper-field structures, five opposed-side systems, five cross-diagonal structures and five whole-field systems. Natural-playback temporal differences and full-page visual review locate the actual movement. |
| Retain D identity and old comparisons | Original hollow X, typeface and tiny OLED transform retained. All 20 old JS content hashes match the pre-change snapshot. The chooser contains **40 studies plus 4 baseline/historical modes**. All old 20 were selected in the browser without runtime failure. |
| Protect information without forcing movement into one corner | Host clips new overlays to upper/lower full-width fields and subtracts visible information/control rectangles individually. Actual canvas pixel checks found zero overlay pixels inside eight protected information blocks. |
| Real rendering, not only mock commands | All new 20 played naturally for about 8 seconds at 1280×800. **140 original full-page JPEGs**, four before/after/temporal-change contact sheets; all four sheets inspected. No manual animation time advancement. |
| Responsive behavior | **100 browser cases**: new 20 × 1440×1000, 2560×720, 390×844, 844×390, 667×375. Two natural-time pixel samples each; visible evolution, non-lower-left motion, no document overflow, controls inside viewport and no unexpected page/console error. Six representative proposals per viewport have full-page screenshots. |
| Shared state behavior | **29 assertions passed again using new field-study IDs**, covering pause pixels/clock, paused selection, resume, URL/history/query, native keyboard, legacy modes, active-only rendering, simple home, projection, image/GIF/video overrides, video pause, visibility event wiring, reduced-motion preference and compact controls. |
| Resolution and local preview | Actual DPR2 browser context produced a visible 2× backing canvas. LAN browser loaded all 40 through `192.168.1.111:8765`, with listener bound to `0.0.0.0:8765`. |
| Repeatable code checks | `node design/big-standby-motion-prototype/verify.cjs`: **40 registrations, 5,200 deterministic frozen-frame contract draws** across five ratios and 13 times. Host inline script syntax and `git diff --check` pass. |

## Observed temporal distribution

The table measures changed canvas alpha pixels outside the lower-left screen quadrant over seven natural-time samples. It excludes the static product X and text, which are not painted into this canvas. These values locate activity; they are not aesthetic scores or frame-rate guarantees.

| Study | Main visible moving structure | Outside lower-left |
| --- | --- | --- |
| 21 | Full-width upper perspective canopy, changing focus and bent depth lines | 100.0% |
| 22 | Broad upper suspension bridge, moving distributed load | 100.0% |
| 23 | Upper panoramic counter-rotating shutter colonnade | 100.0% |
| 24 | Large upper-right polygon momentum wheel and left counterweight chord | 100.0% |
| 25 | Connected upper tiling under a travelling compression field | 100.0% |
| 26 | Left/right counterweight towers and spanning suspension exchange | 84.6% |
| 27 | Opposed wedge jaws transforming upper/lower clearances | 75.9% |
| 28 | Coupled four-bar portals on both sides | 82.5% |
| 29 | Opposed rigid fan bones spanning both fields | 76.1% |
| 30 | Large zigzag seam, opposed top/bottom locking fronts | 73.3% |
| 31 | Broad panel field with migrating diagonal fault and realignment | 82.9% |
| 32 | Moving incidence and coherent reflected routes across the bounds | 85.0% |
| 33 | Four-corner wire membrane, distributed shear and load | 80.7% |
| 34 | Diagonal perspective tunnel and accelerating portals | 76.9% |
| 35 | Broad upper-left / lower-right angular over-under braid | 91.7% |
| 36 | Coherent lozenge field with travelling local topology flips | 82.2% |
| 37 | Full-width local polygon apertures and counter-rotation fronts | 81.2% |
| 38 | Multiple moving sources, angular contour-island merging and splitting | 85.8% |
| 39 | Perimeter corner locks feeding long changing ribs | 79.4% |
| 40 | Four long turning elbow ribbons with unequal inertia | 80.0% |

## Evidence and limits

Evidence archive: `C:/Users/User/AppData/Local/Temp/opencode/d-field-review/`.

- `study-21-*` … `study-40-*`, numbered 1–7 JPEGs: original natural-time full-page frames.
- `field-group-0.jpg` … `field-group-3.jpg`: before/after frames plus temporal change maps.
- `study-*-WIDTHxHEIGHT.jpg`: responsive examples.
- `first-batch-hashes.json`: protected old-20 source hashes.
- `artifact-manifest.json`: final source hashes and check counts.
- `runners/`: archived local review and boundary scripts; no test media is uploaded to a server.

Very short landscape screens have shallow lower fields; text masks can remove most lower geometry in some phases, while broad upper movement remains visible. Information readability is preferred to drawing over text. No Android implementation, physical receiver benchmark, long-duration OLED test, external-phone connectivity test, or user aesthetic approval is claimed. Visibility behavior uses an explicit synthetic hidden getter/event. The video fixture proves override/pause state only, not the visual validity of an earlier recording. No dependencies, persistence, automatic carousel, commits, pushes or merges were added.
