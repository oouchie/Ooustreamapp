package com.ooustream.iptv.common

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.ooustream.iptv.BuildConfig

/**
 * Lightweight audio diagnostic logging. All methods are no-ops in release builds.
 * Filter with: adb logcat -s OOUSTREAM_AUDIO
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioLogger {
    private const val TAG = "OOUSTREAM_AUDIO"

    fun logPlayerCreated(hasTrackSelector: Boolean, hasAudioAttributes: Boolean) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "Player created: trackSelector=$hasTrackSelector audioAttrs=$hasAudioAttributes")
    }

    fun logTrackSelection(tracks: Tracks) {
        if (!BuildConfig.DEBUG) return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            Log.w(TAG, "TRACKS: 0 audio groups (no audio in stream)")
            return
        }
        Log.w(TAG, "TRACKS: ${audioGroups.size} audio group(s)")
        for ((gi, group) in audioGroups.withIndex()) {
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val sel = if (group.isTrackSelected(i)) "SEL" else "   "
                val sup = if (group.isTrackSupported(i)) "OK" else "UNSUPPORTED"
                Log.w(TAG, "  [$sel] g$gi/t$i lang=${f.language ?: "?"} " +
                    "label=${f.label ?: "?"} codec=${f.codecs ?: "?"} " +
                    "ch=${f.channelCount} mime=${f.sampleMimeType} $sup")
            }
        }
    }

    fun logLanguageSelected(lang: String?, label: String?, fallback: Boolean, reason: String) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "LANG: selected lang=$lang label=$label fallback=$fallback ($reason)")
    }

    fun logVolume(context: String, volume: Float) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "VOL [$context] volume=$volume")
    }

    fun logAudioError(error: PlaybackException) {
        if (!BuildConfig.DEBUG) return
        Log.e(TAG, "ERROR: code=${error.errorCode} msg=${error.message}", error)
    }

    fun logNoAudioTracks() {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "DIAG: Stream has no audio tracks")
    }

    fun logAudioCapabilities(caps: AudioCapabilities) {
        if (!BuildConfig.DEBUG) return
        val supported = mutableListOf<String>()
        if (caps.supportsEncoding(C.ENCODING_AC3)) supported.add("AC3")
        if (caps.supportsEncoding(C.ENCODING_E_AC3)) supported.add("E-AC3")
        if (caps.supportsEncoding(C.ENCODING_DTS)) supported.add("DTS")
        if (caps.supportsEncoding(C.ENCODING_DTS_HD)) supported.add("DTS-HD")
        if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) supported.add("TrueHD")
        if (caps.supportsEncoding(C.ENCODING_AC4)) supported.add("AC4")
        Log.w(TAG, "CAPS: maxCh=${caps.maxChannelCount} passthrough=[${supported.joinToString()}]")
    }

    fun logAudioStatus(status: String) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "STATUS: $status")
    }
}
