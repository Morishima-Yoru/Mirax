# 大螢幕分欄：平行視界的官方對應

結論：平行視界在 Google 的對應是 Jetpack WindowManager 的 activity embedding。它以 Activity 為單位：寬時左右分欄，窄時後開的 Activity 疊在上面。但 Google 寫明它是給「難以改成單一 Activity 的多 Activity 舊 App」用的。Mirax 的設定頁在 `MainActivity` 裡，要用它得先把頁拆成 Activity。單一 Activity 的 Views App，官方的兩窗格元件是 `SlidingPaneLayout`，同樣是寬時並排、窄時疊上。推論：這是 Mirax 最小的官方做法，理由在〈對 Mirax 的推論〉。另有兩點會影響設計：依 README 的 420 dpi 換算，Fold 5 內螢幕約 829×690 dp，橫直都是 Medium 寬度；M3 Expressive 已不建議 navigation drawer。

詞彙以 [CONTEXT.md](../../CONTEXT.md) 為準。這份筆記講 App 自己的版面：「頁」是 App 的一頁，「視窗」是 App 視窗，「窗格」是分欄後的一欄。「畫面」仍只指 Windows 桌面那塊。

研究日期：2026-09-30。版本與日期照當天的頁面記錄。

## 依據

| 等級 | 內容 |
|------|------|
| Android 官方文件 | developer.android.com 的 activity embedding、Views 雙窗格、canonical layouts、window size classes、adaptive app quality、Android 15／16／17 行為變更，直接讀取 |
| AndroidX 原始碼與版本頁 | GitHub `androidx/androidx` 的 `androidx-main`，預設值以原始碼為準；`window`、`slidingpanelayout`、`preference`、`startup` 的 release notes |
| Material Components | GitHub 上 MDC-Android 的元件文件與 release，直接讀取 |
| Material Design 3 | m3.material.io 要 JavaScript，抓不到內文。引用的表格與句子來自搜尋引擎對該頁的摘錄，屬間接 |
| Samsung | developer.samsung.com 的 One UI 大螢幕設計頁，直接讀取 |
| Huawei | developer.huawei.com 的平行視界 codelab，只用來對照名詞 |
| 本 repo | README 的內螢幕 420 dpi；`app/build.gradle.kts`、`AndroidManifest.xml`、`MainActivity.kt` |

## activity embedding

