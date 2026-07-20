package com.ooustream.iptv.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.ooustream.iptv.common.AudioLogger
import android.media.MediaCodecInfo.CodecProfileLevel

/**
 * Routes Dolby Vision **Profile 7** content to the hardware HEVC decoder instead of the FFmpeg
 * software renderer, on devices whose hardware can actually decode the base layer.
 *
 * WHY THIS EXISTS (verified against Media3 1.10.0 source):
 * A DV Profile 7 stream (codec `dvhe.07`, mime `video/dolby-vision`) on a display that does NOT
 * advertise Dolby Vision should be shown by decoding its HEVC Main 10 base layer. Media3 has that
 * whole path — EXCEPT `MediaCodecUtil.getAlternativeCodecMimeType()` maps DV profiles 4 and 8 to
 * `video/hevc` but deliberately returns null for Profile 7 ("deprecated / not always backward
 * compatible"). With no HEVC alternative, `MediaCodecVideoRenderer` only considers the DV decoders
 * (which reject P7) → it reports FORMAT_EXCEEDS_CAPABILITIES, while the auto-registered FFmpeg video
 * renderer reports FORMAT_HANDLED and wins the track — so the base layer decodes in single-threaded
 * software at ~0fps: slideshow, black screen, OOM. The mt8696 hardware HEVC decoder does Main 10 4K
 * fine; it's simply never offered the format.
 *
 * The fix: rewrite the track Format's sampleMimeType from `video/dolby-vision` to `video/hevc`
 * (with a real Main 10 codecs string), preserving the already-valid base-layer HEVC CSD
 * (`initializationData`), colorInfo, and size/rate. Then the normal HEVC path selects the hardware
 * decoder (index 0, FORMAT_HANDLED) over FFmpeg automatically. This mirrors how IJKPlayer/IPTV
 * Smarters plays these files.
 *
 * FAIL-OPEN GATE — the rewrite fires only when every condition holds, otherwise the Format is
 * returned unchanged (today's behavior, incl. the FFmpeg fallback that genuinely-HEVC-less boxes
 * like Allwinner sun50iw9p1 need):
 *   1. sampleMimeType == video/dolby-vision
 *   2. DV Profile 7 (codecs starts with "dvhe.07" / "dvh1.07"). NOT 4/8 (Media3 already maps those),
 *      NOT 5 (single-layer, no HDR10-compatible base — would look wrong), NOT 9/10 (AVC/AV1 base).
 *   3. The display does NOT natively support Dolby Vision — never hijack a real DV output.
 *   4. A real vendor hardware HEVC decoder advertises Main 10 for this resolution. This is the
 *      condition that separates mt8696 (rewrite → hardware) from Allwinner (no Main 10 → no rewrite →
 *      existing FFmpeg fallback preserved).
 *
 * The v4.2.3 4K-HEVC frame watchdog remains the backstop: if a specific P7 encode's RPU/EL NAL units
 * trip the hardware decoder, the watchdog can still recover.
 */
@UnstableApi
object DolbyVisionBaseLayer {

