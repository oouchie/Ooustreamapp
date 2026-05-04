# Phone Touch UX Overhaul (Tier 1 + Tier 2)

Senior UI/UX audit ship plan, drafted 2026-05-03 after Explore-agent inventory.

## Goal

Bring the phone build of Ooustream from "TV app squashed into a phone" to "feels like Netflix mobile." Ship in 2 versioned increments so each round can be verified on-device before the next lands. TV behavior must not regress on any change.

## Shipping plan

- **v3.7.11** — Quick wins + player gesture overhaul (the perceptual 80/20). Most user-visible, lowest risk.
- **v3.7.12** — Lists / overlays / detail-screen toolbar / GuidedStep replacement. Heavier surgery, smaller perceived delta per change.

Splitting like this means andresi gets her v3.7.10 fix soaked for ~24 hrs before phone-UX changes land, and TV users see incremental-not-shocking updates.

---

## v3.7.11 — Quick wins + player gestures

### A. Quick wins (tier 1, all phone-only branches)

- [ ] **A1. Soft keyboard auto-show on Search.** `SearchFragment.kt:127` after `searchEditText.requestFocus()`, call `InputMethodManager.showSoftInput(searchEditText, SHOW_IMPLICIT)`. TV branch unchanged.
- [ ] **A2. Aspect ratio HUD popup.** New `AspectRatioOverlay` (or extend `SeekFeedbackOverlay`) shown for 1.5s when `cycleAspectRatio()` runs. Shows current mode label (Fit / Fill / Stretch) centered. Phone + TV both benefit.
- [ ] **A3. 48dp touch targets in player controls.** `res/layout/overlay_player_controls.xml` — every action LinearLayout gets `android:minWidth="48dp"` `android:minHeight="48dp"`. Verify no visual regression on TV.
- [ ] **A4. Ripple on missing card layouts.** Audit `item_top10_card.xml`, `item_trending_rank.xml`, `item_channel_strip.xml`, `item_search_chip.xml`, `item_section_card.xml` — ensure `android:foreground="?android:attr/selectableItemBackground"` is present on the FrameLayout root. Already-rippled cards untouched.
- [ ] **A5. Snackbar helper for non-blocking phone feedback.** New `common/PhoneFeedback.kt` with `Activity.snack(msg)` extension that uses Snackbar on phone, Toast on TV. Replace ~10 user-facing Toast call sites (favorite added, copy URL, etc.) — keep system Toasts (crash logs, debug events) as Toasts.

### B. Player gesture overhaul (tier 2, the centerpiece)

- [ ] **B1. Replace double-tap-to-pause with double-tap-zone seek.** `OoustreamPlaybackFragment.kt:2293-2356` rewrite of gesture listener:
  - `onDoubleTap(e)` — if `e.x < width/2` → -10s seek, else → +10s seek
  - Show `DoubleTapRippleOverlay` (new view) — circular expanding ripple centered on tap point + arrow icon + "+10" / "-10" label. Fade in 100ms, fade out 400ms.
  - Single-tap stays "toggle controls"; pause/resume becomes a tap on the play button (mobile pattern).
