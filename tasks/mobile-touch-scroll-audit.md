# Ooustream Phone Touch & Scroll Audit — Consolidated Report

## The user's reported problem, answered first

> "Some of the menus aren't scrolling properly on the mobile phone side."

After verifying findings against the actual source across all 17 screens, the headline answer is nuanced and important:

**Almost nothing in this app is a literal "the list refuses to scroll" bug.** The Leanback `VerticalGridView`/`HorizontalGridView` rows and the plain `RecyclerView` lists DO drag-scroll on a finger in nearly every case we checked. Several auditor claims of "RecyclerView can't scroll / Series unreachable / can't reach Logout" were the classic mistake of conflating `NestedScrollingChild` fling-cooperation with basic touch scrolling, and were **refuted and downgraded** during verification (Search results ScrollView, Settings actions list, Update/Backup actions list).

**What actually makes "the menus feel broken on a phone" is four things, in order of impact:**

1. **No phone/portrait layouts.** This is a 10-foot Android TV (Leanback) app retrofitted onto phones with only `layout/` and `layout-television/` resource dirs — no `layout-port/`, no `layout-sw320dp/layout/`. Multi-panel TV screens render side-by-side in portrait at unusable widths, and fixed-dp widths overflow narrow phones. This drives the four P0s and most P1s.
2. **A handful of genuine scroll-position / animation bugs** that DO make scrolling visibly misbehave: the Favorites view-mode toggle re-arms a per-row fade so scrolling flashes/fades rows; Series Detail yanks the header off-screen on load and collapses the episode list on return from playback; the player controls bar kills every touch gesture for 15s.
3. **Inconsistent touch-passthrough.** The proven `FOCUS_AFTER_DESCENDANTS + isFocusableInTouchMode=false` idiom is applied to some grids and the OPPOSITE is done on Home — producing focus-steal friction on drag-start and first-tap-does-nothing, which a thumb user reads as "not scrolling right."
4. **Pervasive sub-48dp touch targets** that make working lists feel fiddly.

The remediation is mostly additive (phone layout variants + `DeviceUtils.isPhone()` gates) and reuses patterns the codebase already has (`PhoneToolbarHelper`, `PhoneTrackPickerSheet`, the VOD/Series passthrough fix).

---

## Systemic Issues (cross-cutting root causes)

### S1 — No phone/portrait layout variants (the #1 root cause)
Only `layout/` (TV-derived, shared by phone) and `layout-television/` exist. No `layout-port/`, no `layout-sw320dp/layout/`. Only `values-sw320dp/` and `values-sw600dp/` override dimens — which cannot re-stack panels or change orientation. Every multi-panel TV screen renders side-by-side in portrait; fixed-dp widths overflow with no horizontal scroll. Affects: livetv (P0), settings (P0), vod (P1), series (P1), vod_detail (P1), account (P1), login (P1), speedtest (P2), update/backup (P2), parental (P2).

### S2 — GuidedStepSupportFragment for all settings/confirm/PIN flows
Leanback's horizontal two-pane (~58% guidance / ~42% actions) is wrong for portrait. The actions list IS touch-scrollable (verified — no outer scroll wrapper, GridLayoutManager scrolls), so "can't scroll to Logout" was a false alarm; the real harm is the menu jammed into ~42% width + keyline focus-snap on tap, plus the Backup-import-by-pasting-JSON flow and the PIN-gate D-pad advance.

### S3 — Inconsistent touch-passthrough idiom
Home sets `isFocusableInTouchMode=true` on all rows (opposite of the working VOD/Series fix); VOD/Series category lists never get the fix. Result: focus-steal on drag-start, first-tap focus artifacts. Not hard scroll locks.

### S4 — Focus-keyed visuals/animations run on phone with no symmetric clear
Neighbor-dimming, Login button dim, VodDetail Play focus state, and the Favorites staggered fade run unconditionally; touch focus is transient so clears don't fire — sticky/janky states.

### S5 — Sub-48dp touch targets everywhere (TV density never bumped for touch).

### S6 — Missing on-screen touch back affordances + touch-dead focus idioms (reachability/discoverability gaps).

---

## Per-Page Breakdown

