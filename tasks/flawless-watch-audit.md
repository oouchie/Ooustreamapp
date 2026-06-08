# Ooustream Android TV — Watch-Experience Audit: Flawlessness vs Netflix / Disney+ / Hulu

## Executive Verdict

**Overall flawlessness: 5.5 / 10**

A paying customer can put Ooustream on tonight and watch Live TV, a movie, or binge a series end-to-end. On a healthy connection the steady state is genuinely good: acceptable time-to-first-frame, correct resume position via `setMediaItem(item, positionMs)`, proper AC3/EAC3/DTS 5.1→stereo downmix on the 1GB Fire Stick, and an unusually deep, well-engineered failure-recovery stack (content-aware stall detector, frame watchdog, HW→platform-SW→FFmpeg video ladder, audio fallback ladder, STATE_ENDED auto-retry, fast bad-chipset shortcuts). The app is hardened against the things that *crash* a lesser IPTV app, and it deserves real credit for that — most of this audit is about polish and a handful of state-management gaps, not "it doesn't work."

Where it falls short of the streaming-platform bar is concentrated and consistent:

1. **One true P0** — resume progress can silently regress to ~0 when the customer backs out during a buffer/rebuild, because saves aren't gated on a known-good playing state and the DB write is a full REPLACE.
2. **A pervasive perceptual gap** — every transient moment (play, zap, rebuffer, seek refill, decoder rebuild, reconnect) shows the same bare spinner on a pure-black surface, with no poster/backdrop, no held last frame, and a spinner that is dismissed *before* the first frame paints. Normal loading reads as "frozen."
3. **One-directional recovery** — a single stutter permanently downgrades video to soft SD for the rest of the session/title, and a single bad-audio episode leaks silence across an entire binge.
4. **Binge boundaries black-gap** with no pre-buffer, and a flaky next-episode lookup can falsely end a binge.

None of these stop a determined viewer. Together they keep the experience at "works, but visibly less polished than Netflix." The good news: the highest-impact fixes are a small number of shared mechanisms (art-backdrop + first-frame fade + last-frame hold; spinner debounce; resume save-gate; symmetric re-quality).

---

## Flawlessness Scorecard

| Dimension | Score | Verdict |
|---|---|---|
| Startup latency (press play → first frame) | 6 | TTFF acceptable; loss is perceptual — lone spinner on black, dismissed on STATE_READY before first frame paints. |
| Rebuffering | 5 | Real hangs recovered, but recovery blanks to black + un-debounced spinner; sustained-stall recovery hard-stops and cold-restarts. |
| Live TV channel zapping | 6 | Player reused in-place, audio-pop muted, but each zap flashes black behind a bare spinner; banner lags 500ms. |
| Error recovery | 6 | Deeply hardened (back-out works, bad-chipset shortcuts fast) but every path goes black + spinner + "Optimizing…" toast. |
| Resume / continue watching | 4 | setMediaItem-resume correct, but un-gated saves can clobber a good bookmark with ~0. Mandatory resume modal adds friction. |
| Binge / next episode | 5 | No next-episode pre-buffer (black gap); uncached lookup can falsely fire "No more episodes"; cancel path can dead-end black. |
| Audio / A-V sync | 7 | Downmix + fallback solid; gaps are disabled-audio leaking across binge episodes and unrecovered mid-stream sink faults. |
| Seeking | 6 | In-buffer seeks instant; out-of-buffer scrubs lack debounce, use frame-exact seeks, flash a spinner, show only a relative delta. |
| Quality / decoder | 5 | Excellent going DOWN but strictly one-directional — caps/factory never cleared, so a transient stutter softens the whole session. |
| Loading states / perceived perf | 5 | Browse skeletons exist; detail→play is black+spinner; empty first-load categories shimmer forever; poster shimmer ends early. |
| Subtitles / CC | 6 | Sound foundation, but CC toggle fakes "On" when nothing renders, and captions draw behind the controls bar. |
| Memory & stability (1GB) | 7 | Good release/lifecycle hygiene + static caps; image-cache trim hits the wrong (empty) loader; no dynamic buffer tightening. |
| Network reconnect | 6 | Held-frame + stall ladder backstop recovery, but fast network-return skips STATE_BUFFERING; no WAKE_MODE_NETWORK; unlabeled spinner. |
| Discovery → play | 6 | Mostly solid; dominant gap is black-spinner-no-art; Search resumes silently while other surfaces prompt; deep-link Live broken. |

---

## Systemic Themes

