# ADR 0007: 支援微軟擴充視訊格式作為備援模式

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
8. **自訂更新率**：自訂模式接受 24 至 240 Hz（含端點）的任意整數，不再吸附到 24、25、30、50、60。偏好文字的小數四捨五入，恰半取較高整數；正數超界夾到端點，原本無效或清空輸入的處理不變。超過 H.264 Level 5.1 時仍沿長寬比縮小尺寸，不降低已修正的更新率；互動模式仍沿用既有 30 Hz 備援與 EDID 偏好，不擴充固定模式表或宣稱來源／硬體已驗證高更新率。

9. **EDID 可表示性**：保留 EDID 1.3 與單一 128-byte 區塊。DTD 尺寸、像素時脈或必要的範圍描述符不能表示所選時序時，M3 回 `wfd_display_edid: none` 並記錄警告，不截斷、夾成錯誤上限、移除 1.3 必要的範圍描述符，或偷用 1.4 offset。literal 模式供給與 M4 選取不變；可表示的 EDID 範圍由量化後的 DTD 時脈向外取整，避免與 DTD 矛盾。失去 EDID 偏好提示可能影響來源選模，並非保證來源會選高 Hz。

## 查證補充

- Level 5.1 使用既有 `H264Level51`：每幀最多 36,864 macroblocks、每秒最多 983,040 macroblocks，各邊向上取整至 16 像素。不是先前口述的每秒約 5.3 億像素。
- 21 組之中可選 15 組：排除 2736×1824@60、3000×2000@60、3240×2160@60 與全部 4500×3000；3000×2000、3240×2160 的 24/30 Hz 仍可選。靜態界線不是實機解碼能力認證。
- EDID 沿用 1.3、單一 **128-byte** 區塊；可表示時，範圍描述符包含量化後的所選時序。不能表示時不提供 EDID 提示。偏好是提示；Windows 仍決定 M4 的實際選擇。
- [VESA E-EDID A Rev.1](https://glenwing.github.io/docs/VESA-EEDID-A1.pdf) §3.10、§3.10.3／表 3.20 要求 1.3 提供範圍描述符，各掃描率為單 byte 整數；[A Rev.2](https://glenwing.github.io/docs/VESA-EEDID-A2.pdf) 表 3.26 與 FAQ D-22–D-27 說明 offset／可選範圍描述符屬於 1.4。以上為 VESA 標準文件的鏡像。
- [Wi-Fi Alliance Miracast v2.3](https://www.wi-fi.org/system/files/Miracast_Specification_v2.3.pdf) §6.1.6 定義 `none`；Mirax 使用內建顯示，不屬於該節禁止回 `none` 的外接顯示 dongle 例外。`720×1280@240` 符合 Level 5.1，但 DTD 約 319.682 kHz 超出 1.3 的 255 kHz 範圍上限，因此不提供 EDID，仍供給該 literal 模式。
- M4 階層只用於同一請求中的有效選擇，不依欄位順序、不跨請求鎖死。微軟位元需為合法單選且在本次供給內；非法、保留或未供給的選擇記錄警告，不自行虛構替代模式。
