# ACTIVE — MultiView (and single-player) live freeze: the keep-alive seek (2026-09-17)

DIAGNOSED BY LIVE INSTRUMENTATION on .82 (AFTKRT / mt8696, debug 4.2.16) while the user was in
MultiView. Symptom: individual tiles freeze ~5-6s, roughly once per minute per tile, often 2-3 at
once. Self-inflicted.

MECHANISM (every link verified against the local Media3 1.10.0 clone in ffmpeg-build/media3-source,
not inferred):
- MultiViewStallDetector.startKeepAlive fired `player.seekToDefaultPosition()` every 60s per slot.
- An Xtream .ts body has no Content-Length and TsExtractor reports an unseekable map with unknown
  duration, so ProgressiveMediaPeriod marks it live progressive. Its in-buffer seek branch is
  EXCLUDED for that source type, and an unseekable map forces the position to 0 — so ANY seek
  cancels the load and re-opens the HTTP request. There is no cheap seek on this source type.
  (This is why the "seek forward inside the buffer" idea was rejected — it is unreachable.)
- TsExtractor.seek then declines to reset its TimestampAdjuster (the reset test requires a
  non-zero first-sample timestamp; TsExtractor is built with `new TimestampAdjuster(0)`), so the
  re-opened stream keeps the timestamp baseline of the connection opened 60s earlier while the
  renderer position resets to 0.
- Result: every incoming frame is ~60s EARLY. VideoFrameReleaseControl holds anything more than
  MAX_EARLY_US_THRESHOLD = 50_000us early, so it holds them all. Exactly one frame force-renders
  (firstFrameState = NOT_RENDERED after the flush). Hence the fingerprint: renderedOutputBufferCount
  static with drops/s=0 — frames HELD, not dropped.
- Captured proof: `First PTS after Flush = 1000060094000` minus INITIAL_RENDERER_POSITION_OFFSET_US
  (1_000_000_000_000) = 60.094s of skew.
- HARD_RESET was the only rung that recovered, because setMediaItem+prepare rebuilds the extractor
  and its TimestampAdjuster. And because it returns the slot to STATE_READY it RE-ARMED the 60s
  keep-alive — hence the fixed cadence forever.

TWO OF MY OWN EARLIER CLAIMS WERE WRONG — do not repeat them:
1. "The tiles are 56-70s behind live." FALSE. `bufferedPosition` is the ABSOLUTE
   largestQueuedTimestamp while currentPosition is re-based to 0 on reconnect, so their difference
   compares two reference frames. 60,094 - 3,624 = 56,470 = the logged number exactly. It is
   timestamp skew, not latency. The app cannot measure real live latency on this source type at all
   (getCurrentLiveOffset() is TIME_UNSET).
2. "Replace it with an in-buffer forward seek." IMPOSSIBLE — see above.
Also: deleting setLiveConfiguration would NOT disarm the keep-alive. MediaItem.liveConfiguration is
never null and liveness comes from the unknown-length body, so isCurrentMediaItemLive stays true.
The LiveConfiguration (targetOffsetMs=3000, 0.97-1.03 speed window) is INERT anyway because
ProgressiveMediaSource hardcodes isDynamic=false.

## Plan
- [x] `multiview/MultiViewStallDetector.kt` — DELETE the keep-alive (fn, call site, job field,
      cancels, constant) with a comment saying why it must not come back.
- [x] `multiview/MultiViewPlayerManager.kt` — softReset() is a logged NO-OP for live (it was rung 1
      manufacturing the freeze); non-live keeps seekTo(currentPosition). Corrected the false
      "seek to live edge, ~100ms, invisible" and "start 3s behind live edge" comments.
- [x] `multiview/MultiViewStallDetector.kt` — audio-slot ladder skips SOFT_RESET for FROZEN and goes
      straight to HARD_RESET (with soft inert + 30s cooldown it would sit frozen 60s otherwise).
- [x] NEW `player/LiveResync.kt` — one shared `ExoPlayer.resyncProgressiveLive(item?)`
      (stop/clearMediaItems/setMediaItem/prepare/play). Rebuilding the MediaItem is the ONLY thing
      that re-seeds the extractor. Used by MultiView hardReset + all 3 single-player sites.
