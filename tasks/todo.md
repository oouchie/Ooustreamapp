# 2026-11-07 — v5.2.6 (versionCode 119): byte-capped VOD buffer, honest slow-tip, quieter crash log

Source: kiarawil's export `2026-11-07_21-36-kiarawil-DL-99A47A99` (AFTKRT mt8696, 1.6GB, 5.2.4), reported as
"buffering". Verified from the log: Live TV + Reasonable Doubt S1E2 (49 min) were perfect; S1E3 stopped being
served by the provider 3.5 min in (6 attempts, 184MB then 0KB/30s windows, live channels fine in between);
Inconceivable (2017) (1080p AVC + DTS-HD 6ch) took the Java heap 44MB → 219MB (15s) → 383/384MB (5 min),
121 GC cycles avg 1s / max 4.1s, dropped frames up to 27/s, audio underruns, seven 7–104ms BUFFERING flips,
the slow-connection tip fired ("rebuffers=3"), then ANR at 21:29:38 (pss 598MB). Ten LOW_MEMORY PROCESS_EXIT
rows in the crash log were all importance=400 (cached) = invisible background reclaim.

Mechanism (Media3 1.10.0 `DefaultLoadControl`, verified from the tagged source): with
`prioritizeTimeOverSizeThresholds(true)` loading ignores the byte target until `minBufferMs` (25s on HIGH),
then stops at `maxBufferMs` (60s) OR the DEFAULT target (~141MB = 2000+200 × 64KB segments). 141MB of
buffer + ~200MB baseline = the observed 315–336MB live set on a 384MB heap.

## Plan
- [x] `BufferConfigs`: VOD/SERIES on every tier get `setTargetBufferBytes(heapTargetBufferBytes())` =
      20% of `Runtime.maxMemory()` (floor 20MB, ceiling 128MB; 384MB heap → 76MB, 256MB → 51MB) and
      `setPrioritizeTimeOverSizeThresholds(false)` — the v5.2.2 low-memory pattern, device-verified then.
      LIVE unchanged. Hero trailer inherits (it uses `forContentType(VOD)`). JUnit for the cap maths.
- [x] `buildLoadControl()` logs `maxHeap=` and `vodTargetBytes=` on `BUFFER_CONFIG`.
- [x] Slow-connection tip: a rebuffer counts only after it has lasted 500ms (job cancelled by READY/ENDED);
      the tip is suppressed (`SLOW_CONNECTION_TIP_SUPPRESSED reason=heap`) when the heap is ≥90% used.
- [x] `SessionIntegrityTracker`: LOW_MEMORY exits persist to the crash log only at importance ≤ VISIBLE (200).
- [x] Bump 5.2.6 / 119, `update.json` (customer changelog), `assembleRelease`, device check on .84 (AFTKRT,
      same model as kiarawil) + .129 (Ooustick), commit, push, GitHub release with both APKs, CLAUDE.md.

## Review (2026-11-08)
- JVM: `BufferConfigsTest` 5/5, `SearchRankerTest` 7/7. `assembleRelease` clean (the `lintVitalAnalyzeRelease`
  ASM `NegativeArraySizeException` is the known non-fatal Compose-lint noise; BUILD SUCCESSFUL).
- .84 AFTKRT, Inconceivable (2017) = 1080p AVC + DTS-HD, 21.5 Mbps (Fire OS MediaCodecLogger):
  5.2.5 Dalvik HeapAlloc 153 → 230MB over 160s, still rising. 5.2.6: 101 → 127MB by 20s then flat to 160s
  (fresh process); 2nd run 128 → 130MB flat; 3rd run 146 → 174MB by 20s then flat to 180s, one fragment,
  PLAYING the whole time. One run switched to a 4K HEVC title at 119s with five non-adb remote presses in the
  input queue soon after — not reproduced, not attributed to code.
- .129 Ooustick (API 29, low-memory 20MB cap unchanged): same title, HeapAlloc 37 → 65MB over 90s, one
  rebuffer at 45–60s, same pid, no crash.
- Not device-verified: 500ms rebuffer gate, heap suppression of the tip, LOW_MEMORY importance filter.


# 2026-11-04 triage — 4 customer exports (Oouchie247, stefanig, qwen2384, ckp912) — built + device-checked, NOT released

