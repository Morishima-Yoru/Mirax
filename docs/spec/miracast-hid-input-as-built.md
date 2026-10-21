# Miracast HIDC 指筆輸入：現況規格

**基準：**`feature/uibc-pen-final` 的 `3c18c0b` 加上工作樹中尚未提交的接觸生命週期修復（2026-10-21）。本文件把實作可觀察的行為固化成規格，**不是獨立於實作的需求來源**；符合本文件不等於實機驗收。詞彙沿用 [第二螢幕情境](../CONTEXT.md)，決策沿用 [ADR 0001](../adr/0001-standalone-sink.md)、[ADR 0002](../adr/0002-visible-picture-axes.md) 和 [ADR 0003](../adr/0003-picture-scale.md)。歷次試驗及負面證據在 [完整研究](../research/uibc-multitouch-investigation.md)。

## 路徑與責任

```text
Windows 來源裝置 Win+K 允許輸入
    └─ RTSP M3 能力宣告 → 來源裝置 M4 指定 UIBC 埠與 HID type → PLAY／M15 enable
          └─ SinkConnectionController → UibcTouchChannel → 同一條 TCP → Windows 原生 HID/Pointer
手機 SurfaceView → PictureActivity 指／筆分類與 slot → PictureTouchMap 畫面座標
          └─ UibcTouchChannel → UibcPackets HIDC descriptor／report → 同一條 TCP
```

| 模組 | 現行責任與可查來源 |
| --- | --- |
| `WfdCapabilityTable`／`RtspSinkSession` | 觸控開關開啟才在 M3 宣告 `HIDC; generic_cap_list=none; hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none`；M4 取來源埠，若同時有 SingleTouch 優先 type **2**，否則 MultiTouch 選 type **3**；來源的 enable/disable 更新協商狀態；PLAY 後 sink 可送 M15 enable。[能力表](../../app/src/main/java/me/trinitrix/mirax/wfd/rtsp/WfdCapabilityTable.kt)、[RTSP](../../app/src/main/java/me/trinitrix/mirax/wfd/rtsp/RtspSinkSession.kt) |
| `SinkConnectionController` | 將協商的影片尺寸、HID type、來源埠和啟用狀態交給通道；RTSP 結束或停止時關閉通道。M15 嘗試與非同步 TCP 撥號各走自己的流程，**目前沒有以 socket 已連上作為 M15 條件**。[控制流程](../../app/src/main/java/me/trinitrix/mirax/wfd/SinkConnectionController.kt) |
| `PictureActivity`／`PictureContactState`／`PictureTouchMap` | `SurfaceView` 接收 Android 指／筆與 hover；依協商畫面尺寸而非可能補齊的 decoder buffer 映射局部座標；正式 type-2 接觸狀態按 Android pointer ID 配 slot 0–9，僅 type-3 的診斷備援限於描述元的 slot 0–1，保存已結束的指／筆及疊層覆蓋位置。[畫面輸入](../../app/src/main/java/me/trinitrix/mirax/PictureActivity.kt)、[接觸狀態](../../app/src/main/java/me/trinitrix/mirax/session/PictureContactState.kt)、[座標](../../app/src/main/java/me/trinitrix/mirax/session/PictureTouchMap.kt) |
| `UibcTouchChannel`／`UibcPackets` | 通道非同步連到來源裝置 UIBC 埠，開啟 TCP_NODELAY；單一 writer 排序 descriptor、初始化空報告與後續 report；組包模組產生 HIDC 外框及 Touch/Pen HID bytes。[通道](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcTouchChannel.kt)、[組包](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcPackets.kt) |

## 正式 HID 形狀與輸入語意

