package com.ooustream.iptv.common

import android.content.Context
import android.os.Build
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import android.os.Handler

/**
 * Single source of truth for the ExoPlayer audio rendering pipeline.
 *
 * Used by OoustreamPlaybackFragment (main player), MultiViewPlayerManager (4-slot player),
 * and any future player instances. Ensures consistent:
 * - Stereo downmix (ITU-R BS.775 for 5.1/7.1, passthrough for mono/stereo)
 * - FFmpeg software fallback (AC3/DTS/EAC3 → FFmpeg, AAC/MP3 → hardware)
 * - Decoder fallback on init failure
 *
 * Budget devices (Ooustick, Fire TV Stick) cannot output multichannel PCM —
 * all channel counts are downmixed to stereo.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioPipelineFactory {

    /**
     * Safely builds a [DefaultAudioSink] with the given downmixer.
     * media3's DefaultAudioSink.Builder.build() can access Build.SOC_MODEL (API 31+)
     * which throws NoSuchFieldError on API < 31 when R8 strips the API level guard.
     * Falls back to deprecated constructor which avoids the SOC_MODEL check.
     */
    private fun buildAudioSinkSafely(
        context: Context,
        downmixer: ChannelMixingAudioProcessor,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return try {
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(downmixer))
                .build()
        } catch (e: Throwable) {
            // media3's DefaultAudioSink.build() accesses Build.SOC_MODEL (API 31+)
            // which throws NoSuchFieldError on API < 31 when R8 strips the API guard.
            // Retry without custom audio processor chain (may bypass the SOC_MODEL path).
            AudioLogger.log("DefaultAudioSink.Builder.build() failed: ${e.javaClass.simpleName}, using fallback without downmixer")
            try {
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
            } catch (e2: Throwable) {
                // If even the basic builder fails, rethrow — player can't work without audio
                AudioLogger.log("DefaultAudioSink fallback also failed: ${e2.javaClass.simpleName}")
                throw e2
            }
        }
    }

    /**
     * Creates a [DefaultRenderersFactory] with:
     * - Custom [DefaultAudioSink] containing [ChannelMixingAudioProcessor] for stereo downmix
     * - EXTENSION_RENDERER_MODE_ON (hardware first, FFmpeg fallback for AC3/DTS/EAC3)
     * - Decoder fallback enabled (tries next decoder if first fails)
     * - AudioTrack playback params enabled (supports speed adjustment)
     */
    /**
     * Creates a [DefaultRenderersFactory] identical to [createRenderersFactory] but with
     * a software-only [MediaCodecSelector] for video. Hardware audio decoders are kept.
     * Used as a fallback when the hardware video decoder inits but fails to render frames.
     */
    fun createSoftwareVideoRenderersFactory(context: Context): DefaultRenderersFactory {
        return createRenderersFactory(context).apply {
            setMediaCodecSelector(MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val allDecoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType.startsWith("video/")) {
                    // Software-only for video: c2.android.*, OMX.google.*, or any SW decoder
                    allDecoders.filter { info ->
                        info.name.startsWith("c2.android") ||
                        info.name.startsWith("OMX.google") ||
                        info.name.contains(".sw.", ignoreCase = true)
                    }.ifEmpty { allDecoders } // fall through if no software decoders found
                } else {
                    allDecoders // All decoders for audio (hardware + FFmpeg)
                }
            })
        }
    }

    /**
     * Creates a [DefaultRenderersFactory] with EXTENSION_RENDERER_MODE_PREFER for audio
     * only — video renderers still prefer hardware MediaCodec.
     *
     * Pre-v3.7.0 the bundled FFmpeg extension was Jellyfin's audio-only build, so setting
     * MODE_PREFER only affected audio. v3.7.0 replaced it with our custom AAR that has
     * video decoders too (h264/hevc/mpeg4/vp9/mpeg2video/vc1). Globally setting MODE_PREFER
     * caused ExoPlayer to pick `ffmpegLavc60.3.100-hevc` over `OMX.MTK.VIDEO.DECODER.HEVC`
     * on mt8696 devices — FFmpeg software HEVC at 1080p60 tops out at ~20fps on Cortex-A53,
     * so video went into a frame-drop spiral. Discovered via customer wash1220's v3.7.3
     * debug log: 102 FRAMES_DROPPED events right after the factory was applied.
     *
     * This override forces [EXTENSION_RENDERER_MODE_OFF] for video, keeping hardware first
     * and [setEnableDecoderFallback] handling HW failures via c2.android software decoders.
     * Audio still gets MODE_PREFER so FFmpeg wins for AC3/EAC3/DTS decode + downmix.
     *
     * Used by:
     *  - v3.3.3 reactive AC3/EAC3 audio error recovery (rebuildPlayerWithFfmpegPreferred)
     *  - v3.7.3 proactive MTK 5.1 audio preemptive rebuild (mtkMultichannelFfmpegApplied)
     */
    fun createFfmpegPreferredRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Force MODE_OFF for video — FFmpeg must never outrank MediaCodec here.
                // Audio chain still sees MODE_PREFER via setExtensionRendererMode below.
                super.buildVideoRenderers(
                    context,
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // Same downmix pipeline as createRenderersFactory
                val downmixer = ChannelMixingAudioProcessor()
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(1, 1, floatArrayOf(1f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(3, 2, floatArrayOf(
                    1f, 0f, 0.707f, 0f, 1f, 0.707f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(4, 2, floatArrayOf(
                    1f, 0f, 0.707f, 0f, 0f, 1f, 0f, 0.707f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(5, 2, floatArrayOf(
                    1f, 0f, 0.707f, 0.707f, 0f, 0f, 1f, 0.707f, 0f, 0.707f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(6, 2, floatArrayOf(
                    1f, 0f, 0.707f, 0f, 0.707f, 0f, 0f, 1f, 0.707f, 0f, 0f, 0.707f)))
                downmixer.putChannelMixingMatrix(ChannelMixingMatrix(8, 2, floatArrayOf(
                    1f, 0f, 0.707f, 0f, 0.5f, 0f, 0.707f, 0f,
                    0f, 1f, 0.707f, 0f, 0f, 0.5f, 0f, 0.707f)))
                return buildAudioSinkSafely(context, downmixer, enableFloatOutput, enableAudioTrackPlaybackParams)
            }
        }.apply {
            // PREFER = FFmpeg extension decoders tried FIRST for all audio codecs.
            // Hardware decoders used only if FFmpeg doesn't support the format.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setEnableAudioOutputPlaybackParameters(true)
            forceEnableMediaCodecAsynchronousQueueing()
        }
    }

    /**
     * Detects MediaTek chipsets by checking hardware/SOC identifiers.
     * MTK devices have known issues with OMX.MTK video decoders (black screen after first frame).
     */
    fun isMtkDevice(): Boolean {
        // Build.HARDWARE is sufficient — SOC_MODEL is API 31+ and crashes on older devices
        // even inside try-catch (R8 strips exception handlers or inlines the field access)
        return Build.HARDWARE.contains("mt", ignoreCase = true) ||
            Build.HARDWARE.contains("mediatek", ignoreCase = true)
    }

    /**
     * Detects Amlogic chipsets. Amlogic HEVC decoders (OMX.amlogic.hevc.decoder.awesome2)
     * have intermittent black screen issues when combined with hardware EAC3 decoding.
     */
    fun isAmlogicDevice(): Boolean {
        return Build.HARDWARE.contains("amlogic", ignoreCase = true) ||
            Build.HARDWARE.contains("aml", ignoreCase = true)
    }

    /**
     * Creates a [DefaultRenderersFactory] optimized for MediaTek devices:
     * - Custom [MediaCodecSelector] that deprioritizes OMX.MTK HEVC decoders on
     *   KNOWN-BAD MTK chipsets (mt8695 black screen bug — render 1 frame then stop)
     * - Same audio pipeline as [createRenderersFactory]
     *
     * On good-MTK chipsets (mt8696 / AFTKRT) and non-MTK devices, returns the
     * default factory so hardware 4K HEVC is preferred naturally. The prior
     * version of this method deprioritized OMX.MTK HEVC on ALL MediaTek devices,
     * which meant AFTKRT's hardware HEVC was skipped in favor of c2.android
     * software HEVC — 4K content ran at ~9fps as a slideshow. The black-screen
     * bug the deprioritization targets is mt8695-specific.
     */
    fun createMtkAwareRenderersFactory(context: Context): DefaultRenderersFactory {
        if (!isMtkDevice()) return createRenderersFactory(context)

        val hardware = Build.HARDWARE.lowercase()
        // Only the truly bad MTK chipsets need the HEVC deprioritization. Good
        // MTK chipsets (mt8696) have working HW HEVC including 4K and should
        // get normal decoder priority.
        val needsHevcWorkaround = hardware.contains("mt8695") ||
            hardware.contains("mt8167")
        if (!needsHevcWorkaround) return createRenderersFactory(context)

        return createRenderersFactory(context).apply {
            setMediaCodecSelector(MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val allDecoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType == "video/hevc") {
                    allDecoders.sortedWith(compareBy { info ->
                        when {
                            info.name.startsWith("c2.mtk", ignoreCase = true) -> 0
                            info.name.startsWith("c2.android", ignoreCase = true) -> 1
                            info.name.contains("google", ignoreCase = true) -> 2
                            info.name.startsWith("OMX.MTK", ignoreCase = true) -> 3
                            else -> 4
                        }
                    }).toMutableList()
                } else {
                    allDecoders
                }
            })
        }
    }

    fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val downmixer = ChannelMixingAudioProcessor()

                // Passthrough: mono and stereo (processor throws if no matrix for channel count)
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(1, 1, floatArrayOf(1f))
                )
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f))
                )

                // 3-channel (L, R, C) → stereo — some European DVB broadcasts
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(3, 2, floatArrayOf(
                        1f, 0f, 0.707f,    // L = FL + 0.707*C
                        0f, 1f, 0.707f     // R = FR + 0.707*C
                    ))
                )

                // 4-channel (L, R, SL, SR) → stereo — quadraphonic
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(4, 2, floatArrayOf(
                        1f, 0f, 0.707f, 0f,    // L = FL + 0.707*SL
                        0f, 1f, 0f, 0.707f     // R = FR + 0.707*SR
                    ))
                )

                // 5-channel (L, R, C, SL, SR — 5.0 without LFE) → stereo
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(5, 2, floatArrayOf(
                        1f, 0f, 0.707f, 0.707f, 0f,    // L = FL + 0.707*C + 0.707*SL
                        0f, 1f, 0.707f, 0f, 0.707f     // R = FR + 0.707*C + 0.707*SR
                    ))
                )

                // 5.1 surround (6ch) → stereo: ITU-R BS.775
                // Channel order: FL, FR, C, LFE, SL, SR
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(6, 2, floatArrayOf(
                        1f, 0f, 0.707f, 0f, 0.707f, 0f,    // L = FL + 0.707*C + 0.707*SL
                        0f, 1f, 0.707f, 0f, 0f, 0.707f     // R = FR + 0.707*C + 0.707*SR
                    ))
                )

                // 7.1 surround (8ch) → stereo
                // Channel order: FL, FR, C, LFE, BL, BR, SL, SR
                downmixer.putChannelMixingMatrix(
                    ChannelMixingMatrix(8, 2, floatArrayOf(
                        1f, 0f, 0.707f, 0f, 0.5f, 0f, 0.707f, 0f,    // L = FL + 0.707*C + 0.5*BL + 0.707*SL
                        0f, 1f, 0.707f, 0f, 0f, 0.5f, 0f, 0.707f     // R = FR + 0.707*C + 0.5*BR + 0.707*SR
                    ))
                )

                return buildAudioSinkSafely(context, downmixer, enableFloatOutput, enableAudioTrackPlaybackParams)
            }
        }.apply {
            // ON = hardware decoders first, FFmpeg as fallback for codecs hardware can't handle
            // (AC3/DTS/EAC3 → FFmpeg software decode, AAC/MP3 → hardware). PREFER breaks live TV
            // because FFmpeg handles ALL codecs and AudioSink rejects its PCM output on some streams.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // Try next decoder in fallback list if first fails to initialize
            setEnableDecoderFallback(true)
            // Enable AudioTrack-level playback speed control
            setEnableAudioOutputPlaybackParameters(true)
            // Force async MediaCodec buffer queueing on API < 31. This matches how IJKPlayer
            // (used by IPTV Smarters) feeds the MTK HEVC decoder — async callbacks instead of
            // synchronous polling. Fixes HEVC Main 10 (10-bit) silent decoder stalls on mt8696
            // where the decoder renders 1 frame then stops with sync queueing.
            forceEnableMediaCodecAsynchronousQueueing()
        }
    }
}