## What the logs show (verified from the exports)
- **Oouchie247** (AFTKRT .742f, 5.2.4): two "crashes" = ANRs, both with the Java heap FULL
  (`Heap: 0% free, 384MB/384MB` at 19:48 on MoviesSpotlight, `328MB/328MB; 8,047,557 objects` at 23:28 on
  VodFragment). GC marking up to 17.7s. Also 6 LOW_MEMORY kills since 10-30 (pss up to 524MB).
  `ADULT_GUARD items vod=` 69,280 (19:45) → **125,768** (23:28), 2 adult VOD categories.
  The 19:48 ANR came ~25s after leaving "18+: 4K CFNM" (AVC 3840x2160 on HW, played fine).
- **stefanig** (AFTSS mt8695, 900MB, 5.2.4) "buffering": `ADULT_GUARD vod=` 66,754 → 85,221 → 87,274 → 89,234
  in one hour. Heap `used=208MB total=208MB free=0MB` 4s after the 89k resolve, as Ted Lasso S2E4 started.
  Ted Lasso (mkv, AVC 1080p + DTS-HD 6ch, 43 subtitle tracks, lowMem=true) stalls every attempt ~2s in with
  `buffered≈200ms` STATIC for 30s, then the socket idles (`loaded=0KB` ×3) and dies `SocketTimeoutException`.
  Her live channels the same evening refilled in ~1s at 10–21 Mbps.
- **qwen2384** (AFTDCT31, **still on 5.1.1**): adult only 694 titles (different bouquet). "Badland Rising (2026)"
  (168423.mkv) fails `No valid varint length mask found` at the same spot (~1h37m in) on two separate days →
  damaged file at that point; retries re-hit it. 12 UNCLEAN_SHUTDOWNs on Search/Settings/Home (API 28 = no
  exit reason available). Long binges otherwise play fine.
- **ckp912** (AFTKRT, 5.2.4): from 21:21 every live channel gets `state=ENDED` (server closed the stream
  cleanly, no error) every ~15–35s, app reconnects in ~1–2s. 1 Gbps wifi. App holds ONE stream (full-bleed
  preview is released before fullscreen — checked `goFullscreen()`). Earlier that night CNN ran 18 min clean.
  The slow-connection tip fired and told them to restart their router — wrong advice for this pattern.

## Root cause (main one) — the adult catalogue exploded tonight and the app holds all of it
NOT verified against the panel (portal DB access was denied) — inferred from the app's own counts.
1. `AdultContentGuard.resolve()` downloads every adult category's full `VodStream` list (125k objects,
   transient), keeps a boxed `Set<Int>`, and **persists 125k ids as a SharedPreferences StringSet** — held
   in memory forever by SharedPreferencesImpl, re-written as XML every 15 min, `apply()` is waited on by
   the main thread at onPause/onStop (QueuedWork).
2. `CatalogCache.vod()` (Home, Spotlight, Movies, Search, recommendations) is the bulk `get_vod_streams` —
   which (inferred) now also carries the adult titles: ~30k normal → up to ~155k objects.
3. Opening the adult category: 60k+ `VodStream` → 60k+ `PosterItem` built ON THE MAIN THREAD (twice:
   movies + watchProgressMap emissions), plus a Room `IN (...)` with 60k params (fails silently).

## Fix plan
- [x] 1. AdultContentGuard: derive adult ids from the bulk list already in memory (no second download);
      hold them as a sorted `IntArray`; persist to a small binary file in filesDir, not SharedPreferences;
      one-time delete of the old huge prefs keys.
- [x] 2. CatalogCache: drop adult-category items while parsing (streaming Gson read of `get_vod_streams`,
      skipping rows whose category is adult) so they are never materialized. Adult titles stay reachable
      only through their own category (same rule as today).
- [x] 3. Movies/Series category grid: build PosterItems off the main thread; chunk the watch-progress
      query (≤900 ids) and only for what's visible; cap a giant category (e.g. first 3,000 + "use Search")
      — owner decision.
- [x] 4. stefanig: confirm whether the 20MB low-memory byte cap deadlocks on this file (cap full while
      buffered stays 0.2s → loading stops, `prioritizeTimeOverSizeThresholds=false`). Repro on a 1GB box
      (Ooustick) with Ted Lasso S2E4 (id 1224083). If confirmed: let loading continue below minBuffer
      even when the byte cap is hit.
