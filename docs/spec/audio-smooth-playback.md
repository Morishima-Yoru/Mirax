# Spec: 第二螢幕音訊的低 CPU 平滑保護

## Problem Statement

使用者將來源裝置的 Windows 畫面投放到手機時，剛連線或手機 CPU 忙碌期間，會頻繁聽到刺耳破音。使用者接受硬體吞吐量與排程的限制，但希望無法連續播放時優雅退化成短暫、柔和的靜音，而非直接播放不連續波形。

目前音訊播放器立即啟動 AudioTrack，使用共享 IO dispatcher，並在最多約 120ms 的待播佇列滿時直接丟棄舊 PCM。現有測試證明資料交付與有界佇列，不證明可聽平滑度、CPU 成本或音畫同步。CPU 忙碌與破音的相關性來自使用者觀察；尚未以實機證據排除來源音訊、封包遺失或其他原因。

## Solution

連線初期先安靜累積足夠 PCM，再平滑淡入。短暫供料不足或丟棄舊音訊時，只在可控制的實際輸出接點執行極短淡出／淡入，必要時重新預載。容許小幅、有界的音訊緩衝，負載回穩後以有限次丟棄追回較低延遲。

正常播放不執行全時段逐樣本 DSP；保護措施本身須有 CPU 預算。這是降低本機播放不連續的 UX 保護，不是保證任何負載下都沒有破音，也不是新增 A/V 同步系統。

## User Stories

1. As a Mirax user, I want quiet audio preparation when an active connection starts, so that initialization does not immediately expose harsh pops.
2. As a Mirax user, I want playback to start as soon as sufficient audio is buffered, so that a healthy phone does not wait through a fixed startup delay.
3. As a Mirax user, I want sparse audio to become eligible for playback after a bounded preparation interval, so that a short sound is not held indefinitely.
4. As a Mirax user, I want a source without audio to remain silent, so that an expired preparation timer does not create noise or unnecessary playback.
5. As a Mirax user, I want the first audible samples to fade in, so that playback does not begin with an abrupt waveform edge.
6. As a Mirax user, I want transient supply interruptions to degrade into a brief soft gap when possible, so that phone load does not turn into repeated sharp crackles.
7. As a Mirax user, I want recovered audio to resume smoothly, so that recovery does not introduce another click.
8. As a Mirax user, I want discarded stale audio to have softened audible splice boundaries, so that catching up does not add avoidable popping.
9. As a Mirax user, I want minor scheduling delays to be absorbed by bounded buffering, so that ordinary background work does not immediately interrupt audio.
10. As a Mirax user, I want old queued audio to be discarded instead of accumulating indefinitely, so that audio does not fall progressively farther behind the picture.
11. As a Mirax user, I want catch-up to finish even during loud continuous audio, so that the player does not wait forever for silence.
12. As a Mirax user, I want both stereo channels to share the same transition gain, so that protection does not shift the stereo balance.
13. As a Mirax user, I want normal playback samples to remain unchanged outside protection transitions, so that the feature does not continuously alter the sound.
14. As a Mirax user, I want glitch protection to add very little CPU work, so that it does not worsen the load causing the problem.
15. As a Mirax user, I want audio preparation and recovery to leave RTP reception and picture delivery unblocked, so that improving audio does not freeze the picture.
16. As a Mirax user, I want stopping an active connection to cancel pending audio, so that old sounds do not continue into the next connection.
17. As a Mirax user, I want reconnecting to reset all buffering and fade state, so that the new active connection cannot play the previous connection's PCM.
18. As a Mirax user, I want output failures to release audio resources without crashing the RTP receiver, so that an audio failure does not unnecessarily end picture reception.
19. As a Mirax user, I want Windows casting to remain unchanged, so that the improvement does not require extra source-side software or setup.
20. As a developer, I want deterministic tests at the player-to-output boundary, so that preparation, interruption and recovery can be verified without listening to CI hardware.
21. As a developer, I want short writes, cancellation and output errors covered, so that smoothing does not weaken existing data delivery and resource ownership.
22. As a developer, I want measured CPU and latency comparisons against the current player, so that low-cost and low-latency claims are backed by evidence rather than buffer configuration alone.

