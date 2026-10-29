package com.ooustream.iptv.player

import androidx.media3.exoplayer.DefaultLoadControl
import com.ooustream.iptv.common.QualityTier
import com.ooustream.iptv.data.model.ContentType

object BufferConfigs {
    // Live TV is tuned for fastest first frame. setPrioritizeTimeOverSizeThresholds(true)
    // lets ExoPlayer start playing once minPlaybackMs (ms) is filled instead of also
    // waiting for a byte threshold. minPlaybackMs=500 means first frame as soon as half
    // a second of data is buffered — ~400-800ms faster than the Media3 default.

    fun forContentType(type: ContentType): DefaultLoadControl = when (type) {
        ContentType.LIVE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // prioritizeTimeOverSizeThresholds(true): buffer purely by TIME (up to maxBufferMs), never
        // stopping early on a byte threshold. Matches the LIVE path and guarantees a high-bitrate 4K
        // stream builds a real time cushion instead of a few MB.
        ContentType.VOD -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 2_000, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ContentType.SERIES -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    fun forContentTypeAndQuality(type: ContentType, tier: QualityTier): DefaultLoadControl {
        return when (tier) {
            QualityTier.LOW -> {
                // Conservative buffers for weak connections
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (type == ContentType.LIVE) 3_000 else 15_000,
                        if (type == ContentType.LIVE) 8_000 else 45_000,
                        if (type == ContentType.LIVE) 500 else 1_000,
                        if (type == ContentType.LIVE) 1_000 else 2_000
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
            QualityTier.MEDIUM -> forContentType(type)
            QualityTier.HIGH -> {
                // Generous but capped — 60s max keeps memory under control on mid-range devices
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (type == ContentType.LIVE) 8_000 else 25_000,
                        if (type == ContentType.LIVE) 25_000 else 60_000,
                        if (type == ContentType.LIVE) 500 else 2_000,
                        if (type == ContentType.LIVE) 2_000 else 5_000
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            }
        }
    }

    /** Capped buffers for low-RAM devices (<=128MB heap) to prevent OOM in video decoder. */
    fun forLowMemory(type: ContentType): DefaultLoadControl = when (type) {
        // LIVE minBuffer raised 3s→6s (watch-audit): a 3s floor on the 1GB stick re-stalled on
        // every throughput wobble. 6s/8s of TS at typical IPTV bitrates is still only a few MB.
        ContentType.LIVE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(6_000, 8_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Byte cap, not just a time window. ExoPlayer's buffer lives on the Java heap, and 30s of
        // a 4K remux is ~100MB on top of the decoder's ~19 4K output buffers — on the 1GB Ooustick
        // that ran the box out of memory and the low-memory killer took the app ~9s into
        // Marshals 4K. IPTV Smarters plays the same file on the same decoder with a 20MB demux
        // cap (its default `max-buffer-size`). With time no longer prioritized, the cap only
        // bites high bitrates: 30s of a ~5Mbps HD title is under 20MB anyway.
        ContentType.VOD, ContentType.SERIES -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 2_000)
            .setTargetBufferBytes(LOW_MEMORY_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    private const val LOW_MEMORY_TARGET_BUFFER_BYTES = 20 * 1024 * 1024
}
