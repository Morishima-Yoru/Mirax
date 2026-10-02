# 自製 Sink

手機作為 Windows 的第二螢幕。Windows 保持原廠：Win+K、原生 Miracast、延伸桌面。手機自己完成發現、協商、解碼與顯示。

決定見 [ADR 0001](adr/0001-standalone-sink.md)。詞彙以 [CONTEXT.md](CONTEXT.md) 為準。

## 已定

- 目標畫面是內螢幕橫向，名義尺寸 `2176×1812`，約 6:5。完整保留、不裁切、不拉伸、同時鋪滿，要求 Windows 實際送出同比例的畫面。只改手機排版無法讓 Windows 改送 6:5。
- 更新率先以 60 Hz 為目標。30 Hz 要先確認可接受，才算支援行為。
- 啟動可以走 ADB shell，包含重開機後較麻煩的一次啟動。root、Knox 企業授權、Samsung 特權 broker 都不當前提。
- `WifiP2pManager.setWfdInfo()` 需要 `CONFIGURE_WIFI_DISPLAY`。這是 signature / known-signer 權限。目前驗證過的身分是 shell UID 2000。一般安裝的 APK 過不了這道檢查。
- P2P beacon 只宣告裝置是 Primary Sink、session、控制埠與吞吐。寬高不在 `WifiP2pWfdInfo` 裡。解析度在後續 RTSP 協商。
- 標準 CEA / VESA / HH 模式表沒有 `2176×1812`。能寫出這個尺寸的是 Microsoft 的 `microsoft_custom_video_formats`：寬、高、更新率三個十六進位欄位。Fold 橫向 60 Hz 寫成 `0880 0714 003C`。Windows 11 24H2 / 25H2 對這個參數的支援帶有 KB 條件；這台 25H2 是否已裝對應更新尚未確認。Windows 10 不在該支援表上。
- `1812` 不是 16 的倍數。廣告這個高度在 Microsoft 參數裡仍然合法；編碼端用 frame cropping 處理，和 1080 的作法同一類。
- S Pen 與一般觸控是兩種輸入。S Pen 不在範圍內。一般觸控走 UIBC 的 HIDC / USB，見 [touch.md](research/touch.md)。
- 可觸控還要求使用者在 Win+K 允許來自該裝置的輸入。未允許時畫面可以在，狀態是只顯示。

## 手機要自己做的鏈

1. Wi-Fi Direct / WFD sink 發現與 group 生命週期。
2. RTSP 能力與 session：廣告可用的 6:5 模式。
3. RTP / H.264 接收與拆包，經 Android `MediaCodec` 畫到自己的全螢幕 surface。
4. 拆除與重連。
5. 一般觸控：M3 廣告 HIDC，連上 source 的 UIBC TCP，先送 touch report descriptor，再送接觸報告。座標用畫面矩形換算到協商後的影片像素。

實作前先用協定來源與一次 host/裝置測試確認：Windows 會列出並真的選 `2176×1812`，或一個明確選定、同比例的後備尺寸。

## 已觀察的 Samsung 路徑

這些只說明舊組合做了什麼。新的接收端不呼叫 SmartMirroring、`SecondScreenPlayer`，也不載入 `libremotedisplay_wfd.so`。

- 舊 daemon 只設定 sink beacon、等待 P2P group，再 `am start` Samsung 的播放器。RTSP/RTP 不在那份 Java 裡。
- 一次實際連線裡，Windows 的 M3 查了 `wfd_video_formats`、`wfd2_video_formats`、`microsoft_video_formats`，沒有查 `microsoft_custom_video_formats`。Samsung 的 `microsoft_video_formats` 回 `none`。M4 選定 `2560×1440@60`。
- 該次 EDID 解出 1920×1200 與 1920×1080@60 的 detailed timing，沒有 `2176×1812`。EDID 缺這一筆，不能單獨證明其他模式欄位也沒宣告它。
- 後來的畫面量測：播放器已是橫向，邏輯畫布約 `2176×1812`，串流仍是 `2560×1440`，surface 約 `2176×1224`。16:9 放進約 6:5 的畫布，留白是比例造成的。更早的「直向只有 1201px 寬」已被這次量測取代。
- Samsung 公開的 Second Screen 支援寫的是 Galaxy Tab，清單沒有 Z Fold 5。這台韌體上 Quick Settings tile 預設停用，shell 的 `pm enable` 被 `SecurityException` 拒絕。
- 普通 App、Knox 元件開關、Shizuku，都沒有成為已驗證的免 ADB 啟動方式。Knox 會改變裝置管理前提，未獲明確同意前不做。

細節在 [docs/research](research/README.md)。

## 尚未定案

- 這台 Windows 11 25H2 是否查詢並選用 `microsoft_custom_video_formats`。要用新 sink 自己的 M3/M4 日誌確認，不能沿用 Samsung 那次沒被查詢的紀錄。
- 協商到的寬高，以及畫出的 surface 是否鋪滿內螢幕且比例正確。
- Win+K 能否發現手機、延伸桌面、穩定解碼、斷線重連。全程不在 Windows 安裝額外軟體。
- 接收端執行時，Samsung SmartMirroring 的套件、行程與 native library 不參與。ADB 啟動步驟要可重做。
- 能力與 EDID 編碼、RTSP 狀態、RTP/H.264 重組，先有可在 host 上跑的測試，再做裝置整合。
- 一般觸控：這台 Windows 的 M3 是否仍列出 `wfd_uibc_capability`，以及它勾的是 SingleTouch 還是 MultiTouch。觸摸沒有預設 HID descriptor，要先送再送 report。
- 重開機後 shell 程序如何再啟動。脫離 ADB client 之後是否仍存活，尚未在紀錄裡實測。

## 驗收

- Win+K 連上後，Windows 把桌面延伸到手機。
- 日誌裡的 M4 尺寸是 `2176×1812`，或事先寫明的同比例後備。
- 畫面鋪滿內螢幕橫向，沒有裁切或拉伸。
- 斷線後可再連。
- 使用者允許輸入且通道開着時，畫面上的新按下成為 Windows 的一般觸控；進入只顯示時，尚未結束的接觸全部結束。
- S Pen 不進入這條輸入。
