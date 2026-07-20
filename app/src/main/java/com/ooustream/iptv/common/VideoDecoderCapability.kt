package com.ooustream.iptv.common

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Asks the device's actual MediaCodec decoders whether they can handle a given video size, instead of
 * guessing from chipset allow/deny lists.
 *
 * Why this exists: a 4K stream on a stick with no 4K decoder used to burn ~2.5 minutes in the frame
 * watchdog before failing. The obvious guard — [DeviceTierDetector.maxVideoSize] + `setMaxVideoSize`
 * — does NOT block it: Media3's `exceedVideoConstraintsIfNecessary` defaults to `true`
 * (DefaultTrackSelector.java:1790), so when a single-bitrate IPTV stream offers only a 2160p track,
 * the selector deliberately exceeds the cap and selects it anyway. IPTV has no smaller rung to fall
 * back to, so the only honest options are "play it" or "say we can't".
 *
 * Nor does `setExceedRendererCapabilitiesIfNecessary(false)` catch it. Media3 1.10.0 auto-registers
 * the FFmpeg software video renderer, which happily reports it *supports* 4K HEVC and then decodes it
 * at ~0fps. So the question that matters is specifically **"can a MediaCodec decoder do this?"** —
 * and [MediaCodecUtil.getDecoderInfos] lists only MediaCodec decoders, never the FFmpeg renderer.
 *
 * Fails OPEN by design. A wrong "this device can't play that" refusal makes working content
 * unplayable, which is worse than the slow failure it replaces — so anything inconclusive returns
 * null and playback proceeds exactly as it does today.
 */
@UnstableApi
object VideoDecoderCapability {

    private val cache = HashMap<String, Boolean?>()

    /**
     * @return `true` if at least one MediaCodec decoder reports support, `false` if decoders were
     *   found with real capabilities and none of them support it, and `null` when it cannot be
     *   determined (no decoders listed, no capability data, or the query threw) — callers MUST treat
     *   `null` as "go ahead and try".
     */
    @Synchronized
    fun canDecode(mimeType: String, width: Int, height: Int, frameRate: Float): Boolean? {
        if (mimeType.isBlank() || width <= 0 || height <= 0) return null

        val fps = frameRate.takeIf { it > 0f }?.toDouble() ?: 0.0
        // Key on the exact fps handed to probe() — truncating here would let a 59.94 result be
        // returned for a later 59.0 query, which is a different question for the decoder.
        val key = "$mimeType|${width}x$height@$fps"
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null

        val result = probe(mimeType, width, height, fps)
        cache[key] = result
        return result
    }

    private fun probe(mimeType: String, width: Int, height: Int, fps: Double): Boolean? = try {
        val infos = MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false)
        // No decoders listed at all, or none exposing capability data, means "we don't know" — not
        // "unsupported". isVideoSizeAndRateSupportedV21 also returns false when capabilities are
        // null, so without this check an information gap would read as a definitive refusal.
        val informative = infos.filter { it.capabilities?.videoCapabilities != null }
        when {
            informative.isEmpty() -> null
            informative.any { it.isVideoSizeAndRateSupportedV21(width, height, fps) } -> true
            else -> false
        }
    } catch (_: Throwable) {
        null
    }
}