- **Spinner-on-black vs hold-last-frame (the #1 cross-cutting gap).** The same bare centered `ProgressBar` over a forced-transparent (black) Leanback `SurfaceView` is the loading/recovery affordance for startup, zap, rebuffer, seek refill, decoder rebuild, and reconnect. No backdrop art, no held last frame, no `onRenderedFirstFrame`-driven crossfade exists anywhere. **One** art-overlay + first-frame-fade + last-frame-snapshot mechanism uplifts seven dimensions.
- **No spinner debounce.** `showBufferingOverlay(true)` fires synchronously on every `STATE_BUFFERING` with no delayed-show and no minimum-visible floor — sub-second refills flash, marginal pipes strobe. One debounce covers rebuffering, seeking, zap, and reconnect perception.
- **One-directional recovery ladders.** Both the video downgrade (720p→480p cap + SW/FFmpeg factory) and the audio-disable fallback go DOWN and never come back UP; caps/flags set `true` are never reset. A transient event permanently degrades the rest of the session/title.
- **Episode-transition state hygiene.** `skipToNextEpisode` / binge `onPlayNext` reuse the shared player via `setMediaItem` but skip the reset block `tuneToChannel` runs — leaking audio-disable state, the subtitle self-test guard, and subtitle re-application into the next episode.
- **Resume-save robustness.** Progress is checkpointed from any state (onPause + 5s loop) with only a percent gate and a REPLACE upsert; a non-playing snapshot clobbers a good bookmark. Checkpointing must be STATE_READY-only and monotonic.
- **Metadata/connection cold tax.** No stream-host preconnect, no next-episode metadata cache, no HTTP disk cache. Cheap preconnect-on-focus + session-scoped metadata caching shave latency and remove the false-binge-stop failure.
- **Inconsistent resume UX.** Blocking modal on detail/hero/recommendation; silent resume from Search and Continue Watching. Converge on silent-resume + an in-player "Restart" chip.

---

## Ranked Top Customer-Impact Hiccups

1. **[P0] Resume bookmark silently regresses to ~0** on a buffer/rebuild exit (Resume).
2. **[P1] Resolution/decoder downgrade is permanent** — one stutter softens the rest of the session/title (Quality/Decoder).
3. **[P1] Every play/zap/recovery shows a bare spinner on pure black** — no poster, no held frame (Startup/Loading/Error-recovery, cross-cutting).
4. **[P1] Buffering spinner has no debounce** — flashes/strobes on sub-second rebuffers (Rebuffering).
5. **[P1] Disabled-audio fallback leaks across binge episodes** — multi-episode silence (Audio).
6. **[P1] No next-episode pre-buffer** — black gap + spinner between every binge episode (Binge).
7. **[P1] Uncached next-episode lookup can falsely end a binge** on a network blip (Binge).
8. **[P1] Wifi returns but player keeps spinning** — fast-retry skips STATE_BUFFERING (Network reconnect).

---

## Per-Dimension Breakdown (verified, un-mitigated hiccups only)

### Startup latency — Score 6
**Verdict:** TTFF itself is within the acceptable IPTV band; the entire deficit is perceptual.

- **[P1] Initial load shows a spinner on a black screen — no poster/backdrop.**
  - *Symptom:* Press play → black screen + small centered spinner for the whole 2-5s cold-start. Reads as "frozen / broken."
  - *Trigger:* Every VOD/Series/Live start and every zap.
  - *Root cause:* `showBufferingOverlay()` (`OoustreamPlaybackFragment.kt:2151-2169`) builds only a bare `ProgressBar` over a black `SurfaceView` (fragment bg forced TRANSPARENT, lines 202/207; class extends `VideoSupportFragment`, line 81). `streamIcon` is stored (`:3401-3435`) but never rendered as a backdrop. `onRenderedFirstFrame` only logs (`ExoPlayerDiagnosticListener.kt:70-72`).
  - *Fix:* Full-screen ImageView behind the SurfaceView; Coil-load `streamIcon`/cover with a dim scrim in `onViewCreated`; keep spinner on top; crossfade out (~200ms) on `onRenderedFirstFrame`. Art is already in Coil memory cache from the row the user clicked — near-free.
  - *Benchmark:* Netflix/Disney+/Prime/Hulu always paint key art + shimmer during spin-up and swap to video only on first frame.

- **[P2] Spinner is hidden on STATE_READY, before the first frame paints.**
  - *Symptom:* On cold start the spinner vanishes a beat before any picture appears — a brief totally-black, spinner-less gap (looks even more like a freeze).
  - *Root cause:* `showBufferingOverlay(false)` on `STATE_READY` (`:1152`) fires before `onRenderedFirstFrame` (decoder still spinning up first output buffer), worse on mt8695/mt8167.
  - *Fix:* Keep the overlay (and the backdrop) visible until `onRenderedFirstFrame`, not `STATE_READY`. Same fix family as the P1 above — implement together.

- **[P2] No stream pre-warm / preconnect.** Press-play pays cold DNS→TCP→TLS→302 because both pre-warm paths warm METADATA only (`PredictivePreFetcher.kt:35-54`, `HomeViewModel.kt:213-223`). *Fix:* On poster/channel focus dwell (reuse `ScreenPreWarmer`'s 500ms debounce) issue an OkHttp preconnect to the resolved stream host. Saves the handshake (hundreds of ms), most relevant when the provider 302-redirects to a separate CDN. **Do not** pre-buffer adjacent channels on the 1GB target.

- **[P3] VOD/Series resume re-reads the DB on the press-play critical path.** `getResumePositionSync()` (`PlayerViewModel.kt:77-83`) is awaited before `setMediaItem`, even though `HomeFragment.playVodWithResumeCheck` already queried it. *Fix:* Thread the resolved position through `newInstance`; ~1-5ms, imperceptible — cleanup, not friction.

- **[P3] Buffering spinner has no debounce** (shared with Rebuffering #2/#4 below).

### Rebuffering — Score 5
**Verdict:** Real outages recover; transient dips look worse than they are, and the heavy recovery blanks the screen.

- **[P1] Sustained-stall recovery does a hard `player.stop()` that black-screens instead of holding the last frame.**
  - *Symptom:* On a stall outlasting the timeout, the picture goes fully black + bare spinner for the retry delay (1s→3/5/8/12/15s), then cold-restarts from an empty buffer — a void, not a pause-and-resume.
  - *Trigger:* LIVE stays in `STATE_BUFFERING` the full 15s (VOD/SERIES 30s) — e.g. a sustained WiFi sag. **Not** every dip (short dips auto-resume natively with no stop).
  - *Root cause:* `startStallDetector()` waits `delay(stallTimeout)` then `p.stop()` (`:1499-1508`), clearing decoder output onto a raw SurfaceView (`LeanbackPlayerAdapter`, `:383`) with bg forced TRANSPARENT (`:202/207`). No `setKeepContentOnPlayerReset` (that's a PlayerView API; this is VideoSupportFragment).
  - *Fix:* Don't `stop()` on the first 1-2 escalations — leave it in `STATE_BUFFERING` to refill and auto-resume (invisible recovery); only escalate to `stop()/prepare()` after a longer threshold when the source is genuinely dead. Additionally hold the last frame via a TextureView snapshot ImageView during any stop/rebuild.
  - *Benchmark:* Netflix/YouTube TV freeze the last frame, show a subtle spinner only after a grace period, refill in the background, never clear to black.

- **[P1] Buffering spinner pops instantly on every `STATE_BUFFERING` — no debounce, no scrim, no minimum-show.**
  - *Symptom:* A bare gray spinner blinks for sub-second rebuffers and strobes during BUFFERING↔READY churn.
  - *Root cause:* `STATE_BUFFERING → showBufferingOverlay(true)` synchronous (`:1129`); `STATE_READY` hides immediately (`:1152`); overlay is a bare `ProgressBar` with no scrim/theme/debounce (`:2151-2169`).
  - *Fix:* Post a ~600-800ms delayed show, cancel on `STATE_READY`; enforce ~500ms minimum on-screen once shown; style with a scrim. **Caveat:** don't swallow the frame watchdog's deliberate `showBufferingOverlay(true)` at `:1577-1580`. (The watchdog also fires a "Optimizing video playback…" toast at `:1581-1583` that compounds the "struggling" impression — drop it.)

- **[P2] Pre-buffer is sized once at player creation and never adapts.** On the primary 1GB stick (`memoryClass<=192`, `:226-230`) the buffer is the fixed `forLowMemory` LIVE 3s/8s (`BufferConfigs.kt:57-65`); `QualityPolicy.tier` is bypassed there. Real per-stream throughput (`DefaultBandwidthMeter`) is read only for logging (`PlaybackHealthMonitor.kt:72-75`). *Fix:* (a) Raise the LIVE `minBuffer` floor in `forLowMemory` 3s→~6s within the memory budget; (b) on higher-memory tiers drive `QualityPolicy` from `bitrateEstimate` rather than the OS link hint. Bounded by single-bitrate reality — deeper buffer only partially masks a collapsed pipe and costs start-latency + memory on 1GB.

- **[P2] Live channel zap clears to a spinner with no last-frame hold** (same mechanism as live-zap #1 — bundle the fix). `tuneToChannel` does `setMediaItem→prepare→play` on the existing player (`:2633-2635`); the old output is flushed to black. *Fix:* hold the outgoing frame until `onRenderedFirstFrame` of the new channel; suppress the spinner for the first ~700ms.

### Live TV channel zapping — Score 6
**Verdict:** Mechanics are right (in-place reuse, audio mute); the transition looks like a TV blink.

- **[P1] Every channel change flashes a black screen behind a bare centered spinner.**
  - *Symptom:* Each D-pad zap cuts to a black SurfaceView + small spinner — no held frame, no channel-branded backdrop. Reads as "the TV blinked off."
  - *Root cause:* `tuneToChannel()` reuses the same ExoPlayer on a Leanback SurfaceView; `prepare()` tears down prior output → black until first frame. `setKeepContentOnPlayerReset`/`setShutterBackgroundColor` appear nowhere (grep-confirmed). Banner is posted after `delay(500)` (`:2649-2652`). The black is **connect+first-frame only** (~1.5-3s), not debounce-inclusive — the old frame persists through the 300ms debounce.
  - *Fix:* Show a dark scrim carrying the target channel logo/name/number + thin bar synchronously when `tuneToChannel` starts; dismiss on `onRenderedFirstFrame` (wire the currently-unused AnalyticsListener callback); show channel-info immediately instead of the 500ms-delayed banner; optionally hold the prior frame as a freeze-bitmap so the change crossfades.
  - *Benchmark:* YouTube TV / cable guides crossfade or show a channel-branded loading card; Netflix holds box-art during any source switch.

- **[P2] Preview→fullscreen re-buffers the SAME stream from scratch.** `goFullscreen()` calls `stopPreview()→previewManager.release()` (`LivePreviewManager.kt:84-92`) then `tx.replace` launches a fresh `OoustreamPlaybackFragment` that cold-starts the identical URL. *Trigger:* MID/HIGH stick where auto-preview ran (~1s dwell); NOT on ULTRA_LOW (`memoryClass<=128`, no preview) and NOT on a quick OK. *Fix:* hand the warm preview ExoPlayer to the fullscreen surface, or at minimum capture the preview's last frame as a freeze-backdrop behind the fullscreen spinner (dovetails with the zap shutter mask).

### Error recovery — Score 6
**Verdict:** Heavily hardened — back-out works mid-recovery, bad chipsets are shortcutted. The gap is presentation.

- **[P1] Every recovery path goes to BLACK + spinner instead of freezing the last frame.** Mid-movie, any rebuffer or rebuild (`rebuildPlayerWithSoftwareDecoder` `:1835`, `…FfmpegVideoDecoder` `:1955`, `…FfmpegPreferred` `:2048`) goes black + spinner + an "Optimizing video decoder/playback…" toast. *Root cause:* VideoSupportFragment SurfaceView; `safeReleasePlayer()` does `clearVideoSurface()+release()` (`:1404-1410`); no last-frame retention anywhere (grep-confirmed). *Fix:* TextureView-snapshot the current frame into an ImageView above the surface before stop/clear; show it (optionally a small corner spinner) during recovery; crossfade to live on the new player's `onRenderedFirstFrame`. Drop both "Optimizing…" toasts — recovery should be silent. (`setKeepContentOnPlayerReset` is the wrong API here — it's PlayerView-only.)

- **[P2] Buffering spinner flashes on every sub-second transient and every channel change** — same root cause/fix as Rebuffering #2 (no debounce at `:1129`, `:1379`, and via `tuneToChannel`).

- **[P2] Decoder-incompatible streams can show black+spinner ~10-13s before recovery/error on a NON-shortcut chipset.** The ladder is sequential; each rung needs a fresh ~3s frozen-detection plus a rebuild/buffer. The known-bad mt8695/mt8167 path is already fast (~5-8s). *Residual gap:* a non-listed silently-stalling decoder walks the full ladder with only a bare spinner. *Fix:* after ~3s of continuous recovery, show a dismissable "Optimizing playback…" card (Exit / Choose-different-stream); add a hard wall-clock recovery ceiling (~10s) → friendly error; generalize the silent-stall fast path beyond the hardcoded list. **Note:** BACK already aborts recovery (the `OnBackPressedCallback` at `:898-917` pops the fragment and `onDestroyView` cancels all jobs) — the customer is not trapped.

- **[P3] Mid-stream rebuffer recovery does a full `stop()`/restart that discards the partial buffer.** Only fires after the full 30s VOD / 15s LIVE timeout, so it's not interrupting a recoverable blip — the residual harm is just discarding the partial buffer (slightly slower cold restart). The black-screen portion is subsumed by the hold-last-frame fix.

### Resume / continue watching — Score 4
**Verdict:** The resume *design* is correct; the *save path* has the audit's only P0.

- **[P0] `onPause` + 5s autosave save `currentPosition` with no state/monotonic guard.**
  - *Symptom:* Watch to 1h30m, the stream rebuffers/rebuilds, press Back during the spinner → next launch replays from the start. Progress silently regressed. **Worse:** the 5s autosave loop corrupts with *no* user action — it ticks during the rebuild prepare→seek window when `currentPosition` reads ~0.
  - *Root cause:* onPause (`:2943-2963`) and the 5s loop (`:977-993`) both save whenever `dur>0 && pct>0.05` with no `STATE_READY` check and no monotonic floor. `saveProgress()` → `WatchProgressDao.upsert` is `@Insert(OnConflictStrategy.REPLACE)` (`WatchProgressDao.kt:35`), fully replacing position+percent+completed; `lastWatched` re-defaults to now, floating the corrupted row to the top of Continue Watching. Rebuild captures pos before release then `setMediaItem+prepare`, `seekTo` only after (`:1928-1932`) — async window reads ~0.
  - *Fix:* Gate every save on `playbackState == STATE_READY` (or `playWhenReady && pos>0`); make the bookmark monotonic (refuse a >~15s regression vs the prior persisted position unless the user seeked back); skip saves entirely during an active rebuild via a `rebuildInProgress` flag. Keep the 95%/STATE_ENDED completion logic intact.
  - *Benchmark:* Netflix/Disney+ checkpoint only from a known-good playing state and treat the bookmark as monotonic within a session.

- **[P1] Mandatory Resume/Start-Over modal on every detail/hero/recommendation play.** `ResumePlaybackHelper.showIfNeeded()` (`:12-30`) builds a blocking AlertDialog whenever progress exists; only the Continue Watching card path resumes silently. Two contradictory resume UXs ship. *Fix:* make resume silent on all paths (the `setMediaItem(uri, positionMs)` machinery already exists and is proven on the CW path); replace the modal with an in-player "Resuming from 1:24:05 — press DOWN to restart" chip + a persistent Restart control. *Benchmark:* Netflix/Disney+/Prime resume instantly, surface "Restart" inside the player.

- **[P3] Binge "Up Next" card shows a phantom ~6% progress bar.** `advanceSeriesOnCompletion()` writes `progressPercent=0.06f, duration=1` (`PlayerViewModel.kt:161-181`) to clear the `>0.05` filter; `ContinueWatchingPresenter` sets the bar unconditionally (`:42`). *Fix:* hide the bar for the `isUpNext` (position==0) case it already computes (`:46`), or add a queued flag and store `progressPercent=0f`. (Note: the "Up Next" label and time-left are already handled correctly — purely a cosmetic phantom bar, zero functional impact.)

- **[P3] Resume position never re-validated against actual duration.** `getResumePositionSync()` (`:77-83`) returns the stored position with no upper bound; if the server re-encoded the file shorter, resume can park on a black end frame. ExoPlayer's internal clamp prevents a crash. *Fix:* after `STATE_READY`, if startPos is within the last ~10s of (or beyond) the now-known duration, restart from 0. Rare precondition — P3.

### Binge / next episode — Score 5
**Verdict:** Functional but every boundary is felt, and the failure modes can falsely end a binge.

- **[P1] No next-episode pre-buffering — black gap + spinner between every episode.** Both advance paths do bare `setMediaItem→prepare→play` into the same player (`:490-492`, `:2344-2346`); grep confirms zero `ConcatenatingMediaSource/addMediaItem/seekToNextMediaItem/preload`. The 15s binge runway is used only for the countdown card. *Fix:* resolve+`addMediaItem(nextMediaItem)` at the 15s mark so it pre-buffers, then `seekToNextMediaItem()` on advance. Interim: hold the last frame under a dimmed scrim.

- **[P1] `resolveNextEpisode()` does an uncached network round-trip (up to 3×/boundary) and can false-stop the binge.** `getSeriesInfo` is uncached (`ContentRepository.kt:75-78`) and the OkHttpClient has no `.cache()` (`NetworkModule.kt:44-67`); the function returns `null` on ANY exception (`PlayerViewModel.kt:256-258`), conflating fetch-failure with episodes-exhausted → fires "No more episodes" / series-complete on a real next episode. *Fix:* cache `getSeriesInfo` per `seriesId` for the session; resolve next-episode ONCE and stash it; distinguish "list parsed, current is last" from "lookup threw" and retry briefly on a throw, keeping the binge card up.

- **[P2] Episode ends to a black/idle player with no auto-play and no completion UI.** Two paths: (a) the 1s poll never observes `(dur-pos)<15000` before `STATE_ENDED` (unknown/0 duration, stall over the final 15s, hard cut); (b) **the user pressed Cancel on the binge card** — `onCancel` (`:505`) never resets `bingeShown` (only reset at the two auto-advance paths). SERIES `STATE_ENDED` (`:1188-1192`) only inserts a DB Up Next row — no auto-play, no overlay (unlike VOD `:1194-1203`). *Fix:* in SERIES `STATE_ENDED`, when no in-player binge UX ran, resolve next (reuse the cached result) and auto-advance OR show `seriesCompleteOverlay`; reset `bingeShown=false` in `onCancel`.

- **[P3] Binge transition uses a bare spinner + Toast — no last-frame hold, no branded next-up card.** `showBufferingOverlay` is the generic spinner; confirmation is a `Toast` (`:499`, `:2353`); the countdown card has no episode artwork. *Fix:* dedicated "Up Next" card (title + S/E + thumbnail over dimmed last-frame). Largely a cosmetic restatement of the pre-buffer finding — implement *with* it, not before it.

### Audio / A-V sync — Score 7
**Verdict:** The best-hardened dimension; one narrowly-triggered P1.

- **[P1] Disabled/fallback audio state leaks from one episode to the next in a binge.** `skipToNextEpisode` (`:2332-2358`) and binge `onPlayNext` (`:478-504`) reuse the shared player but never reset `audioFallbackAttempted` / `audioDisabledByFallback` / `userTrackOverrideActive` / `mtkMultichannelFfmpegApplied` and never clear `setTrackTypeDisabled(AUDIO,true)`; `onTracksChanged` then early-returns (`:1292-1294`). The reset block exists only in `tuneToChannel` (LIVE) and the two retry handlers. *Trigger (narrow):* only when Stage-2 disable-audio actually fired (FFmpeg unavailable or also failed). *Fix:* extract `resetAudioStateForNewContent()` and call it at the top of both episode-transition paths before `setMediaItem`. (`userTrackOverrideActive` leaking is a real secondary bug — a manual track pick on episode N suppresses English auto-select on N+1.)

- **[P2] Mid-stream audio dropout (sink error / sustained stall) is logged but never recovered.** `checkAudioStall()` (`PlaybackHealthMonitor.kt:190-229`) and `onAudioSinkError/Underrun/Disabled` (`ExoPlayerDiagnosticListener.kt:137-154`) are pure log calls; the fallback ladder lives only in `onPlayerError` reachable via `PlaybackException`, so a non-fatal sink error never triggers recovery. *Fix:* add an `onAudioStall` callback (and wire `onAudioSinkError`) that toggles `setTrackTypeDisabled(AUDIO,false)+re-select` or escalates to `rebuildPlayerWithFfmpegPreferred()`, with an attempt-cap. Already correctly gated on `hasSelectedAudio && volume>0`. *Severity note:* per project triage, these events overwhelmingly ride on network starvation (nothing to recover); standalone irrecoverable sink faults are the minority — hence P2.

- **[P3] Anti-pop mute on channel switch is defeated by synchronous volume restore.** `tuneToChannel` sets `volume=0f → setMediaItem/prepare/play → volume=1f` all synchronously (`:2630-2636`), so the mute window has zero wall-clock duration. The only possible artifact is the NEW stream's first un-faded buffer (faint click), masked by the >1s buffering gap on most zaps. *Fix:* restore volume from `STATE_READY`/`onRenderedFirstFrame` with an 80-120ms fade-in and a ~600ms safety timeout. (Note: the VOD/Series initial-load path does NOT mute at all — there's no resume-mute to defeat.)

### Seeking — Score 6
**Verdict:** Correctly insulated from the recovery ladder; out-of-buffer scrubbing is slow and unguided.

- **[P2] Every D-pad seek tap fires an immediate network seek — no scrub accumulation/debounce.** Every path commits `seekTo()` synchronously on ACTION_DOWN (`OoustreamPlaybackGlue.kt:258-278`, `PlayerControlsBar.kt:636-660`, fragment `:829-837`); `seekDeltaForRepeat` only grows the *step*, not coalesces commits. In-buffer seeks are instant; the storm only manifests stepping repeatedly past the buffer. *Fix:* accumulate `pendingSeekTargetMs`, update the seekbar optimistically, commit one `seekTo()` after ~250-350ms idle / on key-up. (Seek BUFFERING is correctly NOT escalated by the watchdog — `:1156-1159` — so a storm never cascades into retries.)

- **[P2] No `setSeekParameters()` — every seek is frame-EXACT.** All four ExoPlayer.Builder sites omit it (grep: zero hits), so out-of-buffer seeks must decode from the preceding keyframe. *Fix:* `SeekParameters.CLOSEST_SYNC` during active coarse scrub only; keep EXACT for the final landing commit and for resume/chapter jumps.

- **[P2] Seek-induced rebuffer shows a bare spinner over the frozen frame.** Same no-debounce root cause as Rebuffering #2 — gate `showBufferingOverlay(true)` behind a ~400-600ms cancellable delay.

- **[P3] No scrub preview thumbnails or absolute scrub-time bubble.** Only a cumulative relative "+Ns" (`SeekFeedbackOverlay.kt:36-57`); no `PlaybackSeekDataProvider`. *Fix:* render the ABSOLUTE target timecode at the seekbar thumb during scrub (pairs with the pendingSeekTarget). True trickplay thumbnails are impractical for single-bitrate Xtream VOD on 1GB — the timecode bubble is the realistic win.

### Quality / decoder — Score 5
**Verdict:** Excellent fallback going DOWN, with a structural blind spot: it never climbs back.

- **[P1] Watchdog 720p/480p downgrade + SW-decoder rebuild is permanent.** `trackSelector` is created once (`:243`) and reused across every rebuild and every `tuneToChannel`; `setMaxVideoSize(720)/(480)` is applied (`:1746-1788`) and NEVER cleared (no `clearVideoSizeConstraints` anywhere); `usingSoftwareVideoDecoder`/`usingFfmpegVideoDecoder` are set `true` and never reset. `tuneToChannel` resets retry/audio state but never the video cap or factory. So after one stutter, every subsequent channel inherits the 480p SW player for the whole session. *Fix:* in `tuneToChannel`, `clearVideoSizeConstraints()` + rebuild once with the HW factory + reset both flags; in the watchdog "sustained playback confirmed" branch (`:1815`), after N good polls clear the cap and attempt ONE upward HW step, falling back only if it re-stalls; keep the cap sticky ONLY for mt8695/mt8167. The Retry handler (`:2178-2189`) should also clear the video cap. *Benchmark:* top platforms' quality controllers are bidirectional — a momentary dip never costs the rest of the title.

- **[P2] No upward decoder/resolution re-probe after a transient VOD/Series downgrade.** Same root cause, VOD manifestation: the player isn't recreated mid-title, so a brief dip leaves the rest of the movie in SD SW decode. *Fix:* debounced upward re-probe after `SUSTAINED_PLAYBACK_POLLS×2` (~12s) of smooth playback + good buffer: `clearVideoSizeConstraints()` + HW rebuild at the current position; if it re-stalls within a short window, fall back and mark confirmed-bad (don't oscillate).

- **[P2] ULTRA_LOW mt8695 "tries" HEVC Main 10 it can't decode → often a permanent ~3fps FFmpeg slideshow with NO error.** v3.7.9 removed the upfront refusal (`:1220-1245` now only logs `HEVC_MAIN10_ATTEMPT`); the watchdog routes mt8695 to `rebuildPlayerWithFfmpegVideoDecoder()`. FFmpeg HEVC at ~3fps *advances* frames, so it never trips the frozen-frame watchdog and the friendly-error give-up frequently never fires — the user is stranded in an unwatchable slideshow. *Fix:* restore a fast-fail in `onTracksChanged` (HEVC Main 10 + `canDecodeHevcMain10()==false` + ULTRA_LOW → `showFriendlyError` within ~1s); re-add a slideshow guard (sustained <~10fps over several polls → give up). Narrow device/content slice, but the no-error variant nudges toward P1 for that class.

### Loading states / perceived performance — Score 5
**Verdict:** Browse has skeletons; the play transition and a couple of empty-state edges undercut it.

- **[P1] Detail→play shows a black screen + tiny bare spinner instead of the title's art.** Same root cause/fix as Startup #1 and Discovery #1 — full-bleed backdrop ImageView + `onRenderedFirstFrame` crossfade. Worst on VOD/Series start (screen literally empty); on zap, the `ChannelZapOverlay` softens it slightly.

- **[P1] Mid-stream rebuffer flashes a bare spinner with no threshold and no hold-frame treatment.** Same no-debounce root cause (`:1126-1152`, `:2151-2169`). Borderline P1/P2 — recovers <1s, but strobes visibly on flaky pipes. *Fix:* ~600-800ms delayed cancellable show + ~500ms minimum + dim the held frame.

- **[P2] Empty VOD/Series category can leave shimmer skeletons on screen permanently.** The skeleton→real swap is gated `if (!skeletonSwapped && list.isNotEmpty())` (`VodFragment.kt:233-253`, `SeriesFragment.kt:215-232`); `_isLoading` exists in the ViewModel but neither fragment observes it, and there's no empty-state view. *Narrow trigger:* only when the FIRST-loaded category for the fragment instance resolves empty (New Releases year-filter, fully parental-blocked, or empty server category); a later empty category shows a blank static grid. *Fix:* observe `_isLoading`; on empty-after-loaded swap to a friendly empty-state; reset swapped state per `selectCategory`.

- **[P3] Poster shimmer dismisses at 300ms keyed to `image.drawable != null` — true the instant the synchronous color placeholder is set.** On slow networks the shimmer ends while the card is still a flat color rectangle (`PosterPresenter.kt:113-138`, `ProgressiveImageLoader.kt:39-77`). *Fix:* drive shimmer dismissal from Coil's `onSuccess`, keeping the 1500ms timeout only as a safety net.

### Subtitles / CC — Score 6
**Verdict:** Solid foundation (single track selector, language carry-over, Netflix-like caption style); two real UX gaps.

- **[P1] CC toggle says "CC On" even when no subtitle track exists or matches.** `toggleClosedCaptions()` (`:2866-2888`) computes `newEnabled` purely from the inverse disabled-flag, immediately calls `updateCcState(true)` + a hardcoded "Subtitles On" toast, and never checks `player.currentTracks` for a TEXT group or verifies a track was actually selected. Since `subtitlesEnabled` defaults to false, the CC button is the *primary* entry point — the very first action a captions-wanting customer takes hits the fake "On." *Fix:* before enabling, check for any `TRACK_TYPE_TEXT` group; if none, stay Off + "No subtitles available for this content"; if some exist, after enable hook the next `onTracksChanged`/`onCues` and auto-select an available track (any language) or revert to Off naming the available languages. Mirror the actually-selected check in the picker dismiss path (`:636-643`).

- **[P2] Subtitles render behind the controls bar and get dimmed/overlapped.** `configureSubtitleView` adds the SubtitleView (`:736`) *before* the MATCH_PARENT controls bar (`:749`), so the bar paints over it; fixed bottom padding `screenHeight*0.08` (`:2794`) puts captions in the controls band; no `bringToFront`/elevation exists. The strong caption outline keeps text legible, so it's dimming/partial-overlap, not full occlusion. *Fix:* add the SubtitleView after the controls bar (or `bringToFront()`), and in `onVisibilityChanged` raise the bottom padding to ~22% while controls are visible, animated to match the fade.

- **[P2] Manual non-preferred subtitle choice is dropped on next-episode autoplay; self-test stops after episode 1.** Both episode paths only `setMediaItem` and don't reset `subtitleSelfTestRan` or re-apply subtitle params (unlike `tuneToChannel`). The exact per-track override (referencing the old media's group) is invalid for the new item — but the *language* preference DOES carry (the picker sets `setPreferredTextLanguage` + persists it), so only the exact-track override dies. The self-test stopping is diagnostic-only. (Note: the auditor's "frozen last caption through the gap" claim was refuted — Media3 1.10.0 `TextRenderer` clears cues on `setMediaItem`.) *Fix:* extract `reapplySubtitleState()` (re-enable TEXT per pref + `setPreferredTextLanguage` + reset `subtitleSelfTestRan`) and call it from both episode paths.

- **[P3] Legacy `TrackSelectionHelper` is dead code** with an unguarded array index and no language persistence (`TrackSelectionHelper.kt:109-116`). Unused (grep: only self-reference); the live `TrackPickerOverlay` path has both the bounds guard and language persistence, so no customer is exposed. *Fix:* delete it.

### Memory & stability (1GB) — Score 7
**Verdict:** Good lifecycle hygiene and static caps; two wiring/robustness gaps, neither a steady-state breaker.

- **[P2] `trimForPlayback()` clears the wrong (effectively empty) Coil cache.** Two ImageLoaders exist: the `OoustreamApp.ImageLoaderFactory` singleton (every `imageView.load()` resolves to it) and an injected `AdaptiveImageLoader` (`allowRgb565(true)`) wired ONLY into `PredictivePreFetcher`, which never calls it — so its cache is never populated and its RGB565 setting is dead. `trimForPlayback()` (`:393`) clears that empty cache; the real display cache (~14-19MB on a 1GB stick) stays resident the whole movie. *Fix:* collapse to ONE loader (return `adaptiveImageLoader.imageLoader` from `newImageLoader()`, add `allowRgb565(true)`), then `trimForPlayback()` does `memoryCache.trimToSize(maxSize/4)` on player entry via `context.imageLoader`. An OOM amplifier at the margin (decoder/YUV buffers dominate heap), not a primary cause.

- **[P2] Health monitor logs heap every 60s but never acts on it.** The memory tick only calls `logger.logMemory(...)` (`PlaybackHealthMonitor.kt:102-109`); no `onTrimMemory`/`ComponentCallbacks2` exists in app code, and all memory defenses are chosen once at init. *Note:* Coil 2.x registers its OWN `ComponentCallbacks2` and auto-trims its memory cache under system pressure — so the image cache IS shed; the real residual gap is no app-level dynamic BUFFER tightening. *Fix:* in the 60s tick, trim the unified image cache + log `MEMORY_TRIM` past a tier-aware threshold; register a `ComponentCallbacks2` that nudges the load control toward smaller buffers on `TRIM_MEMORY_RUNNING_CRITICAL` while a player is foregrounded.

- **[P2] Buffering spinner shows instantly on every dip** — same no-debounce item, listed here for the long-movie-on-imperfect-wifi case.

- **[P3] Diagnostic logger flushes to disk on every event from the main thread.** `write()` (`StreamDiagnosticLogger.kt:280-291`, `@Synchronized`) does `write()+flush()` per call, invoked directly from AnalyticsListener callbacks that run on the main thread (no `setPlaybackLooper`) — a few ms of main-thread I/O exactly during a degraded burst. *Fix:* post writes to a single-thread dispatcher; periodic flush (every 1-2s / N lines) + explicit flush in the crash handler and `onPause`/`onDestroyView`.

### Network reconnect — Score 6
**Verdict:** Held-frame + stall ladder backstop recovery; one missed fast-path plus best-practice and UX gaps.

- **[P1] Wifi returns but the player keeps spinning — fast-retry skips the `STATE_BUFFERING` case.** The network-return branch gates re-prepare on `STATE_IDLE || playerError != null` (`:1383`); on a fast reconnect the player is still `STATE_BUFFERING` with no error, so the restored-connectivity signal is ignored and recovery falls to the 15s/30s stall detector or 30s OkHttp readTimeout. *Fix:* broaden the guard to also handle `STATE_BUFFERING` (reset `retryCount`, `seekToDefaultPosition` for LIVE, `prepare()`, `play()`). Optionally add a SEPARATE stream-only OkHttpClient with 8-10s timeouts — do NOT shorten the shared singleton (Retrofit/EPG/auth reuse it for slow `get_live_streams` payloads). *Scope note:* many blips keep the same Network handle and fire no callbacks (ExoPlayer self-resumes); the bug bites only when `onLost→onAvailable` actually fired before the player errored — hence P1, not P0.

- **[P2] No `WAKE_MODE_NETWORK`.** None of the 6 ExoPlayer.Builder sites call `setWakeMode(C.WAKE_MODE_NETWORK)` (defaults to NONE), so the radio/CPU can power-save during a stall; `FLAG_KEEP_SCREEN_ON` covers only the display and is cleared when `isPlaying` flips false (during buffering). `WAKE_LOCK` is already in the manifest. *Fix:* add `.setWakeMode(C.WAKE_MODE_NETWORK)` to the main builder + the three rebuild factories + MultiView. One-liner, no manifest change.

- **[P2] Reconnect UX is a bare unlabeled spinner.** `showBufferingOverlay` is reused for normal-buffer / network-loss / watchdog with no "Reconnecting"/offline copy (no such string exists); the only network-aware messaging is the terminal modal after retries exhaust. *Fix:* give the overlay an optional label — "Reconnecting…" (or "Waiting for network" when `networkMonitor.isConnected == false`) on the network-loss branch and after the first stall retry; keep it subtle/non-modal over the held frame.

- **[P3] Stall recovery does `stop()` + fixed delay instead of an in-place refill.** First escalation does `p.stop()` then `delay(1000)` before `prepare()/play()` (`:1499-1517`); a bare `prepare()` reconnects faster on a still-alive socket. Only fires after the full 15s/30s window, so the customer has already seen a spinner — marginal polish win.

### Discovery → play — Score 6
**Verdict:** Mostly solid; the dominant gap is the shared black-spinner-no-art, plus an inconsistency and a broken deep link.

- **[P1] Every play action opens on a black screen + bare spinner (no poster/backdrop/last-frame).** Same root cause/fix as Startup #1 and Loading #1 — full-screen art ImageView + `onRenderedFirstFrame` crossfade. Fires on 100% of plays.

- **[P2] Channel zap drops to black + spinner; banner lags 500ms.** Same as live-zap #1 — synchronous channel-art/banner on key-press + held outgoing frame.

- **[P2] Search movies silently jump to the middle (no Resume dialog), unlike every other surface.** `SearchFragment.navigateToContent`'s VOD branch (`:611-628`) builds `newInstance` without `forceStartFromBeginning` and without `ResumePlaybackHelper`, so `needsResume` auto-seeks silently — while Home/detail prompt. *Fix:* converge the surfaces (route Search through the same resume-check, or — better — move everything to silent-resume + in-player Restart, which makes Search's behavior the correct one). Low frequency.

- **[P3] Resume requires a blocking modal** — see Resume #2 (same `ResumePlaybackHelper` finding; a design-philosophy call, fully functional).

- **[P3] Live deep-link shows placeholder "Channel 1234" and seeds no channel list.** MainActivity's deep-link Live branch builds a real URL via `buildLiveStreamUrl` (`:383`) but hardcodes `streamName = "Channel ${streamId}"` (`:388`) and never calls `setChannels` — so zap/up-down is silently dead after a deep-link tune-in. (The auditor's "For You Live opens with empty URL" claim is FALSE — that path builds a valid URL and seeds channels; the empty-URL branch is dead code.) *Fix:* look up the channel by `streamId` for the real name + seed the channel list; delete the unreachable empty-URL "live" branch.

