# Miracast 連線研究：首次配對至 RTSP

**更新日期：** 2026-10-02
**裝置：** Galaxy Z Fold 5，Android 16 / API 36
**來源：** Windows 11，Win+K，Mirax 為 Primary Sink

## 摘要

本次 session 記錄到一次 fresh reinstall 後，Windows 的 Win+K 裝置列經 OCR 確認並首次點選即連線成功：P2P group 形成、Mirax pairing state 從 `UNPAIRED` 轉成 `PAIRED`、socket 綁定 P2P 介面、RTSP 在第 1 次連線到 `192.168.137.1:7236`，之後進入 PLAY 並收到第一個 RTP packet。

這是一次成功觀察，尚不能證明可重複，也不能把成功歸因於某一項單獨修正。同一輪成功連線後，`Get-NetFirewallDynamicKeywordAddress` 回報 0 個項目；此結果本身不能證明 firewall 正在阻擋，也不能證明 `WifiDirectDisplay` 是否為實際生效的規則條件。

Mirax 的 `PAIRED` 是 app 在觀察到 group formed 後設定的狀態，不是 supplicant WPS completion event。Android 16 AOSP 對 `startWps()` 的說明是 callback 回報 WPS 是否成功啟動，而非 WPS protocol 是否完成。目標 Samsung 韌體上的正常事件序列仍須實機記錄確認。

本次連線細節取自 session handoff 摘要；報告未附該次完整 Android 與 Windows raw logs。

## 單次成功紀錄

1. 依測試程序 fresh reinstall Mirax。
2. OCR 確認 Cast 裝置清單中的 Fold 裝置列後，第一次點選即開始連線。
3. P2P group formed；app pairing state 由 `UNPAIRED` 轉成 `PAIRED`。
4. Binder 以 P2P 介面已配置的非 loopback IPv4 選擇介面；即使 Java 回報 `NetworkInterface.isUp == false`，socket bind 仍成功。session 摘要沒有保留介面名稱。
5. RTSP TCP 連到 `192.168.137.1:7236`，第 1 次嘗試成功。
6. RTSP 到達 PLAY，播放開始，並收到第一個 RTP packet。
7. 同一成功 session 後，`Get-NetFirewallDynamicKeywordAddress` 輸出 0 個項目。

上述第 1 至 7 項是 session observation，不是多次重複測試，也沒有足夠資料對單一修正作因果歸因。

## 目前程式路徑

- [`P2pNetworkBinder.kt`](../../app/src/main/java/me/trinitrix/mirax/wfd/P2pNetworkBinder.kt) 掃描名稱含 `p2p` 的介面，優先選擇同時有 IPv4 且 `isUp` 的介面；若 Java 尚未回報 link-up，仍保留有非 loopback IPv4 的 P2P 介面作為候選。`bind()` 明確回報成功或失敗。
- [`SinkConnectionController.kt`](../../app/src/main/java/me/trinitrix/mirax/wfd/SinkConnectionController.kt) 只在 pairing state 是 `PAIRING` 時等待 `PAIRED`；等待期間若 group 掉線、狀態回到 `UNPAIRED` 或逾時，就略過 RTSP dial。RTSP dial 每次 timeout 為 3 秒，整體重試視窗為 20 秒；到達 PLAY 後啟動 RTP listener，並記錄第一個封包。
- [`PrimarySinkBeacon.java`](../../helper/src/me/trinitrix/mirax/wfd/PrimarySinkBeacon.java) 在看到 group formed 時把 app pairing state 設為 `PAIRED`。`UNPAIRED` 的一般 admission 路徑不註冊 external approver，`PAIRED` 才為 persistent-group reinvocation 註冊 approver。`startWpsPbc()` 仍存在於 approver callback 的分支，但不在 group-formed handler 中；因此「移除 group formed 後的 app-side 第二次 WPS 呼叫」不等於「Android framework 不會執行 WPS」。
- 同一 beacon 會反射呼叫 `WifiP2pWfdInfo.setWpsConfigMethodsSupported()` 設定 PBC + Keypad；session handoff 記錄目標韌體未提供此方法。這是能力限制紀錄，尚未證明它是此次或先前連線失敗的原因。

