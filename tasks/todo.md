# Mobile Touch & Scroll — P0 + P1 Sweep

Scope chosen by user: **all 4 P0 + all P1** items from `tasks/mobile-touch-scroll-audit.md`.
Approach: **runtime `DeviceUtils.isPhone()/isTV()` swaps** (single layout files, gated in Kotlin). Keep TV byte-identical.
Baseline: `:app:compileDebugKotlin` green (exit 0) before any change.
Verify after each batch: `./gradlew :app:compileDebugKotlin` (JAVA_HOME = Android Studio jbr). Final: `assembleDebug`.

---

## Batch A — The 3 real touch-scroll bugs + Home passthrough (surgical, no layout work) ✅ compiles clean
- [x] A1. Favorites toggle fade: dropped `isFirstLoad = true` from `FavoritesAdapter.viewMode` setter.
- [x] A2. Series Detail collapse: `EpisodeRecyclerAdapter.watchProgressMap` setter now equality-guards + `notifyItemRangeChanged(0, itemCount)` (no submitList(null)).
- [x] A3. Series Detail autoscroll: both `requestFocus()` blocks gated behind `isTV()`; episode item `isFocusableInTouchMode = isTV`.
- [x] A4. Home passthrough: static rows now `FOCUS_AFTER_DESCENDANTS + isFocusableInTouchMode=false` on phone; dynamic rows (944/1296) flipped `!isTV`→`isTV`.

## Batch B — Login P0 cluster ✅ (compiling)
- [x] B1. `fragment_login.xml` rewrapped in `ScrollView` (fillViewport) + centering wrapper.
- [x] B2. `MainActivity` sets `SOFT_INPUT_ADJUST_RESIZE` on phone after setContentView.
- [x] B3. Username auto-focus gated behind `isTv`.
- [x] B4. `passwordInput` IME Done → `loginButton.performClick()`.
- [x] B5. `login_card` widened to MATCH_PARENT on phone (TV keeps 480dp).
- [x] B6. `restAlpha` field: full opacity on phone, 0.9 dim on TV.

## Batch C — Navigation shell ✅ (compiling)
- [x] C1. `bottom_navigation` default `visibility=gone`; `navigateToLogin` hides deterministically.
- [x] C2. `syncBottomNav` zeroes `main_container.bottomMargin` when nav hidden; restores `bottom_nav_height` when shown.

## Batch D — Live TV P0 + P1s (3-panel portrait) ✅ compiles clean
- [x] D1. Phone: `preview_panel` GONE, categories re-weight 32 / channels 68 (added `@+id/channels_panel`).
- [x] D2. Header kept VISIBLE on phone (was GONE) — channel-filter search + MultiView reachable again.
- [x] D3. `previewContainer` focusability gated to TV.

## Batch E — VOD / Series / Account / VOD-Detail portrait re-stacks ✅ (compiling)
- [x] E1. VOD: keep header (search) visible on phone, hide decorative center logo/title; grid passthrough already present.
- [x] E2. Series: same header treatment; `poster_columns=2` added to values-sw320dp.
- [x] E3. VOD Detail: runtime VERTICAL stack (poster over metadata, full-width meta), plot un-clamped, Play-button focus gated to TV.
- [x] E4. Account: `content_area` → VERTICAL, all cards match_parent full-width on phone (gauge now fits).

## Batch F — MultiView + Player P0/P1 ✅ (compiling)
- [x] F1. MultiView phone click on EMPTY slot → `openChannelPicker(i)`; empty-state hint set to "Tap a screen…" on phone.
- [x] F2. Player action row wrapped in `HorizontalScrollView` (fillViewport, scrollbars=none); buttons → wrap_content + 2dp margins (both factories).
- [x] F3. `PlayerControlsBar.onScrimTouch` forwards empty-area touches to the player `touchListener`; fragment wires it in `setupTouchGestures`. Single-tap still toggles controls.