[指南](https://developer.android.com/develop/ui/views/layout/activity-embedding)（2026-09-08 更新）：它把 App 的 task 視窗分給兩個 Activity，或同一個 Activity 的兩個實例。

### 定位

指南開頭的註記：

> Modern android development (MAD) uses a single-activity architecture based on Jetpack APIs, including Jetpack Compose. Activity embedding is designed for multiple-activity, legacy apps that can't be easily updated to MAD. Create new apps using MAD. Update your legacy apps to MAD whenever possible.

同頁說，它讓「based on multiple activities rather than fragments or view-based layouts such as `SlidingPaneLayout`」的 App 不改原始碼就能分欄。[Views 的 canonical layouts](https://developer.android.com/develop/ui/views/layout/canonical-layouts)（2026-09-22 更新）寫「activity embedding (for legacy apps)」。[Tier 2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2) 也分開寫：Compose App 用 `ListDetailPaneScaffold`，「legacy activity-based apps」用 activity embedding。

單一 Activity 的 App 在這些頁裡的對應：Compose 用 `ListDetailPaneScaffold` 這類 scaffold；Views 用 `SlidingPaneLayout`（下一節）。

### 裝置條件

- 指南：「supported on most large screen devices running Android 12L (API level 32) and higher」。
- 裝置要有 window extensions interface。指南說 12L 以上的大螢幕裝置幾乎都有，但不能跑多個 Activity 的裝置可能沒有。
- 執行期查 `SplitController.splitSupportStatus`。[原始碼](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/java/androidx/window/embedding/SplitController.kt)定義三個值：`SPLIT_AVAILABLE`、`SPLIT_UNAVAILABLE`、`SPLIT_ERROR_PROPERTY_NOT_DECLARED`。
- 同一份原始碼：摺疊機在小螢幕上可以收起分欄，這時仍回 `SPLIT_AVAILABLE`。
- 不支援時，指南說 Activity 照非 embedding 的模型開在最上層。
- 部分 API 要較新的 Extensions：`setSplitAttributesCalculator` 要 2，`updateSplitAttributes` 要 3，釘選要 5（同上原始碼）。拖曳調整窗格要 6（指南）。

### 相依與版本

- [release notes](https://developer.android.com/jetpack/androidx/releases/window)（最新更新 2026-06-17）：穩定版 `androidx.window:window:1.5.1`（2025-11-19），alpha 為 1.6.0-alpha05。指南範例還寫 `1.1.0-beta02`，已過時。
- 1.4.0（2025-05-20）加入分隔線、釘選、dialog 變暗整個 task。1.5.0（2025-09-24）加入 Large 與 XLarge 尺寸類別，並自動保存與還原分欄狀態。
- 用 XML 規則要加 `androidx.startup:startup-runtime`，[穩定版 1.2.0](https://developer.android.com/jetpack/androidx/releases/startup)（2024-09-18）。指南範例寫 1.1.1。

### Manifest

- `<application>` 裡加 `<property>`，名稱 `android.window.PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED`，值 `true`。指南：「On WindowManager release 1.1.0-alpha06 and later, activity embedding splits are disabled unless the property is added to the manifest and set to true.」[`WindowProperties.kt`](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/java/androidx/window/WindowProperties.kt) 也說沒設就不能用分欄。
- 另一個是 `android.window.PROPERTY_ACTIVITY_EMBEDDING_ALLOW_SYSTEM_OVERRIDE`。同檔：預設 `false`；App 自己提供規則時應設 `false`；這只是給 OEM 的提示，不能強制；建議一律明寫，不要依賴預設值。

### 規則與預設值

三種規則：`SplitPairRule` 決定哪對 Activity 並排；`SplitPlaceholderRule` 在右側先放佔位 Activity；`ActivityRule` 讓指定 Activity 永遠佔滿 task 視窗。預設值取自 [`attrs.xml`](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/res/values/attrs.xml) 與 [`RuleParser.kt`](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/java/androidx/window/embedding/RuleParser.kt)：

| 屬性 | 預設 | 備註 |
|------|------|------|
| `splitRatio` | `0.5` | 指南說須大於 0.0、小於 1.0 |
| `splitMinWidthDp` | `600` | `0` 表示永遠允許 |
| `splitMinHeightDp` | `600` | 不論左右或上下分欄都會檢查 |
| `splitMinSmallestWidthDp` | `600` | 比的是寬高較小者 |
| `splitMaxAspectRatioInPortrait` | `1.4` | `alwaysAllow` 是 0，`alwaysDisallow` 是 -1 |
| `splitMaxAspectRatioInLandscape` | `alwaysAllow` | |
| `splitLayoutDirection` | `locale` | 另有 `ltr`（主側在左）、`rtl` |
| `finishPrimaryWithSecondary` | `never` | 可用 `always`、`never`、`adjacent` |
| `finishSecondaryWithPrimary` | `always` | 同上 |
| `finishPrimaryWithPlaceholder` | `always` | 只接受 `always`、`adjacent` |
| `clearTop` | `false` | |
| `stickyPlaceholder` | `false` | |
| `alwaysExpand` | `false` | `ActivityRule` 的屬性；`ActivityRule` 優先於 `SplitPairRule` |

[`SplitRule.kt`](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/java/androidx/window/embedding/SplitRule.kt) 的 `checkParentBounds` 要寬、高、最小寬度、長寬比四項同時成立才分欄。同檔說 600 這個預設「reflects WindowWidthSizeClass.MEDIUM」。指南範例用的 `splitMinWidthDp="840"` 是範例值，不是預設。

上下分欄：指南說 `TOP_TO_BOTTOM`、`BOTTOM_TO_TOP` 只能用 API 設，XML 不支援。但 `androidx-main` 的 `attrs.xml` 已有 `topToBottom`、`bottomToTop`。

### 註冊

- XML：規則放在 `res/xml`。寫一個 `androidx.startup` 的 `Initializer`，用 `RuleController.parseRules()` 讀檔、`setRules()` 套用。Manifest 的 `InitializationProvider` 用 meta-data 指向它。指南說它在 `Application.onCreate()` 之前執行。
- API：在 `Application.onCreate()` 建規則，交給 `RuleController`。
- 規則只影響設定之後才啟動的 Activity（`SplitRule.kt`）。

### 寬度不夠時與返回

- 視窗窄到規則不成立時，secondary 容器的 Activity 疊在 primary 上，完全蓋住（`SplitRule.kt`）。指南的說法是「the non-placeholder activities in the secondary pane of the task window are stacked on top of the activities in the primary pane」。
- 佔位 Activity 在小螢幕等情況不會出現，所以不能獨自承載重要內容；`stickyPlaceholder` 決定它在視窗變窄後是否仍留在上面（[`SplitPlaceholderRule.kt`](https://github.com/androidx/androidx/blob/androidx-main/window/window/src/main/java/androidx/window/embedding/SplitPlaceholderRule.kt)）。
- 分欄建立或解除時，Activity 可能整個重建（指南的限制一節）。
- 返回：按鈕導覽時，送到最後有焦點的 Activity。返回滑動時，API 34 以下送到滑動那一側；API 35 以上，同一 App 的 Activity 一律結束最上層那個，不論從哪側滑。

### 和 Mirax 導覽有關的條目

- 導覽選單常駐左側、每選一項就換右側：指南要求 `clearTop`。否則分欄收起後，按返回會先回到上一個選過的頁，而不是選單。
- 要全視窗的 Activity：用 `ActivityRule` 的 `alwaysExpand`。
- Dialog：WindowManager 1.4 起，分欄中的 dialog 預設變暗整個 task（`DimAreaBehavior.ON_TASK`）。

## 單一 Activity 的 Views

### `SlidingPaneLayout`

來源是[雙窗格指南](https://developer.android.com/develop/ui/views/layout/twopane)（2026-05-28 更新）與 [release notes](https://developer.android.com/jetpack/androidx/releases/slidingpanelayout)。

- 穩定版 `androidx.slidingpanelayout:slidingpanelayout:1.2.0`（2022-01-26），之後沒有新版。Release notes：「This library is in maintenance mode and will only receive critical fixes; new features are not planned.」並建議 Compose。
- 只有兩個子 view：第一個是左窗格，第二個是右窗格。子 view 可以是一般 view 或 fragment（[Views canonical layouts](https://developer.android.com/develop/ui/views/layout/canonical-layouts)）。
- 並排或重疊：兩個子 view 的 `layout_width` 加總放得下就並排，剩下的寬度照 `layout_weight` 分。指南的例子是 200 dp 加 400 dp，要 600 dp 才並排。
- 放不下時兩窗格重疊，各自佔滿寬度，右窗格可以拖開。1.2.0 起預設是關閉（顯示左窗格）；打開時右窗格完全蓋住左窗格。
- 摺疊：設定的寬度是摺線兩側各自的最小寬度；放不下就退回重疊。
- 右窗格要有初始內容，指南用 `android:name` 指定初始 fragment。換右窗格內容時不要 `addToBackStack`。
- 返回：指南範例是一個 `OnBackPressedCallback`，在 `isSlideable && isOpen` 時啟用，呼叫 `closePane()`。
- 有 lock mode 可以限制拖動。Navigation 的 `AbstractListDetailFragment` 包了這一套。
- [Views 的 adaptive 設計頁](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views)（2026-05-28 更新）另一個例子：280 dp 清單加 300 dp 內容，580 dp 以上並排。

設定專用的包裝是 [`PreferenceHeaderFragmentCompat`](https://developer.android.com/reference/androidx/preference/PreferenceHeaderFragmentCompat)。[`androidx.preference`](https://developer.android.com/jetpack/androidx/releases/preference) 1.2.0（2022-01-26）加入它，描述是「two-pane preference that automatically adapts based on size of the device」；參考頁說它控制一個 `SlidingPaneLayout`。要用它，入口清單得是 `PreferenceFragmentCompat`，各設定頁得是 Fragment。這個 library 也在維護模式，穩定版 1.2.1（2023-07-26）。

### Material Components 的 side sheet

來源是 MDC 的 [SideSheet.md](https://github.com/material-components/material-components-android/blob/master/docs/components/SideSheet.md)。

- 1.8.0 起有 side sheet。Mirax 用 1.12.0，已經有。
- Standard：`SideSheetBehavior` 掛在 `CoordinatorLayout` 的子 view 上，浮在主內容上方。文件：「co-exist with the screen's main UI region and allow for simultaneously viewing and interacting with both regions」。
- Coplanar：設 `app:coplanarSiblingViewId`，展開時把指定的兄弟 view 擠窄，不蓋住它。文件：「Coplanar side sheets are not recommended for narrow screens.」
- Modal：`SideSheetDialog`。有 scrim，擋住其餘操作；點外面或滑出邊緣就關。Modal 的結構圖有可選的返回鈕。
- 預設貼 `end` 邊。
- 返回：modal 自動支援預測返回。Standard 要自己加 `OnBackPressedCallback`，把進度轉給 `SideSheetBehavior`。
- 文件裡沒有依寬度自動在 standard 與 modal 之間切換的設定。
- [M3 規格](https://m3.material.io/components/side-sheets/specs)（間接）：兩種最寬都是 400 dp。

### Compose

只記和 Views 的關係。Tier 2 把 `ListDetailPaneScaffold` 列為 Compose App 的多窗格做法。[Compose canonical layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts) 的 `ListDetailPaneScaffold`、`SupportingPaneScaffold` 都是 Compose API。Views 的頁面指向 `SlidingPaneLayout`、layout 資源與 activity embedding。Mirax 沒有 Compose。

## Material Design 3

m3.material.io 這次只讀到搜尋摘錄。能在 developer.android.com 或 MDC 文件找到同樣內容的，一併附上。

### 寬度斷點

[M3 breakpoints](https://m3.material.io/foundations/layout/breakpoints/overview)（間接，舊名 window size classes）與 [Android window size classes](https://developer.android.com/develop/ui/views/layout/use-window-size-classes)（2026-09-22 更新，直接）的數字一致。窗格、導覽、對話框三欄取自 M3 摘錄：

| 類別 | 寬度 (dp) | 窗格 | 導覽 | 對話框 |
|------|-----------|------|------|--------|
| Compact | < 600 | 1 | navigation bar、modal expanded rail | simple、full-screen |
| Medium | 600–839 | 1（建議）或 2 | navigation bar、modal expanded rail | simple |
| Expanded | 840–1199 | 1 或 2（建議） | modal 或 standard expanded rail | simple |
| Large | 1200–1599 | 1 或 2（建議） | 同上 | simple |
| Extra-large | ≥ 1600 | 1 到 3（建議） | 同上 | simple |

Android 頁另有高度類別：< 480、480–899、≥ 900。它說大型內螢幕直向多為 Medium，橫向「at least expanded width」。Fold 5 內螢幕不符合這句，見最後一節。

### Canonical layouts 與設定頁

- 三種：list-detail、supporting pane、feed（[Views canonical layouts](https://developer.android.com/develop/ui/views/layout/canonical-layouts)）。
- List-detail：Expanded 兩窗格並排；Medium 與 Compact 一次一個；尺寸改變時保留狀態（同頁）。
- 設定：[list-detail 指南](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail)說它也可以用在「dividing app preferences into a list of categories with the preferences for each category in the detail pane」。Supporting pane 的用例「Tools and settings」指的是編輯工具旁的調色盤、效果等設定（Views canonical layouts）。
- [M3 supporting pane](https://m3.material.io/foundations/layout/canonical-examples/supporting-pane)（間接）：「For content with a parent-child relationship, use a list-detail layout instead.」
- Views 的 supporting pane 用 layout 資源：`layout`（Compact，放在下方或 bottom sheet）、`layout-w600dp`（Medium，各半）、`layout-w840dp`（Expanded，30% 與 70%）。

### 導覽：drawer 與 rail

- MDC 的 [NavigationDrawer.md](https://github.com/material-components/material-components-android/blob/master/docs/components/NavigationDrawer.md)：「The navigation drawer is being deprecated in the Material 3 expressive update. For those who have updated, use an expanded navigation rail」。
- [M3 navigation drawer](https://m3.material.io/components/navigation-drawer/overview)（間接）：「no longer recommended in the Material 3 Expressive update」。
- Tier 2 的 UI_Secondary_Elements：「Navigation drawers are updated to expanded navigation rails.」
- MDC 的 [NavigationRail.md](https://github.com/material-components/material-components-android/blob/master/docs/components/NavigationRail.md)：expanded rail「is meant to replace the navigation drawer」，用 `expand()`、`collapse()` 切換。文件列的 expanded rail modality 只有 Non-modal；M3 在 Compact 與 Medium 建議的 modal expanded rail，這份文件沒寫。
- Expressive 主題與樣式要 MDC 1.14.0 以上（[getting-started](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md)）。[1.14.0](https://github.com/material-components/material-components-android/releases/tag/1.14.0) 在 2026-05-13 發布，minSdk 提高到 23。Mirax 目前是 1.12.0。

### Side sheet 與對話框

- Standard side sheet 與主內容並存；modal 擋住其餘操作（見上節）。[M3 large and extra-large](https://m3.material.io/foundations/layout/breakpoints/large-extra-large)（間接）說 standard side sheet 常用在 Medium 與 Expanded。
- 對話框：M3 表格只在 Compact 列 full-screen dialog，Medium 以上都是 simple dialog（間接）。Tier 2 要求小的編輯選單與 modal 不蓋滿整個螢幕，dialog 換成最新的 Material 元件。

## Adaptive app quality 與 Android 16

### 三個等級

[總覽](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality)（2026-04-10 更新）取代舊的大螢幕品質指南：

| 等級 | 名稱 | 和 Mirax 版面有關的條目 |
|------|------|-------------------------|
| [Tier 3](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-3) | Adaptive ready | Config_Changes：佔滿可用區域、不 letterbox；旋轉、摺疊、調整大小後保留狀態。另有 Config_Combinations、Multi-Window_Functionality、Multi-Resume |
| [Tier 2](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2) | Adaptive optimized | Responsive_adaptive_layouts：版面依 window size classes；leading 側的 rail 在寬時展開；trailing 側的側欄在桌面尺寸預設打開、較小時關閉；多窗格照 canonical layouts。UI_Secondary_Elements：小選單與 modal 不蓋滿；drawer 換成 expanded rail。觸控目標至少 48 dp |
| [Tier 1](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-1) | Adaptive differentiated | 依情境分 Desktop、Foldables、Camera • Audio |

Tier 2 的測試 T-Layout_Flow 對 activity embedding 另有一條：大螢幕並排，小螢幕疊起。

### Android 15：`Configuration` 含系統列

[Android 15 行為變更](https://developer.android.com/about/versions/15/behavior-changes-15)：target 35 以上，`Configuration.screenWidthDp`、`screenHeightDp` 不再扣掉系統列，`smallestScreenWidthDp` 連帶改變。頁面建議版面計算改用 `ViewGroup`、`WindowInsets` 或 `WindowMetricsCalculator`。`MainActivity` 的側欄判斷讀的正是 `Configuration.screenWidthDp`。

### Android 16：sw ≥ 600dp 忽略方向與比例限制

[Android 16 行為變更](https://developer.android.com/about/versions/16/behavior-changes-16)（2026-09-16 更新）：

- 範圍：target 36 的 App，在最小寬度 ≥ 600dp 的顯示器上，方向、可否調整大小、長寬比的限制不再生效。App 佔滿整個顯示視窗，不再 pillarbox。
- 被忽略的：`screenOrientation`、`resizeableActivity`、`minAspectRatio`、`maxAspectRatio`、`setRequestedOrientation()`、`getRequestedOrientation()`。
- 例外：遊戲（依 `android:appCategory`）；使用者在裝置長寬比設定裡選的值；sw600dp 以下的螢幕。
- 暫時退出：在 `<activity>` 或 `<application>` 加 `android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`，值 `true`。頁面說 target API 37 時就失效。[Android 17 行為變更](https://developer.android.com/about/versions/17/behavior-changes-17)（2026-09-16 更新）確認 target 37 起不能退出。
- 同版另一條：target 36 在 Android 16 上預設開預測返回，`onBackPressed` 不再被呼叫，`KEYCODE_BACK` 不再派送。暫時退出是 `android:enableOnBackInvokedCallback="false"`。

日期：條件是「target 36 且跑在 Android 16」。README 記這台 Fold 5 是 Android 16，Mirax target 36，所以現在就適用。Android 16 的 [release notes](https://developer.android.com/about/versions/16/release-notes) 只列到 Beta 4.1（2025-05-13）。Android 17 的 [release notes](https://developer.android.com/about/versions/17/release-notes) 列到 Beta 4.1（2026-06-01），並寫 Beta 3（2026-03-26）達到 Platform Stability。兩個正式版日期都不在這兩頁上。

Mirax 的 app 模組沒有方向或比例限制：manifest 沒有 `screenOrientation`、`resizeableActivity`、`minAspectRatio`、`maxAspectRatio`，程式沒有 `setRequestedOrientation()`。所以這條不會拿掉現有設定，只代表內螢幕的橫直兩向都要能用。新的窗格或 sheet 要用 `OnBackPressedCallback` 處理返回；`PictureActivity` 已經這樣做。

## Samsung

讀了兩頁 One UI 設計指南，頁上沒有更新日期。

[Designing for large screens](https://developer.samsung.com/one-ui/largescreen-and-foldable/intro.html)：

- 多窗格比例：600–959 dp 是 42% 與 58%，960 dp 以上是 38% 與 62%，Z Fold 建議 50:50。
- 這一節的延伸連結只有 Android Developers 的 Sliding pane layout 和 Activity Embedding 兩頁，沒有說 Galaxy 裝置支援哪一個。
- 導覽表還是 M3 Expressive 之前的寫法：Medium 用 navigation rail 或 modal navigation drawer。另提醒 Z Fold 直向用 navigation rail。
- 小輸入用小的 pop-over，不要佔滿整個螢幕，並靠近觸發它的元件。
- 最多同時 3 個分割視窗和 5 個 pop-up 視窗。

[Layout design for large screens](https://developer.samsung.com/one-ui/largescreen-and-foldable/large_screen_layout.html)：

- Galaxy 的寬度類別表把「Z Fold devices in portrait」和「Z Fold devices in landscape」都放在 Medium（600 ≤ 寬 < 840）。Expanded 只有平板。
- 寬 600dp 以上用大螢幕版面。選單與內容同時顯示。短任務用小 pop-up，不要換到整頁。

沒有找到 Samsung 說明 Galaxy Z Fold 或 One UI 支援 Jetpack activity embedding 的官方頁。要用只能在執行期查 `splitSupportStatus`。

## 平行視界（名詞對照）

[Huawei 的 codelab](https://developer.huawei.com/consumer/cn/codelab/AppMultiplier/index.html) 把平行視界寫成「以Activity为基本单位」的應用內分屏系統方案，需要 EMUI 10.0 以上的 Huawei 摺疊機或平板。App 在 manifest 的 `<application>` 加 `EasyGoClient` meta-data，在 `assets/easygo.json` 用 `activityPairs` 寫「from → to」的 Activity 對；範例的 `mode` 有 0（购物模式）和 1（导航栏模式）。對到 Google：meta-data 開關對應 `PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED`，`activityPairs` 對應 `SplitPairRule` 的 primary 到 secondary。這段只對照名詞，不拿來推論 Google 的行為。

## 對 Mirax 的推論

以下是推論，不是來源的原話。

### 內螢幕的 dp

README 記內螢幕 420 dpi，density 是 2.625：

| 方向 | 寬 × 高 (dp) | 寬度類別 | 預設規則下的 activity embedding |
|------|--------------|----------|--------------------------------|
| 橫向 | 約 829 × 690 | Medium | 分欄：寬、高、最小寬度都 ≥ 600 |
| 直向 | 約 690 × 829 | Medium | 分欄：高寬比約 1.2，未超過 1.4 |

- 這是全螢幕視窗的數字。target 35 以上 `Configuration` 含系統列，`screenWidthDp` 應接近它。多視窗與 Samsung 的顯示大小設定會改變它，要在機器上量。
- 兩向都是 Medium，和 Samsung 的表一致，和 Android 頁「橫向 at least expanded」那句不一致。M3 在 Medium 建議一個窗格、允許兩個；Samsung 對 Z Fold 建議雙窗格 50:50。兩窗格在這台是允許的，但不是 M3 的首選。
- 照抄指南範例的 `splitMinWidthDp="840"`，這台兩向都不會分欄（829 < 840）。
- 最小寬度約 690 dp，Android 16 那條適用於內螢幕。
- `SIDEBAR_BREAKPOINT_DP = 600` 剛好等於 Medium 起點、activity embedding 的預設門檻、Samsung 的「600dp 以上」。

### 三種做法

| | activity embedding | `SlidingPaneLayout` | standard side sheet |
|---|---|---|---|
| 頁的單位 | 右側每頁一個 Activity | 同一 Activity 的兩個子 view | 同一 Activity 的一個 sheet |
| 新相依 | `window` 1.5.1；XML 規則再加 `startup` 1.2.0 | `slidingpanelayout` 1.2.0 | 無 |
| Manifest | 加兩個 property；XML 規則再加 `InitializationProvider` | 不變 | 不變 |
| 寬時 | 開了右側頁才分欄；加 placeholder 則常駐 | 兩窗格常駐並排 | 按下才打開；coplanar 會擠窄儀表板 |
| 窄時 | 系統自動疊成全視窗 | 自動疊起 | 不自動，要另外用 modal 或整頁 |
| 返回 | 系統處理 | 一個 callback 呼叫 `closePane()` | standard 自己轉給 behavior |
| 裝置依賴 | window extensions，執行期查 | 無 | 無 |
| 維護 | 持續開發 | 維護模式 | 持續開發 |
| 官方定位 | 多 Activity 舊 App | Views 的 list-detail | 窄螢幕不建議 standard |

activity embedding 要改的：

- 一般、影像、進階、關於各改成 Activity。語言、標準模式、縮放可以是右側容器裡再疊的 Activity。
- `MainActivity` 在左側。導覽清單常駐時，`SplitPairRule` 要開 `clearTop`。
- `PictureActivity`（顯示畫面）用 `ActivityRule` 的 `alwaysExpand`，永遠佔滿。
- 狀態仍走 `MiraxApp.instance.session`，兩個 Activity 本來就共用它。每個新 Activity 要自己的標題列。

`SlidingPaneLayout` 要改的：

- `MainActivity` 的根改成 `SlidingPaneLayout`。左窗格放儀表板，右窗格放設定的頁堆疊：入口清單，往下是一般、影像、進階、關於，再往下是語言、標準模式、縮放。
- 兩個子 view 的寬度決定何時並排。合計不超過約 690 dp，橫直都並排；介於 690 與 829 之間，只有橫向並排。
- 一個 `OnBackPressedCallback`：右窗格有下一層時先退一層；窄時右窗格開著就 `closePane()`。
- 現在手寫的 docked 與 overlay 判斷、scrim、漢堡按鈕的顯示邏輯，由它取代。窄時漢堡按鈕改成打開右窗格。
- 寬時右窗格一直在，要有預設頁，例如入口清單。原本 drawer 的項目移進入口清單，寬時就不需要 drawer。

standard side sheet 要改的：

- 根改成 `CoordinatorLayout`。設定的頁堆疊放進掛 `SideSheetBehavior` 的容器，貼 end 邊，寬度不超過 400 dp。要儀表板不被蓋住，就設 `coplanarSiblingViewId`。
- 窄時改用 `SideSheetDialog` 或整頁，由 `layout-w600dp` 之類的資源選。這個切換是 App 的程式。
- 頁堆疊和返回鈕在 sheet 裡自己管。

### 建議

推論：Mirax 最小的官方做法是 `SlidingPaneLayout`。

- 它在現有的單一 Activity 裡同時做到「寬時開在右側」和「窄時疊上去」。判斷由元件量寬度完成，不用手寫門檻。
- Google 把它列為 Views 的 list-detail 做法；activity embedding 則明寫給多 Activity 舊 App。
- 設定是父子關係（一般到語言、影像到標準模式和縮放）。M3 對父子關係指向 list-detail，Google 的 list-detail 指南也把 App 偏好設定列為用途。
- 只加一個相依，不動 manifest，不依賴裝置的 window extensions。

它的代價：

- Library 在維護模式，只修重大錯誤。
- 寬時右窗格常駐。若要「平時儀表板全寬，只有打開某頁才出現右側」，它做不到。那時可選 activity embedding，不加 placeholder，代價是拆 Activity；或 coplanar standard side sheet，代價是窄時的呈現要自己選。
- Fold 5 兩向都是 Medium。兩窗格合乎 Samsung 建議、M3 允許，但不是 M3 在 Medium 的首選。

## 未確認

- m3.material.io 沒有直接讀到。斷點表、drawer「no longer recommended」、side sheet 規格、supporting pane 的句子都來自搜尋摘錄；同方向的內容有 MDC 文件與 Tier 2 直接佐證。M3 的 list-detail 範例頁沒讀。
- M3 supporting pane 的摘錄說 Medium 放在下方；Views canonical layouts 說 Medium 各半並排。以哪個為準未確認。
- 這台 Fold 5 的 One UI 有沒有 window extensions、`splitSupportStatus` 回什麼：沒有 Samsung 官方頁，要在機器上查。
- `splitLayoutDirection` 的 `topToBottom`、`bottomToTop`：指南說 XML 不支援，`androidx-main` 已有。哪個穩定版開始支援沒查。
- MDC 從哪一版開始有 `NavigationRailView.expand()`、1.12.0 能不能用，沒查。Views 上 modal expanded rail 的做法未確認。
- Android 16 與 17 正式版的日期不在 developer.android.com 的 release notes 頁上。
- 內螢幕實際的 `screenWidthDp` 與 `smallestScreenWidthDp`。
- Views canonical layouts 的適用性流程圖是圖片，內容沒讀到。
