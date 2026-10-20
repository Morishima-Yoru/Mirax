# 研究紀錄

這些是轉進 Mirax 時仍有用的結論。裝置 dump、Samsung native library、keystore，以及會啟動 Samsung 播放器的舊 daemon，留在本機實驗目錄。目前的 Android 接收端程式在 `app/`，shell helper 在 `helper/`。

更早的直向 `1201×2176` 診斷、以及對 Samsung library 做 hook 的步驟，已被後來的橫向量測和 [ADR 0001](../adr/0001-standalone-sink.md) 取代，所以沒有收進來。

| 文件 | 保留的判斷 |
|------|------------|
| [miracast-connectivity.md](miracast-connectivity.md) | 一次首次連線到 RTSP/PLAY/RTP；WPS 完成、firewall 因果與重複性仍待驗證 |
| [custom-format.md](custom-format.md) | `2176×1812` 怎麼寫進 Microsoft 自訂格式，Windows 要先查詢才選得到 |
| [samsung-receiver.md](samsung-receiver.md) | 舊組合裡誰負責 beacon、誰負責 RTSP；那次 M4 是 `2560×1440@60` |
| [native-second-screen.md](native-second-screen.md) | Samsung 公開支援是 Tab；這次 Fold 實驗不是原廠流程 |
| [daemon-privilege.md](daemon-privilege.md) | `setWfdInfo()` 綁在 shell 身分，一般 APK 過不了 |
| [app-entrypoints.md](app-entrypoints.md) | 普通 App 沒有已證實的免 ADB Sink 入口；這台韌體拒絕 `pm enable` tile |
| [privileged-entrypoints.md](privileged-entrypoints.md) | Knox 只能嘗試打開 Samsung 自己的元件，而且尚未實測、會改裝置管理前提 |
| [device-owner-wifi-display.md](device-owner-wifi-display.md) | Device Owner 不能取得或轉授 `CONFIGURE_WIFI_DISPLAY` |
| [touch.md](touch.md) | 一般觸控可以走 UIBC HIDC；S Pen 不在範圍內 |
| [large-screen-split.md](large-screen-split.md) | 平行視界的官方對應是 activity embedding，但 Google 限給多 Activity 舊 App；單一 Activity 的 Views 推論以 `SlidingPaneLayout` 最小 |
| [win11-source-burst-pacing.md](win11-source-burst-pacing.md) | Win11 來源約每秒 30 AU 突發後靜默 ~550 ms；與 High latency ~500 ms 對齊；sink pacing 只能換成穩定延遲 |

現行設計以 [design.md](../design.md) 為準。研究裡若出現「下一步去改 Samsung 播放器」的句子，以 ADR 為準。