## Batch G — Heavier new phone surfaces (GuidedStep) ✅ (compiling)
- [x] G1. P0: NEW `common/PhoneGuidedStepFragment` base + `Theme.Ooustream.GuidedStep.Phone` (widens actions pane via `guidedActionContentWidthWeight` 0.714→40). All 7 GuidedStep screens (Settings/Subtitle/Confirm×2/Update/Backup/ClearConfirm/ParentalPin) extend it. Verified no portrait Leanback variant exists → audit was right. **NEEDS on-device visual confirm** (one change I can't visually verify here).
- [x] G2. Cram resolved by G1's full-width theme (PIN screen now full-width); PIN entry is functional on touch (tap field → type → tap Unlock). Dedicated numberPassword dialog deferred as a nicety (sole-gateway risk not worth a blind rewrite).
- [x] G3. Backup: phone-only "Import from File" SAF action (`OpenDocument`) → `viewModel.importBackupEncrypted(bytes)`. **Also closed a real gap**: the export is AES/GCM-encrypted but the only UI path (paste-JSON) feeds the *unencrypted* importer — there was NO way to restore an exported backup. Now there is.
- [x] G4. Parental quick-action row → `0dp/weight=1` equal thirds, 48dp tall; root padding 48dp→`@dimen/overscan_horizontal` (TV stays 48dp, phone 12/8dp).

### Notes / device-verify list
- G1 GuidedStep theme: confirm Settings/Backup/PIN render full-width and dark-branded on the phone.
- Live TV phone re-stack (D1): confirm categories/channels split feels right in portrait.
- Player F3: confirm double-tap-seek / drag volume-brightness work while controls are visible.

---

## Review

**Status: all 7 batches implemented. `:app:assembleDebug` BUILD SUCCESSFUL (both ABIs). Uncommitted.**
Approach held throughout: runtime `DeviceUtils.isPhone()/isTV()` swaps, single layout files, TV path preserved.

### Delivered (P0s = 3 of 4 fully; 4th relieved)
- **3 real touch-scroll bugs** (the heart of the complaint): Favorites fade-on-scroll, Series-Detail
  load autoscroll + onResume list-collapse, Player 15s gesture-dead window. All fixed surgically.
- **Live TV P0**: dropped the dead 45% preview panel on phone, re-weighted categories 32 / channels 68,
  restored the touch header (channel filter + MultiView were unreachable).
- **Login P0**: ScrollView + `adjustResize` (keyboard no longer hides Sign In), IME-Done submit,
  full-width card, full-opacity button, no auto-IME-on-load.
- **MultiView P0**: empty-slot TAP now opens the channel picker (was a dead grid on touch entry).
- **Settings P0 (relieved)**: GuidedStep actions widened to ~full width on phone via a new theme +
  `PhoneGuidedStepFragment` base across all 7 GuidedStep screens. (Verified no Leanback portrait
  variant exists, so the audit's "crammed to 42%" was correct.)
- **Nav**: bottom bar no longer flashes over splash/Login; dead 56dp bar removed under fullscreen.
- **VOD/Series**: in-screen search restored on phone (header was fully hidden); tiny-phone 2-col grid.
- **VOD Detail**: portrait poster-over-metadata stack, plot un-clamped, Play focus gated to TV.
- **Account**: 3-column dashboard → vertical full-width stack (gauge no longer overflows).
- **Home**: rows now use the correct passthrough idiom (`FOCUS_AFTER_DESCENDANTS` +
  `isFocusableInTouchMode=false`) — was inverted on both static and dynamic rows.
- **Player**: action row wrapped in HorizontalScrollView (no more clipped buttons); gestures work
  while controls are visible.
- **Parental**: quick-action row no longer clips (equal thirds, 48dp, overscan padding).

### Bonus fixes found mid-implementation (beyond the audit)
- **Backup restore was broken for everyone**: export is AES/GCM-encrypted but the only import UI fed
  the *unencrypted* parser (`importBackup`), and the encrypted importer (`importBackupEncrypted`) had
  NO UI. New phone SAF "Import from File" wires `importBackupEncrypted` — first working restore path.
- Home **dynamic** rows had the same inverted `isFocusableInTouchMode` as the static rows (fixed; and
  corrected a TV deviation introduced mid-edit — now `false` on both platforms, TV byte-identical).

### Verification
- `:app:compileDebugKotlin` clean after every batch (A,B/C,D,E,F,G) + final correction.
- `:app:assembleDebug` → BUILD SUCCESSFUL in 55s (full resource/theme/layout processing).
- NOT yet device-verified. On-device checklist (you're on a phone):
  1. **GuidedStep theme (G1)** — open Settings / Backup / Parental-PIN on the phone; confirm the menu
     is full-width and dark/gold (this is the one change I can't visually verify here).
  2. **Live TV (D1)** — confirm the 2-panel portrait split feels right; channel tap → fullscreen.
  3. **Player (F3)** — with controls showing, confirm double-tap-seek + vertical drag volume/brightness.
  4. **Login (B)** — focus a field; confirm the keyboard pushes the card up and Sign In stays reachable
     (and IME "Done" signs in).

### Deferred (intentionally, with reason)
- **G2 full PIN dialog**: PIN entry is functional on touch and G1 fixed its cram; a dedicated
  numberPassword dialog is a nicety, and replacing the sole parental-controls gateway blind is the one
  thing not worth rushing. Candidate for a focused follow-up.
- P2/P3 polish items from the audit (sub-48dp icons on some headers, season-tab raw padding, hero dots
  hit area, etc.) — out of the P0+P1 sweep scope.

### Follow-up: Home hero Play button (user request)
- Hero primary button was "Resume: {last Continue Watching title}" (a DIFFERENT movie than the hero),
  and the only thing that played the hero movie was the mislabeled "More Info" button.
- Fixed: primary → **"▶ Play"** plays the featured hero movie (`playFeaturedHero`); **"More Info"** →
  opens that movie's `VodDetailFragment` (`openFeaturedHeroDetail`). Resume stays on the Continue
  Watching row below. Removed `latestResumeItem` + `refreshHeroResumeCta`. New `@string/hero_play`.
  Applies to phone AND TV (action-semantics fix). `:app:compileDebugKotlin` clean.

## Feature: Actor / cast search (full backfill + "Starring" labels)
Architecture: main search = `ContentRepository.search` (client-side filter of bulk lists; FTS is offline
fallback). Series bulk list HAS cast/director → free. VOD bulk list has NO cast → needs a side cache
backfilled from `get_vod_info`.
- [x] D1. `VodCastEntity` (`vod_cast`) + `VodCastDao` (note: `cast` is a SQLite keyword → backtick-quote in the LIKE query).
- [x] D2. DB v11→v12 migration (CREATE TABLE vod_cast) + entity/dao registered + provider.
- [x] D3. `ContentRepository`: injects VodCastDao; `getVodInfo` upserts cast (free opportunistic); `search`
      matches series cast/director (bulk list) + VOD via cast cache; returns `castMatches` (id→actor, title misses only).
- [x] D4. `VodCastBackfillWorker` (Hilt): paced (150/run, 200ms), resumable (skips fetched ids), daily-rescan
      guard once done; periodic 15-min w/ CONNECTED constraint in OoustreamApp.
- [x] D5. `SearchResults.castMatches` preserved through `filterSearchResults`.
- [x] D6. `PosterItem.castMatch` + `poster_starring` line in item_poster_card (GONE unless set) + PosterPresenter
      binding; SearchFragment sets it for vod + series.
- [x] D7. `:app:assembleDebug` BUILD SUCCESSFUL (Room v12 schema, Hilt worker, layout all validate) +
      final `compileDebugKotlin` clean with the rescan guard. Only pre-existing warnings.
      Device-verify: type an actor → series appear immediately; movies appear as the backfill/opportunistic
      cache fills; cast-matched cards show gold "Starring {actor}". DB migration v11→v12 is additive
      (new vod_cast table) — favorites/watch-progress survive.

### Not done (release steps — awaiting user)
- version bump, `update.json`, CLAUDE.md version-history entry, commit/push, GitHub release. Hold until
  device-verified.
