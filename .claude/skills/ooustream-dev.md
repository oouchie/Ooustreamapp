---
name: ooustream-dev
description: Ooustream IPTV Android TV development reference. Use when working on any feature, bug fix, or enhancement in this codebase. Provides architecture patterns, conventions, and known pitfalls specific to this project.
user_invocable: false
---

# Ooustream IPTV Development Reference

## Architecture Quick Reference

### Fragment Navigation
- Manual FragmentManager — NO NavGraph
- `MainActivity.navigateTo*()` methods for each screen
- `FragmentTransitions.apply(tx, TransitionDirection.FORWARD)` for animations
- Always `addToBackStack(null)` for back navigation

### Key Handling Rules
- **Leanback playback**: ALL keys in `OoustreamPlaybackGlue.onKey()` — NEVER `setOnKeyInterceptListener`
- **Non-Leanback fragments**: Implement `KeyEventHandler` interface, handle in `onKeyEvent(keyCode)`
- **Long-press detection**: Use `onFullKeyEvent(event: KeyEvent)` for ACTION_DOWN/UP timing
- **Must consume ACTION_UP** for handled keys to prevent Leanback auto-showing controls
- **Guard key interception**: Check which view has focus before consuming DPAD_CENTER (e.g. `isSlotFocused()`)

### DI Pattern
```kotlin
@AndroidEntryPoint
class MyFragment : Fragment() {
    @Inject lateinit var myDep: MyDep
    private val viewModel: MyViewModel by viewModels()
}

@HiltViewModel
class MyViewModel @Inject constructor(
    private val repo: MyRepository
) : ViewModel()
```

### Dialog Rules
- **ALWAYS** use `android.app.AlertDialog` (NOT `androidx.appcompat.app.AlertDialog`)
- Leanback theme is NOT AppCompat-compatible — AppCompat dialogs crash

### Focus Navigation (Android TV)
- Focus must live on views WITH IDs (usually XML-defined FrameLayouts)
- Set `nextFocusUpId/DownId/LeftId/RightId` on the FOCUSABLE view, not children
- Use `FOCUS_BLOCK_DESCENDANTS` on containers with PlayerView/SurfaceView
- Set visibility SYNCHRONOUSLY before returning from key handler (not via async StateFlow)
- `requestFocus()` must target the focusable view, not a non-focusable child

### ExoPlayer Setup
```kotlin
// Renderer: hardware first, FFmpeg fallback for AC3/DTS/EAC3
setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

// Stereo downmix (REQUIRED — budget devices can't output 6ch PCM)
val downmixer = ChannelMixingAudioProcessor()
downmixer.putChannelMixingMatrix(ChannelMixingMatrix(1, 1, floatArrayOf(1f)))         // mono passthrough
downmixer.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f,0f,0f,1f))) // stereo passthrough
downmixer.putChannelMixingMatrix(ChannelMixingMatrix(6, 2, floatArrayOf(...)))         // 5.1→stereo ITU-R BS.775
downmixer.putChannelMixingMatrix(ChannelMixingMatrix(8, 2, floatArrayOf(...)))         // 7.1→stereo

// Audio focus
player.setAudioAttributes(attrs, handleAudioFocus = true) // single player
player.setAudioAttributes(attrs, handleAudioFocus = false) // multi-player (manual volume)
```

### Screen Wake
- Leanback `VideoSupportFragment` handles keep-screen-on automatically
- Non-Leanback playback fragments MUST add `FLAG_KEEP_SCREEN_ON` in `onViewCreated` and clear in `onDestroyView`

### Logging
- Fire TV suppresses `Log.d()` — use `Log.w()` for production-visible logs
- Audio logs: `AudioLogger.log()` (OOUSTREAM_AUDIO tag)
- Crash logs: `CrashLogger` saves to file, viewable in Settings

## Build & Deploy

### Build
```bash
powershell.exe -Command "Set-Location 'C:\Users\oouch\App Projects\ooustream-iptv-android'; & '.\gradlew.bat' assembleDebug"
```

### Deploy
```bash
ADB="/c/Users/oouch/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="C:/Users/oouch/App Projects/ooustream-iptv-android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s 192.168.1.82:5555 install -r "$APK"
```

### Devices
- .82, .84 = Fire TV Stick
- .222 = Ooustick customer device

