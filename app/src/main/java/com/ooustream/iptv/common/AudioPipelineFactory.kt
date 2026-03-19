package com.ooustream.iptv.common

import android.content.Context
import android.os.Build
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

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
     * Creates a [DefaultRenderersFactory] with EXTENSION_RENDERER_MODE_PREFER for audio.
     * FFmpeg extension decoders are tried FIRST, before hardware MediaCodec decoders.
     *
     * Used as a fallback when hardware audio decoders falsely report support for AC3/EAC3
     * but crash at runtime (e.g. mt8695-based Fire TV Sticks). FFmpeg correctly decodes
     * these codecs via software and the ChannelMixingAudioProcessor downmixes to stereo.
     */
    fun createFfmpegPreferredRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context) {
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
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(downmixer))
                    .build()
            }
        }.apply {
            // PREFER = FFmpeg extension decoders tried FIRST for all audio codecs.
            // Hardware decoders used only if FFmpeg doesn't support the format.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setEnableAudioTrackPlaybackParams(true)
        }
    }

    /**
     * Detects MediaTek chipsets by checking hardware/SOC identifiers.
     * MTK devices have known issues with OMX.MTK video decoders (black screen after first frame).
     */
    fun isMtkDevice(): Boolean {
        return Build.HARDWARE.contains("mt", ignoreCase = true) ||
            (Build.SOC_MODEL?.contains("mt", ignoreCase = true) == true) ||
            Build.HARDWARE.contains("mediatek", ignoreCase = true)
    }

    /**
     * Creates a [DefaultRenderersFactory] optimized for MediaTek devices:
     * - Custom [MediaCodecSelector] that deprioritizes OMX.MTK video decoders
     *   (prefers c2.mtk > c2.android > OMX.google > OMX.MTK)
     * - Same audio pipeline as [createRenderersFactory]
     *
     * On non-MTK devices, falls back to [createRenderersFactory].
     */
    fun createMtkAwareRenderersFactory(context: Context): DefaultRenderersFactory {
        if (!isMtkDevice()) return createRenderersFactory(context)

        return createRenderersFactory(context).apply {
            // Custom codec selector: deprioritize OMX.MTK video decoders
            setMediaCodecSelector(MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val allDecoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType.startsWith("video/")) {
                    // Prefer C2/SW decoders over OMX.MTK for video (OMX.MTK has black screen bug)
                    allDecoders.sortedWith(compareBy { info ->
                        when {
                            info.name.startsWith("c2.mtk", ignoreCase = true) -> 0      // C2 MTK: newer, fewer bugs
                            info.name.startsWith("c2.android", ignoreCase = true) -> 1   // C2 generic SW
                            info.name.contains("google", ignoreCase = true) -> 2          // OMX.google SW
                            info.name.startsWith("OMX.MTK", ignoreCase = true) -> 3      // OMX MTK: problematic
                            else -> 4
                        }
                    }).toMutableList()
                } else {
                    allDecoders // Keep all decoders for audio (hardware + FFmpeg)
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

                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(downmixer)
                    )
                    .build()
            }
        }.apply {
            // ON = hardware decoders first, FFmpeg as fallback for codecs hardware can't handle
            // (AC3/DTS/EAC3 → FFmpeg software decode, AAC/MP3 → hardware). PREFER breaks live TV
            // because FFmpeg handles ALL codecs and AudioSink rejects its PCM output on some streams.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // Try next decoder in fallback list if first fails to initialize
            setEnableDecoderFallback(true)
            // Enable AudioTrack-level playback speed control
            setEnableAudioTrackPlaybackParams(true)
        }
    }
}
