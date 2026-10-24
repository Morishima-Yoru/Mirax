# 投放畫面釘選可行性（2026-10-24）

## 結論

**一般 App 可進入釘選，但釘選不能完全解決上下邊緣觸控被系統搶走。** 不把這次實驗改成投放自動釘選。

## 實驗

- 裝置：TrebleDroid vanilla，Android 14，800×1280，手勢導覽（`navigation_mode=2`）。不是 Samsung Fold 驗收。
- 以 `PictureActivity` 的 debug mock 顯示全螢幕拉伸畫面，保留現有 sticky immersive 與返回手勢排除；在 SurfaceView 記錄實際送達的 `MotionEvent`。
- 直接呼叫公開 `Activity.startLockTask()`，確認 Android 顯示的釘選提示後，實測 `ActivityManager.lockTaskModeState=2`（`PINNED`）。沒有 Device Owner、沒有用 ADB 強制 lock task、沒有改全機導覽或釘選偏好。
- 用同一組 UIAutomator 滑動，在頂部 y=1 向下、底部 y=1278 向上；除了單次滑動，也測試系統列浮出後再次從邊緣滑動。

| 場景 | 未釘選 | 已釘選 |
|---|---|---|
| 頂部第一次下滑 | 系統列浮出；SurfaceView 收到 DOWN→MOVE→UP | 系統列底色仍浮出，但狀態圖示隱藏；收到 DOWN→MOVE→UP |
| 頂部連續兩次下滑 | 第二次展開通知面板，前景變成 SystemUI；SurfaceView 只收到一次完整接觸 | 通知面板沒有展開，App 保持前景；但 SurfaceView 仍只收到一次完整接觸，第二次沒有 DOWN |
| 底部第一次上滑 | 導覽列浮出；SurfaceView 收到 DOWN→MOVE→UP | 導覽列仍浮出；SurfaceView 收到 DOWN→MOVE→UP |
| 底部連續兩次上滑 | 本次記錄出現 CANCEL，前景變成 Launcher／最近使用畫面 | App 保持前景；SurfaceView 收到兩次完整 DOWN→MOVE→UP |

釘選確實能阻止通知面板展開，並改善這組底部操作；但不能據此宣稱頂部觸控完整、系統列不再浮出，或所有底部操作都歸 App。解除釘選用的系統保留操作也不能拿掉。

`WindowInsets.isVisible()` 在這次實驗的 transient 系統列浮出時仍回報 false，不能單靠它判定「系統列沒有顯示」。上表同時核對截圖、前景套件與接觸序列。

## 清理與證據

- `stopLockTask()` 非同步完成後，已確認回到 `NONE`；沒有留下裝置鎖定或新的系統設定。
- 第一輪探針把解除要求當成同步完成，立即斷言失敗；第二輪等待實際狀態轉換後通過。這是探針的時序修正，不是釘選或正式功能修復。
- 暫時探針已移出正式測試目錄。完整探針、instrumentation 輸出、MotionEvent 日誌、截圖及 view hierarchy 保存在測試機主機的 OpenCode 暫存目錄：`C:/Users/User/AppData/Local/Temp/opencode/mirax/pinning-probe/`，以 `repeat-*` 與 `repeat/` 為第二輪證據。
- 尚未驗證 Samsung 韌體、實體手指連續滑動、Windows 端 UIBC 或實際 Miracast 串流。

## 補充：shell 狀態列禁用旗標也不等於觸控直通

後續在同一台 vanilla 上，暫時套用 `cmd statusbar send-disable-flag statusbar-expansion home recents`；`dumpsys statusbar` 確認 `mDisabled1=0x01210000`，測試後以 `none` 還原至 `mDisabled1=0x0`、`mDisabled2=0x0`。沒有保留全域封鎖，也未加入正式投放流程。

- 連續兩次從頂部下滑：App 保持前景，通知面板沒有展開；但截圖仍出現時間與狀態圖示，SurfaceView 只收到 1 組 DOWN／UP，第二組接觸沒有送達。
- 連續兩次從底部上滑：App 保持前景，SurfaceView 收到 2 組 DOWN／UP；系統列仍浮出。
- 這只能限制部分系統動作，不能解決整個上／下邊緣觸控要求。不能把「多工沒有開啟」當成「系統已不攔截事件」。

證據在 `C:/Users/User/AppData/Local/Temp/opencode/mirax/handle-visibility/` 的 `statusbar-before.log`、`statusbar-protected.log`、`statusbar-after.log`、`bar-probe-logcat.log` 與 `bar-probe/` 截圖。AOSP Android 14 的 [`DisplayPolicy.requestTransientBars`](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/services/core/java/com/android/server/wm/DisplayPolicy.java) 是獨立的系統邊緣手勢處理，並不因上述 App 或 status-bar disable 設定就全面停止。