- **[P3] Movies route through a detail screen before Play.** Grid-tile primary action opens `VodDetailFragment` rather than playing directly; a post-API `playButton.requestFocus()` (`VodDetailFragment.kt:260`) can steal focus when metadata returns. (Play is NOT network-gated — `containerExtension` is pre-warmed from the grid payload and the resume label is a local Room Flow.) *Fix:* make the tile primary action play directly with a resume check; remove the redundant post-API `requestFocus()`.

---

## Prioritized Fix Backlog (P0 → P3)

### P0 — breaks/loses watching; fix first
1. **Gate progress saves on STATE_READY + monotonic floor + skip-during-rebuild.** `OoustreamPlaybackFragment.kt` onPause (`:2943-2963`) and 5s autosave loop (`:977-993`); read prior persisted position before write; add a `rebuildInProgress` flag set across `rebuildPlayerWith*`. Keep `WatchProgressDao` REPLACE but never feed it a non-READY snapshot. *Closes the only P0.*

### P1 — frequent/notable hiccups; the bulk of the streaming-bar gap
2. **Build the shared player art/transition layer.** Add a full-screen ImageView behind the Leanback SurfaceView in `OoustreamPlaybackFragment`; Coil-load `streamIcon`/cover with a dim scrim in `onViewCreated`; keep it + spinner until `onRenderedFirstFrame` (wire the AnalyticsListener / `ExoPlayerDiagnosticListener:70` callback to drive the fade), NOT `STATE_READY` (`:1152`). Capture a TextureView last-frame snapshot to hold during stop/rebuild/zap instead of clearing to black. Drop the "Optimizing…" toasts (`:1581-1583`, SW-rebuild toast). *Uplifts Startup, Rebuffering, Live-zap, Error-recovery, Loading, Discovery simultaneously.*
3. **Debounce the buffering spinner.** Post a ~600-800ms cancellable `showBufferingOverlay(true)`; cancel on `STATE_READY`; enforce ~500ms minimum-visible. Carve out the frame watchdog's deliberate show (`:1577-1580`) and the immediate cold-start indicator. *Uplifts Rebuffering, Seeking, Live-zap, Network-reconnect, Loading.*
4. **Make the decoder/resolution downgrade bidirectional.** In `tuneToChannel`: `clearVideoSizeConstraints()` + rebuild once with the HW factory + reset `usingSoftwareVideoDecoder`/`usingFfmpegVideoDecoder`. In the watchdog "sustained playback confirmed" branch (`:1815`): after N good polls, clear the cap and attempt ONE upward HW step, fall back only on re-stall. Keep the cap sticky ONLY for mt8695/mt8167. Add the cap-clear to the Retry handler (`:2178-2189`). *Closes Quality #1 and #2.*
5. **Reset audio (and subtitle) state on episode transitions.** Extract `resetAudioStateForNewContent()` (audio flags + `setTrackTypeDisabled(AUDIO,false)` + `clearOverridesOfType(AUDIO)`) and `reapplySubtitleState()` (re-enable TEXT per pref + `setPreferredTextLanguage` + reset `subtitleSelfTestRan`); call both at the top of `skipToNextEpisode` (`:2332`) and binge `onPlayNext` (`:478`) before `setMediaItem`. *Closes the binge audio-silence leak + subtitle self-test gap.*
6. **Pre-buffer the next episode.** When the next episode resolves at the 15s binge mark (`:1005`), `addMediaItem(nextMediaItem)`; on advance call `seekToNextMediaItem()` instead of `setMediaItem→prepare→play`. *Closes the binge black-gap; pairs with the "Up Next" card.*
7. **Cache next-episode lookup + distinguish failure from exhaustion.** Cache `getSeriesInfo` per `seriesId` for the session; resolve next-episode ONCE and stash it; on a thrown lookup retry briefly and keep the binge card up instead of firing "No more episodes" / series-complete. *Closes the false-binge-stop.*
8. **Handle network-return from STATE_BUFFERING.** Broaden the guard at `:1383` to include `STATE_BUFFERING`; reset `retryCount`, `seekToDefaultPosition` for LIVE, `prepare()/play()`. *Closes the post-reconnect spin.*
9. **Make resume silent on all surfaces + in-player Restart.** Stop calling `ResumePlaybackHelper.showIfNeeded` on detail/hero/recommendation/Search; thread the position through `newInstance`; add a "Resuming from X — press DOWN to restart" chip + a persistent Restart control in `PlayerControlsBar`. *Closes the modal friction AND the Search/other-surface inconsistency.*
10. **CC toggle reflects an actually-rendering track.** In `toggleClosedCaptions()` (`:2866`), check for a `TRACK_TYPE_TEXT` group; if none → stay Off + "No subtitles available"; if some → after enable, verify a track was selected (auto-select any-language fallback or revert with available languages). Mirror in the picker dismiss path. *Closes the fake "CC On."*