### Live TV — fragment_live_tv.xml / LiveTvFragment.kt  (worst screen on phone)
- **[P0] 3-panel side-by-side TV layout, no portrait path** (`fragment_live_tv.xml:24-28,35,56,79`; `LiveTvFragment.kt:121-126`). *Symptom:* categories 22% (~79dp), channels 33% (~119dp), preview 45% (~162dp) all rendered at once in portrait; channel names/badges crushed, EPG unreadable, half the screen donated to a preview that never plays. *Root cause:* one horizontal LinearLayout, three weighted match_parent children; the only phone gating hides the header and flips channel focusability — never re-stacks. *Fix:* on `!isTV()` set `preview_panel` GONE and re-weight channels to full width, or a layout-port/runtime ConstraintSet stacking [category strip] over [full-width channels].
- **[P1] Header set GONE on phone → channel filter + MultiView entry unreachable** (`LiveTvFragment.kt:122`; header `fragment_live_tv.xml:180-272`). *Note:* the header search is an in-screen channel FILTER (not the global Search tab), and the MultiView icon is also gated by device capability — but both are genuinely lost on Live TV on phone. *Fix:* slim Material toolbar with a 48dp search action + MultiView/overflow.
- **[P1] Preview panel = 45%-wide inert dead-zone** (`LiveTvFragment.kt:137-138,356`; `fragment_live_tv.xml:79`). *Symptom:* preview never starts on phone (auto-preview TV-only), tap is a no-op (`previewingChannel` null), yet it is focusable in touch mode and steals width. *Fix:* GONE on phone, reassign weight, gate `isFocusable/isFocusableInTouchMode` behind isTV.
- **[P2] Category rows 42dp in a ~79dp column** (`item_category.xml:5`). *Fix:* ≥48dp dimen with values-television override.
- **[P3] Channel focus visuals + real-EPG upgrade fire only on D-pad focus** (`ChannelPresenter.kt:109-161`; `LiveTvFragment.kt:336-371,438-491`). Every row already shows an inferred "on now" line regardless of focus (verified), so only the gold chrome + real-EPG upgrade are missing — cosmetic. *Fix:* drive from center/visible item on scroll.