## Implementation Decisions

### Scope and existing boundary

- 保留 LpcmAudioPlayer 對呼叫端的 start、submit、stop 契約。緩衝、平滑與恢復留在音訊模組內，不新增 UI、設定、SessionAction 或 SessionSnapshot 欄位。
- 沿用 PcmOutput 作為主要輸出與測試邊界，只擴充區分準備與開播、中斷阻塞寫入，以及必要輸出進度觀測的最小契約；AndroidPcmOutput 與測試替身同步更新。
- 不要求修改 RTP／RTSP 協商、來源裝置、demux 的資料格式或視訊解碼排程。僅處理播放器能觀察到的供料不足、佇列丟棄與輸出 underrun；精確傳遞 TS 遺失事件另案處理。
- 遵守 ADR-0001 的自製接收鏈與原生 Windows 投放方式，以及 ADR-0004 的深層模組邊界。

### PCM units and fixed budgets

維持 48kHz、雙聲道、signed PCM16 little-endian。一個 stereo frame 含左右聲道各一個 sample，共 4 bytes；所有長度、丟棄與增益區間必須對齊完整 stereo frame。來源 PCM 不保證恰好是 10ms，水位不能以 Channel item 數推算。

| 用途 | 時間 | Stereo frames | Bytes |
| --- | --- | --- | --- |
| 單次送往輸出的最大 PCM 區塊 | 10ms | 480 | 1,920 |
| 啟動／恢復預載與追趕目標 | 150ms | 7,200 | 28,800 |
| 啟動／恢復淡入 | 50ms | 2,400 | 9,600 |
| 接點單側微淡出／淡入 | 約 1.333ms | 64 | 256 |
| 軟性追趕啟動水位 | 180ms | 8,640 | 34,560 |
| App 待播硬上限 | 250ms | 12,000 | 48,000 |
| 單次軟性追趕最大丟棄量 | 30ms | 1,440 | 5,760 |
| AudioTrack 請求容量下限 | 100ms | 4,800 | 19,200 |

### Startup and rebuffering

- start 開始本次預載；1,500ms 期限從本次 start 請求起，以 monotonic time 計算，不以第一份 PCM 或牆鐘時間起算。
- 待播 PCM 達 150ms，或期限已到且至少有一個完整 stereo frame，即可開播。完全沒有 PCM 時維持靜音與事件等待；期限已過後收到 PCM，不再額外等待另一輪 1,500ms。
- 1,500ms 是解除預載等待的邏輯期限，不是在 CPU 無法排程時仍保證實際出聲的硬期限。
- AudioTrack 的準備與 play 分開。150ms 預載主要保留於 App；若在 play 前預寫，總量最多一個 10ms 區塊，避免把 150ms 全部以 blocking write 塞進尚未播放的 100ms 輸出而卡住。
- 裝置容量與 streaming start threshold 分開設定。AndroidPcmOutput 準備時使用 setStartThresholdInFrames 請求 1-frame 開播門檻，並核對實際回傳值；由 App 負責 150ms 預載政策，不保留 AudioTrack 預設的容量門檻，否則少於 100ms 的短音訊即使超時仍可能不出聲。無法建立此門檻時依音訊準備失敗契約處理，不將 play 呼叫成功當成實際開播證據。
- 首次開播與飢餓後重新預載開播，均套用跨區塊連續的 50ms 淡入；資料不足時保留增益進度，不對每個區塊重新起算。
- 不能將一次短暫的 App 佇列為空直接當成實際 underrun。須結合已提交資料與實際輸出進度，允許尚在輸出緩衝內的音訊繼續播放。
- 供料不足以維持連續輸出時，在尚可處理的尾端淡出，進入重新預載；其 1,500ms 期限從本次供料中斷被播放器確認時起算，其他門檻與無 PCM 行為同啟動。

