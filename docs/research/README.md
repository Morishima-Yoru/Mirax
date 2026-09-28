# 研究紀錄

這些是轉進 Mirax 時仍有用的結論。裝置 dump、Samsung native library、keystore，以及會啟動 Samsung 播放器的舊 daemon，留在本機實驗目錄。目前的接收端程式在 repo 的 `receiver/`。

更早的直向 `1201×2176` 診斷、以及對 Samsung library 做 hook 的步驟，已被後來的橫向量測和 [ADR 0001](../adr/0001-standalone-sink.md) 取代，所以沒有收進來。

| 文件 | 保留的判斷 |
|------|------------|
| [custom-format.md](custom-format.md) | `2176×1812` 怎麼寫進 Microsoft 自訂格式，Windows 要先查詢才選得到 |
| [samsung-receiver.md](samsung-receiver.md) | 舊組合裡誰負責 beacon、誰負責 RTSP；那次 M4 是 `2560×1440@60` |
| [native-second-screen.md](native-second-screen.md) | Samsung 公開支援是 Tab；這次 Fold 實驗不是原廠流程 |
| [daemon-privilege.md](daemon-privilege.md) | `setWfdInfo()` 綁在 shell 身分，一般 APK 過不了 |
| [app-entrypoints.md](app-entrypoints.md) | 普通 App 沒有已證實的免 ADB Sink 入口；這台韌體拒絕 `pm enable` tile |
| [privileged-entrypoints.md](privileged-entrypoints.md) | Knox 只能嘗試打開 Samsung 自己的元件，而且尚未實測、會改裝置管理前提 |
| [touch.md](touch.md) | 一般觸控可以走 UIBC HIDC；S Pen 不在範圍內 |

現行設計以 [design.md](../design.md) 為準。研究裡若出現「下一步去改 Samsung 播放器」的句子，以 ADR 為準。
