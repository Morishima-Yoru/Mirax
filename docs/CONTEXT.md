# 第二螢幕

手機作為 Windows 的 Miracast 第二螢幕。這份詞彙只記這個情境裡的說法。

## Language

**畫面**:
使用者看見的那幅 Windows 桌面矩形。位置以它的左上為原點，向右、向下沿着使用者看見的邊；寬沿着看見的橫邊，高沿着豎邊。畫面在面板上轉向時，軸跟着畫面。
_Avoid_: 面板, 黑邊, 控制項

**控制項**:
疊在畫面上、屬於手機的操作區。在其上按下的接觸歸手機。蓋住畫面上某個接觸的瞬間，該接觸結束。
_Avoid_: 畫面

**一般觸控**:
在可觸控的畫面裡按下、歸 Windows 的接觸。面板報上來的都算，含指尖、指節與手掌，不另做手掌排除；每一個只有按下、移動、放開，可同時多個且不設上限，捏合與捲動不是另外的輸入。手機的返回滑動、狀態列與多工不取走畫面上的這些接觸。
_Avoid_: 基本觸摸, 觸控回傳, 手勢, 手掌排除, S Pen

**接觸結束**:
滑出畫面、滑上控制項或控制項蓋住該點，使該接觸不再是一般觸控；畫面矩形改變或進入只顯示，則尚未結束的全部結束。結束後仍按着也不恢復，必須先放開，再在可觸控的畫面裡按下。
_Avoid_: 暫停, 追溯

**可觸控**:
觸控通道開着、且使用者允許輸入的狀態。此狀態下，在畫面裡的新按下成為 Windows 的輸入。
_Avoid_: 只顯示

**只顯示**:
沒有觸控通道、使用者未允許輸入、或通道在連線中途關掉。畫面仍在；進入此狀態時尚未結束的一般觸控全部結束，手機也不拿這些接觸操作自己。
_Avoid_: 斷線, 黑屏, 可觸控

**S Pen**:
這台 Fold 的筆。與一般觸控是兩種輸入。
_Avoid_: 觸控筆, stylus

## 縮放

**縮放**:
畫面在面板上的擺法。四種：等比、鋪滿、拉伸、原寸。
_Avoid_: Full, Scratch, scale mode

**等比**:
畫面整個看得見，比例不變，置中。面板上畫面以外的區域不屬於畫面。
_Avoid_: 適應, 黑邊, fit, contain

**鋪滿**:
畫面蓋滿面板，比例不變，中心對齊；超出面板的部分不顯示。
_Avoid_: Full, 裁切, 裁滿, fill, cover

**拉伸**:
畫面四邊貼齊面板，比例不保留。
_Avoid_: Scratch, stretch

**原寸**:
畫面的一個像素對面板的一個像素，不縮放，左上對齊。畫面大於面板時，右方和下方在面板之外。
_Avoid_: 1:1, 原始大小, 點對點

## 架構與連線

**視口**:
面板中實際呈現畫面的有效可見矩形區域，依當前縮放決定邊界。落在視口之外或被控制項覆蓋的面板區域均不屬畫面。
_Avoid_: 畫布, 顯示層, ViewportContainer

**活動連線**:
從 Wi-Fi Direct 群組建立（P2P group-up）直到播放結束或中斷期間的暫態實例。其所屬狀態（協商模式、日誌暫存、返回鍵確認）在連線結束時徹底歸零。
_Avoid_: Session實例, 永久連線

**活動連線階段**:
活動連線內部的生命週期階段。四種：無、協商中、供給凍結、播放中、已結束。
_Avoid_: SessionPhase, 階段狀態
英文代碼術語：`ConnectionPhase { NONE, NEGOTIATING, OFFER_FROZEN, PLAYING, ENDED }`

**廣告階段**:
WFD 廣告與信標監聽的內部生命週期階段。四種：閒置、廣告中、信標監聽中、被拒絕。
_Avoid_: AdvertisingState, 廣告狀態
英文代碼術語：`AdvertisingPhase { IDLE, ADVERTISING, BEACON_LISTENING, DENIED }`

**連線歷程**:
一次完整連線嘗試的固定快照紀錄，包含來源主機、連線起訖時間、成功與否、協商參數與診斷日誌，最多保留 500 筆，持久化儲存（DataStore），重啟不遺失。進階設定提供一鍵清除。
_Avoid_: 歷史記錄, LogBuffer

## E2E 測試與驗證

**端到端測試 (E2E Test)**:
從電腦（Windows 投射端）透過 `click-fold.ps1` 自動化發起 Miracast 投射，在手機端（Mirax 接收端）經 Wi-Fi Direct 連線、RTSP 協商、H.264 解碼串流至 `PictureActivity` 前景顯示，並驗證即時除錯疊層（Debug Overlay）完整呈現的自動化閉環檢驗流程。
_Avoid_: 單元測試, 局部測試

**UI 階層傾印 (UiAutomator Dump)**:
透過 `adb shell "uiautomator dump /sdcard/dump.xml"` 取得手機當前畫面節點樹 XML，作為視圖節點（如 `DebugOverlayView`）是否存在與可見的客觀真實依據（Ground Truth）。
_Avoid_: 純日誌推定, 盲測

**投射自動化腳本 (click-fold.ps1)**:
位於 `scripts/click-fold.ps1` 的 PowerShell 測試腳本。自動呼叫 Windows 控制中心（Win+K 快速設定），利用 OCR 識別掃描到的裝置名稱正則（`-DeviceMatch`）並點選連線，並留存前後螢幕擷圖。
_Avoid_: 手動投射, 人工點擊

**除錯疊層 (Debug Overlay)**:
位於 `PictureActivity` 右上角（邊界約 `[848,24][1256,776]`）的半透明疊層（`DebugOverlayView`）。由 500ms 週期性 Handler 定時觸發 `updateDebugOverlay()`，繪製串流數據（Decoder/Input/Output/Frames/Bitrate）與即時折線圖（FPS, Latency, Bandwidth）。
_Avoid_: 浮動按鈕, 吐司訊息

**多模態視覺檢驗 (OCR 檢驗)**:
利用 `click-fold.ps1` 產生的螢幕擷圖與本機 OCR 引擎識別畫面上實際繪製出的文字（如 `DEBUG OVERLAY`, `STREAM INFO`, `1280X720@60`），確認像素層級的正確繪出。
_Avoid_: 無視覺驗證


