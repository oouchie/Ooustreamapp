package com.ooustream.iptv.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Rolling measurement of how fast stream bytes are actually ARRIVING.
 *
 * Why this exists instead of `DefaultBandwidthMeter.getBitrateEstimate()`: that estimate is only
 * recomputed in `onTransferEnd`, and a progressive VOD load keeps ONE HTTP transfer open for the
 * whole title. So for exactly the content where buffering complaints come from, the built-in meter
 * reports a stale figure from the initial connection and never moves — it cannot see a source that
 * has gone quiet mid-title. Counting `onBytesTransferred` can.
 *
 * Reading this together with the buffer depth is what makes a stall diagnosable:
 *   deep buffer + 0 Mbps  -> normal. LoadControl is satisfied and has stopped reading on purpose.
 *   empty buffer + 0 Mbps -> the source is not delivering. This is the failure case.
 * A zero on its own means nothing, which is why the stats overlay never shows it without the buffer.
 *
 * Thread-safety: `onBytesTransferred` is called from ExoPlayer's loader thread while the UI reads
 * from the main thread, so all shared state is guarded by [lock]. Cheap — one long add per chunk.
 */
@UnstableApi
object StreamThroughputMeter : TransferListener {

    // Declared before the arrays below: an object's property initializers run top-to-bottom, so a
    // const referenced by one must appear first.
    private const val SLOT_MS = 500L
    private const val WINDOW_SLOTS = 10 // 5s trailing window

    private val lock = Any()

    /** Byte counts stamped with the elapsed-time slot they landed in. */
    private val slotBytes = LongArray(WINDOW_SLOTS)
    private val slotStamp = LongArray(WINDOW_SLOTS)

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        if (!isNetwork) return
        val now = SystemClock.elapsedRealtime()
        val slot = ((now / SLOT_MS) % WINDOW_SLOTS).toInt()
        val stamp = now / SLOT_MS
        synchronized(lock) {
            if (slotStamp[slot] != stamp) {
                slotStamp[slot] = stamp
                slotBytes[slot] = 0L
            }
            slotBytes[slot] += bytesTransferred.toLong()
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    /**
     * Bits per second measured over the trailing [WINDOW_SLOTS] x [SLOT_MS] window, or 0 when
     * nothing has arrived in that window (which is legitimate when the buffer is full).
     */
    fun bitsPerSecond(): Long {
        val now = SystemClock.elapsedRealtime()
        val currentStamp = now / SLOT_MS
        val oldestStamp = currentStamp - (WINDOW_SLOTS - 1)
        var total = 0L
        synchronized(lock) {
            for (i in 0 until WINDOW_SLOTS) {
                // Ignore slots older than the window — they're stale data from a previous burst.
                if (slotStamp[i] in oldestStamp..currentStamp) total += slotBytes[i]
            }
        }
        return total * 8L * 1000L / (WINDOW_SLOTS * SLOT_MS)
    }

    /** Drop all history. Call when a new title starts so its readout isn't seeded by the last one. */
    fun reset() {
        synchronized(lock) {
            slotBytes.fill(0L)
            slotStamp.fill(0L)
        }
    }
}
