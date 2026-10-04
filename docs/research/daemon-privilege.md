# setWfdInfo 的權限

daemon 可以從當時那個 ADB shell 視窗分離，分離之後仍是 shell 身分，不會因此變成普通 Android App。

核心差異是 `WifiP2pManager.setWfdInfo()`。Android 14 起明確要求 `CONFIGURE_WIFI_DISPLAY`。AOSP 把它定為 `signature`；較新的原始碼加上 `knownSigner` 例外。它不是使用者可同意的一般執行期權限。

ADB 是取得 shell 身分的通道。程序若在 ADB client 關閉後仍存活，UID 仍是 2000。只改 `AttributionSource` 或 package 名稱，不會把一般 App UID 變成 shell。Wi-Fi P2P service 用 Binder 的 `sendingUid` 查這個權限。

`startListening()` 不是同一道門。普通 App 做 Wi-Fi P2P 需要 `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`，以及依 target SDK 的 `NEARBY_WIFI_DEVICES` 或較舊的位置權限。這些補不上 `setWfdInfo()`。

| 方式 | 能否設定目前這種 Sink beacon | 含義 |
|------|------------------------------|------|
| `adb shell` 下的 `app_process` | 可以。這是驗證過的路徑 | 可以做成 detached launcher，使 ADB client 不必一直連着；程序仍是 shell。關閉 client 後是否仍存活、重開機後如何再啟動，當時沒有實測 |
| 一般安裝的 APK / 前景服務 | 不完整 | 可以拿附近 Wi-Fi 等一般權限，過不了簽章級 `CONFIGURE_WIFI_DISPLAY` |
| 平台簽章或韌體整合的 system app | 可以 | 必須由 OEM / 韌體授予。裝進 `priv-app` 本身不等於通過 `signature` 檢查 |
| Root 或自訂系統映像 | 工程上可行 | 成本與維護最高，不是一般 APK 的發布方式 |

本機 SmartMirroring manifest 自己宣告了 `CONFIGURE_WIFI_DISPLAY`，所以原廠套件具備當權限持有者的條件。沒有證據證明它提供普通 App 可穩定呼叫的公開 broker。見 [app-entrypoints.md](app-entrypoints.md)。

當時的建議是先留在 shell UID，為 `app_process` 做可靠的脫離與重啟入口，分別驗證「關掉 ADB client 後仍在」和「重開機後如何啟動」。不要先把整套搬進普通 APK。若硬性要求不依賴 ADB、root 或韌體簽章，下一題是 Samsung 自己的元件能否打開 Sink，而不是讓一般 APK 假裝擁有 shell 權限。

裝置上 `setWfdInfo` 曾經成功，是既有測試紀錄。這份評估沒有為了寫它而重新操作手機。
