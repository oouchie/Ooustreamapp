package com.ooustream.iptv.player

import androidx.media3.exoplayer.DefaultLoadControl
import com.ooustream.iptv.common.QualityTier
import com.ooustream.iptv.data.model.ContentType

/**
 * Every ExoPlayer load control in the app comes from here.
 *
 * LIVE buffers by TIME only (`prioritizeTimeOverSizeThresholds(true)`): small windows, fastest
 * first frame, a few MB at most.
 *
 * VOD / SERIES buffer by time AND bytes since v5.2.6. ExoPlayer's buffer lives on the Java heap,
 * and with time prioritised Media3 ignores the byte target until `minBufferMs` is reached and then
 * fills to `maxBufferMs` or its DEFAULT target (~141MB: 2000 video + 200 audio 64KB segments).
 * kiarawil (AFTKRT, 384MB heap, 2026-11-07): a 1080p AVC + DTS-HD remux took the heap from 44MB to
 * 383MB/384MB in five minutes — ~140MB of buffer on top of the catalogue — and the collector ran
 * 121 cycles of 1–4s: dropped frames, audio dropouts, seven 7–104ms "rebuffers", then an ANR.
 * [heapTargetBufferBytes] ties the cap to the heap the device actually has; with time no longer
 * prioritised the cap only bites high bitrates (60s of a 6 Mbps title is ~45MB on a 76MB cap).
 * Same mechanism the v5.2.2 low-memory profile uses with its fixed 20MB (device-verified on .129).
 */
object BufferConfigs {

    fun forContentType(type: ContentType): DefaultLoadControl = when (type) {
        // minPlaybackMs=500: first frame as soon as half a second is buffered, ~400-800ms faster
        // than the Media3 default.
        ContentType.LIVE -> live(5_000, 15_000, 500, 1_500)
        ContentType.VOD -> onDemand(15_000, 45_000, 2_000, 5_000)
        ContentType.SERIES -> onDemand(15_000, 45_000, 1_500, 3_000)
    }

    fun forContentTypeAndQuality(type: ContentType, tier: QualityTier): DefaultLoadControl = when (tier) {
        // Conservative buffers for weak connections
        QualityTier.LOW ->
            if (type == ContentType.LIVE) live(3_000, 8_000, 500, 1_000)
            else onDemand(15_000, 45_000, 1_000, 2_000)
        QualityTier.MEDIUM -> forContentType(type)
        // Generous but capped — 60s max keeps memory under control on mid-range devices
        QualityTier.HIGH ->
            if (type == ContentType.LIVE) live(8_000, 25_000, 500, 2_000)
            else onDemand(25_000, 60_000, 2_000, 5_000)
    }

    /** Capped buffers for low-RAM devices (1GB Ooustick, 1.28GB AFTMM; memoryClass<=192 && totalMem<1.4GB). */
    fun forLowMemory(type: ContentType): DefaultLoadControl = when (type) {
        // LIVE minBuffer raised 3s→6s (watch-audit): a 3s floor on the 1GB stick re-stalled on
        // every throughput wobble. 6s/8s of TS at typical IPTV bitrates is still only a few MB.
        ContentType.LIVE -> live(6_000, 8_000, 500, 1_000)
        // 30s of a 4K remux is ~100MB on top of the decoder's ~19 4K output buffers — on the 1GB
        // Ooustick that ran the box out of memory and the low-memory killer took the app ~9s into
        // Marshals 4K. IPTV Smarters plays the same file on the same decoder with a 20MB demux cap
        // (its default `max-buffer-size`).
        ContentType.VOD, ContentType.SERIES ->
            onDemand(10_000, 30_000, 1_500, 2_000, targetBytes = LOW_MEMORY_TARGET_BUFFER_BYTES)
    }

    /**
     * The VOD/SERIES byte cap for a device whose Java heap limit is [maxHeapBytes]
     * (`Runtime.maxMemory()`, i.e. `dalvik.vm.heapsize` with `largeHeap`): [HEAP_FRACTION] of it,
     * never below the low-memory 20MB and never above Media3's own 128MB video default.
     * 384MB (AFTKRT) → 76MB, 256MB (Ooustick) → 51MB, 192MB → 38MB.
     */
    fun heapTargetBufferBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Int =
        (maxHeapBytes * HEAP_FRACTION).toLong()
            .coerceIn(LOW_MEMORY_TARGET_BUFFER_BYTES.toLong(), MAX_TARGET_BUFFER_BYTES.toLong())
            .toInt()

    private fun live(minMs: Int, maxMs: Int, playMs: Int, rebufferMs: Int): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun onDemand(
        minMs: Int,
        maxMs: Int,
        playMs: Int,
        rebufferMs: Int,
        targetBytes: Int = heapTargetBufferBytes()
    ): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs, playMs, rebufferMs)
            .setTargetBufferBytes(targetBytes)
            // Media3 1.10.0 DefaultLoadControl: with this false, loading stops at the byte target
            // even below minBufferMs, and shouldStartPlayback() also returns true once the target
            // is reached, so a cap smaller than bufferForPlaybackMs can never deadlock the start.
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

    const val LOW_MEMORY_TARGET_BUFFER_BYTES = 20 * 1024 * 1024
    private const val MAX_TARGET_BUFFER_BYTES = 128 * 1024 * 1024
    private const val HEAP_FRACTION = 0.20
}
