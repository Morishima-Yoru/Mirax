# D motion exploration — review record

Date: 2026-10-27. Status: **20 implemented proposals for comparison, not user-approved designs.**

## Deliverables and evidence

| Requirement | Artifact / evidence |
| --- | --- |
| At least 20 geometrically distinct D ideas | `README.md` concept ledger and numbered `01`–`20` JavaScript files. Each has a separate Designer implementation and Traditional Chinese runtime explanation. |
| More than constant-speed currents | Five nonlinear motion studies, five mechanical geometry studies, five interference studies, five spatial-field studies. Each registers an independent drawing implementation; these are not aliases or dash-speed presets. |
| Direct comparison | Existing `../big-standby-prototype.html?variant=D&motion=study-01-surge`; native grouped chooser contains all 20. `signal`, `scan`, `quiet` and `off` remain compatible. |
| Stable D identity | Original hollow double-outline X and typography retained. Only overlay geometry changes; shared original text/X retain the existing tiny OLED shift. No Android source changes. |
| One controllable animation | Host clears a single study canvas and invokes only the selected callback through the existing pausable rAF clock. |
| Actual visual review | Every study played naturally for approximately 8 seconds at 1280×720; six real full-page JPEGs per study, no manual animation-clock advancement. Four contact sheets were generated from those JPEGs and inspected. |
| Multiple aspect ratios | All 20 selected and sampled twice at each of 1440×1000, 2560×720, 390×844, 844×390 and 667×375: **100 browser cases**. Each had evolving visible canvas pixels outside the center band, no document overflow or unexpected runtime error. Six representative studies per viewport also have full-page screenshots. |
| Existing boundaries | **29 browser assertions passed**: pause pixels/clock, paused selection, resume, history, reload/query preservation, native keyboard, four legacy modes, active-study-only drawing, simple home, projection, image/GIF/video override/removal, video pause, visibility event wiring, reduced-motion preference and compact control separation. |
| Recovery and compatibility | Additional browser checks cover A/B/C/D canvas visibility and deliberately thrown study errors: logged once, failed overlay hidden, chooser can recover to another study. |
| LAN | Listener observed at **0.0.0.0:8765**. HTTP 200 and actual browser navigation to `http://192.168.1.111:8765/` successfully loaded the host and all 20 studies. |

## Repeatable local check

```sh
node design/big-standby-motion-prototype/verify.cjs
```

Result: **20 registrations, 2,600 deterministic contract draws passed**, across the five aspect ratios and 13 observation times. The verifier also checks host script paths, unique registry IDs, balanced drawing state, finite coordinates and frozen input geometry.

This is deliberately not a visual-quality assertion: different commands or pixels alone do not prove a good animation. The real JPEG sequences and their geometric integration were reviewed separately.

## Representative comparisons

| Group | IDs and visible mechanism |
| --- | --- |
| Nonlinear mechanics | `01-surge`: pullback / slingshot; `02-impact`: corner impact / damping; `03-dual-pulse`: unequal paired wave rhythm; `04-gear`: junction gear changes; `05-tide`: asymmetric push / pull. |
| Geometric tension | `06-tension`: head-tail strain; `07-shear`: opposed shear trusses; `08-diamond`: diamond linkage / lock; `09-vernier`: tooth alignment beats; `10-origami`: hinged rigid panels. |
| Interference | `11-moire`: lattice coherence; `12-arc`: angular charge bridging; `13-sonar`: diamond wavefield refraction; `14-penetration`: clipped structural cross-sections; `15-glitch`: local signal loss / reconstruction. |
| Spatial fields | `16-relay`: closed paired-rail transfer; `17-cathode`: successive outline construction; `18-parallax`: inertial depth overlays; `19-rendezvous`: opposing momentum exchange; `20-shield`: local pressure-responsive facets. |

Use the chooser's visible descriptions or the state inspector to read the selected concept. No automatic carousel or new product settings were added.

## Issues found and fixed during review

- `06` and `11` initially consumed nonexistent frame fields. They now use the exact documented `rails`, `arms` and `visibleEdges` contract.
- The host initially referenced 15 obsolete study filenames. All script paths now match the actual numbered files and are checked by the verifier.
- Full diagonal rails sent some spring phases behind the opaque information band, producing blank visible frames. Study rails are now clipped to the visible lower field; historical currents are unchanged.
- At 720px height, captions and receiver information conflicted with the center band. Responsive caption positioning and black text backplates preserve readability without changing the typeface or X.
- Compact landscape navigation overlapped the longer motion chooser by approximately 9px. The chooser now starts at 324px, leaving a measured gap after the 308.64px navigation edge at 667×375.

## Evidence location

Local evidence is archived under:

`C:/Users/User/AppData/Local/Temp/opencode/d-studies-review/`

- `group-0-real-time.jpg` … `group-3-real-time.jpg`: six natural-time samples for each study.
- `study-*-1.jpg` … `study-*-6.jpg`: original full-page frames.
- `study-*-WIDTHxHEIGHT.jpg`: representative responsive screenshots.
- `final-667x375.jpg` and `boundaries-final-844x390.jpg`: final compact controls.
- `legacy-and-runners/`: archived scratch runners and earlier untracked evidence, including unreliable recordings. Those recordings are **not** used as visual evidence; one decodable WebM was used only for media override/pause state checks.

## Limits

No Android build, receiver-device performance benchmark, real casting, long-duration OLED assessment, or external-phone connectivity test was performed. Fine neon lines and moire anti-aliasing may look different on the target display. Browser visibility handling was exercised with an explicit synthetic `document.hidden` getter and event, not Android lifecycle automation. Sampling and screenshots establish this prototype's observed behavior, not a guaranteed frame rate or acceptance of its aesthetics.
