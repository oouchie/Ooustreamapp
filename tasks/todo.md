# ACTIVE — 4K movies slideshow on Fire TV Stick 4K Max (mt8696)

**Reported 2026-07-19.** User's personal Fire TV Stick 4K Max plays 4K movies as a **slideshow**
(few fps), audio may be fine. mt8696 is our known-good 4K HW decoder → slideshow = 4K decoding in
SOFTWARE (FFmpeg or c2.android) instead of the HW HEVC decoder.

**The contradiction to resolve:** CLAUDE.md v3.7.0 claims this was fixed — "blanket HEVC
deprioritization caused AFTKRT to fall back to c2.android.hevc.decoder for 4K and slideshow at ~9fps;
now only applies on mt8695/mt8167." If a current build still slideshows on 4K Max, either that fix
regressed, the content is HEVC Main 10 (10-bit) and HW rejects the profile → SW, or it's a different
4K Max revision. DO NOT GUESS — the decoder name in a live logcat settles it.

**VERIFIED ROOT CAUSE (on-device, AFTKRT 192.168.1.84, debug build sideloaded, run-as diagnostic logs):**
The failing "4K movies" are **Dolby Vision Profile 7** remuxes (codec string `dvhe.07.06`, 3840x2160,
.mkv, + TrueHD 8ch audio). The user's TV supports **HDR10 + HLG but NOT Dolby Vision**
(display `mSupportedHdrTypes=[2,3]`). So ExoPlayer must decode the DV P7 **HEVC Main 10 base layer** —
but it routes it to **`ffmpegLavc-hevc` (single-threaded software)** instead of the hardware HEVC
decoder → `fps=0.0-0.7`, 500+ dropped frames, `AUDIO_UNDERRUN`, `free=21MB` → slideshow →
`VIDEO/BLACK_SCREEN` → OOM crash.

The device HAS the hardware to play it: `OMX.MTK.VIDEO.DECODER.HEVC` (4K@60) + `OMX.MTK.VIDEO.DECODER.DVHE.*`.
Plain 4K **AVC** and 8-bit HEVC use hardware fine — only **10-bit / DV** content falls to software.
IPTV Smarters (IJKPlayer) plays the SAME file smoothly because it forces the HW HEVC decoder on the base layer.

