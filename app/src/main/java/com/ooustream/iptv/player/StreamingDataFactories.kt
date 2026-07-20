package com.ooustream.iptv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.data.remote.AuthInterceptor
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
     * Build an OkHttpDataSource.Factory for fetching stream bytes.
     *
     * CRITICAL: this must NOT carry the JSON-API request headers. The shared @Singleton client has
     * `AuthInterceptor`, which `addHeader`s `Accept: application/json` and a second `User-Agent`
     * (`OoustreamIPTV/1.0`) onto EVERY request. On a video byte-fetch that means each 4K segment
     * request goes out advertising "I want JSON" with a duplicate/conflicting UA — exactly the kind
     * of malformed media request IPTV CDNs throttle or de-prioritize. IPTV Smarters (IJKPlayer) sends
     * a clean media GET and saturates the link; we were sending an API-shaped request and getting
     * ~real-time throughput, so the buffer never built past ~2s and 4K stuttered.
     *
     * Fix: derive a streaming client from the shared one via `newBuilder()` — inheriting its
     * connection pool, timeouts, and self-signed-SSL config — but strip `AuthInterceptor` so stream
     * requests carry ONLY our single player User-Agent (set by OkHttpDataSource below) and no bogus
     * Accept header. The Retrofit/API path keeps the shared client untouched.
     */
    fun buildDataSourceFactory(okHttpClient: OkHttpClient): OkHttpDataSource.Factory {
        val streamingClient = okHttpClient.newBuilder()
            .apply { interceptors().removeAll { it is AuthInterceptor } }
            .build()
        return OkHttpDataSource.Factory(streamingClient)
            .setUserAgent(userAgent)
    }

    /** True if the URL is an M2TS/BDAV container ExoPlayer can't demux without prefix-stripping. */
    fun isM2tsUrl(url: String): Boolean {
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        return ext == "m2ts" || ext == "mts"
    }

    /**
     * Build a progressive media source that strips M2TS 192-byte prefixes (see
     * [M2tsExtractingDataSource]) so ExoPlayer's TsExtractor can read Blu-ray-style VOD.
     * The wrapper is self-gating (passes through non-M2TS bytes), so this is safe even if the
     * server ends up serving plain TS after the redirect.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun buildM2tsMediaSource(
        url: String,
        upstreamFactory: androidx.media3.datasource.DataSource.Factory
    ): androidx.media3.exoplayer.source.MediaSource {
        val m2tsFactory = M2tsExtractingDataSource.Factory(upstreamFactory)
        // SINGLE TsExtractor (not the 20-way DefaultExtractorsFactory): multi-extractor sniffing
        // makes ExoPlayer open the source, peek, then RE-OPEN to extract — and this provider's
        // single-use-token `/live/play/` redirect rejects the 2nd concurrent connection with 416.
        // One extractor → no sniff re-open → one connection (how IJKPlayer plays it). CBR seeking
        // off for the same reason (length probing = extra opens).
        val extractors = androidx.media3.extractor.ExtractorsFactory {
            arrayOf(
                androidx.media3.extractor.ts.TsExtractor(
                    androidx.media3.extractor.ts.TsExtractor.MODE_SINGLE_PMT,
                    androidx.media3.common.util.TimestampAdjuster(0L),
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory(
                        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    )
                )
            )
        }
        return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            m2tsFactory, extractors
        ).createMediaSource(
            androidx.media3.common.MediaItem.fromUri(url)
        )
    }
}
