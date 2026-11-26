# Fourth batch: three descendants per surviving candidate

Question: after the current review cleanup, which mechanism should each surviving design explore next?

The second cleanup removes 20 active rejects. Nine originals remain: seven kept (14, 17, 20, 36,
45, 51, 58) and two unrated (09, 34). Each receives three new, independently implemented proposals.
New IDs 66-92 start unrated. Original files and saved decisions stay unchanged; removed IDs are not reused.

## Implementation contract

Read the assigned source and the frame contract in `README.md`. Each file is a self-contained IIFE
registering one descriptor with `id`, `sourceId`, numbered Traditional Chinese `title`, `description`,
the category below, and deterministic `draw(context, frame)`.

`sourceId` identifies the original, not another new variant. This batch leaves the actual X body
unchanged: no `xMotion`. Draw connected geometric structure, with non-uniform evolution in shape,
spacing, depth, junctions or topology. Distinct mechanisms matter; changing speed/brightness or
renaming a source does not answer the question. Keep line work restrained but visible in playback.

Only the 34/36/45 descendants set `scope: 'field'`. They compose across the whole viewport using
`frame.field.top` and `.bottom`; these are not separate mini-canvases. The host clips their drawings
around actual foreground plates. Mark-based descendants of 09/14/17/20/51/58 leave scope unset,
so clipping does not erase the X arms they operate on. Use projected `arms`, `diamond`, `rails`,
and `visibleEdges`; tolerate missing/zero-length/off-screen segments and empty field rectangles.

The frame has `width`, `height`, `time`, `field`, `rails`, `arms`, `diamond`, `visibleEdges`.
No `band`, `xGeometry` or DOM references are supplied. The frame is read-only. Time is shared,
elapsed seconds, and pauses/resets with the host. Helpers belong inside the IIFE; no timers,
own animation loops, storage or network. English comments, four-space indentation.

Preserve hollow double-outline X, black/white hard-line composition, existing typography and the
asymmetric responsive plates. Keep all information on its current OLED-only drift. New motion
belongs to the overlay, with connected contours/facets rather than floating bars or particles.

## Assignment and mechanism questions

| Original | New files | Category | Three different questions |
| --- | --- | --- | --- |
| 09 vernier | `66-vernier-slip`, `67-vernier-weave`, `68-vernier-lock` | 第四批 · 09 咬合延伸 | Can a travelling slip defect change engagement? Can two connected tooth banks braid without sliding dashes? Can a compliant latch deform and transfer lock through corners? |
| 14 penetration | `69-curved-section`, `70-folded-section`, `71-depth-slice` | 第四批 · 14 穿透延伸 | What changes with a curved cutting surface, a hinged cutting surface, or depth-separated sectional shells rather than a straight beam? |
| 17 cathode | `72-cathode-orbit`, `73-cathode-nodes`, `74-cathode-weave` | 第四批 · 17 描邊延伸 | Can one coherent contour circuit construct the mark? Can geometric corner nodes negotiate the trace path? Can crossing trace paths weave through the four arms without a generic return sweep? |
| 20 shield | `75-shield-shingle`, `76-shield-hinge`, `77-shield-torque` | 第四批 · 20 護場延伸 | What changes when shield facets slide as connected shingles, open as paired hinges, or flex under a transferred torque? |
| 34 depth | `78-depth-fork`, `79-depth-gimbal`, `80-depth-fold` | 第四批 · 34 縱深延伸 | Can the corridor split toward two vanishing points, turn through a geometric gimbal, or fold its depth planes rather than simply advance portals? |
| 36 quasicrystal | `81-quasi-defect`, `82-quasi-domains`, `83-quasi-twin` | 第四批 · 36 準晶延伸 | What if flips follow a bound dislocation, two moving domain interfaces, or a mirrored twin boundary? Avoid a renamed strain/row-slide preset. |
| 45 growth | `84-growth-branch`, `85-growth-merge`, `86-growth-void` | 第四批 · 45 生長延伸 | Can growth branch through connected cells, merge neighbouring crystalline domains, or renew a moving void while retiring older cells? |
| 51 cross-sections | `87-section-hinge`, `88-section-sliding`, `89-section-tomograph` | 第四批 · 51 交切延伸 | Can the crossing planes hinge around their intersection, slide interacting section windows, or reconstruct offset depth layers? Distinguish these from 14's surface-shape studies. |
| 58 spark relay | `90-spark-bifurcate`, `91-spark-pair`, `92-spark-threshold` | 第四批 · 58 接力延伸 | Branch the connected relay, use paired angular bridges, or conduct through corner thresholds. |

The file names are ID suffixes; append `.js`. Titles identify the source, e.g. `66 · 09-A ...`.
All three siblings should be visibly distinct at normal scale, not speed or brightness presets.

## Delivery

Each lane edits only its nine assigned numbered files. The parent owns the host, docs, verifier, cleanup and browser evidence.
Use the real fixture/trace from `verify.cjs` for finite coordinates, frozen-frame determinism and context-stack balance.
Return completed files, distinct mechanisms, spatial focus, three useful observation times and honest limits.
The parent checks actual natural playback with the originals. Implemented options are not aesthetic approval.