## Home Screen Row Pattern
Each home row follows this pattern in HomeFragment:
1. `private lateinit var myRow: HorizontalGridView` + `private lateinit var myLabel: TextView`
2. `private val myObjectAdapter = ArrayObjectAdapter(MyPresenter())`
3. `setupMyRow()` — creates `ItemBridgeAdapter`, sets click listeners in `onBind`, calls `attachRowDimming()` for neighbor dimming
4. `observeMyContent()` — `repeatOnLifecycle(STARTED)` collects Flow, calls `myObjectAdapter.safeReplaceAll(items)`
5. Visibility toggled based on data: label + row both VISIBLE or both GONE

### Adding a New Home Row
1. Create presenter extending `Presenter` (see `NewEpisodesPresenter.kt` for badge pattern)
2. Create layout XML (`@dimen/card_poster_width` × `@dimen/card_poster_height` with `@dimen/card_corner_radius` for poster-type)
3. Add label + HorizontalGridView to `fragment_home.xml`
4. Add fields, adapter, setup, observe, and click handler in `HomeFragment.kt`
5. Add Flow in `HomeViewModel.kt`
6. Call `attachRowDimming(row, label, viewHolder, position)` in `onBind` for spotlight effect

## Mobile Touch Patterns

### Device Detection
- `DeviceUtils.isTV(context)` — checks UiModeManager for TV mode
- `DeviceUtils.isPhone(context)` — smallestScreenWidthDp < 600
- Guard all touch-only code with `if (!DeviceUtils.isTV(context))`

### Touch Gestures on Fragments
```kotlin
// In onViewCreated, guarded by !isTV
if (!DeviceUtils.isTV(requireContext())) {
    val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent) = true  // toggle controls
        override fun onDoubleTap(e: MotionEvent) = true           // play/pause
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float) = true // seek/zap
        override fun onDown(e: MotionEvent) = true                // REQUIRED for fling detection
    })
    view.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
}
```

### Resource Qualifiers
- `values/` — Phone defaults (360dp+)
- `values-sw320dp/` — Extra-small phones (320-359dp)
- `values-sw600dp/` — Tablets (600dp+)
- `values-television/` — Android TV
- Card sizes, text sizes, margins all dimension-driven — never hardcode dp in layouts

### Touch Feedback
- `android:foreground="?android:attr/selectableItemBackground"` on FrameLayout cards
- Safe on TV — ripples only triggered by touch, invisible with D-pad focus
- Only works on FrameLayout at API < 23; other ViewGroups need API 23+

### Overlay Dismiss
- All overlays with scrim should have `scrim.setOnClickListener { dismiss() }` for mobile

### Orientation Lock
- MultiView locks to landscape on phones via `requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE` in `onAttach()`, reset in `onDetach()`

### Adapter Updates (VerticalGridView)
- **NEVER use `setItems(list, DiffCallback)`** on Leanback VerticalGridView — granular diff notifications cause position -1 crash
- Use `setItems(list, null)` for atomic `notifyDataSetChanged()` + save/restore `selectedPosition` with bounds clamping

## Common Pitfalls
1. `ChannelMixingAudioProcessor` throws without passthrough matrices for 1ch and 2ch
2. `EXTENSION_RENDERER_MODE_PREFER` breaks live TV — always use `MODE_ON`
3. `viewModelScope.launch` cancelled on ViewModel clear — use `NonCancellable` for critical Room ops
4. `Room.fallbackToDestructiveMigration()` wipes all user data
5. `setBackgroundResource()` inflates XML each call — use `setBackgroundColor()` for scroll perf
6. `GuidedStepSupportFragment` can't change action count dynamically — use fixed-slot layout
7. `ArrayObjectAdapter.setItems(list, DiffCallback)` causes IndexOutOfBoundsException on VerticalGridView — use `setItems(list, null)` for atomic update
8. TrackPickerOverlay reads `player.currentTracks` as snapshot — stale after channel switch; must listen for `onTracksChanged`
9. Row label default color is `text_primary` (white), turns gold via BrowseCardFocusHelper on focus — don't set labels to `text_accent` in XML
10. Hardcoded dp/sp in item layouts breaks on phones — always use `@dimen/` references with qualifier overrides
