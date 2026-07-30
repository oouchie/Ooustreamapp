package com.ooustream.iptv.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * Neutralizes Media3's "stuck playing without ending" detector on players that carry
 * IPTV **live** streams.
 *
 * ## The bug this fixes
 *
 * Media3 1.10.0 ships `StuckPlayerDetector`. Its `StuckPlayingNotEndingDetector` arms a timer
 * whenever `isPlaying && duration != C.TIME_UNSET && position >= duration`, and when that holds
 * continuously for `DEFAULT_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS` (60_000) it throws
 * `StuckPlayerException: Player stuck playing without ending for 60000 ms`.
 *
 * That is a sound check for real VOD. It is a false positive for our live channels: the provider
 * serves them as progressive media, so the extractor derives a **finite, bogus duration** (~60-70s
 * on the streams measured). Playback sails past it, 60s later the detector fires, and the error
 * lands in `onPlayerError` → generic retry ladder → `showBufferingOverlay(true)` → re-`prepare()`.
 * `STATE_READY` resets `retryCount`, so it always takes the 1s rung and always "recovers" — which
 * is precisely why it repeats forever instead of surfacing as a hard failure.
 *
 * Measured on two AFTKRT sticks running v4.2.9, both playing live FOX 5: a metronomic **131.8s**
 * period (.82: 23:47:00.1 → 23:49:11.9 → 23:51:23.6), each event tearing down both codecs and
 * taking ~1.5s to first frame. Users reported it as "keeps buffering". The network was ruled out —
 * the stream arrived as a regular ~4MB burst every 5s (~5.5Mbps) with no gaps, and VOD in the same
 * session held a healthy 28→64s buffer refilling at ~60Mbps.
 *
 * ## Why disabling is the right call
 *
 * The detector is diagnostic-only: its sole action is to throw, and here that throw *destroys
 * working playback*. We already own stall detection with far better signals — the frame watchdog
 * covers STATE_READY (decoder faults) and `startStallDetector()` / `SOURCE_STALL` covers
 * STATE_BUFFERING (supply faults, gated on `bufferedPosition` being genuinely static since v4.2.9).
 * Only this one detector is disabled; stuck-buffering, stuck-playing-no-progress and
 * stuck-suppressed keep their defaults.
 *
 * Applied to *all* content rather than gated to `ContentType.LIVE` on purpose: a VOD whose
 * container advertises a wrong short duration would fall into the identical loop, and we have no
 * way to trust a provider-supplied duration.
 *
 * `setStuckPlayingNotEndingTimeoutMs` asserts `value > 0`, so there is no disable sentinel;
 * `Int.MAX_VALUE` ms is ~24.8 days, i.e. never inside a session.
 *
 * Call this at **every** `ExoPlayer.Builder` that plays a stream URL — including the
 * `rebuildPlayerWith*` clones. Hand-copied rebuild paths drifting from the initial build path is
 * this file's whole reason for existing (it has bitten us at least four times: v3.6.3 cue
 * listener, v4.2.5 Dolby Vision wrap, v4.2.9 load control + stats overlay).
 */
fun ExoPlayer.Builder.withoutBogusLiveDurationStuckDetection(): ExoPlayer.Builder =
    setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE)
