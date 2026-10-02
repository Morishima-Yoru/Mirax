# 交接：備用機連不上 Miracast

這份文件給下一台電腦上的 agent。開發機是 `Leviana-UX8406CA`。使用者要把 session 搬到備用機 `DESKTOP-2JHJLHH` 繼續。

使用者已更正：備用機沒有連上。Win+K 的結果是失敗，不是「連上了但沒畫面」這種使用者可見的成功。

## 不要做的事

不要在 Parsec 視窗裡自己按 Win+K，也不要自己點投影面板。開發機上的 Parsec 視窗座標會跑到負座標螢幕，按鍵常常送到本機而不是備用機。使用者已看過這個失敗。

下次要在備用機發起連線時，直接請使用者按 Win+K 並點手機。使用者會在備用機上操作。按完之後再讀手機日誌。

## 機器

| 角色 | 名稱 | 位址 | 備註 |
|------|------|------|------|
| 開發機 | `Leviana-UX8406CA` | `192.168.1.105` | 這次 session 的工作目錄。Win+K 連得上手機 |
| 備用機 | `DESKTOP-2JHJLHH` | `192.168.1.107`（`DESKTOP-2JHJLHH.local`） | 連不上。可由開發機的 Parsec 看到畫面，但不要用它代按 Win+K |
| 手機 | `SM-F946U` / `q5q`，序號 `RFCW91N2T2T` | USB adb | Android 16，SDK 36。adb 在開發機上 |

備用機的 Wi-Fi Direct MAC 曾是 `10:f0:05:10:fc:49`，舊群組名 `DIRECT-FMDESKTOP-2JHJLHHLWOI`。開發機舊群組是 `DIRECT-nkLEVIANA-UX8406CATKS`，GO MAC `40:c7:3c:ce:45:a1`。這些持久群組已在手機上刪除。

## 目前手機上在跑什麼

最後一次觀察（約 2026-09-29 21:07 之後）：

- App `me.trinitrix.mirax` 已安裝含這次修改的 debug APK。
- 廣播由 Shizuku user-service `me.trinitrix.mirax:wfd` 負責，不是 `/data/local/tmp/mirax-helper.jar`。
- `WfdOwnerBridge.USER_SERVICE_VERSION` 已從 2 改成 3，讓 Shizuku 載入新的 dex。
- 廣播名稱是 `Renathan's Z Fold5`。
- 當時的廣告模式只有 `1812×2176@60`（直向）。CEA / VESA / HH 位元全是 0。
- `dumpsys wifip2p`：`mListenStarted true`，`numPersistentGroup=0`。
- 接收執行緒 `mirax-sink` 在 app 程序裡。

到備用機後先重查，不要假設程序還活著：

```powershell
adb devices -l
adb shell dumpsys wifip2p
```

看 `mListenStarted`、`numPersistentGroup`、`mGroup`，以及 `ps` 裡有沒有 `me.trinitrix.mirax:wfd`。

## 這次已經做了什麼

目標是：找得到 beacon 的新電腦，也能被手機自動接受，而不是只有開發機靠舊的 Wi-Fi Direct 群組重連。

`PrimarySinkBeacon`（手機上實際在跑的）和 `MiracastReceiver`（已移除的舊 shell receiver，同一種寫法）都改了：

1. 在 `startListening()` 之前，用 `MacAddress.BROADCAST_ADDRESS`（`ff:ff:ff:ff:ff:ff`）註冊 external approver。Android 13 之後，沒有單一 MAC approver 的新 GO negotiation / invitation 會改彈系統確認對話框；shell 程序不會去按。Framework 在找不到該 MAC 時會改用這個廣播位址。見 AOSP `WifiP2pServiceImpl.notifyInvitationReceived`。
2. Framework 在 `CONNECTION_REQUEST_ACCEPT` 之後會拆掉 approver（`detachExternalApproverFromPeer`）。`onDetached` 在 reason 不是 `APPROVER_DETACH_REASON_REPLACE` 時會再掛上廣播 approver。`REPLACE` 不能再掛，否則會和剛換上的註冊互相取代。
3. 開始廣播時，以及群組拆掉之後，用隱藏 API `requestPersistentGroupInfo` / `deletePersistentGroup` 刪掉全部持久群組。shell UID 2000 有 `NETWORK_SETTINGS` 與 `OVERRIDE_WIFI_CONFIG`，這兩個呼叫在這支手機上成功過。P2P 還在 `P2pDisabledState` 時刪除會失敗，所以刪除排在 `setWfdInfo` 成功之後、`startListening` 之前。
4. 要刪的 network id 由 `SavedP2pGroups.persistentNetworkIds` 決定：保留 `>= 0` 的 id，丟掉暫時 id。

