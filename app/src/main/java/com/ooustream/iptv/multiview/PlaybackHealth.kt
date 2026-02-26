package com.ooustream.iptv.multiview

/**
 * 5-level health state for MultiView slot playback quality.
 * Used by the chop detector to drive auto-recovery decisions.
 */
enum class PlaybackHealth {
    SMOOTH,           // No drops, buffer healthy
    SLIGHT_STUTTER,   // Elevated drops (<15/sec), buffer >2s — soft reset candidate
    CHOPPING,         // Active frame drops (>15/sec) OR buffer <2s — hard reset needed
    FROZEN,           // No new frames for 1+ second while STATE_READY
    DEAD              // All recovery attempts exhausted — signal lost
}

/**
 * Recovery actions for the 3-level recovery ladder.
 * Escalates from cheapest (soft) to most expensive (nuclear).
 */
enum class RecoveryAction {
    SOFT_RESET,       // seekToDefaultPosition() — decoder resync, ~100ms, invisible
    HARD_RESET,       // stop → clearMediaItems → setMediaSource → prepare → play
    NUCLEAR_RESET,    // release player + quit thread → rebuild from scratch
    MARK_SIGNAL_LOST  // all attempts exhausted — show signal lost overlay
}
