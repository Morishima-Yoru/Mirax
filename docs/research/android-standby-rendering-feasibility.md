# Android 大待機渲染可行性研究

狀態：研究；原型交集已更新至 v13，Android 未實作、效能／OLED 未實測，不取代 ADR。觀察日期：2026-10-29。

## 決策摘要

**首選：沿用既有 Views，在獨立裝飾 View 使用硬體 Canvas；資訊保留原生文字 View，操作區不參與裝飾變形。** 粒子位置先用 CPU 計算、Canvas 交由 HWUI 繪製；View 本來已有 RenderNode，不先建立另一套引擎或自行管理 RenderNode。[S1][S3]

- C 的約 1,800 粒子、X 輪廓、細線、A 分層風景、D 已選定的平面幾何，都不構成先寫 OpenGL ES／Vulkan／WebGL 的理由。是否達標仍須量測。
- 既有 C ERROR 的模糊、紅／青分色、偏移、screen 混合，可先評估平台 `RenderEffect` 組合，不必為此先寫 shader。複雜背景或特殊形變經量測確有必要，才局部加入 `RuntimeShader`／AGSL。[S5][S6]
- **備選：平台 WebView 封裝收斂後的視覺原型**。移植量小，但新增第二套 UI／生命週期與安全邊界；不是現有 Android 已採用的框架，也不保證 Canvas2D 每一步都走 GPU。
- Compose 並未安裝；不為待機背景引進 Compose、不因 `graphicsLayer` 名稱就視為更快。OLED 防護是獨立的顯示政策，兩個方案都不能只靠像素漫移宣稱不會烙印。

## 觀察範圍與證據

本次只新增／更新此研究檔。Android、HTML、JS、ADR、原型 TODO 均唯讀；沒有建置、裝置測試、瀏覽器自動化、server 操作、依賴變更或評價 JSON 寫入。

初讀時窗約 **08:39 至 08:58（UTC+08:00）**；**09:06 依使用者確認的 v13 重新查核 HTML、感測器 JS、Parallax、ADR 0006 與 `design/standby-prototype-todo.md`，以下交集以 v13 為準**。HTML SHA-256 前綴由初讀 `ad86bfaa6961`、08:58 的 `eae324a438bc`，變為 09:06 的 `a4d3035b255b`。主代理同時調整原型，故不是原子快照，不聲稱稍後工作樹仍相同；舊固定校準中心、隨機 HUD 與 C 預設關閉不再是最新需求。原型已落地不等同 Android 已實作或本研究已完成實機驗收。

| 現況 | 證據 |
| --- | --- |
| Android 14 起；現有平台能力足夠 | `app/build.gradle.kts`：`minSdk = 34`、`targetSdk = 34`、`compileSdk = 36`、JVM 17；`viewBinding = true`，未啟用 Compose |
| 版本不在 catalog | `gradle/libs.versions.toml` 不存在；`gradle/` 僅有 `wrapper/`。依賴直接寫在 app Gradle |
| 已宣告 UI 依賴 | core-ktx 1.15.0、appcompat 1.7.0、Material 1.12.0、constraintlayout 2.2.0、activity-ktx 1.9.3 |
| 已宣告其他依賴 | Shizuku api/provider 13.1.5、DataStore preferences／rxjava3 1.1.1、RxJava 3.1.6、Gson 2.10.1；測試已有 JUnit 4.13.2／Truth 1.4.4。無 Compose、AndroidX WebKit 或渲染／粒子套件的直接宣告，未執行 resolved dependency graph |
| 建置工具 | 根 `build.gradle.kts`：AGP 8.13.2、Kotlin 2.3.21；`settings.gradle.kts` 僅納入 `:app` |
| 原生首頁是 Views | `MainActivity.onCreate()` 使用 `ActivityMainBinding`；`render()`、`updateDashboard()` 讀 `SessionSnapshot`。`activity_main.xml` 是 FrameLayout／LinearLayout；目前不是大待機原型的 Android 實作 |
| 已有 Canvas 用法 | `wfd/DebugOverlayView.onDraw(Canvas)` 繪製文字與圖表。可沿用平台繪製慣例，不表示應把除錯 overlay 當待機引擎 |
| 投放有獨立顯示與輸入路徑 | `PictureActivity` 的 `SurfaceView`／觸控轉送；`H264SurfaceDecoder` 將 MediaCodec 配置到輸出 Surface。`PictureActivity` 已設 `FLAG_KEEP_SCREEN_ON` |
| 感測器／WebView 尚無原生接線 | 定點搜尋 `app/src/main` 未見 `SensorManager`、`SensorEventListener`、Compose、WebView、RuntimeShader、RenderNode 或 RenderEffect 使用；未見強制 software layer／停用硬體加速設定。這是源碼查核，不是實機 GPU 證據 |

另讀 `docs/CONTEXT.md`、`docs/adr/0006-c-standby-gyroscope-parallax.md`、指定 HTML／三個 C JS，以及 `ic_mirax_mark.xml`；補讀感測器 JS 的輸入處理。未展開全 repo 或全部 D studies。

## 已實現交集與尚未完成的要求

| 功能 | 觀察到的交集、Android 邊界 |
| --- | --- |
| C 粒子 X 成形 | HTML `getContext('2d')`／`drawParticles()` 逐粒繪製約 1,800 圓點；`MiraxCParticleAssembly` 使用真實四臂輪廓與中央菱形周邊，約 1/5 保留自由粒子。開啟全程 10 秒、解除 8 秒；中途反向按剩餘比例延續，不能藉減少時長換取「反應快」 |
| C 新時序 | **仍為 TODO，未實作：只減少 X 開始反應的延遲，成形過程與散開不得加快。** 已暫存 `design/standby-prototype-todo.md`；既有 anticipation 位移不等同新要求已完成。未來分開量測「第一個可見反應」與「完成成形」，保留既有 10／8 秒進度與反轉連續性，不靠壓縮時長假裝起始更快 |
| 共同小幅透視 | `MiraxCParallax` 前後景共用姿態：各軸視覺傾斜最多 ±4°，透視距離為可見長邊 2 倍；背景最多 1.06 倍安全留邊，前景不放大。背景平移為短邊 1.8%、前景 1.44%；ADR 要求背景單軸投影位移 <8%、前景透視位移 <10%。這些是原型預算，不是所有螢幕已驗證 |
| 移動方向，不鎖初始中心 | **v13 已落地：** `MiraxCGyroscope` 以相鄰 `R_previous^T × R_current` 與實際取樣 `dt` 求方向角速度，`30°/s` 對應正規化輸入 1，限幅至 `[-1, 1]`；不保存啟動中心。首筆只初始化；非正間隔忽略，間隔 >250ms 接續樣本並送零。150ms 無有效新資料送零 target，由 `MiraxCParallax` 每秒係數 8 的指數平滑收斂，不瞬間跳回中心；依螢幕方向映射。原生沿用此方向／速率語意，不回退固定校準中心。這是旋轉方向感，不是可靠的實際平移／絕對位移估計 |
| HUD 小橢圓 | **v13 已落地：** 現有 `animateStandby` rAF 呼叫 `updateCHudOrbit(time)`，以精確 cos／sin 橢圓移動，水平半徑最多 6 CSS px、垂直最多 3 CSS px、5.5 秒一圈；窄視口依安全空間縮幅。錨點／引線／對話框一起移動，不另開 rAF／CSS／WAAPI 晃動，不再採六隨機目標。訊息內容／出現位置的刷新與軌道分開，OLED 極慢隨機漫移也獨立；原生使用同一幀時鐘即可，不需物理引擎 |
| C 動態預設與感測授權 | **v13 已落地：** `cPreviewOverride: true`，依使用者要求預設啟用 C 視差／小橢圓，即使系統原始 `motion.reduced` 為 true；畫面明示原型允許，可取消或暫停，不改系統設定。`effectiveReduced` 才是套用 C 選擇後的閘門，不能拿原始 reduced 值擅自恢復預設關閉。感測器仍須明確點擊／授權，不因視覺預設開啟而自動讀取；其他主題及 ERROR 震動不套用此放寬 |
| A 離線夜晚／廣播白天 | **仍為 TODO，未實作。** `design/standby-prototype-todo.md` 明訂跟隨既有廣播開關：關閉為夜晚、啟用為白天，不用牆鐘時間或網路連線猜測。現有分層 SVG 地景、霧與慢速平移不等於完整日夜切換。可沿用同一組 Path／gradient 轉換色值或交叉淡化，不用即時光照引擎；過渡外觀與時長仍待實作／驗收 |
| D 幾何 | 已有多邊形、線框、導軌與 Canvas2D studies；基本幾何可用 Path／Canvas。大量 studies 是評選候選，不代表全部列入 Android 產品範圍，尚未選定的複雜效果不預先承諾逐像素對等 |
| 產品分界 | 評分、筆記、inspector、主題比較器、模擬連線控制、HTTPS 評價 API 都是研究工具，不進 APK。真正狀態取 `SessionSnapshot`，不移植假日誌／效能數字作真實狀態 |
| 不干擾核心用途 | 投放時停止並移除待機裝飾，不變形 SurfaceView／投放畫面、不改 Windows 一般觸控座標；簡單首頁不承受粒子／感測器成本。自訂背景顯示時停用生成背景、C 視差與 HUD，不覆蓋使用者圖片／影片；仍顯示的資訊另受 OLED 政策管理 |

## 真正有差異的兩個方案

| 項目 | A. 既有 Views ＋硬體 Canvas，首選 | B. 平台 WebView 封裝視覺原型，備選 |
| --- | --- | --- |
| 新依賴 | 無；API 34 已涵蓋下列平台能力 | `android.webkit.WebView` 為平台設施，不需 Gradle 新依賴；但 app 現在沒有 WebView 整合 |
| CPU／GPU 分工 | CPU 更新粒子、輪廓取樣、佈局／字形準備與 display list；硬體 Canvas 經 HWUI／RenderThread 提交 GPU 繪製。View 屬性變換可重用 display list；粒子每幀變動仍須重記其繪製指令 | JS 粒子數學與 DOM／Canvas 指令仍在 CPU；Chromium 可做加速 raster／合成，但 Canvas2D、filter、驅動及 WebView 版本會影響實際路徑。CSS transform 不等於整頁無 CPU 工作 |
| 功能適配 | 目前 2D 交集可覆蓋；原生字體、無障礙、狀態與生命週期一致。SVG／CSS 需翻成 Path／層屬性，不能零成本逐像素複製 | 較易保持 SVG／CSS 外觀與時序；必須裁掉評價工具、server／外部資源依賴，與原生狀態重新接線 |
| 感測／控制 | 原生 SensorManager；只有裝飾資訊共用視差，操作保持原位 | 宜由原生提供最少數值姿態，不依賴嵌入 WebView 的網頁方向權限行為；原生導航／控制留在 WebView 外。任何 JS 通訊都要定義可信邊界 |
| OLED／失敗回退 | 獨立慢速位移與待機政策；無硬體 Canvas 時降為簡單、低 APL 靜態背景，保留原生資訊與操作 | 同樣須獨立保護。JS 出錯、頁面未就緒或背景計時器被節流時，不能依賴網頁自我保護；原生宿主管待機時間。失敗顯示原生簡單首頁／低亮度背景 |
| 主要代價 | 轉譯已選效果；粒子 CPU／GPU 負載必須量測，不能靠 API 名稱保證流暢 | WebView 啟動／記憶體、renderer 生命周期、更新與 GPU 差異；語意樹／觸控協調更複雜。現有 `getContext('2d')` 不會自動變成 WebGL |

**不把 Compose 或 AGSL 列成第三個完整引擎：** `DrawScope` 仍是繪製指令介面，`graphicsLayer` 是隔離／變換／合成工具，不提供 GPU 粒子模擬或任意 mesh 形變。改用 Compose 需新增套件與建置設定，沒有證據能比既有 Views 省完整工作。AGSL 則是 A 的局部可選能力，不需要因此切換 UI 框架。[S6][S7]

WebView 若後來勝出，官方建議使用安全的本機內容載入方式。單一可信、內嵌資源頁可用 `loadDataWithBaseURL()` 的 HTTP(S) base URL；多檔資源官方推薦 `WebViewAssetLoader`，但其 AndroidX WebKit 依賴目前不在專案，須另評估，不能假稱已安裝。不得為方便打開通用 file 存取、外部導航、評價寫入端點或任意 `addJavascriptInterface`。WebGL 必須另寫 renderer、偵測 context 可用與處理 context loss，現在不選。[S8][S9]

## API、硬體界線與 fallback

以下 API 門檻是平台版本，不是效能承諾。現有 minSdk 34 已高於所有所需門檻；若將來降低 minSdk 才需要額外版本分支。

| 能力／最低 API | 實際界線、fallback |
| --- | --- |
| View 硬體 Canvas：API 11；target ≥14 預設啟用 | 目前 manifest 未覆寫停用，僅代表配置預期。繪製時查 **`Canvas.isHardwareAccelerated()`**，不能只看 View 屬性；對 Bitmap 的 Canvas 可為軟體。一般圓、線、Path、文字可進 HWUI，不代表產生幾何／shape／排版也在 GPU。[S1][S2] |
| `Canvas.drawVertices()`：方法 API 1，硬體支援 API 29 | 可處理有限的三角形幾何；不是自動批次粒子引擎。一般 Path／circle 已足夠，先不用 mesh；複雜／大量每幀 Path 更新仍有 CPU／上傳成本。硬體表列 Path 高倍率縮放的完整支援自 API 28；本案 34 足夠。[S1][S2] |
| 字形／路徑：`Canvas.drawText()`／`drawPath()` 方法 API 1 | 原生文字 View 提供 shaping、fallback 字體、字級與語意；GPU 可繪製字形，CPU shaping 不會因此消失。不要每幀把文字轉 Path 或整頁重建 bitmap。大層 raster 後放大會像 bitmap，前景不放大。[S1][S2][S7] |
| View `rotationX/Y`、`translationX/Y`：API 11；`setCameraDistance()`：API 12 | 平面層能做小幅透視／位移，不能把單一平面變成真 3D 粒子體積；foreground／background 可同姿態不同深度。變換後需自行保留安全邊界，不能拿佈局矩形當最後投影界線。操作區不放進此層。[S4] |
| `RenderNode`／`Canvas.drawRenderNode()`：API 29 | 記錄 display list、重用屬性變換；**RenderNode 不支援軟體 Canvas**。View 本已使用，不先手動管理；需要顯式節點時才加。軟體退回直接畫基本 Path／資訊，不把 `drawRenderNode()` 丟給軟體 Canvas。[S2][S3] |
| `RenderEffect`、`View.setRenderEffect()`：API 31 | blur／offset／color filter／blend 可組合；作用於節點內容，不是自動抓取背後畫面或獨立 SurfaceView 的 backdrop blur。模糊有中間渲染、擴張邊界與 fill-rate 成本；局限在已選的裝飾層。`Paint.setMaskFilter()` 在官方硬體支援表仍不支援，不能當硬體 blur 捷徑。非硬體路徑略去模糊／色散，不整頁強制 software。[S1][S5] |
| `RuntimeShader`／`RenderEffect.createRuntimeShaderEffect()`：API 33 | AGSL 在 Canvas／RenderNode 管線計算像素顏色／取樣，可做局部動態背景、色散／視覺扭曲；不是 GLSL 的整條管線，不提供頂點／compute 粒子更新。把 1,800 點迴圈搬進全螢幕逐像素 shader 可能更昂貴。一般硬體繪製可組合 GPU fragment shader；不推論所有 Canvas 都是 GPU 或軟體 AGSL 一定相容。編譯失敗須記錄並退回基本圖形／solid fill。[S6] |
| Compose `Canvas`／`DrawScope`、`graphicsLayer`：AndroidX API，不是獨立的 Android API level | 須先選 Compose 版本與其 minSdk，本案未選亦未安裝。`graphicsLayer` 可隔離指令、改變旋轉／位移而不重執行內容繪製；raster／offscreen 後內容如 bitmap，alpha、RenderEffect、blend 可能增加離屏合成。它不會變更 measured size，也不自動解決出界。`Modifier.blur()` 僅 Android 12／API 31 起生效，較舊版本忽略，不自帶軟體 blur fallback。[S7] |
| 平台 WebView：API 1；`evaluateJavascript()`：API 19 | WebGL／Canvas2D 加速與 filter 支援不能只由 OS minAPI 推定，需查實際 WebView 版本、GPU 路徑及 runtime feature。WebGL context 可能不可用／失去；回退 Canvas2D 或原生靜態頁，不以安全設定放寬換相容性。[S8][S9] |
| `TYPE_ROTATION_VECTOR`：API 9；`TYPE_GAME_ROTATION_VECTOR`：API 18 | 優先評估不受地磁干擾的 game rotation vector，缺少時用 rotation vector；兩者皆不可假設每台有。game reference 仍可漂移。相鄰旋轉矩陣／quaternion 配合實際 dt 求角速度，不直接相減 Euler 角；沿用 v13 的 30°/s 正規化與 idle 零 target，由 Parallax 平滑收斂。依螢幕轉向映射，旋轉／視窗變更清歷史。感測器未明確啟用、資料無效、缺感測器、暫停或有效減少動態時回退中性；離開可見待機即 unregister，不為裝飾要求高頻權限／背景感測。[S10] |
| `Choreographer`／`postInvalidateOnAnimation()`：API 16；`ValueAnimator.areAnimatorsEnabled()`：API 26 | 用平台幀時鐘與 monotonic elapsed time，不靠幀數決定 10 秒成形；HUD 與 Parallax 共用幀時鐘。保留 C v13 預設允許小幅位移、可取消／暫停的明確選擇，區分系統原始 reduced／動畫狀態與有效動態閘門，不能把查到系統狀態直接當作 C 預設關閉。極慢 OLED 位移不掛在裝飾 animator 開關，見下節。[S4][S11] |
| `FLAG_KEEP_SCREEN_ON`：API 1；`screenBrightness`：API 3；`BRIGHTNESS_OVERRIDE_NONE`：API 8 | 前景可見 window 才保持亮屏；不是背景喚醒授權。亮度預設沿用系統，若另經用戶同意暫時調暗，只限目前 window 並恢復 `-1`；不寫全域亮度。[S12] |

## 原生最小整合邊界，僅供後續批准

1. `MainActivity` 的 dashboard／`render()`／`updateDashboard()` 將既有 `SessionSnapshot.advertisingEnabled`、`showPicture` 等狀態送給待機視覺；不複製 session reducer，不讓裝飾切換廣播狀態。A 按已確認的既有廣播開關決定夜晚／白天，不另造網路或時鐘狀態機。
2. 首頁視覺節點承擔粒子／Path；字形與資訊沿用原生文字元件；導航／設定／觸控操作留在獨立固定層。避免在動畫中重建整個 dashboard、重排字或逐幀配置新 Path／Paint／粒子物件。
3. 共同視差、HUD 小軌跡、OLED 慢漂保留各自狀態與 gating，再明確合成變換，不互相覆寫、不讓 HUD 自己脫離引線。只對背景做 overscan；不足空間先縮幅，不能裁字、擋操作或縮小觸控目標。
4. 生命週期接線包含可見性、焦點、暫停、減少動態、尺寸／方向改變、簡單首頁、自訂背景與進入投放。裝飾失敗記錄後保留可用首頁；不修改 `PictureActivity` 的投放幾何、UIBC 觸控或 decoder Surface。
5. 若批准實作，需新增少量既有 JUnit 數學／時序測試與必要畫面 fixtures：反轉連續性、10／8 秒時長、首幀反應、四方向、無 NaN、組合姿態安全區、各停用條件及 OLED 獨立性。無需先新增 framework、可選配置、shader 管理器或 benchmark 依賴。

## OLED：兩個方案都必須採用的政策

**降低累積磨耗風險，不保證消除烙印。** 軟體位移不能逆轉面板老化；整體亮度、停留時間、溫度、面板差異及 OEM 政策皆影響結果。[S13][S14]

1. **先列靜態熱點。** 品牌與 X、`STANDBY` 固定字形／邊線、時鐘冒號與單位、連線提示、綠／紅狀態燈、D 固定導軌、導航圖示、Android 狀態／導航列、自訂靜態圖片都會長期佔用相同像素。時鐘換數字、狀態燈閃爍、粒子背景在動，均不代表其他元素受保護；大字／寬線的內部更容易仍然重疊。
2. **獨立極慢隨機漫移。** C 現有 `MiraxCOledDrift` 的每軸目標 ±3 CSS px，段長至少 150 秒且隨距離加長，使用平滑曲線；`updateCOledDrift()` 約每 100ms 更新，沒有依賴 paused／reduced 開關。這只證明程式結構獨立，不是抗烙印測試。原生位移要按實體像素、密度與安全區校準，不能直接把 3 CSS px 當 3dp。兩軸不固定短週期、不在每次狀態切換歸中，避免反覆停回相同像素。
3. **漫移的根本限制。** 幾個像素只能分攤文字邊緣／細線磨耗；粗字、寬色塊和大面積背景的內部仍照亮相同像素。加大幅度又會傷害可讀性、出界和焦點穩定性。這不是像素刷新／面板補償，也不能讀取 OEM 保護設定後推論已安全。
4. **暫停裝飾不可一併停掉保護。** 產品需分開「裝飾允許」與「可見待機的保護策略」。C 原型已有部分分離，但 A／B／D 的舊共用動畫時鐘會隨暫停停止，不能直接當通用實作。C v13 依使用者要求預設允許小幅位移；使用者取消允許而使有效減少動態成立、或暫停時，停止並歸零共同視差／HUD 軌道，不能僅憑系統原始 reduced 值擅自關閉。極慢 OLED 漫移仍獨立，可在裝飾停止時持續。若使用者或輔助工具仍不適合慢漂，改用低 APL、縮短亮屏時間、淡出非必要靜態資訊與系統休眠，不能承諾關閉慢漂後風險相同。App 背景／螢幕關閉時不靠 timer 維持運作。
5. **黑底與低亮灰需真機選擇。** OLED 真黑 `#000000` 可減少黑區發光；目前 C 的 `#080808` 為低亮灰，不是關像素，也不是防烙印機制。先以低 APL、減少大面積高亮與過亮飽和狀態燈為方向；灰階細節、黑階拖影／閃爍、環境可讀性須在目標面板確認。不能只降低 alpha 卻讓必要文字不符合對比要求。自訂背景的 APL 不受生成背景控制，須誠實告知靜態高亮圖片的限制，不能偷偷覆蓋使用者內容。
6. **導航／字體／觸控／無障礙。** 優先保護仍需顯示的靜態文字，避免長亮粗框與固定高亮燈。非必要控制可依既有互動規則閒置淡出，但焦點、TalkBack、鍵盤／遙控器操作與明確返回入口不可消失。不要只平移控制的繪製而留下原位命中區／語意 bounds；裝飾不攔截觸控，不以小字／縮小目標換保護。系統列不屬 app Canvas，不能假稱一起 pixel-shift；是否隱藏需獨立產品決策並保留系統手勢，不能為 OLED 擅改首頁導航行為。
7. **亮度與時間優先於無限亮屏。** 保留使用者／系統自動亮度，不要求全域亮度寫入權限，不強拉最高亮度。若要閒置調暗，必須先取得明確產品／使用者允許，只作用於目前 Activity、可恢復，並提供前景待機的結束／系統休眠政策。不得加背景 wake lock、服務保活或自動喚醒來維持裝飾；現有投放 Activity 的亮屏需求不等於待機也可永久亮屏。[S12][S14]
8. **TV／OEM 不能代替 app 責任。** Android TV／Google TV Ambient Mode 本來就為避免長時間靜態畫面而設；官方描述閒置約 10 分鐘進入，再依 Energy Saver 關屏，不能把此時長當每個 OEM／手機的保證。待機不是 active video playback，不應因有粒子動畫就永久阻止 Ambient Mode；app 不能阻止 TV Energy Saver。OEM 像素位移／面板補償可能存在，沒有本研究可依賴的通用偵測／保證。即使 OEM 開啟，app 仍須降低靜態曝光並讓出休眠。[S13]
9. **只有實體 OLED 驗證能支持有限承諾。** 在明確同意、受控且不刻意加速損傷的條件下測試目標 OLED，記錄面板／亮度／溫度／曝光時間／OEM 模式，分辨暫時殘影與永久磨耗。截圖、模擬器、單次無殘影、數學測試均不能證明長期安全；不得承諾「pixel shift 完全避免烙印」。

## 效能與相容驗證計畫，全部未執行

1. **先建立真實基線。** 目標 API 34+ 手機／Fold 不同長寬比，另列 Android TV 或無感測器裝置。記錄 SoC、刷新率、解析度、面板、WebView 版本；release-like 建置，不以桌面原型 FPS 代替 Android 結論。
2. **辨認 GPU 路徑。** 記錄實際 Canvas 是否硬體；用 System Trace／Perfetto FrameTimeline 分開 UI thread、RenderThread、GPU／合成與記憶體。Profile GPU Rendering 是定位線索，不是 GPU 全部時間或烙印證據。WebView 額外看 JS／raster／compositor，不把 CSS layer 數當流暢證明。[S15]
3. **逐項加效果，不先重寫。** 原生資訊基線 → 1,800 粒子 → 成形／解除 → 前後景透視／HUD → OLED → ERROR blur／分色；觀察 frame time 分布、長幀、每幀配置／GC、CPU／GPU、熱與待機耗電。60Hz 約 16.7ms、120Hz 約 8.3ms 是整幀預算，不是已達成數據；不為維持高刷新無限燒電。必要時降低裝飾更新頻率／取樣負載，不能偷偷縮短成形時間。
4. **驗證體感時序。** 從廣播操作事件到第一個可見反應，再到完整成形，各自量測；包含中途反向、暫停／恢復、視窗改變、靜止後再移動。感測輸入檢查不同 dt 的 30°/s 正規化、同側姿態反向、150ms 無資料送零 target 後平滑收斂，不能誤測成瞬間歸中。C 起始延遲 TODO 先以「下一可繪製幀開始回應」作待驗證目標，不宣稱固定毫秒延遲。
5. **驗證安全邊界。** 最大視差＋HUD 橢圓＋OLED 合成、不同字級／語系／窄橫屏、TalkBack／鍵盤焦點、真實 touch bounds；驗證 HUD 6×3 CSS px 半徑預算與 5.5 秒週期的橢圓方程、狹窄版面縮幅，以及前景不放大、不裁字、不與品牌／狀態重疊。投放、簡單首頁、自訂背景、切到背景後不得留下裝飾 frame loop／sensor listener。
6. **驗證保護失效路徑。** C 原始 reduced 為 true 仍預設允許小幅位移、但感測器未明確啟用前不讀取；取消允許、paused、有效減少動態、動畫比例為零、感測器缺席／無效、WebView renderer 失敗、無硬體 Canvas，均需驗證獨立低 APL／時間／休眠策略。不得因裝飾 fallback 留下無限高亮靜態頁。OLED 項另做真機長時間受控觀察，不用效能 trace 假稱防烙印完成。

## 待決事項