- [x] `player/OoustreamPlaybackFragment.kt` — the SAME defect on the single-channel live path, 3
      sites: LIVE_STREAM_ENDED auto-retry (~1408), network-return (~1714), onResume (~4112). At the
      first two the trailing prepare() was DEAD CODE (prepare() no-ops unless STATE_IDLE and the
      seek had already masked state to BUFFERING), so the destructive seek was the whole recovery.
      onResume had no prepare() at all.
- [x] `multiview/MultiViewStallDetector.kt` — buffer signal now rejects values > 20_000ms
      (largest configured maxBufferMs is 10_000) and logs `MV_LIVE_POSITION_SPLIT` with RAW
      pos/buffered instead. The inflated value silently scored a wedged slot as SMOOTH, which is
      how this survived three audits.
- [x] `multiview/PlaybackHealth.kt` — fixed the RecoveryAction.SOFT_RESET comment (the false
      "~100ms, invisible" premise).
- [x] `multiview/MultiViewFragment.kt` — recovery-mask safety timeouts held per slot and cancelled
      by onFirstFrameAfterRecovery, so a stale timer can't un-mask a LATER recovery.
- [x] `:app:compileDebugKotlin` clean, no new warnings from any touched file.
- [ ] SOAK on .82 or .84 (AFTKRT), 4 live tiles, 10+ continuous minutes. Baseline is measured, so
      this is unambiguous: `adb logcat -s OOUSTREAM_AUDIO` must show ZERO `keep-alive seek-to-live`,
      ZERO `health SMOOTH -> FROZEN`, ZERO `HARD RESET`. Also `grep -E 'MediaCodecLogger.*(Flushing|
      Possible seek found)'` should be quiet — a "Possible seek found" with cur PTS BELOW last PTS
      means some seek path was missed.
- [ ] Single-player LIVE walk (code-verified only, NOT measured): tune live, Home, wait 20s, return
      — must resume in a couple of seconds with no frozen frame and no WATCHDOG_HARD_RESET. Repeat
      with WiFi off/on mid-stream.
- [ ] Ooustick pass (a Fire TV pass is not sign-off).

## Open / deferred
- UNEXPLAINED: slot 2 took keep-alive seeks at 17:51:09, 17:54:10 and 17:55:11 with NO freeze, while
  slots 1/2/3 all froze on their own timers in the 4-slot session. Mechanism predicts it should
  freeze. Best guess: that channel is HLS/.m3u8 rather than a raw .ts (for HLS the seek genuinely
  resolves to the live edge), but the URL was NOT confirmed. Open.
- DEFERRED, and now the most important remaining gap: nothing monitors a MultiView slot wedged in
  STATE_BUFFERING. evaluateHealth returns early unless STATE_READY, the watchdog skips unless
  STATE_READY, and the STATE_BUFFERING listener zeroes noNewFramesSinceMs. This is the MultiView
  mirror of the v4.2.16 LIVE bug and wants the same treatment (poll bufferedPosition while
  BUFFERING, escalate only when completely static, log MV_SOURCE_STALL_ARMED). Net-new live logic —
  its own release, its own soak.
- Latency: no automatic bound now that keep-alive is gone. Accepted deliberately — keep-alive never
  bounded it. If it ever matters, measure externally first (same channel on a single player next to
  a MultiView tile), then consider a manual "Re-sync" row on the slot popup wired to hardReset().
- MultiView stays gated off on LOW/ULTRA_LOW tier. A MultiView pass here is NOT clearance to lift it.
- A marginal provider will now show MORE hard resets, not fewer (soft rungs no longer absorb them).
  Do not read that as a regression.


---

# ACTIVE — Live TV "Recently Watched" category (2026-09-17)

Add a Recently Watched pseudo-category to the Live TV left rail, second under Favorites, so a
user can jump straight back to channels they have been watching.

