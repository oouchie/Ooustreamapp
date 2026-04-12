package com.ooustream.iptv.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Coarse device capability tier.
 *
 * Used by the playback pipeline to decide resolution caps, codec filtering,
 * buffer sizes, and whether to attempt HEVC Main 10 (10-bit HDR) content at
 * all. The tier is a function of (heap size, total RAM, known chipset bugs).
 *
 *   HIGH      — 256MB+ heap, capable modern SoC. Everything attempted.
 *   MID       — 192MB-256MB heap, mid-range chipset.
 *   LOW       — 128MB-192MB heap, budget Fire TV Stick (AFTMM, AFTKRT).
 *   ULTRA_LOW — <=128MB heap OR known-bad MTK chipset. mt8695-class AFTSS.
 *               HEVC Main 10 is refused at the track selector; streams are
 *               capped to 1080p max; the watchdog's MTK shortcut engages.
 */
enum class DeviceTier {
    HIGH,
    MID,
    LOW,
    ULTRA_LOW,
}

/**
 * Unified device capability detector. Replaces the scattered inline
 * `am.memoryClass <= 128` / `memoryClass <= 192` checks across the codebase.
 *
 * This is the single source of truth for "can this device play X content" —
 * anything player-adjacent (track selector caps, buffer sizing, decoder
 * factory selection, HEVC Main 10 gating, stream capability UX) should call
 * into here rather than reading `ActivityManager` or `Build.HARDWARE` directly.
 *
 * Results are cached on first call — device capabilities don't change at
 * runtime so we avoid the cost of querying ActivityManager on every access.
 *
 * Introduced in v3.5.9 as part of the "device-aware playback routing" work.
 * See CLAUDE.md phase 15 for context.
 */
object DeviceTierDetector {

    @Volatile
    private var cachedTier: DeviceTier? = null

    /**
     * Known-bad MediaTek chipsets — confirmed hardware decoder stalls on
     * plain 1080p AVC, HEVC Main 10 crashes when libVLC tries to decode,
     * and software HEVC at any resolution is unwatchable on the Cortex-A53
     * cores these ship with.
     *
     * These are hardcoded to ULTRA_LOW regardless of `memoryClass` because
     * the decoder bug is the constraint, not RAM. Adding a new entry to
     * this list automatically routes the device through the ULTRA_LOW
     * playback path (resolution cap, HEVC Main 10 refusal, MTK shortcut).
     */
    private val BAD_MTK_HARDWARE = listOf(
        "mt8695",   // Fire TV Stick 3rd gen AFTSS — the one andresi has
        "mt8696",   // Fire TV Stick AFTMM variants
        "mt8167",   // Older Fire TV family, same HEVC Main 10 gap
    )

    /**
     * Return the device's capability tier. Cached on first call.
     *
     * Decision order:
     *   1. If `Build.HARDWARE` contains a known-bad MTK chipset → ULTRA_LOW
     *      (regardless of heap — the bug is decoder, not RAM)
     *   2. Otherwise use `ActivityManager.memoryClass` as the RAM proxy.
     */
    fun tier(context: Context): DeviceTier {
        cachedTier?.let { return it }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = am.memoryClass
        val hardware = Build.HARDWARE.lowercase()

        val isBadMtk = BAD_MTK_HARDWARE.any { hardware.contains(it) }

        val tier = when {
            isBadMtk -> DeviceTier.ULTRA_LOW
            memoryClass <= 128 -> DeviceTier.ULTRA_LOW
            memoryClass <= 192 -> DeviceTier.LOW
            memoryClass <= 256 -> DeviceTier.MID
            else -> DeviceTier.HIGH
        }

        cachedTier = tier
        return tier
    }

    /**
     * Can this device decode HEVC Main Profile 10 (10-bit HDR) at any
     * realistic resolution?
     *
     * Returns `false` for ULTRA_LOW and LOW tiers:
     *   - ULTRA_LOW (mt8695-class): hardware decoder has no Main 10 profile
     *     support. Software decode via c2.android is ~8 fps slideshow.
     *     libVLC's HEVC decoder native-crashes on mt8695 (the original v3.5.7
     *     motivation).
     *   - LOW (AFTMM/AFTKRT, 160-192MB heap): hardware may support Main 10
     *     in theory, but software fallback at 1080p exceeds the heap budget
     *     and software decode at 720p is still marginal on Cortex-A53.
     *
     * Returns `true` for MID and HIGH tiers — hardware decoder path is
     * expected to work, libVLC fallback is available if it doesn't.
     *
     * The playback fragment checks this at `onTracksChanged` time. If the
     * stream is HEVC Main 10 (codec string `hvc1.2*` or `hev1.2*`) AND this
     * returns false, the user sees a friendly "your device can't play this"
     * dialog instead of an unwatchable slideshow or a native crash.
     */
    fun canDecodeHevcMain10(context: Context): Boolean {
        return when (tier(context)) {
            DeviceTier.HIGH, DeviceTier.MID -> true
            DeviceTier.LOW, DeviceTier.ULTRA_LOW -> false
        }
    }

    /**
     * Max video (width, height) to allow in the track selector, or `null`
     * if no cap is needed. Applied at player init via
     * `DefaultTrackSelector.Parameters.setMaxVideoSize`.
     *
     * Intentionally lenient:
     *   - ULTRA_LOW / LOW: cap at 1080p (blocks 4K from being selected, which
     *     is rare on IPTV anyway, but a safety net for any 4K variant the
     *     server might serve). 1080p AVC hardware decode often works on
     *     these devices even when HEVC Main 10 doesn't.
     *   - MID / HIGH: no cap (null).
     *
     * This is a resolution filter, not a codec filter. Codec-level filtering
     * (e.g. HEVC Main 10) is handled separately via `canDecodeHevcMain10()`
     * and enforced reactively in `onTracksChanged`.
     */
    fun maxVideoSize(context: Context): Pair<Int, Int>? {
        return when (tier(context)) {
            DeviceTier.ULTRA_LOW, DeviceTier.LOW -> 1920 to 1080
            DeviceTier.MID, DeviceTier.HIGH -> null
        }
    }

    /**
     * One-line human-readable description for diagnostic logs.
     * Example: `tier=ULTRA_LOW, hw=mt8695, model=AFTSS, memoryClass=160MB, badMtk=true`
     */
    fun describe(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val hardware = Build.HARDWARE.lowercase()
        val badMtk = BAD_MTK_HARDWARE.any { hardware.contains(it) }
        return "tier=${tier(context)}, hw=${Build.HARDWARE}, model=${Build.MODEL}, " +
            "memoryClass=${am.memoryClass}MB, badMtk=$badMtk"
    }

    /**
     * Reset the cached tier. For tests only — the tier does not change at
     * runtime in production.
     */
    internal fun resetCacheForTests() {
        cachedTier = null
    }
}
