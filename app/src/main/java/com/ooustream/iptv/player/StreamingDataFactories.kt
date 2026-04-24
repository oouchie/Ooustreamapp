package com.ooustream.iptv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.ooustream.iptv.BuildConfig
import okhttp3.OkHttpClient

/**
 * Shared factory builders so every ExoPlayer instance in the app gets the same
 * stream-side configuration: CEA-608 closed captions on live TS, extended PTS
 * search for poorly muxed IPTV streams, CBR seeking, and an explicit User-Agent
 * that providers' anti-bot filters recognize instead of the default `okhttp/4.12.0`.
 */
object StreamingDataFactories {

    /**
     * User-Agent used on every stream-byte fetch. Some IPTV providers block the
     * raw OkHttp default UA; announcing ourselves as a media player matches what
     * the big clients (VLC, IPTV Smarters, TiviMate) do.
     */
    val userAgent: String = "Ooustream/${BuildConfig.VERSION_NAME} (Android TV; ExoPlayer)"

    /**
     * Configured DefaultExtractorsFactory. Call per-player — `DefaultExtractorsFactory`
     * holds per-instance state (seek tables, etc.) so a shared singleton is unsafe.
     *
     * Config applied:
     *  - CEA-608 subtitle format registered on the TS extractor so US closed
     *    captions embedded in H.264 SEI actually surface as a selectable
     *    subtitle track. Without this call they're invisible.
     *  - 3× PTS search bytes — some IPTV transcoders write PTS far from the
     *    start of the stream; default search can give up and fail to play.
     *  - Constant bitrate seeking for containers without precise seek tables
     *    (some IPTV VOD MKV/MP4 files).
     */
    fun buildExtractorsFactory(): DefaultExtractorsFactory {
        return DefaultExtractorsFactory().apply {
            setTsSubtitleFormats(
                listOf(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                        .setLanguage("en")
                        .build()
                )
            )
            setTsExtractorTimestampSearchBytes(
                3 * TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
            )
            setConstantBitrateSeekingEnabled(true)
        }
    }

    /**
     * Build an OkHttpDataSource.Factory with the Ooustream User-Agent attached.
     * Reuses the app's shared OkHttpClient (connection pool, SSL config, etc.)
     * — this only adds the UA header to stream-byte requests, it doesn't touch
     * the Retrofit API path.
     */
    fun buildDataSourceFactory(okHttpClient: OkHttpClient): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
    }
}