### Settings (GuidedStep) — SettingsFragment.kt / SubtitleSettingsFragment.kt / SettingsConfirmFragment.kt
- **[P0] TV two-pane GuidedStep, no portrait path** (`SettingsFragment.kt:35`; entries `MainActivity.kt:234`, `HomeFragment.kt:1901`). *Symptom:* title pane ~58% width, the whole Account/Parental/Subtitle/Backup/Logout menu jammed into ~42% in portrait. *Fix:* phone branch → full-width RecyclerView/BottomSheet + Material toolbar; GuidedStep only for isTV().
- **[P1] Guidance pane = fixed non-scrollable ~58% dead width** (`onCreateGuidance` in all three). Same root defect as the P0; fixed by the phone replacement.
- **[P2] Actions list is focus-aligned VerticalGridView** — DRAG-SCROLLS FINE (verified: GridLayoutManager.scrollVerticallyBy/canScrollVertically present, no outer ScrollView). Real friction is keyline focus-snap on tap + the ~42% width crunch. (Auditor's P0 "Logout unreachable by drag" was **wrong** — downgraded.) Action count is 15, not 16.
- **[P2] Action rows have no enforced 48dp minHeight** (no `guidedActionItemContainerStyle` override).
- **[P3] Confirm/sub-screens have no in-screen back** — but Cancel button + system back both work (not a dead-end). *Fix folds into phone replacement.*
- **[P3] SettingsConfirmFragment stores onConfirm in a companion (static) field** — process-death/sequential-confirm foot-gun. Correctness, not touch-scroll. *Fix:* AlertDialog or Fragment Result API.

### MultiView — MultiViewFragment.kt / fragment_multiview.xml
- **[P0] Tapping an empty slot does nothing; picker only on long-press** (`MultiViewFragment.kt:198-211,679-683`; entries `HomeFragment.kt:694`, `LiveTvFragment.kt:218` both seed null; `MainActivity.kt:438`). *Symptom:* every touch user lands on 4 empty slots, tap does nothing, the hint says "Press OK to pick a channel" (no OK on phones) — feature unusable on touch entry. *Fix:* in the phone `setOnClickListener`, if `slot.channel == null` call `openChannelPicker(i)`; fix `fragment_multiview.xml:154` hint to be touch-aware.
- **[P2] Layout switcher + audio-slot bar start GONE and auto-hide in 8s** (`fragment_multiview.xml:113,122`; `showControls()` 580-586; timeout 8000ms line 85). Both top AND bottom bars affected (same mechanism). *Fix:* on phone keep bars visible / skip auto-hide.
- **[P2] Layout buttons ~27-29dp, audio buttons ~27dp** (`view_multiview_top_bar.xml:66-120`; `MultiViewBottomBarController.kt:48-77`). *Fix:* 48dp min + ≥8dp spacing.
- **[P2] No on-screen back/close** (system back IS routed via `MainActivity.kt:292 → showExitDialog → popBackStack 1072`, so not trapped). *Fix:* PhoneToolbarHelper close button.
- **[P3] EPG ticker is an auto-scroll marquee, not finger-draggable** (`MultiViewBottomBarController.kt:159-173`). Acceptable as marquee.
- **[NOT A DEFECT] Channel picker dual-pane** — plain RecyclerViews in a weighted LinearLayout, scrolls/taps fine (verified).

### Player — OoustreamPlaybackFragment.kt / PlayerControlsBar.kt
- **[P1] Bottom action row overflows / squashes below 48dp, no horizontal scroll** (`overlay_player_controls.xml:230`; `PlayerControlsBar.kt:383-398,472-529,485`). *Symptom:* 10 buttons on phone (Stats phone-only) in a `gravity=center` LinearLayout with `minimumWidth=48dp` each (~480dp) exceed screen → edge buttons (Prev/Stats) clipped off both sides or all squashed. *Fix:* wrap in HorizontalScrollView (wrap_content buttons + marginEnd), or overflow secondaries on phone.
- **[P1] All phone gestures dead for 15s while controls visible** (`OoustreamPlaybackFragment.kt:748-755,776,2521-2555`; `PlayerControlsBar.kt:97-101`; `PlayerControlsManager.kt:21`). *Symptom:* once the MATCH_PARENT controls bar is shown, its full-screen click listener (→ hide) intercepts every touch; the gesture listener is bound only to rootView+surfaces, never the bar, so double-tap-seek / drag volume-brightness / fling-zap / long-press-2x / pinch are dead for the full 15s auto-hide. *Fix:* forward non-widget touches from the bar's scrim to the gesture pipeline (or attach the same touchListener to the bar root, treat simple tap as hide).
- **[P3] Pinch aspect non-reversible** (`:2513-2517,3068-3080`) — moot with only 2 modes (toggle either way).
- **[NOT A DEFECT] Track picker** routes to PhoneTrackPickerSheet (real ScrollView, 52dp rows) — scrolls fine.

### Login / Splash — fragment_login.xml / LoginFragment.kt / activity_main.xml / themes_mobile.xml
- **[P0] No scroll container + no windowSoftInputMode → keyboard hides Sign In button & error text** (`fragment_login.xml:2-15`; `activity_main.xml:10-14`; `AndroidManifest.xml:34-39`; `LoginFragment.kt:84`). *Symptom:* username auto-focuses → IME pops on load; non-scrollable centered card; default ADJUST_UNSPECIFIED; `main_container` also reserves bottom_nav_height — Sign In (4th child) and error text (5th) occluded with no scroll recovery. *Fix:* wrap card in `fillViewport` ScrollView/NestedScrollView + set `windowSoftInputMode=adjustResize` on the Mobile theme + TV-gate the auto-focus.
- **[P1] IME "Done" not wired → no fallback submit when button is occluded** (`fragment_login.xml:73` declares `actionDone`; no `setOnEditorActionListener` in `LoginFragment`). Together with the P0 this is a hard dead-end on short phones. *Fix:* `passwordInput.setOnEditorActionListener { … IME_ACTION_DONE → loginButton.performClick() }`.
- **[P1] Login card hardcoded 480dp, overflows phones, no horizontal scroll** (`fragment_login.xml:9-15`). *Fix:* match_parent + margins / maxWidth, @dimen indirection.
- **[P3] Login button permanently dimmed to alpha 0.9 + no ripple** (`LoginFragment.kt:57-81,99-114`).
- **[P3] Splash can't be skipped by tap** (`IntroSplashFragment.kt:32-41,68-103`; no touch forwarding in MainActivity). 5s cap bounds it. *Fix:* `playerView.setOnClickListener { skip() }` on phone.

### Navigation shell — activity_main.xml / MainActivity.kt
- **[P1] Permanent 56dp bottom margin → dead black bar under fullscreen player/MultiView/Login** (`activity_main.xml:14`; `bottom_nav_height` 56dp phone / 0dp TV; no Kotlin mutates the margin). *Fix:* zero `bottomMargin` in `syncBottomNav` when nav hidden, or restructure to weighted LinearLayout.
- **[P1] Bottom nav shown over splash/Login; tabs tappable while unauthenticated** (`activity_main.xml:23-32` no visibility; `syncBottomNav` 157-172 only fired from back-stack listener + navigateToHome; splash/Login committed without addToBackStack; navigateToLogin skips it too). *Fix:* `visibility=gone` by default and/or call syncBottomNav on splash/Login/logout paths (it already classifies both as hideNav).
- **[P2] Favorites & Settings unreachable from Live/VOD/Series/Search** (5-item menu; surfaced only on Home; sidebar TV-only). *Fix:* shared header with gear + star, or overflow.

### Home — fragment_home.xml / HomeFragment.kt
- **[P1] All rows set isFocusableInTouchMode=true (inverse of VOD/Series)** (`HomeFragment.kt:503-509,1291`; vs `VodFragment.kt:85-86`). *Symptom (narrowed):* friction on empty-area/drag-start touches; grid is a needless focus contender during scroll. On-card taps DO reach the card (descendantFocusability=afterDescendants + card focusableInTouchMode). *Fix:* set `isFocusableInTouchMode=false` to match VOD/Series.
- **[P2] 10-foot density, no portrait tuning** — hero is 45% of screen at runtime (`HomeFragment.kt:203-207`, `integers.xml`), 185dp rows + 24dp gaps push rails below the fold. Fully scrolls. *Fix:* phone-tuned row heights/margins (extract the hardcoded 24dp margins to @dimen first).
- **[P2] Hero swipe onDown returns true → sticky first vertical drag from hero** (`HomeFragment.kt:1748-1777`; onFling already horizontal-gates at 1759). *Fix:* return false from the listener.
- **[P3] Hero dots ~8dp tappable** (`updateHeroIndicators` ~641-671) — expand hit area to 48dp.
- **[P3] Neighbor-dimming not isTV-gated → cards can stick at 50/65% alpha on touch** (`HomeFragment.kt:535-556`; `BrowseCardFocusHelper.kt:31-73`). *Fix:* gate to TV.

### VOD — fragment_vod.xml / VodFragment.kt
- **[P1] 25/75 horizontal split, no portrait** (`fragment_vod.xml:35,70`; phone fix only hides chrome + grid passthrough `VodFragment.kt:81-87`). Grid IS 3 cols on phone (less broken than claimed); the ~90dp ellipsized sidebar is the real degradation. *Fix:* re-weight/stack on phone.
- **[P2] frosted_header GONE removes the ONLY in-screen VOD search** (`VodFragment.kt:83`; icon 28dp `fragment_vod.xml:154-155`). *Fix:* keep search icon (≥48dp), hide only decorative chrome.
- **[P2] Category rows 42dp** (`item_category.xml:5`). *Fix:* ≥48dp dimen (72dp would be too tall).
- **[P3] Category RecyclerView lacks the passthrough fix** — scrolls fine, only a first-tap focus-scale artifact (`VodFragment.kt:81-87`, missing on `categoriesList`).
- **[P3] Poster long-press returns true** — minor scroll competition.
- **[P3] Back-nav focus restore uses grid.post{}** (`VodFragment.kt:240-246`) — contradicts the v3.7.13 "synchronous is load-bearing" invariant (sibling path 322-325 is synchronous). Focus-restore/slow-device crash-family, not a touch-scroll break. *Fix:* set `selectedPosition` synchronously after assigning the adapter.

### Series — fragment_series.xml / SeriesFragment.kt
- **[P1] 25/75 side-by-side, no portrait** (`fragment_series.xml:34,69`; poster_columns stays 3). *Fix:* stack on phone, add `poster_columns=2` in values-sw320dp.
- **[P1] Search affordance hidden on phone (frosted_header GONE), no fallback** (`SeriesFragment.kt:81`; `series_category_search` is `visibility=gone` dead code with ZERO references). Hidden on phones AND tablets (gate is `!isTV`). *Fix:* keep search icon visible, or wire the dead sidebar EditText.
- **[P2] Category rows 42dp** (`item_category.xml:5`).
- **[P3] Header search icon 28dp** — latent (header GONE on all touch devices today).
- **[P3] Category list lacks passthrough parity** — scrolls fine, minor first-tap focus steal.

### Series Detail — SeriesDetailFragment.kt / EpisodeRecyclerAdapter.kt / fragment_series_detail.xml  (real scroll bugs here)
- **[P1] requestFocus() on episode list yanks header off-screen on load** (`SeriesDetailFragment.kt:238-242,272-276`; list focusable in NestedScrollView `fragment_series_detail.xml:196-197`). NestedScrollView auto-scrolls to the newly-focused descendant. *Fix:* gate both requestFocus blocks behind isTV; set episodes_list `focusableInTouchMode=false` on phone.
- **[P1] watchProgressMap setter does submitList(null)+submitList(current) → list collapses to 0 height, jolts scroll on return from playback** (`EpisodeRecyclerAdapter.kt:26-33`; chain `SeriesDetailFragment.kt:130-134,285-289`, runs every onResume, no equality guard). *Fix:* early-return on equal value; rebind via `notifyItemRangeChanged` payload instead of emptying.
- **[P2] Season tabs use raw-pixel padding (32,16,32,16), no min dimensions** (`SeasonTabPresenter.kt:14-23`). *Fix:* convert to dp, minWidth 64dp / minHeight 48dp.
- **[P3] Back arrow 44dp** (`PhoneToolbarHelper.kt:90-92`) — shared with VodDetail; bump to 48dp.

### VOD Detail — fragment_vod_detail.xml / VodDetailFragment.kt
- **[P1] TV/landscape hero layout, no portrait** (`fragment_vod_detail.xml:9-21,37-44,47-62`; backdrop hardcoded 320dp literal, poster-left + metadata-right). *Fix:* stack poster over metadata on phone, swap 320dp/140dp to dimens.
- **[P2] Plot capped at 5 lines, no touch expand** (`fragment_vod_detail.xml:158-170`; `VodDetailFragment.kt:220-224`). maxLines clamps height so the working ScrollView has nothing to reveal. *Fix:* maxLines=MAX / tap-to-expand on phone.
- **[P3] No watch-next/similar row** — discoverability gap (enhancement).
- **[P3] Back arrow 44dp** (`PhoneToolbarHelper.kt:90-92`) — same fix as Series Detail.
- **[P3] Play button focusableInTouchMode + requestFocus paints gold focused state on entry** (`fragment_vod_detail.xml:184-187`; `VodDetailFragment.kt:227,256`). *Fix:* gate requestFocus to TV, drop focusableInTouchMode.

### Favorites — FavoritesAdapter.kt / FavoritesFragment.kt / FavoritesViewModel.kt  (real scroll bug here)
- **[P1] Grid/List toggle re-arms staggered fade-in → scrolling flashes/fades rows** (`FavoritesAdapter.kt:34-42,88-104`; `toggleViewMode` 134-136 doesn't re-emit displayItems so `markFirstLoadComplete` never fires; self-heals within ~60s on the next EPG tick). Direct match to the user's complaint. *Fix:* don't set isFirstLoad=true on a user toggle, or clear the one-shot flag next frame.
- **[P2] Remove (X) button 28dp** (`item_favorite_list.xml:133-146`, `item_favorite_card.xml:85-99`). Edit mode is touch-reachable. *Fix:* 48dp hit area.
- **[P2] Filter tabs ~30dp** (`FavoritesFragment.kt:140-174`). *Fix:* 48dp min on phone.
- **[P3] Live Scores row is a Leanback HorizontalGridView** — sibling above the main list, doesn't block vertical drag; only horizontal-fling smoothness. *Fix:* plain RecyclerView on phone (optional).

### Account — fragment_account_dashboard.xml / AccountDashboardFragment.kt / ConnectionGaugeView.kt
- **[P1] 3-column horizontal card row, no portrait** (`fragment_account_dashboard.xml:38,42-49,137-145,185-192`; zero device gating). ~100dp/card → 72sp number, labels, URL, gauge squeezed/clipped. *Fix:* `orientation=vertical` + match_parent cards on phone.
- **[P1] ConnectionGaugeView fixed 140dp overflows ~100dp column** (`fragment_account_dashboard.xml:206-207`; `onMeasure` 69-76 derives size from the EXACTLY spec, ignores incoming constraint). *Fix:* resolved by the vertical stack; add responsive @dimen as defense.
- **[P3] No on-screen back** (system back works via `SettingsFragment.kt:353-358`). *Fix:* PhoneToolbarHelper.
- **[NOT A DEFECT] Whole-page ScrollView** — healthy (single ScrollView, no nested scrollables, no interceptors).

### Search — fragment_search_aurora.xml / SearchFragment.kt
- **[NOT A DEFECT] Results ScrollView "swallows vertical drag / Series unreachable"** — REFUTED. Plain ScrollView + horizontal RecyclerView rows is the standard working pattern; a horizontal grid does not disallow parent interception for a vertical drag. Page scrolls. (Optional polish: swap to NestedScrollView for fling consistency.)
- **[P2] Voice & Clear icons 28dp** (`fragment_search_aurora.xml:65-94`). *Fix:* 48dp hit area.
- **[P3] Recent chips 32dp** (`item_search_chip.xml:6`; row 44dp). *Fix:* 40-48dp.
- **[P3] Result rows use WRAP_CONTENT row height** (`SearchFragment.kt:386-393`) — leanback anti-pattern, renders OK in practice. *Fix:* fixed dimen like the trending_row (260dp) pattern.

### Update / Backup / ClearConfirm (GuidedStep)
- **[P1] Backup "Import" = paste raw multi-line JSON into a Leanback inline editor, no file picker** (`BackupFragment.kt:39-47,70-77`; `BackupViewModel.kt:39-44`; no SAF anywhere in the package). Most phone users cannot complete a restore. *Fix:* ACTION_OPEN_DOCUMENT on phone, feed stream to importBackup.
- **[P2] Horizontal two-pane TV guidance layout, no portrait** (framework-driven `lb_guidedstep_fragment.xml`; entries `SettingsFragment.kt:242-244`). *Fix:* phone-native fragments; AlertDialog for ClearConfirm.
- **[P3] Actions list focus-aligned VerticalGridView** — DRAG-SCROLLS FINE (verified) and all three lists (2/2/4 rows) fit on screen, so no overflow. (Auditor's P1 "stuck/can't pan" was **wrong** — downgraded.)
- **[NOT A DEFECT] Guided action row heights** — Leanback default is well above 48dp.

### Parental — ParentalPinFragment.kt / ParentalSettingsFragment.kt / fragment_parental_settings.xml
- **[P1] PIN entry is a GuidedStep, the sole gateway, TV-shaped on phone** (`ParentalPinFragment.kt:22,99-104,171-175`; entry `SettingsFragment.kt:240`; Change-PIN re-enters it `ParentalSettingsFragment.kt:88-96`). IME works but field→Unlock advance is a D-pad ACTION_ID_NEXT concept. *Fix:* Material AlertDialog/BottomSheet numberPassword on phone.
- **[P1] Quick-action button row wrap_content + gravity=center + no scroll → both outer buttons clipped** (`fragment_parental_settings.xml:114-159`; root padding 48dp line 8). Clipping is SYMMETRIC (gravity=center), so "Block All Adult" and "Change PIN" can be unreachable on a 320-360dp phone. *Fix:* `0dp/weight=1` thirds; replace 48dp root padding with the existing `@dimen/overscan_horizontal`.
- **[P2] focusableInTouchMode=true on rows/tabs** (`item_category_toggle.xml:10-11`) — TV-only attribute, causes fling focus-retention jank (NOT a double-tap-to-toggle bug — clicks fire on first tap, verified). *Fix:* gate to TV.
- **[P2] No phone layout: 48dp overscan padding + 24dp row insets** (`fragment_parental_settings.xml:8`; `item_category_toggle.xml:8-9`) — list scrolls, just wasteful density. *Fix:* use `@dimen/overscan_horizontal`.
- **[P3] No on-screen back** (system back works). *Fix:* PhoneToolbarHelper.
- **[P3] Quick-action buttons 40dp / tabs 44dp** (`fragment_parental_settings.xml:56,71,85,125,138,151`) — below 48dp. *Fix:* 48dp on phone.
- **[NOT A DEFECT] Category RecyclerView** — plain RecyclerView at weight=1, no scroll wrapper/interceptor; scrolls fine.

### Speed Test — fragment_speed_test.xml
- **[P2] Content column hardcoded 400dp clips on narrow phones** (`fragment_speed_test.xml:9`). Button text stays centered/tappable, so cosmetic edge-clip. *Fix:* match_parent + horizontal margins.
- **[P3] No on-screen back** (gesture back works; `SpeedTestFragment.onKeyEvent` returns false). *Fix:* PhoneToolbarHelper.
- **[NOT A DEFECT] No scroll needed** — short static centered column; run_test_button is 48dp. Healthy.

---

## Prioritized Fix Backlog (P0 → P3)

### P0 — unusable / unscrollable on phone (4)
1. **Live TV 3-panel portrait** — `LiveTvFragment.onViewCreated` (`!isTV()`): set `preview_panel` GONE, re-weight channels to full width (or layout-port stack [category strip]/[full-width channels]). Files: `livetv/LiveTvFragment.kt`, `res/layout/fragment_live_tv.xml`.
2. **Settings GuidedStep two-pane** — phone branch at `SettingsFragment.onGuidedActionClicked` / `MainActivity.kt:234`: render full-width RecyclerView/BottomSheet + Material toolbar; GuidedStep only for isTV(). Files: `settings/SettingsFragment.kt` (+ SubtitleSettings, SettingsConfirm).
3. **MultiView empty-slot tap dead** — in the phone `setOnClickListener` (`MultiViewFragment.kt:199`): if `slot.channel == null` call `openChannelPicker(i)`; fix `fragment_multiview.xml:154` hint to "Tap a screen to pick a channel". Files: `multiview/MultiViewFragment.kt`, `res/layout/fragment_multiview.xml`.
4. **Login keyboard occludes Sign In** — wrap the card in a `fillViewport` ScrollView/NestedScrollView; set `windowSoftInputMode=adjustResize` on `Theme.Ooustream.Mobile`; TV-gate the auto-focus at `LoginFragment.kt:84`. Files: `res/layout/fragment_login.xml`, `res/values/themes_mobile.xml`, `auth/LoginFragment.kt`.

### P1 — badly degraded but usable (16)
5. **Login IME Done unwired** — `passwordInput.setOnEditorActionListener { IME_ACTION_DONE → loginButton.performClick() }`. (`auth/LoginFragment.kt`)
6. **Login card 480dp overflow** — match_parent + margins / `@dimen/login_card_width`. (`res/layout/fragment_login.xml`)
7. **Nav: bottom nav over splash/Login** — `visibility=gone` default + call `syncBottomNav` on splash/Login/logout paths. (`res/layout/activity_main.xml`, `MainActivity.kt`)
8. **Nav: 56dp dead margin under fullscreen** — zero `main_container.bottomMargin` when nav hidden in `syncBottomNav`. (`res/layout/activity_main.xml`, `MainActivity.kt`)
9. **Player action row overflow** — wrap `action_buttons_row` in HorizontalScrollView (wrap_content buttons + marginEnd) or overflow secondaries on phone. (`res/layout/overlay_player_controls.xml`, `player/PlayerControlsBar.kt`)
10. **Player gestures dead for 15s** — forward bar-scrim touches to the gesture pipeline; treat simple tap as hide. (`player/OoustreamPlaybackFragment.kt`, `player/PlayerControlsBar.kt`)
11. **Favorites toggle fade-on-scroll** — don't set `isFirstLoad=true` on a user toggle, or clear the flag next frame. (`favorites/FavoritesAdapter.kt`)
12. **Series Detail requestFocus auto-scroll** — gate both requestFocus blocks behind isTV; `episodes_list focusableInTouchMode=false` on phone. (`series/SeriesDetailFragment.kt`, `res/layout/fragment_series_detail.xml`)
13. **Series Detail watchProgress collapse** — equality short-circuit + `notifyItemRangeChanged` payload instead of `submitList(null)`. (`series/EpisodeRecyclerAdapter.kt`)
14. **Live TV header GONE** — slim toolbar with 48dp search + MultiView/overflow. (`livetv/LiveTvFragment.kt`, `res/layout/fragment_live_tv.xml`)
15. **Live TV preview dead-zone** — GONE + reweight on phone; gate preview focusability to isTV. (`livetv/LiveTvFragment.kt`, `res/layout/fragment_live_tv.xml`)
16. **VOD 25/75 portrait** — re-weight/stack on phone. (`vod/VodFragment.kt`, `res/layout/fragment_vod.xml`)
17. **Series 25/75 portrait** — stack + `poster_columns=2` in values-sw320dp. (`series/SeriesFragment.kt`, `res/layout/fragment_series.xml`, `values-sw320dp/dimens.xml`)
18. **Series search unreachable** — keep search icon visible on phone or wire the dead sidebar EditText. (`series/SeriesFragment.kt`, `res/layout/fragment_series.xml`)
19. **VOD Detail portrait hero** — stack poster over metadata; swap 320dp/140dp literals to dimens. (`res/layout/fragment_vod_detail.xml`)
20. **Account 3-column dashboard** — `orientation=vertical` + match_parent cards on phone. (`res/layout/fragment_account_dashboard.xml`, `account/AccountDashboardFragment.kt`)
21. **Account gauge 140dp overflow** — resolved by vertical stack; add responsive `@dimen/connection_gauge_size`. (`res/layout/fragment_account_dashboard.xml`, `account/ConnectionGaugeView.kt`)
22. **Home rows focusableInTouchMode** — set false to match VOD/Series. (`home/HomeFragment.kt`)
23. **Backup import requires pasted JSON** — ACTION_OPEN_DOCUMENT (SAF) on phone. (`backup/BackupFragment.kt`)
24. **Parental PIN GuidedStep gateway** — Material numberPassword AlertDialog/BottomSheet on phone. (`parental/ParentalPinFragment.kt`, `settings/SettingsFragment.kt`)
25. **Parental quick-action row clip** — `0dp/weight=1` thirds + replace 48dp root padding with `@dimen/overscan_horizontal`. (`res/layout/fragment_parental_settings.xml`)
*(P1 count = 16 distinct items above; #5-#25 minus the layout/portrait twins that share a single fix are tracked individually for the backlog.)*

### P2 — noticeable friction
- VOD/Series/Live TV category rows 42dp → ≥48dp dimen w/ TV override (`item_category.xml`).
- VOD/Series `frosted_header` GONE removing in-screen search → keep search icon (`vod/series` Fragments).
- Home density (row heights/margins) + hero-swipe onDown returns true (`home/HomeFragment.kt`, `fragment_home.xml`).
- VOD Detail plot truncation no expand (`fragment_vod_detail.xml`, `vod/VodDetailFragment.kt`).
- Favorites remove-X 28dp + filter tabs ~30dp (`item_favorite_*.xml`, `favorites/FavoritesFragment.kt`).
- Search voice/clear icons 28dp (`fragment_search_aurora.xml`).
- MultiView bars gone+8s autohide, buttons ~27dp, no on-screen back (`multiview/*`).
- Settings actions keyline-snap + no row minHeight (folds into the P0 phone replacement).
- Parental focusableInTouchMode jank + overscan padding (`item_category_toggle.xml`, `fragment_parental_settings.xml`).
- Nav: Favorites/Settings unreachable off-Home (`MainActivity` / shared header).
- Speed Test 400dp clip (`fragment_speed_test.xml`).

### P3 — polish
- Detail back arrows 44dp→48dp (`common/PhoneToolbarHelper.kt`, fixes both detail screens).
- Hero dots 8dp, neighbor-dimming not isTV-gated (`home/HomeFragment.kt`, `common/BrowseCardFocusHelper.kt`).
- VOD/Series category lists missing passthrough parity; VOD long-press; VOD back-nav grid.post{} restore (`vod`/`series` Fragments).
- Login button permanent dim + no ripple; splash tap-to-skip (`auth/LoginFragment.kt`, `splash/IntroSplashFragment.kt`).
- Live TV channel focus visuals on touch; MultiView ticker not draggable; player pinch non-reversible.
- Search recent chips 32dp + wrap_content row height; season tabs raw-pixel padding.
- Parental quick-action 40dp/tab 44dp; on-screen back for Account/SpeedTest/Parental/MultiView.
- VOD Detail Play-button focus paint; SettingsConfirm static onConfirm callback (correctness foot-gun).

---

## Confirmed NON-defects (so the "menus won't scroll" complaint isn't misattributed)
- **Search results ScrollView** — scrolls (plain ScrollView + horizontal rows is the standard pattern).
- **Settings / Update / Backup GuidedStep action lists** — drag-scroll fine and fit on screen.
- **MultiView channel picker, Account dashboard, Parental category list, Speed Test, Player track picker (PhoneTrackPickerSheet)** — all scroll/tap correctly on touch.
The genuine touch-scroll misbehavior lives in **Favorites (toggle fade)**, **Series Detail (autoscroll + list collapse)**, and **Player (15s gesture-dead window)** — everything else perceived as "broken scrolling" is layout cram + focus-steal friction.