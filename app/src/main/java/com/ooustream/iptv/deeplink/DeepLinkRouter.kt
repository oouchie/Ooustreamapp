package com.ooustream.iptv.deeplink

import android.net.Uri

sealed class DeepLinkTarget {
    data class Live(val streamId: String) : DeepLinkTarget()
    data class Vod(val streamId: String) : DeepLinkTarget()
    data class Series(val seriesId: String) : DeepLinkTarget()
    data class Search(val query: String) : DeepLinkTarget()
    object Home : DeepLinkTarget()
    object LiveTv : DeepLinkTarget()
    object Favorites : DeepLinkTarget()
}

object DeepLinkRouter {

    /**
     * Parse a deep link URI into a navigation target.
     *
     * Supported schemes:
     *   ooustream://live/{streamId}
     *   ooustream://vod/{streamId}
     *   ooustream://series/{seriesId}
     *   ooustream://search?q={query}
     *   ooustream://home
     *   ooustream://livetv
     *   ooustream://favorites
     */
    fun parse(uri: Uri?): DeepLinkTarget? {
        uri ?: return null

        val scheme = uri.scheme?.lowercase()
        if (scheme != "ooustream") return null

        val host = uri.host?.lowercase() ?: return null
        val pathSegments = uri.pathSegments

        return when (host) {
            "live" -> {
                val streamId = pathSegments.firstOrNull() ?: return null
                val id = streamId.toIntOrNull() ?: return null
                if (id <= 0) return null
                DeepLinkTarget.Live(streamId)
            }
            "vod" -> {
                val streamId = pathSegments.firstOrNull() ?: return null
                val id = streamId.toIntOrNull() ?: return null
                if (id <= 0) return null
                DeepLinkTarget.Vod(streamId)
            }
            "series" -> {
                val seriesId = pathSegments.firstOrNull() ?: return null
                val id = seriesId.toIntOrNull() ?: return null
                if (id <= 0) return null
                DeepLinkTarget.Series(seriesId)
            }
            "search" -> {
                val query = uri.getQueryParameter("q")?.take(200) ?: return null
                if (query.isBlank()) return null
                DeepLinkTarget.Search(query)
            }
            "home" -> DeepLinkTarget.Home
            "livetv" -> DeepLinkTarget.LiveTv
            "favorites" -> DeepLinkTarget.Favorites
            else -> null
        }
    }
}