**Mechanism (why FFmpeg wins):** framework logs `W/VideoCapabilities: Unsupported mime video/dolby-vision`
(can't report DV decoder caps) AND the mt8696 HW HEVC decoder under-advertises the Main 10 profile → its
`supportsFormat` = EXCEEDS/UNSUPPORTED, while the auto-registered `ExperimentalFfmpegVideoRenderer`
reports FORMAT_HANDLED → ExoPlayer picks software. `setExceedRendererCapabilitiesIfNecessary(false)`
compounds it.

**Fix direction:** force the HEVC base layer onto HARDWARE on devices that HAVE a real vendor HW HEVC
decoder (mt8696), keeping FFmpeg software-video fallback ONLY for devices with no HW HEVC (Allwinner).
Also: prefer AC3 over TrueHD 8ch audio to cut software-decode CPU load. Fix design workflow: wf_899647d2-08f.

**Corrections logged this session (my errors):** (1) initially diagnosed "upstream throughput/provider
CDN" — WRONG, IPTV Smarters proved the network is fine. (2) Then "our HTTP header pollution
(Accept: application/json)" — a real bug but NOT the 4K blocker; those edits were reverted. (3) Measured
"smooth 24fps" and claimed fixed — WRONG PROCESS (pid 20305 = IPTV Smarters, not our app pid 25955).
Lesson: verify the pid/process before claiming a playback result. The DV-P7-software-decode cause above
is verified on the correct process.

**Note on the earlier upfront 4K refusal gate (VideoDecoderCapability):** it's resolution-only + fails
open, and DV caps query returns "Unsupported mime" → null → it does NOT refuse (correct — we want to
PLAY this in hardware, not refuse it).

**Device facts:** AFTKRT, mt8696, armeabi-v7a, Android 11 (API 30), MID tier, memoryClass=192
(heapgrowthlimit; largeMemoryClass=384), display 4K HDR10/HLG (no DV).

---
---

# DONE-IN-CODE (pending device verify) — Continue Watching migration fix + 4K gating

## Problem (VERIFIED in source, 2026-07-18)

Provider swapped their movie backend behind the same domain (`default_server_url` is still
hardcoded `https://flarecoral.com`), so VOD **stream IDs got renumbered**. Freshly-browsed VOD
rebuilds its URL from current credentials → new IDs → plays fine. Continue Watching does not.

Root cause is not stale data, it is that the app **freezes and replays an absolute URL**:

- `watch_progress.extra` stores the entire absolute stream URL (host + username + password +
  streamId + container extension) captured at the previous play — `PlayerViewModel.kt:104` and `:199`.
- Nothing in the codebase ever rewrites, expires, or invalidates it.
- Both Continue-Watching launch paths **prefer** that frozen URL over rebuilding it:
  - `HomeFragment.kt:1853` (Continue Watching row)
  - `HomeFragment.kt:1180` (Pick Up & New row, VOD branch)

So every CW card replays a URL pointing at content that no longer exists on the new backend.

Supporting findings:
- CW makes **zero** network calls before playback — straight from Room to ExoPlayer.
- `watch_progress.streamId` holds the **episode** id for series rows; the series id lives in the
  separate `seriesId` column. Validation must use `seriesId` for series, never `streamId`.
- `ContentRepository.getVodStreams(null)` / `getSeries(null)` each fetch the **entire** catalog and
  are already called on every Home screen creation (`HomeViewModel.loadFeaturedContent()`), so a
  validation pass costs **no additional network**.
- Clearing both tables would also empty "Pick Up & New" (it self-hides via `toggleRow`).
- No user-visible "JSON error" string exists for VOD anywhere in the app — UNVERIFIED which exact
  message the customer sees. Does not change the fix.

## Decisions (user, 2026-07-18)

- Continue Watching: **smart prune against the live catalog** (not a full wipe) — keep bookmarks
  whose IDs still exist on the new server, drop the rest.
- "Pick Up & New": **leave as-is** — no code change.

## Plan

### A. Stop replaying frozen URLs (the actual cure)
- [ ] Add a helper that pulls just the container extension out of a stored URL
      (strip query/fragment, take after last `.`, validate shape).
- [ ] `HomeFragment.navigateToContinueWatching()` (~:1852): rebuild the URL from **current**
      credentials via `viewModel.build*StreamUrl(id, ext)`, using only the extension from `extra`.
- [ ] `HomeFragment` Pick Up & New VOD branch (~:1180): same treatment.
- [ ] Keep the extension (v4.2.1 lesson: a hardcoded `"mp4"` fails `.m2ts` titles on first attempt).

### B. Smart prune (self-healing, no extra network)
- [ ] New `data/repository/WatchHistoryPruner.kt` (`@Singleton`).
- [ ] `pruneVod(liveIds)` — drop `type='vod' AND seriesId IS NULL` rows whose `streamId` is gone.
- [ ] `pruneSeries(liveSeriesIds)` — drop rows whose `seriesId` is gone, plus orphan `series_tracking`.
- [ ] **Guard: never prune on an empty/failed catalog fetch.** Empty list → no-op.
- [ ] **Compute the dead set in Kotlin, delete by the small list.** A `NOT IN (:8000 ids)` would blow
      SQLite's 999-variable limit on API 23. Chunk deletes.
- [ ] Validate against the **raw/unfiltered** catalog so parental-blocked categories never cause deletes.
- [ ] New DAO methods: `getAllOnce()`, `deleteByStreamIds()`, `deleteBySeriesIds()` on
      `WatchProgressDao`; `deleteBySeriesIds()` on `SeriesTrackingDao`.
- [ ] Hook into `HomeViewModel.loadFeaturedContent()` where both catalogs are already in hand.
      Once per session (flag), not on every Home return.
- [ ] Log a `WATCH_HISTORY_PRUNED` diagnostic event with counts.

### C. 4K gating on non-4K devices (second request, investigation running)
- [ ] Decide gate placement + capability signal once the investigation lands.
- [ ] Note: IPTV streams are single-bitrate — a track-selector cap cannot pick a smaller rung, so
      "blocking" means fail-fast with honest copy, not silent downscale.

### D. Ship
- [ ] `assembleDebug` + `assembleRelease` clean.
- [ ] Device-verify on AFTKRT before release (lessons.md: playback-path changes get device-verified).
- [ ] Version bump + `update.json` + CLAUDE.md release-history entry.

## Follow-ups (deliberately NOT in this change)
- `watch_progress.extra` still stores the username+password in plaintext in an unencrypted Room DB,
  and `BackupService` restores it verbatim. Once nothing trusts it as a URL, it should store only the
  container extension. Deferred to keep this change surgical and reviewable.

## Review (implemented 2026-07-18)

**What shipped (2 independent fixes, both requested this session):**

### Fix 1 — Continue Watching after the VOD-server migration
- `HomeFragment` no longer replays the frozen `watch_progress.extra` URL. Both launch sites
  (`navigateToContinueWatching`, Pick Up & New VOD branch) rebuild from **current** credentials via
  `viewModel.build*StreamUrl(id, ext)`, keeping only the container extension parsed from the saved
  URL by the new `containerExtFrom()` helper. This is the actual cure — even a bookmark that survives
  the migration now points at the right server.
- New `data/repository/WatchHistoryPruner.kt` (@Singleton) deletes `watch_progress` / `series_tracking`
  rows whose id is gone from the live catalog. Called from `HomeViewModel.loadFeaturedContent()`
  against the **raw** (unfiltered) VOD + series catalogs that function already fetches — zero extra
  network. Once per session; no-op on an empty/failed fetch; dead set computed in Kotlin and deleted in
  400-id chunks (SQLite 999-param ceiling); series validated by `seriesId`, VOD by `streamId`.
- New DAO methods: `WatchProgressDao.getAllOnce/deleteByStreamIds`, `SeriesTrackingDao.deleteBySeriesIds`.
- "Pick Up & New" left as-is per user decision — it auto-hides while empty and keeps whatever survives.

### Fix 2 — refuse oversized (4K) video upfront on non-4K sticks
- **Root cause found + verified in Media3 1.10.0 source:** the tier `setMaxVideoSize(1080p)` cap does
  NOT block 4K — `exceedVideoConstraintsIfNecessary` defaults to `true`
  (`DefaultTrackSelector.java:1790`), so a single-track 2160p IPTV stream is selected anyway. The old
  code comments claiming the cap "blocks 4K" were factually wrong; corrected them.
- New `common/VideoDecoderCapability.kt`: asks the device's real MediaCodec decoders via
  `MediaCodecUtil.getDecoderInfos` + `isVideoSizeAndRateSupportedV21`. Returns null when inconclusive
  → **fails open** (never refuses working content on doubt). `MediaCodecUtil` lists only MediaCodec
  decoders, not the auto-registered FFmpeg SW renderer — which is exactly the Allwinner failure
  (FFmpeg claims 4K support, then plays at 0fps).
- `onTracksChanged` refuses with a friendly error only when `canDecode` returns exactly `false`,
  guarded by `oversizedVideoRefused` (reset in `resetTrackStateForNewContent` + `tuneToChannel`; NOT
  cleared by Retry, so Retry is a genuine escape hatch). Turns the ~2.5-min watchdog thrash into an
  immediate honest message. The v4.2.3 watchdog give-up stays as the backstop.

**Verification:**
- `:app:compileDebugKotlin`, `assembleDebug`, `assembleRelease` (R8) all clean.
- Adversarial review — THREE rounds (I initially mis-reported "all refuted" by reading a mid-run
  journal; see lessons.md):
  - Round 1 (5 dimensions + verify): 12 candidates → **4 confirmed**, all in the new code:
    (P1) 4K gate read stale cross-channel cached resolution → refused the NEXT channel on a zap;
    (P2) same root cause via tuneToChannel not clearing the cache;
    (P3) prune awaited on the hero critical path + built the ~8k-id set on the main thread;
    (P3) pruner catch(Exception) swallowed CancellationException → false failure log on nav-away.
    Refuted: account-switch (server URL hardcoded), fps cache key (never populated by Xtream — fixed
    anyway), Pick Up & New dead branch (pre-existing, harmless).
  - Fixes: gate moved INSIDE the video-track let (reads current videoFormat, not the cache) +
    tuneToChannel clears cached dims; prune moved off the hero path into the parallel block on
    Dispatchers.Default; pruner rethrows CancellationException before its generic catch.
  - Round 2 (re-review): all 3 prior fixes confirmed resolved; **1 new P3** — my bare
    `launch { pruneVod }` could let a rare log-rotation IOException (StreamDiagnosticLogger.write
    calls rotateIfNeeded OUTSIDE its try) escape and cancel the sibling content-row launches. Fixed
    by wrapping the launch in catch(CancellationException){throw}/catch(Exception){}.
  - Round 3 (focused): row-blanking hole confirmed CLOSED for both prune calls; made the pruneSeries
    launch's CE handling symmetric (cosmetic).
- **NOT device-verified** — no Fire TV reachable on the network this session (tried .84/.82/.154/
  .155/.222). lessons.md requires an on-device walkthrough for playback-path changes before release.

**Outstanding before a GitHub release:**
- [ ] Sideload on AFTKRT: open a CW movie that existed pre-migration → confirm it plays (URL rebuild),
      confirm dead bookmarks disappear from the row after one Home load (prune), and confirm a known
      4K title shows the honest error immediately instead of a 2.5-min black screen.
- [ ] Version bump (→ v4.2.4 / versionCode 92), `update.json`, CLAUDE.md release-history entry.

## Follow-ups (deliberately NOT in this change)
- `watch_progress.extra` still stores username+password in plaintext (unencrypted Room), and
  `BackupService` restores it verbatim. Now that nothing trusts it as a URL, it could store only the
  container extension. Deferred to keep this change surgical.

---
---

# ARCHIVED — EPG Screen Redesign (shipped in v4.2.2)

Reference: `/Users/oouchiebates/Downloads/IMG_8798.JPG` (TiviMate-style guide).
Goal: make the EPG guide resemble the reference, plus 4 confirmed upgrades.
Target = Fire TV / 10-foot D-pad (Kotlin app is Fire-TV-only; phone is the separate Flutter app).

## Confirmed scope (from user)
- **Header preview**: TIERED live preview — auto-play focused channel video on capable
  devices; static logo + now/next panel on low-RAM sticks (mt8695 / Ooustick).
- **Toolbar**: Global Search · Full-screen EPG (toggle header off) · Hours (2h/4h/8h) ·
  Now (jump-to-now) · date label. **No Record, no Edit EPG.**
- **Upgrades (all 4)**:
  1. Genre color-coding of program cells (sports/movies/news/kids…) via ChannelNameParser.
  2. Now/next live progress bar inside the currently-airing cell in EVERY row.
  3. Inline favorite heart per channel (D-pad + touch toggle), Favorites-first ordering.
  4. Selectable + persisted time window (2h / 4h / 8h).

## Architecture decisions
- Keep the existing Canvas-lane engine (GuideProgramLaneView / GuideTimelineController /
  GuideTimeHeaderView / GuideRowAdapter) — right 60fps approach. Extend, don't replace.
- `windowDurationMs` becomes mutable, driven by the Hours selector (persisted in prefs).
- New header is a sibling above the toolbar + grid; collapsible for "Full screen EPG".
- Tiered preview reuses LivePreviewManager (muted, audio-isolated) into a header surface.

## Plan
- [x] R: research APIs (preview player, device tier, favorites, EPG now/next + genre, nav/resources)
- [x] 1. GuideTimelineController: mutable window duration (setWindowDuration), keep focus/now logic
- [x] 2. GuideProgramLaneView: genre color accent + live-progress in current cell + 2-line cell (title + time range)
- [x] 3. Header: channel logo, title, now-playing (time + progress), up-next, tiered live preview surface (in fragment XML)
- [x] 4. Toolbar row: Global Search, Full-screen toggle, Hours selector, Now, date — D-pad pills
- [x] 5. item_guide_row.xml + GuideRowAdapter: favorite heart, channel number/name
- [x] 6. fragment_epg_grid.xml: restructure into header + toolbar + ruler + grid + hints
- [x] 7. EpgGridViewModel: now/next via row lanes, favorite toggle/observe, genre classification, Favorites-first ordering
- [x] 8. EpgGridFragment: wire tiered header preview, toolbar actions, hours persistence, focus flow, full-screen toggle
- [x] 9. Strings + drawables (ic_heart_outline, bg_guide_pill)
- [x] V1: assembleDebug clean (44s, only pre-existing warnings)
- [x] V2: adversarial review (5-agent research + 3-dimension review, each finding verified) — 3 confirmed of 19
- [x] V3: address confirmed findings + rebuild clean (17s)

## Review
**Build:** `assembleDebug` clean (Kotlin + resources), no new warnings. NOT yet device-verified.

**Proactive perf fix (pre-review):** `GuideProgramLaneView.onDraw` formatted a SimpleDateFormat time-range
string per cell per frame (GC churn on 1GB). Now precomputed into `rangeLabels` on the `programs` setter.

**Adversarial review — 3 confirmed of 19 candidates (16 verified non-issues), all fixed:**
1. (medium) `headerVisible` survived back-stack instance but view re-inflated VISIBLE → preview silently
   suppressed + toggle inverted. Fix: reset `headerVisible = true` in onViewCreated.
2. (low) Back-from-playback focus landed on row 0, not the tuned channel. Fix: `pendingRestoreStreamId`
   set in tuneToChannel; restored in the submitList COMMIT callback (race-free, per v3.7.13 lesson),
   position-then-requestFocus. onResume simplified to the no-recreation case.
3. (low) Header labeled a future program "now playing" during sub-5-min real-EPG holes at NOW. Fix:
   `current` = only a program containing now; gap surfaces under "Up next", now slots blank.

**Deliberate deviations from the reference:** NOW line kept GOLD (app-wide convention) not red; Record +
Edit EPG dropped (Xtream has no DVR/EPG-edit); favorite toggle = hold-OK (no focus-stealing heart column).

**Outstanding (not blockers):**
- SurfaceView preview corners won't clip to the rounded frame (SurfaceView ignores clipToOutline) —
  same as the existing LiveTv preview; cosmetic. Could switch PlayerView to texture_view if it bothers.

---
## ARCHIVED — Phone touch fixes (paused; superseded by Flutter phone-app decision)
NOTE: the files below were described as uncommitted in a prior session but are NOT present at
current HEAD (EpgGridFragment.kt / fragment_epg_grid.xml are the clean v4.x versions). Kept for
history only. Kotlin app is now Fire-TV-only — these phone-touch items are out of scope.

- Grid touch-scroll fix (Leanback VerticalGridView → RecyclerView+GridLayoutManager on phone),
  common/TouchGridSetup.kt, single-tap focus stripping, requestFocus cursor-restore gating to isTV.
- STILL-TODO (touch): Home horizontal rows, Search results, EPG guide rows, Favorites scores row.
- DECISION (now resolved): separate Flutter phone app (Android+iOS), Kotlin stays Fire TV.

---

## 2026-07-19 — Ooustick D-pad dead / "can't leave the Home screen" (customer unit 192.168.1.250)

### Status: FIXED + device-verified. NOT released (debug build on customer box, per instruction).

### Root cause (VERIFIED on-device, not inferred)
Ooustick (Allwinner H616, `sun50iw9p1`) reports `mCurUiMode=0x11` (NORMAL) and declares no
`leanback` / `television` / `touchscreen` feature. `DeviceUtils.isTV()` tested uiMode ALONE →
false; `sw540dp < 600` → `isPhone()` true. All 72 `isTV()/isPhone()` call sites across 37 files
flipped to touch mode. `TouchGridSetup.stripItemFocusForTouch()` then set `isFocusable=false` on
every card (10 HomeFragment call sites) and `MainActivity.isSidebarAllowedForCurrentFragment()`
returned false → no cursor, no nav menu.
Measured before fix: focus parked on `top10_row` with 0 focusable descendants; 5 focusable nodes.

### Regression window (git-verified)
- v4.1.0 `20cb109`: no card focus-strip. D-pad worked (phone mode, but cards focusable + bottom nav).
- **v4.2.0 `eef9171` (2026-06-12, "phone touch overhaul") introduced `TouchGridSetup`** → broke it.
- Customer device `lastUpdateTime=2026-07-12` (updated to 4.2.3) — matches their report exactly.
- **Every Ooustick in the field on >= 4.2.0 is affected.**

### Changes (uncommitted)
1. `common/DeviceUtils.kt` — `isTV()` ORs leanback ‖ television ‖ uiMode ‖ **no-touchscreen**;
   cached; added `describe()` for diagnostics + `setTvOverrideForTest()`.
2. `res/layout-television/activity_main.xml` → `res/layout/activity_main_tv.xml`; `MainActivity`
   selects it at runtime. REQUIRED — fixing isTV() alone crashed at `setContentView`
   (`InflateException`: Material `BottomNavigationView` under Leanback theme), because the OS still
   picks `layout/` from uiMode regardless of our Kotlin.
3. `OoustreamApp` — logs `DEVICE_CLASS` at startup via `DeviceUtils.describe()`.

### Device verification (192.168.1.250, in-place `-r` install, user data preserved)
- focusable nodes on Home 5 → 19; gold cursor visible on section cards
- DOWN: `section_card_root` → `poster_root`; RIGHT: `[6,520]` → `[160,520]`
- **OK on "Movies" → `VodFragment`** (left the Home screen — the reported symptom)
- Movies renders 4 columns (was 2); remote-hint bar restored
- Live TV played: `ffmpegLavc60.3.100-ac3`, HW decoder proven good 120 frames/2000ms

### TODO — follow-ups (NOT done)
- [ ] Cut the real release (bump 4.2.4 / vc92, `assembleRelease`, update.json, GitHub release) so
      all field Oousticks get the fix OTA. Customer box currently runs a DEBUG apk.
- [ ] De-qualify the remaining `-television` resources so Ooustick gets true TV sizing:
      `values-television/{dimens,colors,integers}.xml` (posters 110x165 → 180x270dp, hero 45% → 65%)
      and `layout-television/fragment_{vod,series,live_tv}.xml` (currently plain `RecyclerView`
      instead of Leanback `VerticalGridView` → focus-driven EPG loading + preview-on-dwell never fire).
- [ ] Consider a unit test on `DeviceUtils.computeIsTv` signal fusion (no test infra in repo today).