### Audible discontinuities and fades

- 保留最多 64 個尚未交給 AudioTrack 的 stereo frames 作為可控制尾端。丟棄事件必須作用於實際保留資料的接點，而非修改之後根本不會播放的被丟棄資料。
- 丟棄舊 PCM 的接續處，舊尾端以 64-frame 整數線性增益淡出至零，新開頭以 64-frame 增益從零淡入。舊尾端少於 64 frames 時以可用長度淡出；只有一個 frame 時將其設為零。新開頭的淡入跨輸入區塊延續，不因單個區塊較短而縮短或重置；僅收到一個 frame 時仍從零開始。
- 左右聲道使用同一個 frame gain；運算須正確處理負值與 PCM16 邊界，避免中間值溢位。起訖增益必須真正到達零／完整增益，不留下公式端點誤差。
- 淡化以 PCM frame 序列推進，不以 write 呼叫次數推進；短寫續傳沿用已處理的樣本，不重複乘上增益或跳過尚未交付的淡化區間。
- 相鄰且尚未送出的丟棄事件可以合併，避免對同一個接點反覆處理。50ms 恢復淡入與 64-frame 接點淡入重疊時，採較保守的單一增益，不重複相乘或重新起算。
- 已提交的波形不能事後淡出。若 CPU 長時間完全得不到排程，或輸出已 underrun，僅保證在恢復時淡入；不得宣稱能追回或完全消除已發生的破音。
- 正常播放路徑不做全量波形掃描、浮點轉換、濾波、重採樣或時間伸縮。允許必要的水位計數、區塊切分與有界尾端保留成本；不稱為零 CPU。

### Bounded buffering and catch-up

- 250ms 上限涵蓋 App 等待播放的 PCM 與保留尾端，不包含一個最多 10ms 的寫入中區塊，也不包含 AudioTrack 已接受的資料。非完整 stereo frame 不得進入播放；異常輸入須記錄並安全丟棄。
- 輸入超過硬上限時，立即丟棄最舊且尚未由 worker 持有作為接點尾端的完整 frames、保留較新 PCM，並記錄接點中斷。受保護尾端仍計入 250ms 預算，不是額外額度；producer 不得為騰出空間而丟掉唯一可淡出的尾端。submit 不等待播放器或裝置，也不執行波形搜尋。
- 待播量高於 180ms 時進入軟性追趕，持續至不高於 150ms；每次輸出迭代額外丟棄最多 30ms，兩次軟性追趕之間必須有實際播放進度，避免反覆排水的忙迴圈。
- 軟性追趕優先選取雙聲道低振幅接點。每次最多檢查 192 個候選 stereo frames；將左右聲道絕對振幅均不高於 256 的接點視為低振幅。搜尋不得擴張為掃描整個佇列。
- 找不到低振幅接點時，仍在上述丟棄預算內使用微淡出／淡入完成接續，不等待無限長的安靜段。硬上限淘汰不受單次軟性追趕的 30ms 限制。
- AudioTrack 請求容量至少取裝置最小值與 19,200 bytes 的較大者，並觀測實際配置。capacity 不是當下排隊時間；不能宣稱總延遲上限為 250ms。
- App 預載、App 佇列與裝置容量不可重複加總為固定延遲。實際音訊延遲與音畫偏移須量測；本規格不建立共享 PTS 時鐘，也不承諾消除 A/V 偏移。

### Worker, cancellation and failures