### P2 — noticeable friction
11. **SERIES STATE_ENDED terminal UX + reset `bingeShown` on cancel** (`:505`, `:1188-1192`): resolve next (reuse cache) → auto-advance or `seriesCompleteOverlay`; never a silent black end.
12. **Seek debounce + CLOSEST_SYNC during active scrub** (`OoustreamPlaybackGlue.kt:258-278`, builder sites): accumulate `pendingSeekTargetMs`, commit one seek on idle/key-up; `SeekParameters.CLOSEST_SYNC` scoped to scrubbing only.
13. **Subtitles above the controls bar + raised padding on controls-visible** (`:736`/`:749`/`:2794`/`onVisibilityChanged`).
14. **Collapse to one Coil ImageLoader + real `trimForPlayback`** (`OoustreamApp.kt`, `AdaptiveImageLoader.kt`, `AppModule.kt`, fragment `:393`); add `allowRgb565(true)`.
15. **Add `WAKE_MODE_NETWORK`** to all ExoPlayer.Builder sites (one-liner; `WAKE_LOCK` already granted).
16. **Reconnect label** on the buffering overlay (network-loss branch + after first stall retry).
17. **mt8695 HEVC Main 10 fast-fail + slideshow guard** (`onTracksChanged:1220-1245`; sustained <~10fps → give up).
18. **Empty-category empty-state** — observe `_isLoading` in Vod/SeriesFragment; swap skeletons for a friendly empty-state; reset swapped state per category.
19. **Preview→fullscreen warm handoff** (or last-frame freeze-backdrop) in `LiveTvFragment.goFullscreen` / `LivePreviewManager`.
20. **Mid-stream audio-stall recovery callback** in `PlaybackHealthMonitor` + wire `onAudioSinkError` (attempt-capped), gated on `hasSelectedAudio && volume>0`.
21. **App-level dynamic buffer tightening** via `ComponentCallbacks2` on `TRIM_MEMORY_RUNNING_CRITICAL` while a player is foregrounded.
22. **Raise LIVE `minBuffer` floor 3s→~6s in `forLowMemory`** (`BufferConfigs.kt:57-65`) for the 1GB target.
23. **Stream-host preconnect on focus dwell** (reuse `ScreenPreWarmer` 500ms debounce); preconnect only, no adjacent-channel pre-buffer on 1GB.