## Android P2P / WPS 判讀

### API callback 的範圍

Android 16 AOSP 標籤 `android-16.0.0_r1` 中，`WpsInfo.PBC` 是 Push Button Configuration，常數值為 `0`（[Android API reference](https://developer.android.com/reference/android/net/wifi/WpsInfo#PBC)）。同一版本的 [`WifiP2pManager.startWps()`](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/framework/java/android/net/wifi/p2p/WifiP2pManager.java#L2687) 標為 hidden API，文件說明呼叫會立即返回，`ActionListener` 回報的是 WPS 是否成功啟動；它目前限於本機作為 Group Owner、開放新 client 加入的用途。`WifiP2pManager` 對 `ActionListener` 的總說明也把 callback 定義為 action initiation 的成功或失敗（[Android 16 AOSP API source](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/framework/java/android/net/wifi/p2p/WifiP2pManager.java#L90-L94)）。因此不能把 `onSuccess()` 當作 supplicant WPS completion。

### PBC 與 group formation 的先後

AOSP Android 16 有不同 P2P 路徑，不能簡化成「WPS 一定在 group formed 前完成」：

- Fresh `connect()` 若未能 reinvoke persistent group，framework 會進入 `ProvisionDiscoveryState`；收到 `P2P_PROV_DISC_PBC_RSP_EVENT` 後才啟動 group negotiation（[`WifiP2pServiceImpl.java`](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java#L3860)、[PBC response handling](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java#L4717)）。Provision Discovery 的 PBC response 是流程階段事件，不等同 app 收到 WPS completion。
- 若本機已是 Group Owner 並收到新 client 的 PBC join request，framework 會先走 `UserAuthorizingJoinState`；使用者接受後，AOSP 自己呼叫 native `startWpsPbc()`（[PBC join request](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java#L5713)、[接受後啟動 WPS](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/service/java/com/android/server/wifi/p2p/WifiP2pServiceImpl.java#L5928)）。這條路徑中，GO group 已存在時仍可能由 framework 啟動 WPS。
- Android 16 AOSP 的 `WifiP2pListener` 有 group-created、client-joined 等 group lifecycle callbacks，沒有專用的 WPS-completed callback（[listener interface](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/framework/java/android/net/wifi/p2p/WifiP2pManager.java#L1344)）。目標機的 Samsung framework/supplicant 仍可能有 OEM 差異，須以該機 log 驗證。

Mirax 目前將 group formed 映射成 app-level `PAIRED`，這能讓 RTSP 路徑繼續，不代表 app 已觀察到真正的 WPS completion。先前手動於 group 形成後呼叫 `startWps()` 的試驗只記到 `WPS-PBC-ACTIVE`、沒有 `WPS-SUCCESS`，該額外 app-side 呼叫已移除。這些試驗不能代替正常 provision-discovery 流程的目標機事件紀錄。

## Windows Firewall 判讀

本次記錄的零筆輸出來自 `Get-NetFirewallDynamicKeywordAddress`。Microsoft 文件將此 cmdlet 定義為查詢 dynamic keyword address objects，並可依 ID、policy store 或 AutoResolve 類型篩選（[Get-NetFirewallDynamicKeywordAddress](https://learn.microsoft.com/en-us/powershell/module/netsecurity/get-netfirewalldynamickeywordaddress?view=windowsserver2025-ps)）。`AutoResolve` 物件建立時可以尚未有 IP address，之後可用另一個 cmdlet 更新（[建立物件](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewalldynamickeywordaddress?view=windowsserver2025-ps)、[更新物件](https://learn.microsoft.com/en-us/powershell/module/netsecurity/update-netfirewalldynamickeywordaddress?view=windowsserver2025-ps)）。

Microsoft 將 `New-NetFirewallRule -DynamicTarget` 的 `WifiDirectDisplay` 定義為 dynamic transport rule condition；`-RemoteDynamicKeywordAddresses` 則接受 dynamic keyword address IDs，兩者是不同參數（[New-NetFirewallRule](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule?view=windowsserver2025-ps)）。查到 0 個 address objects 並不等於查到 0 個 `WifiDirectDisplay` 規則，也不直接表示 TCP 7236 被擋。文件亦未說明 `WifiDirectDisplay` 必須對應到一個可由該 cmdlet 列出的 keyword address object，或必須如此才能建立 Miracast 連線。

同一 session 的 RTSP 已成功連線，因此零筆 address-object 輸出不足以解釋這次成功路徑；但它也沒有指出是哪條 ActiveStore rule 放行連線，或 query 涵蓋哪些 policy stores。現有 [`pairing fix spec`](../spec/miracast-pairing-fix.md) 中「dynamic target 是 gatekeeper」及「WPS completion 會填入該 target」的敘述，本次資料尚未驗證，應視為待驗證假設，而非已證實根因。

## 結論與待查事項

| 問題 | 目前判斷 |
|------|----------|
| 首次連線是否可到 RTSP / PLAY / RTP？ | 是，session 摘要記錄一次成功。 |
| 修正 `isUp` 篩選是否有幫助？ | 成功結果與修正相符，但單次測試不能單獨證明因果。 |
| 移除 group formed 後的 app-side WPS 是否修復問題？ | 成功結果與修正相符；沒有隔離實驗，不能單獨歸因。Framework 仍可能在正常 join 路徑自行呼叫 WPS。 |
| `PAIRED` 是否證明 supplicant WPS 完成？ | 否；這是 Mirax 在 group formed 時設定的 app state。 |
| 0 個 dynamic keyword address 是否證明 firewall 擋住 RTSP？ | 否；同一成功 session 已連到 RTSP，且該 cmdlet 查的是 address objects，不是流量允許/拒絕結果。 |
| 唯一 persistent group 是否屬於這台 Windows？ | 尚未確認；總數 1 不足以識別 peer。 |
| 首次點選是否已可靠？ | 尚未；目前只有一次成功。 |

### 下一輪取證

1. 每次測試前 fresh reinstall，重複首次點選測試並逐次保存 app、Android framework、supplicant 與 Windows 的時間線；app log 可先用 `adb logcat -s MiraxWfdBeacon MiraxSink MiraxP2pBinder`。
2. 將 persistent-group 的 network ID / peer identity 與 Windows peer 對上，不以 `numPersistentGroup=1` 作為歸屬證據。
3. 在目標 Fold 上分別記錄 provision-discovery PBC request/response、WPS 相關 supplicant event、group created、client joined、RTSP SYN/SYN-ACK 與 PLAY；確認正常流程中可觀察到的真正 WPS completion 證據。
4. 在 Windows 記錄命令的 policy store/filter、ActiveStore 中相關 rule 的 `DynamicTarget` 與 keyword-address IDs，並和成功/失敗封包時間線對照；不要單看零筆 address-object 輸出下結論。

## 重測安全

handoff 要求只有 OCR 確認 Cast 清單中的精確 Fold 裝置列時才點選。檢查目前 [`click-fold.ps1`](../../scripts/click-fold.ps1) 發現：腳本會先遍歷 Cast window 的 UI Automation 名稱，只要名稱符合 `Fold|Renathan` 就設定點擊目標，並在 OCR 執行前離開該分支；OCR 是 UIA 未命中時才走的後備路徑。因此目前腳本**沒有強制 OCR gate**，不符合 OCR-only 重測條件。修正此閘門前，不要用該腳本進行重測；不得改用預設座標、後備座標或點選其他裝置。