1. 已驗證的正式路徑在 M4 包含 `SingleTouch` 時選 HIDC **type 2**，每次活動連線只有一條 UIBC TCP。此路徑的 report descriptor **1452 bytes**：十個獨立的 Digitizers/Touch Screen 單接觸頂層集合（TLC，Report ID **1–10**，各 135 bytes），一個 Digitizers/Pen TLC（Report ID **11**，102 bytes）。每個 slot 使用自己 TLC 的 local Contact ID 0；**不是**一個宣告十指 Contact Count Maximum 的單一 TLC。若 M4 只有 `MultiTouch`，程式仍可能選 type 3，但其 packed-touch／feature report 路徑沒有正式 Windows 指筆驗收；第二條 socket 非正式路徑。[描述元](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcPackets.kt)、[研究](../research/uibc-multitouch-investigation.md)。
2. UIBC HIDC 封包：4-byte common header（version 0、無時間戳、HIDC category、封包總長）＋ USB path、HID type、usage、2-byte payload length 的 5-byte header ＋ HID bytes；必要時以 1 byte 補成偶數長度。usage 1 為 descriptor，usage 0 為 input report。通道在尺寸已知且啟用時先排 descriptor，再排每個 slot 的空報告和筆的離範圍報告；低階 API 可暫存尚未可送的報告，但正式 UI 不在 TCP 及 descriptor 就緒前接納新接觸。[封包](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcPackets.kt)、[傳送順序](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcTouchChannel.kt)。
3. 正式 type-2 路徑每一個指事件影格按 slot 順序送出十份 Touch report，包括未活動 slot。活動 slot 的 tip／in-range／confidence 對應旗標 `0x07`、放開為 `0x06`，並帶 X/Y、相同的 100 微秒 scan time 與 Contact Count；依使用者確認的 [一般觸控](../CONTEXT.md) 定義**最多十指**。若來源只選 type-3，原有診斷備援共用相同 TCP，描述元與 UI 都僅分配兩個 slot；不得把第三指列為已傳遞。第 11 指（type-3 為第 3 指）無可用 slot 時不分配；原有指抬起後仍不追認已按下的溢出指，必須先放開再按下。[指報告](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcPackets.kt)、[slot 分配](../../app/src/main/java/me/trinitrix/mirax/session/PictureContactState.kt)。
4. Pen input report 為 8 bytes（ID 11、旗標、X/Y、16-bit 壓力）。tip bit 0、barrel bit 1、invert bit 2、eraser bit 3、**in-range bit 5**；Android 壓力轉成 1–4095 的 tip 值，hover／離範圍為零。`TOOL_TYPE_STYLUS`／`TOOL_TYPE_ERASER` 走獨立筆路徑；相同的在範圍內筆樣本仍持續送，僅重複的離範圍 idle 樣本略過。[筆輸入](../../app/src/main/java/me/trinitrix/mirax/PictureActivity.kt)、[報告](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcPackets.kt)、[節奏](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcTouchChannel.kt)。
5. 畫面縮放依目前 `PicturePlacement` 決定 SurfaceView 位置；有效視口與可移動 DebugOverlayView 的覆蓋區域先決定接觸歸屬，仍在畫面內的點才用 `clampedPixel` 映到協商影片像素。新修復令滑出視口、滑入／被疊層蓋住的指筆立刻發終止報告，不讓同一個 Android pointer 的後續 MOVE 重新按下；畫面矩形變化、只顯示、控制面板展開及離開畫面時結束所有接觸。UI 僅在 TCP 已連且 descriptor 排入、通道啟用時接納新按下，依通道版本偵測 disable→enable 或新 TCP，避免斷線前已按下的接觸於重連後復活。這些是程式碼及局部回歸測試的行為，**不是新 APK 的 Windows native 驗收**。[畫面佈置／事件](../../app/src/main/java/me/trinitrix/mirax/PictureActivity.kt)、[接觸狀態](../../app/src/main/java/me/trinitrix/mirax/session/PictureContactState.kt)、[座標](../../app/src/main/java/me/trinitrix/mirax/session/PictureTouchMap.kt)。
   - 原本沒有正式 caller 的 `ScreenViewportGeometry` 及其專屬測試已移除；實際畫面矩形仍由 `PicturePlacement` 計算，接觸生命週期直接由 `PictureActivity` 與 `PictureContactState` 協調。
