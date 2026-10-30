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
