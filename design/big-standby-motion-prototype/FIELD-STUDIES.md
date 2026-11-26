# Second batch: spatial compositions 21–40

Question: how can D feel alive across its composition rather than around a small lower-left rail?
Implement twenty additional studies, keeping 01–20 unchanged for comparison.

## Drawing contract

Use the same IIFE / `window.miraxDStudies.set(id, descriptor)` contract as `README.md`.
Each new descriptor also sets `scope: 'field'`. The host uses this to protect information from overlays.
Set Traditional Chinese title/description/category; comments remain English.
No DOM, timers, own rAF, random particles, network or storage. Only the selected callback paints.

`draw(context, frame)` receives the previous fields plus:

```js
frame.field = {
    top: { x, y, width, height },
    bottom: { x, y, width, height }
};
```

These are full-width CSS-pixel composition rectangles above/below the central information band.
They follow responsive layout and leave the bottom navigation strip clear. A rectangle can be shallow.
The host clips field studies to these two rectangles and subtracts the visible brand, captions, device,
cast hint and prototype controls, so draw the intended continuous geometry through these masks.
They are not mini-canvases: use `frame.width`/`height` and the two fields to compose one whole scene.
All geometry is read-only. Time is elapsed seconds from the shared pausable clock.

For 21–40, `rails`, `arms`, `diamond`, `visibleEdges` remain compatibility data, not the design scaffold.
Build independent large-scale geometry. Main X and product text remain stationary except their existing tiny OLED shift.
Use white/grey angular outline structures, with restrained luminous area rather than solid bright planes.
Keep meaningful motion evolving; stagger recovery/loading rather than waiting for a single event.

## Spatial acceptance

Each proposal needs a clear moving subject outside the lower-left quadrant.
The group must cover upper-field, opposed-side, cross-diagonal and whole-field compositions.
The parent reviews natural playback, full-page frames and temporal canvas differences.
A stationary large grid with a small moving lower-left packet fails, even if many pixels are drawn.
Do not merely relocate or enlarge a previous study: change the mechanism and spatial organization.

## Assigned concepts

| File / ID suffix | Category | Main composition and distinct mechanism |
| --- | --- | --- |
| `21-perspective` | 第二批 · 上半空間 | Upper full-width trapezoid floor/ceiling lattice; moving vanishing focus bends successive depth lines non-uniformly. |
| `22-suspension` | 第二批 · 上半空間 | Broad suspended angular bridge, two distant anchors and distributed load cause travelling tensile sag across the upper span. |
| `23-shutters` | 第二批 · 上半空間 | Upper panoramic colonnade of deep shutter gates; aperture widths and perspective faces counter-rotate in a cascading mechanical rhythm. |
| `24-rosette` | 第二批 · 上半空間 | Large off-center upper-right polygon rosette, linked concentric polygons exchange angular momentum with opposed directions; wide upper-left chord counterweight. |
| `25-tessellation` | 第二批 · 上半空間 | Upper full-width tiling changes cell aspect ratios under a travelling compression zone; vertices remain connected. |
| `26-counterweight` | 第二批 · 雙側互動 | Tall left/right counterweight towers linked across upper/lower fields; unequal accelerated vertical exchange changes suspension geometry. |
| `27-jaws` | 第二批 · 雙側互動 | Large edge-mounted opposed wedge jaws load inward, mesh and release outside the info band; their clearances drive a changing angular corridor. |
| `28-linkage` | 第二批 · 雙側互動 | Two side portals made of coupled crank-rocker four-bar linkages; nonlinear transmission joins upper and lower lobes without moving text. |
| `29-fans` | 第二批 · 雙側互動 | Paired broad angular fan sectors on opposite edges open as rigid rays with unequal inertial lag, crossing the upper span rather than following X arms. |
| `30-zipper` | 第二批 · 雙側互動 | Full-width macro zigzag seam zips/unzips in opposing upper/lower bands; a travelling lock front transforms the connected topology. |
| `31-fault` | 第二批 · 對角跨區 | Two-dimensional panel field experiences a migrating diagonal fault; adjacent connected blocks shear then realign across upper-left/lower-right. |
| `32-reflection` | 第二批 · 對角跨區 | Long angular ray routes reflect from viewport boundaries; moving incidence creates coherent diagonal billiard paths, not a scanning bar. |
| `33-membrane` | 第二批 · 對角跨區 | Four-corner anchored wire membrane; alternating corner loads pull a connected net across both fields and deform its interior. |
| `34-depth` | 第二批 · 對角跨區 | Large rectangular/trapezoid portals move through a diagonally offset perspective tunnel; acceleration in depth and moving focus alter the whole projected structure. |
| `35-braid` | 第二批 · 對角跨區 | Broad diagonal angular ribbon braid traverses both fields; over/under exchange and longitudinal compression produce changing crossings. |
| `36-quasicrystal` | 第二批 · 全幅場域 | Coherent lozenge tiling undergoes local phason flips travelling across upper and lower fields; topology changes rather than optical grating drift. |
| `37-apertures` | 第二批 · 全幅場域 | Full-width geometric aperture wall; polygon openings counter-rotate and open locally under multiple travelling fronts, not whole-scene breathing. |
| `38-contours` | 第二批 · 全幅場域 | Angular iso-contour field around multiple moving sources; merging and splitting contour islands migrate between top and bottom regions. |
| `39-perimeter` | 第二批 · 全幅場域 | Viewport perimeter locks in an angular sequence; corner brackets feed long structural ribs whose lengths/angles change across the full upper/lower span. |
| `40-vortex` | 第二批 · 全幅場域 | Four long elbow ribbons form an angular turning field around the protected center; unequal rotational inertia exchanges the four quadrants continuously. |

## Delivery

Edit only your assigned numbered JS files. Parent owns host HTML, docs, verifier and browser evidence.
Return completed files, the spatial center of motion, distinguishing mechanism, and three useful observation times each.
Do not claim browser validation unless you actually performed it.
