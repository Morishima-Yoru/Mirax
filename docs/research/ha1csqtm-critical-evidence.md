# HA1CSQTM critical evidence (session 2026-10-09 20:37)

**Verdict:** The ~550 ms RTP burst pacing is **source-side** and happens even when Host SETs latency **`normal`** (not only `high`). Healthy RTCP does **not** raise ABR on this link. Phone remains **P2P GO**. Settings resolution hard-lock was a Mirax interactive override (fixed); this session advertised Settings/wm modes and Host picked **`1280×800@60`**.

## Required probes — results

| # | Probe | Evidence |
|---|-------|----------|
| 1 | Settings → M3 advertise | Prefs: `checked_standard_modes` = 720p@{24,25,30,50,60} only; `preferredMode` absent; `auto_wm_size_on_connect=true`. Live: `interactive offer … preferred=1280×800@60 modes=6` → `M3 reply preferred 1280x800@60` → **`source selected 1280x800@60`**. No more forced 1080p30. |
| 2 | GO vs client | `phoneIsOwner=true`, `P2P group up (phone is GO)`, `iw … type P2P-GO`, SSID `DIRECT-P6-Phh-Treble vanilla`. **No `goIntent …→0` log** → ExternalApprover path did not run (system/listen path). Intent=0 patch did not apply this join. |
| 3 | Host latency SET | `SET_PARAMETER microsoft_latency_management_capability: **normal**` → `source latency mode normal`. |
| 4 | RTCP vs ABR | `RTCP SR #1…`, `RR #1…80 frac=0 lost=0 sr=… dlsr_ms=…` → RR/SR healthy. |
| 5 | RTP gap + bitrate | After unlock + this negotiate: many `RTP gap_ms≈540–650`; motion sample **br avg≈168 kbps max≈482**, fps avg≈14. Gaps persist under **`normal`**. |

## Critical falsifications

1. **`high` latency SET is not required for ~550 ms bursts.** This session is `normal` and still shows ~550 ms gaps + sparse AUs (`au_since=0` between gaps).
2. **Clean RTCP is not sufficient for ABR climb** on this Phh+Mirax link (frac=0, SR present, advertise `microsoft_max_bitrate=20e6`).
3. **1080p hard-cap was Mirax, not Settings.** Prefs never listed 1080p; earlier build injected `1920x1080@30` via `interactiveModes`. Removed; this session proves Settings/wm path works again.

## Still open (next experiments, not blocking this evidence pack)

- Why Phh always becomes GO (`startListening` / OEM intent) while Fold often leaves Windows as GO (`192.168.137.1` in prior Fold success notes).
- What Host metric (beyond RR loss/jitter) keeps encode near hundreds of kbps–few Mbps on this GO+STA concurrent radio.
- Whether forcing client role (if achievable) changes gap/bitrate distribution.

## Raw anchors (log)

```
MiraxWfdBeacon: P2P group up phoneIsOwner=true owner=/192.168.108.66
MiraxSink: interactive offer … preferred=1280×800@60 modes=6
MiraxSink: source selected 1280x800@60
MiraxSink: source latency mode normal
MiraxRtcp: RTCP RR #1 → 192.168.108.248:7492 frac=0 lost=0 … sr=1 dlsr_ms=25
MiraxSink: RTP gap_ms=551 / 550 / 552 … (repeated)
MiraxH264: size=1280x800@60  bitrate_kbps peaking hundreds under swipe
```
