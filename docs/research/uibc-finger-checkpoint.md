# UIBC finger checkpoint (2026-10-18)

**PASS — finger only.** Base `origin/fix/touch-input` at `f34b12935f0c2fac2e6cfc697f4178be6111d44a`; branch `verify/uibc-finger-20261017`. Tested APK SHA-256 `07ca5f79a8639fc53d0e06906d9332be7250590dbdce9f83313e404f20e6770b` on ADB device `HA1CSQTM` (`TrebleDroid vanilla`). Windows 11 Cast selected that device; P2P group formed, video mode `1920x1200@60`, input permission **On**. USB/ADB drove the gesture; Windows events below came through Miracast, not an ADB pointer query.

All raw logs, packet capture and phone screenshots for **one connection** are in `C:/Users/User/AppData/Local/Temp/opencode/mirax/finger-20261018-03/` (kept outside Git per `.agents/e2e-testing.md`). In particular: `handshake.log`, `handshake-followup.log`, `uibc.etl`, `uibc-wire.txt` (PktMon text), `wire-evidence.txt`, `pointer-probe-console.log`, `windows-touch-target.log`, `visible-before.png`, `visible-after.png`. `handshake.log` SHA-256 `a1254fc29b4472d978fa228a9127893ed628c637cb64b486e5f3195ad261b9c4`; `uibc.etl` SHA-256 `ec4f85fb63b77e3c96babb7fbf24ec720bf65aa60fad758bfc44bef4cb86d1cb`.

## Same-session handshake and source-side bytes

- `11:44:08.971` M3 requested `wfd_uibc_capability`; M3 response advertised `SingleTouch/USB, MultiTouch/USB`.
- `11:44:16.037` M4 source returned `hidc_cap_list=SingleTouch/USB, MultiTouch/USB;port=50000`; sink selected HID type `2` (`SingleTouch`).
- `11:44:16.223` sink sent M15 `wfd_uibc_setting: enable`; `11:44:16.227` source accepted it; `11:44:16.229` descriptor sent (`135B`, `1920x1200`). Windows Cast input checkbox was Off on this connection, then explicitly switched On.
- Windows PktMon `--comp all --pkt-size 0` captured TCP from the phone to the source's port 50000. HIDC wire header `00 01 00 90 01 02 01 00 87` precedes the 135-byte Report ID 1 touch descriptor. Full 144-byte HEX is in `wire-evidence.txt` (SHA-256 `72781679b24d2ffe87a6895040de6cad9babe7626d09953725b0a05972d14195`). Sample source-received reports (HIDC type `02`, Report ID `01`):

  ```text
  DOWN 00 01 00 14 01 02 00 00 0a 01 07 00 c0 03 d5 01 87 3d 01 00
  UP   00 01 00 14 01 02 00 00 0a 01 06 00 4d 04 d5 01 dd 56 01 00
  ```

## Actual Windows outcome

`adb shell input swipe 400 500 550 500 650` on the phone while the Windows pointer probe was running produced:

```text
[11:45:42.636] POINTER-DOWN type=TOUCH id=14 x=1279 y=365 active=1
[11:45:43.195] POINTER-UP type=TOUCH id=14 x=1465 y=365 active=0
[11:45:52.216] SUMMARY downs=1 maxConcurrent=1 distinctIds=1
```

The same Miracast connection then received `adb shell input tap 400 600`. The Windows click target changed visibly from orange `TOUCH TARGET` (`visible-before.png`) to green `WINDOWS CLICK RECEIVED` (`visible-after.png`); its Windows-side log recorded `2026-10-18T11:46:42.2938300+08:00 WINDOWS_CLICK`. The corresponding Android `forwardPictureTouch` down/up was at `11:46:39.304` / `11:46:39.308` (device and host clocks differ). This proves Windows interaction, not merely packet writing.

The original probe mislabeled `GetPointerType` failures as `TOUCH`. After correcting the probe to report such failures as `UNKNOWN`, a repeat on **this same connection** confirmed the Win32 API's actual pointer type (see `strict-repo-console.log`, verified probe EXE SHA-256 `830ad5ef461ae7cd6212da2a1dd920b70cc38f2ea0d570e5a32b2830531d8bd2`):

```text
[11:56:20.352] POINTER-DOWN type=TOUCH id=17 x=1279 y=240 active=1
[11:56:21.135] POINTER-UP type=TOUCH id=17 x=1466 y=240 active=0
[11:56:30.833] SUMMARY downs=1 maxConcurrent=1 distinctIds=1
```

Focused JVM tests: `WfdProtocolTest` 22, `UibcPacketsTest` 17, `UibcTouchChannelTest` 8, all passing; `:app:assembleDebug` passed. Multi-pointer, Android stylus/synthetic pen, coexistence and physical S Pen **are not verified by this checkpoint**. No Windows VHF driver/service was added.