    /** Wrap an ExtractorsFactory so each video track's Format is checked/rewritten once, at source. */
    fun wrap(context: Context, delegate: ExtractorsFactory): ExtractorsFactory {
        val appContext = context.applicationContext
        return object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> =
                delegate.createExtractors().map { RewritingExtractor(it, appContext) }.toTypedArray()

            override fun createExtractors(
                uri: android.net.Uri,
                responseHeaders: Map<String, List<String>>
            ): Array<Extractor> =
                delegate.createExtractors(uri, responseHeaders)
                    .map { RewritingExtractor(it, appContext) }.toTypedArray()
        }
    }

    /**
     * Return an HEVC Main 10 Format if [format] is a rewritable DV Profile 7 track on this device,
     * otherwise return [format] unchanged.
     */
    fun maybeRewrite(context: Context, format: Format): Format {
        // Second rewrite class handled here (same seam, same fail-open philosophy): plain HEVC whose
        // codec string over-declares its tier/level. See maybeNormalizeHevcLevel.
        if (format.sampleMimeType == MimeTypes.VIDEO_H265) {
            return maybeNormalizeHevcLevel(context, format)
        }
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format

        val codecs = format.codecs?.lowercase() ?: ""
        val isProfile7 = codecs.startsWith("dvhe.07") || codecs.startsWith("dvh1.07")
        if (!isProfile7) return format

        val width = format.width
        val height = format.height
        if (displaySupportsDolbyVision(context)) {
            AudioLogger.log("DV Profile 7 on a DV-capable display — leaving native DV path")
            return format
        }
        if (!hasHardwareHevcMain10(width, height, format.frameRate)) {
            // Log what the HEVC decoders actually advertise — the mt8696 Main-10 advertisement is the
            // one thing that decides whether the rewrite can fire, and it must be checked on-device.
            AudioLogger.log("DV Profile 7 rewrite DECLINED (no HW HEVC Main10 for ${width}x$height): ${describeHevcDecoders()}")
            return format
        }

        val rewritten = format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(hevcMain10Codecs(width, height, format.frameRate))
            .build()

        AudioLogger.log(
            "DV Profile 7 base-layer routed to hardware HEVC: ${width}x$height " +
                "(was $codecs) — sampleMimeType→video/hevc"
        )
        return rewritten
    }

    /**
     * Normalize an over-declared HEVC tier/level so a hardware decoder that genuinely handles the
     * content isn't rejected on paper.
     *
     * Real case ("Black and Blue (2019)" 4K remux): codecs `hvc1.2.4.H156.B0` declares High tier
     * Level 5.2 (a 4K@120-class claim) on content that is actually 4K@24. The mt8696 HW decoder
     * advertises up to ~Level 5.1, so the profile-level check reports EXCEEDS_CAPABILITIES, and with
     * `setExceedRendererCapabilitiesIfNecessary(false)` on the track selector (an audio-crash safety
     * kept deliberately), NO video renderer is selected at all — audio plays over a black screen and
     * the watchdog eventually gives up. FFmpeg-based players (IPTV Smarters) play the same file
     * because they ignore declared levels entirely and just decode.
     *
     * We split the difference: keep capability checking, but re-declare the LEVEL from the actual
     * resolution/frame-rate when — and only when — every gate proves the paper claim is the sole
     * blocker:
     *   1. The declared codec string fails `isFormatSupported` on EVERY vendor HW HEVC decoder.
     *   2. The actual width×height@fps IS supported by a vendor HW decoder (so the hardware truly
     *      can decode this stream — level checks are conservative by design).
     *   3. The rewritten candidate verifiably PASSES `isFormatSupported` on a vendor HW decoder.
     * Only the tier+level token is replaced — the PROFILE stays untouched, so a device whose
     * hardware lacks Main 10 (Allwinner) still correctly declines 10-bit content and keeps its
     * software fallback. Any gate inconclusive → the format is returned unchanged.
     */
    private fun maybeNormalizeHevcLevel(context: Context, format: Format): Format {
        val codecs = format.codecs ?: return format
        val parts = codecs.split('.')
        // hvc1/hev1 . <profile> . <compat> . <tier+level> . <constraints...>
        if (parts.size < 4) return format
        val base = parts[0].lowercase()
        if (base != "hvc1" && base != "hev1") return format

        val width = format.width
        val height = format.height
        if (width <= 0 || height <= 0) return format

        return try {
            val vendors = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
                .filter { info ->
                    val name = info.name.lowercase()
                    !name.startsWith("c2.android") && !name.startsWith("omx.google") &&
                        info.capabilities?.videoCapabilities != null
                }
            if (vendors.isEmpty()) {
                AudioLogger.log("HEVC normalize: no vendor HW decoders — no-op ($codecs)")
                return format
            }

            // Gate 1: paper claim rejected everywhere. If any vendor already accepts it, no-op.
            val paperOk = vendors.filter { it.isFormatSupported(context, format) }
            if (paperOk.isNotEmpty()) {
                AudioLogger.log(
                    "HEVC normalize: declared $codecs ALREADY accepted by " +
                        paperOk.joinToString { it.name } + " — no-op (selection blocker is elsewhere)"
                )
                return format
            }

            // Gate 2: the actual pixels are within real hardware ability.
            val fps = format.frameRate.takeIf { it > 0f }?.toDouble() ?: 0.0
            if (vendors.none { it.isVideoSizeAndRateSupportedV21(width, height, fps) }) {
                AudioLogger.log(
                    "HEVC normalize: ${width}x$height@$fps exceeds real HW ability — no-op ($codecs)"
                )
                return format
            }

            // Re-declare the level honestly from actual size/rate; keep profile + constraints.
            val is4k = height >= 1440 || width >= 2560
            val level = when {
                is4k && format.frameRate > 30f -> "L153"  // 5.1 (4K@60)
                is4k -> "L150"                            // 5.0 (4K@30)
                else -> "L123"                            // 4.1 (1080p@60)
            }
            val normalized = parts.toMutableList().also { it[3] = level }.joinToString(".")
            val candidate = format.buildUpon().setCodecs(normalized).build()

            // Gate 3: only ship the rewrite if it provably unblocks a vendor decoder.
            if (vendors.none { it.isFormatSupported(context, candidate) }) {
                AudioLogger.log(
                    "HEVC normalize: candidate $normalized STILL rejected by all vendors " +
                        "(${describeHevcDecoders()}) — no-op"
                )
                return format
            }

            AudioLogger.log(
                "HEVC level normalized for hardware decode: ${width}x$height " +
                    "codecs $codecs → $normalized"
            )
            candidate
        } catch (t: Throwable) {
            AudioLogger.log("HEVC normalize: threw ${t.javaClass.simpleName}: ${t.message} — no-op")
            format
        }
    }

    /** True only if the connected display advertises Dolby Vision (HDR type 1). */
    private fun displaySupportsDolbyVision(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return false
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            @Suppress("DEPRECATION")
            val hdr = display.hdrCapabilities ?: return false
            hdr.supportedHdrTypes.any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True if a real VENDOR hardware HEVC decoder (not the c2.android/OMX.google software ones)
     * advertises the Main 10 profile and can handle this resolution. Fails closed to `false` on any
     * doubt, so an inconclusive probe leaves DV content on the existing software path.
     */
    private fun hasHardwareHevcMain10(width: Int, height: Int, frameRate: Float): Boolean {
        return try {
            val fps = frameRate.takeIf { it > 0f }?.toDouble() ?: 0.0
            val infos = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
            infos.any { info ->
                val name = info.name.lowercase()
                val vendor = !name.startsWith("c2.android") && !name.startsWith("omx.google")
                val main10 = info.capabilities?.profileLevels?.any {
                    it.profile == CodecProfileLevel.HEVCProfileMain10
                } == true
                val sizeOk = width <= 0 || height <= 0 ||
                    info.isVideoSizeAndRateSupportedV21(width, height, fps)
                vendor && main10 && sizeOk
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Diagnostic: list HEVC decoders + whether each advertises Main 10, for on-device gate debugging. */
    private fun describeHevcDecoders(): String {
        return try {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).joinToString("; ") { info ->
                val main10 = info.capabilities?.profileLevels?.any {
                    it.profile == CodecProfileLevel.HEVCProfileMain10
                } == true
                "${info.name}[main10=$main10]"
            }.ifEmpty { "no HEVC decoders" }
        } catch (e: Throwable) {
            "query failed: ${e.javaClass.simpleName}"
        }
    }

    /** Real HEVC Main 10 (profile 2) codecs string; level scaled to resolution/fps. Never null. */
    private fun hevcMain10Codecs(width: Int, height: Int, frameRate: Float): String {
        val is4k = height >= 1440 || width >= 2560
        // L153 = HEVC level 5.1 (4K@60), L150 = 5.0 (4K@30), L120 = 4.0 (1080p). Understating for
        // selection is safe (a decoder advertising a higher level still matches); actual decode
        // reads the bitstream SPS. hvc1.<profile=2>.<compat=4>.<tier L + level>.<constraints>.
        val level = when {
            is4k && frameRate > 30f -> "L153"
            is4k -> "L150"
            else -> "L120"
        }
        return "hvc1.2.4.$level.90"
    }

    // ── Extractor delegation: rewrite the video Format at the TrackOutput.format() seam ──────────

    private class RewritingExtractor(
        private val delegate: Extractor,
        private val context: Context
    ) : Extractor by delegate {
        override fun init(output: ExtractorOutput) {
            delegate.init(RewritingExtractorOutput(output, context))
        }
    }

    private class RewritingExtractorOutput(
        private val delegate: ExtractorOutput,
        private val context: Context
    ) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val out = delegate.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) RewritingTrackOutput(out, context) else out
        }

        override fun endTracks() = delegate.endTracks()
        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
    }

    private class RewritingTrackOutput(
        private val delegate: TrackOutput,
        private val context: Context
    ) : TrackOutput by delegate {
        override fun format(format: Format) {
            delegate.format(maybeRewrite(context, format))
        }
    }
}
