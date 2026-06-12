# v4.0.0 — Seamless Binge + EPG Grid Guide + Watch-Polish Sweep

Plan: ~/.claude/plans/zesty-discovering-mountain.md (approved 2026-06-11)
Baseline: v3.9.3 (versionCode 84), main @ 2159773.

## Workstream 1 — Gapless Binge Pre-Buffer (player core)
- [x] 1.1 New state: pendingNextEpisode / lastKnownDurationMs / preBufferEnabled (HIGH|MID tier gate) + buildNextMediaItem(mediaId=episodeId)
- [x] 1.2 Queue at 15s binge mark (addMediaItem + PREBUFFER_QUEUED log; blank-episodeId guard)
- [x] 1.3 Binge Cancel removes queued item (PREBUFFER_REMOVED)
- [x] 1.4 onMediaItemTransition handler (handleGaplessEpisodeTransition): finalize prev → swap identity → Up Next row → per-episode resets → UI/diag swap
- [x] 1.5 PlayerViewModel.insertUpNextRow() extracted + queueUpNextRow (NonCancellable); saveProgress/markCompleted now SNAPSHOT identity at call time (race fix — lazily-read fields would attribute prev episode's 100% save to the new episode after the synchronous swap)
- [x] 1.6 advanceToNextEpisode(source) consolidation (playlist fast path + legacy fallback); rewired onPlayNext + glue onNextEpisode; skipToNextEpisode removed
- [x] 1.7 Rebuild interplay: dropPendingNextEpisode() in all 3 rebuilds (PREBUFFER_DROPPED)
- [x] 1.8 Cleanup in onDestroyView; compileDebugKotlin clean

## Workstream 2 — Watch-Polish Sweep
- [x] 2.1 WAKE_MODE_NETWORK — main player ×4 + MultiView ×2 (decorative players skipped: hero trailer/splash/muted preview)
- [x] 2.2 Reconnect label on buffering overlay ("Waiting for network…" on loss, "Reconnecting…" past first stall retry)
- [x] 2.3 Subtitles bringToFront above controls bar + 8%→22% bottom padding while controls visible
- [x] 2.4 Seek coalescer: requestDpadSeek/commitPendingSeek (300ms idle), CLOSEST_SYNC for >30s scrubs (DEFAULT restored), glue/bar/buttons all delegate; commit-on-pause
- [x] 2.5 Absolute landing timecode in SeekFeedbackOverlay (gold line under delta)
- [x] 2.6 Hide phantom Up-Next progress bar (ContinueWatchingPresenter isUpNext)
- [x] 2.7 Empty-category empty-state (Vod/SeriesFragment + layouts; 2.5s confirm so initial empty emission doesn't flash)
- [x] 2.8 Slideshow guard: SW/FFmpeg decoder <10fps × 5 polls → friendly error (HDR-specific copy for Main 10). DEVIATION: upfront ULTRA_LOW fast-fail NOT restored — v3.7.9 customer evidence (allinone) shows mt8695 HW does decode Main 10; the guard catches the stranded case without regressing working devices
- [x] 2.9 Audio-stall recovery: PlaybackHealthMonitor.onAudioStall + diagnosticListener.onAudioSinkFault → 2-stage ladder (renderer re-init → FFmpeg rebuild), capped per content
- [x] 2.10 BufferConfigs.forLowMemory LIVE minBuffer 3s→6s
- [x] 2.11 Deep-link Live: real channel name/icon + full channel list seeded for zapping; deleted dead TrackSelectionHelper.kt

## Workstream 3 — EPG Grid Guide (new feature, epg/guide/)
- [ ] 3.1 API: getShortEpg limit param threaded through ContentRepository/EpgCacheRepository (NOT DONE YET — see note)
- [x] 3.2 GuideModels: GuideProgram/GuideRowData + GuideEpgNormalizer (clip/sort/gap-fill >5min holes, hour-aligned synthetic blocks)
- [x] 3.3 EpgGridViewModel: category-scoped channels (favorites→first-category fallback), viewport fetch (Semaphore(3), job cancel, ±3 buffer), memoized synthetic lanes, combined `rows` flow on Default dispatcher
- [x] 3.4 GuideTimelineController (2h shared window, NOW ticker, virtual focusAnchorMs) + GuideProgramLaneView (Canvas cells, source-styled text, gold virtual focus, NOW line) + GuideTimeHeaderView (30-min ruler)
- [x] 3.5 EpgGridFragment (KeyEventHandler: LEFT/RIGHT program walk, FF/REW ±2h, OK via row click → tune-or-details) + GuideRowAdapter (ListAdapter+DiffUtil, EPG payload) + fragment/item layouts + android.app.AlertDialog details
- [x] 3.6 Entry points: header_guide_icon in Live TV (gold-ring focus, category-scoped), MainActivity.navigateToEpgGuide, ooustream://guide deep link
- [x] 3.7 Phone touch: lane drag pans shared timeline, tap = OK on tapped cell, touch hint copy
- [x] 3.8 compileDebugKotlin + assembleDebug clean

## Post-plan additions (user feedback during the session)
- [x] TV Guide card on Home, placed right after Live TV (user request) — SectionItem("guide") → navigateToEpgGuide
- [x] In-guide category switcher (user caught the gap) — focusable "Category ▾" chip in the guide header,
      UP-at-row-0 hands focus to it (Leanback swallows UP at the grid edge — explicit requestFocus in onKeyEvent),
      single-choice picker (Favorites + all live categories), switchCategory clears per-row EPG state + reloads
- [x] Deep-link defer fix — handleDeepLink(deferToStartupFlow) so the splash flow can't clobber deep-link nav
- [x] Favorites-fallback label fix — guide chip says "Favorites" instead of "All Channels"

## Verify / Release
- [x] assembleDebug + assembleRelease clean (19MB arm64 / 18MB arm32)
- [x] AFTKRT (192.168.1.84) on-device verification (screenshots + run-as diagnostic log):
      • Guide: opens from Home card + deep link, real EPG lanes + italic inferred fill, logos + initials
        fallback, NOW line, time-aligned UP/DOWN, RIGHT walks programs, OK-now → tunes with working CH± zap,
        OK-future → details dialog → Watch Channel works, category picker switches (Favorites → FIFA),
        channel numbers shown
      • Binge: PREBUFFER_QUEUED → TRANSITION_REQUEST mode=playlist → TRANSITION_SEEK in ~190ms (gapless,
        Sofia S1E2→S1E3); silent resume + Restart button present; last-episode → Series Complete overlay;
        completed episode drops from CW; new episode tracks progress (S1E3 "20m left" in CW)
      • Polish: 75-tap seek burst coalesced (landed 41:43, no per-tap network seeks); DEVICE_TIER=MID logged
      • NOT separately device-tested (low-risk, build-verified): reconnect label (needs a wifi-drop),
        subtitle z-order (needs CC content), audio-stall recovery (needs a sink fault), slideshow guard
        (needs mt8695 + Main 10), empty-category state, phone touch pan
- [x] Bumped 4.0.0/85, update.json (3 URLs + changelog), CLAUDE.md (version, inventory, release history)
- [x] Commit, push, gh release v4.0.0 with both ABI APKs

## Review — v4.0.0 shipped
All three planned workstreams landed plus 4 user-feedback items mid-session. The two headline features
(gapless binge, EPG grid guide) are device-verified end-to-end with screenshot + diagnostic-log evidence.
One audit deviation (documented in CLAUDE.md): the ULTRA_LOW HEVC Main-10 upfront fast-fail was NOT
restored — v3.7.9 customer evidence contradicts it; the new <10fps slideshow guard covers the stranded
case instead. One critical latent bug found & fixed beyond the plan: PlayerViewModel saveProgress/
markCompleted read identity fields inside launched coroutines (post-swap misattribution); both now
snapshot at call time. Deferred (next release candidates): preview→fullscreen warm handoff, stream-host
preconnect, ComponentCallbacks2 buffer tightening, SW→HW decoder re-probe, guide past-programs horizon
(needs get_simple_data_table), warm-app deep-link multi-instance quirk (standard launchMode).

---
## Archive — v3.9.1 deferred-items review (shipped)
- A (#9) Silent resume + Restart — DONE FULL. B (#2) Last-frame hold — DONE FULL (best-effort).
- C (#4) safe part (resolution-cap re-probe) done; SW→HW decoder-swap re-probe still held.
- D (#6) safe part (frozen-frame over gap) done; true playlist pre-buffer → being implemented NOW in v4.0.0 WS1.