- 使用生命週期受控的專用音訊 worker／coroutine dispatcher；僅在其專用執行緒設定公開的 Process.THREAD_PRIORITY_AUDIO。不修改共用 Dispatchers.IO worker 的優先級，也不依賴 urgent／隱藏或特權 API。
- 優先級設定失敗須記錄，安全退回一般優先級；不能因此遺漏音訊資源釋放。
- 準備、預載、輸出與重緩衝等待都不阻塞 RTP 接收或畫面交付。無輸入時使用事件等待與必要的單次期限喚醒，不做高頻輪詢或忙等。
- stop 立即拒絕本代新 PCM、取消等待與丟棄未播資料，並透過輸出邊界要求中斷阻塞寫入；不是只呼叫 Job.cancel。實際 release 由單一資源擁有者保證執行一次。
- restart 重設全部水位、期限、增益與輸出進度。前一代必須已停止輸出，下一代才可開播；不得混入舊 PCM。不得以 coroutine 已取消代替輸出已停止的證據。
- 保留短寫續傳、錯誤碼與例外處理；失敗時終止本代音訊、記錄原因並釋放資源，不讓例外穿透 RTP 接收迴圈。準備中途失敗與取消也適用。
- 明確停止／斷線優先於淡出與 drain，不增加等待淡出的停止延遲，也不保證強制停止本身無任何接點聲。
- 診斷沿用 Android Log，事件可合併／限頻；不逐樣本、逐幀寫日誌，不新增 UI 或持久遙測。

## Testing Decisions

### Behavioral seam and automated evidence

- 主要從 LpcmAudioPlayer 的 start／submit／stop 餵入 PCM，觀察 PcmOutput 收到的順序、frame 數、樣本振幅、開播時機與停止／釋放行為；不斷言私有狀態名稱或 Channel 實作。
- 延續 LpcmAudioPlayerTest 的 RecordingOutput、JUnit 與 Truth。擴充同一輸出替身以控制短寫、阻塞、輸出進度與錯誤；僅補最小的內部可控時間／排程注入，保留呼叫端 API。測試不用真實 1.5 秒 sleep 證明期限。
- 維持既有依賴；本功能不引入新的音訊 DSP、排程或 mocking 套件。

| 情境 | 外部可觀察的通過條件 |
| --- | --- |
| 預載不足、達標與超時 | 未達門檻不開播；累積 7,200 frames 或邏輯期限到且有 PCM 後可開播；完全無 PCM 不產生音訊；期限後的新 PCM 不重新等待。 |
| 短音訊與裝置開播門檻 | 小於裝置容量的非零短音訊在解除預載後可推進實際播放頭，不須再等待填滿裝置容量；測試替身也模擬此門檻，不能只觀察 play 是否被呼叫。 |
| 非 10ms 的輸入 | 不同 PES 大小、零長度及部分區塊不破壞 frame 計量；任何寫入都維持 stereo frame 對齊。 |
| 啟動／恢復淡入 | 正負值與左右不同訊號的增益跨區塊連續；2,400 frames 內從零到完整增益，之後非事件區段逐樣本不變。 |
| 丟棄接點 | 實際寫出的舊尾端與新開頭包含微淡出／淡入；被丟棄但未播放的陣列不是驗證依據；小於 64 frames、連續丟棄與短寫仍正確。 |
| 裝置尚有待播資料 | 暫時 App 佇列為空不誤判成必須重新預載；輸出進度跨計數回繞或重置時不產生負水位。 |
| 實際供料中斷 | 在仍可控制尾端時淡出；裝置已 underrun 時恢復淡入，不假裝修補已播放資料；反覆中斷不造成忙等。 |
| 突發輸入與硬上限 | 阻塞輸出後大量 submit 仍可返回；保留資料符合 12,000-frame 待播預算加最多一個寫入中區塊，最新合法 PCM 最終可交付。 |
| 追趕與無安靜段 | 高水位後能回到目標範圍；軟性丟棄量有界、持續音訊不因找不到低振幅點而停滯、不切斷半個 stereo frame。 |
| 取消與重連 | 等待資料、預載、短寫及阻塞寫入期間 stop 均能喚醒／中斷；資源只釋放一次；新一代不播放舊 PCM。 |
| 輸出失敗 | 準備、開播、寫入及優先級設定失敗都有明確處理；釋放與錯誤隔離不因新增流程退步。 |

更新舊 120ms backlog 與原始 marker bytes 的斷言，使其驗證新的 frame 預算與淡化後的行為，而非假設保護接點的 bytes 不變。既有 WfdProtocolTest 的 LPCM 轉換與遺失 PES 測試仍須通過，證明未擴大協定行為。自動化驗收至少包括完整 JVM 測試與 debug APK 建置。