### P3 — polish gaps vs Netflix
24. **Hide the phantom Up-Next progress bar** (`ContinueWatchingPresenter.kt:42`/`:46`).
25. **Revalidate resume position against duration after STATE_READY**; restart from 0 if within last ~10s / beyond.
26. **Absolute scrub timecode bubble** at the seekbar thumb (pairs with the pendingSeekTarget).
27. **Poster shimmer dismissal from Coil `onSuccess`** (`PosterPresenter.kt:113-138`, `ProgressiveImageLoader.kt`).
28. **In-place refill on first stall escalation** (skip `stop()`, shorter delay) for VOD; reserve hard stop for repeated failures.
29. **Async/batched diagnostic logging** off the main thread (`StreamDiagnosticLogger.kt:280-291`); flush in crash handler + onPause.
30. **Deep-link Live: real channel name + seed channel list**; delete the dead empty-URL "live" branch.
31. **Grid-tile direct play + remove post-API `requestFocus()`** (`VodDetailFragment.kt:260`).
32. **Thread resume position through `newInstance`** to drop the redundant DB read on the play critical path.
33. **Delete dead `TrackSelectionHelper`** (`TrackSelectionHelper.kt`).

---

### Closing note on what's already right (don't regress it)
The recovery machinery is genuinely strong and should be preserved while implementing the above: BACK aborts in-flight recovery (`OnBackPressedCallback:898-917` + `onDestroyView` job cancellation); seek BUFFERING is correctly insulated from the watchdog (`:1156-1159`); the bad-chipset shortcuts (mt8695/mt8167) are fast (~5-8s); `setMediaItem(item, positionMs)` resume avoids watch-the-logo-then-jump; the downmix matrices (1-8ch) and three-stage audio fallback are complete; release/lifecycle ordering (listener-before-release, MediaSession-before-player, `safeReleasePlayer`, `largeHeap`) is clean; and lip-sync is handled correctly by `DefaultAudioSink`'s media clock (no skew watchdog needed). The path to "flawless" here is overwhelmingly about the *presentation* of the transient moments and a small set of state-reset gaps — not re-architecting the engine.