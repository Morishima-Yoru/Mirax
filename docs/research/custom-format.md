# 自訂 2176×1812 的協商

目標是讓 Windows 10/11 的 Miracast source 連上 Primary Sink，並有機會選到 Galaxy Z Fold 5 內螢幕橫向的 `2176×1812`。

規範出處是 Wi-Fi Display Technical Specification v2.1，以及 Microsoft 公開的 [MS-WFDPE](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/9ac9b0a4-0a94-483d-a8bf-cd7bfea99be0)。下面只記這個專案要用的欄位關係與算出的值，不重抄規格文法。

## 協商順序

| 訊息 | 方向 | 作用 |
|------|------|------|
| M1 | Source → Sink | `OPTIONS`，確認 Sink 的 WFD 方法 |
| M2 | Sink → Source | `OPTIONS`，確認 Source 的方法 |
| M3 | Source → Sink | `GET_PARAMETER`，問能力名稱；Sink 回值 |
| M4 | Source → Sink | `SET_PARAMETER`，選定格式、presentation URL、RTP port |
| M5 | Source → Sink | 用 `wfd_trigger_method: SETUP` 讓 Sink 發 SETUP |
| M6 | Sink → Source | `SETUP` |
| M7 | Sink → Source | `PLAY` |

Source 與 Sink 各自維護 `CSeq`。帶 body 的 GET/SET_PARAMETER 使用 `Content-Type: text/parameters`。拆除時 Source 送 `wfd_trigger_method: TEARDOWN`，Sink 再對 presentation URL 發 `TEARDOWN`。

`WifiP2pWfdInfo` 沒有寬高。解析度只出現在這段 RTSP。

## 哪一種參數寫得出 Fold 尺寸

| 參數 | 能否表示 2176×1812 |
|------|-------------------|
| `wfd_video_formats` 的 CEA / VESA / HH 位元 | 不能。模式表沒有這個尺寸 |
| `wfdx_video_formats` | 仍是位元圖加上最大寬高上限，不是任意模式清單 |
| `microsoft_video_formats` | 只勾 Microsoft 已命名的額外解析度 |
| `microsoft_custom_video_formats` | 可以。字面寬、高、更新率 |

[MS-WFDPE §2.7.1.3](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/e886ba33-f731-4f01-ba5f-6e9204804865) 把每個自訂模式定義成三個十六進位欄位：寬、高、更新率（Hz），逐行掃描、沒有交錯旗標。多個模式用逗號加一個空白分開。`none` 表示沒有自訂格式。這個參數裡沒有 profile、level 或位元率；那些約束來自 `wfd_video_formats` 或 `wfdx_video_formats`。自訂尺寸套用到放得下它的 codec 組合。

Microsoft 公開範例 `0A00 0438 001E` 是 2560×1080@30，`0870 0740 001E` 是 2160×1856@30。同一套算法得到 Fold 橫向：

| 欄位 | 十進位 | 四位十六進位 |
|------|--------|----------------|
| 寬 | 2176 | `0880` |
| 高 | 1812 | `0714` |
| 60 Hz | 60 | `003C` |
| 30 Hz | 30 | `001E` |

60 Hz：

```text
microsoft_custom_video_formats: 0880 0714 003C
```

60 與 30 一起廣告：

```text
microsoft_custom_video_formats: 0880 0714 003C, 0880 0714 001E
```

設計上先只把 60 Hz 當目標。30 Hz 要先被接受，才一併廣告。

## Windows 什麼時候選得到

[MS-WFDPE 產品行為](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/6b66a34f-8b8b-461d-8261-6f285248c3b7) 寫明 `microsoft_custom_video_formats` 支援 Windows 11 24H2 與 25H2（帶 MSKB-5120998）以及 26H1（帶 MSKB-5120996）及之後。Windows 10 不在該表。Source 沒有在 M3 查這個名字時，Sink 廣告了也不會被選到。

要讓非標準尺寸有機會被選上：

1. Source 的 M3 查詢包含 `microsoft_custom_video_formats`。
2. Sink 回合法的 `0880 0714 003C`，並且 `wfd_video_formats` 或 `wfdx_video_formats` 裡的 H.264 profile/level 蓋得住該像素率。
3. 同時廣告的 CEA/VESA 模式（例如 2560×1440@60）可能在實作裡勝出。多個模式誰贏，規格把它留給實作。
4. Windows 的 M4 寫下所選格式，Sink 接受它。

EDID 可以帶 detailed timing，但規格沒有要求 Source 優先採用 EDID。只放 EDID、不放影片格式參數，不足以強迫 `2176×1812`。

H.264 巨集塊是 16×16。`1812 % 16 = 4`。Microsoft 自己的範例高度 `1080` 也不是 16 的倍數，所以廣告 `0880 0714` 在這個參數裡合法。編碼圖片仍要 crop 或 pad；那是 H.264，不是 RTSP 欄位的額外限制。2176 可以被 16 整除。

gnome-network-displays 是 Source，只解 CEA/VESA/HH，原始碼裡沒有 `microsoft_custom_video_formats`。miraclecast 可以用設定把額外字串透傳進 M3 回覆，編碼仍以 MS-WFDPE 為準。

## 接收串流時要處理的形狀

影像走 MPEG-2 TS，再放進 RTP/UDP，payload type 33。每個 TS 包 188 bytes，一個 RTP 包帶整數個 TS 包。RTP 時戳 90 kHz，對齊 PCR。多工的影音 TS 帶 PCR；PES 裡的 PTS 決定呈現。H.264 的 PMT `stream_type` 是 `0x1B`。

接收端要做的事：綁到 SETUP 談定的 UDP port，剝 RTP，按 188 拆 TS，由 PAT/PMT 找到 H.264 PID，組 PES，把 NAL 送進解碼器。音訊以 `wfd_audio_codecs` 談定的為準；目前草稿先考慮 LPCM。

## Android 發現階段

Primary Sink 的 beacon：啟用 WFD、`DEVICE_TYPE_PRIMARY_SINK`、session available、控制埠 `7236`、設定 max throughput。`setWfdInfo()` 需要 `CONFIGURE_WIFI_DISPLAY`。`startListening()` 讓 Wi-Fi Direct 進入 listen。權限邊界見 [daemon-privilege.md](daemon-privilege.md)。

## 還沒有的證據

這台 Windows 11 25H2 是否已裝對應 KB、M3 是否會查詢自訂格式、M4 是否選 `2176×1812`，都要看新 sink 自己的協商日誌。Samsung 那次連線沒有查這個參數，不能拿來回答這題。