Decisions taken (user, 2026-09-17):
- Semantics = the existing 30s floor. The rail means "channels you watched for 30+ consecutive
  seconds". The channel currently on screen appears once you back out of it. NOT adding a
  start-of-tune tracker for v1. Lowering MIN_SESSION_SECONDS was refused outright:
  ChannelRecommendationEngine scores frequency as sessionLogs.size, so it would inflate Home's
  "For You - Live Now" - a cross-feature change, not a local one.
- Label = "Recently Watched" (the app's existing string, used by MultiView's picker and
  Favorites). Distinct from the "Recently Added" rows on Movies/Series, which mean new-to-the-
  catalog, not new-to-you.
- NOT the default landing category; Favorites stays the init default. Recent is opt-in.
- Rendered unconditionally (even with an empty log), exactly like Favorites: savedCategoryPosition
  is a raw adapter index, so a row that appears after the first watch would shift every index
  below it on precisely this feature's happy path.

NO DB MIGRATION. Verified: OoustreamDatabase is version = 12 and DatabaseModule ends with
.fallbackToDestructiveMigration(). Adding a @Query to an existing DAO changes no schema and no
identity hash. Bumping to 13 without a real 12->13 Migration would destructively wipe favorites,
watch progress, series tracking and blocked categories on every existing install.