- [x] 5. Slow-connection tip: don't count `LIVE_STREAM_ENDED` reconnects as rebuffers (ckp912).
- [ ] 6. (optional) MKV varint error mid-file: skip ~10s past the bad spot instead of retrying the same byte.
- [x] Verify: install on .82/.84 (release build), open the adult 4K category, Home, Movies; `dumpsys meminfo`
      before/after; no ANR in `dumpsys dropbox`. Ooustick walk for #3/#4.

## Review (2026-11-05, release builds on .82 AFTKRT + .129 Ooustick)
**What changed**
- `data/remote/VodListReader.kt` (new): streams `get_vod_streams` item by item (Gson JsonReader, Retrofit
  `@Streaming` `getVodStreamsRaw`); `Slimmer` shares one copy of repeated values (type, category, ext,
  rating, added) and drops num/custom_sid/direct_source (read nowhere for movies).
- `CatalogCache`: the bulk movie list is streamed + slimmed and adult-category rows are never kept;
  their ids go to a sorted IntArray (`vodAdult()`). Behaviour change: app-wide Search, Home, Spotlight and
  the Movies "By decade"/"Recently Added" no longer contain adult titles; adult categories now sit in
  "More services" without a count (their titles aren't in the index). Still browsable in their category.
- `AdultContentGuard`: sorted IntArrays + binary file `filesDir/adult_content_guard.bin` (temp+rename);
  movie ids come from `CatalogCache.vodAdult()` (no second download); old prefs XML deleted unopened.
- `FavoriteRemapper` / `WatchHistoryPruner` take the adult ids as `alsoLive`, so adult favorites/history
  are not treated as deleted now that the catalogue leaves them out.
- Movies grid (`VodFragment`): shows the first 3,000; note "Showing the first 3,000 of N titles. Select
  the search icon…" in the hint line (also on phone layouts when capped); the header search filters the
  whole category. Watch progress only for the shown titles; `getProgressForIds` chunked at 900.
  Per-category list in `VodViewModel` streamed + slimmed.
- Series grid (`SeriesFragment`): same 3,000 cap + note ("Recently Added" = 34,533 series). Verified on .82:
  note shown, grid scrolls, Dalvik alloc ~86 MB.
- `VodDetailViewModel` "More like this": streamed, keeps the best 20 (was: whole category in memory).
- `VodCastBackfillWorker`: uses the shared catalogue (was a full 165k download every 15 min).
- `RecommendationEngine`: never fetches an adult category.
- Slow-connection tip ignores the reconnect after a live `STATE_ENDED` (5s grace).

**Measured**
- .82, adult "18+ | 4K Nutflix" (65,218 titles): opens, note shown, grid scrolls, detail page + More like
  this (20) fill. Dalvik alloc 127–135 MB before slimming → 90–100 MB after. Header search "Angels"
  filters all 65k, note gives way to the normal hint. Home: 0 adult titles on screen.
- Home cold start alloc: .82 153 → ~99–109 MB; .129 115 → ~80 MB (single samples, no forced GC).
- `dumpsys dropbox`: no ooustream ANR/crash on either stick during the session.
- stefanig repro on .129 (same low-memory 20 MB byte cap): Ted Lasso S2E4 "SD" copy is really 1080p AVC +
  DTS-HD 6ch at ~28 Mbps (position vs bytes). Ooustick pulled ~25 Mbps and played with short rebuffers —
  NO deadlock. Her log: ~2 Mbps delivered → the file can't play on her connection. Cause #4 rejected,
  no BufferConfigs change.
- The provider re-imported everything: Movies "Recently Added" = 135,578 non-adult titles (was ~29k in
  October); Series "Recently Added" 34,533; categories are now genres + 4K + language + 2 services.

**NOT verified**
- The guard's binary file and the old prefs delete (release build: no `run-as`).
- An AFTSS (stefanig's model) and .84; phone.
- The slow-tip change on a real server-closed live stream (needs ckp912's pattern).
- Item 6 (MKV varint skip) not done.
- Finding, not changed: the "SD" quality badge is shown for a 1080p remux.

## Customer replies (no app change needed)
- qwen2384: update to 5.2.4 (Settings → Check for updates). Badland Rising is damaged ~1h37m in — report
  to the provider.
- ckp912: ask whether another device uses the same login; check active connections for the line on the panel.

# v5.2.4 (versionCode 117) — favorites after the id change, 4K trial out, remote-friendly Favorites, Live TV categories — 2026-10-29

Why: the 2026-10-29 reports (cysnell, cameron20, keithahg, forevery, allinone) and the provider's
series-id re-key that day. Plan: `~/.claude/plans/snug-tickling-star.md`. Released 2026-11-01 on the owner's go-ahead ("ship 5.2.4").

- [x] 1. `data/repository/FavoriteRemapper.kt` — re-match dead vod/series favorites by unique exact normalized
      name; called from the two HomeViewModel prune launches; `FAVORITES_REMAP`; pure `matchByName` JVM-tested
- [x] 2. `WatchHistoryPruner` — skip the delete when >=3 AND >=50% of history ids vanish (`WATCH_HISTORY_PRUNE_SKIPPED`)
- [x] 3. Remove the v5.2.3 4K hardware-first trial (2 files deleted, APF restored to f0a9ba4, ~60 refs out of the
      playback fragment); keep the v5.2.2 refusal + the "title Mbps" readout
- [x] 4. Favorites with a remote: edit-mode OK removes; info-card Favorites button removes on the Favorites screen;
      ContentInfoHelper dismisses the card after a favorite toggle; Series/Movies Favorites list refreshes on toggle
- [x] 5. SessionIntegrityTracker: version change → `APP_UPDATED` (diagnostic only, not the crash log);
      PACKAGE_STATE_CHANGE / PACKAGE_UPDATED reason names
- [x] 6. `common/DisplayHdr.kt` — TV HDR/DV types in the export header + DEVICE_TIER; DolbyVisionBaseLayer delegates
- [x] 7. Fresh player screen after a failed same-player retry on a video decoder error (VOD/SERIES only, max 2)
- [x] 8. Live TV (TV, full-bleed): LEFT/RIGHT on a channel steps category; skips empty ones; chip row follows;
      menu only from Favorites
- [x] 9. Settings → Clear Cache clears only caches (content, catalog, EPG, both Coil loaders); keeps favorites,
      history, parental settings
- [x] 10. Slow-connection tip in the player (`SlowConnectionTipOverlay`, owner's three support steps)
- [x] 11. SessionIntegrityTracker consumes the open-session marker in `install()` (one death = one entry)
- [x] 12. Favorites Undo bar keeps focus while UNDO is focused; Live TV category-step focus fixes
- [x] 13. Radio channels (2026-10-30, "Radio: 70s on 7" on .84): frame watchdog stands down when there's no
      picture (no video track, or Audio only mode); audio-only screen shown for audio-only channels, placed
      directly above the video; the 5 player overlays cancel only their own fade timer (they wiped the whole
      window's queue, which left the slow-connection card stuck on screen)
      Verified on .84 (release build, 10:30-10:33): 70s on 7 played 67s with no flush, buffering or rebuild
      (old build: flush at 9s, give-up at ~35s); radio screen shows the channel name and follows zaps (90s on 9);
      `dumpsys activity top` shows it directly above the video, under the backdrop, zap list, banner and controls;
      a video channel zapped through still drew its first frame in 1.5s. NOT verified: the stuck-card fix
      (no repro attempted), the watchdog's normal ladder on a frozen video channel, Ooustick.
      MultiView (owner OK 2026-10-30): `MultiViewStallDetector` had the same null-as-0 frame rule, so a radio
      slot read FROZEN and got reset (8s on the audio slot, 4s elsewhere). Now: no video track = frame check
      stands down; `onNoVideoTrack` (from onTracksChanged, or at attach when tracks are already known) clears
      the slot's spinner/mask and keeps the channel logo up (`MultiViewSlotView.showAudioOnly`). Before this a
      radio tile's mask was only ever cleared by the bogus reset. A swap now re-points the detector to the new
      slot numbers (it judged health and cleared masks on the wrong tile); `clearChannel()` drops the mask/logo.
      Built 10:50. NOT device-tested yet: .84 was in use. Still open (pre-existing): after a swap the manager's
      own onPlayerError listener still reports the OLD slot number, so an error can reset the other tile.
- [x] Release bookkeeping: versionCode 117 / 5.2.4, update.json, CLAUDE.md
- [x] Verify: assembleRelease, static greps, JVM matchByName, device walk if a stick answers

## Review (2026-11-01, released as v5.2.4 / 117)
All 13 items are built and shipped; "[x]" means built, not device-verified. What was actually checked:
- **Build:** assembleRelease clean for both ABIs (versionCode 117 / 5.2.4 read back from each APK).
- **JVM:** the real `FavoriteNameMatch` matcher, 17/17 (exact, case/punctuation, 4K vs HD, duplicates, dedupe).
- **Static:** no `HardwareFirst4k|HardwareTrialJudge|Hardware4kTrial|HW_4K` left; `AudioPipelineFactory`
  identical to `f0a9ba4`.
- **.84 (AFTKRT, release builds, 2026-10-30):** install over 5.2.3 while on screen → signed in, About
  "v5.2.4", no crash-log entry for the install; Favorites edit mode → OK removed the focused card, focus
  stayed in the grid; Live TV RIGHT ×7 with the chip row following (before the park change); radio
  (70s on 7) 67s with no flush/rebuild, audio-only screen followed zaps, a video channel drew its first
  frame in 1.5s.
- **NOT verified:** LEFT / LEFT-LEFT menu gate on the final build; Undo with the remote; Clear Cache keeping
  data; the slow tip on screen; the fresh restart (needs the Realtek decoder failure); the trial removal on
  an mt8695 stick; remapper + pruner guard against real dead ids; "Display HDR" line; MultiView with a
  radio slot; a VOD/episode on the final build; Ooustick (.222/.129 not answering); phone.
- **Owner to-do on .84:** the walk removed two of the stick's live-channel favorites, "GA | Atlanta | NBC
  WXIA" and "US: ESPN FHD" (screenshots before/after); re-add them.
- **Still open (pre-existing):** after a MultiView swap the manager's onPlayerError reports the OLD slot
  number.


# Hardware-first 4K on mt8695 + title bitrate readout — 2026-10-29

Why: shanewi (AFTSS, mt8695, 5.2.2) gets the 4K refusal on Marshals S2E2 (`hvc1.1.6.L150.90`,
3840x1920). The stick's HEVC decoder only DECLARES 1080p, so Media3 marks the track EXCEEDS and the
FFmpeg software renderer wins (~2fps → LMK). IPTV Smarters ignores declared limits and configures
the hardware decoder. Whether this chip can really do 4K is NOT verified — so the app tries and watches.

- [x] `common/HardwareFirst4k.kt` — one predicate shared by the renderer and the fragment
      (mt8695 only, video/hevc only, above 1080p, no MediaCodec decoder declares the size, a hardware
      HEVC decoder exists, < 2 failed trials on this app version; a process death mid-trial = 1 failure)
      + `HardwareFirst4kVideoRenderer` (reports HANDLED + offers hardware decoders only for that case)
- [x] `AudioPipelineFactory` — opt-in `hardwareFirst4k` on createRenderersFactory /
      createMtkAwareRenderersFactory / createFfmpegPreferredRenderersFactory; swaps the stock MediaCodec
      video renderer only on mt8695. Software-video, FFmpeg-video, preview, MultiView, hero: unchanged.
- [x] `player/HardwareTrialJudge.kt` — pure verdict logic (JVM-testable): no first frame after 5s of
      data-ready time → fail; 3 windows of 1.5s below max(12, 0.75×fps) → fail; 6 good windows → pass.
      Starvation, pauses, seeks and a frozen clock are not held against the decoder.
- [x] Fragment: trial instead of refusal when the decision is TRIAL; frame watchdog stands down while
      it runs; a video-renderer error during the trial = fail; fail → the same v5.2.2 refusal message.
      Both player builds that can run 4K on mt8695 opt in (initial + FFmpeg-preferred audio rebuild).
- [x] Stats overlay: "title X Mbps" = bytes per second of media loaded, next to "net".
- [x] JVM-run the judge; compileReleaseKotlin; assembleRelease; install on .84 (no 4K regression,
      readout works). Do NOT release without asking.

## Review (2026-10-29, .84 AFTKRT mt8696, release build installed over 5.2.2 — NOT committed)
- Build: `assembleRelease` clean, both ABIs. `HardwareTrialJudge` JVM run: 19/19 scenarios as expected.
- .84 regression (the trial can't fire on mt8696): "4K: Five Nights at Freddy's" (`dvhe.08.06`
  3840x2160, TrueHD 7.1) on `OMX.MTK.VIDEO.DECODER.HEVC`; "Stomp the Yard" (`avc1.640029`, DTS-HD 5.1)
  on `OMX.MTK.VIDEO.DECODER.AVC`; audio PCM 2ch both (`AUDIOINFO format: 2`). Dropbox: no new
  ANR/crash (only ooustream entry is a 5.2.0 ANR from 10-27; retention back to 10-26).
- Title readout, checked against Fire OS's own `MediaCodecLogger ... bitrateInKbps` (video only):
  FNAF title 92.6 / 95.6 Mbps vs decoder 90.8-95.3 Mbps; Stomp the Yard title 32.9 / 33.9 Mbps vs
  decoder 28.5-31.2 Mbps (+ DTS-HD audio). Reading is there on the first open (window starts at READY).
- Finding: FNAF 4K is a ~95 Mbps remux. Net read 58-110 Mbps, buffer stayed under 7s, and the decoder
  logged two starvation gaps (6.5s, 8.0s) in the first ~50s. That is the .84 "buffer keeps dropping":
  the title needs more than the connection sustains. Stomp the Yard (33 Mbps) built a 30s buffer.
- Added after the first device pass: during a gapless binge, once the episode is fully loaded the next
  one's bytes no longer count against it (`hasNextMediaItem` + buffered >= duration).
- NOT verified: the trial on a 1080p mt8695 stick (none here); whether that hardware decodes
  3840x1920; IPTV Smarters on AFTSS; DV P7 4K stays refused; the judge counts frames, not picture
  correctness. Side effect: FNAF and Stomp the Yard resume points on .84 moved ~1 min.
- Provider ticket **#1626** opened 2026-10-29 (panel API, read back byte-identical): Marshals S2E2
  (episode 390204) / S1E1 / S2E3 are 4K; asks them to move 4K copies into their existing 4K section,
  put 1080p (H.264 or 8-bit HEVC) in the regular series listings, and send a list of 4K-only series.
  No customer names. Not included: the ~95 Mbps 4K-movie buffering (server-side limit unverified).
  - Provider replied 2026-10-29 19:20 UTC (28 min later): "hello, 1080p versions will be added".
    No date, no list of 4K-only series, nothing on moving the 4K copies. Status `answered`, still open.
  - Follow-up sent 2026-10-29 21:20 UTC (message 4580, read back exact): "Thanks! We have customers who
    can't watch their shows right now. Roughly when will the 1080p versions be up?" Dropped the 4K-list ask
    (owner: we have the 4K category list). Gap: that list won't show 4K episodes inside regular series.
- **Released as v5.2.3 (versionCode 116), 2026-10-29**, at the owner's "lets release update": full OTA
  (update.json), both ABIs on GitHub release v5.2.3. Field check needed on an AFTSS/AFTSSS: a debug export
  should show `HW_4K_TRIAL` then `HW_4K_TRIAL_PASSED` or `HW_4K_TRIAL_FAILED reason=...`.

# Trial (branch try/kotlin-2.2): Movies direction B in Compose for TV — 2026-10-19

Design: canvas https://claude.ai/artifact/KrpBfRTj1LbBvsN3vBhGox, board "B — Spotlight + Rows".
Scope: a test screen on the trial branch only. NOT for main until reviewed on .82 + an Ooustick.

- [x] Compose + tv-material + coil-compose deps, Kotlin compose compiler plugin
- [x] `vod/spotlight/MoviesSpotlightViewModel` — rows from data the panel really has:
      Recently Added (14-day window), Top rated 2020s, top services by size, decades.
      Same parental + adult exclusion as VodViewModel.visibleCatalog().
- [x] `vod/spotlight/MoviesSpotlightFragment` + `MoviesSpotlightScreen` (ComposeView)
      hero follows the focused card (debounced get_vod_info for backdrop/plot/genre/runtime)
      Play → same resume flow as VodDetailFragment; More Info → VodDetailFragment; card OK → detail
- [x] Route sidebar "vod" + Home Movies card to it (trial branch only); keep sidebar double-LEFT
- [x] Build release, install on .82, walk: D-pad through rows, hero updates, Play, Back focus,
      sidebar, memory (dumpsys meminfo) vs old VodFragment
- [x] Review section

## Review (2026-10-19, .82 AFTKRT, release build)
- Works on device: hero follows focus (plot/runtime/backdrop from get_vod_info after a 450ms dwell),
  rows pin under the hero, OK → detail, Play → resume flow, Back → same card, "See all ›" at the end
  of every row → full Leanback grid on that category (2020s verified), "All Movies" button, left
  rail (design B) navigates (Settings verified). Old double-LEFT sidebar disabled on this screen.
- Accent is cyan #00D4FF (user choice). Detail page is still the gold one.
- PSS on this screen: ~172 MB (budget says browsing <150 MB). Not compared against VodFragment yet.
- Lint: Compose BOM 2026.06.01 pulls a lifecycle lint jar that crashes AGP 8.7.3 lint
  (`NullSafeMutableLiveData`) — disabled that one check on this branch. Needs an AGP bump before main.
- Trial-only: `ooustream://movies` deep link (adb couldn't trigger the double-LEFT sidebar).
- Not done: LEFT into the rail lands on the nearest icon, not Movies; "Top Rated" drops flat 10.0
  ratings on the ASSUMPTION they are panel defaults (unverified); no Ooustick / phone test.

# Catalog "Browse by" release — plan (2026-10-09)

Source: `tasks/catalog-layout-options-2026-10-09.md` (items 1–3 = this release; 4–5 follow-up).

## Scope
- [x] 1. `tmdb_id` alternate name on `VodStream` + `Series` (panel sends `tmdb_id`; app read `tmdb` → always null).
      Feed a known series TMDB id into `EpisodeNameResolver` (skip the name search when we have it).
- [x] 2. `CatalogCache` — ONE in-memory copy of the bulk movie / series lists (10-min TTL, single-flight,
      cleared on expiry + on "Update Playlist"). Today every full-list consumer (Home hero/trending, BYW rows,
      Movies "Recently Added", Series "Recently Added") re-downloads 10 MB / 6.5 MB.
- [x] 3. `CatalogBrowse` — index builder: series genres (≥5 titles), decades (movies from `(YYYY)` in the
      title, series from `releaseDate`; "Classics" fold before 1970 / 1990), per-category counts, recent count.
- [x] 4. Sidebar groups in `CategoryListAdapter` / `CategoryItem`: HEADER rows (non-focusable), GROUP rows
      (▸/▾ toggle), indented children. Fold services with < 50 titles under "More services". Rename
      "Unlabeled Movies/Series" → "All Other Movies/Series" and move to the end of the main list. Counts on
      every service row.
- [x] 5. Movies + Series ViewModels: browse index flow, expanded-group state, virtual-category selection
      (genre / decade / classics) filtered through parental blocks AND adult exclusion (adult titles only
      ever appear inside their own category).
- [x] 6. "Recently Added" → true 14-day window on `added` / `last_modified`; row hides when the window is
      empty (unless it is the selected row).
- [x] 7. Home + RecommendationEngine read the bulk lists from `CatalogCache` (no behavior change, fewer downloads).
- [x] 8. Build (`compileDebugKotlin` + `assembleRelease`), device-walk Movies + Series sidebars on .82:
      groups expand/collapse by D-pad, focus stays sane, genre/decade grids populate, adult titles absent from
      every virtual category, Recently Added shows the 2026-10-07 drop.
- [x] 9. CLAUDE.md + release notes; version bump 5.1.0 (111); `update.json`; GitHub release.

## Decisions
- Recently Added window = **14 days** (plan said 7; with the catalog's lumpy drops a 7-day row would blink
  in and out weekly). Label stays "Recently Added".
- Virtual categories exclude adult content by category NAME (always available from the loaded category
  list) plus `AdultContentGuard` ids when they are known — no network wait, no unprotected window.
- Groups default collapsed; a group auto-expands when its child is the selected category (back-nav restore).
- Sidebar search (header search) flattens the list: no headers/groups while a filter is typed.
- NOT in this release: movie genre rows (needs the widened `get_vod_info` cache — item C), season art.

## Review (2026-10-09)
Built + device-walked on .82 (AFTKRT, release build) via uiautomator-driven D-pad scripts: Movies and
Series sidebars render the BROWSE BY / SERVICES blocks, groups expand/collapse, decade + genre grids
populate (1990s newest-first; Crime by rating), Recently Added = the 2026-10-07 drop, no adult titles
in view, "All Other Movies 5897", "More services (8)" (8, not the 16 estimated — the <50 floor folds
fewer than the <100 count in the survey). Zero FATAL/ANR in logcat across the walks.
Not verified: name-based adult exclusion (stick has parental ON), Ooustick, phone, the TMDB-id
episode-name path (needs a series open while the bulk list is cached).
Found while verifying: the `ooustream://vod` deep link does not open the Movies screen (it needs an
id); Home's genre rows are service rows on this panel. Neither changed.

## Home Hub (design 3, cyan) — trial, 2026-10-20
- [x] `home/hub/HomeHubFragment` + `HomeHubScreen` (Compose), shares `HomeViewModel`; `navigateToHome` opens it
- [x] Header (greeting, search, settings, clock), featured hero, "Pick up where you left off" panel, 6 section tiles
- [x] All old Home rows: Continue Watching, For You — Live Now, For You / Because You Watched, Top 10,
      Pick Up & New, Trending Now, Trending Series, genre rows (For You moved under CW at user's request)
- [x] Hold-OK on Pick Up & New → "Remove from list" (NEW card: clears flag; watched card: `dismissed=1`,
      `getCompletedContent` now filters `dismissed = 0` — query only, no migration)
- [x] Double-LEFT menu only from the hero Play button (Compose is one View, focusSearch can't see edges)
- [x] Dedupe 4K/HD copies in Trending/Top 10/genre rows (`CatalogBrowse.sameTitleKey`, also used by Movies)
- [x] Rows edge-to-edge so a focused poster isn't clipped; cold start focuses Play at top
- [x] `ooustream://oldhome` trial deep link to compare with the Leanback Home
- Verified on .82 (release build): every row renders; remove flow (Turtles removed from Pick Up, focus moved
  to next card); menu gate; For You/BYW are empty on this account in the OLD Home too (checked)
- NOT verified: Back focus restore from detail/player on the Hub; Ooustick; phone; PSS
- Next: Series 1 (Spotlight + Rows) and Live TV 2 (Full-Bleed), cyan

## Live TV Full-Bleed (cyan) — trial, 2026-10-20
`livetv/bleed/LiveFullBleed{Fragment,Screen}.kt`; TV routes "live" here, phones keep `LiveTvFragment`
(`ooustream://oldlive` opens the old screen for comparison).
- [x] Focused channel plays behind the screen with sound after a 700ms rest (one player, stream swapped;
      FFmpeg-preferred audio). Verified .82: tunes in 0.4–1.2s; fast presses tune only the last channel.
- [x] Chips (Favorites / Recent / categories), list with rule-based "on now" (italic) or real EPG,
      bottom line: channel · times · title · progress · Next.
- [x] OK = full screen (background stream released first); Back lands on the channel the player ended on.
- [x] Hold OK = favourite toggle (verified add + remove on LA 1, net unchanged).
- [x] Dead stream → "This channel isn't playing right now" (INTL 4K UHD: 6 provider 404s); next channel recovers.
- [x] Double-LEFT menu only from the channel list (`SidebarGate`, also used by Home Hub).
- [ ] Not checked: Ooustick, low-memory stick (no background video by design), PSS while playing.
- Seen, not fixed: a dark strip on NFL Network's right edge (ESPN 2 fills) — looks like the stream.
- Detail-page season list overlaps "Start over" on All American (8 seasons) — check before Series.


## Search ranking — "Scream (1996)" never showed (2026-11-07)
Found via the portal's title requests: Cyrus Snell asked for "Scream 1996" (also Doctor Who 2005, Warehouse 13).
Reproduced against the live list with the owner line and the app's exact filter: "scream" → 147 non-adult
matches in `added`-ascending order, "Scream (1996)" at #122, row shows 60. "scream 1996" → 0 (parentheses).
- [x] `search/SearchRanker.kt` — normalise, parse a trailing year, tier score, tie-breaks (year, plain copy,
      release year, added); ASCII fast path + first-token pre-filter.
- [x] `ContentRepository.search` ranks live / vod / series through it; cast-only matches still last.
- [x] JUnit 4 added to the build; `SearchRankerTest` 7 cases pass (`:app:testDebugUnitTest`).
- [x] `:app:assembleDebug` clean (both ABIs). Timing at 135k synthetic titles: 17 ms vs 7 ms old.
- [ ] Device check on .82 / .129: type "scream", "scream 1996", "jay z", "doctor who 2005".
- [x] Released v5.2.5 (118) 2026-11-07 — commit bc20f68, GitHub release v5.2.5 (arm64 18.4 MB, arm32 17.0 MB), `update.json`
      live. The 2026-11-05 adult-guard / streamed-list work shipped in the same build.
