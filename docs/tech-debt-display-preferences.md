# DisplayPreferences Tech Debt Documentation

## Overview
The `DisplayPreferences` class (`app/src/main/java/me/trinitrix/mirax/session/DisplayPreferences.kt`) encapsulates UI display preferences for the Mirax application. This document captures known technical debt and areas for improvement.

## Current Implementation

```kotlin
internal class DisplayPreferences(
    initialBottomHandleEnabled: Boolean = true,
    initialFloatingBallEnabled: Boolean = true,
    initialPictureScale: PictureScale = PictureScale.PROPORTIONAL,
    initialTouchEnabled: Boolean = true,
    initialShowDebugMessages: Boolean = true,
    initialCameraCutoutAffectsLayout: Boolean = false,
    initialBroadcastAutoStopMinutes: Int = 1,
    initialShowDebugOverlay: Boolean = false,
) {
    var bottomHandleEnabled: Boolean = initialBottomHandleEnabled
        private set
    var floatingBallEnabled: Boolean = initialFloatingBallEnabled
        private set
    var pictureScale: PictureScale = initialPictureScale
        private set
    var touchEnabled: Boolean = initialTouchEnabled
        private set
    var showDebugMessages: Boolean = initialShowDebugMessages
        private set
    var cameraCutoutAffectsLayout: Boolean = initialCameraCutoutAffectsLayout
        private set
    var broadcastAutoStopMinutes: Int = clampAutoStopMinutes(initialBroadcastAutoStopMinutes)
        private set
    var showDebugOverlay: Boolean = initialShowDebugOverlay
        private set

    // Setter methods for each property...
}
```

## Tech Debt Items

### 1. No Persistence Layer
**Severity**: High
**Description**: `DisplayPreferences` is an in-memory only class with no persistence mechanism. Settings are lost when the app process dies or the device reboots.

**Impact**: 
- Users must reconfigure preferences after every app restart
- Settings like `bottomHandleEnabled`, `floatingBallEnabled`, `pictureScale` don't persist

**Related**: `SessionPreferences.kt` uses `SharedPreferences` for `SessionSettings` but `DisplayPreferences` is separate and not persisted.

**Proposed Fix**: 
- Migrate `DisplayPreferences` to use `DataStore` (like `ConnectionHistory`) or integrate with existing `SessionPreferences` persistence
- Add `DataStore` persistence with JSON serialization similar to `ConnectionHistoryImpl`

---

### 2. Aggressive Default for `broadcastAutoStopMinutes`
**Severity**: Medium
**Description**: Default value is `1` minute, which is very aggressive for auto-stopping broadcasts.

**Code**: 
```kotlin
initialBroadcastAutoStopMinutes: Int = 1
```

**Impact**: 
- Broadcasts automatically stop after 1 minute unless user manually changes setting
- Poor UX for presentations, demos, or long-screen sessions
- May cause confusion when broadcast stops unexpectedly

**Proposed Fix**: 
- Increase default to 10-30 minutes
- Or make it configurable on first run
- Consider user preference for "never auto-stop" option

---

### 2. `cameraCutoutAffectsLayout` Default Behavior
**Severity**: Medium
**Description**: Default is `false`, meaning dashboard content may be obscured by punch-hole cameras or notches on modern devices.

**Code**:
```kotlin
initialCameraCutoutAffectsLayout: Boolean = false
```

**Impact**: 
- On devices with punch-hole cameras (e.g., Samsung Galaxy S series, Pixel 6+), the dashboard UI may render under the camera cutout
- Users may not see important UI elements

**Proposed Fix**: 
- Detect device cutout at runtime using `DisplayCutout` API
- Default to `true` for devices with cutouts
- Or provide "smart default" based on `windowInsets.displayCutout`

---

### 3. `showDebugOverlay` Default is False
**Severity**: Low
**Description**: Debug overlay is disabled by default, which is correct for production but makes debugging harder for developers.

**Code**:
```kotlin
initialShowDebugOverlay: Boolean = false
```

**Impact**: 
- Developers must manually enable debug overlay for debugging
- Not a user-facing issue

**Proposed Fix**: 
- Keep as-is for production
- Consider build-config based default (true for debug builds, false for release)

---

### 4. Duplicate State Management
**Severity**: Medium
**Description**: `DisplayPreferences` maintains its own state separate from `SessionSettings` and `SessionPreferences`. This creates multiple sources of truth.

**Impact**: 
- Potential for state divergence between `DisplayPreferences` and persisted `SessionSettings`
- `MiraxSession.exportSettings()` must manually copy values
- Testing complexity - need to mock both layers

**Proposed Fix**: 
- Make `DisplayPreferences` a pure projection of `SessionSettings`
- Or merge into `SessionSettings` with reactive updates via `DataStore`/`SharedPreferences` observer pattern
- Single source of truth for UI preferences

---

### 5. No Validation for `broadcastAutoStopMinutes` Range
**Severity**: Low
**Description**: The `clampAutoStopMinutes` function clamps to 1-180, but there's no validation when setting from external sources (e.g., settings import).

**Code**:
```kotlin
companion object {
    fun clampAutoStopMinutes(minutes: Int): Int = minutes.coerceIn(1, 180)
}
```

**Proposed Fix**: 
- Add validation in `setBroadcastAutoStopMinutes` (already uses clamp)
- Document the valid range in KDoc

---

### 6. Missing KDoc for Individual Properties
**Severity**: Low
**Description**: Class has KDoc but individual properties lack documentation.

**Proposed Fix**: 
- Add KDoc for each property explaining purpose, default, and valid range

---

## Integration Points

### MiraxSession Integration
```kotlin
// In MiraxSession.exportSettings()
display.bottomHandleEnabled = displayPreferences.bottomHandleEnabled
display.floatingBallEnabled = displayPreferences.floatingBallEnabled
display.pictureScale = displayPreferences.pictureScale
display.customModes = caps.customModes
display.autoAddWmSizeOnConnect = caps.autoAddWmSizeOnConnect
display.touchEnabled = displayPreferences.touchEnabled
display.showDebugMessages = displayPreferences.showDebugMessages
display.cameraCutoutAffectsLayout = displayPreferences.cameraCutoutAffectsLayout
display.broadcastAutoStopMinutes = displayPreferences.broadcastAutoStopMinutes
display.showDebugOverlay = displayPreferences.showDebugOverlay
```

### SessionPreferences Persistence
`SessionPreferences.kt` persists these settings via `SharedPreferences`:
- `bottom_handle_enabled`
- `floating_ball_enabled`
- `picture_scale`
- `touch_enabled`
- `show_debug_messages`
- `camera_cutout_affects_layout`
- `broadcast_auto_stop_minutes`

`show_debug_overlay` is intentionally session-only and not persisted.

---

## Recommended Priority

1. **High**: Add persistence layer (DataStore migration)
2. **High**: Fix `broadcastAutoStopMinutes` default
3. **Medium**: Smart default for `cameraCutoutAffectsLayout`
4. **Medium**: Consolidate state management (single source of truth)
6. **Low**: Improve KDoc coverage

---

## Related Files
- `app/src/main/java/me/trinitrix/mirax/session/DisplayPreferences.kt`
- `app/src/main/java/me/trinitrix/mirax/session/SessionTypes.kt` (SessionSettings)
- `app/src/main/java/me/trinitrix/mirax/SessionPreferences.kt`
- `app/src/main/java/me/trinitrix/mirax/session/MiraxSession.kt`
- `docs/tech-debt.md` (general tech debt documentation)
