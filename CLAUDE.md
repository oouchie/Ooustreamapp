# Ooustream IPTV Android TV

## Project Overview
Native Kotlin/Leanback IPTV app for Android TV (Fire TV Stick primary target).

- **Package**: `com.ooustream.iptv`
- **Server**: `https://flarecoral.com` (Xtream Codes API)
- **Tech**: Kotlin 1.9, Leanback, Media3 ExoPlayer, Hilt, Room, Retrofit, Coil
- **Min SDK**: 21 | **Target SDK**: 34
- **Theme**: Dark TV (#0A0A0A bg), gold focus (#FFC107), corner brackets

## Build
```bash
./gradlew.bat assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture
- **DI**: Hilt (`@AndroidEntryPoint`, `@HiltViewModel`, `@Singleton`)
- **Database**: Room v8 with FTS4 (not FTS5 — minSdk 21 compat)
- **Background**: WorkManager (periodic score refresh for recommendations)
- **Navigation**: Manual FragmentManager (no NavGraph)
- **State**: StateFlow / MutableStateFlow
- **Images**: Coil with progressive loading + dominant color placeholders
- **Player**: Media3 ExoPlayer with Leanback PlaybackTransportControlGlue

## Completed Features

### Phase 1 — Scaffold
DI, Room DB, Retrofit, Auth flow, themes

### Phase 2 — UI & Playback
All UI fragments (Home, LiveTV, VOD, Series, Search, Favorites, Settings), presenters, ExoPlayer playback, channel zapping

### Phase 3 — Tier 3+4 Enhancements (15 features)
- #13 Subscription Status Dashboard
- #14 Quick-Access Sidebar
- #15 Progressive Image Loading
- #16 FTS4 Full-Text Search
- #17 Adaptive Fragment Transitions
- #18 Audio Feedback for D-pad
- #19 Smart Onboarding Tutorial
- #20 Predictive Channel Pre-Fetching
- #21 Network-Aware Quality Adjustments
- #26 Deep Link Engine (ooustream:// scheme)
- #27 Live Channel Preview on Focus
- #28 Smart Category Ordering
- #29 Audio-Only Mode
- #31 AI-Powered "For You" Recommendations
- #35 Predictive Screen Pre-Warming

### Phase 4 — Premium Playback UX
- **Watch Next Suggestions** — End-of-movie overlay with AI-recommended movies (RecommendationEngine), HorizontalGridView poster cards, no auto-play (`player/WatchNextOverlay.kt`)
- **Channel Banner** — Pre-roll top banner on live TV showing channel name/number/logo, current + next EPG program with progress bar, auto-hides after 5s (`player/ChannelBannerOverlay.kt`)
- **Series Complete** — End-of-series overlay with Replay/Exit options when no more episodes (`player/SeriesCompleteOverlay.kt`)
- **Seek Feedback** — On-screen "▶▶ +10s" / "◀◀ -10s" text on DPAD seek, auto-dismiss after 800ms (`player/SeekFeedbackOverlay.kt`)
- **Content Info Overlay** — Long-press info sheet on VOD/Series poster cards showing metadata, plot, Play/Favorite buttons (`common/ContentInfoOverlay.kt`, `common/ContentInfoHelper.kt`)
- **Continue Watching fix** — Time-based filter (last 60s excluded) instead of percentage; 100% progress saved on STATE_ENDED
- **Gold progress bars** — Continue Watching card progress bars changed from cyan to gold (#FFC107), Netflix-style overlay on poster bottom edge
- **Live TV scroll performance** — Debounced EPG loading (400ms), debounced focus effects (60ms), cached overlay drawables, sound throttling, disabled child layout animations
- **Player controls visual polish** — Gold progress bar/icons via Leanback theme attrs, cinematic gradient scrim (`bg_playback_scrim.xml`), ColorStateList icon tints (gold focused, white unfocused)
- **Key handling refactor** — Moved ALL key handling from `setOnKeyInterceptListener` into `OoustreamPlaybackGlue.onKey()` (interceptor was unreliable — GlueHost overwrites it)
- **saveProgress race condition fix** — `withContext(NonCancellable)` prevents viewModelScope cancellation from dropping Room inserts on Back press
- **Home focus restoration** — Focus returns to last-focused row/item after returning from playback
- **Series binge from Continue Watching** — Selecting a series episode from Continue Watching properly loads series context for next-episode flow
- **Search reset via Back** — Back key in Search clears results and returns to empty state before exiting
- **Continue Watching completion fix** — `completed` column in watch_progress, save at all percentages (removed 0.95 guard), auto-mark completed at >95% or STATE_ENDED, series "Up Next" insertion on episode completion
- **Movie Trailer Button** — "Trailer" button on ContentInfoOverlay (long-press info sheet), launches YouTube app via ACTION_VIEW intent using `youtube_trailer` field from Xtream Codes API. Only shown when trailer data available.
- **Back button controls dismiss fix** — Back key now dismisses player controls overlay without exiting content. Fixed ACTION_UP leak via `backConsumedOnDown` flag in `OoustreamPlaybackGlue.onKey()`.
- **Audio & Subtitle Track Picker** — Right-side slide-in panel for switching audio/subtitle tracks during playback. D-pad navigable, radio-button selection, always shows Default audio + Off subtitles as fallbacks. Triggered from "Tracks" button on controls bar. (`player/TrackPickerOverlay.kt`, `res/layout/overlay_track_picker.xml`)
- **Trending Series row** — Home screen row showing recently updated series sorted by `lastModified`, using PosterPresenter. Click navigates to SeriesDetailFragment.
- **D-pad seek fix** — Left/Right only seeks when progress bar/play button focused; navigates between action buttons when action row focused. `!actionButtonsRow.hasFocus()` guard in `PlayerControlsBar.dispatchKeyEvent()`.
- **OTA Update system fix** — UpdateFragment rewritten with fixed 2-slot action layout (info + button). GuidedStepSupportFragment doesn't support dynamic action count changes — `notifyActionChanged()` only works for in-place content updates, not structural changes.
- **Speed Test accuracy fix** — Download test uses `get_live_streams` (large payload) instead of `get_live_categories` (tiny ~1KB) for accurate throughput measurement.
- **Update Playlist** — Settings option to refresh all channels/content from server on demand.

### Phase 4b — Audio System Hardening (v2.2.0)
- **DefaultTrackSelector** — Proper ExoPlayer track selector with English audio preference, English subtitle preference, subtitles disabled by default. Stored as field for runtime access. (`player/OoustreamPlaybackFragment.kt`)
- **Label-based English fallback** — `isEnglishTrack()` helper matches by language code (`en`, `eng`) AND label (`English`, `eng`). `onTracksChanged` switches to English even if wrong language was auto-selected.
- **Audio status indicator** — Top-right overlay shown when stream has no audio tracks or unsupported codec. Persistent for no-audio, auto-dismiss for transient issues. (`player/AudioStatusOverlay.kt`)
- **Audio-specific error handling** — `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` and `ERROR_CODE_AUDIO_TRACK_WRITE_FAILED` show codec unsupported indicator (video keeps playing).
- **Preview player audio hardening** — `LivePreviewManager` now sets AudioAttributes with `handleAudioFocus=true` and `setPreferredAudioLanguage("en")`.
- **Audio diagnostic logging** — `AudioLogger` with `OOUSTREAM_AUDIO` tag, `BuildConfig.DEBUG`-guarded. Logs track selection, language decisions, volume changes, errors. (`common/AudioLogger.kt`)
- **Crash logger** — Global uncaught exception handler saves crash traces to file. Settings > Crash Logs shows traces for customer troubleshooting. (`common/CrashLogger.kt`)

### Phase 5 — AI Features
- **"For You — Live Now"** — Personalized Home screen row surfacing live channels the user watches, ranked by time-of-day + day-of-week patterns. On-device only, no cloud. Data: `channel_watch_log` (raw sessions) → `ChannelRecommendationEngine` (frequency × recency × duration scoring) → `channel_scores` (precomputed). WatchSessionLogger logs LIVE sessions >30s. WorkManager refreshes scores every 6h. Row hidden until 3+ unique channels watched. (`recommendation/WatchSessionLogger.kt`, `recommendation/ChannelRecommendationEngine.kt`, `recommendation/ScoreRefreshWorker.kt`, `home/ForYouLivePresenter.kt`)
- **Smart EPG Gap Filler** — 3-tier EPG resolution when data is missing/garbage: (1) Real EPG → use as-is, learn pattern; (2) Pattern cache → historical match from `epg_pattern_cache` table; (3) Rule-based → infer from channel name (60+ networks mapped) + time of day. Styling: real=normal white, pattern=italic light blue #90CAF9, rule=italic dim white 47%. Integrated into ChannelBannerOverlay, PlayerControlsBar, LiveTvFragment channel cards, and Home "For You — Live Now" cards. (`epg/ChannelNameParser.kt`, `epg/SmartEpgFiller.kt`)

### Hotfixes (v2.3.x)
- **Playback stall detector** (v2.3.0) — Content-aware retries (LIVE=3, SERIES=5, VOD=6) with escalating delays (1s→15s). Watchdog detects silent buffering hangs (15s LIVE, 30s VOD/SERIES) and forces recovery.
- **AppCompat AlertDialog crash fix** (v2.3.1) — ALL AlertDialogs use `android.app.AlertDialog` (not `androidx.appcompat.app.AlertDialog`) because Leanback theme is not AppCompat-compatible. Fixed in: SettingsFragment, OoustreamPlaybackFragment, MainActivity, FavoritesFragment, TrackSelectionHelper.
- **Crash log scroll fix** (v2.3.2) — ScrollView needs `isFocusable = true` + `isFocusableInTouchMode = true` for D-pad scrolling on TV. `setTextIsSelectable(true)` steals focus from ScrollView — don't use it.
- **Low-memory buffer sizing** (v2.3.3) — `ActivityManager.memoryClass <= 128` triggers `BufferConfigs.forLowMemory()` with capped buffers (30s max VOD instead of 90-120s) to prevent OOM on 1GB Fire Sticks.
- **Smart audio fallback** (v2.3.4) — Two-stage recovery when audio decoder fails: (1) `findSupportedAudioTrack()` searches for non-AC3/EAC3 tracks preferring English via `TrackSelectionOverride`, (2) disables all audio as last resort. `audioFallbackAttempted` flag prevents infinite loop.
- **AC3 audio root fix** (v2.3.5) — `setExceedRendererCapabilitiesIfNecessary(false)` on DefaultTrackSelector prevents ExoPlayer from selecting codecs the device can't decode. `setPreferredAudioMimeTypes(AAC, E-AC3, AC3)` makes AAC preferred over AC3. User-friendly error messages via `friendlyErrorMessage()` replace raw ExoPlayer dumps. Retry button resets audio state (re-enables audio, clears overrides).

## Deferred Features (Future Updates)

These features were scoped but deferred for a future release:

### #22 Graceful Offline Degradation
- Cache last-loaded category/channel lists for offline browsing
- Show cached EPG data when network unavailable
- Queue favorite toggles for sync when reconnected
- Estimated effort: Medium

### #23 Android TV Home Screen Integration (Watch Next + Channels API)
- Publish "Continue Watching" items to Android TV home screen Watch Next row
- Create branded Ooustream channel with recommended content
- Use TvProvider + PreviewChannelHelper APIs
- Estimated effort: Large (5+ days)

### #25 Multi-Profile Family Management
- Up to 5 user profiles per account
- Per-profile watch history, favorites, recommendations
- Profile switcher on home screen
- Estimated effort: Large (5+ days)

### #30 Multi-Server Quick Switch
- Save multiple Xtream Codes server credentials
- Quick-switch between servers without re-login
- Per-server favorites and watch history
- Estimated effort: Medium

### #32 Firebase Cloud Sync
- Sync favorites, watch progress, and preferences across devices
- Firebase Realtime Database or Firestore backend
- Conflict resolution for multi-device usage
- Estimated effort: Large (5+ days)

### #33 Smart Parental Controls
- PIN-protected content filtering by category/rating
- Per-profile parental settings
- Activity logging for parents
- Estimated effort: Large (5+ days)

### #34 Secure Guest Mode
- Limited-access guest profile (no favorites, no history persistence)
- Auto-expire after configurable timeout
- Restricted category access
- Estimated effort: Medium

## Key Files (Most Modified)
1. `MainActivity.kt` — sidebar, transitions, deep links
2. `HomeFragment.kt` — onboarding, sidebar, For You row, For You Live Now row, pre-warming
3. `OoustreamPlaybackFragment.kt` — audio-only, quality policy, analytics, Watch Next, channel banner, series complete, seek feedback overlays, cinematic scrim, WatchSessionLogger, SmartEpgFiller, stall detector, AC3 audio fallback, low-memory buffers, friendly error messages
4. `OoustreamPlaybackGlue.kt` — ALL key handling (DPAD, media buttons, channel zap, seek, back), gold-tinted action icons, Back-dismisses-controls fix
5. `OoustreamDatabase.kt` — v8, WatchAnalytics + SearchIndex + ChannelWatchLog + ChannelScore + EpgPattern entities
6. `PlayerViewModel.kt` — analytics recording, stream URL building, Watch Next suggestions (RecommendationEngine), NonCancellable saveProgress
7. `ChannelPresenter.kt` — channel list items with debounced focus effects for scroll perf
8. `LiveTvFragment.kt` — category/channel lists, debounced EPG loading, preview player, SmartEpgFiller for channel EPG text
9. `VodFragment.kt` / `SeriesFragment.kt` — ContentInfoHelper for long-press info overlay
10. `common/ContentInfoHelper.kt` — reusable long-press info overlay wiring for any fragment
11. `player/TrackPickerOverlay.kt` — audio/subtitle track picker slide-in panel
12. `player/PlayerControlsBar.kt` — custom controls bar with action buttons (Tracks, Aspect, External Player, etc.)
13. `update/UpdateFragment.kt` — OTA update screen (fixed 2-slot GuidedStep layout)
14. `speedtest/SpeedTestService.kt` — ping + download speed test against IPTV server
15. `epg/SmartEpgFiller.kt` — 3-tier EPG resolution (real → pattern → rule), pattern learning
16. `epg/ChannelNameParser.kt` — 60+ network recognition, content type inference from channel names
17. `recommendation/ChannelRecommendationEngine.kt` — on-device channel scoring (frequency × recency × duration)
18. `recommendation/WatchSessionLogger.kt` — silent Live TV session logging (>30s threshold)
19. `common/AudioLogger.kt` — debug-only audio diagnostic logging (OOUSTREAM_AUDIO tag)
20. `player/AudioStatusOverlay.kt` — top-right audio status indicator (no audio, unsupported codec)
21. `common/CrashLogger.kt` — global crash logger, saves traces to file for customer troubleshooting

## Deployment
- **ADB**: `adb connect <firestick-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **GitHub Auto-Update**: App fetches `update.json` from repo root (`raw.githubusercontent.com`), compares `versionCode`, downloads APK via `UpdateService`. OTA UI fixed in v2.1.1.
- **Primary test devices**: Fire TV Stick at 192.168.1.82, 192.168.1.84

## Memory Constraints
Fire TV Stick has 1GB RAM. Total feature overhead: ~3-6MB. Audio-only mode saves memory by disabling video decoder.

## Performance Patterns
- **Debounced focus effects**: ChannelPresenter defers expensive visuals (overlay drawables, scale animation, background resource inflation) behind 60ms delay — only lightweight background tint applied immediately during rapid D-pad scrolling
- **Debounced EPG loading**: LiveTvFragment delays EPG API calls by 400ms so fast-scrolling through channels skips intermediate loads
- **Cached overlay drawables**: GoldGlowFocusDrawable and FocusBracketDrawable created once per view in onCreateViewHolder, reused via view tags
- **Sound throttling**: DpadSoundManager.playMove() throttled to max once per 80ms during rapid focus changes
- **VerticalGridView tuning**: `setAnimateChildLayout(false)` + `itemAnimator = null` for channel lists

## DB Version History
- v1-v4: Phase 1+2 (favorites, watch progress, EPG cache, search history, crash recovery, content cache)
- v5: Phase 3 (WatchAnalyticsEntity, SearchIndexEntity, SearchIndexFts)
- v6: Phase 4 (destructive migration — added columns; resets user data)
- v7: Continue Watching fix (completed/dismissed columns on watch_progress)
- v8: Phase 5 AI features (ChannelWatchLogEntity, ChannelScoreEntity, EpgPatternEntity — destructive, no users yet)