## Plan
- [x] `data/local/dao/ChannelWatchLogDao.kt` - add `RecentChannelRow` projection +
      `observeRecentChannels(cutoff, limit): Flow`. ONE aggregate (SQLite bare-column rule gives
      the MAX row's metadata), `MAX(timestamp)` not session-end, NO window function
      (ROW_NUMBER needs SQLite 3.25+/~API 30; minSdk is 23 and the fleet has API 25/28 sticks).
- [x] `parental/ContentFilterManager.kt` - expose `isFilteringActive` (shouldFilter() was private).
- [x] NEW `data/repository/RecentChannelsRepository.kt` - the single source of recent channels.
      30-day window, over-fetch 60 -> parental filter -> take(25). Fails CLOSED on a null
      categoryId while filtering is active.
- [x] `livetv/LiveTvViewModel.kt` - `RECENT_ID`, `emptyState` StateFlow, RECENT branch in
      `selectCategory` collecting the Flow (not a one-shot), + fix the pre-existing raw-vs-filtered
      `categories.firstOrNull()` fallback bug in loadCategories.
- [x] `livetv/LiveTvFragment.kt` - virtualCats shape in updateCategoryList (+ reversed-containment
      search guard), `lastRenderedCategoryId` guard on the reset-to-top branch, emptyState
      collector that also clears the skeleton, Guide icon maps RECENT_ID -> null.
- [x] `res/layout/fragment_live_tv.xml` + `res/layout-television/fragment_live_tv.xml` -
      `channels_empty_text` TextView in channels_panel. BOTH files: Ooustick inflates layout/,
      Fire TV inflates layout-television/.
- [x] `settings/SettingsViewModel.kt` - Clear Watch History now also clears channel_watch_log +
      channel_scores (it cleared neither; the omission becomes a privacy complaint the moment the
      rail is on screen).
- [x] `backup/BackupService.kt` - clearAllData() clears channel_watch_log too.
- [x] `multiview/ChannelPickerDialogFragment.kt` - repoint loadRecentChannels() at the shared
      repository (deletes a 90-day full-table read, kills the duplicate, inherits the parental
      filter), and point its CATEGORY_* ids at the LiveTvViewModel constants.
- [ ] Build: `:app:compileDebugKotlin` + `assembleDebug`. Confirm the generated
      OoustreamDatabase_Impl still reads Delegate(12).
- [ ] DEVICE WALK on an AFTKRT **and** an Ooustick (a Fire TV pass is not sign-off - v4.2.0
      shipped Fire-TV-verified and bricked every Ooustick):
      1. Empty Recent -> row present, message shown, NO permanent shimmer
      2. Populated -> newest first, names/logos correct
      3. Watch >30s -> Back -> present at row 0 with no manual refresh, and the gold cursor does
         NOT jump to row 0 while scrolled down
      4. Header search survives "rec" / "recent" / "watched"
      5. Guide while Recent selected -> favourites/first-category guide, not a blank one
      6. CH+/- zapping from a Recent channel walks the Recent list
      7. Settings -> Clear Watch History -> Recent empties
      8. Parental ON + blocked category -> a previously-watched channel from it does not appear
      9. Label fits the rail (fall back to "Recent" if it clips)

## Device findings (.82 AFTKRT, 2026-09-17, debug 4.2.16)
VERIFIED on device:
- Installed debug over release 4.2.15 with `install -r`; release signingConfig uses the DEBUG
  keystore, so no uninstall and no data loss (favorites=18, channel_watch_log=234 rows survived).
- `PRAGMA user_version` = 12 AFTER the install. No migration ran. Generated impl = Delegate(12).
- The new GROUP BY query, run against the device's real 234-row table: 22 distinct channels in the
  30-day window, correctly ordered newest-first with correct name/category/icon per channel.
- Rail renders correctly: "Recently Watched" second under Favorites with the clock glyph, and the
  label does NOT clip (several real categories DO clip at that width, e.g. "4K / UHD Channels ...").
- Default selection is Favorites on a cold start AND on Home->LiveTV. Recent is opt-in as designed.

NEW FINDINGS worth acting on:
- The `emojiColors` accent map is COSMETICALLY INERT for color emoji. setTextColor has no effect on
  a color-font glyph, so the light-blue 0xFF90CAF9 on the clock does nothing visible - and neither
  does Favorites' red 0xFFEF4444 (the heart is red because the glyph is red). Differentiation comes
  from the glyph alone. Not worth code, but do not believe the accent is doing anything.
- Parental fail-closed cost, measured on real data: 7 of the 22 in-window channels have a NULL
  categoryId (149 of 234 raw rows), so with parental controls ON the rail loses ~32% of its
  entries. Cause: Home's "For You - Live Now" fabricates LiveStreams with categoryId = null and
  WatchSessionLogger copies that in. Follow-up option: have the logger resolve a real categoryId at
  session start, which fixes FUTURE rows only.

UNVERIFIED (needs a human with a remote):
- Populated rail rendering in a clean run; cursor-does-not-jump after watch->Back; search filter
  survives typing; Guide while Recent selected; empty-state + no-shimmer (needs the fresh .235
  stick, which has an empty log); Ooustick pass.
- Testing note: driving this screen over `adb input keyevent` proved unreliable (an OK press landed
  in fullscreen playback instead of the rail). Prefer a human walk, or uiautomator-verified focus
  before each synthetic keypress. Also: the FIRST Back press in the player only dismisses controls,
  so a session is not logged until the SECOND Back - do not read that as a logging bug.
- Reading the DB over adb MUST copy ooustream_db-wal and -shm too; the main file alone gave stale
  row counts and a misleading "newest row".

## Known-unfixed, deliberately out of scope
- Dead stream ids: Recent shows a channel the provider has since removed. Structurally immune to
  the frozen-URL half of the problem (the table stores channelId, never a URL; the URL is rebuilt
  from current credentials at click time) so only the dead-id half applies. Attach catalog
  validation to the URL-upgrade ticket. Worth one curl against a known-dead live id first: a clean
  404 fails fast with honest copy, a 200-with-no-body burns the full v4.2.16 stall ladder (~45s).
- `FavoritesViewModel` applies no ContentFilterManager at all, and Home's "For You - Live Now" rail
  is unfiltered. Both are real pre-existing parental holes in the same neighbourhood. Flagged, not
  widened into this change.
- The Favorites branch in LiveTvViewModel.selectCategory still applies no parental filter (the
  else-branch does). Pre-existing; not silently changed here.
- EPG guide has no Recent scope. Deliberate: EpgGridViewModel.loadChannels ends with an
  unconditional favourites-first re-sort that would destroy recency ordering.


---

# ACTIVE — Series/Movie title UI-UX cleanup (2026-08-08, from IMG_9314.JPG)

Player title showed "The Closer (2005) - The Closer (2005) - S01E03 - The Big Picture - The Closer (2005) - S01E02 - About Face".

Root causes:
- `PlayerViewModel.buildNextResult` builds `"$seriesName - $epTitle"` with no dedupe — provider episode
  titles already embed "Series (Year) - SxxEyy - Title", so every binge/next-episode advance stacks the
  series name again.
- Fallback `info.info?.name ?: streamName` uses the CURRENT full display title as the series name →
  titles compound across episode transitions AND get persisted into `watch_progress` (Continue Watching
  re-serves the garbage forever).
- Season/episode badges are already rendered separately everywhere (controls bar "S1 E2", CW cards
  "S1 E2 · Resume") so the title text never needs embedded SxxEyy tokens.

## Plan
- [x] New `common/MediaTitleFormatter.kt` — shared formatter:
  - `episodeTitle(seriesName, rawEpisodeTitle, episodeNum)` → "Series – Episode Name" (strips series
    name occurrences, SxxEyy tokens, trailing (year) on the series segment; falls back "Episode N")
  - `cleanDisplayTitle(raw, isSeries)` → retro display cleanup for legacy stored names (segment dedupe,
    SxxEyy strip for series, first+last heuristic for compounded legacy strings)
- [x] `PlayerViewModel`: add `seriesName` field; `buildNextResult` uses formatter; fallback chain never
  touches full `streamName` (uses `seriesName`, else first segment of streamName)
- [x] `OoustreamPlaybackFragment.newInstance`: add `seriesName` arg; onCreate sanitizes incoming
  streamName for VOD/SERIES (fixes legacy stored names at display time)
- [x] `SeriesDetailFragment.playEpisode`: replace ad-hoc dedupe with formatter, pass seriesName
- [x] `ContinueWatchingPresenter` + `WatchItAgainPresenter`: clean stored names at bind time
- [x] Compile check (`:app:compileDebugKotlin`) — clean

## Review (2026-08-08)
- Formatter logic unit-verified via standalone JVM test (9 cases): the exact screenshot string
  collapses to "The Closer – About Face"; legit hyphenated movie titles are untouched
  ("Mission: Impossible – Dead Reckoning Part One" keeps both segments); duplicate-segment junk
  deduped; missing episode titles fall back to "Episode N".
- New saves write CLEAN names into watch_progress; legacy compounded rows are cleaned at display
  time (player entry + CW/Watch-It-Again cards) and self-heal in the DB on the next progress save.
- NOT device-verified on a stick yet.

---

# ACTIVE — v4.2.9: buffer starvation after decoder rebuild (AFTKRT)

**Reported 2026-07-26**, live-captured on AFTKRT (mt8696, 192.168.1.82) running shipped 4.2.8.
User: "the movie I'm watching keeps buffering and I see no buffer or bitrate".

**Measured on-device — network/CPU/memory all RULED OUT:**
- Refill bursts hit **163 Mbps** (20,346 KB/s); ISP 330-370 Mbps; WiFi -47 dBm @ 1201 Mbps 5 GHz.
- CPU 310%/400% idle. GC pauses 100-220 **us**. MemFree 52-70 MB and SwapFree **flat** across
  every stall/recover transition (memory hypothesis explicitly refuted by measurement).
- Buffer sawtooth refills at **~12s**, ceilings at **~28s** = the exact signature of
  `BufferConfigs.forLowMemory(VOD)` (10s/30s). AFTKRT should be on 15s/45s (25s/60s at HIGH).
- MediaSession id `ooustream_playback_ffmpeg_*` proves `rebuildPlayerWithFfmpegPreferred()` fired.
- Hard freeze captured: socket ESTABLISHED to 74.119.149.88:80, `rx_queue` pegged at 1,232,736 B
  byte-identical for 42 consecutive samples, buffer 0.13s, state=BUFFERING, **no recovery for 45s+**.

**Root causes (all verified in source):**
1. **Load-control drift.** `:310` (main) has the v4.2.4 gate `memoryClass <= 192 && totalMemGb < 1.4f`.
   All three rebuild paths (`:2404`, `:2553`, `:2657`) still use `memoryClass <= 192` alone, so on
   AFTKRT (memoryClass 192 / 1.63 GB) any decoder rebuild silently halves the buffer. Rebuilds also
   downgrade `forContentTypeAndQuality(type, tier)` -> `forContentType(type)`.
   Third occurrence of the rebuild-clone drift class (cf. v3.6.3 cueListener, v4.2.5 DV wrap).
2. **Watchdog inert while buffering.** `:1994` early-`continue`s unless `STATE_READY` and resets its
   own frozen timer — nothing watches a `STATE_BUFFERING` stall, so an empty buffer never recovers.
3. **Stats overlay bound to a dead player.** `attachPlayer()` called once at `:652`; rebuilds
   re-attach listener + cue listener but never the overlay.
4. **Bitrate never displays.** `StreamStatsOverlay.kt:123` reads `videoFormat.bitrate`, which is
   `Format.NO_VALUE` for progressive IPTV containers.

**Plan**
- [x] Confirm device/version, catch the freeze live
- [x] Rule out ISP / WiFi / CPU / GC / memory by measurement
- [x] Match on-device sawtooth to a specific BufferConfigs profile
- [x] Verify all 4 defects in source
- [ ] Fix 1: one `buildLoadControl()` used by all 4 player-build sites
- [ ] Fix 2: `rebindPlayerDependents()` (listener + cue + stats overlay) at every rebuild
- [ ] Fix 3: watchdog starvation recovery during STATE_BUFFERING. Recover ONLY when
      `bufferedPosition` is **static** for the whole window — a thin-but-live stream still advances
      it, so this cannot reintroduce the v4.2.4 hard-reset loop the starvation guard was added to stop
- [ ] Fix 4: stats overlay shows measured throughput from the bandwidth meter + stall count
- [ ] `assembleRelease` clean
- [ ] Device-verify on AFTKRT: `BUFFER_CONFIG lowMem=false` after a rebuild, stats overlay live
- [ ] 4.2.9 / versionCode 97, update.json, commit, push, gh release with BOTH APKs

**Review — shipped as v4.2.9 (versionCode 97)**

Implemented (all four), `assembleRelease` clean, installed on AFTKRT 192.168.1.82:
1. `buildLoadControl()` — one source of truth, used by the initial build + all three rebuild paths.
   Rebuilds now also get `forContentTypeAndQuality(type, tier)` instead of the tier-blind
   `forContentType(type)`.
2. `startStallDetector()` progress-gated. **The first draft of this was WRONG and got caught by the
   adversarial verify pass**: I added a second starvation watchdog inside `startFrameWatchdog`, on the
   belief that the existing detector recovered with a bare `prepare()`. It does not — it already calls
   `p.stop()` first (confused with the `onPlayerError` retry path). That draft was discarded because it
   duplicated an existing recovery and raced it. Shipped instead: the existing detector's real flaw —
   a blind `delay(timeout)` that escalated on time-in-BUFFERING even while a refill was progressing —
   is fixed by polling and firing only when `bufferedPosition` is completely static.
3. `rebindPlayerDependents()` — listener + cues + stats overlay + sleep timer, at every rebuild.
   (`SleepTimerManager` had the identical stale-player defect; found by the audit.)
4. `StreamThroughputMeter` — a `TransferListener` counting real arriving bytes.
   **`DefaultBandwidthMeter` was the wrong source** (audit finding): it only recomputes in
   `onTransferEnd`, and progressive VOD holds one transfer open for the entire title, so its estimate
   never moves once playback starts. The overlay now shows measured arrival rate + a rebuffer counter.

**DEVICE-VERIFIED on AFTKRT (v4.2.9 installed):**
- `ooustream_playback_ffmpeg_*` session ids in logcat → the FFmpeg rebuild DID fire this session.
- Stats overlay AFTER that rebuild rendered live data: `Buffer: 20.3s`, `net 6.4 Mbps`,
  `1080p (1920x1080)`, `avc1.64002A | ac3 5.1`. Pre-fix it would have shown a released player.
- **20.3s is the proof for fix 1**: on the rebuilt player, pre-fix `forLowMemory(LIVE)` caps
  maxBuffer at **8s**. Reaching 20.3s means the rebuild took the tier config, not the low-memory one.

**NOT device-verified:** the progress-gated stall recovery (fix 2) — it fires only on a genuinely dead
source, which can't be triggered on demand. Logic reviewed + compiles; treat any stall-recovery
regression report against this version with that in mind.

**Not done / deferred:** `TrackPickerOverlay.activePlayer` also goes stale across a rebuild (audit
confirmed, but impact downgraded — all players share one `DefaultTrackSelector`, so selections still
apply). Stale comment at the main media-source factory claiming rebuilds are left unwrapped by
`DolbyVisionBaseLayer` — false since v4.2.5, worth correcting on the next pass.

---

# DONE (shipped v4.2.4/v4.2.5) — 4K movies slideshow on Fire TV Stick 4K Max (mt8696)

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

## Session 2026-08-11 — EAC3 passthrough fix + inline S/E titles (pre-release v4.2.13 work)

Triggered by report `2026-08-10_19-26-Oouchie-DL-23049E80.txt.pdf` ("closer keeps stopping") +
user request "series must show season and episode number on all displays".

- [x] Triage report: EAC3 passthrough stall recurred TWICE on a third AFTKRT (3a6fa453c50e472e,
      4.2.12) — 16:33 mid-episode 15s AUDIO_STALL (eac3 2ch); 19:24 resume-seek frozen clock →
      watchdog burned decoders → FALSE "video format not supported" dialog.
- [x] Implement PCM-only audio sink: `AudioPipelineFactory.buildAudioSinkSafely` → no-context
      `DefaultAudioSink.Builder()` (Media3 1.10 verified: context Builder IGNORES
      setAudioCapabilities; null context pins DEFAULT_AUDIO_CAPABILITIES = PCM-only). One shared
      function covers all player build sites. Tradeoff accepted: AVR users get stereo PCM, no
      bitstream Dolby (Settings toggle deferred).
- [x] Inline S/E in every series title: `MediaTitleFormatter.episodeTitle` + `cleanDisplayTitle`
      now render "The Closer – S1 E3 – About Face"; caller S/E wins > raw token w/ season >
      partial caller. 5 call sites updated (SeriesDetailFragment, PlayerViewModel,
      ContinueWatching/WatchItAgain presenters, playback-fragment entry sanitizer).
- [x] JVM-verified formatter: 13/13 cases (incl. legacy compound collapse, idempotency,
      v4.2.12-era stored strings + caller S/E, VOD untouched).
- [x] compileDebugKotlin + assembleDebug clean.
- [x] DEVICE-VERIFIED on .82 (AFTKRT, Dolby-advertising sink), RELEASE/R8 build: every AudioTrack is
      `format: 2` (PCM 16-bit) 2ch — previously `format: 6` (E_AC3) + `audio_output: hdmi, eac3, 6ch`.
      3 seek bursts each advanced the clock at speed=1.0; zero AUDIO_STALL / WATCHDOG / crash.
- [x] FOUND DURING VERIFICATION: a 2nd passthrough source the sink fix did NOT cover —
      `HomeFragment.startHeroPreview` built a bare DefaultRenderersFactory, and the 4K hero titles
      carry EAC3 5.1, so idling on Home bitstreamed Dolby. Now uses the shared factory. Splash
      (`IntroSplashFragment`) audited + left alone: res/raw/intro.mp4 is AAC 2ch (ffprobe).
- [x] Device-walked the new titles + logo: CW cards show "Power – S1 E4 – …", "The Closer – S1 E8 –
      …", "Snowfall – S6 E8 – …"; header + center logos render the new lockup correctly.
- [x] Released v4.2.13 (vc101).

### Follow-ups from this session (NOT done)
- [ ] Optional Settings toggle "Dolby passthrough: Off (default) / Auto" for AVR/soundbar owners who
      want bitstream Dolby back. Only if someone asks — correctness beat format here.
- [ ] Consider making the brand lockup a real view (mark ImageView + TextViews) instead of a raster,
      matching the portal's live-text rationale (sharp at any DPR, can never ship a typo). Would
      replace the 11 `android:src="@drawable/logo_full"` slots with an `<include>`.
- [ ] The other two sticks were unreachable this session (.84 powered off, .214 off-network) — the
      EAC3 fix is verified on ONE device/sink combination only.

## Session 2026-08-14 — Four customer reports + crash-log persistence (v4.2.14)

### Report triage (all four read)
- **oneal738** (AFTDCT31 m7632, tier=LOW, v4.2.12) — "buffering". Playing ONLY "4K:" catalog
  titles: 3840x2160, 18/20 tracks Dolby Vision `dvhe.08.06`, DTS-HD 6ch decoded in SOFTWARE by
  FFmpeg. 52 rebuffers, refills 8–28s. Bandwidth peaked **97Mbps** and healthy stretches held a
  25s buffer at 24fps — so not a thin pipe. Hard failures were 5× `ECONNREFUSED` to the provider
  CDN (74.119.149.61:80). **App-side gap:** `RESOLUTION_CAP maxRes=1920x1080` was logged and then
  2160p played anyway — the known `setMaxVideoSize`-is-only-a-preference limitation
  ([[project_maxvideosize_doesnt_block_4k]]). `VideoDecoderCapability` won't refuse because the
  device CAN decode it. Gap = "can decode but shouldn't" on a 1080p-class stick. Advise customer
  to avoid the 4K rows.
- **stefanig** (mt8695, 900MB, ULTRA_LOW, v4.2.13) — genuine starvation: 4 `SOURCE_STALL`,
  refills to 30s, bandwidth ceiling ~11.8Mbps. Customer-side network. Also confirms the v4.2.13
  title format working in the field ("Theoretical Herpes – S1 E4 – Literal Dragons").
- **td2733** (mt8696, v4.2.13) + **gamalieland** (AFTKA, v4.2.12) — both "crashing", both API 28,
  and **neither export contained a crash** → drove the fix below.

### Done
- [x] Root-caused the empty crash reports: `SessionIntegrityTracker` wrote death records only to
      the rotating diagnostic log, and emits them at the start of the session AFTER the death.
- [x] `CrashLogger.recordEvent()` + mirroring, `@Synchronized` append, MAX_CRASHES 5→12,
      filtered to real defects (CRASH_JAVA excluded — already has a stack trace).
- [x] Device-verified on .82 via `run-as` (am crash → trace entry, relaunch → UNCLEAN_SHUTDOWN
      with screen/foreground/duration/version/freeRam; zero PROCESS_EXIT leakage).
- [x] Released v4.2.14 (vc102).

### Investigated, NOT reproduced
- [ ] User report: "channel scrolling — the channel number changes but the picture doesn't"
      (fullscreen CH+/CH−). **Could not reproduce on AFTKRT/4.2.13**: single zap tuned + rendered
      in ~800ms, and a before/after screenshot diff measured mean pixel delta 100.9 (whole frame
      changed). `buildLiveUrl(channel)` correctly uses `channel.streamId`; `channelSwitchJob` is
      only cancelled by re-arm and onDestroyView. **Leading explanation: the deliberate 300ms
      debounce in `debouncedTune`** — the number/overlay update per keypress but the tune only
      commits 300ms after the LAST press, so while scrolling the picture intentionally stays put.
      Keep the debounce (it exists so scrolling past N channels doesn't open N streams — the
      v4.2.1 551 connection-limit fix), but: **`onZapConfirm` currently only dismisses the overlay
      — pressing OK should commit the pending tune immediately.** Awaiting the user's device +
      version and whether the picture catches up after they stop pressing.

### Follow-ups (carried forward)
- [ ] 4K on non-4K devices: consider gating the "4K:" catalog by device tier / display, since the
      resolution cap provably does not hold.
- [ ] `StreamDiagnosticLogger.rotateIfNeeded` uses `lastModified`, which every write refreshes —
      an active log never ages out (10.7h single file observed) and an idle app makes stub files.
      Behaviour does not match the documented 30-min/90-min window; decide intended semantics.
- [ ] Still outstanding from v4.2.13: optional "Dolby passthrough: Off/Auto" setting; brand
      lockup as a live-text view; EAC3 fix verified on only one device/sink.
