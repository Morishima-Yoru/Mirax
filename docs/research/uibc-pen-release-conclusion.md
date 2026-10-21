# Miracast 指筆交付與清理結論（2026-10-20）

## 驗收

`feature/uibc-pen-final` 由 `main` 建立，依序整合 `fix/touch-input`、`verify/uibc-finger-20261017`、`verify/uibc-multitouch-20261018`。最後一個 merge 訊息含 `victory`。指筆實作 commit `51f9535` 保留完整研究報告和測試；清理另行提交，以便追溯兩階段差異。原有但與指筆無關的 `PrimarySinkBeacon.java` WPS 改動**未納入分支**，留在本機專用 stash，並在 `C:/Users/User/AppData/Local/Temp/opencode/mirax/release-pen-final-20261020/excluded-helper-wps.patch` 保存補丁。

Android 仍以同一條 type-2 UIBC socket 傳送十個 Touch collection（IDs 1–10）和一個 Pen collection（ID 11）。Windows 原生 live pointer 在 run 86 確認 TOUCH+PEN 同時接觸，在 run 87 確認十指；原始旗標、型別、ID、Cast／注入窗口和 release 均已核對。最後一次完整 Gradle 任務與 16 個 suite、230 個測試通過；本文件不將 Android socket 寫入或裝置列舉當成 Windows 接觸證據。使用者另回報在 SM-F946U 上直接使用手指、多指與實體 S Pen 操作投影成功；這是人手體驗，不是對壓力、側鍵、橡皮擦、所有 App 或所有重連的自動驗證。

**交付 APK 的驗證界線：**上述 Windows 與實體筆實驗使用 SHA-256 `1354c723…` 的 APK，當時包含工作樹中未提交的 `PrimarySinkBeacon.java` WPS 改動。按本次要求排除該改動後，在最終分支重新建置所得 APK 為 `fa8290d2823e7994df65221273c3a705da6a0f65c03df8e9db19dc6277979ae9`，AndroidTest APK 仍為 `a6cfab89…`。兩版 APK 的 886 個 ZIP 條目中只有 `classes3.dex` 的內容不同；差異包含 P2P beacon 類別。**因此 run 86/87 的 Windows live pointer 證據不可冒充為對此新 APK 的驗收**，而 WPS 配對／連線尤其應用新 APK 再驗。這不改變已封存實驗的結論。

## 清理與可重現性

- 移除 Git 末端的 41 個 `scripts/_*.ps1` 單次診斷腳本、三個硬編碼本機路徑的 probe 產生器、`scripts/state.png` 暫存截圖和編譯後的 `scripts/pointer-probe/MultiTouchProbe.exe`。保留可檢查的 `scripts/pointer-probe/MultiTouchProbe.cs`；該程式為驗證工具，不是 Android 安裝套件的一部分。
- 工作樹根目錄 19 份零散 `.log` 已複製至 `C:/Users/User/AppData/Local/Temp/opencode/mirax/release-pen-final-20261020/original-root-logs/`，並以 `artifacts.sha256` 核對，再從根目錄移除。
- **保留** `C:/Users/User/AppData/Local/Temp/opencode/mirax/` 原本的正／負／無效實驗原始檔、run 86/87 的 probe 原始碼與測試 EXE、APK、雜湊清單及舊手機 APK 備份。刪掉 Git 末端的 EXE 不會抹去已提交的歷史；如需從整段 Git 歷史消除二進位檔，必須另外規劃改寫歷史，不屬於本次清理。
- 早期研究中的 S Pen 範圍限制是歷史狀態；現行功能與限制以本頁和 [完整調查](uibc-multitouch-investigation.md) 為準。Windows 的 Cast 視窗曾遮擋診斷 probe 的筆座標；這是已確認的量測缺陷，並**不能**倒推所有歷史負例的原因。

## 界線

十個獨立單觸點 TLC 能產生同時有效的 Windows TOUCH pointer；不因此宣稱每個 Windows App 都會將它們組成預期的縮放手勢。另未證明所有連線的穩定 pointer identity、不間斷筆跡、壓力／側鍵／橡皮擦或不用 ADB 的啟動流程。本次不新增 Windows 驅動、服務、第二個正式 socket 或 Android 端猜測性計時修補。
