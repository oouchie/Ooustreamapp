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
    // NO-OP for live progressive streams. A seek on an Xtream .ts is NOT a cheap decoder
    // resync — it reconnects the stream and keeps the old timestamp baseline, which freezes
    // the slot. This rung applies only to non-live media; live escalates straight to
    // HARD_RESET. The old comment here ("~100ms, invisible") was the false premise that let
    // the freeze survive three audits.
    SOFT_RESET,
    HARD_RESET,       // stop → clearMediaItems → setMediaItem → prepare → play (re-seeds extractor)
    NUCLEAR_RESET,    // release player + quit thread → rebuild from scratch
    MARK_SIGNAL_LOST  // all attempts exhausted — show signal lost overlay
}
