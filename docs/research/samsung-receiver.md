# Samsung 接收端實際做了什麼

範圍：Fold 5 上 SmartMirroring `8.2.27.28`、當時的 shell daemon、分離出來的 WFD native library，以及一次仍在連線中的 `WifiDisplaySink` logcat。APK、library 與 daemon 原始碼留在本機實驗目錄。

## 結論

那時候沒有自製完整的 Miracast Receiver。daemon 建立 Wi-Fi Direct/WFD 可發現入口、偵測群組，再叫起 Samsung 的 `SecondScreenPlayer`。播放器裡的混淆類別曾被標成 session manager；分離的 `libremotedisplay_wfd.so` 含有 sink、格式協商、RTP/H.264 與顯示相關符號。現存材料沒有證明播放器如何連到那個 `.so`。

```text
shell daemon
  ├─ WifiP2pManager：宣告 WFD Sink、進入 P2P listen
  ├─ 等待 Wi-Fi Direct group
  └─ am start → SecondScreenPlayer（wfd://IP:7236）
                    ├─ APK 筆記裡的 session manager
                    ├─ native sink / format / RTP / renderer（連線方式未閉合）
                    └─ VideoSurfaceView
```

daemon 設定的 `WifiP2pWfdInfo` 是：WFD enabled、`PRIMARY_SINK`、session available、RTSP 控制埠 `7236`、max throughput `50`，並曾用反射嘗試打開 content protection。`setWfdInfo` 成功後呼叫 `startListening`。這個 API 是進入 listen 並回應 probe，不是接收或解碼影片。群組成立後啟動 `com.samsung.android.smartmirroring/.player.SecondScreenPlayer`，extras 含 `uri=wfd://<group-owner-ip>:7236`、`ScreenSharing=true`、`pre_wifi_enable_status=true`、`isSamsungDevice=true`。

`WifiP2pWfdInfo` 沒有寬、高或影片模式。解析度屬於後續 RTSP。

## APK 與 native 能支持什麼

`SecondScreenPlayer` 是 exported Activity。`SecondScreenActivity` 是 non-exported。daemon 啟動的是 Player。元件存在只能說明入口在，不能證明 Fold 5 有官方 Second Screen 流程。

APK 裡看得到 `classes.dex` 與 `libDiagMonKey.so`，沒有 `libremotedisplay_wfd.so`。DEX 字串掃描也沒找到該 library 名稱。library 是另外從裝置取出的。合理的工作模型是播放器把接收交給 Samsung / Android 的 native WFD 堆疊，但哪個程序載入它尚未閉合。

靜態符號能支持的判斷，和不能證明的事：

| 線索 | 能支持 | 不能證明 |
|------|--------|----------|
| `WifiDisplaySink` 的 format / UIBC 相關名稱 | binary 含 sink、格式與 UIBC 處理 | 該次 session 實際回了哪一則 RTSP |
| 格式 parse / select 名稱 | binary 含模式選擇 | 可接受的模式集合是否含任意尺寸 |
| RTP H.264、MediaCodec、renderer 名稱 | 有接收與解碼相關實作 | 該次 decoder 設定與 Java→native 呼叫序列 |
| UIBC parse / pen 判斷名稱 | 有 UIBC 與 pen 路徑 | 該次 Fold session 最終協商到的每種輸入 |

可列印字串含 `wfd_video_formats`。簡單掃描沒看到 `wfdx_video_formats` 或 `microsoft_custom_video_formats`。字串不在，不等於功能一定不支援。

## 那次連線的 M3/M4

對正在運作的手機做了唯讀 `adb logcat -d -s WifiDisplaySink`，沒有停 daemon、播放器或 session。

- Windows 的 `GET_PARAMETER` 包含 `wfd_video_formats`、`wfd2_video_formats`、`microsoft_video_formats`，沒有 `microsoft_custom_video_formats`。這不能證明 Samsung 拒絕該參數，只能確定那次 Windows 沒問。
- Samsung 回的 `microsoft_video_formats` 是 `none`。
- 回覆含 `wfd_display_edid`，以 `0002` 開頭、後接 512 個十六進位字元。本地解碼看到 1920×1200（約 60 Hz）與 1920×1080@60 的 detailed timing，沒有 `2176×1812`。EDID 缺席不能排除其他模式欄位另行宣告該尺寸。
- Samsung 把 Windows 的 `SET_PARAMETER` 標成 `Received M4 request`，其中有 `wfd2_video_formats`。隨後記錄 `mVideoWidth=2560`、`mVideoHeight=1440`、`mVideoFPS=60`。
- 啟動 log 另有 `displaySize:2176x1773`、`IsMultiFold:0`、`isSupportWide:1`。這些是 Samsung 內部欄位，不是實際橫向畫布，也不是 WFD 模式清單。

因此那次實際選的是 `2560×1440@60`。自訂 6:5 是否可用，這份紀錄回答不了。

## 黑邊是比例，加上排版

後來的畫面量測（沿用當時的連線，不是另一次重抓）：播放器已橫向，邏輯畫布約 `2176×1812`；串流 `2560×1440`；surface 約 `2176×1224`，從 `y=98` 開始。寬度縮放 `2176/2560 = 0.85`，高度 `1440×0.85 = 1224`。16:9 填不滿約 6:5 是比例造成的；上下不對稱還說明 top margin 一類的排版也在起作用。

更早的 SurfaceFlinger dump 把 view 記成直向 `1201×2176`。那是較早的捕獲。後續量測確認播放器已 landscape、surface 寬 `2176`。直向診斷不要再當現況。

完整保留 16:9、不變形、又沒有留白，三者無法同時蓋住 6:5 畫布。即使協商出 6:5，surface 仍要鋪滿且對齊。

## 輸入是另一條路徑

那次 Windows 的 UIBC offer 含 Keyboard、SingleTouch、MultiTouch、Gesture，不含 pen。S Pen 回不來，不是解析度問題。一般觸控的後續判斷見 [touch.md](touch.md)。

## 當時留下的未確認項

| 判斷 | 狀態 |
|------|------|
| daemon 只做 P2P/WFD metadata、listen、等 group、叫起 Player | 由當時的 Java 原始碼確認 |
| `WifiP2pWfdInfo` 不是影片解析度清單 | API 與協定階段 |
| Player 是 daemon 直接啟動的 Activity | manifest 與原始碼 |
| native binary 含 sink / format / RTP / decoder 符號 | 靜態名稱；runtime 路徑未確認 |
| 混淆類別管理 WFD session | 既有 DEX 筆記，不是完整重跑 |
| 該次 M4 為 2560×1440@60 | `WifiDisplaySink` log |
| 這台 Win11 25H2 是否已裝 KB-5120998 並會選 2176×1812 | 未確認 |
