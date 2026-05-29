# Home screen improvements (batched — single commit + version bump at end)

Context: Quick Tune already removed (uncommitted, in working tree). All 4 upgrades requested.
Device risk: mt8695/AFTSS diverges from fast devices — compile after each step.

## 0. Quick Tune removal (DONE, uncommitted)
- [x] Remove views, fields, methods, ViewModel block, deleted presenter/layout/drawables
- [x] compileDebugKotlin + processDebugResources clean

## 1. Jump-back-in hero CTA
- [ ] Track latest Continue Watching item (`latestResumeItem`) from observeContinueWatching
- [ ] Hero primary button label -> "▶ Resume: {title}" when a resume item exists, else "Watch Now"
- [ ] Watch Now click resumes latestResumeItem; More Info plays featured
- [ ] Refresh CTA on CW updates + onResume
- [ ] compile

## 2. Row lazy-loading (below-the-fold defer)
- [ ] Below-fold observers (Trending, TrendingSeries, Top10, BecauseYouWatched, GenreRows)
      deferred until first scroll OR 1200ms fallback, one-shot
- [ ] compile

## 3. Hero depth + content fade-in polish
- [ ] View.fadeInRow() helper; rows fade alpha 0->1 when first shown
- [ ] compile

## 4. Consolidate New Episodes + Watch It Again into one "Pick Up & New" rail
- [ ] Verify card heights match; ClassPresenterSelector; combine() flows; click dispatch by type
- [ ] compile

## Finalize
- [ ] Full assembleDebug; update CLAUDE.md + bump version; commit batched

## Review

Status: ALL DONE, uncommitted (batched per user). assembleDebug clean.

- **0. Quick Tune removed** — views/fields/methods/ViewModel block gone; ChannelStripPresenter +
  item_channel_strip + 2 drawables deleted. Drops a recommendation recompute on every Home load.
- **1. Jump-back-in hero CTA** — `latestResumeItem` tracked from Continue Watching; hero primary
  button shows "▶ Resume: {title}" and resumes via navigateToContinueWatching(); More Info plays
  the featured title (stays reachable). Falls back to "Watch Now" + featured when no resume item.
- **2. Lazy-load** — `BecauseYouWatched` + genre rows (the two dynamic-inflation observers) deferred
  to first scroll OR 1200ms fallback (one-shot, guard reset per view). Static XML rows kept immediate
  so focus restore is unaffected. Trigger added to existing NestedScrollView scroll listener.
- **3. Content fade-in** — `toggleRow(label,row,show)` helper fades each row in on first appearance;
  replaced 6 duplicated visibility blocks (also a dedup win).
- **4. "Pick Up & New" rail** — New Episodes + Watch It Again merged into one HorizontalGridView via
  ClassPresenterSelector (both cards share card_poster_width/height). combine() flows, new episodes
  first, watch-again series deduped against new-episode series. Old watch_again row/label/observers
  removed from XML + Kotlin + focus save/restore + mobile grid list.

Risk notes:
- Mixed-type in-place safeReplaceAll on the Pick Up rail (HorizontalGridView, not the crashing
  VerticalGridView GridLayoutManager) — standard RecyclerView view-type-change handling, small list.
- Lazy-load: returning to a deep below-fold position may land focus on hero if data not yet bound
  (graceful). Static rows excluded from defer specifically to avoid this for the common case.

Verification done: compileDebugKotlin after each upgrade; processDebugResources; full assembleDebug.
NOT verified on-device (slow-hardware divergence) — sideload to AFTSS recommended before release.

## Live TV UI/UX overhaul (Netflix-lead audit) — DONE, uncommitted, pushed to .84
- [x] P0-1 Auto-preview on focus dwell (~1s, TV only, skips low-mem mt8695)
- [x] P0-2 "On now" line on every channel row (SmartEpgFiller.inferRuleBased — pure, no DB; ChannelPresenter epgResolver)
- [x] P0-3 OK always = fullscreen (removed two-press preview/fullscreen model)
- [x] P1-4 Bigger preview (panels 22/33/45, preview 60/40) + now-playing overlay (channel + ON NOW + live progress)
- [x] P1-5 Killed instructional text (nav_hints bar + preview_focus_hint removed; placeholder = play glyph)
- [x] P1-6 MultiView header icon no longer 0.4 alpha (read as disabled)
- [x] P1-7 Removed redundant panel category_search (header search is the one)
- [x] P2-8 Channel card text bumped (name 13->15, epg 11->12, time 10.5->11; epg contrast up)
- [x] P2-10 Preview crossfade (alpha 0->1, 220ms) instead of hard visibility swap
- [x] compile + assembleDebug clean; installed+launched on 192.168.1.84
- DEFERRED (features, own pass): #9 recency cluster atop channel list, #11 channel-number quick-jump

## Live TV FUNCTIONALITY audit (4 specialist agents) — P0+P1 fixed, uncommitted, on .84
- [x] P0-1 Muted preview: disable AUDIO track + handleAudioFocus=false + volume=0 (LivePreviewManager).
      Was playing full-volume audio + grabbing focus; auto-preview made it fire on every dwell.
- [x] P1-2 Channels VerticalGridView "Invalid item position -1" guard (setItems(null) + synchronous
      in-bounds selectedPosition in updateChannelList) — same class as v3.7.13 VOD/Series fix.
- [x] P1-3 Preview panel D-pad dead-end fixed (nextFocusUp/Down -> channels_list).
- [x] P1-4 Auto-preview dwell job guarded with isAtLeast(RESUMED) after delay (no bg decoder on pause).
- [x] P1-5 Stopped focus-driven EPG guess swap: focused card only UPGRADES to a hedged "Likely:"
      PATTERN_CACHE result, never swaps one bare rule guess for another.
- [x] P1-6 loadCategories + selectCategory single-flight Job guards (no overlapping collectors on
      back-stack re-entry / rapid category switch).
- [x] compile + assembleDebug clean; installed+launched on .84
- VERIFICATION NOTE: 2 agents called the ChannelPresenter CoroutineScope a P0 crash; reading the
  code, the job is stored synchronously and cancelled in onUnbindViewHolder -> downgraded to P2.
- DEFERRED P2 (verified, low urgency): EPG inclusive-boundary off-by-one (1s cosmetic);
  ChannelNameParser re-sorts 75-key map per parse (hoist to val); learnPattern non-transactional +
  write amplification; ChannelPresenter presenter-scoped SupervisorJob tidy-up.

## Finalize (pending user)
- [ ] sideload-test Home + Live TV on device (.84 done; AFTSS/mt8695 for low-mem path)
- [ ] update CLAUDE.md (Home + Live TV) + version history; bump version
- [ ] commit batched (Quick Tune removal + Home 4 upgrades + Live TV overhaul)
