# 普通 App 能不能啟動 Sink

檢查對象是 Z Fold 5 上的 SmartMirroring `8.2.27.28`。目標是找一個一般 sideload APK 可穩定呼叫、重開機後不必再接 ADB、且不要求 root 的 Sink 啟動入口。2026-09-27 對連線中的 `SM-F946U` / Android 16 做了唯讀 PackageManager 查詢，並嘗試一次只啟用 tile 的 `pm enable`。系統拒絕，元件狀態沒有被改動。

## 結論

沒有找到能由一般 App 穩定呼叫、並完整啟動 Miracast Sink 的已證實入口。

最接近的 broker 候選是 exported `SmartMirroringService`，受該 APK 自訂的 `signatureOrSystem` 權限保護。Quick Settings tile 由系統綁定，manifest 預設停用。ADB shell 的 `pm enable` 也被 Android 16 PackageManager 以 `SecurityException` 拒絕。唯一沒有額外元件權限且 exported 的 `SecondScreenPlayer` 是播放 Activity，不負責 `setWfdInfo()` 與 P2P listen。

開機廣播只能啟動 App 自己。它補不上 `CONFIGURE_WIFI_DISPLAY`。

## 元件

| 元件 | Manifest | 對一般 App |
|------|----------|------------|
| `SmartMirroringService` | exported，要求 `com.samsung.android.smartmirroring.API`，protection level `0x3` | `0x3` 對應 `signatureOrSystem`。manifest 沒有 intent-filter 契約，不能從名字推斷它會啟動 Sink |
| `ControllerService` | exported，自訂權限，同樣 `0x3` | 不是普通 App 的入口 |
| `SecondScreenPlayer` | exported，無元件級 permission | 一般 App 可以啟動它。它不是已證實的 Sink bootstrap |
| `SecondScreenActivity` | 非 exported；裝置上還在 `disabledComponents` | 外部 App 不能明確啟動 |
| `ScreenSharingTile` | TileService，exported，要求 `BIND_QUICK_SETTINGS_TILE`，`enabled=false` | 由 System UI 綁定。當時的 Quick Settings 清單裡沒有這個 tile |

2026-09-27 在 `SM-F946U`、`F946USQS8GZE8` 上執行的啟用命令被拒絕：

```text
pm enable --user 0 com.samsung.android.smartmirroring/com.samsung.android.smartmirroring.tile.ScreenSharingTile
```

回覆是 `SecurityException: Shell cannot change component state ... to 1`。之後的唯讀 `dumpsys package` 確認 tile 沒進入 `enabledComponents`，`SecondScreenActivity` 仍在 `disabledComponents`。`settings get secure sysui_qs_tiles` 裡也沒有這個 tile。同一條 shell provisioning 在這版韌體上被排除。

APK 只有非 exported 的 `LAZY_BOOT_COMPLETE` receiver，沒有一般的 `BOOT_COMPLETED` Sink receiver。Samsung 自己的開機回呼會更新 Second Screen 元件狀態，所以即使某次啟用成功，也不能先假設重開機後仍開着。

Shizuku / 無線偵錯可以當免電腦的 shell 通道。Shizuku 說明非 root 的無線偵錯啟動每次重開機都要重做，不符合「重開機後不再接 ADB」的要求。

AndroidX MediaRouter 發布的是 Android 應用之間的媒體路由，不會把手機註冊成 Windows Win+K 看得到的 Miracast sink。

## 權限邊界

`WifiP2pManager.setWfdInfo()` 要求 `CONFIGURE_WIFI_DISPLAY`。AOSP Android 16 將它定義為 `signature|knownSigner`，並列出受信簽章。較舊版本可以只有 `signature`。`exported` 只表示其他 App 能否進入元件；元件另有 `android:permission` 時，呼叫者仍要持有該權限。

## 其他候選

| 候選 | 判斷 |
|------|------|
| 一般 App + 開機自啟 | 對完整 Sink 不成立。開機 receiver 啟動的是自己的 App |
| Knox 管理 App | 條件式企業實驗。可以嘗試打開 Samsung 元件，不會把 `CONFIGURE_WIFI_DISPLAY` 授給第三方。見 [privileged-entrypoints.md](privileged-entrypoints.md) |
| 換 Samsung 支援的 Tab | 官方 Win+K 路徑在 Tab 上。那不是 Fold 5 |

目前沒有已證實的「普通 App、Fold 5、Windows Win+K、免 root、重開機後不需 ADB」方案。Knox 會改變裝置管理前提，可能要求清除並重新註冊，而且沒有證明 Fold 5 的原生 Second Screen 能持續可用。
