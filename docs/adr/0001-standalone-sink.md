# ADR 0001: 手機自製 Miracast Sink

狀態：接受。

## 背景

Fold 5 要當 Windows 的第二螢幕，目標是內螢幕橫向 `2176×1812`。Windows 端保持原廠 Win+K。

Samsung 公開的 Second Screen 支援範圍是 Galaxy Tab，沒有 Z Fold 5。這台手機上的 SmartMirroring 可以播串流，但 Quick Settings 入口在目前韌體上是停用的，shell 也無法把它打開。舊實驗用 shell daemon 廣播 sink，再把 Samsung 播放器叫起來；那次協商結果是 `2560×1440@60`。16:9 放進約 6:5 的畫布，留白是比例造成的。

`setWfdInfo()` 需要簽章級的 `CONFIGURE_WIFI_DISPLAY`。一般 APK 拿不到。shell UID 可以。

## 決定

接收端由這個專案自己實作：WFD/P2P 發現、RTSP 協商、RTP/H.264、`MediaCodec` 顯示、拆除與重連，以及一般觸控的 UIBC HIDC 路徑。

不把 Samsung SmartMirroring、`SecondScreenPlayer` 或 `libremotedisplay_wfd.so` 放進新的接收鏈。

Windows 不安裝驅動、背景服務、應用程式或轉接器。

啟動接受 ADB shell。不把 root、Knox 或 Samsung 特權 broker 當可用前提。

S Pen 不在範圍內。

## 後果

- 解析度必須在 sink 自己的 RTSP 能力裡廣告，並由 Windows 在 M4 選定。手機排版改不了來源比例。
- `2176×1812` 要靠 `microsoft_custom_video_formats` 這類 literal 模式。這台 host 是否查詢並選用它，仍待新 sink 的一次協商日誌。
- 發現階段繼續依賴 shell 身分，直到另有已驗證的啟動方式。
- Samsung 路徑的量測只留在研究紀錄裡，用來說明為什麼 16:9 填不滿這塊畫面。
