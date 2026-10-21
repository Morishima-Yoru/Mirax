# 本輪 APK 的 Windows 原生指筆重驗（2026-10-21）

本次使用工作樹建置的 `app-debug.apk` SHA-256 `21954dba061a5bf40d3d019977498c60c44d532ec6e3c978ba668b898b12bd1a`，AndroidTest APK 為 `a6cfab89119cc25a5ca249512b8bdb5da92c16ce5cecb8ec4cf4fcec7b43b9fb`；兩次執行前均核對雜湊。測試裝置為 `HA1CSQTM`（TrebleDroid vanilla），Windows 以 Win+K 連線，Cast 允許裝置輸入。兩次 Android 日誌皆有 `pairing state UNPAIRED -> PAIRED` 及 P2P group up。這不是封存 run 86／87 使用的 `1354c723…` APK，也不是修復前的 `fa8290d2…` APK；未套用留在 stash 的 WPS 改動。

| 測試 | Android／連線證據 | Windows 原生 live pointer 證據 |
| --- | --- | --- |
| `injectFingerAndStylus_throughPictureActivitySurface` | 配對後 P2P group up、type-2 UIBC 連上 `192.168.137.1:50000`、1452-byte 十指描述元、M15 accepted；`PictureActivity` 前景注入通過 `OK (1 test)` | Probe 前景與 9/9 命中確認；11 對 TOUCH＋PEN 同時有效接觸，各 11 次 down／up；例如 TOUCH ID 670／PEN ID 671，旗標 `0x00016017`／`0x00014017` 均有 `INCONTACT` 且無取消／UP／capture-changed；結束無殘留接觸。 |
| `probeAllFingerSlotsOnExistingChannel` | 重新配對後 P2P group up、相同 type-2 UIBC 通道與十指描述元；`ALL_SLOTS_BEGIN count=10` 至 `ALL_SLOTS_END`，`OK (1 test)` | Probe 前景與 9/9 命中確認；Windows `confirmedMaxConcurrent=10`，首組十指 ID 692–701 的 45 對原生快照均有有效接觸旗標；共 80 個 TOUCH down／80 個對應 up，零殘留。 |

原始檔與 SHA-256 清單在 `C:/Users/User/AppData/Local/Temp/opencode/mirax/real-ui-pen-20261021-074355/`、`C:/Users/User/AppData/Local/Temp/opencode/mirax/real-ten-20261021-080033/` 的 `evidence.sha256`；各有 `touch-events-*.log`、`connection-gesture.log`、`instrumentation.log`、Cast 截圖與 `independent-*-live-audit.json`。Windows probe 原始碼為 [`MultiTouchProbe.cs`](../../scripts/pointer-probe/MultiTouchProbe.cs)，不在 Android APK 內。第一次嘗試因 Cast 視窗未暴露預期 UI Automation 裝置列而未選取裝置，Android 測試等待連線逾時；紀錄保留在第一個目錄的 `attempt-1/`。其後核對 Cast 畫面僅有預期裝置列，以該次畫面的座標選取並完成兩次連線；首次嘗試**不是**成功的指筆驗收，也無法推斷為 WPS 故障。

兩次實測證明這個 APK 在這組裝置／Windows 設定下能配對並把所述指筆送達 Windows；Android 寫入日誌本身不算接觸證據。十指案例直接使用**既有通道**送出，沒有驗證十指都經 Android 畫面／控制項路徑；兩次均由 instrumentation 注入，沒有獨立擷取 WPS 握手，也沒有重驗實體 S Pen、移動疊層遮住接觸、四種縮放、人手手勢或長期重連可靠性。原先的負面試驗與 Cast 視窗遮擋發現仍按各自時間保留，不倒推其成因。
