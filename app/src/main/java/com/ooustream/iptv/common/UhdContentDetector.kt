package com.ooustream.iptv.common

/**
 * Detects categories/content likely containing 4K/UHD material based on name patterns.
 * Used to hide 4K content on devices that can't handle it (e.g. 900MB Fire TV Sticks).
 */
object UhdContentDetector {

    private val UHD_PATTERNS = listOf(
        Regex("\\b4K\\b", RegexOption.IGNORE_CASE),
        Regex("\\bUHD\\b", RegexOption.IGNORE_CASE),
        Regex("\\b2160[pP]?\\b"),
        Regex("\\bUltra\\s*HD\\b", RegexOption.IGNORE_CASE)
    )

    fun is4kContent(name: String): Boolean {
        return UHD_PATTERNS.any { it.containsMatchIn(name) }
    }
}
