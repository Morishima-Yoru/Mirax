# 一般觸控

結論：可以。未安裝驅動的 Windows 10/11 Miracast source 會把 Sink 送來的接觸收成自己的指標輸入。前提是使用者在 Win+K 允許來自該裝置的輸入，而且 Sink 用 HIDC / USB 送按下、移動、放開。規格裡的 Generic 座標是另一條封包。Microsoft 寫明 Generic 只涵蓋 ASCII 按鍵；Windows 要的是 HID Commands。

詞彙以 [CONTEXT.md](../CONTEXT.md) 為準。S Pen 與一般觸控是兩種輸入；Mirax 透過複合 HID 報告描述元在同一條 UIBC 通道中支援兩者。

## 依據

| 等級 | 內容 |
|------|------|
| 規格 | Wi-Fi Display Technical Specification v2.1 的 UIBC 章節。此處只記協商順序與欄位角色 |
| Microsoft | Windows 無線投影說明、給接收器廠商的 UIBC 說明、Continuum 對 HID Commands 的要求、Windows Pen HID 指引 |
| 舊連線筆記 | 一次 Samsung session 的 Windows M4，`hidc_cap_list` 含 Keyboard、SingleTouch、MultiTouch、Gesture，不含 pen |
| 公開旁證 | [miraclecast #139](https://github.com/albfan/miraclecast/issues/139)（2016，Windows 10 對 ScreenBeam）。鍵盤 HIDC 有作用；同外殼的滑鼠報告沒有變成游標；沒有已驗證的觸摸 report 樣本 |

[Understanding Windows 10 Wireless Projection](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-understanding) 寫明 inbox Miracast 支援 UIBC，而且只有使用者明確允許時才注入。Win+K 連上後的核取方塊是允許來自該裝置的 mouse、keyboard、touch 與 pen。沒勾時，TCP 上的封包不會變成桌面輸入。那時的狀態是只顯示：畫面仍在，尚未結束的一般觸控全部結束。

[給接收器廠商的說明](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-receiver-manufacturers) 要求 UIBC 的 TCP 關掉 Nagle，否則游標和鍵盤會被併包。

[MS-WFDPE](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-wfdpe/9ac9b0a4-0a94-483d-a8bf-cd7bfea99be0) 沒有另定義 UIBC。

## 誰開 TCP

1. M3：Source 問 `wfd_uibc_capability`。Sink 若支援，`port=none`，並列出自己的 category。
2. M4 或後續設定：Source 選雙方交集，在 `port=` 填自己已在聽的 TCP port，並可帶啟用設定。Source 先 listen，再送出這個請求。
3. Sink 主動連到 Source 的那個 port。整段 session 用這一條 TCP。
4. 之後可以用設定開關這條通道，不必重連。
5. M3 回 `none` 時，這條通道不會建立。

給一般觸控與數位筆的 M3 值：

```text
wfd_uibc_capability: input_category_list=HIDC; generic_cap_list=none; hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none
```

同時列出兩種觸摸，讓只勾其中一項的 source 仍有交集。Source 在 M4 選中的那一項，才是後面 HID Type 要用的值。規格的輸入類型名稱裡沒有 Pen，Pen 是作為 USB HID 頂層集合封裝在描述元內。

## 封包要記的事

共通頭在不帶 timestamp 時是 4 bytes：version 0、不帶 timestamp、input category 為 HIDC、length 是整個 TCP payload 的位元組數。

Generic 的確定義了 Touch Down / Up / Move，座標相對協商後的影片解析度，原點在左上。Microsoft 對 Windows source 寫的是 HID Commands，舊的 Windows offer 把 generic 清單設成 `none`。一般觸控與 S Pen 走 HIDC。

HIDC 每一則帶：USB path、與 M4 一致的 Single Touch 或 Multi Touch type、usage、長度、以及 descriptor 或 input report。每一組 path + type，要在 input report 之前先送 report descriptor，而且可以重送。預設 descriptor 只涵蓋 USB 鍵盤和 USB 滑鼠。觸摸與手寫筆沒有預設 descriptor。沒先送，Windows 無法解釋後面的座標。

### 複合描述元（Composite Report Descriptor）

Mirax 送出之 Report Descriptor 同時包含兩個頂層 Application Collection：
- **Report ID 1 (Touch Screen)**：Usage Page `0x0D` (Digitizers) / Usage `0x04` (Touch Screen)，支援多指 Tip Switch、In-Range、Contact ID、X、Y 與 Contact Count。
- **Report ID 2 (Integrated Pen)**：Usage Page `0x0D` (Digitizers) / Usage `0x02` (Pen)，包含 Physical Stylus Collection，支援 Tip Switch (`0x42`)、Barrel Switch (`0x44`)、Invert (`0x3C`)、Eraser Switch (`0x45`)、In Range (`0x32`)、X、Y 與 16-bit 壓力 Tip Pressure (`0x30`，0..4095)。

### 數位筆輸入報告

- 報告 ID 為 `2`。
- 開關位元組：bit 0 (Tip)、bit 1 (Barrel)、bit 2 (Invert)、bit 3 (Eraser)、bit 4 (In Range)。
- 懸停（Hover）：`inRange = true, tip = false, pressure = 0`。
- 下筆：`inRange = true, tip = true, pressure = 1..4095`。
- 抬筆：`inRange = true, tip = false, pressure = 0`。
- 離開偵測範圍：`inRange = false, tip = false, pressure = 0`。

座標語意來自 descriptor 的 logical min/max。實務上把 logical 範圍設成 M4 選定的影片寬高，原點放左上。

## 座標

接觸落在畫面上。扣掉畫面以外的區域，正規化到該次 session 的寬高，寫進已經送給 Windows 的 touch 或 pen report。手指離開時清除 Tip Switch。多指才用下一個 Contact ID。單指的 Contact Count 是 1。

用整片面板座標會偏移。控制項蓋住的接觸歸手機，該接觸結束，不送進 Windows。

## 最小實作順序

1. M3 廣告上面的 HIDC 能力，`port=none`。
2. 解析後續的 `port` 與啟用設定。啟用之後，由手機連到 RTSP 對端的該 port，並關掉 Nagle。
3. 先送複合 touch + pen report descriptor，再送 contact / pen report。
4. 只採樣畫面內的接觸與懸停，映射到協商解析度。
5. 在 Windows 上允許輸入，確認單指與 S Pen 按下、移動、懸停、放開落在延伸桌面的對應位置。
