# 一般觸控

結論：可以。未安裝驅動的 Windows 10/11 Miracast source 會把 Sink 送來的接觸收成自己的指標輸入。前提是使用者在 Win+K 允許來自該裝置的輸入，而且 Sink 用 HIDC / USB 送按下、移動、放開。規格裡的 Generic 座標是另一條封包。Microsoft 寫明 Generic 只涵蓋 ASCII 按鍵；Windows 要的是 HID Commands。

詞彙以 [CONTEXT.md](../CONTEXT.md) 為準。S Pen 與一般觸控是兩種輸入，S Pen  不在這次範圍。

本機實驗裡的接收端當時把 `wfd_uibc_capability` 回成 `none`，不解析 source 給的 TCP port，畫面也沒有把接觸送出去。下面是要補上的路徑，不是已在這台 Windows 上定案的封包。

## 依據

| 等級 | 內容 |
|------|------|
| 規格 | Wi-Fi Display Technical Specification v2.1 的 UIBC 章節。此處只記協商順序與欄位角色 |
| Microsoft | Windows 無線投影說明、給接收器廠商的 UIBC 說明、Continuum 對 HID Commands 的要求 |
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

給一般觸控的 M3 值：

```text
wfd_uibc_capability: input_category_list=HIDC; generic_cap_list=none; hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none
```

同時列出兩種觸摸，讓只勾其中一項的 source 仍有交集。Source 在 M4 選中的那一項，才是後面 HID Type 要用的值。`Gesture` 出現在舊 offer 裡；按下、移動、放開不依賴它。規格的輸入類型名稱裡沒有 Pen。一般觸控不需要那個 token。

## 封包要記的事

共通頭在不帶 timestamp 時是 4 bytes：version 0、不帶 timestamp、input category 為 HIDC、length 是整個 TCP payload 的位元組數。

Generic 的確定義了 Touch Down / Up / Move，座標相對協商後的影片解析度，原點在左上。Microsoft 對 Windows source 寫的是 HID Commands，舊的 Windows offer 把 generic 清單設成 `none`。一般觸控走 HIDC。

HIDC 每一則帶：USB path、與 M4 一致的 Single Touch 或 Multi Touch type、usage、長度、以及 descriptor 或 input report。每一組 path + type，要在 input report 之前先送 report descriptor，而且可以重送。預設 descriptor 只涵蓋 USB 鍵盤和 USB 滑鼠。觸摸沒有預設 descriptor。沒先送，Windows 無法解釋後面的座標。

座標語意來自 descriptor 的 logical min/max。實務上把 logical 範圍設成 M4 選定的影片寬高，原點放左上。

[Windows 觸控螢幕 HID 集合](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/touchscreen-required-hid-top-level-collections) 要求頂層集合是 Digitizer / Touch Screen，並帶 Contact ID、X、Y、Tip Switch、Contact Count。這是 USB 觸控裝置指引，還不是這條 Miracast 解析器的實機保證。它是 descriptor 的第一個候選。

絕對座標的 USB 滑鼠可以少送 descriptor，但那是游標點擊，不是一般觸控。

## 座標

接觸落在畫面上。扣掉畫面以外的區域，正規化到該次 session 的寬高，寫進已經送給 Windows 的 touch report。手指離開時清除 Tip Switch。多指才用下一個 Contact ID。單指的 Contact Count 是 1。

用整片面板座標會偏移。控制項蓋住的接觸歸手機，該接觸結束，不送進 Windows。

WFD Extended Capability 的一個 bit 表示支援 UIBC。`WifiP2pWfdInfo` 沒有這個欄位的 API。Beacon 通常不帶它，Probe Response 才會。Windows 在該 bit 為 0 時是否仍於 M3 詢問 `wfd_uibc_capability`，這支 sink 還沒有對照 log。2016 年的 inbox source 會在 M3 列出這個參數。

## 還沒用這台機器定案的部分

- 這支 sink、這台 Windows 11 的 M3 是否仍列出 `wfd_uibc_capability`。
- Windows 對 `SingleTouch/USB` 與 `MultiTouch/USB` 實際勾哪一項。
- 使用者沒允許輸入時，封包仍可能送到，但不會變成點擊。那就是只顯示。

## 最小實作順序

1. M3 廣告上面的 HIDC 能力，`port=none`。
2. 解析後續的 `port` 與啟用設定。啟用之後，由手機連到 RTSP 對端的該 port，並關掉 Nagle。
3. 先送 touch report descriptor，再送 contact report。
4. 只採樣畫面內的接觸，映射到協商解析度。
5. 在 Windows 上允許輸入，確認單指按下、移動、放開落在延伸桌面的對應位置。