相關檔案：

- `helper/src/me/trinitrix/mirax/wfd/PrimarySinkBeacon.java`
- `helper/src/me/trinitrix/mirax/wfd/SavedP2pGroups.java`
- `helper/src/me/trinitrix/mirax/helper/Helper.java`（可選的第一個參數會立刻 `startAdvertising`）
- `helper/build.ps1`（編譯清單加上 `SavedP2pGroups.java`）
- 歷史來源路徑（receiver 模組已移除）：`receiver/src/com/secondscreen/receiver/MiracastReceiver.java`
- `app/src/main/java/me/trinitrix/mirax/wfd/WfdOwnerBridge.kt`（`USER_SERVICE_VERSION = 3`）
- `app/src/test/java/me/trinitrix/mirax/wfd/SinkConnectionWireTest.kt`

單元測試只鎖「要刪的 network id」和廣播 MAC 字串。它沒有打到 `WifiP2pManager`。真正的迴圈是手機上的 `dumpsys wifip2p` 加 `MiraxWfdBeacon` 日誌。

開發機上驗證過：

- 改之前：`numPersistentGroup=3`，群組為 LEVIANA、EVAN、DESKTOP-2JHJLHH。
- 改之後、Shizuku `:wfd` 啟動時：日誌有 `addExternalApprover ff:ff:ff:ff:ff:ff`、`approver attached ff:ff:ff:ff:ff:ff`、`deletePersistentGroup` 0/1/2 success，然後 `numPersistentGroup=0` 且 `mListenStarted true`。