- 目前移植基準為 v13：方向／速率、不鎖啟動中心、150ms idle 零 target、小橢圓 HUD 與 C 視覺預設開啟均已落地，不再列為原型待實作；Android 接線與實機驗證仍未做。
- **仍開放的原型 TODO 僅此兩項：** A 關閉廣播為夜晚／啟用為白天；C X 更早開始反應但不加速成形／散開。暫存 `design/standby-prototype-todo.md`，須實作與驗證後才能勾選；環境無 `gh`，尚待發布至 `Morishima-Yoru/Mirax` GitHub Issues，不宣稱已有 issue。A 的外觀／轉換時長仍待收斂，不新增牆鐘時間／網路連線規則。
- 手機／Fold 是否為唯一首發範圍；TV 是產品支援或只作設施相容研究。感測器缺席時視差輸入保持中性，不要求使用者校準才能使用首頁，也不因此把已允許的 HUD 軌道或 OLED 慢漂一併關閉。
- 真黑或低灰、前景亮度／燈號 duty cycle、原生實體像素漫移範圍、閒置淡出／調暗／結束待機時間，以及允許的前景亮屏範圍。
- 只有外觀對等轉譯成本或實測證據證明必要，才重新評估 WebView；只有已批准效果無法由現有 Canvas／RenderEffect 滿足，才評估局部 AGSL。沒有選定效果或量測證據，不新增 renderer／依賴。

## 官方來源與查詢限制

查閱日：2026-10-29。依政策先嘗試 Context7 `resolve-library-id` 對應 Android、Jetpack Compose、Android WebView；三項皆回覆 **`Monthly quota exceeded`**。因此改用 DuckDuckGo 尋找官方文件，並直接核讀下列 Android／Chromium 頁面與 API reference。Chromium 架構文自身提醒實作會演進，只用於辨別 raster／合成分工，不當成每個現行 WebView 的支援保證。

- **[S1] 硬體 Canvas 支援表、預設與 software 邊界**：https://developer.android.com/topic/performance/views/hardware-accel-views
- **[S2] Canvas API，包括 drawVertices／drawRenderNode**：https://developer.android.com/reference/android/graphics/Canvas
- **[S3] RenderNode，API 29、display list、僅硬體**：https://developer.android.com/reference/android/graphics/RenderNode
- **[S4] View API，transform／camera／RenderEffect／invalidate**：https://developer.android.com/reference/android/view/View
- **[S5] RenderEffect，API 31；runtime shader effect API 33**：https://developer.android.com/reference/android/graphics/RenderEffect
- **[S6] RuntimeShader／AGSL 能力與管線**：https://developer.android.com/reference/android/graphics/RuntimeShader ；https://developer.android.com/develop/ui/views/graphics/agsl
- **[S7] Compose 繪圖、graphicsLayer／offscreen；blur 限制**：https://developer.android.com/develop/ui/compose/graphics/draw/modifiers ；https://developer.android.com/develop/ui/compose/graphics/images/customize
- **[S8] WebView API、安全載入本機內容**：https://developer.android.com/reference/android/webkit/WebView ；https://developer.android.com/develop/ui/views/layout/webapps/load-local-content
- **[S9] Chromium GPU raster／合成架構**：https://www.chromium.org/developers/design-documents/gpu-accelerated-compositing-in-chrome/
- **[S10] Sensor API、旋轉向量／座標映射／生命週期**：https://developer.android.com/reference/android/hardware/Sensor ；https://developer.android.com/develop/sensors-and-location/sensors/sensors_position ；https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- **[S11] 幀時鐘與動畫停用狀態**：https://developer.android.com/reference/android/view/Choreographer ；https://developer.android.com/reference/android/animation/ValueAnimator
- **[S12] 保持前景亮屏、亮度 window 屬性**：https://developer.android.com/develop/background-work/background-tasks/awake/screen-on ；https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- **[S13] Android TV Ambient Mode／Energy Saver／OLED 風險**：https://developer.android.com/training/tv/playback/ambient-mode
- **[S14] Google OLED 保護建議：亮度、休眠時間、溫度**：https://support.google.com/pixelphone/answer/9128915?hl=en
- **[S15] Slow rendering、Perfetto FrameTimeline、量測限制**：https://developer.android.com/topic/performance/issues/render

## 原型後續更新：v27

上文為 v13 研究快照。v14／v15 只提前隨機微幅預動，未解決使用者感受到的聚合遲滯。
v16 移除該預動與雙重緩入，v17 起完整聚合為 6 秒。v19 在使用者確認
「起步快、逐漸減速」後，以正規化指數 ease-out 取代 sigmoid／inverse sigmoid：
`f(t) = (1 - exp(-4t)) / (1 - exp(-4))`，其中 t 為 6 秒內的正規化時間。
由起點立即朝 X 移動，速度持續下降，不再中段放慢後於尾段加速；0 秒為 0、
6 秒精確到達 100%。只套用一次轉場，不再疊加預動或第二次緩入；散開仍為原有
緩動曲線與 8 秒總時長。
新增實際手機瀏覽器點擊／投影路徑測試，量測整體朝 X 移動，避免以單顆粒子的抖動冒充反應。
自動化檢查通過不代表實體手機觀感已驗收，因此 C 起步反應 TODO 仍保持開放。
A 廣播狀態驅動日夜仍待實作；本次沒有變更 Android 選型或進行原生實作。


v20 依使用者回報調整陀螺儀：角速度正規化由 30°/s 改為 90°/s 達最大輸入，
同樣 30°/s 現為 1/3；視差平滑係數由每秒 8 改為 4。取消前後景的透視旋轉與
動態放大，改為分層純平移，背景／前景上限仍為原視口短邊的 1.8%／1.44%。
背景以實際擴大繪圖緩衝邊界代替縮放，原視口粒子幾何與尺寸不變。
相鄰姿態方向、150ms idle、250ms 斷樣與明確授權均保留；OLED 漫移與 v19 的
6 秒指數 ease-out／8 秒散開不變。上文 v13 的 30°/s、係數 8 與透視預算僅是
歷史快照，不應作為後續 Android 目標。Android 仍只研究，不新增原生實作。


v21 將粒子與邊角資訊改為獨立速度：兩層共用方向輸入，但分別保留移動歷史，
粒子平滑係數為每秒 4、邊角資訊為每秒 2；不再把同一平滑位移乘固定深度比例。
粒子上限維持短邊 1.8%，邊角資訊改為 0.6%，使標題較慢且更穩定。兩層停止後
各自平滑收斂，暫停或失焦仍同步清空兩層。純平移、明確授權、OLED 漫移、
6／8 秒聚散均不變；Android 仍未移植，實體手機手感待驗收。


v23 將邊角資訊上限設為短邊 1%，粒子維持 1.89%，獨立平滑係數 2／4 不變。
C 首次符合動態與可見條件時預設嘗試訂閱一次；若平台要求使用者手勢授權，
不在載入時呼叫 requestPermission，仍保留明確啟用按鈕。手動停止後不因 render、
重新聚焦或返回 C 而自動重啟；重新整理恢復預設意圖。其他主題不自動啟用。
這取代先前「感測器一律手動啟用」的原型政策，不是繞過瀏覽器權限；
Android 仍未移植，實體手機觀感仍待驗收。


v24 已實作 A 的廣播狀態日夜切換，取代上文研究快照中的未實作 TODO：接收／
廣播關閉為冷色夜山與彎月，廣播開啟才顯示原日間色盤與太陽，不依牆鐘時間，
也不把「等待連接／連接失敗」誤當作廣播關閉。沿用分層 SVG、山形與霧，不新增
渲染引擎、外部素材或依賴。一般切換以 1.2 秒顏色／透明度過渡；暫停及減少動態時
直接更新，仍能辨識目前廣播狀態。桌面／手機視口、自動化狀態轉換與 HTTPS 預覽
已檢查；實體手機外觀仍待驗收。Android 原生保持研究範圍，未進行移植。


v25 把 A 的色彩過渡延長為 3.6 秒，天空各漸層節點與山色同步緩變。
太陽與月亮分成各自的平移／透明度圖層：太陽從左下升起、向左下落下，
月亮從右下升起、向右下落下，光暈跟隨各自天體，沿用前方山形作遮擋。
關閉廣播立即清空並隱藏 Windows 連線提示，開啟時才顯示；B／C／D 的提示保留。
瀏覽器驗證已實際觀察途中顏色與位移、往返中途反向，以及 320 個響應式案例。
暫停與減少動態時仍直接切換狀態，不強制播放升落；沒有新增動畫引擎或依賴，
Android 原生仍未移植，實體手機觀感仍待驗收。


v26 修正 A 轉場在使用者已回報的 reduced=true 條件下完全不動的問題：v25
的兩條 CSS 閘門把顏色與日月的 transition 一律設成 none，手機點擊後第一幀就
跳至終點。此問題已在 HTTPS 手機視口重現，不把它歸因於快取或使用者漏看。
A 新增獨立且明示的原型轉場預覽允許（預設開啟），只放寬 A 漸變與日月升落；
可取消以遵循系統減少動態，或使用暫停立即停止轉場。不改系統原始 reduced 值、
C 的允許選擇、其他主題或錯誤震動。Inspector 同時回報原始偏好、A 預覽選擇與
實際 A 轉場閘門。手機 reduced=true 回歸檢查改為實際 tap 後觀察途中顏色、
兩天體的相反方向移動與可見區位移，不再只驗證最後日／夜顏色。
模擬與網頁證據通過不代表實體手機已驗收；Android 仍未移植。


v27 修正日月只在 SVG 右側局部移動的問題：以實際可見視口與 slice 裁切比例
換算軌跡，太陽起訖點完整位於畫面左邊之外，月亮則在右邊之外；顯示時停於
視口中上方，寬螢幕另保留天空可見空間。移除舊手機固定 -190 位移，日月不再
繼承山景的平移／縮放。旋轉或改變視窗時直接對齊目前狀態，避免下一次從舊座標
進場。3.6 秒漸變、A 預覽允許與暫停選擇、廣播中才顯示連線提示均保留。
檢查以可見區實際 bounds 為準，包含進出左右邊界、途中分處左右半屏、六種
長寬比、手機 reduced=true 點擊與 320 個響應式案例；不以固定 SVG 位移值冒充
從螢幕邊緣進出。實體手機仍待驗收，Android 未移植。

## v28 presentation handoff

The browser prototype now targets A/C/D only. B is removed from the theme menu
and scene code, not from historical review records. An old B URL falls back to A.
The left-center settings handle and a rightward background swipe open a native
dialog styled as a full-height drawer. Prototype notes, comparison navigation,
simulation controls, motion-study selection, ratings, notes and the inspector
belong inside that drawer, under one expandable prototype-tools option. They
must not overlay the theme when the drawer is closed.

One broadcast switch remains at bottom center, independent of decorative motion:
A uses the landscape palette, C a restrained monochrome instrument treatment,
and D a hard-edged geometric treatment. The distinct shapes identify the active
theme without adding decorative effects. The dock is absent during simulated
projection and on the unchanged simple home. A's broadcast-driven day/night
transition and C's assembly, gyroscope, layered translation and OLED drift retain
their existing behavior.

All 16 browser verification suites pass for this shell change. Evidence includes
240 scene layouts, 120 drawer layouts, 18 clean-mode layouts, six native Chromium
touch swipes, modal/background focus separation, scrollable review tools and
mocked review persistence. Cold HTTPS loads retain all 57 scripts, and the
39 backend tests pass. The real review JSON retains its previous SHA-256.
D field studies use the visible broadcast dock as their lower boundary rather
than a comparison bar that is now inside the closed drawer.

This is a presentation handoff, not an Android implementation, a new rendering
dependency or evidence of measured GPU cost, power consumption or burn-in
prevention. Real-phone swipe behavior and the previously open C visual/sensor
acceptance still need a user.

## v29 monochrome slash proposal and control polish

