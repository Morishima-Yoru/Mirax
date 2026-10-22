# Technical Debt Documentation

## DisplayPreferences / SessionSettings Tech Debt

### 1. Aggressive Default for `broadcastAutoStopMinutes`

**File:** `app/src/main/java/me/trinitrix/mirax/session/SessionTypes.kt` (line ~315)

**Issue:** Default value is `1` minute:
```kotlin
/** Minutes of advertising before it turns itself off. Default one minute. */
val broadcastAutoStopMinutes: Int = 1,
```

**Impact:** Very aggressive auto-stop may interfere with legitimate use cases (presentations, demos, testing). Users must manually extend it each session.

**Consideration:** 
- Default of 1 minute might be intentional for battery/privacy
- Could be increased to 5-10 minutes as more reasonable default
- Or make it configurable on first run

---

### 2. `cameraCutoutAffectsLayout` Default Behavior

**File:** `app/src/main/java/me/trinitrix/mirax/session/SessionTypes.kt` (line ~317)

**Issue:** Default is `false`:
```kotlin
/**
 * When true, dashboard padding also clears the punch-hole / notch. Default false
 * so the cutout does not shift the layout.
 */
val cameraCutoutAffectsLayout: Boolean = false,
```

**Impact:** On devices with punch-hole cameras or notches, the dashboard content may be obscured by the cutout when this is `false`.

**Consideration:** 
- Could detect device cutout at runtime and default to `true` for affected devices
- Or provide a "smart default" based on `DisplayCutout` API

---

### 3. No Migration Path for Settings Schema Changes

**File:** `app/src/main/java/me/trinitrix/mirax/SessionPreferences.kt`

**Issue:** No versioning or migration logic for `SharedPreferences` schema changes. Adding new persistent fields requires manual default handling; `showDebugOverlay` is intentionally session-only.

**Impact:** Settings corruption or loss when app updates add new preference fields.

**Fix:** Add schema version tracking and migration logic.

---

### 4. Inconsistent Persistence Granularity

**File:** `app/src/main/java/me/trinitrix/mirax/SessionPreferences.kt`

**Issue:** Multiple fine-grained save methods (`saveBottomHandleEnabled`, `saveFloatingBallEnabled`, `savePictureScale`) plus a bulk `saveResolutionSettings`. This can lead to partial saves and inconsistency.

**Impact:** Race conditions between individual saves and bulk saves; potential for settings to get out of sync.

**Consideration:** Consolidate to single save method or use transactional approach.

---

### 5. DisplayPreferences Class Not Using Persistence Directly

**File:** `app/src/main/java/me/trinitrix/mirax/session/DisplayPreferences.kt`

**Issue:** `DisplayPreferences` is an in-memory class with private setters. It doesn't directly interact with `SessionPreferences`. The persistence happens at `MiraxSession.exportSettings()` → `SessionPreferences.saveResolutionSettings()`.

**Impact:** 
- Two sources of truth: in-memory `DisplayPreferences` vs persisted `SessionSettings`
- Potential for divergence if `DisplayPreferences` is modified without calling export/save
- Testing complexity: need to mock both layers

**Consideration:** 
- Make `DisplayPreferences` observe `SessionPreferences` changes
- Or make `DisplayPreferences` a pure projection of `SessionSettings`
- Consider DataStore migration for better reactivity

---

## Other Tech Debt Items

### 6. MiraxSession God Class Tendency

**File:** `app/src/main/java/me/trinitrix/mirax/session/MiraxSession.kt`

**Issue:** Despite deep module extraction (Tickets 01-03), `MiraxSession` remains a large facade (~400+ lines) handling:
- Action routing
- Snapshot composition
- Settings export
- Effect emission
- Module lifecycle coordination

**Consideration:** Further decompose into `SessionController` (action routing) + `SessionSnapshotProvider` (snapshot composition) + `SessionSettingsManager`.

---

### 7. Limited Connected-Device Automation

**Directory:** `app/src/androidTest/`, `scripts/pointer-probe/`

**Issue:** Android instrumentation covers UI and input injection, but live Windows Miracast acceptance still requires an external source and native Windows pointer probe; it is not part of CI.

**Impact:** JVM and Android instrumentation alone cannot detect every Windows receiver, Wi-Fi Display, or audio playback regression.

**Priority:** HIGH - Critical for regression prevention

---

### 8. PowerShell Scripts as Manual Test Helpers

**Directory:** `scripts/`

**Issue:** 20+ PowerShell scripts for manual E2E verification (click-fold, connect-ui, monitor-discovery, etc.)

**Impact:** 
- Not automated, not in CI
- Windows-only (PowerShell)
- No assertions, just UI automation
- Brittle - depend on specific UI hierarchy

**Consideration:** Migrate to Android UiAutomator tests in `androidTest/`

---

## Summary by Priority

| Priority | Item | Effort | Risk |
|----------|------|--------|------|
| 🔴 HIGH | Live Windows Miracast coverage outside CI | High | Hardware regression risk |
| 🟡 MEDIUM | `broadcastAutoStopMinutes` default too aggressive | Low | UX friction |
| 🟡 MEDIUM | `cameraCutoutAffectsLayout` default on cutout devices | Medium | Layout issues |
| 🟢 LOW | Settings schema migration | Medium | Future-proofing |
| 🟢 LOW | Persistence granularity inconsistency | Medium | Race conditions |
| 🟢 LOW | DisplayPreferences / SessionSettings duality | High | Architecture |
| 🟢 LOW | MiraxSession further decomposition | High | Maintainability |
| 🟢 LOW | PowerShell → UiAutomator migration | High | Test automation |

---

## Related Files

- `app/src/main/java/me/trinitrix/mirax/session/DisplayPreferences.kt`
- `app/src/main/java/me/trinitrix/mirax/session/SessionTypes.kt`
- `app/src/main/java/me/trinitrix/mirax/SessionPreferences.kt`
- `app/src/main/java/me/trinitrix/mirax/session/MiraxSession.kt`
- `app/src/test/java/me/trinitrix/mirax/session/MiraxSessionTest.kt` (unit tests only)
- `scripts/*.ps1` (manual test helpers)
