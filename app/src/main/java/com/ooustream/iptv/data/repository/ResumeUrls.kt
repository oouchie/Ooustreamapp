package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.entity.WatchProgressEntity

/**
 * Pull the container extension out of a stored `watch_progress.extra` stream URL.
 *
 * The rest of that URL (host, credentials, stream id) is deliberately discarded — it is a frozen
 * snapshot of a previous play and goes stale the moment the provider moves a backend. Only the
 * extension is worth carrying forward, because it was proven by a real successful play.
 *
 * Returns null for anything that is not plausibly an extension, so callers fall back to "mp4".
 */
fun containerExtFrom(savedUrl: String?): String? {
    val url = savedUrl?.takeIf { it.isNotBlank() } ?: return null
    val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    if (!path.contains('.')) return null
    val ext = path.substringAfterLast('.')
    return ext.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.lowercase()
}

/**
 * Play URL for a Continue Watching row, ALWAYS rebuilt from the CURRENT credentials. `extra`
 * holds the absolute URL captured at the previous play and nothing ever expires it, so replaying
 * it verbatim survives a provider server/catalog migration as a dead link — the July 2026 VOD
 * backend move renumbered every movie id and broke this whole row. Only its container extension
 * is kept: that was proven by a real play, and a hardcoded "mp4" fails .m2ts titles (v4.2.1).
 */
fun ContentRepository.resumeStreamUrl(item: WatchProgressEntity): String? {
    val id = item.streamId.toIntOrNull() ?: return null
    val ext = containerExtFrom(item.extra) ?: "mp4"
    return when (item.type.lowercase()) {
        "live" -> buildLiveStreamUrl(id)
        "series" -> buildSeriesStreamUrl(id, ext)
        else -> buildVodStreamUrl(id, ext)
    }
}