Theme E adds an independent monochrome slash-cut composition alongside A/C/D.
Its references are the diagonal, hard-edged cyberpunk vocabulary described by
[ROG SLASH](https://rog.asus.com/us/content/rog-slash/), not ROG logos, assets or
RGB colors. Two fictional monospace streams move from lower-left to upper-right
on 240/360-second repeating transform tracks. The shared MIRAX symbol remains
an identifiable white outlined X, with one faint offset contour, not a new mark.
The scene uses fixed inline SVG/HTML and isolated CSS: no additional renderer,
clock, sensor subscriber, external font or package. Its text is decorative and
hidden from accessibility navigation; it must not be read as actual telemetry.

E retains the common drawer, icon-only broadcast switch and state machine.
It has a separate `concept-e` review/Note identity. Decoration pauses for reduced
motion, explicit pause, hidden documents, the simple home, projection, custom
media and other selected themes. Main controls stay outside the artwork.

The shared control revision replaces visible broadcast text with accessible
labels and simple outline/icon treatments. C uses a downward equilateral triangle
with a double lower-left rail, upward triangle when off, X when on and a red
exclamation mark during the existing temporary failure. The original failure
clock, particle assembly, gyroscope gain and OLED drift budgets do not change.
The rail log title alone gains 1pt, bold white, retaining colored badges and
50-percent dialog opacity. D moves Win + K to the receiver corner, removes lower
copy, and A/D omit center descriptions and simulation disclaimers from the scene;
the drawer retains an explicit, honest prototype notice.

The drawer uses 320ms entry and 260ms exit; view changes crossfade the inert old
view over 240ms and reveal the next view over 420ms. Rapid reversal cancels the
old drawer completion; reduced motion/pause use immediate state changes. C's
failure and projection entry immediately release the modal before moving focus.
These presentation changes do not override the separate A/C preview preferences.

D composition 209 retains the two sources 17/118, with sparse extrusion and
smooth cathode tracing. Thirty-one explicit rejects are archived; 23 active
studies remain. Reviews are not rewritten and 209 inherits no source review.
The preview listener is restarted to discover 209/E; Tailscale routes are unchanged.
Browser checks cover E's six layouts/seven lifecycle gates, four icon states and
transitions, 320 scene layouts, 160 drawer layouts and the live 209 Canvas.
Android implementation, real hardware feel, GPU/power and burn-in remain unverified.

## v30 readable content padding correction

The user rejected the v29 edge-hugging text arrangement. Main-scene information
now shares `clamp(24px, 3vmin, 48px)` padding plus the corresponding safe-area
inset. E no longer owns separate 16px edges. A's old compact header selector
also had higher specificity than the shared rule, leaving branding at 70px;
the content-container selector now applies the same inset at every breakpoint.
C still reserves its unchanged title-parallax/OLED budget. Footer widths derive
from the actual inset, not an assumed old fixed gap. Below 480px, C's information
row sits above the bottom-center broadcast action, with readable text sizes.
The handle, button placement, IP geometry, animations and review data are unchanged.

`verify-edge-padding-ui.js` reproduces the old margins and passes 112 cases after
the correction. Existing 320 scene/160 drawer layouts, 450 HUD placements and
4,320 parallax-composition cases pass. HTTPS DPR-3 phone, narrow, landscape and
desktop captures were inspected. This is browser evidence, not physical-device
or notch validation; Android implementation and hardware-performance limits remain.

## v33 D/E consolidation and size-comparison baseline

The user has merged E back into D. Only A/C/D remain selectable; legacy E URLs
resolve to D while historical E votes/Notes remain intact. D keeps its original
X geometry, receiver header and central status band. E contributes the slash-cut
broadcast outline and diagonal channels. The upper/left channel has three rows
of explicitly fictional text, moving bottom-left to upper-right; white cathode
sweeps trace the glyph outlines, not a duplicate X. The unchanged source 118 C2
hollow perspective is now the default D study. This replaces the v29 visual
selection, not the 23-study comparison catalogue or the sources 17/118/209.

Shared branding now follows A across every theme. A has a slim cloud-edge handle
and pill action with a rounded green play/red rounded-square stop. C's triangle
extra rail moves outside the lower-left side. C's first log style keeps a truly
opaque white first line and saturated non-white statuses; only its body remains
half-transparent, correcting the earlier white-under-parent-opacity treatment.
The other nine log styles are unchanged. C corner copy moves slightly inward
when it shares the action row; the narrow raised row keeps its previous insets.

Frame, icon, color, hover/press and dock visibility transitions share the raw
reduced-motion/pause/hidden gate. Native discrete-display transitions allow the
dock to fade while preserving hidden semantics; unsupported browsers fall back
to ordinary hide/show, rather than leaving a focusable invisible action.
There are no added renderers, animation loops, sensors, fonts or dependencies.

CSS `text-size-adjust: 100%` and its WebKit spelling prevent automatic text
inflation; editable review fields use at least 16 CSS px to avoid native focus
zoom. Pinch/manual and accessibility zoom are intentionally not disabled.
A CSS-pixel reference cannot guarantee identical physical millimeters across
screens or override browser/OS accessibility settings. Intentional responsive
layout changes remain separate from automatic browser scaling.

Evidence: 240 scene/120 drawer layouts, 120 cross-theme branding checks,
120 edge cases, 36 desktop/mobile/DPR sizing cases, four manual viewport scales,
three theme crossfades, a real dock fade, 450 HUD placements/eight status presets,
and 4,320 composed motion cases pass. Six native Chromium touch swipes, hybrid
scene flow/cathode masks/seven lifecycle gates and isolated mocked review
persistence pass. HTTPS DPR-2 phone/narrow/landscape/desktop captures were inspected.
This remains browser evidence, not physical phone/iOS, sensor, GPU/power or OLED
burn-in validation. Android implementation and rendering selection are unchanged.

CSS references checked for this correction:
- [MDN: text-size-adjust](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/text-size-adjust)
- [MDN: transition-behavior](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/transition-behavior)

## v34 corrected preview behavior and explicit platform limits

The user rejected four v33 completion claims. A matching mobile browser context
with `prefers-reduced-motion: reduce` reproduced paused D copy/edge effects and
zero-duration broadcast transitions. The user then explicitly requested removal
of reduced-motion functionality. v34 removes the preference query, state,
listeners, override settings and CSS/JavaScript branches from the prototype.
Explicit pause, hidden, custom-media and view gates remain; sensor permission and
manual stop are unchanged. Decoration pause does not remove short user-triggered
button/drawer/view feedback. This is a prototype preview policy, not an Android
implementation or a decision to remove accessibility from the future native app.

The old cathode mask was idle for most of a 24-second cycle and travelled over
thousands of offscreen text pixels. The new four boundary scans use 1px white
heads and fading tails across the actual visible band intersection, looping over
4.8 seconds and fading before reset. The three left text rows and one right row
retain their 240/360-second bottom-left to upper-right loops. Live elapsed samples
under both system preferences and isolated 0.5/1/2-second screenshot pixels prove
actual motion and painted white heads/tails, not merely configured CSS animation.

C now uses measured content fit instead of a fixed portrait breakpoint. Both
corner blocks center on the action and move inward when their descendants fit;
otherwise they keep their raised, near-edge placement. Leftward overflow from
right-justified flex text must be checked directly: scrollWidth alone misses it.
At 480x375 CSS px, OFF fits but ESTABLISHING does not. Parallax gains, HUD orbit,
assembly timing and OLED drift budgets remain unchanged.

Reference fonts no longer vary with viewport width. The prototype defaults to an
explicit presentation-size lock using viewport bounds, native gesture restrictions
and keyboard/Ctrl-wheel suppression. The drawer has an accessible unlock and
describes the limits. Trusted Chromium multi-touch events verify locked scale 1,
actual enlargement after unlock, and restoration to scale 1 after relock.
This does **not** prove control over browser menu/site zoom, browser accessibility
overrides, iOS ignoring viewport limits, or OS magnification. A web page cannot
promise an absolute manual-zoom prohibition. Reviewers should reset browser page
zoom to 100%; equal CSS sizes still do not imply equal physical millimeters.

Browser checks cover 36 size contexts, 120 corner/child bounds, 240 scenes/120
drawers, 120 cross-theme brand comparisons, 450 HUD and 2,400 dynamic placements,
4,320 composed parallax cases, six native swipes, preference-independent A/C/D
motion, 90ms intermediate button glyphs and mocked review persistence. HTTPS cold
loads fetch all 28 scripts. Phone/landscape screenshots were inspected, but actual
Android phone/forced-zoom acceptance, sensor hardware, power/GPU and OLED burn-in
remain unverified. Rendering selection and Android project dependencies are unchanged.

## v35 evidence checkpoint: product BUILD verified, final UI suite PASS

This checkpoint replaces its earlier pending-product snapshot in place. The
parent independently verified all eleven product changes; their original request
wording remains before the implementation matrix, now checked for BUILD only in
[`design/standby-prototype-todo.md`](../../design/standby-prototype-todo.md#v35-scope-delegation-and-evidence).
All v35 product/agent/browser QA gates passed, including the final new UI suite,
latest D-diff oracle and Git/votes/source audit. Historical v30-v34 records and
all older manual acceptance stay unchanged and OPEN where previously open. This
child edited only the two owned documents, without rerunning product tests;
implementation/test/doc edits are child-owned, the parent dispatches/reviews/verifies.

Final reviewed host SHA-256 is unchanged:
`b27179f649976ee9d3d06777437209e9b7121ed37135274a92dda5404197ba76`.
Latest foreground D-diff oracle: terminal, no blockers. It checked the visible
intersection, 9.5px second-row offset and odd 65 repeats; its read-only 65-to-64
comparison restored the prior JS SHA, with no added rAF/timers/layout reads.
Final D source SHA-256:
- `big-standby-e.css`: `a2f726a4c9114f1a9f721a8615aaaaeed5897ef5226ca2273ea4d0260b89379d`
- `big-standby-e-scene.js`: `3f2f01a3711f3e4427b15af17e425f2d25cb6a088e1f03198c339192f33bbbee`

| Area | Parent-verified result |
| --- | --- |
| Final v35 UI suite | Parent terminal run: 26 groups PASS in 60,219ms; `failures = pageErrors = blockedWrites = []`; `ownedContexts = closedContexts = 2`. `reviewBefore = reviewAfter` matches the protected SHA below. Covers D 568x320 paint, DPR 1/3 geometry, C footer real font ink, all ten styles / eight presets / 24 body strings, A fades/OFF 75ms PNG/reversal/hidden cancellation, exact A/C/D 7,500ms active-clock boundaries/gates/stale episodes. |
| D product | Three upper/left rows; two lower/right rows, second fixed/repeated white `Win + K`. Four shot edges use fresh 0.12-0.90s gaps, 0.95-1.60s durations and quadratic position. Node 90 shots / 6,784 acceleration checks; independent 80,000 frames / 8,988 shots / eight viewports. |
| A/C/D geometry | A/C 96 CSS-px targets, original 84 CSS-px artwork/frame paths, frame scale 1.12, glyph CTM 0.84; fills A `#193b30` / C `#262a28`. D 84 CSS-px target, icon (-2, +2) CSS px. A sun 33, moon 11/9, landscape status 43%; portrait 49%, existing short-portrait 47%. Full celestial exits/reversal/gradient passed two normal and six edge viewports. |
| A text | 75ms exit glyph/partial-opacity and per-character PNG difference; normal OFF clear/hide/repeated render; rapid phases; hidden/theme/simple/projection flush passed. Clean native 45ms OFF/ON reversal at 550/1,100ms: three correct labels, opacity 1, no animations/pending work. |
| C layouts/motion | 1,680 one-line Ranges (maximum 108.109px < minimum body 130px), unchanged 10px font, no cropping; footer gap 16px / 120 cases. Motion 2,400 positions / 64 ellipse samples; styles 450 placements; colors 80 cases plus 80 visible checks. |
| Legacy/shared gates | Controls, day/night, both motion preferences (3.6s transitions; native 550ms interpolation), 36 sizing layouts plus two pinch cases, 120 edge-padding cases and clean drawer passed. Full RWD: 240 cases / ten navigation / 120 drawer / 120 brand checks. |
| Backend/unit/sensors | Real-HTTP 40 tests, flake8, all Node CJS, D streams and v35 production-function unit checks passed, including 16.7ms/80ms clock offsets and Range leading. C failure retains 250ms glitch/active-clock return; onset first directed pickup/6s and auto/explicit gyroscope UI passed. HTTPS: 28 scripts under both preferences; native Chromium mocks seven samples / 1.824 CSS-px reversal / settled zero; origin/payload guards passed. |

D verification found a second counterexample after the initial six sizes:
568x320 clipped one hint below y=321.5775 and the next beyond x=597.1979. The
shared fix centers the viewport intersection, aligns the hint with the edge's
9.5px offset and changes 64 repeats to 65, retaining 32px padding, font, colors,
API and timing without a 568px special case. The writer reports 983 viewports
with a 16.03px minimum margin; the parent did not reproduce that scan. Parent
actual HTTPS complete-group counts are 5/1/3 at 390x844 / 568x320 / 1440x1000;
568x320 rect is x=493.706, y=245.696, right=541.712, bottom=293.702. Paired
stream row/window widths agree at 1,350/909/1,600px.

A/C/D actual HTTPS v35 cold loads passed valid TLS, 200/`no-store`, no 404/page
errors; D served the final CSS/JS and selected default source118. Parent read and
visually reviewed `C:/Users/User/AppData/Local/Temp/opencode/` artifacts
`mirax-v35-HTTPS-D-{390x844,568x320,1440x1000}.png` (including white shot heads),
`mirax-v35-HTTPS-{A,C}-390x844.png` and `mirax-v35-A-day-896x592.png`.
Official preview:
`https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=35`.
Exact unit/backend/browser reproduction commands are maintained once in
[the v35 reproduction section](../../design/standby-prototype-todo.md#v35-reproduction).

### Completed agent/browser QA and interpretation limits

All v35 product/agent/browser QA gates are PASS: 26 UI groups in 60,219ms, RWD
240 cases, backend 40 tests, HTTPS A/C/D and final oracle. Parent final JavaScript
syntax and `git diff --check` passed; review SHA and source17/118/209 are unchanged.
No implementation/browser QA remains. Parent final documentation-assertion audit
and formal goal closure are still procedural actions, not claimed complete here.
Legacy drawer checks await real 320ms animation completion, not disabled
animations; the corrected C-color fixture uses the unchanged v34 rail mapping.

A/D synthetic recovery still switches at 7,500 active ms, not 7.5 wall-clock
seconds. Actual A wall/active was 8,526.1/7,899.5ms (about 330ms visible fade
included), D 8,199/7,524.8ms; respective 622.6ms/633.5ms pauses added zero active
time. The shared 80ms frame cap can extend wall time at low FPS; pause, hidden,
simple-home and projection freezes are intentional. v34 removed reduced-motion
functionality; explicit background pause remains, short UI transitions continue.
CSS-pixel lock does not promise equal physical size or block browser-menu/site
zoom and OS magnification.

OPEN: all earlier real-phone/manual visual checks, C aggregation onset and gyro
feel. Browser mocks do not verify real sensors. Android implementation, real-device
GPU/power and long-duration OLED testing remain undone; rendering selection and
Android dependencies are unchanged. Preview is unauthenticated, trusted-network
only; review writes remain origin-restricted. Original studies 17/118/209 remain
unchanged; last verified review SHA-256 is
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Final parent audit confirmed that digest. No votes edited by this lane, no
commit/push or GitHub issue created; TODO sync is incomplete because `gh` is
unavailable.

## v36 D secondary-status marquee: web/browser PASS

All v36 agent/browser gates are verified. This ordinary request replaces only
the fixed shortcut row; the completed pre-v36 goal, entire v35 history and native
Android rendering recommendation remain unchanged. No v36 goal was created.

The lower/right band retains two rows: CSS-selected semantic status first,
decoration second. `.e-stream-row--status` selects one real-state
`.e-status-message[data-status]`; existing tracks/motion gates are reused, with
no new production status/debug API. Actual type is 14.6667 CSS px (12px + 2pt),
weight 700, normal `#f2f2f2` and failed `#ff6b6b`; native motion is 24.339 CSS px/s
and remains running under reduced-motion preference.

| v36 web/browser gate | Verified evidence |
| --- | --- |
| Status copy and immediate useful paint | PASS: real OFF/broadcast buttons and phase actions; OFF has no `Win + K`, waiting names Windows/receiver, connecting says wait, failed gives red retry guidance |
| Recovery/latest-state synchronization | PASS: failed at 7,499 active ms becomes waiting/normal white at 7,500; retry/OFF/theme/history/projection have no stale text or red tint |
| Actual movement, word passage and seams | PASS: native RAF transform/glyph movement, partial-to-whole passage of the same shortcut without another seek, controlled-seek PNG ink for all five glyphs, continuous duplicate seams |
| Geometry and readable ink | PASS: eight sizes, two lower rows, no shrinking/vertical clipping, bounded viewport/window ink, north-west shift and unchanged upper band |
| Lifecycle and preserved regressions | PASS: genuine CSS paused/hidden/simple-home/projected/media gates; A/C geometry, fades and simulated sensor/OLED behavior; original X118 and studies 17/118/209 |
| Data/lifecycle safety | PASS: isolated contexts all closed; read-only/mocked review access, no real POSTs/vote changes, unchanged before/after review SHA |

The size matrix is 320x568, 390x844, 480x375, 568x320, 667x375, 896x592,
1440x1000 and 2560x720. Parent independently checked eight sizes x four phases.
Lower center displacement is approximately (-11.3137, -11.3137) CSS px; most
sizes gain about 32px of visible centerline, including 568x320. At 390x844 the
line already spans the full width and correctly gains zero. Acceptance is
nondecrease everywhere plus some constrained-size gain, not positive gain at
every size. Minimum complete visible character counts in matrix order are
30 / 40 / 15 / 12 / 17 / 22 / 30 / 69, not fixed full-sentence visibility.

### Current v36-host terminal evidence

These are actual parent browser runs; this final documentation lane did not
operate the browser or edit product/tests.

- `verify-v36-ui.js`: `ok: true`, all five groups PASS, 20,662ms;
  `failures = errors = blockedWrites = []`, owned/closed contexts 1.
- Adapted RWD: 240 cases / 10 navigation / 120 drawer / 120 brand PASS.
  Adapted v29 controls: A/C/D symbols, C warning/automatic recovery, four edge
  cases, six drawer transitions and playback PASS.
- Full adapted `verify-v35-ui.js`: 26 groups PASS in 70,656ms, failures/errors/
  writes empty, owned/closed contexts 2, reviews unchanged. This is the new
  v36-host regression run, not the historical v35 60,219ms result above.
- Units: v36 56 checks / three cleanup cases; unchanged behavior 715 shots /
  89,816 samples; D streams 90 shots / 6,784 checks; C status, syntax and diff PASS.
  The earlier runner-only `ensureAnimation()` ReferenceError was fixed using
  verified `syncMotion()`; all active-clock/gated/stale assertions remain intact.
- HTTPS cold load: valid TLS, actual 200 / `no-store`, title v36, version-36 E
  assets/default X118; no 404s, page errors or writes. Real buttons select OFF
  to waiting and failed to white OFF/no-shortcut immediately. Parent visually
  read actual OFF/waiting/failed phone and failed 568x320 PNG ink as legible.
  Temp artifacts include `mirax-v36-D-off-phone.png`,
  `mirax-v36-HTTPS-D-waiting-phone.png`, `mirax-v36-HTTPS-D-failed-phone.png`.

Preview: <https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=D&v=36>

### Integrity and scope limits

Review/notes `beforeHash = afterHash =`
`7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
Original study SHA-256 prefixes remain 17 `f1893c12`, 118 `43ed31dc`,
209 `44678823`. Parent confirms DStreams/publication suffix byte-identical to
v35 and only four host metadata/concept/version-query line changes. Baselines
remain at `C:/Users/User/AppData/Local/Temp/opencode/mirax-v35-before-v36/`;
full v35 document prefixes and completed historical evidence are intact.

Final product SHA-256 identifiers supplied by parent, recorded verbatim rather
than rehashed or LF-normalized by this documentation lane:

| File | Parent-supplied SHA-256 |
| --- | --- |
| `design/big-standby-prototype.html` | `2530f79f98a98bdbd4a30bf0c2b97f791318b91260eb0d813b594ea398279766` |
| `design/big-standby-e.css` | `263998f24bacb2267387f512e452bd2f418dcb4afe7c0cfeab2714bad1484ae7` |
| `design/big-standby-e-scene.js` | `d092f1a0b1a38e7a2323fb8bed4c2f0dc8cd0da935d6efa47bec5497ff8f8ba1` |

No agent QA remains open. Web/browser PASS does not establish native Android
feasibility or close the existing manual hardware checks:

- [ ] OPEN: real-phone/sensor/hardware acceptance, Android integration and actual
  GPU/performance/power/OLED longevity measurement; browser simulations are not
  real-device evidence and do not change the native rendering recommendation.
- [ ] OPEN: deployment security/hardening audit; valid TLS/cache-header checks
  are only a smoke test. GitHub TODO synchronization was not performed.

No Android/dependency/product/test change in this documentation pass, no vote
changes, no commit/push and no GitHub publication.

## v37 final rendering acceptance

**All current v37 AGENT-QA CLOSED (PASS). Manual/hardware/Android/security gates remain OPEN.**
The parent supplied the final browser/HTTPS evidence; this docs-only lane neither
ran the browser nor captured screenshots. Historical v35/v36 evidence is preserved unchanged.

### Final evidence and rendering conclusion

| Parent-owned final gate | Result |
| --- | --- |
| Targeted `verify-v36-ui.js` | Eight groups PASS in 28,996ms; failures/errors/writes empty, context 1/1 closed. Includes eight receiver sizes, actual glyph PNG, native entry/seam/state/gates and 7,500 active-ms recovery. |
| Full `verify-v35-ui.js` | 26 groups PASS in 62,691ms; failures/pageErrors/writes empty, contexts 2/2 closed; A/C/fonts/fades/reduced parity and shared-clock checks retained. Only the synthetic boundary fixture uses 1e-6ms epsilon; production is unchanged. |
| Responsive / legacy gates | RWD 240 cases, v29 controls, edge-padding 144 cases/three themes and e-theme six layouts/six gates/five tracks/four real native edges PASS. |
| Units / syntax / HTTPS | Status 56 + three cleanup, behavior 715 shots/89,816 samples and streams 90 shots/6,784 checks PASS; four modified browser fixtures/diff PASS. Final cold A/C/D HTTPS 200/no-store/TLS 1.3/v37, no errors or writes, context 1/1 closed; served CSS digest equals local. |

Actual PNG proves the new layering, not just DOM overlap: at 1440x1000 all five
Win + K quads lie inside the original NW face with 4px inset. Fixed changed pixels
were `[3,13,10,9,7]`, versus `[0,0,0,0,0]` with only root z-index restored to -1;
finally restored all probe state. Opaque non-text interiors kept wave/magenta RGB
deltas at 0 (limit <=3), with genuine NE/SE/diamond coverage across the two sizes;
the covered SE at 800x800 is not proof. Outside-wave changes of 2,066/4,974 pixels
prove the real renderer stayed active. Qualification, not waived coverage, excludes text/foreground.

The only latest product change is `#e-scene` -1 -> 3: text/channel art 3 > overlay 2
> opaque X 1 > study canvas 0, confined to `.generated-background` -5 below main UI.
Boundary pulses rise with channel art; four real `MiraxDStreams` edge bindings,
same-shot position/alpha/transform changes and CSS/engine freeze/resume were verified,
not fictional CSS border keyframes. Receiver layout retains OLED pure translation:
8px/16px text, nominal 12px/actual >=8px gap and <=400px stacking above the 84px dock.
Upper-left status row three, lower two rows, 64px/52px/-16px geometry, exact status
copy/font/colors/gates, opaque #090909 original five-path X and default118 sources remain intact.

The [final v37 checklist](../../design/standby-prototype-todo.md#v37-final-acceptance)
contains exact gate/native-clock/PNG coverage counts, the four parent-read HTTPS
artifacts, preview URL, final product/vote digests and all retained raw archives.
Those screenshots are paused/seeked illustrations; actual suites establish motion.
Full 17/118/209/votes SHA checks, v36 DStreams publication bytes, pre-face-fill
production inline scripts and both historical document prefixes passed the raw audit.

- [ ] OPEN, non-agent acceptance: real-phone/sensor feel/hardware, Android integration,
  GPU/performance/power and OLED longevity measurements; browser PASS does not close these.
- [ ] OPEN: deployment security/hardening; TLS/cache smoke checks are not a security audit.

Backend/Python checks were not rerun for this CSS-only phase. No Android/native
feasibility, GitHub TODO synchronization, product/test edits in this documentation
pass, dependency work, commit, push or publication is claimed.

## v38 final rendering acceptance

**All v38 AGENT-QA CLOSED/PASS. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**
The parent supplied and reviewed the final browser/HTTPS evidence. This docs-only
lane did not run a browser or produce the screenshots.

- [x] Whole original opaque X at main/overlay 4/5 masks marquee 3 above canvas 0,
  within background -5 below UI; original paths/vertices/echo/default118 stay intact.
- [x] White non-scaling 2px header stays in place behind X; extra corner path is
  completely removed. Receiver retains safe-left/bottom, #eee 2px left rule,
  padding 0/0/0/10, exact 8px/16px type and <=400px stack above the 84px dock.
  Nominal 12px/actual >=8px gap, OLED translation and upper3/lower2/status3 remain.

| Final parent evidence | Result |
| --- | --- |
| Targeted / full suites | v36 eight groups/40 receiver cases PASS 40,575ms, context 1/1 closed; v35 26 groups PASS 64,684ms, contexts 2/2 closed. Failures/errors/writes empty; votes unchanged. |
| Legacy / units | RWD 240/10/120/120, v29 controls, edge 144/three themes, E six layouts/six gates/five CSS tracks/four real native edges PASS. Syntax/status 56+3/behavior 715-89,816/streams 90-6,784/diff PASS. |
| HTTPS | Cold A/C/D 200/no-store/TLS 1.3/v38 title/assets; remote host/CSS digests match local; errors/HTTP failures/writes empty, context 1/1 closed. |

Real PNG: five masked glyphs `[0,0,0,0,0]` versus stack-only raised-text
`[3,13,10,6,7]`; header 58 inside/455 outside samples; removed stub 17 samples,
old-path peak 109 versus fixed 8. Outside header peak 231 versus old thin 108.
Wave/magenta deltas all 0 with qualified face coverage; 1,850/4,410 outside pixels
changed. Receiver backing/gray-AA/both label ink/transparent-border control and
ordinary 257-seek full words at 568x320/480x375 PASS without dodging.

The [sealed v38 checklist](../../design/standby-prototype-todo.md#v38-final-acceptance-whole-x-and-safe-left-receiver-section)
records exact final digests, protected-source audit, four parent-read HTTPS PNG
artifacts and open limits. Those paused stills illustrate appearance; native suites
prove motion. Original X group/symbol, full 17/118/209/votes hashes, metadata-only
inline normalization, unchanged E CSS/scene and complete v37 raw prefixes passed audit.
Pending-doc originals: `C:/Users/User/AppData/Local/Temp/opencode/mirax-v38-before-final-docs/`.

- [ ] OPEN: real phone/sensor/hardware, Android integration, GPU/power/OLED longevity.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization, not covered by TLS smoke checks.

Backend/Python were not rerun; no dependencies, vote writes, commit or push.

## v39 final rendering acceptance

**All v39 AGENT-QA CLOSED/PASS. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**
The parent supplied final actual browser/HTTPS evidence; this lane edited docs only.

- [x] D-only brand rule is 2px #eee with 10px inner-left padding. Original safe
  container anchor stays fixed; content moves 12px inside it. Logo 30x30 CSS px,
  name/version 18px/8px, version margin 7px, mark-name gap 8px and A/C remain unchanged.
- [x] Receiver margin 7px ->3px gives actual Range gap 6.796875px versus old-control
  10.796875px (-4px), with original fonts/family/left/bottom/backing/dock/OLED intact.
- [x] All six browser fixtures, native motion/lifecycle/7,500ms gates and HTTPS PASS.
  Test-only DOMRect tolerance corrects floating-point false failures, not product size.

The [final v39 checklist and evidence](../../design/standby-prototype-todo.md#v39-final-acceptance-matching-brand-rule-and-tighter-receiver-gap)
records exact gate counts/timings, PNG negative controls, four parent-read paused
artifacts, product digests and the preview. Native suites, not stills, prove motion.
Complete sealed v38 and older raw prefixes remain intact; baseline
`C:/Users/User/AppData/Local/Temp/opencode/mirax-v38-before-v39/` is preserved.
Pending-doc originals: `C:/Users/User/AppData/Local/Temp/opencode/mirax-v39-before-final-docs/`.

- [ ] OPEN: manual phone/sensor/hardware, GPU/performance/power/OLED longevity and Android integration.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization.

Preview has no authentication and is for trusted networks only; HTTPS smoke is not
security acceptance. Backend/Python were not rerun in this CSS-only phase. No
dependencies, vote writes, commit or push; no new authentication/security claims.


## v40 browser-pending acceptance

**v40 AGENT-QA remains BROWSER PENDING overall. Targeted parent browser PASS now covers the earlier C log correction, `A wide landscape name-to-eyebrow Range gap`, and `D upper band inner breathing`. A is the separate rerun of `verify-v35-ui.js` `cd5c6d3f0f116d6a3f67434757a9f232ad65494a8d696e6d4ff3cf0f84664692` at 25,639ms. D is 32 cases on unchanged `verify-v36-ui.js` `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`. The 27,233ms figure is the earlier combined run, in which A still failed the literal `auto` assert. It is not a D-only duration, and that combined run is not an all-PASS. Other A/D work, controls/footer, and the full v40 suite remain PENDING. Manual/hardware/Android/security/GitHub synchronization remain OPEN.**

- Sun radius is 30. The moon uses `scale(38/27)` on the original crescent. With vertical room both centers drop `0.045` of the visible height; short layouts clamp clear of the brand and primary copy. Orbit timing stays 3.6s. A/C hit target stays 96 and D stays 84.
- A pill is `x=7 y=13 width=86 height=70 rx=35`. C and D artwork stay unchanged.
- Variant A `failed` shows the existing warning glyph in white inside the broadcast button. Recovery still uses the one 7500 active-ms clock.
- C keeps 10 styles, 8 presets, 24 body lines and 10px type. CORE and GRAV restore their original three-short log frames; other frames use compact machine states, events and unequal row lengths rather than a repeated short-to-long conversational recipe. No layout, orbit, lifecycle, header, badge or accent changes.
- C restores the sealed-v39 DISPLAY-ONLY values: `OPTICS: 421 THz`, `PHASE: <0.08%`, `DRIFT: -12ps/s`, `BUS: 0.42ms`, `APERTURE: 98.2%`, `REFRESH: 60fps`, `RF: -138dBm`, `COLLISIONS: 0%`. All appear in row one or two; BUS and REFRESH move from row three to row two. These are decorative prototype model values, not measured device/network telemetry; no sensor polling was added.
- Parent independently reran `node design/verify-d-streams.cjs`: PASS, 90 shots / 6,784 acceleration checks; C 8x3 rows, eight restored measurement rows, four all-short frames (at most 16 characters per row), three mixed frames (over 16 characters with a spread of at least five), six relative-length patterns, longest rows in positions 1/2/3, maximum 19 characters including `> `, and one rejected eight-preset conversational-pool negative control.
- Parent scope audit PASS: host content outside `cLogPresets` exactly matches the before-C snapshot; original 17/118/209 source checks, `node --check` for `verify-v35-ui.js`, `git diff --check`, and complete sealed-v39 document-prefix checks pass. Protected votes remain byte-exact SHA-256 `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.
- The marquee window uses the shared scan axis (`--e-axis-start` / `--e-axis-end`). Unit checks cover 1920x1080 and 2560x720 against the old centered start. Glyph entry still needs the parent browser.
- The decorative diamond is no longer in the main SVG. `#d-corner-diamond` is a 26 CSS px overlay, hidden at width 600 or below and when it would overlap the receiver, dock, or review control. The five X paths stay unchanged.
- D broadcast fill is opaque `#262a28`. D center copy, broadcast glyph and upper status fade in about 180ms. Phase and broadcast update immediately.
- D brand padding, handle centerline and the full-width study field from the previous v40 pass stay.

### C log correction: targeted parent acceptance PASS

The parent served and SHA-256-checked the exact current `verify-v35-ui.js` (`313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`), then isolated its existing, unmodified C group with the probe/styles/sizes/frames/check/run harness. This was not the full 26-group suite.

- Targeted C browser group: **PASS, 6,427ms**. Seven sizes (`280x568`, `320x568`, `390x844`, `480x375`, `896x592`, `1440x1000`, `2560x720`) x ten styles x eight presets produced 560 actual dialogs, 1,680 actual single-line Range/font/glyph/bounds checks, 560 original measurement-row checks and 70 actual content pools. Body type stayed 10px; maximum text length was 19 characters. The group used active motion and actual `showRandomCHud()`, not hidden/stale fallback DOM. Errors, writes and HTTP errors were empty; one isolated context was created and closed. Real votes before/after matched the protected SHA above.
- HTTPS cold C preview: **PASS**, `https://desktop-2jhjlhh.tail90ee25.ts.net:8443/big-standby-prototype.html?variant=C&v=40`; actual 200 / no-store / TLS 1.3 / v40 title. Served host SHA-256 `b8b8c8950bb80dec55178c8d1e524210508afcb34631fb3198d3f171f5f5b17c` matched local bytes. JavaScript/console errors, writes and HTTP >=400 were empty; the second isolated context was closed. No certificate bypass. This preview is unauthenticated and for trusted networks only; HTTPS smoke is not security acceptance.

The parent captured, READ and visually inspected all six actual PNGs below, under `C:/Users/User/AppData/Local/Temp/opencode/`. All six showed 10px single-line body rows with the popup inside the viewport. The first four measured row-width vectors prove visible variation, the all-short exception and longest-row positions 1/2/3, rather than character-count simulation alone.

| PNG | Viewport / style / preset | Actual Range row widths (px) or inspected result |
|---|---|---|
| `mirax-v40-C-log-three-short.png` | 390x844 / Rail / CORE.FLUX.SECTOR-07 | 89.03125 / 89.03125 / 89.03125 |
| `mirax-v40-C-log-longest-first.png` | 390x844 / Rail / SPECTRAL.BUS.DAEMON | 108.109375 / 89.03125 / 57.234375 |
| `mirax-v40-C-log-longest-second.png` | 390x844 / Rail / ANISOTROPIC.SHELL | 69.953125 / 101.75 / 82.671875 |
| `mirax-v40-C-log-longest-third.png` | 390x844 / Rail / NEURAL.RELAY.CLUSTER | 89.03125 / 76.3125 / 120.828125 |
| `mirax-v40-C-log-narrow-index.png` | 280x568 / Index / KINETIC.COUPLER.REG | Single-line body; popup inside viewport |
| `mirax-v40-C-log-desktop-metric.png` | 1440x1000 / Rail / CHRONO.DIAGNOSTICS | Single-line body; popup inside viewport |

PNGs are static illustrations, not motion proof. The narrow Index check accepts the C popup only: existing narrow status/header wrapping is unrelated and unchanged, so this does not claim the entire 280px layout is perfect. At that documentation follow-up, only this C correction had targeted browser PASS. The later named A and D group results are in the final section. Controls/footer, the full 26-group suite, and the remaining six-fixture whole-v40 browser gates remain PENDING. This documentation-only follow-up did not run a browser or change product/tests.

### A wide landscape name-to-eyebrow gap: source fix, TARGETED_PARENT_BROWSER_PASS

Wide theme A kept `.device` at `calc(49% - 151px)` while `@media (orientation: landscape)` places `.stage.v-A .center` at `43%`. That 6% viewport-height anchor split shrinks the actual Range gap (`#status-eyebrow` top minus `.device-name` bottom) as the screen gets taller. Parent RED, same vertical geometry in off/waiting/connecting/failed: 1920x900 = 17.4140625; 1920x1080 and 2560x1080 = 6.6171875; 2560x1440 and 3440x1440 = -14.9921875; 3840x2160 = -58.1796875. Portrait 390x844 stayed 48.9140625 on `calc(49% - 127px)`. 568x320 keeps the device hidden.

The landscape block now also sets `.stage.v-A .device { top: calc(43% - 111px); }`. The shared 43% anchor plus 111px is height-independent and targets a Range gap of about 31.4px (acceptable 24..40). `.center` stays `43%`. Portrait, short-height, C, and D rules are unchanged. Celestial placement still uses the brand and the center bounds, not the device.

New durable group in `verify-v35-ui.js`: `A wide landscape name-to-eyebrow Range gap`. It measures that Range at 1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, and 901x900 across four phases; checks 16px/9px type and viewport bounds; keeps the center and title fixed while an inline `calc(49% - 151px)` negative control restores the 1080 and 1440 RED gaps; then restores the device inline style. Each phase is its own evaluate and awaits the production fade `finished` promise plus two frames, at most 8 passes, before it accepts a clear map and fresh title/eyebrow. Portrait 390x844, hidden 568x320, and C/D computed baselines are in the same group. This child ran `node --check` on the fixture and `node design/verify-d-streams.cjs` (90 shots / 6,784 acceleration checks, C corpus intact). It did not run a browser. That write-up was still PARENT_BROWSER_PENDING. The separate parent rerun in the final section is TARGETED_PARENT_BROWSER_PASS.

The earlier C log targeted PASS remains valid for the prior fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`. This edit changes `verify-v35-ui.js`, so that hash is not the post-gap file. Other A/D work, controls/footer, and the full v40 suite stay PENDING. Manual phone/sensor/hardware, Android integration, and security remain OPEN.

### D upper band inner breathing: source fix, TARGETED_PARENT_BROWSER_PASS

The upper marquee specified `--e-channel-height: 64px` with `border-block` of 1px each side. Under the page `border-box` rule the content box is 62px, and rows `16+16+24` plus two `3px` gaps fill that 62px, so the first code line sat on the upper edge. Only that custom property is now `80px`. `align-content: center`, the offset, the -45 degree rotation, row sizes, gap, copy, durations, the lower `52px` band and the X geometry stay. The extra 16px is split by centering into about 8px above the first row and 8px below the status row. Padding inside a 64px band was not used, because that would shrink the content box and clip the third row.

Parent pre-landing negative control and isolated candidate, not written into product and not a landing PASS: 320x568, 390x844, 896x592, 1920x1080, 2560x720. Live upper height was 64 everywhere. Old 64 local inset was 0 at the first row and 0 at the last row bottom. Isolated inline 80 was 8/8. Row heights stayed 16/16/24 and the gap stayed 3. Screen position of the three rows had max delta 0. Upper center float delta was <= 0.000123px. Lower band bbox matched value for value. Errors and writes were empty, context 1/1 closed, and the inline style was restored.

Durable group in `verify-v36-ui.js`: `D upper band inner breathing`. Eight existing sizes x off/waiting/connecting/failed. It requires the oriented row quad and `offsetTop` inset in 7.5..8.5, a non-empty first-row Range, row boxes 16/16/24, gap 3, lower height 52, type 11px and status 12px+2pt/24, durations 240s/360s, and X at z-index 4 above the marquee at 3. An inline `--e-channel-height: 64px` control must bring the inset back to about 0 and fail the 8px contract, while row screen positions, the upper center and the lower box stay within 0.05px; `finally` writes back the exact style attribute saved before the override. The check requires that same string, null only when the controller had not set one, plus the restored 80px band and 8px inset. The existing eight-size group now expects `upperHeight === 80` with center shift still under 0.6 and `lowerHeight === 52`. `v35BandLayout` is unchanged. `verify-e-theme-ui.js` only changes its hard `band === 64` caller to `band === 80`. Win + K entry, vertical clip and opaque-X paint checks in the existing v36 groups are untouched. This child ran `node --check` on `verify-v36-ui.js` and `verify-e-theme-ui.js`, plus `node design/verify-d-streams.cjs`. It did not run a browser. That write-up was still PARENT_BROWSER_PENDING. The 32-case parent pass is in the final section, and the fixture was not changed after that pass.

The C log targeted PASS remains valid for fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83` only. `verify-v35-ui.js` is still the A-gap edit, so that hash is not the current v35 file. At that write-up the A wide gap was still PARENT_BROWSER_PENDING. Other A/D work, controls/footer and the full v40 suite stay PENDING.

Parent isolated run of only these two new groups took 4958ms on SHA-verified served bytes. Both RED results were fixture assumptions, not product failures. A called `animation.finish()` and read the copy before `onfinish` wrote the next text. D required `style === null` while the stream controller already stores `--e-top-*` and `--e-bottom-*` position and alpha on the channel. The harness now awaits each phase's real `finished` promise plus two frames, and compares the restored style attribute to the saved string. Product bytes are unchanged. That 4958ms run was a fixture failure, not a landing PASS.

### Isolated HTTPS geometry probe: not a durable-group PASS

Parent ran an isolated HTTPS product probe on the served bytes. Cold A/D responses were HTTP 200, `no-store`, TLS 1.3, title v40. Served host `a46676c6670a044df0e0a63e0189a3ec7e515e90fb45e61929c540a75da4c9b6` and CSS `26694084ca81ae2f61d1b56024b21bdeb99d43a125ee07c46bc31954cd1dbfbf` matched local. Errors, writes, and HTTP status >= 400 were empty. Context 1/1 closed. This probe is static geometry. It is not a PASS of `A wide landscape name-to-eyebrow Range gap` or `D upper band inner breathing`.

A used paused final positions, so it is not native fade evidence. Eight wide viewports (1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, 901x900) times off and waiting all measured Range gap `31.4140625`. Sun and moon did not overlap the device. The smallest wide clearance was `32.720px` (901x900 moon). Portrait 390x844 stayed `48.914`. 568x320 kept the device hidden.

D at 1920x1080, 390x844, and 568x320 measured upper height 80, first and last row insets 8/8, row boxes 16/16/24, and gap 3.

Parent captured six PNGs under `C:/Users/User/AppData/Local/Temp/opencode/` and READ them. IHDR sizes below are the viewports. A returned screenshot field of height 80 is the upper `bandHeight` override, not the viewport.

| PNG | IHDR | bytes | SHA-256 |
| --- | --- | --- | --- |
| `mirax-v40-final-A-gap-1920x1080-off.png` | 1920x1080 | 225129 | `4ab1867e22680301e27a7140d78b956e4634359f205db6bc6f2562ea5058823e` |
| `mirax-v40-final-A-gap-2560x1440-waiting.png` | 2560x1440 | 455492 | `2a0e439d80e9ff2d80972ebae1adced603cd355a91b7c8c7de3135f5789a806e` |
| `mirax-v40-final-A-gap-390x844-off.png` | 390x844 | 87278 | `46229c72aa0279c7e88f85151f175e156adc9fc3b11b690257c8289b44cf163b` |
| `mirax-v40-final-D-breathing-1920x1080.png` | 1920x1080 | 247734 | `ecdc978526e2a7531635bb15f48cd9920a4d5111c350540f4ec4825d88fc8929` |
| `mirax-v40-final-D-breathing-390x844.png` | 390x844 | 61636 | `d08c2892e6b71ee1f19f7785d8cd7f19a0039b5fa24dc6c259e35b8fbde8eed7` |
| `mirax-v40-final-D-breathing-568x320.png` | 568x320 | 48792 | `fb18cd914295f392147de3490a86ffbc8b507bb3981083ed8ef93a82caac560f` |

The first durable-group run stayed RED for the two fixture reasons already corrected: A read copy before `onfinish`, and D required `style === null`. Those groups are not closed by this probe. That rerun request was superseded by the 27233ms run recorded below: the D group passed inside that combined run and its fixture is not replayed; the A group was still open. Product bytes stay the audited pair: host only the scoped A landscape anchor plus its why comment, CSS only the D height 64 to 80 plus its why comment. Scene, votes, C pool, and the sealed v39 prefixes are unchanged. The named groups were still open at this probe.

### E theme native motion: full fixture PASS, not an A/D group close

Parent ran the complete `design/big-standby-motion-prototype/verify-e-theme-ui.js` on its own. The only assertion change in that file is `band === 80`. Result PASS: 6 layouts, 6 lifecycle gates, 5 CSS tracks, and 4 native `MiraxDStreams` edges. Both `reduce` and `no-preference` advance native toward the upper right, and the seam stays continuous. Original X, opaque faces, layers, brand padding, and the receiver still pass. Legacy E to D plus the actual broadcast and drawer actions pass. The fixture closed its owned context in `finally` and wrote no files. Physical device was not tested. Fixture SHA-256 stays `facc095cd8c3bc9bd2514e70c11da5d29fcba4888245bb04b768a8668ab36f39`. This E run by itself does not close `A wide landscape name-to-eyebrow Range gap` or `D upper band inner breathing`. Their targeted parent results are in the final section.

### Combined 27233ms run: D 32 cases passed while A failed the literal auto assert

Parent targeted rerun took 27233ms for the combined A and D groups. `D upper band inner breathing` passed all 8 sizes x 4 phases: first and last insets 8/8, the inline height 64 control returned inset 0, row delta 0, upper center delta <= 0.000123, and the original inline style was restored. `verify-v36-ui.js` stays `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`. That file is not edited again and the parent does not replay it.

`A wide landscape name-to-eyebrow Range gap` passed all 32 wide phase checks, 4 portrait phases, 4 short-height phases, and both old `calc(49% - 151px)` negative controls, including style restoration and a fresh off copy. It failed only the last D baseline. At 1920x1080, `getComputedStyle(device).top` was `1006.22px` and the center was `507.594px`. The center is 47% of 1080. The device value is the used pixel length for stylesheet `top: auto` with `bottom: var(--edge-bottom)`, not the literal word `auto`. Product bytes were not changed.

The D baseline no longer asks for the word `auto`. It sets inline `top: auto` and requires the used rect top, bottom, and left to stay within 0.6px, with the center still at 47%. A leaked `calc(43% - 111px)` would move that rect. The same check then sets that A offset inline, requires the used top to resolve to it and move by more than 40px, and `finally` writes back the exact style attribute saved before the override. C stays hidden on `calc(49% - 151px)` with center 49%. The A copy waits and the old 49% controls are unchanged. That A failure was the fixture reading the literal word `auto`. The separate rerun after the used-top change is TARGETED_PARENT_BROWSER_PASS below. Other whole-v40 gates stay pending.

### A and D named groups: TARGETED_PARENT_BROWSER_PASS

These two results close only the named groups. Whole v40 stays open. Other A/D work, controls/footer, and the full suite remain PENDING. Manual phone/sensor/hardware, Android integration, security, and GitHub TODO synchronization remain OPEN.

`A wide landscape name-to-eyebrow Range gap`, exact fixture `verify-v35-ui.js` `cd5c6d3f0f116d6a3f67434757a9f232ad65494a8d696e6d4ff3cf0f84664692`: TARGETED_PARENT_BROWSER_PASS, 25,639ms. Eight wide viewports (1920x821, 1920x900, 1920x1080, 2560x1080, 2560x1440, 3440x1440, 3840x2160, 901x900) times four fresh phases (off, waiting, connecting, failed) is 32 cases. Every Range gap was 31.4140625. Fonts stayed 16px and 9px. Center stayed 43%. Portrait 390x844, all four phases, gap 48.9140625. 568x320 hid the device in all four phases. Inline `calc(49% - 151px)` restored the old gaps 6.6171875 at 1920x1080 and -14.9921875 at 2560x1440, then restoration returned 31.4140625. The main heading and center stayed put. C stayed hidden on the old 49% device rule. D stayed at 47%. The `top: auto` used-rect counterfactual and the leaked A-offset control passed, and `finally` restored the exact style. Native fades were awaited with the existing `finished` promise plus two animation frames. The group does not call `animation.finish()`. Failures were null. Errors, writes, and HTTP errors were empty. One context was created and closed. Votes before and after were `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036`.

`D upper band inner breathing`, exact unchanged fixture `verify-v36-ui.js` `edf8d2cbfe4a7de6abe5817e1425b5bbaf38c4063046d2a47c65164aab49aa43`: TARGETED_PARENT_BROWSER_PASS, 8 sizes times 4 phases, 32 cases. Upper height was 80, with the left and right axes unchanged and top and bottom insets 8. The inline height 64 control returned inset 0. Rows stayed 16/16/24, gap 3, fonts 11 and 14.6667, status line 24, upper duration 240s, lower duration 360s, and the lower band 52 with 2 rows. Opaque X stayed at z-index 4 above `#e-scene` at z-index 3. The counterfactual ran 32 times: every row delta was 0, center delta was <= 0.0001230203px, the lower box was unchanged, and the exact original inline style was restored. These 32 cases passed inside the earlier combined run of 27,233ms, while the A group in that same run still failed the old literal `auto` assert. That clock is not a D-only duration, and the combined run is not an all-PASS. The D fixture was not edited after the pass, so this evidence is reused and the group was not rerun.

Harness corrections did not change product behavior. A waits for the production fade `finished` promise plus two frames instead of calling `animation.finish()`. D compares the restored style attribute with the saved string instead of requiring `null`. The last D baseline checks the used rect of `top: auto` instead of the literal word `auto`.

Still valid, with product bytes unchanged since those runs: `verify-e-theme-ui.js` `facc095cd8c3bc9bd2514e70c11da5d29fcba4888245bb04b768a8668ab36f39` passed 6 layouts, 6 lifecycle gates, 5 CSS tracks, and 4 native `MiraxDStreams` edges, for both motion preferences, and closed its owned context. That fixture is the native motion proof. HTTPS cold A/D was HTTP 200, `no-store`, TLS 1.3, title v40. Served host `a46676c6670a044df0e0a63e0189a3ec7e515e90fb45e61929c540a75da4c9b6` and CSS `26694084ca81ae2f61d1b56024b21bdeb99d43a125ee07c46bc31954cd1dbfbf` matched local. Errors, writes, and HTTP status >= 400 were empty, and the context closed. Scene stays `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e`. The paused HTTPS geometry probe passed 20 A cases (8 wide viewports plus portrait plus short height, times off and waiting) with no art/device overlap. The smallest wide clearance was 32.720px. Three D layouts measured insets 8/8. Parent READ and visually reviewed six PNGs under `C:/Users/User/AppData/Local/Temp/opencode/`: `mirax-v40-final-A-gap-1920x1080-off.png`, `mirax-v40-final-A-gap-2560x1440-waiting.png`, `mirax-v40-final-A-gap-390x844-off.png`, `mirax-v40-final-D-breathing-1920x1080.png`, `mirax-v40-final-D-breathing-390x844.png`, `mirax-v40-final-D-breathing-568x320.png`. Those paused stills are not native animation proof.

Parent audited the product diff as one A landscape anchor plus its why comment, and one D upper height change from 64 to 80 plus its why comment. Every other host byte, the C pool, and the other CSS bytes match the pre-fix snapshot. `node design/verify-d-streams.cjs` had already passed at 90 shots and 6,784 acceleration checks with the C corpus intact. No product change followed, so that unit result is reused and was not rerun for this documentation edit. The earlier C log targeted browser PASS at 6,427ms stays valid for historical fixture `313c6d668e05200140114c65294acf423b256063fdc18a956b94d41917101a83`. It was not rerun on the newer v35 fixture. The fixed C pool and C styles were not touched.

This documentation edit did not run a browser or a unit suite.

- [ ] OPEN: manual phone/sensor/hardware, GPU/performance/power/OLED longevity and Android integration.
- [ ] OPEN: security/hardening audit and GitHub TODO synchronization.

No dependencies, vote writes, commit or push.


## 2026-10-31 current Android implementation readiness (v40 source audit)

**Status: SOURCE-/API-AUDITED after parent review (2026-10-31).** This source/API readiness assessment remains a recommendation, not an approved ADR or implementation authorization. H0/M1-M4 remain OPEN; G0-G4 remain OPEN/UNEXECUTED implementation/acceptance gates. Native build/device/performance/power/OLED/release verification for this port remains UNEXECUTED.

### Decision and evidence boundary

**Recommendation, not an approved ADR: GO for a bounded native information slice using existing Views/state seams, not an assumed fullscreen standby host; conditional GO for incremental A/C/D decoration ports after the H0 host contract below; NO-GO for claiming the new standby UI integrated, device-qualified, power-qualified, OLED-safe, or release-ready.** The current browser prototype is useful port input, not an unusable prototype merely because some whole-v40 gates remain open. Visual approval of the A/D spacing fixes does not close any Android gate.

Evidence labels in this chapter: **SRC** = current local source inspected, not executed; **BROWSER** = the specifically named parent-reported browser evidence preserved above, not rerun here; **API** = primary platform documentation retrieved on 2026-10-31; **PROPOSED** = a recommendation or acceptance gate, not an implementation or measured result; **OPEN** = missing decision/evidence. No percentage complete, achieved FPS, battery estimate, thermal result, or burn-in guarantee is inferred.

| Readiness question | Current disposition | Evidence / limit |
| --- | --- | --- |
| Can engineering begin a narrow native slice? | **GO, bounded** | SRC: real dashboard, session actions/snapshot, persistence and projection separation exist, but not a fullscreen standby host or big/simple selector. API: API 34+ has the basic View/Canvas primitives. Start with real information and controls; H0 is required before claiming host/layout fidelity. |
| Are browser behavior and UI specified enough to start? | **Partly, feature-scoped** | BROWSER: current C HUD, A spacing, D upper band and E/D fixture gates passed only as named below. Remaining whole-v40 A/D/controls/footer gates stay open. H0 host/navigation coordinates, Android state/error, units, motion and display policy still need explicit contracts. |
| Is the new Android standby integrated/runnable? | **Not implemented / not demonstrated** | SRC: no A/C/D standby host, sensor adapter, theme/media preferences or animation controller was found in the inspected Android source. Existing Mirax app/projection code is present; this is not a claim that the existing app cannot run. No build/install/launch was attempted. |
| Is native visual, input, fold and sensor parity proven? | **NO-GO for parity claim** | OPEN: no native screenshots, font/density/inset goldens, TalkBack checks, sensor replay/physical motion or projection regression for this port. SDK method names do not prove parity. |
| Is native performance/power/OLED/release readiness proven? | **NO-GO** | OPEN: no release-like trace, measured device baseline, power/thermal comparison, agreed brightness/time policy, or distribution validation. Browser PASS and source inspection cannot supply these. |

**Frozen inputs.** The entire pre-chapter report is retained as a raw-byte prefix, SHA-256 `f03d9803aeb66e50939f25d4cae6c3b8d366c9779d4badbe3d93baceb3a103f1`, including the sealed v39 record and latest v40 targeted results. Research snapshots and hashes are under `C:/Users/User/AppData/Local/Temp/opencode/mirax-android-readiness-v40/` (`report-before.raw.md`, `inputs/`, `input-manifest.json`, `primary-source-audit.json`).

| Current product input | Exact SHA-256 |
| --- | --- |
| `design/big-standby-prototype.html` | `a46676c6670a044df0e0a63e0189a3ec7e515e90fb45e61929c540a75da4c9b6` |
| `design/big-standby-e.css` | `26694084ca81ae2f61d1b56024b21bdeb99d43a125ee07c46bc31954cd1dbfbf` |
| `design/big-standby-e-scene.js` | `fca2aab5a73208e26f0c4c0e19bfef4f2041b9905530854391e86f8c17aea37e` |

The Android/build, C helper JS, original geometry, studies, fixtures and other documentation inputs are individually hashed in that manifest. These are source snapshots, not a resolved dependency graph or device build identity. Product variants are **A/C/D only**; B is removed; legacy E selects D (`design/big-standby-prototype.html:1030,1067-1070`). The historic fixture's word "native" for `MiraxDStreams` means browser-side JS/CSS behavior, not native Android code.

**Current browser proof, narrowly retained:** C targeted PASS: 6,427ms, 560 dialogs / 1,680 lines / eight original display-only metrics. A targeted PASS: 25,639ms, 32 wide phase cases, actual Range gap 31.4140625 CSS px; portrait 48.9140625; short-height device hidden. D targeted PASS: 32 cases, upper band 80, inner padding 8/8, rows 16/16/24 and gaps 3. E/D fixture PASS: six layouts, six lifecycle gates, five CSS moving tracks, four `MiraxDStreams` edges. These results are documented in the preceding v40 sections, not newly executed here. Other A/D work, controls/footer and whole-v40 gates remain PENDING; manual hardware, security and Android work remain OPEN.

### What this chapter supersedes, without rewriting history

| Historical passage in this file | Current controlling interpretation |
| --- | --- |
| Opening status / "觀察範圍與證據" describe a v13 snapshot dated 2026-10-29 | Historical only. Use this chapter's exact v40 inputs for current Android readiness; old source observations are retained but rechecked below. |
| "已實現交集與尚未完成的要求": C 10-second assembly and start-response TODO | Current helper uses six-second exponential ease-out assembly, eight-second release and continuous reversal. Do not port the old 10/8 timing (`design/big-standby-c-particle-assembly.js:54-77`). |
| Same table: perspective, rotation, scale, 30 degrees/second, shared smoothing 8 | Superseded by translation-only short-edge 1.89%/1%, independent smoothing 4/2; normalization is 90 degrees/second, idle 150ms, gap rejection 250ms (`design/big-standby-c-parallax.js:37-65`; `design/big-standby-c-gyroscope.js:175-202`). |
| Old manual-enable-only sensor / C preview-override / reduced-motion statements | Browser behavior changed repeatedly through v23-v34. Current browser wiring is not an Android sensor implementation or approval to override native accessibility preferences. ADR 0006 is accepted for the prototype, not proof of native integration. |
| "待決事項": only two prototype TODOs, A day/night not implemented, environment has no `gh` | Not a current backlog or environment assertion. A now uses broadcast-driven day/night. This task did not inspect GitHub tooling, synchronize issues or infer missing work from the old two-item list. |
| Old dependency summary names only JUnit/Truth as testing support | Incomplete as a current inventory: AndroidX instrumentation runner/rules, test-ext JUnit, Espresso and UiAutomator are also directly declared. Their declaration is not a device-test PASS. |
| Old full-browser PASS statements for earlier versions, or any reading of v40 as whole PASS | Version-scoped historical evidence remains valid in its original scope. The latest v40 targeted PASS does not close the pending whole-v40 suite or any Android/device gate. |

### Current repository audit and smallest integration seam

Paths below refer to the frozen source tree. `K/` in subsequent tables means `app/src/main/java/me/trinitrix/mirax/`; `T/` means `app/src/test/java/me/trinitrix/mirax/`. Neither alias names a new source directory.

| Inspected source | What already exists / what it does not establish |
| --- | --- |
| `app/build.gradle.kts:6-38,64-90`; `build.gradle.kts:1-4`; `settings.gradle.kts:9-18` | **Declared:** minSdk 34, targetSdk 34, compileSdk 36, JVM 17; View Binding/AIDL; AGP 8.13.2, Kotlin 2.3.21; only `:app`. compileSdk 36 is a compile-time API surface, not API 36 runtime support or target behavior. No build/toolchain compatibility was tested. |
| `app/build.gradle.kts:64-90` | **Declared UI:** core-ktx 1.15.0, appcompat 1.7.0, Material 1.12.0, constraintlayout 2.2.0, activity-ktx 1.9.3. Other direct dependencies: Shizuku api/provider 13.1.5, DataStore preferences/rxjava3 1.1.1, RxJava 3.1.6, Gson 2.10.1. Tests are also directly declared: JUnit 4.13.2, Truth 1.4.4; instrumentation runner/rules 1.6.1, AndroidX test-ext JUnit 1.2.1, Espresso 3.6.1 and UiAutomator 2.3.0 (`app/build.gradle.kts:79-89`). No Compose, AndroidX WebKit, particle engine or graphics framework is directly declared. **Resolved/transitive versions and installed runtime versions are unverified.** |
| `app/src/main/AndroidManifest.xml:13-35`; `K/MainActivity.kt:76-112,181-210,217-270,278-283,444-490`; `app/src/main/res/layout/activity_main.xml:1-179` | AppCompatActivity + View Binding with existing panes/drawer; status bars and cutout preference already have an inset policy. MainActivity recreates normally; PictureActivity explicitly handles listed size/orientation configuration changes. No standby activity/container is declared. No explicit hardware-acceleration opt-out was found; the actual drawing Canvas must still be checked. [R40-HWUI] |
| `K/MainActivity.kt:886-960,1441-1486,2683-2694` | `Page` lists DASHBOARD/HISTORY/GENERAL/etc., not big/simple standby (`K/MainActivity.kt:77-89`). `buildDashboard():886-935` returns a normal column with title/hint/broadcast/history/handshake; `updateDashboard():937-959` updates tagged children. `buildPage():836-850` wraps that content in `scroll()`, whose content width is capped at 480dp; the column has 28dp horizontal and 56dp bottom padding (`K/MainActivity.kt:2179-2217`). `refreshIfShowing()` is a state-refresh seam, **not a fullscreen scene host**. CUSTOM means custom video resolutions, not imported background media. |
| `K/SessionHost.kt:29-48,99-149`; `K/session/MiraxSession.kt:5-36,123-140,275-355,655-691`; `K/session/SessionTypes.kt:6-23,429-504` | `SessionHost.commit():99-124` is the existing host-side side-effect/persistence/WFD bridge; `dispatchConnectionEvent():130-135` handles the action, commits, then actively calls `MainActivity.refreshIfShowing()`. `MiraxSession.handle()`/`snapshot()` delegate advertising, active connection, display preferences and diagnostics. Reuse that event-driven path: no second WFD reducer, snapshot polling loop, advertise timer or assumed new ViewModel/DI graph. |
| `K/SessionPreferences.kt:35-91,123-153`; `K/session/DisplayPreferences.kt:7-76`; `K/session/ConnectionDiagnosticsRepository.kt:6-46`; `K/session/ConnectionHistory.kt:36-74`; `K/MiraxApp.kt:20-49` | Session settings use SharedPreferences; connection history uses DataStore and the existing history factory; diagnostics are already separated. No standby theme, pause/motion, OLED time/brightness or background-media preference is present in the inspected settings/snapshot. Existing `broadcastAutoStopMinutes` governs broadcasting, not an OLED display timeout. |
| `K/PictureActivity.kt:87-119,202-264,267-282`; `K/wfd/H264SurfaceDecoder.kt:203-229,363-388,471-511`; `K/session/PictureTouchMap.kt:10-49`; `K/PictureActivity.kt:613-645` | Dedicated SurfaceView, MediaCodec output and picture-touch mapping already exist. Projection lifecycle resumes/stops debug updates; onDestroy detaches the decoder (`:202-251`), surface callbacks attach/detach its Surface (`:254-264`), and `applySession()` finishes when `showPicture` is false (`:267-282`). These are projection ownership boundaries, not a new standby host. Do not attach decoration Canvas/sensors to these callbacks or the surface/input-mapping path. Existing projection `FLAG_KEEP_SCREEN_ON` is not a standby policy. |
| `K/wfd/DebugOverlayView.kt:20,162-205`; `K/PictureActivity.kt:833-852`; `app/src/main/res/drawable/ic_mirax_mark.xml:7-27` | A custom Canvas overlay and periodic decoder-stat updates already demonstrate local platform conventions, not standby capacity. Five filled X paths are reusable geometry input; the drawable also has its own colors and an extra stroke, so drawing it unchanged is not D parity. Keep real decoder stats distinct from decorative C/D copy. |
| `T/session/MiraxSessionTest.kt:17-51`; `T/session/ScreenViewportGeometryTest.kt:6-41`; `T/wfd/H264LowLatencyConfigurePolicyTest.kt:7-13`; `app/src/androidTest/java/me/trinitrix/mirax/MiraxE2ETest.kt:30-91`; `app/src/androidTest/java/me/trinitrix/mirax/MiraxMultiTouchInjectionTest.kt:30-31` | Existing session/geometry/decoder and device-regression seams exist. Actual Kotlin globs found 17 unit-source files (16 `*Test.kt` files plus the `TestConnectionHistory.kt` fixture) and two androidTest files. None was executed; this count is not coverage of the new renderer, sensors, timing or accessibility and is not a current PASS. |

The inspected `app/src/main` contains 57 Kotlin and two Java files; `helper/src` adds three Java files to the app source set (`app/build.gradle.kts:41-45`). This is not a greenfield Compose application. A fresh scoped search of Kotlin files under `app/src/main` for `Choreographer`, `SensorManager`, `SensorEventListener`, `RuntimeShader`, `RenderEffect`, `RenderNode`, `WebView`, `BigStandby`, `Standby`, `standby`, `customBackground`, `backgroundUri` and `themeId` returned zero matches. This is a source-wiring observation only, not proof about resolved/transitive dependencies, actual GPU behavior, installed app state or runtime sensor availability.

**PROPOSED renderer seam, not existing code:** a visibility-owned decoration View/container with a small typed projection of `SessionSnapshot` plus local visual progress. Its parent and coordinate rectangle are contingent on H0 below; the existing dashboard refresh seam does not supply fullscreen composition or navigation equivalence. Keep ordinary text and actionable controls as existing native Views above it. Add only the preferences required by the chosen scope through existing persistence; do not prebuild a scene framework, DI wrapper, Compose migration or renderer dependency. Future implementation should use Kotlin, strong types, KDoc and lifecycle-bound coroutines/Flows where needed, without rewriting the existing callback refresh seam merely to introduce a framework. `MainActivity.renderFromHost():278-283` reads the current snapshot; `render():444-490` updates page/drawer/layout parameters and is a **state-event render, not a frame clock**. A decoration frame may invalidate its own View, but must not call full dashboard `render()`, poll the session or run `SessionHost.commit()` every frame.

### H0: host coordinates and settings entry (OPEN, product/UI owner)

**SRC, not a fullscreen host:** `app/src/main/res/layout/activity_main.xml:2-6` supplies a root FrameLayout, but `paneStart` is inside a horizontal split. Its toolbar has a 72dp minimum height and 48dp Menu/Back controls (`app/src/main/res/layout/activity_main.xml:14-48`); `pageStart` takes only the remaining pane height (`:63-67`). Wide rendering retains start/end 50/50 panes (`K/MainActivity.kt:444-490`). Root padding already follows the dashboard's system-bar/optional-cutout policy. The base `Theme.Mirax` does not set `windowFullscreen`; only `Theme.Mirax.Picture` explicitly sets it true (`app/src/main/res/values/themes.xml:3-22`), consistent with MainActivity explicitly showing status bars. A fullscreen projection theme is not proof of a standby host or permission to reuse PictureActivity. A Canvas added to the page/ScrollView would inherit a different width, origin, scrolling and safe edge from the prototype stage. It cannot be called a direct fullscreen port.

| Explicit option, neither selected | Coordinate/composition consequence | Settings, navigation and proof still required |
| --- | --- | --- |
| **H0-F: full-window standby presentation in the existing Activity** | PROPOSED: use the actual app-window visual rectangle with an explicitly chosen inset policy, not panel pixels. The root FrameLayout is only a possible parent; deciding which existing toolbar/panes are hidden, retained or restored requires approval. This best preserves the intent of full-viewport 43%/47% anchors, orbit travel, safe edges and fixed dock, but does not prove pixel parity. | Decide big/simple entry, settings/drawer entry, Back behavior, focus and restoration of the existing page/chrome; supply a named window/inset golden and entry/exit state diagram. No new Activity, Page value or navigation framework is authorized by choosing to study this option. |
| **H0-C: content-region standby with dashboard chrome retained** | PROPOSED: explicitly define the scene as a measured `pageStart` viewport/content rectangle, excluding retained chrome/insets as agreed, not the ScrollView child's scroll height or capped 480dp column by accident. Recompute anchors, orbit limits, edges and dock against that rectangle; preserve 50/50 behavior or approve a scoped exception. This is a native layout reinterpretation, not fullscreen prototype equivalence. | Keep existing settings navigation if selected; decide where big/simple/theme selection lives and whether decoration/dock stay pinned to the content viewport while information scrolls. Require content-rectangle goldens and approved narrow/wide/large-font behavior. Existing navigation is reusable only where it actually remains unchanged. |

**H0 exit artifact:** select H0-F or H0-C; record the scene rectangle/origin, chrome/inset/split policy, big/simple/settings/Back entry-exit contract and resize/fold behavior. `sceneRect` is only a proposed term here, not an existing field/class. Do not silently add a full-window Activity, new Page hierarchy or navigation dependency. Until H0 is selected, G1 can explore real-state binding in the existing dashboard, but cannot claim a final standby host or validate prototype 43%/47%/safe-edge/dock parity. Keep the current dashboard as the fallback.

### Platform fit and per-feature port obligations

**Rendering model:** particle/geometry updates, text measurement, layout and display-list recording still consume CPU work; HWUI can replay supported hardware-Canvas drawing through GPU rendering. Check `Canvas.isHardwareAccelerated`, not just a manifest flag. Each normal View already has a backing RenderNode; property transforms can reuse recorded content, whereas changed particle geometry needs new drawing commands. Neither Canvas2D nor "GPU-backed" means all simulation, blur, clipping and text are free or on the GPU. [R40-HWUI][R40-NODE][R40-CANVAS]

RenderNode is public from API 29, RenderEffect from API 31, RuntimeShader/AGSL from API 33, hence available within the declared API 34 minimum. Availability is not a reason to add them. A scoped RenderEffect may reproduce an approved error blur/color treatment after baseline profiling; AGSL programs a stage of the Canvas/RenderNode pipeline, not a general replacement for the full GPU pipeline or CPU particle simulation. Do not introduce Compose, OpenGL ES, Vulkan, a custom RenderNode tree or AGSL framework without a measured need. [R40-NODE][R40-EFFECT][R40-AGSL]

| Frozen feature / contract | Smallest proposed native primitive / seam | Proof obligation and fallback, all unexecuted |
| --- | --- | --- |
| A broadcast-driven day/night; independent opposite full-viewport celestial orbits and gradients, 3.6s | Reuse exact SVG path data as Canvas Path/vector geometry with platform LinearGradient/RadialGradient; keep separate orbit/gradient progress driven by broadcast intent, not wall-clock or C assembly. Sun radius 30; moon is the actual crescent path scaled from nominal 27 to 38, not a substitute circle (`HTML:79-95,659-673,1676-1726`). | Golden intermediate states and mid-transition reversal at known size/density; verify off/night, on/day and non-overlap. Fallback: bounded static day/night art plus real status. No fake daily weather/time behavior. |
| **Shared A/C:** 96 CSS-px hit area; 84 CSS-px SVG art with `viewBox="0 0 100 100"`; rail groups alone use `scale(1.12)`. **A only:** capsule rect `x=7, y=13, width=86, height=70, rx=35`, all **SVG user units, not CSS px**; failed `!` is white. **C:** triangle `points="12,18 88,18 50,83.81793"` plus double-edge path `M10 28 38 76.49742`, not a capsule; failed `!` retains default red `#ff5757`. **D:** 84 CSS-px opaque charcoal control unchanged (`HTML:533,537,541,544,558-559,804-807`). | Keep native button/switch semantics and independent untransformed hit rectangles. Paint only artwork inside the control, not a decorative click overlay. Preserve actions via `SessionHost` / `SessionAction`. | Define CSS reference-to-dp mapping first; satisfy native touch targets/focus/contrast at font scale. Cancel superseded 180ms copy/state fades; latest state wins, including rapid toggles. Do not disable retry or change broadcast state to satisfy animation. |
| C original 1,800 particles / X geometry, six-second exponential assembly, eight-second release, continuous reversal | CPU numeric arrays and pure progress/math functions; hardware Canvas circles/paths in one decoration View. Reuse `big-standby-c-particle-assembly.js:54-77` semantics, not the v13 durations. | Deterministic replay at several dt values, reversals and resizes; exact five-path targets and no first-response delay hidden by faster formation. If costly, pause/freeze decoration or explicitly approve a lower detail/update cadence; do not silently rewrite timing or geometry. |
| C translation-only depth, background short-edge 1.89%, information 1%, smoothing 4/2 | Separate sibling translations, not nested inherited transforms; CPU exponential smoothing + View translation/Canvas save/translate/restore. No perspective, rotateX/Y or content scaling (`big-standby-c-parallax.js:37-65`). | Independent state histories; safe composed margins for HUD + parallax + OLED; fixed text size at all extremes. Fallback: neutral sensor target, static information; never move the whole root/control/surface. |
| C HUD: 6 by 3 CSS-px ellipse, 5.5s; ten styles / eight presets, three rows with original key/value/units (`HTML:1032,1383-1464,1485-1488,1531-1578`) | One shared decoration clock; Canvas line/anchor art with a real semantic text layer where information is meaningful. Port only the selected product treatment, not every study/review tool. | Golden row packing, anchors and narrow/large-font limits. Original eight metrics, including fps, ms/msec and %, are **DISPLAY-ONLY**, not measured refresh, latency, bitrate, packet or network telemetry. Label as decorative/sample or omit; do not connect invented values to `SessionSnapshot`. The displayed clock is UI time/sample copy, not telemetry, a heartbeat or a network-success criterion. |
| D original selected X118: all five filled paths and vertices, opaque whole X above diagonal code | Reuse five original Path/vector geometries; draw code first, opaque X next, primary UI last. Preserve SVG-to-native viewport matrix; do not approximate, crop or regenerate the X. | Assert path vertices, winding and opaque coverage; pixel goldens for all arms/center. Existing icon's additional stroke/colors are not automatic D output. Fallback: static exact X, never a changed silhouette. |
| D upper three rows / lower two rows; five fixed status messages; same-axis responsive diagonal tracks | Canvas clipped text runs / platform text layout where needed, measured baselines, translate/rotate inside save/restore. Upper 80 / lower 52 CSS px; upper rows 16/16/24, gaps 3, inner 8/8; code 240s/360s; fixed per-phrase status durations 148/144/84/140/108s (`big-standby-e.css:18-40,91-143`; `big-standby-e-scene.js:7-44`). | Preserve readable real status outside decorative marquee; measure each localized phrase to avoid seam jumps. Five browser moving tracks are not five new native clocks. Reduce/freeze decorative tracks if budget fails; retain real status. |
| D four accelerating emission edges, per-glyph Win+K clipping, static 2px white header above clean/mesh bottom | Reuse `MiraxDStreams` scheduling/progress math (`big-standby-e-scene.js:47-127`) on the same native active clock; scoped Canvas clips, strokes and fills. | Replay accelerating travel, restart/pause and alpha; compare glyph clipping, not only text-box bounds. Preserve upper-right direction and no hidden-wall-time burst. Fallback: static strokes; do not introduce timers per edge. |
| D receiver safe-left + 2px / 10 padding / gap 3; brand right 12; center 47% and handle follows including OLED; 26px corner art hides when no room | Existing layout/inset bounds + measured native text/layout positions; decoration follows a resolved safe rectangle, real controls do not inherit burn-shift or parallax. | Dense/short/tall/fold goldens with composed motion extrema. CSS px and pt are not dp/sp/physical px; preserve hierarchy and readable baselines, not font scale/crop tricks. Fallback: hide optional corner art or reduce motion, never crop primary text. |
| Semantic state, errors, real retry and connection controls | Map existing snapshot/action seam to text and art; no browser network simulator or seven-and-a-half-second domain timer. Current C `startCFailure()` shakes/filters `document.body` for 250ms (`HTML:1875-1903`); a native equivalent must be scoped to eligible decoration, never the window/picture/touch root. A measured RenderEffect experiment is optional, not parity proof. | Complete the contract below before failure-theme port. Fallback: existing dashboard/compatibility/retry Views. Decorative animation failure must not restart WFD or clear a real connection failure. |
| Gyroscope / relative angular motion | Foreground `SensorManager`, game rotation vector then rotation vector if available, matrix/quaternion relative delta and screen-axis mapping; neutral input when unavailable. | Timestamp replay plus real motion across display rotations and pause/resume; no absolute-position/calibration promise. Platform availability is checked at runtime. Details below. [R40-SENSORS][R40-POSITION][R40-SMANAGER][R40-SEVENT] |
| OLED slow shift and display lifetime | Independent local visual offset from the same clock, separated from parallax/HUD input; explicit display policy owned by product. Current helper selects independent x/y within +/-3 CSS px, duration at least 150s (`big-standby-c-oled-drift.js:5-29`). | Physical-pixel range, luminance, static areas and timeout require native calibration. Fallback: dim/end standby and allow system sleep. Pixel shift alone is never proof against burn-in. |
| Custom media / simple home / projection exclusions | Simple home uses existing information Views. If imported media is selected, a separate ImageView or platform video container owns its URI/playback lifecycle, not the Miracast decoder Surface. Native imported-media support is **absent**, not the CUSTOM resolution page. | Decide scope and URI/type/size/error rules before import implementation; stop decoration/sensors while media/projection owns the page. Fallback: existing plain dashboard, with an actionable import error if applicable. No new media engine is justified by the browser demo. |
| Resize, fold, orientation, insets | Recalculate scene coordinates in `onSizeChanged`/layout and reapply WindowInsets to the actual available window; retain approved state/progress while resetting stale sensor samples. MainActivity recreation and PictureActivity's handled changes need separate tests. | H0's agreed scene rectangle, measured within the actual window, drives standby; retained chrome/content-viewport constraints apply under H0-C. Do not use panel/native `wm size` or ScrollView child height. Hinge/occlusion support needs an explicit product target and evidence; do not claim a separating fold is handled merely from rotation. Fallback: safe single-pane static information. [R40-INSETS][R40-RESIZE] |

`HTML:` in this table refers only to `design/big-standby-prototype.html`; helper names refer to `design/`. Browser reference sizes remain reference sizes until H0's scene-rectangle contract and native unit/font/inset rules are approved; existing `pageStart`/ScrollView coordinates are not a fullscreen viewport substitute. `Canvas.clipPath`, save/restore and `Paint.measureText`/font metrics supply primitives, not matching browser glyph rasterization. Keep native TextViews for receiver/status/action semantics; measure real baselines and text bounds rather than horizontally scaling or clipping fonts into a screenshot. [R40-CANVAS][R40-PAINT][R40-UNITS]

### State and lifecycle contract, before theme integration

`SessionSnapshot` is the UI projection, not an animation model. The following is **PROPOSED mapping** using current fields (`K/session/SessionTypes.kt:429-504`; phase composition at `K/session/MiraxSession.kt:655-691`). A local visual projection can consume it; it must not duplicate protocol interpretation.

| Actual source field / state | Native UI meaning / unresolved contract |
| --- | --- |
| `advertisingEnabled` | User broadcast intent and A day/night input, **not proof of discoverability**. During ARMING or WIFI_PAUSED the art may be day while native status must state the real condition. Toggle only through the existing action/host seam. |
| `phase = READY` | Closest browser baseline is `off`, not `failed`; preserve native ready/compatibility information and authoritative broadcast flag. A day/night still reads `advertisingEnabled`, not an inferred phase boolean. |
| `phase = ARMING` | **No exact browser phase.** Privileged listen is not confirmed: never announce Win+K discoverability merely because broadcast intent is true. Keep native pending copy; C formation/D status decisions are M3 below. |
| `phase = ADVERTISING` | Closest browser visual state is `waiting`; only the actual confirmed native state may announce discoverability. Use the real receiver name; do not confuse it with an active source connection. |
| `phase = CONNECTING` | Closest browser visual state is `connecting`; preserve real negotiation progress. `handshakeLog` is populated only in CONNECTING while `showDebugMessages` is true, not a perpetual diagnostic feed. |
| `phase = CONNECTED` | Closest browser visual state is `projecting`; obey `showPicture` and hand off to PictureActivity. Stop standby frames/sensors; do not continue an error/formation timer under the decoder surface. |
| `phase = WIFI_PAUSED` | **No exact browser phase.** Broadcast intent remains true while Wi-Fi is off: keep the checked toggle and explicit native paused status, not fake user-off or Win+K-ready copy. A may remain day without claiming discovery. |
| `phase = FROZEN` | **No exact browser phase.** Retain privilege/compatibility waiting and the existing retry action; do not substitute synthetic network `failed`. Existing dashboard title/hint logic remains authoritative (`K/MainActivity.kt:962-992`). |
| `SessionAction.BeaconFailed` (event, not a ScreenPhase) | With active advertising, clears the broadcast flag, sets internal DENIED, ends connection state and emits `ShowAdvertiseFailedToast` (`K/session/SessionTypes.kt:529-533`; `K/session/AdvertisingState.kt:49-57`; `K/session/MiraxSession.kt:463-467`). There is no FAILED ScreenPhase. A returns to night with the real false flag; any extra error art needs M1-M4, not an automatic return to broadcasting. |
| `showPicture`, `selectedMode`, `currentResolutionText`, `handleResolutionText`, `handleRefreshRateHz` | `showPicture` hands off to PictureActivity; selected/handle mode describes a negotiated/current mode, not a measured frame rate. Stop standby decoration and sensor registration before projection; do not wrap the picture stage in a standby transform. |
| `effectiveBroadcastName`, `displayNameOverride`, `displayNameFollowsDevice`, `appLanguage` | Actual receiver identity and locale; do not hardcode browser `MIRAX · 客廳` or pretend it is the Windows source name. Source-name availability is a separate domain requirement (`docs/CONTEXT.md:19-25`). |
| `showCompatibilityNotice`, `wfdOwner`, `effects` | Preserve actionable compatibility/privilege feedback. Effects are host side effects, not a new decorative failure enum. **OPEN, session/product owner:** define which real failures enter a theme error presentation, its message, dismissal and retry behavior. Snapshot currently has no general live failure reason/time field. |
| `handshakeLog`, `showDebugMessages`, `showDebugOverlay`, `connectionRuns` | Real diagnostics/history have existing owners; failed historical runs are not automatically the current live error. Do not mine them to synthesize a failure state or display browser C sample metrics as telemetry. |
| `cameraCutoutAffectsLayout`, `broadcastAutoStopMinutes` | Preserve existing inset and broadcast policy. Neither specifies native immersive standby, dimming, display lifetime or background-media lifecycle. Theme/simple-home/media/pause preferences and their ownership are not current snapshot fields. |

**Mapping gap inventory: seven native phase cases must be covered; three (ARMING/WIFI_PAUSED/FROZEN) have no browser counterpart, and browser `failed` has no native ScreenPhase. Four presentation decisions remain OPEN, not four missing WFD implementations:**

| Decision / owner | Required choice before the affected feature; safe default meanwhile |
| --- | --- |
| **M1: error trigger taxonomy, session/product** | Distinguish beacon-arm failure, negotiation failure/drop, playing disconnect, missing privilege and Wi-Fi pause; decide which may enter an error-themed visual. Existing native phases/toasts remain the default, not a single invented FAILED enum. |
| **M2: current-error content/precedence, session/UI** | Define whether a persistent live error presentation is needed, its message/source and precedence over normal phase copy. Snapshot has no general live failure reason/time field; host effects are consumed and historical runs are not live state. Keep existing toast/compatibility UI unless a small explicit presentation contract is approved. |
| **M3: per-theme inputs/copy, product/UI** | A already follows broadcast intent; choose C formation behavior during ARMING/WIFI_PAUSED and D presentation for the three additional native phases. Preserve native TextView status if D's five browser messages cannot represent the truth; do not fabricate three approved translations/copies. |
| **M4: visual completion/dismiss/retry, session/UI** | Decide when any extra error art ends and which real user action it exposes. Native `Retry` currently refreshes privilege/environment/provisioning (`K/MainActivity.kt:285-290`; `K/session/MiraxSession.kt:126-129`), not an unconditional network restart. No timed re-advertise or retry is authorized. |

**Do not port the browser failure recovery into the session.** `HTML:2478-2494` advances capped active time and changes synthetic `failed` to `waiting` after 7,500 active ms while broadcast remains on. Native `BeaconFailed` instead turns advertising off and emits a toast. These contracts are not interchangeable. A visual flourish may finish locally, but must leave the authoritative phase/flag unchanged until a real action/event changes it. Do not add a timer that silently clears failure, restarts advertising, dispatches `Retry` or fabricates beacon-listening confirmation. M1-M4 block error-themed integration as applicable, not G1's real-information slice.

**Existing broadcast-timer contract, not an OLED timer:** `K/broadcast/BroadcastAutoStop.kt:11-44` schedules idle broadcast auto-off from `broadcastAutoStopMinutes` (default 1 minute at `K/session/SessionTypes.kt:490`, clamped to 1..180). `sync()` arms only ADVERTISING; ARMING/CONNECTING and other phases disarm it rather than interrupt a handshake. Repeated sync with the same armed minutes does not restart the deadline. Expiry uses the existing `SessionHost.setAdvertising(app, false)` plus `MainActivity.refreshIfShowing()` path. Reuse this product policy unchanged: decoration frames, pause, sensor samples and OLED movement must not reset it, modify its inputs or replace it with animation active-time. Do not add a second 7.5s failure/advertising/keep-alive timer. A separately approved display dim/end policy must not commandeer this broadcast timer.

**PROPOSED decoration lifecycle invariant:** one monotonic frame-clock owner for particle/formation progress, HUD, OLED and D streams; A keeps its own finite transition progress on that timeline, not C's formation fraction. Repost `Choreographer` callbacks only while the selected standby page is visible and eligible; remove them when hidden/stopped/detached/destroyed. Use explicit active deltas, a first-resumed-frame zero delta and the frozen browser's 80ms scene-step cap unless intentionally changed with replay proof. Paused/hidden time contributes zero; never catch up a queue of missed emission events. Ordinary View invalidation/property animations use the platform scheduler, not a second free-running loop. [R40-CLOCK][R40-FRAMECALLBACK][R40-LIFECYCLE]

Eligibility needs the actual foreground/lifecycle + H0-approved selected big-standby host + not paused + not projection + not custom media. Those host/settings concepts must first be added deliberately; they do not exist merely because the prototype has them. Start conservatively with a visible resumed owner; define visible multi-window behavior before relaxing that rule. A new snapshot cancels obsolete fade/update work; callbacks must check current owner/state so stale completion cannot write old copy. Resize/fold/recreation recomputes geometry and limits, resets sensor history and preserves only deliberately saveable visual/theme state, never a stale active connection.

**PROPOSED sensor contract:** prefer `TYPE_GAME_ROTATION_VECTOR` for relative movement without magnetic north; fall back to `TYPE_ROTATION_VECTOR`, then neutral/no-sensor. Game rotation vector can drift and availability varies. Use `getRotationMatrixFromVector` plus `remapCoordinateSystem`/current display rotation; Android sensor axes follow natural device orientation, not automatically the current screen. Reproduce the browser's relative rotation derivative once: degrees/second / 90, clamp [-1,1], independent 4/2 smoothing, 150ms idle zero target and >250ms sample-gap rejection. A raw gyroscope alternative reports radians/second already; do not divide angular velocity by dt again. No absolute translation or startup-center calibration is promised. [R40-SENSORS][R40-POSITION][R40-SMANAGER][R40-SEVENT]

Sensor event timestamps are monotonic nanoseconds on the `elapsedRealtimeNanos()` time base; frame and sensor clocks must not be blindly subtracted. Use event timestamps for sample dt, record sample arrival in the chosen animation/idle clock, reset across suspend/rotation, reject non-finite/non-positive intervals and do not retain mutable event buffers. Registration delay is a hint, not a guaranteed cadence; start with a modest normal/game rate and profile, not FASTEST/high-sampling permission. Register only for an eligible C owner; unregister/reset on pause, hidden page, theme/media/projection switch and owner destruction. Missing/invalid sensors must not disable controls or unrelated allowed HUD/OLED policy. [R40-SENSORS][R40-SEVENT][R40-SMANAGER]

**Projection isolation is non-negotiable:** SurfaceView has a separate surface; MediaCodec output and UIBC touch coordinates keep their current owners. Standby translation, clipping, OLED offsets, shader effects or background video must never transform, redraw or consume input for the decoder surface. Preserve contact-ending rules on picture rectangle/control changes and only-display transitions (`docs/CONTEXT.md:7-48`; ADRs 0002/0003/0005). Existing projection keep-awake behavior is not changed by this recommendation. [R40-SURFACE][R40-MEDIACODEC]

### Accessibility, units, display policy and optional fallback

- **Units/fonts:** CSS px, CSS pt, Canvas2D backing pixels, dp, sp and physical panel pixels are different contracts. Layout/hit targets use native density-aware units; text follows native sp/font scale and actual font metrics; OLED offset is separately calibrated in physical pixels. Validate known density, resolution, font scale, locale, font fallback and insets before comparing goldens. Do not port `user-scalable=no`, browser zoom locking or fixed-font screenshot tricks into native policy. [R40-UNITS][R40-PAINT]
- **Controls/accessibility:** retain native actionable controls with at least conventional 48dp touch targets, content descriptions, checked/disabled state, keyboard/D-pad focus and readable contrast. Decorative particles/code/duplicated status stay non-focusable and non-announcing; meaningful receiver/status text remains accessible once. If essential interaction is painted into one Canvas, it requires virtual accessibility nodes; avoiding that new obligation is a reason to keep real Views. Test TalkBack order and focus after transitions, not only a hierarchy dump. [R40-A11Y][R40-TARGETS]
- **Insets/folds:** existing dashboard shows status bars and optionally clears cutouts; picture immersive/gesture policy is separate. A full-bleed backdrop may extend behind bars while text and controls respect safe insets and navigation gestures. Do not copy a browser full viewport over Android system UI, invent TV support, or change projection gesture exclusions for decoration. Phones/Fold are the domain priority; TV remains out of scope unless selected. [R40-INSETS][R40-RESIZE]
- **Pause/reduced motion:** browser preview overrides and v34 removal of reduced-motion behavior are historical browser choices, not permanent native approval. Product/accessibility owner must choose native pause and system-animation-disabled behavior before animated acceptance. `ValueAnimator.areAnimatorsEnabled()` exists, but manual Canvas/Choreographer/sensor loops do not automatically inherit an animator policy; gate them explicitly and offer a readable static fallback. [R40-ANIMATORS]
- **OLED/display policy:** decide foreground display time limit, dim/end behavior, luminance/brightness range, low-APL art, duty cycle and treatment of static bright brand/text/header/error areas. Slow independent pixel shift is only one mitigation; it cannot guarantee burn-in prevention. Allow normal system sleep; no standby background wake lock, invisible GPU loop or assumed always-on entitlement. Projection's existing keep-screen-on flag does not justify adding one to standby. If a dim/end timer is approved, define its monotonic elapsed/foreground-time semantics independently of capped animation progress; pixel shift, frames and sensor noise must not count as human activity or reset it. This is distinct from the existing broadcast auto-off policy. No harmful forced burn-in/endurance test is requested. [R40-AWAKE]
- **Optional WebView:** only reconsider if native fidelity/port cost is shown unacceptable. It is not installed integration and would add a WebKit/version/security boundary. For an approved experiment, use audited packaged assets with `WebViewAssetLoader` (AndroidX WebKit is not directly declared) or a justified `loadDataWithBaseURL` approach; forbid untrusted navigation/native bridges, file/universal-file access and mixed-content bypasses. Keep real controls/state outside it and stop hidden content. On `onRenderProcessGone`, the affected WebView cannot be reused: remove/clean it and show the native safe dashboard; do not retry forever. No WebView/security test was executed. [R40-WEBASSETS][R40-WEBRENDERER]

### Staged execution and acceptance gates, all PROPOSED / UNEXECUTED

No schedule estimate is supplied without a device baseline. Each stage has its own artifact and failure fallback; browser PASS closes none of these native gates.

| Gate / entry | Required exit evidence | Failure meaning / smallest fallback |
| --- | --- | --- |
| **G0: freeze and contracts**, entry = this source snapshot | Hash manifest; selected A/C/D product scope (not 209 studies/review tools); H0-F/H0-C scene rectangle/chrome/settings-entry decision; real-state/error mapping; CSS-reference-to-native units/fonts; native motion/display defaults; phone/Fold targets and custom-media inclusion. Each decision is required before its affected feature, not all before plain information work. | Unselected H0 blocks final host/layout-parity claims; unknown error/media/OLED semantics block those related ports, not real-state binding in the current dashboard. Keep existing behavior; no new Activity, navigation framework, ADR or dependency change is authorized here. |
| **G1: minimal native information slice**, entry = real snapshot/action contract; H0 selected before host/layout validation | Source diff confined to the approved seam; state-projection tests for all actual phases/privilege/Wi-Fi combinations; real name/language/status/controls; lifecycle detach proof. Host acceptance additionally requires H0's measured scene rectangle, settings/Back/chrome entry-exit evidence, known-density/font-scale screenshots and accessibility focus proof. Without H0, current-dashboard binding tests are exploratory, not final host/parity evidence. | Any fake discoverability/error clearing, broken action or hidden callback leak fails. Remove decoration and retain current working information Views. Build/run proof belongs to future implementation, not this assessment. |
| **G2: incremental A, then C, then D**, entry = G1 + selected H0 + per-feature frozen contract | Small deterministic formula/replay tests against frozen JS values; A transition/reversal frames; C six/eight-second reversal and 1,800-target geometry; sensor timestamp/axis/idle cases; D all five X paths/vertices, glyph clips, row baselines, 8/8 spacing, track seams and edge acceleration. Save native goldens at named density/font/insets. | A/C/D fail independently. Preserve information and original geometry; disable/freeze the offending effect rather than replacing the renderer or silently changing timings/metrics. Do not port study/review/vote infrastructure. |
| **G3: real devices, accessibility and performance**, entry = native baseline + agreed measurement protocol | Identified API 34+ phone/Fold builds; real pixels/fonts with large text, narrow/short windows, fold/rotation/cutout/gesture insets; TalkBack/keyboard; sensor/no-sensor; pause/resume/kill/recreate and rapid latest-wins actions. Release-like per-feature and baseline traces, frame/CPU/memory/GC/power/thermal comparison and approved thresholds. | No performance/parity claim on emulator/browser evidence. Reduce approved decorative cadence/detail or fall back to static art; preserve controls and C formation semantics. Missing target hardware or unsigned acceptance thresholds keeps this gate open. |
| **G4: projection handoff, display policy and release decision**, entry = G3 + agreed display limits | Repo device handoff with Windows Miracast, actual Wi-Fi Direct/RTSP/H.264/MediaCodec/UIBC, real DebugOverlay pixels, return/retry/reconnect/rotation and no decoration/sensor activity during projection. Verify existing idle-broadcast auto-off still runs only under its current ADVERTISING policy, cannot cut ARMING/CONNECTING and is not reset by visual frames; separately verify allowed system sleep and any approved dim/end fallback. Include process/renderer failure paths if used and distribution/signing/target compatibility for the chosen release channel. | Decoder/input/contact regression blocks release even if standby is smooth. Retain original dashboard/projection path. Pixel shift, short traces or screenshots cannot close long-term OLED uncertainty. |

**Measurement protocol, PROPOSED:** record model/SoC, API/build fingerprint, panel resolution/density, window size, refresh rate, brightness/adaptive-brightness state, font scale/locale/insets, build variant/profileability, selected effects and sensor availability/rate; record thermal/battery conditions and trace duration. Compare identical scene/state sequences against the existing/plain native baseline. First locate UI-thread simulation/layout/draw recording, RenderThread work, GPU/composition and allocation/GC costs; do not start by installing a benchmark framework.

Use System Trace/Perfetto FrameTimeline expected versus actual presentation for supported View frames, plus platform `Window.OnFrameMetricsAvailableListener`/`FrameMetrics` for window timings/deadlines. **FrameTimeline documentation currently excludes SurfaceView**, so a smooth standby/window trace alone does not establish smooth Miracast decoder output; use the decoder's own counters/logs and an appropriate surface/presentation trace alongside real picture evidence. Profile a release-like, locally profileable build, not a debug-only run; current manifest does not itself provide a profileable configuration. `release` currently disables minification and uses `signingConfig = signingConfigs.getByName("debug")`; the source comment explicitly says **Sideload / GitHub Release only; not a Play Store key** (`app/build.gradle.kts:19-28`). This declared channel is not a port blocker, and Play publication/target policy is not a prerequisite for G1-G3. Distribution channel and signing remain release choices to confirm only for the channel actually selected. No build/manifest change is made here. [R40-FRAMETIMELINE][R40-FRAMEMETRICS][R40-PROFILEABLE]

**Acceptance proposal, not measured PASS:** 60Hz and 120Hz have approximate per-refresh budgets of 16.67ms and 8.33ms; these are arithmetic budgets, not achieved frame times or a demand to animate at maximum refresh. Report p50/p95/p99, deadline-miss/jank distribution and state-switch response, not only average FPS. Agree per-device miss-rate/latency and power/thermal limits after baseline and before judging the port; unset limits are an OPEN gate, not permission to declare success. Require zero standby frame/sensor callbacks after the owner is stopped/destroyed, no growing retained allocations across repeated lifecycle cycles, no stale resumed bursts and no decoder/input regression in the chosen runs. Android Studio power-rail measurements are device-dependent (ODPM support is not universal, notably not assumed for a Fold); disclose missing rail data and use controlled device-supported measurements rather than inventing mAh savings. Do not interpret a short power/thermal trace as burn-in qualification. [R40-POWER]

**Later device handoff, not executed:** follow `.agents/e2e-testing.md:19-87` and `docs/CONTEXT.md:101-120`; keep each run's logs/screenshots/XML/traces under one approved Temp run directory. Pair actual session/codec logs with UiAutomator hierarchy and screenshots/OCR of rendered output; the guide's historical hardcoded bounds are a device example, not a universal assertion. Include `scripts/click-fold.ps1` Windows source handoff and the existing `MiraxE2ETest` / multitouch injection seams when the parent authorizes device work. Neither a log line nor an XML node alone proves correct Canvas pixels, cadence or video/touch behavior.

### Prioritized open decisions and recommendation

1. **Before host/layout port, product/UI owner:** select H0-F or H0-C and approve the scene rectangle, retained chrome/split/insets, big/simple/settings entry and Back/focus restoration. `pageStart`/ScrollView are not fullscreen equivalents. No new Activity/Page/navigation framework is implied; until selected, G1 is limited to real-state binding in the current dashboard.
2. **Before the related port, session/product owner:** resolve M1-M4 for error-themed integration and cover all seven native phases, including ARMING/WIFI_PAUSED/FROZEN and the distinct BeaconFailed flag/toast behavior. Retain the existing event-driven host path; do not import synthetic failure recovery or add polling. These presentation gaps do not block static real information.
3. **Before animated acceptance, product/accessibility/display owner:** choose native unit/font/inset rules, pause/system-motion behavior, brightness/time/sleep policy and whether custom media is in the first scope. Native pixel shift and large-font limits need explicit device calibration; do not reinterpret user satisfaction as these approvals.
4. **After the information slice, Android/performance owner:** obtain the selected phone/Fold baseline and release-like traces; agree per-device frame/power/thermal thresholds before any readiness claim. Only measured bottlenecks can justify RenderEffect/AGSL, altered decorative cadence or reconsidering WebView.
5. **Before release, integration/release owner:** close real Miracast/UIBC/lifecycle/accessibility regression, exact distribution configuration and residual OLED risk. Re-evaluate target requirements for the selected release channel when releasing; compileSdk 36 does not settle them. No current store policy claim is made.

**Next bounded action for the parent:** have the product/UI and session owners choose H0's host rectangle/chrome/settings-entry/Back contract and settle the native state/presentation contract, then seek explicit authorization for a bounded G1 information slice. The completed source/API review does not select H0-F/H0-C, approve M1-M4, close G0-G4 or authorize a port start. This is an audited readiness recommendation, not an approved ADR or Android implementation delivery.

### Primary sources and research limitations (verified 2026-10-31)

Context7 was attempted anew with Android SDK / Views / hardware Canvas / RenderNode / RenderEffect / RuntimeShader / Choreographer / SensorManager scope. `resolve-library-id` returned **"Monthly quota exceeded. Create a free API key at https://context7.com/dashboard for more requests."** No current library ID could be resolved, so a Context7 `query-docs` result is not claimed. This is a new observed failure, not reliance on the v13 quota note. DuckDuckGo located official Android/Perfetto sources; some longer qualified searches returned "No results found". No blog is used as API proof.

All sources below were retrieved successfully (HTTP 200, final URLs and raw HTML/text hashes archived in `primary-source-audit.json` / `primary-sources/`). Webfetch's secondary extraction timed out on some guides and truncated large API navigation before the requested content; direct retrieval plus extraction beginning at `<h1>` supplied the checked source text. The current FrameCallback reference contains an unresolved `ERROR(...)` animation-time cross-reference; no undefined/new API from that broken link is proposed. These documentation-access issues are not Android runtime test failures.

| Reference | Primary source and bounded claim used here |
| --- | --- |
| [R40-HWUI] | [Hardware acceleration, Views](https://developer.android.com/topic/performance/views/hardware-accel-views): acceleration defaults, actual Canvas check, display lists/invalidation and supported operations. |
| [R40-NODE] | [RenderNode](https://developer.android.com/reference/android/graphics/RenderNode): API 29, existing View backing nodes, recorded drawing and transform properties. |
| [R40-EFFECT] | [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect): API 31, scoped effects on a View/RenderNode. |
| [R40-AGSL] | [RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader): API 33, AGSL's place in Canvas/RenderNode drawing, not a complete GPU pipeline. |
| [R40-CANVAS] / [R40-PAINT] | [Canvas](https://developer.android.com/reference/android/graphics/Canvas) / [Paint](https://developer.android.com/reference/android/graphics/Paint): draw/clip/save/restore/transforms, text measurement/font metrics. |
| [R40-SENSORS] / [R40-POSITION] | [Sensor overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) / [Position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position): availability/rates/lifecycle/device axes; game rotation vector versus magnetic rotation vector and drift. |
| [R40-SMANAGER] / [R40-SEVENT] | [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager) / [SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent): registration, matrix/remap API, event timestamp time base and angular units. |
| [R40-CLOCK] / [R40-FRAMECALLBACK] | [Choreographer](https://developer.android.com/reference/android/view/Choreographer) / [FrameCallback](https://developer.android.com/reference/android/view/Choreographer.FrameCallback): display callback posting/removal and adjusted frame-time contract; late-frame jumps require care. |
| [R40-LIFECYCLE] / [R40-RESIZE] | [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle) / [Handle configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes): lifecycle ownership, recreation and size/fold changes. |
| [R40-INSETS] / [R40-UNITS] | [Window insets, Views](https://developer.android.com/develop/ui/views/layout/insets) / [Different pixel densities](https://developer.android.com/training/multiscreen/screendensities): system-window intersections and density/font unit distinctions. |
| [R40-A11Y] / [R40-TARGETS] | [Custom View accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/custom-views) / [Accessible Views apps](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views): custom node/focus obligations, descriptions and 48dp targets. |
| [R40-ANIMATORS] / [R40-AWAKE] | [ValueAnimator](https://developer.android.com/reference/android/animation/ValueAnimator) / [Keep the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on): animator-enabled setting; explicit foreground keep-screen-on behavior, not standby entitlement. |
| [R40-FRAMETIMELINE] / [R40-FRAMEMETRICS] | [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) / [FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics): expected/actual frame analysis and SurfaceView limitation; window frame metrics. |
| [R40-PROFILEABLE] / [R40-POWER] | [Profileable manifest element](https://developer.android.com/guide/topics/manifest/profileable-element) / [Power Profiler](https://developer.android.com/studio/profile/power-profiler): release-like local profiling and device-specific rail availability. |
| [R40-SURFACE] / [R40-MEDIACODEC] | [SurfaceView](https://developer.android.com/reference/android/view/SurfaceView) / [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec): separate surface and codec-to-Surface output boundary. |
| [R40-WEBASSETS] / [R40-WEBRENDERER] | [Local WebView content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content) / [WebViewClient](https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,%20android.webkit.RenderProcessGoneDetail)): asset origin/file/mixed-content guidance; unusable renderer-failed WebView cleanup. |

**Execution limit:** this work read/snapshotted source and researched primary documentation only. No Android implementation, renderer/dependency/test/product edit, app/browser/server launch, Gradle resolution/build/test, adb/UiAutomator/device/Miracast run, security exercise, OLED endurance test, review/vote write, GitHub write, commit/push or goal action was performed. Only this research Markdown is appended in the repository; pre-existing working-tree changes are not attributed to this task. The raw-prefix, protected-input hash, changed-path, whitespace and citation checks are recorded in the Temp audit, not treated as proof of native readiness.

[R40-HWUI]: https://developer.android.com/topic/performance/views/hardware-accel-views
[R40-NODE]: https://developer.android.com/reference/android/graphics/RenderNode
[R40-EFFECT]: https://developer.android.com/reference/android/graphics/RenderEffect
[R40-AGSL]: https://developer.android.com/reference/android/graphics/RuntimeShader
[R40-CANVAS]: https://developer.android.com/reference/android/graphics/Canvas
[R40-PAINT]: https://developer.android.com/reference/android/graphics/Paint
[R40-SENSORS]: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
[R40-POSITION]: https://developer.android.com/develop/sensors-and-location/sensors/sensors_position
[R40-SMANAGER]: https://developer.android.com/reference/android/hardware/SensorManager
[R40-SEVENT]: https://developer.android.com/reference/android/hardware/SensorEvent
[R40-CLOCK]: https://developer.android.com/reference/android/view/Choreographer
[R40-FRAMECALLBACK]: https://developer.android.com/reference/android/view/Choreographer.FrameCallback
[R40-LIFECYCLE]: https://developer.android.com/guide/components/activities/activity-lifecycle
[R40-INSETS]: https://developer.android.com/develop/ui/views/layout/insets
[R40-RESIZE]: https://developer.android.com/guide/topics/resources/runtime-changes
[R40-UNITS]: https://developer.android.com/training/multiscreen/screendensities
[R40-A11Y]: https://developer.android.com/guide/topics/ui/accessibility/views/custom-views
[R40-TARGETS]: https://developer.android.com/guide/topics/ui/accessibility/views/apps-views
[R40-FRAMETIMELINE]: https://perfetto.dev/docs/data-sources/frametimeline
[R40-FRAMEMETRICS]: https://developer.android.com/reference/android/view/FrameMetrics
[R40-PROFILEABLE]: https://developer.android.com/guide/topics/manifest/profileable-element
[R40-POWER]: https://developer.android.com/studio/profile/power-profiler
[R40-SURFACE]: https://developer.android.com/reference/android/view/SurfaceView
[R40-MEDIACODEC]: https://developer.android.com/reference/android/media/MediaCodec
[R40-AWAKE]: https://developer.android.com/develop/background-work/background-tasks/awake/screen-on
[R40-WEBASSETS]: https://developer.android.com/develop/ui/views/layout/webapps/load-local-content
[R40-WEBRENDERER]: https://developer.android.com/reference/android/webkit/WebViewClient
[R40-ANIMATORS]: https://developer.android.com/reference/android/animation/ValueAnimator

## v41 Native Phase Preview and Confirmed Decisions (2026-10-31)

Status: Prototype preview extension (v41); **v41 native-phase prototype TARGETED QA PASS** (parent browser fixture `9960b45...`, 65,890ms). This is not whole-v40/v41 Ready and does not close Android implementation, thermal throttling, hardware sensor testing, security, GitHub sync, commit, or push. No ADR/spec was created.

### User Confirmed Decisions Q1–Q7 (2026-10-31)
*This record supersedes previous recommendations regarding reduced-motion compliance, not keeping awake, postponing custom media, and treating simple mode as the product default.*

1. **Q1 (Main Homepage Role)**: Big Standby is the new full-window animated main homepage for Mirax; the existing native dashboard is maintained separately. The browser prototype's simple view is a minimal illustrative comparison, not an implemented reproduction of the native dashboard.
2. **Q2 (Design Fidelity)**: User confirmed direction is strictly "Faithful to the design" (『忠於設計』). Explicit all-device pixel, RWD, and typography equivalence tolerances remain UNRESOLVED; this is not approved as an exemption from pixel identity.
3. **Q3 (Native ScreenPhase Authority)**: Native `ScreenPhase` defined in `SessionTypes.kt` (`FROZEN`, `READY`, `ARMING`, `ADVERTISING`, `CONNECTING`, `CONNECTED`, `WIFI_PAUSED`) is the single source of truth. The prototype's 7.5s active-ms episode is strictly a legacy demonstration simulator and must never be ported to Android as a network recovery mechanism. The browser prototype has incorporated all 3 missing native states: `ARMING`, `WIFI_PAUSED`, and `FROZEN`.
4. **Q4 (Motion Continuity Policy)**: Foreground Big Standby ambient motion continues even when the system enables reduced motion (the user rejects a completely static Big Standby). Lifecycle pausing when the app is hidden, backgrounded, or actively receiving projection is retained.
5. **Q5 (Product Settings Scope)**: All review voting, text notes, state inspectors, and connection simulation tools are prototype-only QA utilities and are excluded from the native product; the native settings drawer retains only genuine app configuration.
6. **Q6 (Keepawake and Brightness Policy)**: Foreground Big Standby keeps the device awake and the app does not actively dim screen brightness. This is an application-level presentation policy and does not claim to override OS thermal governance or hardware safeguards; the web prototype does not add Web Wake Lock.
7. **Q7 (Custom Media Inclusion)**: Custom images, videos, and GIFs are included in the porting scope and are no longer postponed.

*Disclaimer: This extension preview is not a complete Android specification, ADR, or native implementation. Precise error classification, OS thermal constraints, and physical device gates remain OPEN.*

### Parent RED Verification and Harness Corrections (2026-10-31)
During initial execution of `verify-v41-native-phases-ui.js`, the parent runner identified 4 test harness timing/assertion discrepancies and 2 prototype defects:
- Test timing: Instantaneous reading of `#status-title` occurred while 180ms copy fades were in flight. Resolved in harness by awaiting `mainCopyChanges.animation.finished` and bounded RAF loops.
- Failure fixture: C failure anchor was null until the 250ms glitch animation finished. Resolved by awaiting real glitch finish rather than altering production clocks.
- C RWD: Checked `#status-title` which is intentionally hidden in Variant C. Resolved by measuring actual visible `#c-status-word`, `#c-standby-time`, connection labels, non-overlap, and computed fonts (>= 12px for status word, >= 20px for title).
- Prototype startup assertion: Fixed `console.assert` on startup to validate non-off initial phases and intents without throwing errors on valid queries.
- Prototype CSS active status selectors: Replaced blanket `[data-broadcast="false"]` with canonical phase-based selectors (`.stage[data-phase="..."]`) so that `FROZEN` renders exactly 1 active message whether intent is retained or false, and new native phase tracks run when active in Theme D.

### Parent HTTPS Cold-Load Audit (PASS, 2026-10-31)
Independent remote HTTPS audit by parent verified 9 cold-loaded pages (3 new states ARMING, WIFI_PAUSED, FROZEN x 3 themes A, C, D at 1440x1000):
- Remote production artifacts verified identical: host `4e7bb436...`, css `36f62cf0...`, scene `648d5b9f...`.
- Exact native phase mapping, title copy, C status word (`ARMING` / `WAIT_WIFI` / `UNAVAILABLE`), and disabled toggle in FROZEN verified.
- Theme D: exact 1 active status message running, unready phases show no Win + K invitation, 0 console/page errors, 0 writes, 1 context closed.
- Visual inspection of 9 captured screenshots (`Temp/opencode/mirax-v41-{A,C,D}-{ARMING,WIFI_PAUSED,FROZEN}.png`) PASS. Remote ratings `7c5f...` unchanged.

### Parent Actual Browser Suite Execution (PASS, 2026-10-31)
Parent executed `verify-v41-native-phases-ui.js` (`9960b45...`) in 65,890ms: **PASS ALL 7 groups, 0 failures, 0 errors, 0 blocked writes, 1 owned context closed**:
- 13 cold queries PASS without console errors or manual clicks.
- 24 phase checks (3 variants x 8 phases) PASS: D projecting hides ambient row with 0 visible ambient messages; non-projecting D has exact 1 actually visible message running with advancing transform after CSS transitions; unready phases show no Win + K invitation.
- 72 RWD cases (8 sizes x 3 new states x A/C/D) + CONNECTED projection across 8 sizes PASS without font downscaling or viewport overflow.
- Interactive UI picker + explicit confirmation ACK verified through settings drawer tools; ON -> ARMING observable and stable >400ms without fake auto-ack.
- Live focus transition: enabled broadcast toggle focus falls back to `#settings-handle` on FROZEN while background motion stays active.
- Glitch cancellation: entering ARMING, WIFI_PAUSED, or FROZEN cancels failure glitch, clears 7.5s recovery anchor, and blocks late callback resurrection.
- Before/after protected votes SHA-256 `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036` intact.
- Additional parent checks: `verify-e-theme-ui.js` PASS (6 layouts, 6 lifecycle gates, 5 CSS tracks, 4 native DStreams edges); compatibility probe PASS (simple 9 cases, real PNG `loadFile` media 9 cases [image coverage only, not video codec/GIF/hardware proof], D new phase pause/hidden/resume 6 cases, theme history to C preserves FROZEN).

### Preview URL Parameters & Interactive Review Flow
- Preview query: `?phase=ARMING|WIFI_PAUSED|FROZEN&variant=A|C|D&v=41` (query parameters do NOT pin an immutable artifact version; served host SHA `4e7bb4363dcbcbcb36cc72af6966a8148ea99f1895d43a1842a9c37bb7c81985` identifies the snapshot).
- Prototype review flow: open drawer via `#settings-handle` -> enable `#show-prototype-tools` -> pick phase via `#native-phase` -> click `#confirm-service-ready` to transition from ARMING to WAITING.

### Checklist: v41 Native Phase Prototype (TARGETED QA PASS)
- [x] Node.js unit contract suite `verify-native-phases.cjs` passes all 7 ScreenPhase state mappings, guards, query parsing, intent retention, and legacy timer clearing.
- [x] Node.js suite `verify-d-streams.cjs` passes all status copy, duration, and selector checks.
- [x] Standalone browser test script `verify-v41-native-phases-ui.js` executed and verified PASS (65,890ms, 7 groups, 0 failures/errors/writes).
- [x] Parent browser automation: 24 phase checks: 7 native + legacy failed x A/C/D at matrix viewport; 72 RWD cases: ONLY 3 new states x A/C/D x 8 sizes; 8 CONNECTED projection checks.
- [x] Parent browser automation: Cold URL query loading (`?phase=ARMING`, etc.) without console errors (13 cold queries).
- [x] Parent browser automation: Broadcast ON enters ARMING immediately and stays stable without auto-ack until explicit confirmation.
- [x] Parent browser automation: Unready phases never display Win + K connection invitations.
- [x] Parent browser automation: FROZEN disables broadcast toggle, moves focus to settings handle, and keeps background motion active.
- [x] Parent browser automation: Entering ARMING, WIFI_PAUSED, or FROZEN cancels failure glitch and clears 7.5s recovery anchor.
- [x] Parent browser automation: Protected `review-votes.json` SHA-256 remains intact (`7c5f...`).

*Separate non-agent / hardware limits remain OPEN: Android native implementation, Gradle build, physical devices, thermal/power/OLED measurement, deployment security, and GitHub sync.*

### WIFI/FROZEN copy and FROZEN info button (TARGETED_FROZEN_DETAILS_PARENT_PASS, 2026-10-31)
WIFI_PAUSED title `Wi-Fi 已關閉`, detail `Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播`, C hint `Wi-Fi is off`. D marquee `Wi-Fi is off · Please turn on Wi-Fi to broadcast`. `--e-status-duration` stays 144s (48/40 character-ratio estimate; CSS unchanged). Parent measured D WIFI 26.208333 CSS px/s and FROZEN 26.5, both in 18..32. FROZEN title and summary `接收不可用`; C hint `Receiver unavailable`; C right short code stays `UNAVAILABLE` with responsive wrapping. Parent NO-WRAP candidate stayed temporary in the browser and was not applied, because 320 overlaps the receiver hint. D marquee stays `Receiver unavailable · Check settings` at 112s. FROZEN existing controls are enabled info buttons (`role=button`, no `aria-checked`, `aria-haspopup=dialog`, controls `#receiver-details`, label `查看接收不可用詳情`): an info action, not a broadcast switch and not a 7.5s retry. `toggleBroadcast()` still returns immediately while frozen. This supersedes only the earlier note that FROZEN disables the broadcast toggle. `#receiver-details` is a prototype explanation, not actual Android error detection. A/C warning colors reuse FAILED; D status row `#ff6b6b`. No FAILED glitch or 7.5s recovery on FROZEN. A/C control hit 96x96; D hit 84x84, same as before. Glyph color: A white, C red, D red.

Parent Node: host/scene/fixture syntax, native VM, `verify-v35-behavior`, `verify-c-status`, `verify-v36-status` (60 checks), `verify-d-streams` PASS; `git diff --check` PASS. Fixture `86f1a3961359d954c640512b5d2ba72aa8fa43fd4db18d320a817bcb3c13b25a` 8 groups PASS 78,491ms: 13 cold queries; 24 phase checks; 72 RWD cases (ARMING, WIFI_PAUSED, FROZEN x A/C/D x 8 sizes only; not all 7 phases x 8) + 8 CONNECTED projection; stale glitch 3 targets; detail group 3 themes / 6 intents / 8 popup bounds / 1 simple-settings entry; click close and Escape restore focus; role and `aria-checked` restore; no intent mutation; persistent warning colors; no FAILED timer. 0 errors, 0 writes, 1 owned context closed. Votes `7c5f032ff30b4c245b1303dcbb6f5268c60aeac6229381fb0f9c462176a40036` identical before and after. HTTPS remote hashes exact: host `edf7c95daafdb2f0203508af37aa48431b70d5647beff5563f85fe73ab942df7`, css `a6b094a26a146cc48f4405257e5fb9ea3d28f4708d147cabcf8069788429d198`, scene `057d851d15cc30b1788f2ce3df519915b5010c34bec9888debeb73647f6cc848`. C hints `Wi-Fi is off` and `Receiver unavailable` exact. D WIFI marquee exact. Chinese titles `Wi-Fi 已關閉` and `接收不可用`. 6 cold C/D/A text and warning PNGs at 390x844 parent-read, plus modal C 320x568 and D 568x320 parent-read: 0 horizontal overflow, close focus returns, phase frozen, intent false unchanged, layout offsets before and after exact. PNGs `Temp/opencode/mirax-v41-frozen-details-{A,C,D}-{WIFI_PAUSED,FROZEN}.png` and `Temp/opencode/mirax-v41-frozen-popup-{320,568}.png`. 0 errors, 0 writes, 1 owned context closed.
### Five state-motion requests (TARGETED_STATE_MOTION_5ITEMS_PARENT_PASS, 2026-10-31)
A day/night rise root cause: `#standby-view` has `translate3d(burnX, burnY, 0)` and `#landscape` does not. `layoutCelestial()` measured `.brand` and `.center` from `stage.getBoundingClientRect().top`, so `burnY` entered the clamped Y at 667x375, 2560x720, and 915x412. A 0.01 to 0.05px change added `.a-layout-reset` (`transition: none`), forced reflow, and snapped `.a-sun-orbit` / `.a-moon-orbit` in one frame (`midCount=0`, `getAnimations().length=0`). The measurement now uses `#standby-view` top, or stage top when that view has no box, and writes the transform and edge variables at 0.01px so subpixel noise does not abort the rise. The 3.6s duration, sun r=30, moon scale 38/27, anchors, and pause policy are unchanged.

ARMING detail is exactly `接收啟動中`. Title `啟動中`, eyebrow `STARTING`, and hint `Starting · Not ready` are unchanged. The A connecting hint is blank, and the existing blank-target fade removes it without reserved space. Shared `phases.connecting.detail` stays `模擬發送端已選取此裝置。` for C. Title `正在連接` and the C/D hint `Connecting · Please wait` stay.

The C gather target is now `state.broadcast && state.phase !== 'wifi-paused'`. `big-standby-c-particle-assembly.js` is byte-identical: 6s gather, 8s disperse, and the owned `particleTime` clock. FROZEN particle policy is unchanged. WIFI_PAUSED still keeps broadcast intent and does not start the 7.5s failure recovery.

C and D WIFI_PAUSED use the existing warning glyph at `#ff5757` on the shared dock symbol. The control stays `role=switch`, `aria-checked=true`, label `Wi-Fi 已關閉，停止廣播`. A stays stop or play from intent. Click still calls `toggleBroadcast()` and returns to READY with broadcast false. This is not the FROZEN info action.

Pre-edit Node RED was 4: the long ARMING detail, the A connecting sentence, C Wi-Fi symbol `stop`, and Wi-Fi `setBroadcast(true)`. The fixture now expects 10 groups, including A burnY rise at 667x375, 2560x720, and 390x844. TARGETED_STATE_MOTION_5ITEMS_PARENT_PASS. Parent browser: all 10 groups of `verify-v41-native-phases-ui.js` (`d38e1847...`) PASS in 99,394ms, 0 failures, 0 console/page errors, 0 blocked writes, votes hash `7c5f032...` intact. HTTPS A celestial burnY check (`particleTime = 1700`) over 12 viewports: `brokenCount=0`, exact anchor, active 3.6s transition (`mid >= 4`, `anims >= 2`). 4 HTTPS PNGs inspected: A ARMING, A CONNECTING, C WIFI_PAUSED, D WIFI_PAUSED. Android, device, power, and OLED stay OPEN.

### A arming hint blank, C status no-wrap, and C particle samples (TARGETED_C_PARTICLE_SAMPLES_PARENT_PASS, 2026-11-01)
1. A arming bottom hint: `.v-A[data-phase="arming"] .connection-hint:not(.a-copy-exiting)` set to `display: none`; `render()` passes blank `''` for A `arming` like `off` and `connecting`; `phases.arming.detail: '接收啟動中'` retained for C and simulation tools.
2. C status word wrap fix: `.stage.v-C .c-standby-word` set to `white-space: nowrap; font-size: 20px;` across aligned and stacked layouts. In stacked mode, plate width is `max-content` and hint width adapts via `--c-stacked-plate-width`. `layoutCCornerInformation()` measures text range bounding rects to detect leftward overflow in right-aligned flex containers, keeping `#c-status-word` on 1 line across all viewports with zero overlap against `.connection-hint`.
3. C particle state samples: `big-standby-c-particle-assembly.js` preserved byte-identical. Added 6 distinct particle formation presets (`auto`, `cloud`, `x-mark`, `arming-vortex`, `connecting-tunnel`, `wifi-ripple`, `frozen-lattice`) accessible via `#c-particle-mode` in gyro settings, `#c-particle-picker` in review tools, and URL parameter `?particle=` or `?cParticle=`. Formations project deterministically and freeze when `state.motion.paused` is true.
Node verification PASS: `node --check`, `verify-native-phases.cjs`, `verify-c-assembly.cjs`, `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`. Parent actual browser verified all 12 groups of `verify-v41-native-phases-ui.js` (`3013baea...`) PASS in 111,094ms (0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact), including 56 C status word single-line checks (8 sizes x 7 phases) with zero overlap against hint. Independent parent HTTPS verification on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` visually inspected 6 HTTPS PNGs (`mirax-v41-A-ARMING-blank-hint.png`, `mirax-v41-C-narrow-WAIT_WIFI-360.png`, `mirax-v41-C-particle-arming-vortex.png`, `mirax-v41-C-particle-connecting-tunnel.png`, `mirax-v41-C-particle-wifi-ripple.png`, `mirax-v41-C-particle-frozen-lattice.png`).

### Custom broadcast name input (TARGETED_CUSTOM_DEVICE_NAME_PARENT_PASS, 2026-11-01)
1. `#settings` gained a `廣播名稱` fieldset with `#device-name-input` (text, `maxlength="40"`, `autocomplete="off"`, `spellcheck="false"`) and `#device-name-help`. `#settings input[type="text"], #theme-select` now share the 44px field box; `:focus-visible` gets a white 2px outline.
2. `readDeviceName()` reads `?name=` (alias `?deviceName=`), trims, clamps to 40 characters, and falls back to `MIRAX · 客廳`. `state.deviceName` is seeded from it and re-read in the `popstate` handler.
3. `escapeHtml()` escapes `&`, `<`, `>`, `"`, `'`. `render()` pushes `state.deviceName` into every `.device-name`, C `.connection-title` (still read from the synced `.device-name` DOM node so `verify-c-log-styles.js` keeps copying one source), the A `.receiver-name` hint, and D `.e-status-message[data-status="waiting"] .e-status-phrase`; the settings input is synced only while it is not focused so typing is never overwritten. `input` calls `act('change-device-name')`; `blur` on a blank value restores `MIRAX · 客廳` in both the input and state.
4. Fixtures: `verify-native-phases.cjs` adds 7 `readDeviceName()`/`escapeHtml()` VM checks (default, `name`, `deviceName` alias, whitespace-only fallback, 40-char clamp, markup preserved as text, full escape set). `verify-v41-native-phases-ui.js` adds Group 13 `custom broadcast name: URL init, cross-theme sync, live typing, escaping, and default restore` covering the default name in A/C/D, `?name=MIRAX%20%C2%B7%20%E6%9B%B8%E6%88%BF` in A/C/D, live typing `會議室大螢幕`, inert `<b>Test</b>`, and blank-blur restore.
5. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7), `verify-c-assembly.cjs` (60,831 checks), `verify-c-status.cjs`, `verify-v35-behavior.cjs` (715 shots), `verify-v36-status.cjs` (60 checks, 8 viewports), `verify-d-streams.cjs` (90 shots, 6,784 acceleration checks), `git diff --check` clean. Prototype `42d398a2...`, `verify-native-phases.cjs` `e2563ecd...`, `verify-v41-native-phases-ui.js` `fa0bc52c...`. This child ran no browser. Parent browser: all 13 groups of `verify-v41-native-phases-ui.js` (`fa0bc52c...`) PASS in 116,658ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` verified live settings input editing (`會議室投影主機`) and URL query parameter init (`臥室電視`) syncing across A, C, and D, and inspected 5 HTTPS PNGs (`mirax-v41-custom-name-settings.png`, `mirax-v41-custom-name-A-waiting.png`, `mirax-v41-custom-name-C-waiting.png`, `mirax-v41-custom-name-D-waiting.png`, `mirax-v41-custom-name-url-init.png`). TARGETED_CUSTOM_DEVICE_NAME_PARENT_PASS. Android, device, power, and OLED stay OPEN.


### Broadcast name persistence (TARGETED_DEVICE_NAME_PERSISTENCE_PARENT_PASS, 2026-11-01)
1. `STORAGE_BROADCAST_NAME = 'mirax_broadcast_name'` stores the custom receiver name in `localStorage`. `readDeviceName()` resolution order is `?name=` / `?deviceName=` -> stored value -> `MIRAX · 客廳`; both reads trim and clamp to 40 characters.
2. `saveDeviceName(name)` writes the trimmed, 40-character-clamped name. Empty, whitespace-only, and the default `MIRAX · 客廳` remove the entry instead of writing it, so clearing the field returns to a clean default state.
3. `#device-name-input` `input` persists after `act('change-device-name')`; `blur` on a blank value restores the default in the input, in state, and in storage. Storage access is wrapped in `try/catch` with `console.warn('Broadcast name storage unavailable', error)`, so private-mode or denied storage degrades to the old in-memory behavior.
4. `#device-name-help` now reads `Windows 投射與連線提示顯示的名稱；修改即時生效並儲存於瀏覽器。空白時還原為預設名稱。`
5. Fixtures: `verify-native-phases.cjs` adds section 6, 14 mock-`localStorage` VM checks (restore on load, URL beats storage, whitespace/corrupt values ignored, 40-char clamp on read and write, default and empty clear the entry, denied `getItem`/`setItem`/`removeItem` logged and swallowed). `verify-v41-native-phases-ui.js` Group 13 adds the persistence leg: type `臥室電視機` -> stored -> reload `?v=41` restores state, input, and every `.device-name` -> blank blur clears storage -> reload returns to `MIRAX · 客廳` with a null storage entry.
6. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7, broadcast name persistence 14), `verify-c-assembly.cjs` (60,831), `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`, `git diff --check` clean.
7. Hash: prototype `380e2382078d00ddf6d2d9134fedc39d04f5a8862fb9caa2898153c13aeb07f1`, `verify-native-phases.cjs` `3b39d493891b8a9c0206e2ba3ea0f64febe3fc6add73fdc085914a7c557ff0b1`, `verify-v41-native-phases-ui.js` `bb2940d4629aba0550b9f7ecca05da4066010f76664f25153f2f5e79ba66b307`. Parent actual browser PASS: all 13 groups of `verify-v41-native-phases-ui.js` (`bb2940d4...`) in 107,351ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` set custom name `客廳大型顯示器`, verified reload persistence from `localStorage` with no URL query parameter and clean removal after clearing the input, and inspected 2 HTTPS PNGs (`mirax-v41-persisted-reload.png`, `mirax-v41-persisted-reset-reload.png`). Android, device, power, and OLED stay OPEN.

### Long device name containment fix (TARGETED_PORTRAIT_LONG_NAME_PARENT_PASS, 2026-11-01)
1. User report: `直屏時，過長的裝置名稱會有顯示問題`. Theme A let a long name overflow both screen edges; theme D ran the receiver across the bottom into the shared broadcast dock.
2. `.device` now carries `width: max-content` with `max-width: min(88vw, calc(100vw - var(--edge-left) - var(--edge-right) - 24px))` and `box-sizing: border-box`, so the A receiver row can never widen past the safe insets.
3. `.device-name` now carries `display: block; max-width: 100%; overflow: hidden; text-overflow: ellipsis` next to the existing `white-space: nowrap`, so an overlong name clips to one ellipsised line instead of scrolling the stage.
4. `.stage.v-D .device` landscape bound changed from `calc(100% - var(--edge-left) - var(--edge-right))` to `calc(50vw - var(--edge-left) - 54px)` with `box-sizing: border-box`, keeping the receiver to the left of the 84px dock that sits at `left: 50%`.
5. The D stacking media query widened from `@media (max-width: 400px)` to `@media (max-width: 440px), (orientation: portrait) and (max-width: 600px)`, setting `bottom: calc(max(12px, env(safe-area-inset-bottom)) + 84px + 12px)` and `max-width: calc(100vw - var(--edge-left) - var(--edge-right) - 24px)`. `.stage.v-D .device-name` gained the same clip properties.
6. Fixtures: `verify-v41-native-phases-ui.js` Group 13 adds a portrait containment leg with the exact user string `Renathan's Fold 5555555555555555555` at `390x844` and `412x915`. Theme A must keep `.device-name` inside the viewport with one line and no document horizontal overflow; theme D must place `.device.bottom <= dock.top` with no dock overlap and stay inside the viewport. `verify-d-streams.cjs` D receiver assertions realigned to the new landscape bound, the widened media query, and the `.device-name` clip rule.
7. Node PASS: `node --check verify-v41-native-phases-ui.js`, `verify-native-phases.cjs` (motion 49, particle 7, celestial 23, custom device name 7, persistence 14), `verify-c-assembly.cjs` (60,831), `verify-c-status.cjs`, `verify-v35-behavior.cjs`, `verify-v36-status.cjs`, `verify-d-streams.cjs`, `git diff --check` clean. This child ran no browser. Parent actual browser PASS: all 13 groups of `verify-v41-native-phases-ui.js` (`c28bd61b...`) in 126,010ms, 0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes hash `7c5f032...` intact. Independent HTTPS run on `https://desktop-2jhjlhh.tail90ee25.ts.net:8443` at 390x844 and 412x915 with the exact user string `Renathan's Fold 5555555555555555555`: theme A `.device-name` contained in viewport, single line, ellipsised `...`, 0 horizontal overflow; theme D `.device.bottom <= dock.top` at both sizes, 0 overlap and 0 collision. 4 HTTPS PNGs inspected: `mirax-v41-portrait-long-name-A-390.png`, `mirax-v41-portrait-long-name-D-390.png`, `mirax-v41-portrait-long-name-A-412.png`, `mirax-v41-portrait-long-name-D-412.png`. Android, device, power, and OLED stay OPEN.
### C FROZEN adopts proposal 06 (TARGETED_C_FROZEN_LATTICE_ADOPTED_PARENT_PASS, 2026-11-01)
1. `cParticleSampleProject(mode, ...)` keeps `auto` as the only state-aware branch: `state.phase === 'frozen'` delegates to the `frozen-lattice` (06 / Locked Stasis Lattice) projection, every other phase keeps `cParticleAssembly.project(...)`. Explicit sample modes are unchanged.
2. `drawParticles()` now routes every particle through `cParticleSampleProject(state.cParticleMode || 'auto', ...)`, so the default `auto` mode renders the 06 stasis lattice on `FROZEN` (receiver unavailable) and keeps the existing assembly/free-flight behaviour on `READY`, `WIFI_PAUSED`, `ARMING`, `ADVERTISING`, `CONNECTING`, `CONNECTED`.
3. `#c-particle-mode` and `#c-particle-picker` option copy now reads `依連線狀態（預設 · 接收不可用套用 06 靜滯晶格）` and `06 / Locked Stasis Lattice（靜滯晶格 · 已套用至接收不可用）`.
4. `big-standby-c-particle-assembly.js` is byte-identical; FROZEN still animates (no glitch, no 7.5s recovery) and `state.motion.paused` still freezes it.
5. Fixtures: `verify-native-phases.cjs` asserts `auto` equals `frozen-lattice` coordinates on `frozen` and equals `cParticleAssembly.project(...)` outside it; `verify-v41-native-phases-ui.js` Group 12 asserts `auto` in `FROZEN` matches `cParticleSampleProject('frozen-lattice', 3, 400, 300, frame)`.
6. Parent actual browser: `verify-v41-native-phases-ui.js` (`e657cb86...`) all 13 groups PASS in 114,842ms (0 failures, 0 console/page errors, 0 blocked writes, 1 owned context closed, votes `7c5f032...` intact). Live HTTPS `?variant=C&phase=FROZEN&v=41` in default `auto` renders proposal 06 frozen-lattice coordinates matching `cParticleSampleProject('frozen-lattice')`; `?variant=C&phase=READY&v=41` stays free-flight. 2 HTTPS PNGs inspected: `mirax-v41-C-frozen-auto-lattice.png`, `mirax-v41-C-ready-auto-cloud.png`.

### 2026-11-01 resumed specification interview: Q8-Q9 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q8 (Navigation Seams and Homepage Hierarchy)**:
   - **User Decision**: `1. 採用建議，2. 抽屜可以展開以前的側邊攔，3. 為此，舊版首頁的歷史功能需要額外抽取出。`
   - **Unmistakable Decisions**:
     - Cold Start: Non-projecting startup defaults unconditionally to Big Standby; no new "remember-last-home" preference is created.
     - Navigation Drawer: The left handle opens/reuses the existing native sidebar navigation drawer (`MainActivity.kt:2036-2046`) rather than shipping prototype QA controls, providing access to both Big Standby and the Legacy Dashboard.
     - Independent History Access: The connection-history feature must be accessible independently of the Legacy Dashboard.
     - Session & Broadcast Continuity: Switching UI presentation modes does not modify background advertising or active session state.
   - **Codebase Context & Verified Facts**:
     - `MainActivity.kt:927-929` provides an existing shortcut to history from the Legacy Dashboard (`openPage(Page.HISTORY)`).
     - `Page.HISTORY` and its screen renderer `buildHistory()` already exist independently in the native codebase (`MainActivity.kt:839`, `MainActivity.kt:995-1070`), with `parentOf(Page.HISTORY) == Page.DASHBOARD` (`MainActivity.kt:328-335`).
     - The existing native sidebar (`MainActivity.kt:2036-2046`) currently omits a history entry. The user requirement to "extract" signifies providing independent navigation access/routing, not reimplementing the history page, storage, or session logging. Native extraction remains UNEXECUTED.
   - **Items Remaining OPEN**:
     - Exact history menu placement in the navigation drawer or top bar, retention vs deprecation of the Legacy Dashboard shortcut, Back key target policy from Big Standby (existing Back at `MainActivity.kt:390-416` handles drawer close, parent navigation, and two-press exit confirmation), and return destinations remain OPEN for subsequent questions.

2. **Q9 (Design Fidelity and Motion Scope)**:
   - **User Decision**: `採用建議，但是按照目前所見及所得，我指的是D有許多已經不再使用的主題，但我現在只需要default你給我看的即可`
   - **Unmistakable Decisions**:
     - Fidelity Process: Approved faithful porting process for themes A, C, and D at equivalent viewports, preserving existing responsive behavior, name ellipsis, and C single-line status without universal pixel tolerance assumptions.
     - Theme D Port Scope: Scoped strictly to the currently approved default motion. Historical Theme D study variants are excluded from the native implementation port.
     - Source Fact: The prototype default Theme D motion fallback is identified as `study-118-section-extrusion` (`design/big-standby-prototype.html:1145-1147`).
     - Prototype Scope: The user did NOT authorize deleting prototype studies, ratings, notes, or research files from the repository.
     - Approved States: C FROZEN proposal 06 (Locked Stasis Lattice) remains approved. No new font or pixel tolerance rules are invented.

### 2026-11-01 specification interview: Q10-Q11 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q10 (Navigation Drawer History Entry and Shortcut Preservation)**:
   - **User Decision**: `是，採用建議 (Recommended)`
   - **Closed Decisions**:
     - Existing native sidebar drawer (`MainActivity.kt:2036-2046`) gains a top-level `連線歷程` (Connection History) item, providing unified access from both Big Standby and the Legacy Dashboard.
     - Direct reuse of existing `Page.HISTORY` and backing connection run history; no duplicated data storage, models, or parallel screens.
     - Existing Legacy Dashboard connection history shortcut (`MainActivity.kt:927-929`) is preserved as-is.

2. **Q11 (Big Standby Back-Press Leaving Policy)**:
   - **User Decision**: `是，沿用兩次返回 (Recommended)`
   - **Closed Decisions**:
     - When not actively receiving projection and with drawer, dialogs, and keyboard closed, Back key on Big Standby reuses the native two-press exit confirmation flow (`MainActivity.kt:390-416`): first press displays Toast prompt, second press within 2000ms exits/leaves the application screen.
     - Back on Big Standby does NOT navigate back to Legacy Dashboard and does NOT toggle or terminate advertising state directly.
     - Background broadcast duration remains an uncoupled, separate policy; leaving UI screen makes no promise to terminate or retain background service indefinitely.

3. **Items Remaining OPEN & Unchanged Scope**:
   - **Open Specifications**:
     - Origin-sensitive return destinations for history/settings and switching-home back-stack navigation semantics.
     - Custom media persistence, lifecycle, and runtime error recovery behavior.
     - Background broadcast lifespan and duration limit policy.
     - Remaining native port acceptance gates and error-handling details.
   - **Unchanged Baseline**: Prior decisions Q1–Q9, Themes A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q12-Q13 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q12 (Navigation Back-Stack and Origin-Sensitive Return)**:
   - **User Decision**: `是，採用建議 (Recommended)`
   - **Closed Decisions**:
     - Origin-Sensitive Returns: History and settings pages entered from either homepage (Big Standby or Legacy Dashboard) return step-by-step to that originating homepage.
     - Homepage Switching: Selecting an alternative home from the sidebar replaces the current navigation root; the two homepages do not stack on top of each other.
     - Double-Back Boundary: The approved double-Back exit policy applies exclusively once back at the Big Standby root, never as an exit shortcut directly from child history or settings views.
     - Future Seam Note: Existing native fixed parent mapping (`parentOf(HISTORY) == Page.DASHBOARD` at `MainActivity.kt:328-335`) and settings root logic must be modified in the future to fulfill this spec; native implementation remains UNEXECUTED.

2. **Q13 (Custom Media Background Persistence)**:
   - **User Decision**: `是，重啟後保留 (Recommended)`
   - **Closed Decisions**:
     - Background Persistence: Selected image/video/GIF media persists across normal application restarts until explicitly replaced or removed by the user.
     - Single Media Scope: Supports strictly one media file at a time; playlists and cloud synchronization are excluded.
   - **Unsettled / Open Details**:
     - Storage policy: internal copy-import vs retained external URI reference permissions.
     - Error handling: handling of source file invalidation, deletion, or decoding failure.
     - Playback resumption: paused timeline restoration vs restart-from-beginning behavior.
     - Formats, audio track handling, aspect crop rules, and file size limits.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Media storage/error policy and resume timeline; background broadcast duration limits; remaining native receiver error states and acceptance criteria.
   - **Unchanged Baseline**: Prior decisions Q1–Q11, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q14-Q15 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q14 (Custom Media Cropper and Playback Timeline)**:
   - **User Decision**: `自訂媒體需要一個簡單的裁切器。播放進度總是從頭，開關側邊攔不重設`
   - **Closed Decisions**:
     - Cropper Requirement: Custom media requires a simple cropper (new native product requirement; native implementation remains UNEXECUTED).
     - Timeline Policy: Playback always restarts from the beginning when started or resumed; no saved within-session or persistent resume timestamp (supersedes proposed within-session resume).
     - Drawer Interaction: Merely opening or closing the sidebar drawer does not reset playback progress.
     - Unaccepted Bundle Scope: Audio muting, GIF loop bounds, and crop-fill behavior were not explicitly confirmed and are not accepted by default.
     - Lifecycle Motion: Existing Q4 lifecycle pausing continues to apply.

2. **Q15 (Private Copy Storage and Degradation Protection)**:
   - **User Decision**: `是，保存副本與保護 (Recommended)`
   - **Closed Decisions**:
     - Private Copy Storage: The active selected custom media is stored as an App-private copy to ensure deletion or renaming of the external original does not invalidate the imported background (extra internal storage acknowledged).
     - Safe Transactional Replacement: Existing background is replaced only after a new media import and decoding are validated usable; previous active copy is pruned upon successful replacement. Temporary transactional staging is permitted.
     - Safe Degradation: If a new import fails verification/decoding, the previous background is retained with an error reason. If an already-stored media becomes unreadable or corrupted, the system falls back gracefully to the currently selected A/C/D theme with an error notice, without black screen or connection state disruption.
     - Execution Boundary: No claims of implemented decoders, format parsers, or re-encoders.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Cropper definition (spatial framing vs video time trimming, aspect/fold adaptation, file/resolution bounds); audio track, loop, and fill specifics; background idle broadcast policy (ADVERTISING timeout source); native acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q13, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), and C FROZEN proposal 06 (Locked Stasis Lattice) remain strictly unchanged.

### 2026-11-01 specification interview: Q16-Q17 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q16 (Background Framing vs Cropping Definition)**:
   - **User Decision**: `是，只做背景取景 (Recommended)`
   - **Closed Decisions**:
     - Spatial Background Framing: Custom media (images/videos/GIF) supports spatial background framing via interactive pan, zoom, visible-area preview, and confirm/cancel actions.
     - Confirmation & Cancellation: Confirmation applies the new spatial viewport framing; cancellation preserves the existing background intact.
     - Scope Boundary: Modifies only the displayed framing geometry (scale and viewport offset). Does NOT perform content re-encoding/re-compression, video duration trimming (start/end points), or visual filters.
     - Decision Meaning: Closes the definition of "simple cropper" as non-destructive spatial framing; does NOT constitute approval of any implemented native control (native implementation remains UNEXECUTED).
     - Still Open: Exact viewport adaptation across portrait, landscape, and foldable posture transitions remains OPEN.

2. **Q17 (Idle Advertising Auto-Stop Policy)**:
   - **User Decision**: `是，沿用現行規則 (Recommended)`
   - **Closed Decisions**:
     - Preserve Existing Rule: Preserves the current idle advertising auto-stop behavior (default 1 minute, user-configurable 1–180 minutes; source: `BroadcastAutoStop.kt:29-44`).
     - Phase Scoping: Only `ScreenPhase.ADVERTISING` counts toward the idle countdown; `ARMING`, `CONNECTING`, and active projection do not run or expire this timer.
     - UI Independence: Switching between Big Standby and Legacy Dashboard or opening/closing the sidebar drawer does not reset the running countdown.
     - App Background Boundary: Transitioning the app to the background does not stop or extend the existing timer deadline.
     - Timeout Scope: Expiration disables advertising broadcast; it does NOT trigger screen dimming or app-initiated sleep. Foreground Big Standby keepawake/motion continues per Q4/Q6. OS/platform lifecycle limitations are not overridden.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Specifications**: Orientation and fold posture framing adaptation; custom media audio track handling and loop policies (not explicitly confirmed earlier); theme selection preference and initial default; receiver error presentation and native verification acceptance criteria.
   - **Unchanged Baseline**: Prior decisions Q1–Q15, Theme A/C fidelity, Theme D default-only motion (`study-118-section-extrusion`), C FROZEN proposal 06, media restart timeline (Q14), and private copy storage (Q15) remain strictly unchanged.

### 2026-11-01 specification interview: Q18-Q19 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q18 (Multi-Posture Background Framing Adaptation)**:
   - **User Decision**: `是，共用一組取景 (Recommended)`
   - **Closed Decisions**:
     - Shared Framing Geometry: A single shared spatial framing configuration applies across portrait, landscape, and foldable inner/outer displays; separate per-posture presets are not required.
     - Center & Aspect Fill: Resizing prioritizes the same framing center and aspect-preserving fill with clamped repositioning/cropping to prevent stretching or uncovered screen gaps.
     - User Control: No automatic cropper prompt on layout resize; users manually adjust framing when desired.
     - Visual Acceptance: User explicitly accepts that visible content margins vary across differing aspect ratios rather than demanding identical revealed boundaries.

2. **Q19 (Custom Media Audio, Looping, and Control Scope)**:
   - **User Decision**: `是，無聲循環 (Recommended)`
   - **Closed Decisions**:
     - Ambient Muting & Looping: Custom background videos are strictly muted and looping; GIFs continuously loop regardless of single-play headers; still images remain static.
     - UI Surface Scoping: No player controls, seek bars, or volume sliders on the standby stage.
     - Audio Boundary: Applies exclusively to the ambient standby background; received stream audio during projection is unaffected.
     - Retained Continuity: Playback restarts from the beginning upon actual start/resume per Q14; opening/closing the sidebar drawer does not reset playback. Native player implementation remains UNEXECUTED.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Initial A/C/D theme default and persistent theme selection memory (Theme D default motion identity is separate); truthful FROZEN info presentation and compatibility-settings navigation; remaining resource/format constraints and native acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q17, Theme A/C fidelity, Theme D default motion (`study-118-section-extrusion`), C FROZEN proposal 06, Q4 ambient motion, Q14 restart timeline, Q15 private copy protection, and Q17 idle timer remain strictly unchanged.

### 2026-11-01 specification interview: Q20-Q21 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q20 (Initial Default Theme and Selection Persistence)**:
   - **User Decision**: `首次 C，之後記住選擇`
   - **Closed Decisions**:
     - Initial Launch Default: First cold start with no stored theme preference defaults to Theme C (supersedes previous proposed Theme A default).
     - Theme Persistence: Subsequent selections between A, C, or D are persisted across application restarts.
     - Media Independence: Imported custom background media and framing geometry remain preserved independently across theme changes.
     - Theme D Scoping: Theme D strictly includes the approved default motion (`study-118-section-extrusion`); historical study variants are excluded.

2. **Q21 (Compatibility Settings Page Removal and Precedence Correction)**:
   - **User Decision Sequence**: Initial selection `是，加入設定入口 (Recommended)` was immediately superseded and corrected by explicit user instruction: `不，相容性設定整個頁面砍掉，那裏面的文本內容過於陳舊`.
   - **Controlling Precedence & Closed Decisions**:
     - Complete Removal from Product Scope: The entire Compatibility settings page (`Page.COMPATIBILITY`) and its obsolete text are removed from future product scope (including sidebar, Legacy Dashboard, and proposed FROZEN navigation routes), not merely hidden or renamed.
     - Proposal Superseded: The previously proposed FROZEN compatibility-settings navigation link is REJECTED/SUPERSEDED.
     - Spec Boundary (UNEXECUTED): Removal is an unexecuted product specification decision; native code, underlying permission checks, WFD-owner logic, privilege functions, and diagnostic hooks are NOT deleted. No replacement instruction page or alternative permission destination is invented.
     - FROZEN Presentation: Existing FROZEN truthful info dialog and motion baseline remain intact; final actionable content and handling remain open pending functional source audit.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Final FROZEN actionable info content (without compatibility routing); native measured performance, media capability bounds, and acceptance test gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q19, Theme A/C fidelity, Theme D default motion (`study-118-section-extrusion`), C FROZEN proposal 06, Q4 ambient motion, Q14 restart timeline, Q15 private copy, Q17 idle timer, Q18 shared framing, and Q19 muted looping remain strictly unchanged.

### 2026-11-01 scope update: native FAILED implementation required (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Native FAILED Requirement**:
   - **User Instruction**: `另外，FAILED模式我知道在原本的設計裡沒有，所以在這次需要補上相關實現。`
   - **Closed Requirement**: The native Android port MUST implement a genuine native FAILED presentation/mode; it is no longer excluded as a permanently prototype-only simulator (supersedes earlier product scoping that omitted native FAILED functionality).
   - **Architecture & Implementation Status**: Current native `ScreenPhase` retains seven states; an eighth enum value or alternative presentation architecture has not yet been selected or approved by the user. No native code or model changes have been executed.
   - **Prototype Simulator Boundary**: The prototype 7.5s active-ms countdown is strictly visual reference behavior, NOT approved network reconnect, socket reset, or service recovery logic. The prototype timer must not drive native network reconnection, socket resets, or service recovery. The native FAILED visual lifetime remains undecided.

2. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open for Next Interview**: FAILED trigger classification, source of truthful error reasons, priority relative to new connections / `WIFI_PAUSED` / `FROZEN`, visible lifetime / dismissal, and recovery action handling.
   - **Unchanged Baseline**: Prior decisions Q1–Q21 remain strictly unchanged, including initial Theme C default, private copied media/framing/muted looping/restart, full removal of the Compatibility settings page (latest Q21 correction; no compatibility route), and C FROZEN proposal 06.

### 2026-11-01 specification interview: Q22-Q23 (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

1. **Q22 (Native FAILED Scope Boundary and Pre-Play Requirement)**:
   - **User Decision**: `建立與協商失敗或逾時才需要，因為如果是投放中出錯，是沒機會在Big Standby顯示錯誤的。`
   - **Closed Decisions**:
     - Pre-Play Scope Only: Native Big Standby FAILED presentation is required EXCLUSIVELY for genuine connection establishment or RTSP negotiation failure/timeout occurring BEFORE projection playback begins.
     - Post-Play Errors Excluded: User specifically rejects routing post-PLAY streaming, decoder errors, or playing-session disconnects into this Standby FAILED mode; existing projection-view error handling is left intact without invasive decoder-error routing. (User's reasoning is recorded as product rationale, not as universal proof regarding Standby display lifecycle).
     - Non-Failure Exclusions: Voluntary user cancellation and normal session teardown do not trigger FAILED. System Wi-Fi disablement remains `WIFI_PAUSED`; device/P2P unavailability remains `FROZEN`.
     - Visual Assets: Retains A/C/D prototype visual failure styling for verified pre-play failures.
   - **Engineering Implications**: Per `SessionTypes.kt:595-605`, `succeeded` indicates playback started; `FinishConnectionRun(false)` alone is insufficient to discriminate real failure from voluntary cancellation. Pre-play failure handling requires discrete reason classification and unique attempt identity rather than relying on session history flags or generic drop events. Native implementation remains UNEXECUTED.

2. **Q23 (FAILED Visual Lifetime and Dismissal Semantics)**:
   - **User Decision**: `是，7.5 秒後收起 (Recommended)`
   - **Closed Decisions**:
     - Visible Foreground Duration: Visual FAILED presentation displays for 7.5 seconds of active foreground time; backgrounding or hiding the app pauses this countdown.
     - Pure Visual Dismissal: Expiration at 7.5 seconds strictly dismisses the failure presentation and returns the UI to the CURRENT underlying state; it never forces an `ADVERTISING` state transition.
     - No Service Side-Effects: Dismissal triggers no broadcast toggles, socket resets, or reconnect attempts, and does not restart or extend the idle advertising timer.
     - Event Preemption: Any new incoming connection attempt, active projection, explicit stop, `WIFI_PAUSED`, or `FROZEN` immediately supersedes the active failure episode; stale timers or outdated attempt IDs cannot overwrite current state. 7.5 seconds provides no guaranteed network recovery.

3. **Items Remaining OPEN & Unchanged Baseline**:
   - **Open Next**: Formal specification assembly and final shared-understanding confirmation; platform media/codec limits, thermal/battery constraints, and native hardware acceptance gates.
   - **Unchanged Baseline**: Prior decisions Q1–Q21 remain strictly unchanged, including initial Theme C default, private copied media/framing/muted looping/restart, complete removal of the Compatibility settings page (with no replacement route or page), and C FROZEN proposal 06. Native code implementation and whether `ScreenPhase` adds an eighth state remain unexecuted and open.

### 2026-11-01 specification interview: Q24 confirmation (PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED)
*Status: PRODUCT DECISIONS / NATIVE IMPLEMENTATION UNEXECUTED. Not readiness or implementation authorization.*

- **User Decision**: `確認，只整理草稿 (Recommended)`
- **Consolidation Summary**: User formally confirmed the consolidated product specification decisions resulting from interviews Q1–Q23. The agreed scope is documented below as an unpublished GitHub Issue draft. Direct implementation, issue creation on GitHub, ADR drafting, and code modifications remain unauthorized and unexecuted.

## Big Standby Android port: final specification (2026-11-01)

*Status: product specification only. Publication pending parent verification of issue URL and label; nothing here is implementation authorization.*

### Issue Title

`feat(standby): add the native Big Standby homepage, formal themes, pre-play FAILED, media framing, and localization`

### Issue Body

## Problem Statement

Mirax opens on the Legacy Dashboard today and has no ambient standby identity. Between projection sessions the app shows a utilitarian control surface rather than a deliberate idle presence, and the three approved visual themes exist only as a web prototype that has never been rendered natively. Theme geometry, motion, typography and text behaviour on real devices are therefore unverified, and none of the prototype's hardware behaviour (frame pacing, power, thermal response, long-run OLED wear) has been measured on the target device.

Four product gaps follow:

- A connection that fails before playback begins has no distinct, truthful presentation. The native phase model reports availability, but a pre-play establishment or negotiation failure surfaces as nothing more than a generic drop, so a user cannot tell a real error from an ordinary cancellation.
- The obsolete Compatibility page is a static explanatory page whose prose is now considered outdated, and its routes still appear in navigation and compete with the truthful frozen-state explanation the product should offer. The page and those routes should be removed, while the approved small truthful FROZEN information dialog is ported as new behaviour rather than assumed to exist already.
- Custom standby media (image, video, GIF) is unsupported, so the idle surface cannot be personalised.
- The app already ships Traditional Chinese and English, but the standby content the prototype will bring is new text that is not yet part of that mechanism, so it risks being copied in as hardcoded strings and retrofitted later.

The prototype also cannot answer the questions that decide acceptance: what these themes cost in frames, power and heat, and whether they are safe to leave on for a long time.

## Solution

Big Standby becomes a new full-window, non-projecting cold-start home implemented natively, alongside the Legacy Dashboard, which is retained and maintained. Both homes are reached through the existing sidebar, which gains one shared top-level Connection History destination backed by the existing history records. Switching homes replaces the navigation root; settings and history return step by step to the home they were opened from; the root exit gesture confirms once and leaves the UI on a second press; and the exit gesture never touches the broadcast.

The three formal identities are 山霧 · 流動地景 (Landscape), 微粒 · 黑白場域 (Matter) and 硬線 · MIRAX 形構 (Hardline), carried as stable theme identities with a persisted preference and localized labels; the existing English design identities are noted rather than imposed as a new schema. 微粒 · 黑白場域 is the first-launch default and later choices persist, chosen from genuine app settings reached through the native sidebar and available from either home, with imported media preserved across theme changes. Only 微粒 · 黑白場域 uses the approved frozen lattice, and only while the real screen phase is frozen; 山霧 · 流動地景 and 硬線 · MIRAX 形構 keep their own approved unavailable visuals, and 硬線 · MIRAX 形構 carries only its approved default motion. Ambient motion, sensor parallax and OLED content-safety offsets belong to the rendered surface and are lifecycle-scoped, so hiding, backgrounding or projecting releases them.

The ambient surface presents real native session state and does not invent competing availability or connection truth: the existing seven native phases stay the baseline, and arming ends on real readiness. The frozen state offers an information action that is never a switch; Wi-Fi pause keeps the user's broadcast intent; the warning switch on 微粒 · 黑白場域 and 硬線 · MIRAX 形構 in Wi-Fi pause stops advertising when used. A separate failure episode is allowed without permanently banning a new phase value, and that modelling choice stays technical.

A new pre-play FAILED presentation covers genuine connection establishment or negotiation failure and timeout, classified from a real terminal cause plus the attempt identity, and shown for 7.5 seconds of time during which Big Standby is actually visible in the foreground. It is purely presentational: expiry returns to the current true state, and showing or dismissing it changes no network, broadcast or idle-clock behaviour. Whether this is an eighth phase value or a distinct presentation episode over the existing phases is left to implementation, provided the same behavioural contract holds.

Custom media is a single app-private copy with one shared spatial framing. Import is transactional: staging, usable decode, user confirmation, then swap and prune of the previous copy, with cancellation or error keeping the previous background and a notice. Stored-media corruption falls back to the selected theme with a truthful localized notice and no connection change.

The obsolete Compatibility page and its navigation routes are deleted outright, with no replacement page and no removal of the permission, privilege or wireless-display services behind them. New standby text joins the existing localization mechanism from the first slice instead of being copied from the prototype and retrofitted later: an explicit stored Traditional Chinese or English choice overrides the system, while follow-system resolves the primary system locale so a Traditional Chinese system gets Traditional Chinese and every other locale, Simplified Chinese included, gets English.

Hardware behaviour and acceptance stay open until measured: frame pacing, power, thermal response, codec and format limits, decoded-memory bounds and long-run OLED behaviour are pending measurements, and no numeric frame-rate target is set by this specification.

## User Stories

1. As a Mirax user, I want the app to open on Big Standby after a cold start, so that idle time feels like a designed standby surface rather than a settings screen.
2. As a Mirax user, I want the Legacy Dashboard kept and maintained, so that the controls I already rely on stay available.
3. As a Mirax user, I want 微粒 · 黑白場域 (Matter) selected on first launch, so that the intended first impression is the default.
4. As a Mirax user, I want my later theme choice remembered as a stable identity, so that restarts and language changes never silently swap my theme.
5. As a Mirax user, I want to open the existing sidebar from the left handle, so that navigation behaves the same as elsewhere in the app.
6. As a Mirax user, I want sidebar switching between the two homes to replace the home, so that going back never walks through a stack of homes.
7. As a Mirax user, I want one shared Connection History entry reachable from either home and the existing Legacy Dashboard shortcut to keep working, so that familiar destinations stay one step away and unchanged.
8. As a Mirax user, I want settings and history to return step by step to the home I opened them from, so that going back means what I expect.
9. As a Mirax user, I want overlays, dialogs and the keyboard handled before the exit gesture, so that it closes them instead of leaving the app.
10. As a Mirax user, I want a root exit gesture to confirm once and a second one within two seconds to leave the UI, so that I cannot exit by accident.
11. As a Mirax user, I want that gesture to never toggle the broadcast, so that navigation cannot silently stop receiving.
12. As a Mirax user, I want switching homes to leave the real connection and its idle deadline alone, so that browsing the UI cannot disturb a live connection or stretch the advertising countdown.
13. As a Mirax user, I want the three formal themes rendered faithfully to the approved prototype, so that what ships is what I approved.
14. As a Mirax user, I want the receiver name to truncate with an intentional ellipsis and the 微粒 · 黑白場域 status to keep its single-line 20px source reference, so that the approved hierarchy survives real text.
15. As a Mirax user, I want layout and text bounds to respect safe insets and measured text, and to re-measure when language or configuration changes, so that nothing clips on narrow, notched or folded screens.
16. As a Mirax user, I want the retained fade and dynamic behaviour preserved, so that the surface stays alive without changing character.
17. As a Mirax user, I want any font, responsive, spacing or motion deviation to need my explicit approval, so that no unilateral pixel concession or shrink is applied.
18. As a Mirax user, I want 硬線 · MIRAX 形構 to carry only its approved default motion, so that no study variant appears in the product.
19. As a Mirax user, I want 微粒 · 黑白場域 to use the approved frozen lattice only while the real screen phase is frozen, its other states to keep their approved assembly behaviour, and the ambient layer to keep its parallax and OLED content-safety offsets, so that no unapproved state appears and the other two themes keep their own unavailable visuals.
20. As a Mirax user, I want the ambient surface driven by the real seven native phases with arming ending only on real readiness, and the frozen screen state kept distinct from an offer freeze, so that what I see reflects true availability and never a fake timer.
21. As a Mirax user, I want the frozen state to offer an info action instead of a switch, so that reading why it is frozen cannot change my broadcast.
22. As a Mirax user, I want Wi-Fi pause to keep my broadcast intent, so that receiving resumes when Wi-Fi returns.
23. As a Mirax user, I want the Wi-Fi pause warning switch on 微粒 · 黑白場域 and 硬線 · MIRAX 形構 to stop advertising when I use it, distinct from the frozen state's information action, so that each control does what its label says.
24. As a Mirax user, I want a small truthful information dialog for the frozen state, so that I get facts without invented causes.
25. As a Mirax user, I want a FAILED presentation only for genuine establishment or negotiation failure and timeout before playback, so that cancellations and normal endings are not reported as errors.
26. As a Mirax user, I want FAILED to require a real terminal reason and attempt identity, so that a generic drop or an unsuccessful run flag cannot fake an error.
27. As a Mirax user, I want the FAILED presentation visible for 7.5 seconds of time during which Big Standby is actually in the visible foreground, so that I can read it without it lingering.
28. As a Mirax user, I want that countdown paused while the app is hidden or another home is showing, so that leaving standby does not eat the message.
29. As a Mirax user, I want expiry to return me to the current true state instead of forcing advertising, so that the UI never lies about connection state.
30. As a Mirax user, I want a new attempt, projection, stop, Wi-Fi pause or frozen state to supersede a failure episode immediately, so that stale timers and old outcomes cannot overwrite newer reality.
31. As a Mirax user, I want showing, dismissing or expiring FAILED to cause no extra network reset, reconnect, broadcast toggle or idle-clock change, so that a message stays a message.
32. As a Mirax user, I want the obsolete Compatibility page and its text removed from every route, including the sidebar and Legacy Dashboard, with no replacement page, so that I am never shown stale guidance.
33. As a Mirax user, I want permission, privilege and wireless-display services untouched by that removal, so that existing capabilities keep working.
34. As a Mirax user, I want the screen kept awake in the foreground without app-driven dimming, so that standby stays visible.
35. As a Mirax user, I want ambient motion to ignore reduced-animation preferences without changing global settings, so that the theme I chose keeps moving.
36. As a Mirax user, I want standby rendering, custom media and sensors released when hidden, backgrounded or projecting, so that they cost nothing when I cannot see them.
37. As a Mirax user, I want the incoming stream decoder and audio unaffected by standby lifecycle, so that projection quality is never degraded.
38. As a Mirax user, I want the existing idle advertising stop preserved and counting only while advertising, so that arming, connecting and playing never expire it.
39. As a Mirax user, I want UI changes, backgrounding and failure episodes to leave the idle deadline alone, so that navigating cannot extend or reset the countdown.
40. As a Mirax user, I want idle expiry to stop advertising only, so that my foreground screen and its motion continue.
41. As a Mirax user, I want a single private copy of my chosen media that survives restarts independently of theme and tolerates deleting the original, so that personalisation is durable.
42. As a Mirax user, I want a new background applied only after it decodes usably and I confirm, so that a bad file cannot replace a good one.
43. As a Mirax user, I want cancelling or failing an import to keep my previous background, framing and a notice, so that I lose nothing.
44. As a Mirax user, I want a corrupt stored background to fall back to my selected theme with a truthful notice and no connection change, so that standby never becomes a dead screen.
45. As a Mirax user, I want to pan, zoom, preview, confirm or cancel the background framing, so that the visible area is under my control.
46. As a Mirax user, I want one framing shared across portrait, landscape and fold postures with centre priority and clamped aspect fill, so that the media never stretches or leaves gaps.
47. As a Mirax user, I want differing visible margins accepted instead of a cropper reopening on every resize, so that the layout does not fight me.
48. As a Mirax user, I want status overlays readable over any background, so that the surface stays legible.
49. As a Mirax user, I want background video muted and looping, GIFs looped even when encoded once and stills static, with received stream audio unaffected, so that ambient media never demands interaction.
50. As a Mirax user, I want playback to restart from the beginning when it actually starts or resumes, with sidebar open and close not resetting it and no player controls or seeking, so that timing stays predictable and the surface stays ambient.
51. As a Mirax user, I want every semantic string localized from the first slice through the existing language preference, so that no hardcoded text ever ships.
52. As a Mirax user, I want my explicit Traditional Chinese or English choice to override the system, and follow-system to give Traditional Chinese only when the primary system locale is Traditional Chinese and English otherwise, Simplified Chinese included, with dates, times, numbers and plurals following the resolved language, so that language behaviour is predictable.
53. As a Mirax user, I want keyboard, DPAD and TalkBack to reach every control and layouts measured in both languages at wide, narrow and fold widths without unapproved stretch or shrink, so that the surface is usable and honest about its text.
54. As a Mirax user, I want receiver and source names left exactly as reported and only clearly decorative brand art fixed, so that localization never rewrites someone else's device name.
55. As a Mirax user, I want to pick among the three formal themes from the genuine app settings reached through the native sidebar, from either home, so that I can change the standby look without losing the media I imported.

## Implementation Decisions

- **Two homes, one navigation root.** Big Standby is the cold-start root when nothing is projecting; the Legacy Dashboard stays a full, maintained destination. The sidebar gains one top-level Connection History entry that reuses the existing history page and records; the Legacy Dashboard shortcut is retained. Switching homes replaces the navigation root instead of pushing a home onto the stack, and settings or history resolve their parent from the home they were opened from.
- **One back dispatcher.** Overlays, dialogs and the keyboard consume the back gesture first; the root home confirms once and leaves the UI on a second gesture inside a two-second window. The dispatcher issues no session-control or broadcast command and never resets the idle deadline, and home switching issues none either; presentation-layer visibility updates when leaving or returning to Big Standby are expected, so the failure clock pauses.
- **Theme identity and choice.** Stable, non-localized identities `landscape`, `matter`, `hardline` with a persisted preference and localized labels and subtitles; first launch falls back to matter when nothing is stored; the preference is stored as identity, never as a display string. Choice lives in the genuine app settings destination reached through the native sidebar and available from either home, with imported media preserved across switches. Labels live in resources; no gallery, no new home toolbar, and no mandated shared theme abstraction or metadata schema.
- **Frozen and warning semantics.** Only 微粒 · 黑白場域 uses the approved frozen-lattice motion, and only in the frozen phase; its other states keep the approved assembly behaviour and other particle proposals stay excluded, while 山霧 · 流動地景 and 硬線 · MIRAX 形構 keep their own approved unavailable visuals. The frozen phase exposes an information action that opens a dialog and never toggles broadcasting, with wording derived from live system facts; that dialog is ported, not assumed native. Wi-Fi pause retains broadcast intent, and the warning switch on 微粒 · 黑白場域 and 硬線 · MIRAX 形構 in that phase stops advertising and is not repurposed as an info affordance.
- **Presentation boundary.** The standby surface is a function of resolved strings, a session snapshot and an optional media/framing snapshot; the existing seven native phases stay the real baseline and the surface invents no competing availability or connection truth. Arming reflects real readiness; no presentation-side timer may fabricate a ready or advertising state.
- **FAILED modelling is an implementation choice, not a spec fork.** An eighth phase value and a distinct presentation episode layered over the existing phases are both acceptable, but the chosen one must satisfy the same observations: classification, visibility accrual, dismissal, preemption and absence of side effects. The presentation may not be dropped pending that choice.
- **Pre-play classification.** The connection controller must expose a terminal pre-play outcome carrying a cause classification and the attempt identity. Failure requires a terminal establishment or negotiation cause; user cancellation, normal teardown, system Wi-Fi loss and receiver unavailability map elsewhere; a started-playback outcome excludes the episode. An unsuccessful run flag or a generic peer drop is insufficient evidence on its own. Existing projection error handling is not widened.
- **FAILED episode controller.** Time accrues only while Big Standby is actually visible in the foreground; a hidden app, background, projection or a different home showing accrues nothing, which may require new visibility reports. Whether the episode lives in the presentation layer or in the session, it must take a testable time source and a Big Standby-visible input at the public boundary that owns it, and either shape must expose the same observable behaviour through a public snapshot, effect or projection seam. Dismissal renders the latest session snapshot. Any newer attempt, projection start, explicit stop, Wi-Fi pause or frozen state ends the episode, and outcomes or timers carrying an older attempt identity are ignored. The controller has no handle on broadcast, socket or idle-clock state.
- **Media store.** One active asset slot in app-private storage plus its framing. Import stages the candidate, validates usable decoding, waits for confirmation, swaps, then prunes the previous copy; cancellation or error leaves the previous slot and framing untouched and surfaces a notice. Unreadable stored media degrades to the selected theme with a truthful localized notice. No multi-item library, no cloud, no trimming or re-encode.
- **Framing model.** Centre plus zoom resolved against the current surface size with centre-priority aspect fill and clamping, stored once and shared by all postures; the storage form and API are implementation detail. No posture presets, no automatic framing prompt on resize.
- **Ambient playback.** Video is muted and looping, GIFs are forced to loop, stills are static; sound is scoped to the ambient layer only. Playback restarts from the beginning on real start or resume, is paused by lifecycle, and exposes no controls, seeking or volume.
- **Lifecycle and power.** Foreground standby holds a keep-awake without app-driven dimming; ambient animation ignores reduced-motion preferences without mutating system settings; hiding, backgrounding or projecting stops standby rendering, media and sensor work. The projection decoder and its audio are outside this boundary.
- **Idle advertising untouched.** The existing idle stop keeps its default and configurable range, is consulted only in the advertising phase, and gains no new reset or stop hooks from the UI, home switching, backgrounding or the FAILED episode. Expiry stops advertising only.
- **Compatibility removal.** The page, its builder route and every navigation reference are deleted with no replacement destination, while session-level privilege, permission and wireless-display services remain intact.
- **Localization.** All new semantic strings, including theme labels, state and failure copy, dialogs, framing controls and accessibility descriptions, live in localized resources; identifiers stay untranslated. Resolution reuses the existing preference: an explicit stored Traditional Chinese or English choice overrides the system, and follow-system checks the primary system locale, yielding Traditional Chinese only for Traditional Chinese and English for every other locale, Simplified Chinese included. Measured text caches invalidate on locale or configuration change.
- **Planned technical spikes (gates, unexecuted).** Three bounded spikes precede their slices: renderer or host feasibility for the ambient layer under the fidelity constraints, responsive and text measurement across postures and locales, and media decode plus format, size and decoded-memory bounds. The renderer stays unselected, no whole-app framework rewrite or new dependency is approved here, and nothing in this specification claims a spike has run; a bounded engine or host feasibility decision is pending its gate.

## Testing Decisions

Behavioural tests only, at public boundaries: a test must exercise an observable contract (a snapshot, an emitted effect, a persisted value, a rendered and reachable control) and must fail when the user-visible behaviour regresses. Private helpers are not tested directly. Where time or visibility matters, a test drives a new time source and a Big Standby-visible input at the public boundary instead of a real sleep; fake connection-history prior art exists and is reused, but no session time or visibility input exists today, so that input is added rather than assumed.

Confirmed seams:

1. **Episode-owning public boundary.** Real native phases, pre-play failure classification, 7.5-second accumulation of Big Standby-visible time with pause on leaving standby, and proof that the episode emits no broadcast, reconnect or idle-clock effect. Whether the episode is owned by the session or the presentation layer, the same observable behaviour is asserted through the public snapshot, effect or projection seam.
2. **Real native UI operation.** Dual-home navigation, history reachability, back-stack behaviour, the exit gesture and zh/en switching, driven through the existing UI test approach against the real app.
3. **Public import and framing confirmation boundary.** Import then framing confirmation yields either a persisted asset plus framing or a retained previous boundary, plus playback and lifecycle behaviour inside the app. Visual fidelity, sensor behaviour and performance are measured separately on device, not asserted in these tests.

Existing prior art to follow: `MiraxSessionTest`, `AdvertisingStateTest`, `ActiveConnectionStateTest` and `TestConnectionHistory` for session-level behaviour, and `MiraxE2ETest` as the pattern for language and navigation UI operation. No Big Standby UI tests, exit-gesture tests, media tests or sensor tests exist today, and current custom standby media and sensor work is absent, so none of these may be described as already covered.

Acceptance checks, all unexecuted:

- [ ] Phase snapshots and effects for all seven native phases, including arming gated on real readiness.
- [ ] Pre-play classification: timeout, negotiation refusal, user cancel, normal end, post-play error exclusion, and an unsuccessful run flag alone producing no episode.
- [ ] FAILED episode: 7.5 seconds of visible accrual that pauses when standby leaves the visible foreground, dismissal to the current state, preemption by each newer event, stale attempt rejection, and zero broadcast, reconnect, socket or idle-clock effects.
- [ ] Navigation: cold-start home, root replacement on switch, origin-sensitive back, overlay-before-exit priority, two-step exit, and no broadcast toggle.
- [ ] Theme choice: the settings entry is reachable from either home through the native sidebar, the selection persists across restart and language change, and imported media survives a theme switch.
- [ ] History: one shared entry from both homes, identical records, retained Legacy Dashboard shortcut.
- [ ] Compatibility: no label, text or route remains anywhere; privilege, permission and wireless-display services still work.
- [ ] Media: transactional import success, cancel and error retention, restart persistence independent of theme, external original deletion tolerated, corruption fallback with a truthful notice and no connection change.
- [ ] Framing: single shared configuration, centre-priority clamped aspect fill without stretch or uncovered gaps across portrait, landscape and fold, no automatic prompt on resize.
- [ ] Playback: muted looping video, forced-loop GIF, static stills, restart from the beginning on real start and resume, sidebar open and close not resetting, lifecycle pause for video and GIF.
- [ ] Localization: the specified surfaces render in both languages, an explicit zh or English choice overrides the system, follow-system yields English for Simplified Chinese and other non-Traditional-Chinese primaries, theme identifiers stay stable across switches, dates and numbers are localized, and text re-measures without clipping or unapproved font scaling.
- [ ] Accessibility: labels and descriptions announced correctly, full keyboard and DPAD traversal, focus order intact in both languages.
- [ ] Device measurements pending before release: frame pacing, power draw, thermal behaviour, supported formats and file sizes, decoded-memory bounds, and long-run OLED behaviour. No numeric frame-rate target is asserted.

## Out of Scope

- Prototype review tooling: ratings, notes, inspectors, simulators, study galleries and manual connection triggers.
- Deleting or rewriting prototype archives, review votes or design study assets; existing prototype data is preserved.
- Post-play stream or decoder error presentation in Big Standby, and any expansion of decoder error handling.
- Video time trimming, re-encoding, filters, multi-item media libraries, playlists and cloud sync.
- Any "remember last home" preference, and any replacement page for the removed Compatibility settings.
- New privilege escalation, new wireless-display entry points, or changes to permission and WFD services.
- Guarantees about OS thermal or hardware throttling behaviour, and any unapproved numerical performance promise; a later measured budget agreed with the user is not excluded.
- Inventing translated theme subtitles; English subtitle text requires reviewed resources.
- A preselected renderer, a whole-app framework rewrite or approval of a new dependency, and any claim that the planned technical spikes have been performed; bounded engine or host feasibility decisions stay with their implementation gates.

## Further Notes

- The reference implementation is the local v41 web prototype; the shipped result must be judged against its geometry, typography, motion and state behaviour rather than against the prototype's own QA tooling.
- Naming and localization policy follows the repository's accepted ADR on standby theme names and localization: formal identities only, alphabetic prototype aliases restricted to provenance. The approved 硬線 · MIRAX 形構 default motion originates from `study-118-section-extrusion`, recorded here as provenance only.
- The prototype and the naming/localization ADR currently exist as local, uncommitted work; this issue does not assume they are published or present on the default branch, and it cites no immutable link.
- Every acceptance item above is unexecuted. Hardware, codec, memory, power, thermal and long-run OLED results must be measured and reviewed before any release claim.

### 2026-11-01 test seam confirmation record (publication pending)
*Status: TEST SEAMS APPROVED BY USER. Issue publication still PENDING; no implementation executed.*

- **User Decision**: `確認，採用這些接縫 (Recommended)`.
- **Approved Seams**:
  1. The public boundary that owns the failure episode, whether session or presentation, covering real phases, pre-play failure classification, and the 7.5-second Big Standby-visible time with pause on leaving standby, preemption and zero broadcast effects, without testing private functions.
  2. Real native App UI operation for dual-home navigation, history and back behaviour, plus zh/en locale switching, using the existing UI test approach.
  3. Public import and framing-confirmation boundary resolving to a persisted result or a retained previous boundary for new media, together with playback, framing and lifecycle behaviour in-app, and separate on-device visual, sensor and performance measurements.
- **Not Decided By This Confirmation**: whether pre-play failure becomes an eighth enum value or a presentation-layer episode, and which renderer the ambient layer uses. Both remain technical decisions.
- **Publication Status**: PENDING. The parent session owns publication to `Morishima-Yoru/Mirax` with the `ready-for-agent` label, and must verify the resulting issue URL and label before this record is treated as published. The Issue body above is the text to publish; the local body-file copy is outside the repository.
- **Historical Record**: Interview decisions Q1 Q24 and their original evidence remain above unchanged; the earlier draft body was replaced by the final specification headings without altering those records.