- [ ] **B2. Vertical drag → brightness (left half) / volume (right half).** New `VerticalSwipeGestureHandler` — track `onScroll(distanceY)` if initial down was a vertical motion (delta-y > delta-x). Apply via `WindowManager.LayoutParams.screenBrightness` (left) or `AudioManager.adjustVolume` (right). Show `VolumeBrightnessOverlay` — vertical bar with icon, fades after 800ms idle.
- [ ] **B3. Long-press = 2x speed while held.** `onLongPress(e)` triggers `player.setPlaybackParameters(PlaybackParameters(2f))` + show "2x" badge top-center. Restore on `MotionEvent.ACTION_UP/CANCEL` via raw touch listener (GestureDetector doesn't fire long-press release events). Disable for LIVE content.
- [ ] **B4. Pinch-zoom = cycle aspect.** Add `ScaleGestureDetector` alongside the existing `GestureDetector`. `onScaleEnd` with `scaleFactor > 1.15` cycles to next aspect; `< 0.85` cycles back. Reuses A2's HUD popup.

### C. Phone-only gating

- [ ] **C1. All B-section gestures gated behind `!DeviceUtils.isTV()`.** TV stays remote-only.
- [ ] **C2. Manifest** — confirm `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />` is set.

### D. Build / ship v3.7.11

- [ ] **D1.** Bump versionCode 76→77, versionName 3.7.10→3.7.11
- [ ] **D2.** Update `update.json` with phone-focused changelog
- [ ] **D3.** `assembleDebug` — verify on phone (Pixel emulator) and AFTKRT (regression check on TV path)
- [ ] **D4.** `assembleRelease` + `gh release create v3.7.11`
- [ ] **D5.** Update CLAUDE.md Phase 11 / Version Release History

---

## v3.7.12 — Lists / overlays / detail-screen polish

Land after v3.7.11 has a clean ~24-hour soak.

### E. Pull-to-refresh on grids (tier 2)

- [ ] **E1. Wrap `VerticalGridView` with `SwipeRefreshLayout`** in `VodFragment`, `SeriesFragment`, `LiveTvFragment`, `FavoritesFragment`. Refresh action calls existing `viewModel.refresh()` / `viewModel.loadCategories()` on each.
- [ ] **E2. Phone only** — TV doesn't get the swipe affordance.

### F. Bottom-sheet replacements (tier 2)

- [ ] **F1. `TrackPickerOverlay` → `BottomSheetDialogFragment` on phone.** New `PhoneTrackPickerSheet`. Same UI rows/radio buttons, just rendered as draggable bottom sheet, 70% screen height. Branch in `OoustreamPlaybackFragment.showTrackPicker()` on `isTV`. TV gets the existing right-edge slide-in.
- [ ] **F2. `ContentInfoOverlay` (long-press info) → `BottomSheetDialogFragment` on phone.** Plot, metadata, Play/Favorite/Trailer buttons rendered as a sheet. TV unchanged.

### G. Detail-screen toolbar (tier 1)

- [ ] **G1. `VodDetailFragment` + `SeriesDetailFragment` get a Material `Toolbar` on phone** with back arrow, title, share/info actions. Wraps the existing hero+content body in a `CoordinatorLayout` + `AppBarLayout` for collapsing-toolbar behavior. TV layout unchanged via `layout-television/`.

### H. Hero pagination dots (tier 2)

- [ ] **H1. Tap-able dot indicator below hero buttons** for the ~5 featured items the hero rotates through. Auto-syncs with `currentHeroIndex`. Tap a dot to jump. Clean Material indicator (filled circle for active, outlined for inactive).

### I. GuidedStepSupportFragment phone replacements (tier 1, deferred biggest scope)

This is the largest single piece of work. 6 fragments to replace with Material equivalents — Settings, Update, Backup, ParentalPin, SubtitleSettings, ClearConfirm. Each ~150-300 lines.

**Decision needed**: do all 6 in v3.7.12, or split off into v3.8.0 as its own focused release? Recommendation: **v3.8.0** because each replacement is independent and not user-blocking; "Phone-native settings" deserves its own changelog framing.

### J. Build / ship v3.7.12

- [ ] **J1.** Bump versionCode 77→78, versionName 3.7.11→3.7.12
- [ ] **J2.** Update `update.json`
- [ ] **J3.** `assembleRelease` + `gh release create v3.7.12`
- [ ] **J4.** Update CLAUDE.md

---

## Risks

- **TV regressions.** Every change phone-gates via `DeviceUtils.isTV()`. Build pass on AFTKRT mandatory before each release.
- **Player gesture conflict.** Vertical swipe + double-tap + long-press all on the same `View.OnTouchListener` chain. Order of detectors matters. Plan: `ScaleGestureDetector.onTouchEvent()` first → if not scaling, `GestureDetector.onTouchEvent()` → long-press release tracked via raw ACTION_UP.
- **GuidedStep replacement (deferred to v3.8.0)** could be 2-3 days on its own.
- **Snackbar requires a CoordinatorLayout** in the view hierarchy. `MainActivity.activity_main.xml` may need wrapping. Will check on first attempt.

## Open questions for the user

1. Is the v3.7.11 → v3.7.12 → v3.8.0 split OK, or do you want it all in one release?
2. For the GuidedStep replacements: legacy XML `RecyclerView` or jump on Compose now? (Compose adds ~1.5MB to APK + learning curve, but it's where new Material 3 patterns live.)
3. Do you have a phone you can test on, or should I assume Pixel 6+ as the dev target?

---

## Review

Filled in after each shipping increment.
