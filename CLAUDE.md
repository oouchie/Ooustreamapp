# Ooustream IPTV Android TV

## Project Overview
Native Kotlin/Leanback IPTV app for Android TV (Fire TV Stick primary target).

- **Package**: `com.ooustream.iptv`
- **Server**: `https://flarecoral.com` (Xtream Codes API)
- **Tech**: Kotlin 1.9, Leanback, Media3 ExoPlayer, FFmpeg audio decoder (Jellyfin), Hilt, Room, Retrofit, Coil
- **Min SDK**: 21 | **Target SDK**: 34
- **Theme**: Dark TV (#0A0A0A bg), gold focus (#FFC107), corner brackets
- **Current Version**: 3.5.5 (versionCode 50)

## PERFORMANCE REQUIREMENTS

Before writing any code that touches ExoPlayer, UI rendering, RecyclerView, image loading, memory, or network — read and follow `PERFORMANCE_AGENT.md` in the project root. Every ExoPlayer instance must use the standard configuration pattern. Every RecyclerView must follow the 60fps rules. Every player release must use the safe release pattern. No exceptions.

Key non-negotiables from the performance agent:
- `setEnableDecoderFallback(true)` on every ExoPlayer
- `android:largeHeap="true"` in manifest
- Device tier detection (HIGH/MID/LOW/ULTRA_LOW) applied to all configs
- Memory budget: browsing <150MB, playback <200MB, MultiView <350MB
- safeReleasePlayer() for every player.release() call
- DiffUtil for every RecyclerView adapter (never notifyDataSetChanged)
- Coil hardware bitmaps enabled, cache sizes per device tier
- MultiView: own HandlerThread per player, staggered startup, SurfaceView never GONE

## Build
```bash
./gradlew.bat assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Release Process
When building a version for update/release, follow these steps in order:
1. **Bump version** in `app/build.gradle.kts` (`versionCode` + `versionName`)
2. **Update `update.json`** with new `version_name`, `version_code`, `download_url` (tag matches version), and `changelog`
3. **Build release APK**: `./gradlew.bat assembleRelease`
4. **Commit** the changed files (`app/build.gradle.kts`, source changes, `update.json`)
5. **Push** to remote
6. **Create GitHub release** with the APK: `gh release create v<version> app/build/outputs/apk/release/app-release.apk --title "<title>" --notes "<notes>"`

## Architecture
- **DI**: Hilt (`@AndroidEntryPoint`, `@HiltViewModel`, `@Singleton`)
- **Database**: Room v11 with FTS4 (not FTS5 — minSdk 21 compat), 16 entities, 13 DAOs
- **Background**: WorkManager (periodic score refresh every 6h, new episode sync every 6h)
- **Navigation**: Manual FragmentManager (no NavGraph)
- **State**: StateFlow / MutableStateFlow
- **Images**: Coil with progressive loading + dominant color placeholders (Palette)
- **Player**: Media3 ExoPlayer with Leanback PlaybackTransportControlGlue + Jellyfin FFmpeg audio decoder extension for DTS/AC3/EAC3 software decoding
- **Audio Pipeline**: `AudioPipelineFactory` (single source of truth) → ExoPlayer → FFmpeg decode (AC3/DTS/EAC3) or hardware decode (AAC/MP3) → ChannelMixingAudioProcessor (1-8ch→stereo downmix) → DefaultAudioSink → AudioTrack. Three factory methods: `createRenderersFactory()` (hardware first, default), `createFfmpegPreferredRenderersFactory()` (FFmpeg first, for devices with broken hardware AC3/EAC3), `createSoftwareVideoRenderersFactory()` (software video decoder fallback)
- **External Players**: Intent-based launch to VLC app, MX Player, Kodi, or system default with position handoff (via `ExternalPlayerLauncher.kt`)
- **Security**: androidx.security.crypto EncryptedSharedPreferences for credentials

## Dependencies
- **Media3 ExoPlayer** 1.2.1 (core, HLS, UI, Leanback, Session, DataSource, OkHttp)
- **FFmpeg Decoder**: `org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1` (pre-built native .so files for all ABIs)
- **Core Library Desugaring**: `com.android.tools:desugar_jdk_libs:2.0.4` (required by FFmpeg decoder)
- **Hilt** 2.50 (DI framework + WorkManager integration)
- **Room** 2.6.1 (database + KSP compiler)
- **Retrofit** 2.9.0 + OkHttp 4.12.0 (networking)
- **Coil** 2.5.0 (image loading)
- **Leanback** 1.0.0 (TV UI framework)
- **ConstraintLayout** 2.1.4 (MultiView grid layouts)
- **ZXing** 3.5.2 (QR code generation for upgrade flow)
- **Shimmer** 0.5.0 (loading skeletons)
- **Palette** 1.0.0 (color extraction)
- **WorkManager** 2.9.0 (background tasks)

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
- **Seek Feedback** — On-screen "+10s" / "-10s" text on DPAD seek, auto-dismiss after 800ms (`player/SeekFeedbackOverlay.kt`)
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
- **Audio diagnostic logging** — `AudioLogger` with `OOUSTREAM_AUDIO` tag, `Log.w()` for Fire TV visibility (suppresses `Log.d()`). Logs track selection, language decisions, volume changes, errors. (`common/AudioLogger.kt`)
- **Crash logger** — Global uncaught exception handler saves crash traces to file. Settings > Crash Logs shows traces for customer troubleshooting. (`common/CrashLogger.kt`)

### Phase 5 — AI Features
- **"For You — Live Now"** — Personalized Home screen row surfacing live channels the user watches, ranked by time-of-day + day-of-week patterns. On-device only, no cloud. Data: `channel_watch_log` (raw sessions) → `ChannelRecommendationEngine` (frequency × recency × duration scoring) → `channel_scores` (precomputed). WatchSessionLogger logs LIVE sessions >30s. WorkManager refreshes scores every 6h. Row hidden until 3+ unique channels watched. (`recommendation/WatchSessionLogger.kt`, `recommendation/ChannelRecommendationEngine.kt`, `recommendation/ScoreRefreshWorker.kt`, `home/ForYouLivePresenter.kt`)
- **Smart EPG Gap Filler** — 3-tier EPG resolution when data is missing/garbage: (1) Real EPG → use as-is, learn pattern; (2) Pattern cache → historical match from `epg_pattern_cache` table; (3) Rule-based → infer from channel name (60+ networks mapped) + time of day. Styling: real=normal white, pattern=italic light blue #90CAF9, rule=italic dim white 47%. Integrated into ChannelBannerOverlay, PlayerControlsBar, LiveTvFragment channel cards, and Home "For You — Live Now" cards. (`epg/ChannelNameParser.kt`, `epg/SmartEpgFiller.kt`)

### Phase 6 — Aurora Cinema UI + FFmpeg Audio (v2.5.0)
- **Aurora Cinema Search redesign** — Complete search page rewrite with animated aurora background, frosted glass header, gold-glow animated search bar with pulse effect, filter tabs (All/Live TV/Movies/Series), Trending Now row with ranked poster cards, recent searches as styled chips, categorized results with section headers + poster art, voice search support, long-press info overlay integration, full D-pad navigation. (`search/SearchFragment.kt`, `search/SearchBarFocusAnimator.kt`, `search/SearchChipPresenter.kt`, `search/TrendingRankPresenter.kt`, `res/layout/fragment_search_aurora.xml`)
- **FFmpeg audio decoder integration** — Replaced native libVLC library (~15-20MB) with `org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1` (~5.6MB). Jellyfin fork bundles pre-built native .so files for all ABIs (arm64-v8a, armeabi-v7a, x86, x86_64). Single unified ExoPlayer engine for all audio codecs — no dual-player complexity. `EXTENSION_RENDERER_MODE_ON` = hardware first, FFmpeg fallback for AC3/DTS/EAC3.
- **Stereo downmix** — `ChannelMixingAudioProcessor` via `AudioPipelineFactory` with ITU-R BS.775 matrices for all channel counts (1→1, 2→2 passthrough, 3→2, 4→2, 5→2, 6→2 5.1, 8→2 7.1) in custom `DefaultRenderersFactory.buildAudioSink()`. Budget devices (Ooustick) can't output multi-channel PCM. Shared by main player and MultiView.
- **FFmpeg runtime verification** — `AudioLogger.isFfmpegAvailable` (lazy-cached reflection) + `logFfmpegCodecs()` checks all MIME types at startup. Audio Decoder info row in Settings shows "FFmpeg (AC3, DTS, EAC3, AAC, FLAC)" or "Hardware Only".
- **ExoPlayer track picker cleanup** — TrackPickerOverlay shows all audio tracks (none hidden), with codec+channel labels.
- **AudioLogger upgrade** — All `Log.d()` changed to `Log.w()` for Fire TV Stick visibility (Fire TV suppresses debug logs).

### Hotfixes (v2.3.x)
- **Playback stall detector** (v2.3.0) — Content-aware retries (LIVE=3, SERIES=5, VOD=6) with escalating delays (1s→15s). Watchdog detects silent buffering hangs (15s LIVE, 30s VOD/SERIES) and forces recovery.
- **AppCompat AlertDialog crash fix** (v2.3.1) — ALL AlertDialogs use `android.app.AlertDialog` (not `androidx.appcompat.app.AlertDialog`) because Leanback theme is not AppCompat-compatible. Fixed in: SettingsFragment, OoustreamPlaybackFragment, MainActivity, FavoritesFragment, TrackSelectionHelper.
- **Crash log scroll fix** (v2.3.2) — ScrollView needs `isFocusable = true` + `isFocusableInTouchMode = true` for D-pad scrolling on TV. `setTextIsSelectable(true)` steals focus from ScrollView — don't use it.
- **Low-memory buffer sizing** (v2.3.3) — `ActivityManager.memoryClass <= 128` triggers `BufferConfigs.forLowMemory()` with capped buffers (30s max VOD instead of 90-120s) to prevent OOM on 1GB Fire Sticks.
- **Smart audio fallback** (v2.3.4) — Two-stage recovery when audio decoder fails: (1) `findSupportedAudioTrack()` searches for non-AC3/EAC3 tracks preferring English via `TrackSelectionOverride`, (2) disables all audio as last resort. `audioFallbackAttempted` flag prevents infinite loop.
- **AC3 audio root fix** (v2.3.5) — `setExceedRendererCapabilitiesIfNecessary(false)` on DefaultTrackSelector prevents ExoPlayer from selecting codecs the device can't decode. `setPreferredAudioMimeTypes(AAC, E-AC3, AC3)` makes AAC preferred over AC3. User-friendly error messages via `friendlyErrorMessage()` replace raw ExoPlayer dumps. Retry button resets audio state (re-enables audio, clears overrides).

### Hotfixes (v2.4.x)
- **Sleep timer dialog crash** (v2.4.1) — `SleepTimerManager.showTimerDialog()` was the last remaining use of `androidx.appcompat.app.AlertDialog`. Changed to `android.app.AlertDialog` for Leanback theme compatibility.

### Hotfixes (v2.5.x)
- **5.1 surround audio crash fix** (v2.5.1) — FFmpeg decodes EAC3/AC3/DTS 5.1 → 6ch PCM, but Ooustick can't create 6-channel AudioTrack. Fix: `ChannelMixingAudioProcessor` with ITU-R BS.775 downmix matrices (6→2, 8→2). FFmpeg verification and diagnostic logging added. Audio Decoder status in Settings.
- **Live TV 2ch AC3 audio crash fix** (v2.5.2) — `ChannelMixingAudioProcessor` throws `UnhandledAudioFormatException` when no matrix exists for a channel count. Added identity passthrough matrices for mono (1→1) and stereo (2→2). Changed from `EXTENSION_RENDERER_MODE_PREFER` to `EXTENSION_RENDERER_MODE_ON` — hardware handles AAC/MP3 natively, FFmpeg only for AC3/DTS/EAC3.

### Phase 7 — MultiView Sports Player (v2.6.0)
- **MultiView Fragment** — Full multi-screen live TV player. 4 layout modes: Quad (2x2), Main+3 (1 large + 3 sidebar), Dual (side-by-side), Triple (3 columns). ConstraintSet-based layout switching. D-pad navigation between slots with gold focus border. Auto-hide controls overlay (8s timeout). Exit confirmation dialog. `FLAG_KEEP_SCREEN_ON` while active. (`multiview/MultiViewFragment.kt`, `res/layout/fragment_multiview.xml`)
- **Multi-player audio management** — Up to 4 simultaneous ExoPlayer instances with independent audio control. Only the active audio slot has volume; all others muted at 0f. 200ms crossfade on audio switch. `setAudioSlot()` explicitly mutes ALL non-target players (belt-and-suspenders). Short-press OK on occupied slot switches audio. (`multiview/MultiViewPlayerManager.kt`)
- **Per-slot ExoPlayer pipeline** — Each slot gets its own ExoPlayer with stereo downmix (same `ChannelMixingAudioProcessor` pipeline as main player), `EXTENSION_RENDERER_MODE_ON`, AAC-preferred track selection. Non-focused slots capped to 480p resolution. Reduced buffer sizes for non-audio slots (3-10s vs 5-15s). (`multiview/MultiViewPlayerManager.kt`)
- **MultiViewSlotView** — Fully programmatic custom view (no XML). Shows channel name badge, pulsing LIVE dot, AUDIO indicator (gold), slot number badge, empty state with "Select Channel" prompt. Gold focus border via `bg_multiview_slot_focused` drawable. Non-focusable (focus lives on parent FrameLayout). (`multiview/MultiViewSlotView.kt`)
- **Channel Picker Dialog** — Dual-pane dialog (categories 30% left, channels 70% right). Reuses existing `CategoryListAdapter`. EPG loaded per channel in background. Click assigns channel to slot. (`multiview/ChannelPickerDialogFragment.kt`, `res/layout/dialog_channel_picker.xml`)
- **Auto-fill** — When entering MultiView with a seed channel, remaining slots auto-filled from same category first, then other categories. (`multiview/MultiViewAutoFillUseCase.kt`)
- **Long-press channel swap** — Long-press OK (500ms threshold) on an occupied slot opens channel picker to replace that slot's channel. Uses `onFullKeyEvent()` for ACTION_DOWN/UP timing. (`multiview/MultiViewFragment.kt`)
- **Top bar** — Layout mode selector buttons (2x2, 1+3, Dual, Triple) with gold active state, stream count, clock. Scale+gold focus feedback on layout buttons. D-pad UP from grid reaches layout buttons; DOWN returns to slot 1. Visibility set synchronously in `showControls()` so focus system finds buttons on same frame. (`multiview/MultiViewTopBarController.kt`, `res/layout/view_multiview_top_bar.xml`)
- **Bottom bar** — Audio slot selector buttons, scrolling EPG ticker with channel names and program titles. (`multiview/MultiViewBottomBarController.kt`, `res/layout/view_multiview_bottom_bar.xml`)
- **Pro plan gating** — `UserPlanManager` checks `maxConnections >= 4` for Pro status, `totalMem >= 1.4GB` for device capability. Basic users see locked popup → QR upgrade dialog. Pro badge on Home hero card. Plan refreshed on login and auto-login. (`data/UserPlanManager.kt`)
- **QR upgrade flow** — Full-screen dialog with QR code linking to `ooustick.com/subscribe/pro`, feature list, 5-minute countdown auto-dismiss. Uses ZXing for QR generation. (`multiview/QrUpgradeDialogFragment.kt`, `multiview/QrCodeGenerator.kt`, `res/layout/dialog_qr_upgrade.xml`)
- **Home hero card** — MultiView promotional card on Home screen with pulsing live dot, gold glow + corner brackets on focus, 1.03x scale. Click navigates to MultiView. Only shown when device is capable (RAM >= 1.4GB). (`home/MultiViewHeroPresenter.kt`, `res/layout/item_hero_multiview_card.xml`)
- **Live TV integration** — MultiView icon in Live TV header bar with focus animation (1.3x scale, gold border). Click seeds MultiView with current preview channel. (`livetv/LiveTvFragment.kt`)
- **Focus architecture** — Focus lives on outer FrameLayouts (slot_1–slot_4 in XML), NOT inner MultiViewSlotView children. `FOCUS_BLOCK_DESCENDANTS` prevents PlayerView/SurfaceView from stealing focus. `isSlotFocused()` guard ensures OK/Enter only intercepted when a slot has focus (not layout buttons). (`multiview/MultiViewFragment.kt`)
- **KeyEventHandler extension** — `onFullKeyEvent(event: KeyEvent)` added to `KeyEventHandler` interface for full ACTION_DOWN+ACTION_UP access. Dispatched in `MainActivity.dispatchKeyEvent()` before the ACTION_DOWN guard. Default implementation returns false. (`MainActivity.kt`)
- **Anti-chop auto-recovery** — 3-signal chop detection (dropped frames via AnalyticsListener, rendered frame stall via DecoderCounters, buffer health via bufferedPosition−currentPosition). 3-level recovery ladder: soft reset (seekToDefaultPosition), hard reset (stop/clearMediaItems/setMediaSource/prepare/play), nuclear reset (release player+thread, rebuild). 10s cooldown between attempts, staggered multi-slot recovery. Recovery fade mask hides visual glitches during reset. Emergency quality tier drops non-focused slots to 320p when any slot chops. (`multiview/MultiViewStallDetector.kt`, `multiview/PlaybackHealth.kt`)
- **Thread-isolated ExoPlayer instances** — Each MultiView slot gets its own `HandlerThread` with `setPlaybackLooper(thread.looper)`, preventing decoder stalls on one slot from starving others. Soft/hard/nuclear reset methods on `MultiViewPlayerManager`. Stream URLs stored for recovery without Fragment involvement. (`multiview/MultiViewPlayerManager.kt`)
- **Recovery fade mask** — Dark overlay (150ms fade-in) with optional gold spinner on MultiViewSlotView, shown during hard/nuclear reset to hide decoder glitches. Dismissed on `onRenderedFirstFrame` callback or safety timeout (3s hard, 5s nuclear). (`multiview/MultiViewSlotView.kt`)
- **Single player frame watchdog** — Polls `videoDecoderCounters.renderedOutputBufferCount` every 3s during STATE_READY. Forces hard reset (stop/prepare/play) if no new frames for 5+ seconds. Catches silent freezes that existing STATE_BUFFERING watchdog misses. (`player/OoustreamPlaybackFragment.kt`)
- **SurfaceView focus glitch fix** — Removed scaleX/scaleY animation on slot FrameLayouts (SurfaceView renders on separate hardware layer that doesn't scale with parent transforms). Removed `clipToOutline` on MultiViewSlotView (SurfaceView ignores view clipping, outline recomputation on background swap caused visual artifacts in 1+3 mode). (`multiview/MultiViewFragment.kt`, `multiview/MultiViewSlotView.kt`)

### Phase 8 — Audio Pipeline Audit + Watch Progress (v2.7.1)
- **AudioPipelineFactory** — Single source of truth for `DefaultRenderersFactory` shared by main player and MultiView. All downmix matrices (1→1, 2→2 passthrough, 3→2, 4→2, 5→2, 6→2, 8→2), `setEnableDecoderFallback(true)`, `EXTENSION_RENDERER_MODE_ON`, `setEnableAudioTrackPlaybackParams(true)`. Eliminates code duplication. (`common/AudioPipelineFactory.kt`)
- **Track selector hardening** — `setExceedRendererCapabilitiesIfNecessary(false)`, AAC-first MIME priority (matches stereo-only devices), `setTunnelingEnabled(false)` prevents bypassing audio processor chain. (`player/OoustreamPlaybackFragment.kt`)
- **Stage 2 audio fallback** — When `findAlternateAudioTrack()` returns null, auto-disables audio via `setTrackTypeDisabled(AUDIO, true)` and lets video continue playing. Shows codec unsupported overlay. (`player/OoustreamPlaybackFragment.kt`)
- **User track override protection** — `userTrackOverrideActive` flag prevents `onTracksChanged` from force-switching back to English after user manually selects a non-English track. Reset on channel switch/retry. `TrackPickerOverlay.onTrackSelected` callback sets the flag. (`player/OoustreamPlaybackFragment.kt`, `player/TrackPickerOverlay.kt`)
- **findAlternateAudioTrack hardening** — Added `isTrackSupported()` check to skip unsupported tracks in fallback loop. (`player/OoustreamPlaybackFragment.kt`)
- **isEnglishTrack tightening** — Replaced overly broad `startsWith("en")` with exact matches (`"en"`, `"eng"`, `"en-us"`, `"en-gb"`). Label matching uses `== "english"` instead of `contains("eng")`. (`player/OoustreamPlaybackFragment.kt`)
- **tuneToChannel state reset** — Resets `retryCount`, `audioFallbackAttempted`, `userTrackOverrideActive`, re-enables audio. Mutes player before `setMediaItem` and restores after `play()` to prevent audio pop on channel switch. (`player/OoustreamPlaybackFragment.kt`)
- **MultiView disabled track types fix** — All 4 `setDisabledTrackTypes` locations now preserve TEXT and METADATA disabled state. Previously only disabled AUDIO, accidentally re-enabling subtitle/metadata renderers on non-audio slots. (`multiview/MultiViewPlayerManager.kt`)
- **Preview player audio isolation** — `LivePreviewManager` now disables audio entirely at track selector level (`setTrackTypeDisabled(AUDIO, true)`), sets `handleAudioFocus = false`, and `volume = 0f`. Prevents FFmpeg decoder waste and audio focus conflicts. (`player/LivePreviewManager.kt`)
- **AudioLogger production error logging** — `logAudioError()` no longer gated behind `BuildConfig.DEBUG`, allowing codec failure diagnostics in release builds. `isFfmpegAvailable` cached via `lazy` val (reflection called once). (`common/AudioLogger.kt`)
- **Track picker codec labels** — Added FLAC, TrueHD, Vorbis to `formatCodecLabel()`. (`player/TrackPickerOverlay.kt`)
- **Stream stats audio row** — New audio stats line in StreamStatsOverlay showing bitrate, sample rate, and channel layout (Mono/Stereo/5.1/7.1). (`player/StreamStatsOverlay.kt`, `res/layout/overlay_stream_stats.xml`)
- **Logarithmic sleep fade** — Changed linear volume fade to `(1f - fadeProgress).pow(2)` for perceptually smooth volume reduction. Fast initial drop, slow final drop. (`player/SleepTimerManager.kt`)
- **External player position handoff** — `ExternalPlayerLauncher.launch()` accepts `positionMs` parameter. Passes `"position"` Long extra to VLC, `"position"` Int extra to MX Player. Call site in `OoustreamPlaybackFragment` passes `player?.currentPosition`. (`player/ExternalPlayerLauncher.kt`)
- **ProGuard FFmpeg keep rules** — Added `-keep class org.jellyfin.** { *; }` and `-keep class androidx.media3.decoder.ffmpeg.** { *; }` to prevent R8 from stripping reflection-accessed FFmpeg classes. (`app/proguard-rules.pro`)
- **Watch progress indicators** — Poster cards (VOD/Series) show watched badge (checkmark + dimmed) for completed content and gold progress bar for partially watched. Episode cards show same indicators with progress percentage. `PosterItem` extended with `watchCompleted`/`watchProgress` fields. `EpisodeRecyclerAdapter` accepts `watchProgressMap`. Progress refreshed on `onResume`. (`common/PosterPresenter.kt`, `series/EpisodeRecyclerAdapter.kt`, `series/SeriesDetailFragment.kt`, `series/SeriesDetailViewModel.kt`, `vod/VodFragment.kt`, `vod/VodViewModel.kt`, `vod/VodDetailFragment.kt`, `vod/VodDetailViewModel.kt`)
- **Batch watch progress query** — `WatchProgressDao.getProgressForIds()` and `WatchProgressRepository.getProgressForIds()` for efficient bulk lookup. (`data/local/dao/WatchProgressDao.kt`, `data/repository/WatchProgressRepository.kt`)
- **MultiView unlocked** — Removed "Coming Soon" dialogs from Home hero card and Live TV header icon. Both now navigate directly to `MultiViewFragment` via `MainActivity.navigateToMultiView()`. Device capability check (RAM >= 1.4GB) still gates visibility. (`home/HomeFragment.kt`, `livetv/LiveTvFragment.kt`)

### Hotfixes (v2.8.x)
- **Hero rotation divide-by-zero fix** (v2.8.1) — `startHeroRotation()` race condition: `featuredItems` could be replaced with empty list between size check and modulo. Fix: capture `size` as local val, use `getOrNull()` for safe access. (`home/HomeFragment.kt`)
- **VOD/Series grid IndexOutOfBoundsException fix** (v2.8.1) — Leanback `VerticalGridView` crashes with position -1 when `ArrayObjectAdapter.clear()` + `.add()` loop fires multiple layout notifications. Fix: replaced with atomic `setItems()` + `DiffCallback` in both VodFragment and SeriesFragment. (`vod/VodFragment.kt`, `series/SeriesFragment.kt`)
- **SafeAdapterUtils** (v2.8.1) — `safeReplaceAll()` for atomic adapter updates and `safeSetSelectedPosition()` with bounds checking. Applied to all Home screen rows (Continue Watching, For You, Trending, etc.). (`common/SafeAdapterUtils.kt`, `home/HomeFragment.kt`)
- **Favorites empty fallback** (v2.8.1) — Live TV, Movies, Series pages default to Favorites but now auto-select the first real API category when favorites is empty, instead of showing a blank screen. (`livetv/LiveTvViewModel.kt`, `vod/VodViewModel.kt`, `series/SeriesViewModel.kt`)

### Phase 9 — Watch History + Premium Home Redesign (v2.9.0)
- **New Episodes row** — SeriesTrackingEntity tracks last watched episode per series. NewEpisodeSyncWorker (6h periodic) compares against API via NewEpisodeDetector. Home row shows series with unwatched episodes (green "NEW" badge + gold count). (`data/local/entity/SeriesTrackingEntity.kt`, `data/local/dao/SeriesTrackingDao.kt`, `recommendation/NewEpisodeDetector.kt`, `recommendation/NewEpisodeSyncWorker.kt`, `home/NewEpisodesPresenter.kt`)
- **Watch It Again row** — Home row showing completed content (>95% watched) for easy rewatching. Deduped by COALESCE(seriesId, streamId). Gold checkmark badge. (`home/WatchItAgainPresenter.kt`)
- **Continue Watching enhancements** — Episode badge ("S1 E5") for series, time remaining ("Nm left") on cards. Progress bar 3→4dp. (`home/ContinueWatchingPresenter.kt`, `res/layout/item_continue_watching.xml`)
- **Clear Watch History** — Settings option to truncate watch_progress and series_tracking tables. (`settings/SettingsFragment.kt`, `settings/SettingsViewModel.kt`)
- **Premium poster card redesign** — 120×180dp → 180×270dp, 14dp corners, steeper gradient overlay, focus-revealed metadata line + micro-CTA ("▶ Watch"/"▶ Details") with slide-up animation, shimmer loading via ShimmerFrameLayout, focus scale 1.06→1.08x. (`common/PosterPresenter.kt`, `res/layout/item_poster_card.xml`, drawables)
- **Section card redesign** — 280×140dp → 320×160dp, glass border stroke (1dp #0DFFFFFF), shimmer accent line (3dp gold), CTA turns gold on focus. (`home/SectionCardPresenter.kt`, `res/layout/item_section_card.xml`)
- **Neighbor dimming** — BrowseCardFocusHelper spotlight effect: focused card alpha 1.0, ±2 cards 0.65, distant 0.50. 250ms animated, 100ms debounced. Applied to all 8 home rows. (`common/BrowseCardFocusHelper.kt`)
- **Row header highlighting** — Labels default white, turn gold (#FFC107) when any card in that row is focused. (`home/HomeFragment.kt`, `res/layout/fragment_home.xml`)
- **Track picker fix** — TrackPickerOverlay registers Player.Listener for onTracksChanged while visible, auto-refreshes track list after channel/episode switch. (`player/TrackPickerOverlay.kt`)
- **New app icon** — Cyan aurora TV icon across all mipmap densities + adaptive icon foreground. New branded TV banner for Fire TV home screen.
- **All card layouts resized** — CW, NE, WA, FY cards all 180×270dp with 14dp corners for consistency.

### Phase 10 — Streaming Cinema Experience (v3.0.0)
- **Hero Trailer Auto-Play** — After 4s dwell on hero item, muted VOD preview fades in over backdrop. ExoPlayer with TextureView, `resize_mode="zoom"` for full-bleed, seeks past 2min of logos. 15s max preview. Cross-protocol redirects enabled for IPTV 302s. Overlays/gradients fade out during playback, restore on release. Skip on low-memory devices. (`home/HomeFragment.kt`, `res/layout/fragment_home.xml`)
- **D-pad Hero Navigation** — Left/right on hero buttons cycles through featured items, releases active preview, resets hero rotation. (`home/HomeFragment.kt`)
- **Parallax Hero Scroll** — Hero backdrop moves at 40% scroll speed (`translationY = -scrollY * 0.4f`) with `clipChildren="true"` to prevent bleed. (`home/HomeFragment.kt`, `res/layout/fragment_home.xml`)
- **Time-of-Day Aurora Theming** — Aurora orb colors shift by time bucket: warm amber (morning), cool blue/teal (afternoon), deep purple/crimson (evening), near-black (night). Animated transitions via `ValueAnimator.ofArgb()`. (`common/AuroraBackgroundView.kt`)
- **Enhanced Aurora Visibility** — Orb alpha boosted ~2x (140/110/100), larger radii (0.7/0.6/0.65), more saturated palette colors for TV-visible atmospheric lighting. (`common/AuroraBackgroundView.kt`)
- **Quick Tune Channel Strip** — Circular 56dp channel logos with name labels in HorizontalGridView. Priority: personalized watch data → favorite live channels → first 20 live streams. Gold ring on focus, 1.1x scale. `centerInside` scaleType with 6dp padding for proper logo display. (`home/ChannelStripPresenter.kt`, `res/layout/item_channel_strip.xml`, `home/HomeViewModel.kt`)
- **"Because You Watched" Rows** — Up to 3 personalized rows seeded by recently watched VOD/series. Genre/cast overlap scoring (60% genre, 40% cast) via `getVodInfo()` API. Searches all VOD (40 random candidates) for variety, not just same category. Series use existing genre/cast fields. (`recommendation/RecommendationEngine.kt`, `home/HomeFragment.kt`, `home/HomeViewModel.kt`)
- **Live Sports Banner** — Full-width rotating banner showing live sports events. EPG-driven, auto-advances every 6s. Sports channels detected via `ChannelNameParser`. Personalized sort by channel scores. (`home/LiveSportsBannerView.kt`, `res/layout/view_live_sports_banner.xml`, `home/HomeViewModel.kt`)
- **Smart Trending Algorithm** — Replaced "recently added" sorting with composite score: Rating (50%, TMDB 0-10 normalized) + Recency (30%, logarithmic decay) + User Affinity (20%, from watch history category counts). Applied to both Trending Movies and Trending Series. (`home/HomeViewModel.kt`)
- **EPG Base64 Cache Fix** — `EpgCacheRepository` now applies `decodeBase64()` on cache reads, fixing stale pre-fix entries that showed raw base64 in sports banner. (`data/repository/EpgCacheRepository.kt`)
- **EPG Freshness Overhaul** — Cache TTL reduced 30min→5min. `forceRefresh(streamId)` bypasses cache when no current program found. Playback loop refreshes EPG every 5 minutes and force-refreshes from server when all cached programs are in the past. Prevents stale EPG showing wrong program info. (`data/repository/EpgCacheRepository.kt`, `player/OoustreamPlaybackFragment.kt`)
- **Live TV Auto-Retry on STATE_ENDED** — Live streams that hit STATE_ENDED (server connection drops) now auto-retry after 1s instead of freezing. Logged as `LIVE_STREAM_ENDED` event. (`player/OoustreamPlaybackFragment.kt`)
- **Favorites Live TV Logo Fix** — List mode uses `ChannelDisplayHelper.loadLogo()` with 48dp container, `centerInside` scaleType, and initials fallback — matching Live TV screen style. (`favorites/FavoritesAdapter.kt`, `res/layout/item_favorite_list.xml`)

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

### #34 Secure Guest Mode
- Limited-access guest profile (no favorites, no history persistence)
- Auto-expire after configurable timeout
- Restricted category access
- Estimated effort: Medium

### Phase 11 — Mobile Touch Overhaul (v3.2.0)
- **Player touch gestures** — GestureDetector on OoustreamPlaybackFragment (phone only): single tap toggles controls, double tap play/pause, horizontal fling seeks ±10s (VOD/Series) or zaps channels (Live). (`player/OoustreamPlaybackFragment.kt`)
- **Responsive card sizing** — Phone poster cards reduced from 180×270dp to 110×165dp. Created `values-sw320dp/dimens.xml` for small phones (100×150dp). Section cards also dimensioned. All item layouts use `@dimen/` references instead of hardcoded dp. (`values/dimens.xml`, `values-sw320dp/dimens.xml`, 6 item layout XMLs)
- **Ripple touch feedback** — `android:foreground="?android:attr/selectableItemBackground"` on 8 FrameLayout card roots (poster, continue watching, for you, new episode, watch again, section, trending, channel). Touch-triggered so invisible on TV.
- **Stats button in player controls** — "Stats" action button added to PlayerControlsBar (phone only, TV has MENU key), wired to `statsOverlay?.toggle()`. New `ic_stream_stats.xml` icon. (`player/PlayerControlsBar.kt`)
- **Track picker scrim dismiss** — `scrim.setOnClickListener { dismiss() }` for tap-outside-to-close on phones. (`player/TrackPickerOverlay.kt`)
- **Hero swipe gesture** — Horizontal fling on hero container rotates featured items (phone only). (`home/HomeFragment.kt`)
- **Minimum text sizes** — Extracted 8-9sp hardcoded text to `@dimen/text_badge` (11sp phone, 9sp TV) and `@dimen/text_micro` (10sp phone, 8sp TV) across 8 item layouts. Readable on phone screens.
- **MultiView touch support** — Tap slot to select + switch audio, long-press to open channel picker. Phones locked to landscape via `requestedOrientation`. (`multiview/MultiViewFragment.kt`)
- **Track picker responsive width** — Panel width extracted to `@dimen/track_picker_width` (260dp phone, 320dp tablet/TV). Animation uses `resources.getDimension()`. (`overlay_track_picker.xml`, `player/TrackPickerOverlay.kt`)
- **Phone color adjustments** — `text_secondary` boosted from `#9CA3AF` to `#C0C8D3`, `text_muted` boosted for daylight readability. TV keeps dim originals via `values-television/colors.xml`.
- **VerticalGridView crash fix** — Replaced `setItems(list, DiffCallback)` with `setItems(list, null)` (atomic `notifyDataSetChanged()`) to prevent position -1 crash from granular diff notifications. Selected position saved/restored with bounds clamping. (`vod/VodFragment.kt`, `series/SeriesFragment.kt`)

### Phase 12 — Parental Controls: Category-Level Blocking (v3.3.0)
- **BlockedCategoryEntity + DAO** — Room entity with composite PK (section + categoryId), Flow-based queries, batch insert/delete. (`data/local/entity/BlockedCategoryEntity.kt`, `data/local/dao/BlockedCategoryDao.kt`)
- **DB Migration v10→v11** — Non-destructive migration adds `blocked_categories` table. (`di/DatabaseModule.kt`)
- **ContentFilterManager** — @Singleton filtering engine with in-memory `ConcurrentHashSet` kept in sync via Room Flow. O(1) per-item `isBlocked()` checks. `filterCategories()` and `filterContent()` methods used by all ViewModels. Respects `isEnabled` and `isTemporarilyUnlocked()`. (`parental/ContentFilterManager.kt`)
- **AdultCategoryDetector** — Regex-based detection of adult categories (adult/xxx/18+/late night/erotic/x-rated/nsfw/mature). Used for "Block All Adult" action and auto-block on first PIN setup. (`parental/AdultCategoryDetector.kt`)
- **ParentalControlManager upgrade** — Added 30-minute temporary unlock (in-memory, expires on app restart). Lockout upgraded from 3 attempts/30s to 5 attempts/60s. (`parental/ParentalControlManager.kt`)
- **Content filtering integration** — All ViewModels filter categories and content via ContentFilterManager: LiveTvViewModel (categories + channels), VodViewModel (categories + movies including Recently Added/New Releases), SeriesViewModel (categories + series), SearchViewModel (search results + trending), HomeViewModel (featured, trending movies/series, channel strip, live sports).
- **ParentalSettingsFragment** — Full settings UI with 3 tab buttons (Live TV/Movies/Series) showing blocked count badges, RecyclerView category list with toggle switches (green=allowed, red=blocked), blocked categories sorted to top with strikethrough + BLOCKED badge. Quick actions: Block All Adult, Allow All, Change PIN. D-pad navigable. (`parental/ParentalSettingsFragment.kt`, `parental/ParentalSettingsViewModel.kt`, `parental/CategoryToggleAdapter.kt`)
- **PIN → Settings navigation** — ParentalPinFragment now navigates to ParentalSettingsFragment on unlock (instead of popping back). First-time PIN setup auto-blocks adult categories across all sections.
- **Settings status indicator** — Parental Controls entry in SettingsFragment shows "ON" or "OFF" status, updates on resume.

### Phase 13 — Error Logging & Customer Diagnostics (v3.3.0)
- **StreamDiagnosticLogger** — @Singleton rolling file logger. Writes structured entries (`HH:mm:ss.SSS [CATEGORY/LEVEL] message`) to `filesDir/diagnostics/`. 30-min rotation, 5MB max, 3 files retained (90 min history). Device info header with Xtream username + ANDROID_ID for customer identification. URL credential masking. Export to single .txt. (`common/StreamDiagnosticLogger.kt`)
- **ExoPlayerDiagnosticListener** — Player.Listener + AnalyticsListener logging state changes, errors (5-line stack trace), first frame rendered, dropped frames (>3), decoder init (video/audio HW/SW), video size, bandwidth (30s throttle), track changes. Channel name updated on switch. (`player/ExoPlayerDiagnosticListener.kt`)
- **PlaybackHealthMonitor** — Coroutine-based periodic monitor: buffer health every 15s, memory every 60s. Black screen detection: polls renderedOutputBufferCount every 3s, logs after 3s of no new frames with decoder info, surface validity, audio state, buffer state. Auto-logs recovery when frames resume. (`player/PlaybackHealthMonitor.kt`)
- **SendDebugLogManager** — Packages diagnostic logs + crash logs into single .txt, uploads to Firebase Storage with QR code reference (TV) or email intent (phone). Log headers include Xtream username + ANDROID_ID for customer identification. Firebase metadata includes username, device_id, ref_id. Storage path: `debug-logs/{date}-{username}-{refId}.txt`. (`common/SendDebugLogManager.kt`)
- **NetworkMonitor diagnostic integration** — Network connect/disconnect/capability changes logged to StreamDiagnosticLogger. WiFi signal strength (dBm) via WifiManager. (`common/NetworkMonitor.kt`)
- **Player integration** — ExoPlayerDiagnosticListener + PlaybackHealthMonitor registered on player creation, channel name updated on tuneToChannel, health monitor stopped on onDestroyView. (`player/OoustreamPlaybackFragment.kt`)
- **Settings: Send Debug Log** — New action in SettingsFragment with confirmation dialog, optional issue description input, opens email chooser with pre-filled report. (`settings/SettingsFragment.kt`)
- **App startup logging** — StreamDiagnosticLogger wired into NetworkMonitor at app start, APP_START event logged with version. (`OoustreamApp.kt`)

### Hotfixes (v3.3.x)
- **Software video decoder fallback** (v3.3.2) — Frame watchdog auto-switches to software AVC decoder (`c2.android.*`/`OMX.google.*`) when hardware decoder fails to render frames. `rebuildPlayerWithSoftwareDecoder()` preserves position, listeners, glue. `AudioPipelineFactory.createSoftwareVideoRenderersFactory()` uses custom `MediaCodecSelector` that filters to software-only for video, keeps all decoders for audio.
- **AC3/EAC3 FFmpeg audio fallback** (v3.3.3) — Fixes infinite crash loop on mt8695-based Fire TV Sticks (AFTSSS) where hardware MediaCodec falsely claims AC3/EAC3 `format_supported=YES` but crashes at runtime with `MediaCodecAudioRenderer error` (code 5001). Root cause: `onTracksChanged` re-enabled audio immediately after Stage 2 fallback disabled it (line `setTrackTypeDisabled(AUDIO, false)` in the English auto-select block). Three-stage audio recovery: (1) alternate track (different codec, prefer English), (1.5) `rebuildPlayerWithFfmpegPreferred()` — rebuilds ExoPlayer with `EXTENSION_RENDERER_MODE_PREFER` so FFmpeg handles AC3/EAC3 instead of hardware, (2) disable audio entirely. `audioDisabledByFallback` flag prevents `onTracksChanged` from undoing Stage 2. `isAudioDecoderError()` broadened to check `error.message` for "MediaCodecAudioRenderer". Core player listener (`corePlayerListener`) extracted to field for reuse across player rebuilds via `attachPlayerListener()`. (`player/OoustreamPlaybackFragment.kt`, `common/AudioPipelineFactory.kt`)
- **Faster software video decoder fallback** (v3.3.4) — Frame watchdog tuned for faster black screen recovery on devices without HEVC hardware support (mt8695). Interval 3s→2s, frozen threshold 5s→3s, software fallback on first failure instead of waiting for 2 (`SOFTWARE_FALLBACK_THRESHOLD` 2→1). Total recovery: ~5s instead of ~18s.

### Resource Qualifier Structure
- `values/` — Phone defaults (360dp+)
- `values-sw320dp/` — Extra-small phones (320-359dp)
- `values-sw600dp/` — Tablets (600dp+)
- `values-television/` — Android TV (Fire TV Stick)
- `layout-television/` — TV-only layouts (activity_main sidebar variant)

## Key Files (Most Modified)
1. `MainActivity.kt` — sidebar, transitions, deep links, MultiView navigation, onFullKeyEvent dispatch
2. `HomeFragment.kt` — onboarding, sidebar, For You row, For You Live Now row, pre-warming, MultiView hero card, hero swipe gesture (mobile)
3. `OoustreamPlaybackFragment.kt` — ExoPlayer + AudioPipelineFactory init, audio-only, quality policy, analytics, Watch Next, channel banner, series complete, seek feedback overlays, cinematic scrim, WatchSessionLogger, SmartEpgFiller, stall detector, frame watchdog, three-stage AC3/EAC3 audio fallback (alternate track → FFmpeg rebuild → disable audio), software video decoder fallback (`rebuildPlayerWithSoftwareDecoder`), FFmpeg audio rebuild (`rebuildPlayerWithFfmpegPreferred`), `audioDisabledByFallback` flag, `corePlayerListener` field + `attachPlayerListener()`, user track override protection, low-memory buffers, friendly error messages, mobile touch gestures (GestureDetector)
4. `OoustreamPlaybackGlue.kt` — ALL key handling (DPAD, media buttons, channel zap, seek, back), gold-tinted action icons, Back-dismisses-controls fix
5. `OoustreamDatabase.kt` — v11, WatchAnalytics + SearchIndex + ChannelWatchLog + ChannelScore + EpgPattern + BlockedCategory entities
6. `PlayerViewModel.kt` — analytics recording, stream URL building, Watch Next suggestions (RecommendationEngine), NonCancellable saveProgress
7. `ChannelPresenter.kt` — channel list items with debounced focus effects for scroll perf
8. `LiveTvFragment.kt` — category/channel lists, debounced EPG loading, preview player, SmartEpgFiller for channel EPG text, MultiView icon
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
19. `common/AudioLogger.kt` — audio diagnostic logging (OOUSTREAM_AUDIO tag), FFmpeg verification (lazy-cached), release-build error logging
20. `common/AudioPipelineFactory.kt` — shared DefaultRenderersFactory with all downmix matrices (1-8ch), decoder fallback, FFmpeg extension mode. Three variants: `createRenderersFactory()` (MODE_ON, hardware first), `createFfmpegPreferredRenderersFactory()` (MODE_PREFER, FFmpeg first — for broken hardware AC3/EAC3), `createSoftwareVideoRenderersFactory()` (software-only video)
21. `player/AudioStatusOverlay.kt` — top-right audio status indicator (no audio, unsupported codec)
22. `common/CrashLogger.kt` — global crash logger, saves traces to file for customer troubleshooting
23. `search/SearchFragment.kt` — Aurora Cinema search UI with filter tabs, trending, voice search
24. `search/SearchBarFocusAnimator.kt` — gold-glow animated search bar
25. `player/ExternalPlayerLauncher.kt` — intent-based launch to VLC/MX Player/Kodi/system default with position handoff
26. `multiview/MultiViewFragment.kt` — multi-screen live TV player, layout switching, focus management, key handling
27. `multiview/MultiViewPlayerManager.kt` — up to 4 ExoPlayer instances, audio slot management, resolution caps, thread isolation, soft/hard/nuclear reset, emergency quality
28. `multiview/MultiViewSlotView.kt` — programmatic slot view with channel badge, LIVE dot, audio indicator, recovery fade mask
29. `multiview/MultiViewStallDetector.kt` — 3-signal chop detection (dropped frames, frame stall, buffer health), 3-level recovery ladder, watchdog timer, health StateFlow
30. `multiview/PlaybackHealth.kt` — PlaybackHealth enum (SMOOTH/SLIGHT_STUTTER/CHOPPING/FROZEN/DEAD), RecoveryAction enum
31. `multiview/ChannelPickerDialogFragment.kt` — dual-pane category/channel picker dialog
32. `multiview/MultiViewViewModel.kt` — layout mode, slot states, audio/focus tracking
33. `data/UserPlanManager.kt` — Pro plan detection (maxConnections >= 4), device capability check
34. `home/MultiViewHeroPresenter.kt` — Home screen MultiView promotional card
35. `common/BrowseCardFocusHelper.kt` — neighbor dimming + row header gold highlight utility
36. `home/NewEpisodesPresenter.kt` — New Episodes row presenter with green/gold badges
37. `home/WatchItAgainPresenter.kt` — Watch It Again row presenter with checkmark badge
38. `recommendation/NewEpisodeSyncWorker.kt` — periodic new episode detection worker

## Source File Inventory (204 Kotlin files, 58 XML layouts)

### By Package
| Package | Files | Key Components |
|---------|-------|----------------|
| `account/` | 3 | AccountDashboardFragment, AccountViewModel, ConnectionGaugeView |
| `auth/` | 2 | AuthViewModel, LoginFragment |
| `backup/` | 5 | BackupFragment, BackupService, BackupViewModel, BackupData, ClearConfirmFragment |
| `common/` | 38 | NetworkMonitor, QualityPolicy, DpadSoundManager, AudioLogger, AudioPipelineFactory, CrashLogger, StreamDiagnosticLogger, SendDebugLogManager, ContentInfoHelper, ContentInfoOverlay, Presenters (Channel, Poster, Category, Skeleton), FocusBracketDrawable, GoldGlowFocusDrawable, AuroraBackgroundView, ProgressiveImageLoader, QuickSidebar, RemoteHintOverlay, ScreenPreWarmer, Extensions, BrowseCardFocusHelper, ChannelDisplayHelper |
| `data/local/dao/` | 13 | FavoriteDao, WatchProgressDao, EpgCacheDao, SearchHistoryDao, CrashRecoveryDao, ContentCacheDao, WatchAnalyticsDao, SearchIndexDao, ChannelWatchLogDao, ChannelScoreDao, EpgPatternDao, SeriesTrackingDao, BlockedCategoryDao |
| `data/local/entity/` | 15 | FavoriteEntity, WatchProgressEntity, EpgCacheEntity, SearchHistoryEntity, CrashRecoveryEntity, CachedCategoryEntity, CachedStreamEntity, WatchAnalyticsEntity, SearchIndexEntity, SearchIndexFts, ChannelWatchLogEntity, ChannelScoreEntity, EpgPatternEntity, SeriesTrackingEntity, BlockedCategoryEntity |
| `data/local/` | 1 | OoustreamDatabase (Room v10) |
| `data/` | 1 | UserPlanManager |
| `data/model/` | 12 | AuthResponse, Category, ContentType, EpgProgram, LiveStream, Series, SeriesInfo, StreamUrlBuilder, VodInfo, VodStream, XtreamCredentials |
| `data/remote/` | 3 | AuthInterceptor, SafeSeriesInfoDeserializer, XtreamApiService |
| `data/repository/` | 9 | AuthRepository, ContentRepository, ContentCacheRepository, CredentialStore, EpgCacheRepository, FavoriteRepository, PredictivePreFetcher, SearchIndexRepository, WatchAnalyticsRepository |
| `di/` | 3 | AppModule, DatabaseModule, NetworkModule |
| `epg/` | 2 | ChannelNameParser, SmartEpgFiller |
| `favorites/` | 2 | FavoritesFragment, FavoritesViewModel |
| `home/` | 10 | HomeFragment, HomeViewModel, ContinueWatchingPresenter, ForYouPresenter, ForYouLivePresenter, MultiViewHeroPresenter, PaletteExtractor, SectionCardPresenter, NewEpisodesPresenter, WatchItAgainPresenter |
| `livetv/` | 2 | LiveTvFragment, LiveTvViewModel |
| `multiview/` | 14 | MultiViewFragment, MultiViewViewModel, MultiViewPlayerManager, MultiViewSlotView, MultiViewStallDetector, PlaybackHealth, SlotActionPopup, MultiViewAutoFillUseCase, MultiViewTopBarController, MultiViewBottomBarController, MultiViewLockedPopup, ChannelPickerDialogFragment, QrUpgradeDialogFragment, QrCodeGenerator |
| `onboarding/` | 1 | OnboardingOverlay |
| `parental/` | 7 | ParentalControlManager, ParentalPinFragment, ParentalViewModel, ContentFilterManager, AdultCategoryDetector, ParentalSettingsFragment, ParentalSettingsViewModel, CategoryToggleAdapter |
| `player/` | 21 | OoustreamPlaybackFragment, OoustreamPlaybackGlue, PlayerViewModel, PlayerControlsBar, PlayerControlsManager, TrackPickerOverlay, TrackSelectionHelper, ChannelBannerOverlay, ChannelZapOverlay, ChannelListHolder, WatchNextOverlay, SeriesCompleteOverlay, SeekFeedbackOverlay, AudioStatusOverlay, AudioOnlyOverlay, StreamStatsOverlay, ExternalPlayerLauncher, LivePreviewManager, BufferConfigs, SleepTimerManager, ExoPlayerDiagnosticListener, PlaybackHealthMonitor |
| `recommendation/` | 6 | ChannelRecommendationEngine, RecommendationEngine, ScoreRefreshWorker, WatchSessionLogger, NewEpisodeDetector, NewEpisodeSyncWorker |
| `search/` | 5 | SearchFragment, SearchViewModel, SearchBarFocusAnimator, SearchChipPresenter, TrendingRankPresenter |
| `series/` | 7 | SeriesFragment, SeriesViewModel, SeriesDetailFragment, SeriesDetailViewModel, EpisodeCardPresenter, EpisodeRecyclerAdapter, SeasonTabPresenter |
| `settings/` | 3 | SettingsFragment, SettingsViewModel, SettingsConfirmFragment |
| `speedtest/` | 3 | SpeedTestFragment, SpeedTestService, SpeedTestViewModel |
| `splash/` | 1 | IntroSplashFragment |
| `update/` | 4 | UpdateFragment, UpdateService, UpdateViewModel, UpdateManifest |
| `vod/` | 4 | VodFragment, VodViewModel, VodDetailFragment, VodDetailViewModel |
| Root | 2 | MainActivity, OoustreamApp |

## Layout Files (58 XML)
- **Fragments**: activity_main, fragment_account_dashboard, fragment_home, fragment_live_tv, fragment_login, fragment_multiview, fragment_search_aurora, fragment_series, fragment_series_detail, fragment_speed_test, fragment_vod, fragment_vod_detail
- **Items**: item_category, item_channel, item_channel_skeleton, item_channel_picker, item_continue_watching, item_episode_card, item_epg_program, item_for_you, item_for_you_live, item_hero_multiview_card, item_new_episode, item_poster_card, item_poster_skeleton, item_search_chip, item_search_section_header, item_section_card, item_sidebar_shortcut, item_trending_rank, item_watch_again, item_watch_next_card, item_zap_channel
- **Overlays**: overlay_audio_only, overlay_binge_countdown, overlay_channel_banner, overlay_channel_zap, overlay_content_info, overlay_onboarding_step, overlay_player_controls, overlay_quick_sidebar, overlay_remote_hints, overlay_series_complete, overlay_stream_stats, overlay_track_picker, overlay_watch_next
- **Dialogs**: dialog_channel_picker, dialog_qr_upgrade
- **MultiView**: view_multiview_top_bar, view_multiview_bottom_bar
- **Utility**: include_screen_header, layout_row_header, view_channel_info_overlay

## Deployment
- **ADB**: `adb connect <firestick-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **GitHub Auto-Update**: App fetches `update.json` from repo root (`raw.githubusercontent.com`), compares `versionCode`, downloads APK via `UpdateService`. OTA UI fixed in v2.1.1.
- **Primary test devices**: Fire TV Stick at 192.168.1.82, 192.168.1.84, Ooustick at 192.168.1.222

## Memory Constraints
Fire TV Stick has 1GB RAM. Total feature overhead: ~3-6MB. Audio-only mode saves memory by disabling video decoder. Low-memory buffer capping (BufferConfigs.forLowMemory()) for devices with memoryClass <= 128MB.

## Performance Patterns
- **Debounced focus effects**: ChannelPresenter defers expensive visuals (overlay drawables, scale animation, background resource inflation) behind 60ms delay — only lightweight background tint applied immediately during rapid D-pad scrolling
- **Debounced EPG loading**: LiveTvFragment delays EPG API calls by 400ms so fast-scrolling through channels skips intermediate loads
- **EPG cache**: 5-minute TTL in `EpgCacheRepository`. During live playback, periodic refresh every 5 minutes + force-refresh from server when no current program found in cache
- **Cached overlay drawables**: GoldGlowFocusDrawable and FocusBracketDrawable created once per view in onCreateViewHolder, reused via view tags
- **Sound throttling**: DpadSoundManager.playMove() throttled to max once per 80ms during rapid focus changes
- **VerticalGridView tuning**: `setAnimateChildLayout(false)` + `itemAnimator = null` for channel lists
- **Progressive image loading**: Low-res placeholder → dominant color extraction → full image load

## DB Version History
- v1-v4: Phase 1+2 (favorites, watch progress, EPG cache, search history, crash recovery, content cache)
- v5: Phase 3 (WatchAnalyticsEntity, SearchIndexEntity, SearchIndexFts)
- v6: Phase 4 (destructive migration — added columns; resets user data)
- v7: Continue Watching fix (completed/dismissed columns on watch_progress)
- v8: Phase 5 AI features (ChannelWatchLogEntity, ChannelScoreEntity, EpgPatternEntity — destructive, no users yet). Unchanged through v2.5.2.
- v9: poster_cache table (proper Migration, preserves user data)
- v10: series_tracking table (proper Migration, preserves user data)
- v11: blocked_categories table for parental controls (proper Migration, preserves user data)

### MANDATORY: Database Migration Rules
- **NEVER use destructive migration for new DB versions.** Users have real data (favorites, watch progress, series tracking) that must survive updates.
- Every new table or schema change MUST have an explicit `Migration(oldVersion, newVersion)` object in `DatabaseModule.kt` with the DDL SQL.
- `fallbackToDestructiveMigration()` remains ONLY as a safety net for ancient pre-v8 installs — it must NEVER be the primary migration strategy.
- All migrations are defined in `di/DatabaseModule.kt` and added via `.addMigrations(...)` before `.fallbackToDestructiveMigration()`.
- Test migrations by installing the old APK, creating data, then installing the new APK — verify favorites, watch progress, and series tracking survive.

## Version Release History
- **v2.1.0** — Phase 4 premium playback UX (all overlays, track picker, controls bar)
- **v2.1.1** — OTA update system fix, speed test accuracy fix
- **v2.2.0** — Phase 4b audio system hardening (DefaultTrackSelector, AudioLogger, CrashLogger)
- **v2.3.0-v2.3.5** — Hotfixes (stall detector, AlertDialog crash, scroll fix, low-memory buffers, audio fallback, AC3 root fix)
- **v2.4.0** — Phase 5 AI features (For You Live Now, Smart EPG Filler)
- **v2.4.1** — Sleep timer dialog crash fix
- **v2.5.0** — Aurora Cinema Search redesign, removed native libVLC, added Jellyfin FFmpeg audio decoder extension
- **v2.5.1** — Surround audio (5.1/7.1) stereo downmix fix for Ooustick
- **v2.5.2** — Live TV 2ch AC3 crash fix (passthrough matrices + hardware-first renderer mode)
- **v2.6.0** — MultiView Sports Player (4 simultaneous live streams, Pro plan gating, QR upgrade flow)
- **v2.7.1** — Audio pipeline audit (AudioPipelineFactory, 3/4/5ch downmix, user track override, stage 2 fallback, preview audio isolation, stream stats audio row, logarithmic sleep fade, external player position handoff), watch progress indicators on poster/episode cards, MultiView unlocked
- **v2.8.0** — Poster quality overhaul (TMDB w500 rewriting), 4-column grid spacing, favorites default on VOD/Series/Live TV, improved image caching
- **v2.8.1** — Crash fixes (hero rotation divide-by-zero, VOD/Series grid IndexOutOfBoundsException), safe adapter updates via DiffCallback + SafeAdapterUtils, favorites fallback to first real category when empty
- **v2.9.0** — Watch history rows (New Episodes, Watch It Again, enhanced Continue Watching), premium home card redesign (bigger cards, shimmer, metadata reveal, neighbor dimming, row highlights), track picker fix, new app icon/banner
- **v2.9.1** — Proper Room migrations for favorites, watch progress, and series tracking (no more data loss on version bumps)
- **v3.0.0** — Streaming Cinema Experience: hero trailer auto-play, D-pad hero navigation, parallax scroll, time-of-day aurora theming, Quick Tune channel strip, "Because You Watched" genre/cast rows, Live Sports banner, smart trending algorithm, enhanced aurora visibility, favorites logo fix
- **v3.1.0** — Closed Captions & Subtitle System
- **v3.2.0** — Mobile Touch Overhaul: player touch gestures (tap/double-tap/swipe), responsive card sizing for phones, ripple touch feedback, stats button, hero swipe, track picker responsive width + scrim dismiss, MultiView touch support with landscape lock, minimum text sizes, phone color contrast boost, VerticalGridView position -1 crash fix
- **v3.3.0** — Parental Controls: Category-Level Blocking + Error Logging & Diagnostics. Block any category in Live TV/Movies/Series — blocked content invisible everywhere. PIN-gated settings with 3-section tabs, toggle switches, "Block All Adult" auto-detection. Auto-blocks on first PIN setup. 30-min temp unlock, 5-attempt/60s lockout. ContentFilterManager with O(1) filtering. Room DB v11. Stream Diagnostic Logger (rolling file "black box" for playback), ExoPlayer event listener, PlaybackHealthMonitor (buffer/memory/black screen detection), "Send Debug Log" button in Settings, network diagnostic logging.
- **v3.3.1** — Diagnostic logging fix, preview audio, Watch It Again query fix
- **v3.3.2** — Software video decoder fallback (auto-switch to SW AVC when HW fails), frame watchdog retry limit, track picker language persistence, health monitor bandwidth fix
- **v3.3.3** — AC3/EAC3 audio fix for mt8695 Fire TV Sticks: FFmpeg-preferred player rebuild when hardware falsely claims surround support, `audioDisabledByFallback` flag to prevent `onTracksChanged` from undoing Stage 2, broadened `isAudioDecoderError()` detection, three-stage audio recovery ladder
- **v3.3.4** — Faster software video decoder fallback: frame watchdog interval 3s→2s, frozen threshold 5s→3s, software fallback on first failure (was 2). HEVC black screen recovery ~5s instead of ~18s
- **v3.5.1** — Stability & Device Compatibility: DefaultAudioSink.Builder SOC_MODEL crash fix (API <31 safe fallback), UserPlanManager null userInfo guard, ExoPlayer release listener cleanup (code 1003 fix), amlogic HEVC buffer storm detection + FFmpeg audio rebuild, watchdog "Optimizing video playback" overlay, network capability log throttling (60s interval)
- **v3.5.2** — EPG Freshness & Diagnostics: EPG cache TTL 30min→5min, periodic EPG refresh every 5min during live playback, force-refresh from server when no current program found in cache, live TV auto-retry on STATE_ENDED (server connection drops), debug log user/device identification (Xtream username + ANDROID_ID in log headers and Firebase metadata), splash→login `commitAllowingStateLoss()` crash fix
- **v3.5.3** — Performance Hardening
- **v3.5.4** — Watchdog & Stability: fixed black screen loop on 1080p AVC (AFTMM/AFTSSS), OOM crash fix on 160-192MB heap devices, VOD/Series grid position -1 safety guards
- **v3.5.5** — Home Redesign & Hero Fix: Top 10 Today row (Netflix-style ranked cards), personalized Genre Rows (up to 4 by watch affinity), time-of-day greeting on hero ("Good Evening, username"), sports banner removed, mobile touch fixes (single-tap fullscreen Live TV, grid touch passthrough, search grid taps), hero container clipping fix (disabled Ken Burns scale, added programmatic `clipBounds` on hero_container — Fire TV `clipToOutline` doesn't clip hardware-accelerated transforms)

### Phase 14 — Home Redesign & Hero Clipping (v3.5.5)
- **Top 10 Today row** — Netflix-style ranked row on Home showing top 10 VOD items from trending score (rating × recency × user affinity). `Top10Presenter.kt` custom presenter with rank numeral overlay, `item_top10_card.xml` layout, dimens for TV/phone/small phone. (`home/Top10Presenter.kt`, `res/layout/item_top10_card.xml`)
- **Personalized Genre Rows** — Up to 4 dynamic genre rows on Home, sorted by user watch affinity (most-watched categories first), 15 items each, rating-sorted. Dynamically generated at runtime in `HomeFragment.renderGenreRows()`. (`home/HomeFragment.kt`, `home/HomeViewModel.kt` — `GenreRow` data class, `_genreRows` StateFlow)
- **Time-of-day greeting** — Hero shows "Good Morning/Afternoon/Evening/Night, Username" above the metadata chips. Username pulled from `CredentialStore`. (`home/HomeFragment.setupGreeting()`, `res/layout/fragment_home.xml`)
- **Sports banner removed** — `observeLiveSports()` and `loadLiveSportsEvent()` call removed from HomeFragment/HomeViewModel. Saves a network call + EPG parsing on every Home load. Banner View hidden via `sportsBanner.visibility = GONE`.
- **Mobile touch fixes** — `isFocusableInTouchMode = true` on all 10 Home grid rows so touch events reach children instead of being intercepted by Leanback for focus. Live TV single-tap on mobile goes straight to fullscreen (skips preview-then-tap TV flow). Search result grids use `FOCUS_AFTER_DESCENDANTS` on mobile for touch passthrough. (`home/HomeFragment.kt`, `livetv/LiveTvFragment.kt`, `search/SearchFragment.kt`)
- **Hero container clipping fix** — Static hero backdrop was bleeding past the hero_container into the Continue Watching row on Fire TV. Root cause: Ken Burns 1.05x scale transform + Fire TV's `clipToOutline="true"` silently fails to clip hardware-accelerated transforms. Fix: disabled Ken Burns entirely (`startKenBurns` now no-op) and added programmatic `clipBounds` via `addOnLayoutChangeListener` on `hero_container` — `View.setClipBounds(Rect)` IS respected by the GPU rendering pipeline. Trailer wasn't affected because PlayerView's TextureView has its own hardware-layer clipping. (`home/HomeFragment.kt` `bindViews()` and `startKenBurns()`)

