# Mirax

Use a supported Android phone as a Windows Miracast second screen. Windows stays on the built-in **Win+K** flow. The phone owns the sink: discovery, RTSP, decode, and UIBC touch.

Vocabulary: [`docs/CONTEXT.md`](docs/CONTEXT.md). Design: [`docs/design.md`](docs/design.md).

## Requirements

| Item | Detail |
|------|--------|
| Phone | Android 11 (API 30)+, Wi‑Fi available |
| PC | Windows 11 with inbox Win+K Miracast |
| Privilege | Most Samsung models need [Shizuku](https://shizuku.rikka.app/) (see below) |

Miracast sink advertising calls `WifiP2pManager.setWfdInfo()`, which requires `CONFIGURE_WIFI_DISPLAY` (signature / known-signer). A normal Play-signed APK alone is **not** enough.

## Install (Shizuku path, recommended)

1. Install and start [Shizuku](https://shizuku.rikka.app/) on the phone; enable it via wireless debugging or root as documented there.
2. Download the `v0.0.1` APK from [GitHub Releases](https://github.com/Morishima-Yoru/Mirax/releases) and sideload it.
3. Open Mirax and grant Shizuku access when prompted.
4. Turn on broadcast in Mirax (or use the Quick Settings tile).
5. On Windows, press **Win+K**, pick the phone, and allow input from the device when you want touch.

> The Release APK is signed with the Android debug keystore for sideload/testing only. It is not a Play Store signing key.

## Other privilege paths

| Target | How Mirax gets the privilege |
|--------|------------------------------|
| **Samsung (many models)** | Install Shizuku and authorize Mirax. Shell UID 2000 can call `setWfdInfo` on builds that still grant that permission to `com.android.shell`. |
| **AOSP / test-keys GSI** | Install the `platform` build type (signed with the public AOSP platform test key). See [`signing/README.md`](signing/README.md). |
| **Rooted** | Magisk / KernelSU / `su`. Mirax starts the helper entry as UID 0 so the sink beacon runs with full WFD permission. |

Stock OEM phones that grant `CONFIGURE_WIFI_DISPLAY` neither to shell nor to a sideloaded APK (and are not rooted) are **out of scope**. The in-app Compatibility page explains that case when broadcast cannot start.

## Build from source

```powershell
.\gradlew.bat :app:assembleRelease
```

Output: `app/build/outputs/apk/release/`.  
For AOSP test-keys devices: `.\gradlew.bat :app:assemblePlatform`.

## Code layout

- [`app/`](app/) — Android sink UI and receive path
- [`helper/`](helper/) — Privileged WFD beacon / helper entry (also compiled into the app for Shizuku user-service and root `app_process`)
- [`scripts/`](scripts/) — Win+K helpers
- [`docs/research/`](docs/research/) — Key research notes

## For agents

Issues live in `Morishima-Yoru/Mirax`. See [`.agents/AGENTS.md`](.agents/AGENTS.md).