6. `setEnabled(false)` 對現有接觸排入抬起報告、清除待送狀態，但不斷開 TCP；重新啟用時重送 descriptor。部分被覆蓋的接觸以同一 socket 送個別放開並維持其他指仍按下；報告寫入排序的 `epoch` 與 UI 觀察的 `inputGeneration` 分開，避免畫面尺寸／type 變動或快速停用再啟用時丟棄已排隊的 release。`open()` 新建通道／連線；RTSP 停止時關閉舊 socket。撥號失敗會記錄但無自動重試；若連線後實際寫入失敗，會使該通道失效、關閉其 socket、阻止後續新接觸並令舊 Android 手勢失效；**無自動重撥或在失敗的 socket 補發 release**。這份現況**不保證**每次重新連線或持續穩定的 Windows pointer ID。[生命週期](../../app/src/main/java/me/trinitrix/mirax/wfd/UibcTouchChannel.kt)。

## 驗證矩陣與不能外推之處

| 層次 | 已有證據 | 不能宣稱 |
| --- | --- | --- |
| JVM／組包／RTSP | `WfdProtocolTest`、`UibcPacketsTest`、`UibcTouchChannelTest`、`PictureContactStateTest` 覆蓋協商、描述元、slot、排程、部分釋放、畫面／控制項／通道生命週期及 socket 寫入失敗；本輪完整建置為 **16 suites／250 tests，全數通過** | JVM 測試證明不了 Windows 的 HID/Pointer 注入或 UI 視窗中途佈局的所有情況 |
| Android 測試 | `MiraxMultiTouchInjectionTest` 的部分案例從 `PictureActivity` 注入；十 slot 的案例直接提交給通道 | 十指皆經 UI／視口及控制項檢驗；Android `write` 等於 Windows 收到 |
| Windows 現場 | 封存 APK SHA-256 `1354c723…`：run 86 有 live TOUCH 580／PEN 581 同時有效接觸；run 87 有十指與 404 對／808 份 live 快照。probe 須先前景啟用／九點命中，核對 native pointer ID、型別、`INCONTACT`、取消／終止旗標和 release | 從封存 run 倒推所有早期負例、壓力／側鍵／橡皮擦、所有 App 手勢、每次重連、持續 pointer ID |
| 本輪工作樹 APK | `fa8290d2…` 是接觸修復**之前**的分支建置；修復後的本輪 app APK SHA-256 `21954dba061a5bf40d3d019977498c60c44d532ec6e3c978ba668b898b12bd1a`，AndroidTest 為 `a6cfab89119cc25a5ca249512b8bdb5da92c16ce5cecb8ec4cf4fcec7b43b9fb`。本輪兩次重新配對與 Windows 原生 live pointer 驗證分別確認 UI 指筆同時接觸、既有通道十指並發及釋放；見 [本輪重驗](../research/uibc-current-apk-live-20261021.md) | 不等於實體 S Pen、十指全程經 UI、移動疊層、四種縮放或持續重連均已實測；不能把本輪結果外推到其他 APK／裝置 |

Windows probe 原始碼在 [`scripts/pointer-probe/MultiTouchProbe.cs`](../../scripts/pointer-probe/MultiTouchProbe.cs)；原始正負證據及精確 APK/probe 雜湊在 [run 86/87 研究紀錄](../research/uibc-multitouch-investigation.md) 和 [交付結論](../research/uibc-pen-release-conclusion.md)。此實作不需要新的 Windows 驅動／背景服務；Windows Win+K 的「允許輸入」與手機的觸控開關各自必要。實體 S Pen 基本操作僅有使用者在 SM-F946U 上的人工回饋，不代表壓力等進階筆功能已驗證。
