# ADR 0004: Session God Module Decomposition

狀態：接受。

## 背景

`MiraxSession` 是一個超過 1,000 行的 "God Module"，處理了過多無關的職責：從 Wi-Fi Direct 的生命週期、畫面縮放 (Picture Scale) 的 UI 偏好、一直到 RTSP 交握過程的診斷日誌字串連接。

這種淺層 (shallow) 架構導致其介面 (`SessionAction` 和 `SessionSnapshot`) 幾乎和實作一樣複雜，使得測試範圍過大，並且在進行微小 UI 修改時需要重建整個龐大的 Snapshot。這個模組缺乏局部性 (locality)。

## 決定

我們選擇將 `MiraxSession` 拆解並深化，將其轉變為一個 Facade (外觀模式) 路由器：

1. **保留外部測試接縫 (Seam)**：`MiraxSession` 繼續保留 `handle(SessionAction)` 和 `snapshot()` 作為外部的唯一接縫，維持測試的槓桿效應 (leverage)。
2. **拆解為深層模組 (Deep Modules)**：
   - `AdvertisingState`：專注於 WFD 擁有權與啟動邏輯。
   - `CapsAndProvisioning`：專注於解析度模式檢查與一次性 `wm size` 的寫入。
   - `DisplayPreferences`：專注於底部操作區 (bottom handle)、懸浮球以及畫面縮放等 UI 偏好設定。
3. **萃取外部 Adapter**：將 RTSP 的連線診斷日誌完全移出 Session，定義一個新的 `ConnectionDiagnosticRepository` 介面，讓字串拼接的邏輯不再污染核心領域狀態。

## 後果

- `MiraxSession` 變得很淺，但它隱藏的內部模組變得很深且高度聚焦，提升了程式碼的局部性。
- 新增功能時，若不屬於現有分類，可能需要建立新的內部領域模組。
- `SessionSnapshot` 變成了由外觀層向各子模組查詢後組裝而成的投影 (projection)。