### Device and performance evidence

- 延續 LpcmAudioPlayerDeviceTest，但不以 activePlaybackConfigurations 數量增加當作平滑、延遲或 CPU 通過的證據。另驗證真實 AudioTrack 的準備／開播、實際容量與 start threshold、短音訊的播放頭進度、專用 worker 與停止後資源釋放。
- 效能負責範圍：實作者提供 baseline／候選版本的同機成對比較；維持相同來源裝置、串流設定、音量、輸入素材與負載程序。記錄裝置、版本及測量方法，控制溫度差異，避免以不同壓力程度製造比較結果。
- CPU 驗收預算是本規格採用的工程門檻，尚非測量結果：正常播放及反覆供料中斷兩種情境，各做三次至少 60 秒成對量測，以 process CPU time 除以 wall time、正規化為單核心百分比。候選版本相對 baseline 的額外 CPU 中位數各不得超過 1 個百分點；並檢查音訊 worker 的 trace，排除忙等與無限事件迴圈。量測雜訊無法區分門檻時，此項仍未驗證，不記為通過。
- UX 證據：至少三次剛連線，以及可重現的供料延遲／丟棄場景，使用相同語音與非零振幅測試訊號比較。保留可聽錄音或 loopback 波形與聆聽紀錄，確認受控接點由尖銳跳變改為淡化／短暫靜音；只看日誌或播放狀態不足以通過。
- 延遲證據：以相同音畫標記素材記錄啟動等待、實際音訊延遲與 A/V 偏移；持續串流至少五分鐘確認待播量不持續成長。裝置最小容量若超出 100ms，須納入報告，不以設定值假稱實際延遲。
- 無法取得實機、CPU 或可聽證據時，可以完成實作與自動化測試，但這些 UX／效能驗收保持未驗證，不宣告整體功能已驗收完成。

## Out of Scope

- 保證 CPU 完全失去排程、來源已破音或封包已遺失時仍可還原音訊。
- WSOLA、時間伸縮、變速播放、持續 DSP 濾波、採樣率轉換或新增音訊依賴。
- 改變 48kHz stereo PCM16 格式、多聲道支援或音訊協商。
- 建立共享 A/V 時鐘、傳遞／重建 PTS，或透過延後／丟棄畫面維持口型同步。
- 精確將每個 RTP／TS 遺失事件通知播放器，或重寫 demux 與 RTP 接收執行緒。
- 新增使用者設定、控制項、持久狀態、診斷面板或來源裝置軟體。
- 對斷線提供額外的可聽淡出等待，或承諾固定毫秒內完成平台 native write 的中斷／釋放。

## Further Notes

- 平台依據：[AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack) 的 getStartThresholdInFrames／setStartThresholdInFrames 說明，MODE_STREAM 預設開播門檻是容量，並非呼叫 play 就立即消耗任何長度的已寫入資料。這兩個 API 自 API 31 提供，符合專案目前 minSdk 34。
- 本規格取代對話中「零 CPU」、「修改被丟棄的尾端即可防爆音」、「共用 IO coroutine 直接設定 urgent priority」及「250ms 是總延遲上限」等不成立的描述。
- 原先批准的方向維持：150ms 自適應預載、1,500ms 兜底解除等待、50ms 啟動淡入、事件觸發微淡化、250ms 待播上限、至少 100ms 裝置容量，以及不使用重採樣的有界追趕。
- 待播硬上限、軟性追趕與裝置緩衝是固定的內部保護預算，不是完整的網路 jitter／A/V 自適應控制器。
- 所有預載、增益、期限與輸出進度都屬於本次活動連線，結束後歸零。這些規則不新增活動連線階段。
- 規格完成不等於音訊改善已被驗證；發布至 issue tracker 時使用 ready-for-agent，表示可開始實作，不表示裝置驗收已通過。
