package com.ooustream.iptv.common

/**
 * Maps category names to emoji icons for the Aurora Cinema sidebar.
 * Shared by Live TV, Movies, and Series screens.
 */
object CategoryEmoji {

    /** Special categories */
    const val FAVORITES = "\u2764\uFE0F"       // ❤️
    const val RECENTLY_ADDED = "\uD83D\uDD70\uFE0F" // 🕰️
    const val NEW_RELEASES = "\uD83C\uDD95"    // 🆕

    /** Map category name keywords to emoji */
    fun get(categoryName: String): String {
        val lower = categoryName.lowercase()
        return when {
            lower == "favorites" || lower == "favourite" -> FAVORITES
            lower.contains("recently") || lower.contains("recent") -> RECENTLY_ADDED
            lower.contains("new release") -> NEW_RELEASES
            lower.contains("action") -> "\uD83D\uDCA5"      // 💥
            lower.contains("adventure") -> "\uD83D\uDDFA\uFE0F" // 🗺️
            lower.contains("animation") || lower.contains("anime") -> "\uD83C\uDFA8" // 🎨
            lower.contains("comedy") -> "\uD83D\uDE02"       // 😂
            lower.contains("crime") -> "\uD83D\uDD2A"        // 🔪
            lower.contains("documentary") || lower.contains("docu") -> "\uD83D\uDCC4" // 📄
            lower.contains("drama") -> "\uD83C\uDFAD"        // 🎭
            lower.contains("horror") -> "\uD83D\uDC7B"       // 👻
            lower.contains("music") -> "\uD83C\uDFB5"        // 🎵
            lower.contains("romance") || lower.contains("love") -> "\uD83D\uDC95" // 💕
            lower.contains("sci-fi") || lower.contains("science") -> "\uD83D\uDE80" // 🚀
            lower.contains("thriller") || lower.contains("suspense") -> "\uD83D\uDE31" // 😱
            lower.contains("war") || lower.contains("military") -> "\u2694\uFE0F" // ⚔️
            lower.contains("western") -> "\uD83E\uDD20"      // 🤠
            lower.contains("family") -> "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧
            lower.contains("kids") || lower.contains("child") -> "\uD83D\uDC76" // 👶
            lower.contains("sport") -> "\u26BD"               // ⚽
            lower.contains("news") -> "\uD83D\uDCF0"         // 📰
            lower.contains("reality") -> "\uD83D\uDCFA"      // 📺
            lower.contains("mystery") -> "\uD83D\uDD0D"      // 🔍
            lower.contains("fantasy") -> "\uD83E\uDDD9"      // 🧙
            lower.contains("history") || lower.contains("historical") -> "\uD83C\uDFDB\uFE0F" // 🏛️
            lower.contains("biography") || lower.contains("bio") -> "\uD83D\uDCD6" // 📖
            lower.contains("4k") || lower.contains("uhd") -> "\uD83D\uDCFA"  // 📺
            lower.contains("2026") || lower.contains("2025") -> "\uD83D\uDCC5" // 📅
            lower.contains("super bowl") || lower.contains("football") -> "\uD83C\uDFC8" // 🏈
            lower.contains("christmas") || lower.contains("holiday") -> "\uD83C\uDF84" // 🎄
            lower.contains("marvel") || lower.contains("hero") -> "\uD83E\uDDB8" // 🦸
            lower.contains("star wars") -> "\u2B50"           // ⭐
            lower.contains("classic") -> "\uD83C\uDFAC"      // 🎬
            lower.contains("indian") || lower.contains("bollywood") -> "\uD83C\uDDEE\uD83C\uDDF3" // 🇮🇳
            lower.contains("korean") || lower.contains("k-drama") -> "\uD83C\uDDF0\uD83C\uDDF7" // 🇰🇷
            lower.contains("arab") || lower.contains("arabic") -> "\uD83C\uDDF8\uD83C\uDDE6" // 🇸🇦
            lower.contains("spanish") || lower.contains("latino") -> "\uD83C\uDDEA\uD83C\uDDF8" // 🇪🇸
            lower.contains("french") -> "\uD83C\uDDEB\uD83C\uDDF7" // 🇫🇷
            lower.contains("turkish") -> "\uD83C\uDDF9\uD83C\uDDF7" // 🇹🇷
            lower.contains("food") || lower.contains("cooking") -> "\uD83C\uDF73" // 🍳
            lower.contains("travel") -> "\u2708\uFE0F"       // ✈️
            lower.contains("nature") || lower.contains("wildlife") -> "\uD83C\uDF3F" // 🌿
            else -> "\uD83C\uDFAC"                           // 🎬 (default film clapper)
        }
    }
}
