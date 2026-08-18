package com.ooustream.iptv.data.model

/**
 * Single choke point for every stream URL the app requests.
 *
 * The two provider-supplied fields that end up in these paths — `container_extension` and the
 * episode/stream id — are both routinely wrong in Xtream listings, and the app used to guard them
 * (inconsistently) at the ~10 individual call sites instead of here. Validation lives at the choke
 * point now so a new call site cannot reintroduce the bug; see [sanitizeExt] and [episodeStreamId].
 */
object StreamUrlBuilder {

    /** Fallback container when the listing's extension is missing or unusable. */
    const val DEFAULT_EXT = "mp4"

    /**
     * Normalizes a provider `container_extension` into something safe to put after the final dot.
     *
     * Kotlin's `?:` only fires on **null**, so the `containerExtension ?: "mp4"` pattern that was
     * copied across the app let an EMPTY STRING through untouched — producing a path that ends in a
     * bare `.` (e.g. `/series/u/p/2056173.`). An Xtream panel answers that malformed path with
     * **HTTP 400**, which surfaced to users as the unexplained "Server returned error 400".
     *
     * Shape rule is the one already proven in `HomeFragment.containerExtFrom`: 2-5 alphanumerics.
     * Anything else (blank, whitespace, a leading dot, a Gson-coerced `"0"`/`"false"`, a stray path
     * fragment) falls back to [DEFAULT_EXT] rather than corrupting the URL.
     */
    fun sanitizeExt(raw: String?): String =
        raw?.trim()
            ?.removePrefix(".")
            ?.lowercase()
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: DEFAULT_EXT

    /**
     * Parses a provider episode id (a JSON *string* in the Xtream schema) into a usable stream id.
     *
     * Returns null — meaning "this listing is unplayable, fail honestly" — instead of the old
     * `?: 0`, which silently requested stream `0` and got back a confusing 404/551 that read like a
     * server outage rather than a bad listing.
     */
    fun episodeStreamId(rawId: String?): Int? =
        rawId?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun live(server: String, user: String, pass: String, streamId: Int): String =
        "$server/live/$user/$pass/$streamId.ts"

    fun vod(server: String, user: String, pass: String, streamId: Int, ext: String): String =
        "$server/movie/$user/$pass/$streamId.${sanitizeExt(ext)}"

    fun series(server: String, user: String, pass: String, streamId: Int, ext: String): String =
        "$server/series/$user/$pass/$streamId.${sanitizeExt(ext)}"
}
