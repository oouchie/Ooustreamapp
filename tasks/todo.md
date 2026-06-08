# v3.9.1 — the 4 deferred watch-experience items (highest-risk; device-test before release)

Baseline: v3.9.0 (c047dfe) shipped, builds clean. Each item compile-verified individually + graceful fallback.

## A — #9 Silent resume + in-player Restart (product decision: silent)
- [ ] ResumePlaybackHelper.showIfNeeded → resume silently (no modal), still honoring an explicit "start over".
- [ ] Add a "Restart" (play from beginning) action button to the VOD/Series player controls → seekTo(0).
- [ ] Brief "Resuming from H:MM" toast/chip on silent resume so the user knows.

## B — #2 Last-frame hold (PixelCopy)
- [ ] Before stop()/rebuild/zap, PixelCopy the SurfaceView's current frame into the art-backdrop ImageView
      (so recovery shows a frozen frame, not the poster). Graceful fallback to poster art if capture fails.

## C — #4 Mid-title upward HW re-probe
- [ ] In the watchdog "sustained playback confirmed" branch: after sustained-good polls, if capped/SW,
      clear cap + rebuild ONCE with the HW factory at current position; if it re-stalls within a window,
      mark confirmed-bad and never re-probe again (oscillation guard). Skip for mt8695/mt8167.

## D — #6 Next-episode pre-buffer (riskiest — binge flow change)
- [ ] At the 15s binge mark, addMediaItem(next) so ExoPlayer pre-buffers it.
- [ ] Advance via seekToNextMediaItem() when pre-buffered (instant), else setMediaItem fallback.
- [ ] onMediaItemTransition updates metadata (streamId/controlsBar/glue) + resetTrackStateForNewContent +
      markCompleted(prev) for BOTH auto-advance and seek-advance. Series-complete only when no next item.
- [ ] Show art immediately on the transition gap regardless (safe interim).

## Verify
- [x] compileDebugKotlin per item (A+B clean; C+D verifying). Then assembleDebug.

## Review — the 4 deferred items: 2 done in full, 2 done to their SAFE part (riskiest sub-parts held)
- **A (#9) Silent resume + Restart — DONE FULL.** `ResumePlaybackHelper` resumes silently with a
  "Resuming from H:MM" toast (no modal); new `ic_restart_24` + "Restart" action button on VOD/Series
  controls (`onRestart` → seekTo(0)+play). All 3 call sites unchanged (shared helper).
- **B (#2) Last-frame hold — DONE FULL (best-effort).** `captureLastFrame()` PixelCopies the SurfaceView's
  current frame into the loading backdrop on every stop/rebuild/zap/rebuffer, so recovery freezes the frame
  instead of cutting to the poster. API24+; on ANY miss (old API / invalid surface / not-yet-rendered)
  falls back to the poster backdrop, so it can never be worse than v3.9.0. `hasRenderedFirstFrame` gates it.
- **C (#4) Quality — SAFE PART DONE.** Mid-title resolution-cap re-probe: clears `clearVideoSizeConstraints()`
  ONCE after sustained-good playback (`upwardReprobeAttempted`, reset per channel/episode). **Held blind:**
  the SW→HW *decoder*-swap re-probe — HW-decode failures are usually a permanent codec/chip limit, so a
  mid-title HW rebuild (a ~110-line clone of the SW rebuild) would almost always glitch then fall back. Low
  reward, real oscillation risk → device-verified follow-up.
- **D (#6) Binge — SAFE PART DONE.** Both episode transitions now hold the last frame over the load gap
  (`showBufferingOverlay(immediate)` → frozen frame, no black cut) + the v3.9.0 series-info cache already
  removed the boundary network round-trip. **Held blind:** the TRUE seamless ExoPlayer playlist pre-buffer
  (`addMediaItem`/auto-advance/`onMediaItemTransition`) — a significant rewrite of the working binge flow
  that conflicts with the single-item rebuild machinery → device-verified follow-up.

NOT version-bumped / released — per the plan, sideload v3.9.0+these on a stick first, THEN cut v3.9.1.