Gradle 必須用 JDK 17。預設 `JAVA_HOME` 若是 JDK 11，Android Gradle Plugin 8.7.3 會拒絕。開發機可用：

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat :app:testDebugUnitTest --tests me.trinitrix.mirax.wfd.SinkConnectionWireTest
.\gradlew.bat :app:installDebug
```

Helper jar：

```powershell
pwsh -NoProfile -File .\helper\build.ps1
```

手機上的日常路徑是 Shizuku，不是 helper jar。只推 jar 不會更新 `:wfd`。

## 還沒解決的問題

開發機仍然可以 Win+K 連上。備用機仍然不行。清掉持久群組、掛上廣播 approver，沒有讓備用機完成連線。

使用者在約 2026-09-29 21:24 於備用機按了一次 Win+K。手機日誌（不是使用者看到的成功）：

- `21:24:03` `MiraxWfdBeacon`: `P2P group up phoneIsOwner=false owner=/192.168.137.1`，接著 `source address 192.168.137.1`。
- `21:24:04` `MiraxSink`: `RTSP connected to 192.168.137.1:7236`。
- Source 身分：`MSMiracastSource/10.00.26100.6584`，GUID `BEFFCA5B-3438-0003-82E9-5FC23834DD01`。
- M1/M2 OPTIONS 成功。
- M3 `GET_PARAMETER` 問了 `wfd_video_formats`、`wfd_audio_codecs`、`wfd_client_rtp_ports`、`wfd_display_edid`、`wfd_connector_type`、`wfd_uibc_capability`、`wfd2_*`、`wfd_content_protection`、`wfd_idr_request_capability`、`intel_*`、`microsoft_latency_management_capability`、`microsoft_format_change_capability`、`microsoft_diagnostics_capability`、`microsoft_cursor`、`microsoft_rtcp_capability`、`microsoft_video_formats`，後面還有被日誌截斷的 `m…`。
- 回覆的 `wfd_video_formats` 是 `00 01 03 40 00000000 00000000 00000000 00 0000 0000 11 0714 0880`。`0714`/`0880` 是十進位 1812×2176。三個標準位元圖都是 0。
- `21:24:06.800` `MiraxSink: RTSP closed by source`。M3 回覆後大約 0.4 秒，Windows 關掉 TCP。
- `dumpsys` 的 group event：`21:24:02` 起、channel `5785`、`GroupClient`、`sessionDurationMillis=14916`、`idleDurationMillis=14916`、`numConnectedClients=0`。`connectivityLevelFailureCode=NONE`，`connectionType=FRESH`，`wpsMethod=PBC`。

使用者看到的是沒連上。日誌只說明 P2P 與 RTSP 曾經建立，然後 source 在 M3 之後拆線。不要把這段寫成備用機已連上。

`MiraxSink` 單行日誌有長度上限（約 700 字元），M3 的請求與回覆都被截斷。下一次請把完整 body 留下來，再判斷是哪一個參數讓 source 斷線。

同一晚較早的備用機嘗試（21:11–21:12）也是 FRESH PBC，群組約 15–20 秒後拆掉，同樣是 `GroupClient`、idle 等於整段 session。21:12:19 的系統日誌進過 `UserAuthorizingNegotiationRequestState`；那次 approver 是否真的回了 ACCEPT，沒有留下完整的 `MiraxWfdBeacon` 行，不能當成已證明。

## 下一手假設

依現有日誌排序。還沒有對照開發機同一次的完整 M3。

1. 備用機的 source（`10.00.26100.6584`）拒絕「標準位元圖全 0、只帶直向 1812×2176」的 M3。預測：廣告集合裡加上至少一個 CEA 模式（例如 1920×1080@60），同時保留自訂尺寸，M3 之後 source 會繼續送 M4，而不是關掉 TCP。注意 `docs/research/custom-format.md`：同時廣告的標準模式可能在實作裡勝出，不一定選到 Fold 尺寸。`PrePlayGroupDropped` 禁止在斷線後偷偷把 720p/1080p 補進下一次廣告集合；若要加，必須是這次廣告集合本身的決定，不是斷線後的 latch。
2. 廣告出去的是 `1812×2176`（直向），研究文件裡的 Fold 橫向是 `2176×1812`，自訂格式應為 `0880 0714 003C`。現在的 max-hres/max-vres 與 custom 會變成 `0714 0880`。預測：改回橫向 `2176×1812` 後，這支 source 不再在 M3 之後斷線。EDID 的實體尺寸是寫死的橫向 146×122 mm，timing 卻跟著直向模式，較新的 source 可能因此拒掉。
3. `microsoft_video_formats` 回 `000000000000`，Samsung 那次觀察到的回覆是 `none`。預測：改成 `none`，或略過 source 沒要的語意，這支 source 就不會斷。要先看到完整 M3 回覆再改，避免和假設 1 同時動。
4. 廣播 approver 在 ACCEPT 之後被拆掉，下一個 WPS 步驟趕在重新註冊之前。預測：21:24 那次若日誌裡沒有 `P2P connection request ... accepted`，斷線點會在群組成立之前。21:24 已經有 group up 與 RTSP，所以這假設解釋不了這一次的 M3 斷線。它仍可能解釋更早、沒有 RTSP 的嘗試。

開發機為什麼還連得上，這份日誌沒有它的 M3。下一手要先抓開發機一次完整的 `MiraxSink` RTSP，再和備用機的 M3 逐欄比較。請使用者分別在兩台電腦按 Win+K，不要自己按。

## 怎麼抓下一次

手機接在哪台電腦，就在那台下：

```powershell
adb logcat -c
adb shell cmd wifi set-verbose-logging enabled
adb logcat -v threadtime MiraxWfdBeacon:V MiraxSink:V WifiP2pService:I *:S
```

然後請使用者在目標電腦按 Win+K，點 `Renathan's Z Fold5`。結束後：

```powershell
adb shell dumpsys wifip2p
```

成功的判準是使用者看到畫面，而且日誌出現 M4 `SET_PARAMETER` 與 `EnteredPlay` / `PLAY`，不是只看到 group up。`MiraxSink` 現在把 RTSP 行截斷；若要核對 M3，先把那次 body 完整記下來。

## 分支與建置

分支 `mirax-receiver`，追蹤 `origin/mirax-receiver`。不要把 `.scratch-*`、`MainDashboardPrototype.*`、`helper/classes/`、`__pycache__` 交進去。

備用機若要裝到手機，需要這支手機的 adb，以及 JDK 17 與 Android SDK 36。備用機先前沒有 `/data/local/tmp/mirax-helper.jar`；手機上的 beacon 來自已安裝的 APK。
