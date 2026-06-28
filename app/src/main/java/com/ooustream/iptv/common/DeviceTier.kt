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
        "mt8695",   // Fire TV Stick 3rd gen AFTSS (2020, no HW 4K HEVC)
        // v3.7.0: mt8696 removed. mt8696 is Fire TV Stick 4K Max 2nd gen
        // (AFTKRT, 2023) — it has full hardware 4K HEVC and was incorrectly
        // grouped with mt8695 based on a comment that claimed "AFTMM variants".
        // AFTKRT now re-tiers normally via memoryClass (falls into MID/HIGH)
        // which removes the 1080p resolution cap and the ULTRA_LOW routing.
        "mt8167",   // Older Fire TV family, same HEVC Main 10 gap
    )

    /**
     * Known-good MTK SoCs that must be elevated past `memoryClass`-based
     * ULTRA_LOW/LOW tiering. These have full hardware 4K HEVC and their
     * per-app heap (often 192MB) doesn't reflect the actual capability.
     *
     * Without this override, `memoryClass <= 192` pins them to LOW tier and
     * forces a 1080p video cap, which makes the track selector reject 4K
     * HEVC tracks and fall back to c2.android.hevc software decode — the
     * 4K-glitching path on AFTKRT that led to v3.7.0's tier rework.
     */
    private val GOOD_MTK_HARDWARE = listOf(
        "mt8696",   // Fire TV Stick 4K Max 2nd gen (AFTKRT, 2023) — HW 4K HEVC
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
        val isGoodMtk = GOOD_MTK_HARDWARE.any { hardware.contains(it) }

        val tier = when {
            isBadMtk -> DeviceTier.ULTRA_LOW
            // Known-good 4K-capable MTK SoCs bypass the memoryClass ladder — their
            // per-app heap underreports the real hardware capability (see comment
            // on GOOD_MTK_HARDWARE). Land on MID rather than HIGH so we keep
            // conservative buffer sizing, but remove the resolution cap.
            isGoodMtk -> DeviceTier.MID
            memoryClass <= 128 -> DeviceTier.ULTRA_LOW
            memoryClass <= 192 -> DeviceTier.LOW
            memoryClass <= 256 -> DeviceTier.MID
            else -> DeviceTier.HIGH
        }

        cachedTier = tier
        return tier
    }

    /**
     * Can this device play HEVC Main Profile 10 (10-bit HDR) content via
     * any available decode path (hardware, libVLC, or software)?
     *
     * v3.5.9 used the device TIER which was too aggressive: the AFTKRT
     * (mt8696, 1669MB RAM, tier=ULTRA_LOW because mt8696 is in
     * BAD_MTK_HARDWARE) was blocked from HEVC Main 10 even though it has
     * plenty of RAM for libVLC to software-decode it. v3.5.10 uses total
     * device RAM as the gate instead — this correctly differentiates:
     *
     *   AFTSS  (mt8695,  900MB) → false — libVLC crashes, SW is slideshow
     *   AFTKRT (mt8696, 1669MB) → true  — libVLC handles it fine
     *   AFTMM  (mt8695, 1285MB) → true  — enough RAM for libVLC
     *
     * The existing early VLC swap in onTracksChanged (HEVC Main 10 + MTK →
     * libVLC) kicks in for devices that return true here. Devices that
     * return false see the friendly "device can't play HDR" error.
     *
     * Threshold: 1.2 GB total RAM. Below this, libVLC's HEVC decode either
     * SIGSEGV-crashes (mt8695 at 900MB) or OOMs within minutes.
     */
    fun canDecodeHevcMain10(context: Context): Boolean {
        val t = tier(context)
        if (t == DeviceTier.HIGH || t == DeviceTier.MID) return true

        // LOW / ULTRA_LOW: check total RAM. Devices with >= 1.2GB have
        // enough headroom for libVLC to software-decode HEVC Main 10 at
        // 1080p without crashing or OOMing. The libVLC fallback path in
        // onTracksChanged handles the actual swap.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem >= 1_200_000_000L  // 1.2 GB
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
        val goodMtk = GOOD_MTK_HARDWARE.any { hardware.contains(it) }
        // Allwinner sunxi boxes (sun50iw9p1 = H616, etc.) — generic 4K Android TV boxes whose
        // HW HEVC decoder hides its 10-bit profile, so 4K HEVC Main 10 routes to FFmpeg SW.
        val allwinner = hardware.startsWith("sun") || hardware.contains("allwinner")
        return "tier=${tier(context)}, hw=${Build.HARDWARE}, model=${Build.MODEL}, " +
            "memoryClass=${am.memoryClass}MB, badMtk=$badMtk, goodMtk=$goodMtk, allwinner=$allwinner"
    }

    /**
     * Reset the cached tier. For tests only — the tier does not change at
     * runtime in production.
     */
    internal fun resetCacheForTests() {
        cachedTier = null
    }
}
