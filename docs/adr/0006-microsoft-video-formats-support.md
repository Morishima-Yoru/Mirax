# ADR 0006: 支援微軟擴充視訊格式作為備援模式

狀態：接受。

## 背景
Windows 的 Miracast Source 可支援 `[MS-WFDPE]` 協定擴充。針對 Surface 系列常見之 3:2 螢幕，微軟定義了 `microsoft_video_formats` 48 位元點陣圖（共 21 組模式）。先前 Mirax 僅回傳全零，並以 `microsoft_custom_video_formats` 宣告 Fold 原寸橫向 `2176×1812`。不支援自訂格式的來源只能從其他已宣告格式選擇；並非必然降到 1080p 或 720p。Windows 10 不因安裝 Windows 11 的 KB 而取得自訂格式支援。

## 決策
1. **定位與職責**：將 `microsoft_video_formats` 定位為「次級高解析度相容備援格式」。第一順位仍保留給 `microsoft_custom_video_formats` (2176×1812 原寸)，標準 CEA/VESA 點陣圖為第三順位。
2. **領域模型分離**：將 21 組 3:2 模式由專屬的 `MicrosoftVideoModes` 領域物件管理其 48 位元索引、解析度與編解碼，不直接混入 `StandardVideoModes` 之標準 CEA/VESA/HH 清單中，避免點陣圖跨界混淆。
3. **動態能力宣告 (M3)**：RTSP M3 產生的 48 位元 Hex 遮罩嚴格與使用者在影像設定中勾選的模式集合連動，且受晶片硬體解碼器上限過濾（剔除超過 4096 水平寬度與 H.264 Level 5.1 像素率上限之 4500×3000 等模式）。
4. **預設狀態**：全新安裝時，微軟 3:2 模式預設全數不勾選（Opt-in），維持輕量預設廣播集合。
5. **EDID 連動**：當使用者將 3:2 模式指定為首選模式（`preferredMode`）時，`wfd_display_edid` 直接為其產出 EDID 詳細時間描述符（DTD），引導 Windows Source 優先採用。
6. **M4 決策階層**：`RtspSinkSession.applyParameters()` 解析 M4 `SET_PARAMETER` 時，遵循 `microsoft_custom_video_formats` > `microsoft_video_formats` > `wfd_video_formats` / `wfdx_video_formats` 之優先權順位。
7. **持久化**：沿用既有 `Set<VideoMode>` 與偏好鍵，不新增遷移；限制套用於能力供給，不刪除已儲存的選擇。變更於下次連線生效。

## 查證補充

- Level 5.1 使用既有 `H264Level51`：每幀最多 36,864 macroblocks、每秒最多 983,040 macroblocks，各邊向上取整至 16 像素。不是先前口述的每秒約 5.3 億像素。
- 21 組之中可選 15 組：排除 2736×1824@60、3000×2000@60、3240×2160@60 與全部 4500×3000；3000×2000、3240×2160 的 24/30 Hz 仍可選。靜態界線不是實機解碼能力認證。
- EDID 沿用單一 **128-byte** 區塊，範圍描述符包含所選時序。偏好是提示；Windows 仍決定 M4 的實際選擇。
- M4 階層只用於同一請求中的有效選擇，不依欄位順序、不跨請求鎖死。微軟位元需為合法單選且在本次供給內；非法、保留或未供給的選擇記錄警告，不自行虛構替代模式。
