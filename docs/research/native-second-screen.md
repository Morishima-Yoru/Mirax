# Samsung Second Screen 與這次 Fold 實驗

Samsung 公開文件把 Second Screen 列為 Galaxy Tab 功能。不論舊的 Win+K 流程或較新的 PC companion app，列出的仍是 Tab，沒有 Z Fold 5。手機上有 SmartMirroring 與 `SecondScreenPlayer`，不等於 Fold 5 有官方支援流程。當時能連上，是 Samsung 播放器加上自行啟動的 WFD 入口，不是完整原廠流程。

來源：[Samsung US 相容型號](https://www.samsung.com/us/support/answer/ANS10002024/)、[Tab S11 companion 流程](https://www.samsung.com/de/support/mobile-devices/galaxy-tab-s11-ultra-als-second-screen-verwenden/)。

## 這台韌體上的入口

SmartMirroring `8.2.27.28` 的 manifest 把 `ScreenSharingTile` 宣告為 `enabled=false`。Package Manager 另把 `SecondScreenActivity` 列在 `disabledComponents`。當時的 tile / activity 入口不能直接用。這不能單獨證明停用原因是型號還是設定，但和公開的 Tab-only 範圍一致。`SecondScreenPlayer` 仍在，並能由 daemon 啟動。

## 平板文件裡的流程

支援的平板從 Quick Settings 的 Second screen 進入，留在該頁，Windows 用 Win+K 連接，再用 Win+P 或顯示設定選 Duplicate / Extend。Samsung 說明連線期間不要離開該頁。Extend 時文件舉的解析度例子是 `1920×1200`，那是 Tab，不能推定 Fold 5 會宣告 6:5。

較新的 Tab S11 把入口放在設定裡的 Connected devices，Windows 端用 Samsung 的 Second Screen app，要求同一 Wi-Fi 與 Samsung Account。相容範圍仍寫 Tab。

Smart View 是手機把畫面送到電視。Wireless DeX 是手機把桌面送到 Miracast 螢幕。兩者方向都和「PC 把桌面送到手機」相反。

## 對全螢幕的含義

當時最新接收紀錄是 Windows 送 `2560×1440`，播放器在橫向畫布上保留比例，約 `2176×1224`。畫布若是 `2176×1812`，完整保留 16:9 就會有留白。要同時不裁切、不變形、無留白，來源必須送出約 6:5。這是比例，不是 Samsung 對 Fold 5 的保證。

`wm size` 改的是手機邏輯畫布，不是 Windows 送出的解析度。不要用它推測來源模式，也不要把 Tab 的 `1920×1200` 套到 Fold。
