package com.ooustream.iptv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Converts an M2TS (BDAV, 192-byte packet) stream into standard 188-byte MPEG-TS on the fly by
 * stripping the 4-byte timestamp prefix from each packet, so ExoPlayer's TsExtractor can demux it.
 *
 * Why this exists: some IPTV panels serve VOD with a `.m2ts` container (Blu-ray rips). ExoPlayer's
 * TsExtractor only reads 188-byte packets and rejects 192-byte M2TS with
 * UnrecognizedInputFormatException — while FFmpeg-based players (IPTV Smarters / IJKPlayer) demux
 * them natively. We have no software TS demuxer (libVLC was removed in v3.7.0), so this wrapper
 * closes the gap at the byte layer. (Customer report: "Kung Fu Panda (2008)", stream 1375882 —
 * real container m2ts, 0x47 sync at byte 4 of every 192.)
 *
 * Self-gating: sniffs the first packet on the first read. If the bytes are already standard
 * 188-byte TS (sync at offset 0), or anything that isn't M2TS (mp4, mkv, …), it passes through
 * untouched. That makes the wrapper safe to place in front of any stream.
 */
@UnstableApi
class M2tsExtractingDataSource(private val upstream: DataSource) : DataSource {

    private var stripping = false
    private var modeResolved = false

    // One source packet of staging (192 bytes in M2TS mode). Holds the sniffed first packet too.
    private val pkt = ByteArray(M2TS_PACKET)
    private var pktLen = 0   // valid bytes currently in pkt
    private var pktPos = 0   // read cursor within pkt (the payload region in stripping mode)
    private var endOfInput = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        pktLen = 0
        pktPos = 0
        endOfInput = false

        if (!modeResolved) {
            // First open is always position 0 — open upstream verbatim and decide mode on first read.
            // Always report UNKNOWN length here: declaring the upstream's length makes ExoPlayer's
            // progressive loader re-open to "reach" it, and this provider's single-use-token
            // `/live/play/` redirect rejects that second connection with 416. Unknown length →
            // a single continuous read on one connection (how IJKPlayer/FFmpeg plays it).
            upstream.open(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        // Seek reopen: ExoPlayer asks in stripped-TS coordinates. Translate to M2TS bytes
        // (188-byte packet index → 192-byte packet start). TsExtractor resyncs to the next 0x47,
        // so landing on a packet boundary is sufficient for CBR seeks.
        val spec = if (stripping) {
            val m2tsPos = dataSpec.position / TS_PACKET * M2TS_PACKET
            dataSpec.buildUpon().setPosition(m2tsPos).build()
        } else {
            dataSpec
        }
        return mapLength(upstream.open(spec))
    }

    private fun mapLength(upstreamLength: Long): Long {
        // Always report unknown length. These panels deliver VOD .m2ts via a `/live/play/`
        // redirect that returns a bogus multi-GB Content-Length on a single-use token and 416s
        // any follow-up ranged request — so a known length makes ExoPlayer's progressive loader
        // re-open and hit a dead token. Unknown length → one continuous sequential read (how
        // IJKPlayer/FFmpeg plays it). Costs precise seeking on these streams; playback is the win.
        if (stripping) return C.LENGTH_UNSET.toLong()
        return upstreamLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (!modeResolved) {
            // Fill (up to) one packet, sniff, then fall through to serve from it.
            fillPacket()
            stripping = sniff()
            modeResolved = true
            if (pktLen == 0) return C.RESULT_END_OF_INPUT
        }

        if (!stripping) {
            // Passthrough. Drain anything left in the sniff buffer first, then delegate directly.
            val buffered = pktLen - pktPos
            if (buffered > 0) {
                val n = minOf(buffered, length)
                System.arraycopy(pkt, pktPos, buffer, offset, n)
                pktPos += n
                return n
            }
            return upstream.read(buffer, offset, length)
        }

        // Stripping mode — serve from the 188-byte payload of the current packet.
        if (pktPos >= pktLen) {
            if (endOfInput) return C.RESULT_END_OF_INPUT
            fillPacket()
            if (pktLen <= PREFIX) return C.RESULT_END_OF_INPUT
        }
        val available = pktLen - pktPos
        val n = minOf(available, length)
        System.arraycopy(pkt, pktPos, buffer, offset, n)
        pktPos += n
        return n
    }

    /** Read a full source packet (or whatever remains at EOF) into [pkt]; set payload cursor. */
    private fun fillPacket() {
        pktLen = 0
        pktPos = 0
        while (pktLen < M2TS_PACKET) {
            val n = upstream.read(pkt, pktLen, M2TS_PACKET - pktLen)
            if (n == C.RESULT_END_OF_INPUT) {
                endOfInput = true
                break
            }
            pktLen += n
        }
        // In stripping mode the 4-byte timestamp prefix is dropped (payload starts at offset 4).
        if (stripping) pktPos = if (pktLen > PREFIX) PREFIX else pktLen
    }

    /** M2TS iff the sync byte (0x47) sits at offset 4 but NOT at offset 0 (which would be plain TS). */
    private fun sniff(): Boolean {
        if (pktLen < PREFIX + 1) return false
        val syncAt0 = pkt[0] == TS_SYNC
        val syncAt4 = pkt[PREFIX] == TS_SYNC
        return syncAt4 && !syncAt0
    }

    override fun close() {
        pktLen = 0
        pktPos = 0
        upstream.close()
    }

    @UnstableApi
    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            M2tsExtractingDataSource(upstreamFactory.createDataSource())
    }

    companion object {
        private const val M2TS_PACKET = 192
        private const val TS_PACKET = 188
        private const val PREFIX = 4
        private const val TS_SYNC = 0x47.toByte()
    }
}
