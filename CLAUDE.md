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
- **Database**: Room v5 with FTS4 (not FTS5 — minSdk 21 compat)
- **Navigation**: Manual FragmentManager (no NavGraph)
- **State**: StateFlow / MutableStateFlow
- **Images**: Coil with progressive loading + dominant color placeholders
- **Player**: Media3 ExoPlayer with Leanback PlaybackTransportControlGlue

## Completed Features

### Phase 1 — Scaffold
DI, Room DB, Retrofit, Auth flow, themes

### Phase 2 — UI & Playback
All UI fragments (Home, LiveTV, VOD, Series, Search, Favorites, Settings), presenters, ExoPlayer playback, channel zapping

### Phase 3 — Tier 3+4 Enhancements (16 features)
- #13 Subscription Status Dashboard
- #14 Quick-Access Sidebar
- #15 Progressive Image Loading
- #16 FTS4 Full-Text Search
- #17 Adaptive Fragment Transitions
- #18 Audio Feedback for D-pad
- #19 Smart Onboarding Tutorial
- #20 Predictive Channel Pre-Fetching
- #21 Network-Aware Quality Adjustments
- #24 Picture-in-Picture
- #26 Deep Link Engine (ooustream:// scheme)
- #27 Live Channel Preview on Focus
- #28 Smart Category Ordering
- #29 Audio-Only Mode
- #31 AI-Powered "For You" Recommendations
- #35 Predictive Screen Pre-Warming

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
1. `MainActivity.kt` — sidebar, transitions, PiP, deep links
2. `HomeFragment.kt` — onboarding, sidebar, For You row, pre-warming
3. `OoustreamPlaybackFragment.kt` — audio-only, PiP, quality policy, analytics
4. `OoustreamDatabase.kt` — v5, WatchAnalytics + SearchIndex entities
5. `PlayerViewModel.kt` — analytics recording, stream URL building

## Memory Constraints
Fire TV Stick has 1GB RAM. Total feature overhead: ~3-6MB. Audio-only mode saves memory by disabling video decoder.

## DB Version History
- v1-v4: Phase 1+2 (favorites, watch progress, EPG cache, search history, crash recovery, content cache)
- v5: Phase 3 (WatchAnalyticsEntity, SearchIndexEntity, SearchIndexFts)
