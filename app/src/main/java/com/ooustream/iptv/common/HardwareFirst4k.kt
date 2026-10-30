package com.ooustream.iptv.common

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.ooustream.iptv.BuildConfig

/**
 * Hardware-first 4K on mt8695 sticks (v5.2.3).
 *
 * The 1080p Fire TV sticks built on mt8695 (AFTSS, AFTSSS) have a hardware HEVC decoder that DECLARES
 * 1080p as its limit. Media3 therefore marks a 4K HEVC track EXCEEDS_CAPABILITIES and hands it to the
 * FFmpeg software renderer instead, which decodes 4K at ~2fps until the 128MB heap runs out. v5.2.2
 * refused those titles upfront (customer shanewi: Marshals S2E2, `hvc1.1.6.L150.90`, 3840x1920).
 * IJKPlayer apps (IPTV Smarters) skip the declared limit and simply configure the hardware decoder.
 *
 * Whether this chip can really decode 4K is NOT known. So this lets the hardware decoder TRY, and the
 * playback fragment watches the result with [com.ooustream.iptv.player.HardwareTrialJudge]: no picture
 * or a choppy picture within ~5 seconds gets the same refusal v5.2.2 shows.
 *
 * Deliberately narrow — every condition in [decision] must hold:
 *  - mt8695 only,
 *  - plain HEVC (Dolby Vision Profile 7 is not mapped to HEVC on a stick whose decoder doesn't
 *    declare 4K Main 10, so it stays refused),
 *  - above 1080p and within 4096x2304,
 *  - no MediaCodec decoder declares the size ([VideoDecoderCapability] says a definite `false`),
 *  - a hardware HEVC decoder exists,
 *  - fewer than [MAX_FAILURES] failed trials on this app version. A process that died while the
 *    hardware was decoding a trial title (low-memory kill, native crash) counts as one failure, so
 *    the device stops trying. That mark stays set after the trial passes, until the title ends
 *    cleanly: passing the first seconds and dying a minute later is still a failure.
 * FFmpeg software video stays blocked for 4K regardless.
 *
 * [HardwareFirst4kVideoRenderer] and the fragment both ask [decision], so the renderer only claims a
 * track that the fragment is going to supervise.
 */
@UnstableApi
object HardwareFirst4k {

    enum class Decision {
        /** Not this device or not this kind of track — normal playback rules apply. */
        NOT_APPLICABLE,
        /** Let the hardware decoder try; the fragment judges it. */
        TRIAL,
        /** Trials keep failing on this device and app version — refuse upfront, as v5.2.2 did. */
        LOCKED_OUT,
    }

    /** Failed trials (on one app version) before the device stops trying. */
    const val MAX_FAILURES = 2

    private const val PREFS = "hardware_first_4k"
    private const val KEY_VERSION = "version_code"
    private const val KEY_FAILURES = "failures"
    private const val KEY_IN_FLIGHT = "trial_in_flight"

    private val lock = Any()
    private var loaded = false
    private var failureCount = 0

    private val hasHardwareHevcDecoder: Boolean by lazy {
        try {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
                .any { it.hardwareAccelerated && !it.softwareOnly }
        } catch (_: Throwable) {
            false
        }
    }

    fun isTrialDevice(): Boolean = Build.HARDWARE.lowercase().contains("mt8695")

    /** Cheap checks first: this runs inside the renderer's supportsFormat on the playback thread. */
    fun decision(context: Context, format: Format): Decision {
        if (!isTrialDevice()) return Decision.NOT_APPLICABLE
        if (format.sampleMimeType != MimeTypes.VIDEO_H265) return Decision.NOT_APPLICABLE
        val w = format.width
        val h = format.height
        if (w <= 0 || h <= 0 || (w <= 1920 && h <= 1088) || w > 4096 || h > 2304) {
            return Decision.NOT_APPLICABLE
        }
        if (VideoDecoderCapability.canDecode(MimeTypes.VIDEO_H265, w, h, format.frameRate) != false) {
            return Decision.NOT_APPLICABLE
        }
        if (!hasHardwareHevcDecoder) return Decision.NOT_APPLICABLE
        return if (failures(context) >= MAX_FAILURES) Decision.LOCKED_OUT else Decision.TRIAL
    }

