package com.ooustream.iptv.player

import androidx.media3.exoplayer.DefaultLoadControl
import com.ooustream.iptv.common.QualityTier
import com.ooustream.iptv.data.model.ContentType

object BufferConfigs {
    fun forContentType(type: ContentType): DefaultLoadControl = when (type) {
        ContentType.LIVE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 1_000, 1_500)
            .build()
        ContentType.VOD -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 2_000, 5_000)
            .build()
        ContentType.SERIES -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
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
                        1_000,
                        if (type == ContentType.LIVE) 1_000 else 2_000
                    )
                    .build()
            }
            QualityTier.MEDIUM -> forContentType(type)
            QualityTier.HIGH -> {
                // Generous but capped — 60s max keeps memory under control on mid-range devices
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        if (type == ContentType.LIVE) 8_000 else 25_000,
                        if (type == ContentType.LIVE) 25_000 else 60_000,
                        2_000,
                        if (type == ContentType.LIVE) 2_000 else 5_000
                    )
                    .build()
            }
        }
    }

    /** Capped buffers for low-RAM devices (<=128MB heap) to prevent OOM in video decoder. */
    fun forLowMemory(type: ContentType): DefaultLoadControl = when (type) {
        ContentType.LIVE -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 8_000, 1_000, 1_000)
            .build()
        ContentType.VOD, ContentType.SERIES -> DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 2_000)
            .build()
    }
}
