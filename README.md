# Mirax

Use a Galaxy Z Fold 5 inner display as a Windows Miracast second screen. Windows stays on the built-in Win+K flow. The phone owns the sink.

Vocabulary is in [`CONTEXT.md`](CONTEXT.md). The accepted design is in [`docs/design.md`](docs/design.md), the decision record is in [`docs/adr/0001-standalone-sink.md`](docs/adr/0001-standalone-sink.md), and the research index is in [`docs/research/README.md`](docs/research/README.md).

## Devices

| Item | Value |
|------|--------|
| Phone | Samsung Galaxy Z Fold 5 (`SM-F946U`, Android 16, `F946USQS8GZE8`) |
| Inner display | Portrait 1812×2176; landscape target 2176×1812, 420 dpi |
| Cover display | 904×2316 |
| PC | ASUS Zenbook Duo UX8406CA, Windows 11 Pro 25H2, Intel Arc 140T |

## Code

The current receiver is in [`receiver/`](receiver/). `receiver/build.ps1` runs the protocol tests, then produces the shell jar and the picture apk.

Scripts that open Win+K, select the Fold, and inspect the connect window are in [`scripts/`](scripts/).

[`.archive/`](.archive/README.md) keeps replaced project code for reference. The Samsung player launcher, decompiles, and hooks are not in it.

## For agents

Issues live in `Morishima-Yoru/Mirax`. Read [`AGENTS.md`](AGENTS.md).