    fun failures(context: Context): Int = synchronized(lock) {
        ensureLoaded(context)
        failureCount
    }

    /**
     * Marks the hardware as decoding a trial title, so a process death before [trialEnded] is
     * counted next launch. Also called when the player comes back to the foreground.
     */
    fun trialStarted(context: Context) = synchronized(lock) {
        ensureLoaded(context)
        prefs(context).edit().putBoolean(KEY_IN_FLIGHT, true).apply()
    }

    /**
     * Clears the in-flight mark. [passed]: true = the title played and ended cleanly (clears
     * earlier failures), false = the hardware didn't keep up, null = no verdict (the user left
     * mid-trial, another error ended playback, or the app went to the background).
     */
    fun trialEnded(context: Context, passed: Boolean?) = synchronized(lock) {
        ensureLoaded(context)
        when (passed) {
            true -> failureCount = 0
            false -> failureCount++
            null -> Unit
        }
        prefs(context).edit()
            .putInt(KEY_FAILURES, failureCount)
            .putBoolean(KEY_IN_FLIGHT, false)
            .apply()
    }

    /** Called with [lock] held. Loads once per process; a new app version starts from zero. */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        if (prefs.getInt(KEY_VERSION, -1) != BuildConfig.VERSION_CODE) {
            prefs.edit().clear().putInt(KEY_VERSION, BuildConfig.VERSION_CODE).apply()
            failureCount = 0
            return
        }
        failureCount = prefs.getInt(KEY_FAILURES, 0)
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            // The previous process died while the hardware was decoding a trial.
            failureCount++
            prefs.edit().putInt(KEY_FAILURES, failureCount).putBoolean(KEY_IN_FLIGHT, false).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The stock MediaCodec video renderer, except for a [HardwareFirst4k] TRIAL track: it reports the
 * track as HANDLED by a hardware decoder and offers only hardware decoders for it.
 *
 * Reporting HANDLED is what moves the track off FFmpeg. The track selector maps a track to the
 * renderer with the highest support level and a tie goes to the earlier renderer; this one is built
 * before the FFmpeg renderer, so at HANDLED vs HANDLED it wins. Offering only hardware decoders keeps
 * codec init from landing on a software MediaCodec decoder (the mt8695 HEVC selector puts OMX.MTK last).
 *
 * Built by [AudioPipelineFactory] with the same settings DefaultRenderersFactory uses (checked
 * against the Media3 1.10.0 bytecode on 2026-10-29).
 */
@UnstableApi
class HardwareFirst4kVideoRenderer(
    context: Context,
    builder: MediaCodecVideoRenderer.Builder,
) : MediaCodecVideoRenderer(builder) {

    private val appContext = context.applicationContext

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val capabilities = super.supportsFormat(mediaCodecSelector, format)
        if (RendererCapabilities.getFormatSupport(capabilities) != C.FORMAT_EXCEEDS_CAPABILITIES) {
            return capabilities
        }
        if (HardwareFirst4k.decision(appContext, format) != HardwareFirst4k.Decision.TRIAL) {
            return capabilities
        }
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.getAdaptiveSupport(capabilities),
            RendererCapabilities.getTunnelingSupport(capabilities),
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY,
            RendererCapabilities.getAudioOffloadSupport(capabilities),
        )
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        if (HardwareFirst4k.decision(appContext, format) != HardwareFirst4k.Decision.TRIAL) return infos
        return infos.filter { it.hardwareAccelerated && !it.softwareOnly }.ifEmpty { infos }
    }
}
