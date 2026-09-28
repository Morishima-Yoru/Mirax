# Privileged entry points

Research date: 2026-09-27.

The only additional non-root, non-ADB entry point found that could plausibly activate the Samsung-owned receiver is Samsung Knox device management: `ApplicationPolicy.setApplicationComponentState()` to enable the disabled `ScreenSharingTile`, then let System UI bind that Samsung `TileService`. This is a conditional enterprise-management route. It has not been tested on the Fold 5. Enabling the tile may still not start the WFD sink on this model.

This Knox lead does not run the project daemon as shell or system. It only offers a possible way to activate Samsung's own component, so Samsung's signing privileges can run Samsung's startup path. A Knox Device Owner app running project code would still not receive `CONFIGURE_WIFI_DISPLAY`.

A normal app or boot receiver that calls `setWfdInfo()` stays blocked by that signature / known-signer permission. Knox can change a component's enabled state. It does not grant the permission to a custom receiver.

## Candidates

| Entry point | Assessment |
|-------------|------------|
| Quick Settings `ScreenSharingTile` | System UI might bind Samsung's tile service. The tile is disabled on this Fold, and `pm enable` was rejected. Knox might toggle it. Fold behavior is unverified. |
| Knox `setApplicationComponentState()` plus `startApp()` | Strongest remaining lead for Samsung-owned code. Requires a Knox-authorized app holding `KNOX_APP_MGMT` and, on Android 15 / Knox 3.11+, Device Owner or Profile Owner. `startApp()` takes no Intent extras. A successful return does not prove launch. Samsung documents component-state management as unavailable from Android 17. Fully managed enrollment of an existing device requires factory reset. |
| Android Enterprise `DevicePolicyManager.enableSystemApp()` | Re-enables a whole system package. It does not target the disabled tile and does not grant WFD configuration. |
| Call `SmartMirroringService` directly | Guarded by Samsung's signature/system custom permission. `SecondScreenPlayer` plays a stream and does not initialize the sink. |
| Accessibility, automation, boot receiver, intent launcher | Cannot cross the signature check for `setWfdInfo()` or a Samsung-protected service. |
| Shizuku / wireless debugging / shell wrapper | Still shell authorization. It does not remove the after-reboot bootstrap. |

## What is not established

If Knox enables `ScreenSharingTile`, the least invasive test is whether the tile appears in the Quick Settings editor and whether a tap advertises the WFD sink. That tests Samsung's component. It does not give `CONFIGURE_WIFI_DISPLAY` to a custom app.

Unknowns before calling this viable:

1. Whether this Fold 5 firmware lets a Knox-authorized DPC enable this protected tile.
2. Whether the tile can be added to Quick Settings after enabling.
3. Whether its click path starts listening on Fold 5.
4. Whether Samsung's lazy-boot policy disables the tile again after reboot.

A fully managed enrollment can wipe the phone. Do not attempt it without explicit approval. A work-profile Owner may not have scope over the Samsung component in the personal profile.

## Sources

- [Android `WifiP2pManager.setWfdInfo()`](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.html)
- [AOSP Android 16 manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml)
- [Samsung Knox `ApplicationPolicy`](https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/application/ApplicationPolicy.html)
- [Samsung restricted Knox SDK methods](https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/)
- [Samsung Knox licenses](https://docs.samsungknox.com/dev/knox-sdk/introduction/about-licenses/)
- [Samsung fully managed enrollment](https://docs.samsungknox.com/admin/knox-manage/configure/devices/enroll-devices/enroll-a-single-device/)
- [Android `TileService`](https://developer.android.com/reference/android/service/quicksettings/TileService)
- [App entry points on this device](app-entrypoints.md)
